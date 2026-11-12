#!/usr/bin/env python3
"""
Submit a seed file (generate_seed_data.py) to the running stack as real contribution requests.
Contributors resolve against parties_seed.json first, then the parties bootstrap.canton allocates.

Usage:
    python3 load_seed_data.py                         # all of seed_data.json
    python3 load_seed_data.py --count 100             # first 100 records
    python3 load_seed_data.py --start 250             # resume
    python3 load_seed_data.py --count 20 --dry-run
    python3 load_seed_data.py --file load_failures.json
"""

import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from threading import Lock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from client import OperatorClient, ParticipantClient  # noqa: E402

SEED_DIR = Path(__file__).resolve().parent

CONTRIBUTION_DATA_KEYS = {
    "propertyAddress", "salePrice", "saleDate", "propertyType",
    "squareMeters", "numberOfRooms", "yearBuilt", "energyRating",
    "tags", "additionalInfo",
}


def resolve_contributor(short_name: str, seed_parties: dict) -> str:
    if short_name in seed_parties:
        return seed_parties[short_name]
    try:
        from config import TEST_PARTIES
    except ImportError:
        TEST_PARTIES = {}
    entry = TEST_PARTIES.get(short_name.lower())
    if entry:
        return entry["name"]
    raise KeyError(
        f"Unknown contributor '{short_name}' - not found in the parties file "
        f"or testing/config.py's TEST_PARTIES"
    )


def submit_record(record: dict, party_id: str, participant_index: int) -> tuple:
    client = ParticipantClient(participant_index, party_id)
    contribution_data = {k: v for k, v in record.items() if k in CONTRIBUTION_DATA_KEYS}
    resp = client.create_contribution_request(
        transaction_id=record["transactionId"],
        contribution_data=contribution_data,
        role_in_transaction=record["role"],
    )
    if not resp.success:
        raise RuntimeError(resp.error or "unknown error")
    return record["transactionId"], resp.data.get("contractId")


def wait_until_visible(operator, transaction_id: str, expected_contributors: int,
                       timeout: float = 60.0, interval: float = 0.4) -> int:
    """Wait until the operator has processed the submission. A second contributor arriving
    before that would be treated as the first, so waiting keeps corroboration deterministic.
    Returns the contributor count observed."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        resp = operator.get_contribution_by_transaction(transaction_id)
        if resp.success and resp.data:
            seen = resp.data.get("contributorCount", 0)
            if seen >= expected_contributors:
                return seen
        time.sleep(interval)
    raise TimeoutError(
        f"{transaction_id}: expected {expected_contributors} contributor(s) to become visible "
        f"within {timeout:.0f}s"
    )


def submit_transaction(records: list, resolved_parties: dict, participant_index: int) -> tuple:
    """The contributors of one transaction, each confirmed before the next. Different
    transactions run in parallel."""
    operator = OperatorClient()
    tx_id = records[0]["transactionId"]
    for position, record in enumerate(records, start=1):
        submit_record(record, resolved_parties[record["contributor"]], participant_index)
        wait_until_visible(operator, tx_id, position)
    return tx_id, len(records)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--file", default=str(SEED_DIR / "seed_data.json"), help="Seed data JSON file (default: seed_data.json)")
    parser.add_argument("--parties-file", default=str(SEED_DIR / "parties_seed.json"), help="Party pool JSON from allocate_seed_parties.py (default: parties_seed.json)")
    parser.add_argument("--count", type=int, default=None, help="Only load this many TRANSACTIONS (default: all); a transaction's contributors always load together")
    parser.add_argument("--start", type=int, default=0, help="Skip this many transactions first, for resuming a partial run (default: 0)")
    parser.add_argument("--participant", "-p", type=int, default=1, help="Participant stack index to submit through (default: 1)")
    parser.add_argument("--workers", type=int, default=4, help="Concurrent submissions (default: 4)")
    parser.add_argument("--dry-run", action="store_true", help="Print what would be submitted, make no network calls")
    parser.add_argument("--failures-out", default=str(SEED_DIR / "load_failures.json"), help="Where to write failed records for retry (default: load_failures.json)")
    args = parser.parse_args()

    with open(args.file) as f:
        all_records = json.load(f)

    # slice by transaction, so no sale is loaded with only some of its contributors
    ordered_tx = []
    by_tx = {}
    for record in all_records:
        tx = record["transactionId"]
        if tx not in by_tx:
            by_tx[tx] = []
            ordered_tx.append(tx)
        by_tx[tx].append(record)

    end = args.start + args.count if args.count is not None else None
    records = [r for tx in ordered_tx[args.start:end] for r in by_tx[tx]]

    if not records:
        print(f"No transactions to load (file has {len(ordered_tx)}, "
              f"start={args.start}, count={args.count})")
        return

    try:
        with open(args.parties_file) as f:
            seed_parties = json.load(f)
    except FileNotFoundError:
        print(f"⚠ {args.parties_file} not found - only the bootstrap parties (Alice/Bob/Charlie/Diana) will resolve.")
        print("  Run allocate_seed_parties.py first if your seed data uses SeedNNN contributors.")
        seed_parties = {}

    # resolve every contributor first, so a bad reference fails before any network call
    resolved = []
    for record in records:
        party_id = resolve_contributor(record["contributor"], seed_parties)
        resolved.append((record, party_id))

    if args.dry_run:
        print(f"Would submit {len(resolved)} contribution(s) via participant #{args.participant}:")
        for record, party_id in resolved[:5]:
            print(f"  {record['transactionId']}: {record['contributor']} ({party_id}) as {record['role']}")
        if len(resolved) > 5:
            print(f"  ... and {len(resolved) - 5} more")
        return

    groups = {}
    for record, party_id in resolved:
        groups.setdefault(record["transactionId"], []).append(record)
    party_ids = {record["contributor"]: party_id for record, party_id in resolved}

    multi = sum(1 for g in groups.values() if len(g) > 1)
    print(f"Submitting {len(resolved)} contribution(s) across {len(groups)} transaction(s) "
          f"({multi} corroborated by more than one party) "
          f"via participant #{args.participant} with {args.workers} worker(s)...")

    succeeded = 0
    failed = []
    print_lock = Lock()
    start_time = time.monotonic()
    progress_every = max(1, len(groups) // 20)

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {
            pool.submit(submit_transaction, records, party_ids, args.participant): records
            for records in groups.values()
        }
        done_count = 0
        for future in as_completed(futures):
            records = futures[future]
            done_count += 1
            try:
                _, n = future.result()
                succeeded += n
            except Exception as e:
                failed.extend({"record": r, "error": str(e)} for r in records)

            if done_count % progress_every == 0 or done_count == len(groups):
                with print_lock:
                    elapsed = time.monotonic() - start_time
                    print(f"  {done_count}/{len(groups)} transactions "
                          f"({succeeded} contributions ok, {len(failed)} failed, {elapsed:.1f}s)")

    elapsed = time.monotonic() - start_time
    print(f"\nDone in {elapsed:.1f}s: {succeeded} contribution(s) succeeded, {len(failed)} failed")

    if failed:
        with open(args.failures_out, "w") as f:
            json.dump([f["record"] for f in failed], f, indent=2)
        print(f"Failed records written to {args.failures_out} (retry with --file {args.failures_out})")
        print("First few errors:")
        for f in failed[:5]:
            print(f"  {f['record']['transactionId']}: {f['error']}")


if __name__ == "__main__":
    main()
