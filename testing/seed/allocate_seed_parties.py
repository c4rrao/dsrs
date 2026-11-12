#!/usr/bin/env python3
"""
Allocate a pool of contributor parties on the running Canton node for seed loading, through
`daml ledger allocate-parties` (idempotent). Runs daml from daml/ so the pinned SDK version
talks to Canton. Writes parties_seed.json, which load_seed_data.py reads.

Usage:
    python3 allocate_seed_parties.py --pool-size 30
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DAML_DIR = REPO_ROOT / "daml"

PARTY_LINE_RE = re.compile(r"party = '([^']+)'")


def run_daml_ledger(args: list, host: str, port: int) -> str:
    cmd = ["daml", "ledger"] + args + ["--host", host, "--port", str(port)]
    result = subprocess.run(cmd, cwd=DAML_DIR, capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stdout)
        print(result.stderr, file=sys.stderr)
        raise SystemExit(f"Command failed: {' '.join(cmd)}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pool-size", type=int, default=30, help="Number of contributor parties to allocate (default: 30) - must match generate_seed_data.py's --pool-size")
    parser.add_argument("--prefix", default="Seed", help="Party hint prefix (default: Seed)")
    parser.add_argument("--host", default="localhost", help="Canton ledger API host (default: localhost)")
    parser.add_argument("--port", type=int, default=6865, help="Canton ledger API port (default: 6865)")
    parser.add_argument("--out", default="parties_seed.json", help="Output file path (default: parties_seed.json)")
    args = parser.parse_args()

    if not DAML_DIR.exists():
        raise SystemExit(f"Expected daml/ directory at {DAML_DIR}, not found")

    hints = [f"{args.prefix}{i:03d}" for i in range(1, args.pool_size + 1)]

    print(f"Allocating {len(hints)} parties ({hints[0]}..{hints[-1]}) on {args.host}:{args.port}...")
    run_daml_ledger(["allocate-parties"] + hints, args.host, args.port)

    print("Reading back full party IDs...")
    listing = run_daml_ledger(["list-parties"], args.host, args.port)

    hint_set = set(hints)
    resolved = {}
    for line in listing.splitlines():
        match = PARTY_LINE_RE.search(line)
        if not match:
            continue
        full_id = match.group(1)
        short_name = full_id.split("::", 1)[0]
        if short_name in hint_set:
            resolved[short_name] = full_id

    missing = hint_set - resolved.keys()
    if missing:
        raise SystemExit(f"Allocation reported success but {len(missing)} parties are missing from list-parties: {sorted(missing)}")

    with open(args.out, "w") as f:
        json.dump(resolved, f, indent=2, sort_keys=True)

    print(f"✓ Wrote {len(resolved)} party IDs -> {args.out}")


if __name__ == "__main__":
    main()
