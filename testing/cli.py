#!/usr/bin/env python3
"""
DSRS CLI - Data Sharing Reward System Command Line Interface

Usage:
    python cli.py <group> <command> [args]

Groups:
    holding      - Token/holding management (mint, topup, transfer, merge, offers, accept, reject)
    contribution - Contribution management
    dispute      - Dispute management (list/vote/propose-data as a participant; show as admin)
    reward       - Reward groups you were paid by, and the ids needed to audit them
    query        - Query management
    audit        - On-ledger audit verification workflow, plus direct auditor-stack checks
    system       - System/admin commands (health, state, log, resync, pricing)

Run `python cli.py <group> --help` or `python cli.py <group> <command> --help` for every
argument.

Examples:
    # Mint and manage tokens
    python cli.py holding mint Charlie --amount 1000e
    python cli.py holding offers Charlie --cid
    python cli.py holding accept Charlie <cid>
    python cli.py holding list Alice
    python cli.py holding transfer Alice <holding_cid> Bob --amount 10e
    python cli.py holding merge Alice <holding_cid> <other_holding_cid>

    # Create and view contributions
    python cli.py contribution create Alice Buyer TX-001 --city Lisbon --price 350000
    python cli.py contribution list Alice
    python cli.py contribution list --all       # Operator view
    python cli.py contribution requests Alice
    python cli.py contribution withdraw Alice <request_cid>

    # Handle disputes
    python cli.py dispute list Alice --cid
    python cli.py dispute list --all            # Operator view
    python cli.py dispute detail Alice <cid>    # Full detail incl. all data options + my vote
    python cli.py dispute vote Alice <cid> 0
    python cli.py dispute propose-data Alice <cid> --from-option 0 --price 495000
    python cli.py dispute show <cid>            # Operator/admin view

    # Query workflow
    python cli.py query create Charlie --level 1 --city Lisbon
    python cli.py query proposals Charlie --cid
    python cli.py query accept Charlie <proposal_cid> <holding_cid>
    python cli.py query results Charlie
    python cli.py query funded --all            # Operator view

    # On-ledger audit verification workflow (AuditRequest -> ... -> AuditResult)
    python cli.py audit request Charlie PricingVerification --proposal-id <id>
    python cli.py audit proposals Charlie --cid
    python cli.py audit fund Charlie <proposal_cid> <audit_token_holding_cid>
    python cli.py audit results Charlie

    # Independent auditor-stack checks (separate from the on-ledger workflow above)
    python cli.py audit verify-pricing --proposal-id <id>
    python cli.py audit sync-status

    # System commands
    python cli.py system health
    python cli.py system state
    python cli.py system log
    python cli.py system pricing
"""

import json
import argparse
from client import ParticipantClient, OperatorClient, AuditorClient
from config import TEST_PARTIES, OPERATOR_PARTY, AUDITOR_PARTY, ESCROW_PARTY

# System parties resolve by name like the test parties, so `holding list Operator` shows the
# fee cuts too. The dev JWT secret signs for any party, and they all live on the one node.
SYSTEM_PARTIES = {
    "operator": OPERATOR_PARTY,
    "auditor": AUDITOR_PARTY,
    "escrow": ESCROW_PARTY,
}


def resolve_party(party_name: str) -> str:
    """Resolve short party name to full Canton party ID."""
    party_key = party_name.lower()
    if party_key in SYSTEM_PARTIES:
        return SYSTEM_PARTIES[party_key]
    if party_key not in TEST_PARTIES:
        available = ", ".join(list(TEST_PARTIES.keys()) + list(SYSTEM_PARTIES.keys()))
        raise ValueError(f"Unknown party '{party_name}'. Available: {available}")
    return TEST_PARTIES[party_key]["name"]


def parse_amount(amount_str):
    """Parse amount string - supports tokens or euros (e.g., '1000e', '1000€', or plain '1000000')."""
    amount_str = str(amount_str).strip()
    if amount_str.endswith('e') or amount_str.endswith('€'):
        euros = float(amount_str[:-1])
        tokens = int(euros * 1_000_000)
        return tokens, euros
    else:
        tokens = int(amount_str)
        euros = tokens / 1_000_000.0
        return tokens, euros


def format_amount(amount, token_type=None):
    """Render a token amount. Only RewardTokens are shown in euros; AuditTokens use the same
    integer scale but are not a currency."""
    if token_type and str(token_type).upper() == "REWARDTOKEN":
        return f"€{amount / 1_000_000.0:.2f} ({amount:,} tokens)"
    return f"{amount:,} tokens"


def cmd_holding_mint(args):
    """Mint tokens to one or more parties."""
    operator = OperatorClient()

    recipients = []
    amounts_display = []
    for party_name, amount_str in zip(args.parties, args.amounts):
        party_id = resolve_party(party_name)
        tokens, euros = parse_amount(amount_str)
        recipients.append({"party": party_id, "amount": tokens})
        amounts_display.append((party_name, tokens, euros))

    print("Minting tokens:")
    for party_name, tokens, euros in amounts_display:
        print(f"  {party_name}: €{euros:.2f} ({tokens:,} tokens)")

    resp = operator.mint_tokens(recipients)
    if resp.success:
        print("\n✓ Tokens minted successfully")
        print(f"  Offer Contracts: {len(resp.data.get('offerContractIds', []))} created")
        print("\nNext step: Recipients must accept their offers")
        for party_name in args.parties:
            print(f"  python cli.py holding offers {party_name} --cid")
            print(f"  python cli.py holding accept {party_name} <cid>")
    else:
        print(f"\n✗ Minting failed: {resp.error}")


def cmd_holding_topup(args):
    """Top up a single consumer's RewardToken balance (entitlement mint, with a payment reference)."""
    operator = OperatorClient()
    party_id = resolve_party(args.party)
    tokens, euros = parse_amount(args.amount)

    print(f"Topping up {args.party}: €{euros:.2f} ({tokens:,} tokens), reference={args.reference}")

    resp = operator.topup_tokens(party_id, tokens, args.reference)
    if resp.success:
        print("\n✓ Top-up minted successfully")
        print(f"  Entitlement: {resp.data.get('entitlementId', 'N/A')}")
        print(f"  Offer Contracts: {resp.data.get('holdingOffers', [])}")
        print(f"\nNext step: python cli.py holding accept {args.party} <cid>")
    else:
        print(f"\n✗ Top-up failed: {resp.error}")


def cmd_holding_offers(args):
    """List holding offers."""
    if args.all:
        print("Error: Operator API does not support listing all holding offers")
        print("Holding offers are party-specific contracts")
        return

    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.list_holding_offers()

    if not resp.success:
        print(f"Error: {resp.error}")
        return

    incoming, outgoing = split_offers(resp.data or [], party_id, token_filter(args))

    if not incoming and not outgoing:
        scope = f" {token_filter(args)}" if token_filter(args) else ""
        print(f"No{scope} holding offers for {args.party}")
        return

    if incoming:
        print(f"\nIncoming - {args.party} can accept these ({len(incoming)}):")
        for offer in incoming:
            tt = offer.get('tokenType', 'N/A')
            print(f"  {tt}: {format_amount(offer.get('amount', 0), tt)}"
                  f"   from {short_party(offer.get('from'))}")
            # a reward payout names the group that paid it, which a reward audit asks for
            if offer.get('reference'):
                print(f"    for: {offer['reference']}")
            if args.cid:
                print(f"    Contract: {offer.get('contractId', 'N/A')}")

    if outgoing:
        print(f"\nOutgoing - sent by {args.party}, waiting on the recipient ({len(outgoing)}):")
        for offer in outgoing:
            tt = offer.get('tokenType', 'N/A')
            print(f"  {tt}: {format_amount(offer.get('amount', 0), tt)}"
                  f"   to {short_party(offer.get('to'))}")
            if args.cid:
                print(f"    Contract: {offer.get('contractId', 'N/A')}")
        print("  (nothing for you to do with these - only the recipient can accept them)")


def cmd_holding_accept(args):
    """Accept a holding offer."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.accept_holding_offer(args.contract_id)

    if resp.success:
        print("✓ Holding offer accepted")
        if args.cid and resp.data.get('contractId'):
            print(f"  New Holding Contract: {resp.data.get('contractId')}")
    else:
        print(f"✗ Failed to accept holding offer: {resp.error}")


def cmd_holding_accept_all(args):
    """Accept every pending holding offer a party has."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)

    listed = client.list_holding_offers()
    if not listed.success:
        print(f"✗ Could not list offers for {args.party}: {listed.error}")
        return

    # only incoming offers; the API also returns the ones this party sent
    offers, outgoing = split_offers(listed.data or [], party_id, token_filter(args))

    if not offers:
        scope = f" {token_filter(args)}" if token_filter(args) else ""
        print(f"No incoming{scope} offers for {args.party}")
        if outgoing:
            print(f"  ({len(outgoing)} outgoing offer(s) are waiting on their recipients)")
        return

    print(f"Accepting {len(offers)} offer(s) for {args.party}:")

    accepted, failed = 0, 0
    for offer in offers:
        amount = offer.get("amount", 0)
        token_type = offer.get("tokenType", "N/A")
        label = f"{token_type}: {format_amount(amount, token_type)}"

        contract_id = offer.get("contractId")
        if not contract_id:
            print(f"  ✗ {label}: offer has no contract id")
            failed += 1
            continue

        resp = client.accept_holding_offer(contract_id)
        if resp.success:
            accepted += 1
            line = f"  ✓ {label}"
            if args.cid:
                line += f"  ->  {(resp.data or {}).get('contractId', 'unknown')}"
            print(line)
        else:
            failed += 1
            print(f"  ✗ {label}: {resp.error}")

    summary = f"\n{accepted} accepted"
    if failed:
        summary += f", {failed} failed"
    print(summary)


def cmd_holding_reject(args):
    """Reject a holding offer - tokens are returned to the sender."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.reject_holding_offer(args.contract_id)

    if resp.success:
        print("✓ Holding offer rejected, tokens returned to sender")
        if args.cid and resp.data.get('contractId'):
            print(f"  Returned Holding Contract: {resp.data.get('contractId')}")
    else:
        print(f"✗ Failed to reject holding offer: {resp.error}")


def cmd_reward_groups(args):
    """List the reward groups that paid this contributor, and the ids needed to audit them."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.reward_groups(args.transaction_id)

    if not resp.success:
        print(f"Error: {resp.error}")
        return

    groups = resp.data or []
    if not groups:
        print(f"No reward groups for {args.party}"
              f"{' on ' + args.transaction_id if args.transaction_id else ''}")
        return

    print(f"\nReward groups paying {args.party} ({len(groups)}):\n")
    for g in groups:
        # a group is archived as it pays, so one still on the ledger has not paid out
        state = "PENDING - not distributed yet" if g.get("pending") else "distributed"
        print(f"  {g.get('groupId', 'N/A')}   [{state}]")
        print(f"    transaction: {g.get('transactionId', 'N/A')}")
        print(f"    query:       {g.get('queryId', 'N/A')}")
        print(f"    paid:        {g.get('distributionDate', 'N/A')}")
        others = [short_party(p) for p in g.get("contributors", []) if p != party_id]
        if others:
            print(f"    alongside:   {', '.join(others)}")
        print()

    print("Audit one of these with:")
    print(f"  python3 cli.py audit request {args.party} RewardVerification \\")
    print(f"      --reward-group-id <groupId>")


def cmd_holding_transfer(args):
    """Transfer a holding (or part of it) to another party. Creates a HoldingOffer for them to accept."""
    party_id = resolve_party(args.party)
    recipient_id = resolve_party(args.recipient)
    client = ParticipantClient(args.participant, party_id)

    amount = None
    if args.amount:
        amount, _ = parse_amount(args.amount)

    resp = client.transfer_holding(args.contract_id, recipient_id, amount, args.reference)
    if resp.success:
        print(f"✓ Transfer initiated to {args.recipient}")
        if args.cid and resp.data.get('contractId'):
            print(f"  Offer Contract: {resp.data.get('contractId')}")
        print(f"  Next step: python cli.py holding accept {args.recipient} <cid>")
    else:
        print(f"✗ Transfer failed: {resp.error}")


def cmd_holding_merge(args):
    """Merge other holdings of the same token type into one holding."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.merge_holdings(args.contract_id, args.other_contract_ids)
    if resp.success:
        print("✓ Holdings merged")
        if args.cid and resp.data.get('contractId'):
            print(f"  Merged Contract: {resp.data.get('contractId')}")
    else:
        print(f"✗ Merge failed: {resp.error}")


def cmd_holding_merge_all(args):
    """Merge a party's holdings down to one per token type. Every payout and partial spend
    leaves another fragment behind."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    wanted = token_filter(args)

    resp = client.list_holdings()
    if not resp.success:
        print(f"✗ Could not list holdings for {args.party}: {resp.error}")
        return

    # locked holdings back a funded query and cannot be merged
    by_type = {}
    skipped_locked = 0
    for h in (resp.data or []):
        if not matches_token(h, wanted):
            continue
        if h.get("locked"):
            skipped_locked += 1
            continue
        by_type.setdefault(str(h.get("tokenType", "?")).upper(), []).append(h)

    if not by_type:
        scope = f" {wanted}" if wanted else ""
        print(f"No unlocked{scope} holdings for {args.party}")
        if skipped_locked:
            print(f"  ({skipped_locked} locked holding(s) skipped)")
        return

    merged_any = False
    for token_type, holdings in sorted(by_type.items()):
        if len(holdings) < 2:
            print(f"{token_type}: already a single holding, nothing to merge")
            continue

        holdings.sort(key=lambda h: h.get("amount", 0), reverse=True)
        primary, others = holdings[0], holdings[1:]
        total = sum(h.get("amount", 0) for h in holdings)

        print(f"{token_type}: merging {len(holdings)} holdings -> {format_amount(total, token_type)}")
        result = client.merge_holdings(primary["contractId"], [h["contractId"] for h in others])
        if result.success:
            merged_any = True
            line = f"  ✓ {len(others)} merged in"
            if args.cid:
                line += f"  ->  {(result.data or {}).get('contractId', 'unknown')}"
            print(line)
        else:
            print(f"  ✗ {result.error}")

    if skipped_locked:
        print(f"\n{skipped_locked} locked holding(s) left alone - they are collateral for a funded query")
    if not merged_any and skipped_locked == 0:
        print("\nNothing needed merging")


def cmd_holding_list(args):
    """List token holdings."""
    if args.all:
        print("Error: Operator API does not support listing all holdings")
        print("Holdings are party-specific contracts. Use Navigator to view all holdings.")
        return

    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.list_holdings()

    if resp.success:
        if not resp.data:
            print(f"No holdings for {args.party}")
        else:
            wanted = token_filter(args)
            shown = [h for h in resp.data if matches_token(h, wanted)]
            if not shown:
                print(f"No {wanted} holdings for {args.party}")
            for h in shown:
                amount = h['amount']
                lock = " (LOCKED)" if h.get("locked") else ""
                print(f"  {h['tokenType']}: {format_amount(amount, h['tokenType'])}{lock}")
                if args.cid:
                    print(f"    Contract: {h['contractId']}")
    else:
        print(f"Error: {resp.error}")
        return

    # Minted tokens arrive as HoldingOffers and only show above once accepted.
    offers_resp = client.list_holding_offers()
    if offers_resp.success and offers_resp.data:
        incoming, outgoing = split_offers(offers_resp.data, party_id, token_filter(args))
        if incoming:
            print(f"\n⚠ {len(incoming)} incoming offer(s) not yet accepted:")
            for offer in incoming:
                token_type = offer.get('tokenType', 'N/A')
                print(f"  {token_type}: {format_amount(offer.get('amount', 0), token_type)}"
                      f"   from {short_party(offer.get('from'))}")
                if args.cid:
                    print(f"    Offer Contract: {offer.get('contractId', 'N/A')}")
            print(f"  Run: python3 cli.py holding accept-all {args.party}")
        if outgoing:
            # the escrow and operator are the sender of every reward transfer, nothing to accept
            print(f"\n{len(outgoing)} outgoing offer(s) sent by {args.party}, awaiting their recipients")


def cmd_contribution_create(args):
    """Create a contribution request."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)

    data = {}
    if args.city or args.street or args.postal_code:
        data["propertyAddress"] = {}
        if args.city:
            data["propertyAddress"]["city"] = args.city
        if args.street:
            data["propertyAddress"]["street"] = args.street
        if args.postal_code:
            data["propertyAddress"]["postalCode"] = args.postal_code
        data["propertyAddress"]["country"] = args.country or "Portugal"

    if args.price:
        data["salePrice"] = args.price
    if args.date:
        data["saleDate"] = args.date
    if args.type:
        data["propertyType"] = args.type
    if args.sqm:
        data["squareMeters"] = args.sqm
    if args.rooms:
        data["numberOfRooms"] = args.rooms
    if args.year:
        data["yearBuilt"] = args.year
    if args.energy:
        data["energyRating"] = args.energy

    print("Creating contribution request:")
    print(f"  Party: {args.party} ({args.role})")
    print(f"  Transaction: {args.transaction_id}")
    print(f"  Data: {json.dumps(data, indent=4)}")

    resp = client.create_contribution_request(
        transaction_id=args.transaction_id,
        contribution_data=data,
        role_in_transaction=args.role
    )

    if resp.success:
        print(f"\n✓ Created")
        if args.cid:
            print(f"  Contract: {resp.data.get('contractId', 'unknown')}")
    else:
        print(f"\n✗ Failed: {resp.error}")


def cmd_contribution_list(args):
    """List contributions."""
    if args.all:
        operator = OperatorClient()
        resp = operator.list_contributions()
        if resp.success:
            if not resp.data:
                print("No contributions yet")
                return
            for c in resp.data:
                print(f"\n{c['contributionId']}")
                print(f"  TX: {c['transactionId']}")
                print(f"  Trust Score: {c['totalTrustScore']}")
                print(f"  Contributors: {c['contributorCount']}")
                print(f"  Disputes: {c.get('disputeCount', 0)}")
                if c.get('propertyCity'):
                    print(f"  City: {c['propertyCity']}")
                if c.get('salePrice'):
                    print(f"  Price: {c['salePrice']}")
                if args.cid:
                    print(f"  Contract: {c.get('contractId', 'N/A')}")
        else:
            print(f"Error: {resp.error}")
    else:
        party_id = resolve_party(args.party)
        client = ParticipantClient(args.participant, party_id)
        resp = client.list_contributions()
        if resp.success:
            if not resp.data:
                print(f"No contributions for {args.party}")
                return
            for c in resp.data:
                print(f"\n{c['contributionId']}")
                print(f"  TX: {c['transactionId']}")
                print(f"  Trust Score: {c['trustScore']}")
                print(f"  Contributors: {', '.join(c['contributors'])}")
                if args.cid:
                    print(f"  Contract: {c['contractId']}")
        else:
            print(f"Error: {resp.error}")


def cmd_contribution_requests(args):
    """List contribution requests."""
    if args.all:
        operator = OperatorClient()
        resp = operator.list_contribution_requests()
        if resp.success:
            if not resp.data:
                print("No contribution requests")
                return
            for r in resp.data:
                print(f"\n{r.get('requestId', 'N/A')}")
                print(f"  TX: {r.get('transactionId', 'N/A')}")
                print(f"  Contributor: {r.get('contributor', 'N/A')}")
                print(f"  Requested: {r.get('requestDate', 'N/A')}")
                if args.cid:
                    print(f"  Contract: {r.get('contractId', 'N/A')}")
        else:
            print(f"Error: {resp.error}")
    else:
        party_id = resolve_party(args.party)
        client = ParticipantClient(args.participant, party_id)
        resp = client.list_contribution_requests()

        if resp.success:
            if not resp.data:
                print(f"No pending requests for {args.party}")
                return
            for r in resp.data:
                print(f"\n{r['requestId']}")
                print(f"  TX: {r['transactionId']}")
                if args.cid:
                    print(f"  Contract: {r['contractId']}")
        else:
            print(f"Error: {resp.error}")


def cmd_contribution_withdraw(args):
    """Withdraw a pending contribution request."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.withdraw_contribution_request(args.contract_id)
    if resp.success:
        print("✓ Contribution request withdrawn")
    else:
        print(f"✗ Failed: {resp.error}")


def cmd_dispute_list(args):
    """List disputes."""
    if args.all:
        operator = OperatorClient()
        resp = operator.list_pending_disputes() if args.pending else operator.list_disputes()
    else:
        party_id = resolve_party(args.party)
        client = ParticipantClient(args.participant, party_id)
        resp = client.list_disputes()

    if resp.success:
        if not resp.data:
            print("No disputes found")
            return
        for d in resp.data:
            status = "✓ RESOLVED" if d.get("isUnanimous") else "○ PENDING"
            print(f"\n[{status}] {d.get('disputeId', 'N/A')}")
            print(f"  TX: {d['transactionId']}")
            print(f"  Reason: {d.get('reason', 'N/A')}")
            print(f"  Votes: {d.get('votesCount', 0)}/{d.get('contributorsCount', 0)}")
            if args.cid:
                print(f"  Contract: {d['contractId']}")
    else:
        print(f"Error: {resp.error}")


def cmd_dispute_show(args):
    """Show full detail for a dispute (admin/operator view)."""
    operator = OperatorClient()
    resp = operator.get_dispute(args.contract_id)

    if resp.success:
        d = resp.data
        status = "✓ RESOLVED" if d.get("isUnanimous") else "○ PENDING"
        print(f"[{status}] {d.get('disputeId', 'N/A')}")
        print(f"  TX: {d.get('transactionId', 'N/A')}")
        print(f"  Disputed Contribution: {d.get('disputedContributionId', 'N/A')}")
        print(f"  Reason: {d.get('reason', 'N/A')}")
        print(f"  Data versions: {d.get('allDataCount', 0)}")
        print(f"  Contributors: {d.get('contributorsCount', 0)}")
        votes = d.get("votes") or {}
        if votes:
            print("  Votes:")
            for voter, index in votes.items():
                print(f"    {voter} -> data index {index}")
        else:
            print("  Votes: none yet")
    else:
        print(f"Error: {resp.error}")


def cmd_dispute_vote(args):
    """Vote on a dispute."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.vote_on_dispute(args.contract_id, args.vote_index)

    if resp.success:
        d = resp.data or {}
        print(f"✓ Vote submitted for data index {args.vote_index}")
        # Vote is consuming, so the next vote needs the new contract id
        if d.get('resolved'):
            print(f"  Dispute RESOLVED → Contribution {d.get('contractId', 'unknown')}")
        else:
            print(f"  Voting continues - new dispute contract: {d.get('contractId', 'unknown')}")
    else:
        print(f"✗ Vote failed: {resp.error}")


def cmd_dispute_vote_group(args):
    """Vote as several parties for the same option, chaining the contract id that each vote
    replaces."""
    parties = [p.strip() for p in args.parties.split(",") if p.strip()]
    if not parties:
        print("✗ No parties given")
        return

    contract_id = args.contract_id
    print(f"Voting for option {args.vote_index} as: {', '.join(parties)}")

    for i, party_name in enumerate(parties):
        try:
            party_id = resolve_party(party_name)
        except ValueError as e:
            print(f"  ✗ {e}")
            return

        client = ParticipantClient(args.participant, party_id)
        resp = client.vote_on_dispute(contract_id, args.vote_index)

        if not resp.success:
            print(f"  ✗ {party_name}: {resp.error}")
            print(f"\nStopped at {party_name}. The dispute is still at contract {contract_id}")
            return

        d = resp.data or {}
        new_cid = d.get('contractId', '')
        if d.get('resolved'):
            print(f"  ✓ {party_name} voted - unanimous, dispute RESOLVED")
            print(f"\nContribution: {new_cid}")
            remaining = parties[i + 1:]
            if remaining:
                # unanimity can be reached before the list runs out
                print(f"  ({', '.join(remaining)} did not need to vote - it resolved first)")
            return

        print(f"  ✓ {party_name} voted - voting continues")
        contract_id = new_cid

    print(f"\nAll {len(parties)} voted; still not unanimous.")
    print(f"  Dispute is now at: {contract_id}")


def cmd_dispute_propose_data(args):
    """Propose a new data alternative in a dispute (existing dispute contributor only)."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)

    data = {}

    # start from an option on the table and override only what differs
    if args.from_option is not None:
        detail = client.get_dispute_detail(args.contract_id)
        if not detail.success:
            print(f"✗ Could not read dispute: {detail.error}")
            return
        options = detail.data.get('allContributionOptions', [])
        if not 0 <= args.from_option < len(options):
            print(f"✗ Option {args.from_option} does not exist (dispute has {len(options)})")
            return
        data = {k: v for k, v in options[args.from_option].items() if v is not None}
        if isinstance(data.get('propertyAddress'), dict):
            data['propertyAddress'] = {
                k: v for k, v in data['propertyAddress'].items() if v is not None
            }

    address = data.get('propertyAddress', {})
    if args.city:
        address['city'] = args.city
    if args.street:
        address['street'] = args.street
    if args.postal_code:
        address['postalCode'] = args.postal_code
    if args.country:
        address['country'] = args.country
    if address:
        data['propertyAddress'] = address

    if args.price is not None:
        data['salePrice'] = args.price
    if args.date:
        data['saleDate'] = args.date
    if args.type:
        data['propertyType'] = args.type
    if args.sqm is not None:
        data['squareMeters'] = args.sqm
    if args.rooms is not None:
        data['numberOfRooms'] = args.rooms
    if args.year is not None:
        data['yearBuilt'] = args.year
    if args.energy:
        data['energyRating'] = args.energy

    if not data:
        print("✗ Nothing proposed - pass --from-option and/or at least one data flag")
        return

    print(f"Proposing new data on dispute {args.contract_id} as {args.party}:")
    print(f"  {json.dumps(data, indent=4)}")

    resp = client.propose_dispute_data(args.contract_id, data)

    if resp.success:
        d = resp.data
        print(f"\n✓ {d.get('message', 'Proposed')}")
        if d.get('resolved'):
            print(f"  Dispute RESOLVED → Contribution {d.get('contractId', 'unknown')}")
        else:
            print(f"  Options on the table: {d.get('optionCount', '?')}")
            print(f"  New dispute contract: {d.get('contractId', 'unknown')}")
        if d.get('deduplicated'):
            print("  (matched an option already on the table - recorded as a vote for it)")
    else:
        print(f"\n✗ Proposal failed: {resp.error}")


def cmd_dispute_detail(args):
    """Show full dispute detail as a participant (all data version options, my own vote)."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.get_dispute_detail(args.contract_id)

    if resp.success:
        d = resp.data
        print(f"{d.get('disputeId', 'N/A')}")
        print(f"  TX: {d.get('transactionId', 'N/A')}")
        print(f"  Reason: {d.get('reason', 'N/A')}")
        print(f"  Votes: {d.get('votesCount', 0)}/{d.get('contributorsCount', 0)}")

        # votedFor resolves the dispute, initialPosition decides winners and losers
        votes = d.get('votesByParty') or []
        if votes:
            print("  Standing:")
            print(f"    {'party':<10} {'voted for':>10}  {'entered on':>10}")
            for v in votes:
                voted = v.get('votedFor')
                initial = v.get('initialPosition')
                marker = "" if voted is not None else "   <- has not voted"
                print(f"    {short_party(v.get('party')):<10} "
                      f"{('option ' + str(voted)) if voted is not None else '-':>10}  "
                      f"{('option ' + str(initial)) if initial is not None else '-':>10}{marker}")
        else:
            my_vote = d.get('myVote')
            print(f"  My Vote: {my_vote if my_vote is not None else 'not yet voted'}")

        for i, option in enumerate(d.get('allContributionOptions', [])):
            tally = [short_party(v.get('party')) for v in votes if v.get('votedFor') == i]
            who = f"   <- {', '.join(tally)}" if tally else ""
            print(f"  Option {i}:{who} {json.dumps(option, indent=4)}")
    else:
        print(f"Error: {resp.error}")


TOKEN_TYPES = ("REWARDTOKEN", "AUDITTOKEN")


def short_party(party):
    """"Alice::1220ab..." -> "Alice"."""
    return str(party or "?").split("::")[0]


def split_offers(offers, party_id, wanted=None):
    """Split offers this party can accept from the ones it only sent."""
    incoming, outgoing = [], []
    for offer in offers:
        if not matches_token(offer, wanted):
            continue
        if offer.get("to") == party_id:
            incoming.append(offer)
        elif offer.get("from") == party_id:
            outgoing.append(offer)
    return incoming, outgoing


def token_filter(args):
    """The --token value, normalised to upper case, or None for 'all types'."""
    value = getattr(args, "token", None)
    return value.upper() if value else None


def matches_token(entry, wanted):
    """True when this holding/offer is of the requested type (or no type was requested)."""
    return wanted is None or str(entry.get("tokenType", "")).upper() == wanted


def add_token_arg(parser, help_text="Restrict to one token type (default: all)"):
    parser.add_argument("--token", choices=[t.lower() for t in TOKEN_TYPES] + list(TOKEN_TYPES),
                        help=help_text)


def _range(lower, upper):
    """A {from, to} filter range, omitting whichever bound was not given."""
    r = {}
    if lower is not None:
        r["from"] = lower
    if upper is not None:
        r["to"] = upper
    return r


def cmd_query_create(args):
    """Create a query request."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)

    params = {
        "level": args.level,
    }

    # every filter changes both the result set and the on-ledger price
    if args.city:
        params["propertyAddress"] = [{"city": args.city}]
    if args.price_min or args.price_max:
        params["salePrice"] = _range(args.price_min, args.price_max)
    if args.trust_min or args.trust_max:
        params["trustScore"] = _range(args.trust_min, args.trust_max)
    if args.sqm_min or args.sqm_max:
        params["squareMeters"] = _range(args.sqm_min, args.sqm_max)
    if args.year_min or args.year_max:
        params["yearBuilt"] = _range(args.year_min, args.year_max)
    if args.sale_from or args.sale_to:
        params["saleDate"] = _range(args.sale_from, args.sale_to)
    if args.rooms:
        params["numberOfRooms"] = [int(r) for r in args.rooms.split(",")]
    if args.types:
        params["propertyTypes"] = args.types.split(",")
    if args.energy:
        params["energyRating"] = args.energy.split(",")
    if args.tags:
        params["tags"] = args.tags.split(",")

    print("Creating query request:")
    print(f"  Party: {args.party}")
    print(f"  Level: {args.level}")
    print(f"  Params: {json.dumps(params, indent=4)}")

    resp = client.create_query_request(params)
    if resp.success:
        print("\n✓ Created")
        if args.cid:
            print(f"  Contract: {resp.data.get('contractId', 'unknown')}")
    else:
        print(f"\n✗ Failed: {resp.error}")


def cmd_query_requests(args):
    """List query requests."""
    if args.all:
        operator = OperatorClient()
        resp = operator.list_query_requests()
        if resp.success:
            if not resp.data:
                print("No query requests")
                return
            for r in resp.data:
                print(f"\n{r.get('requestId', 'N/A')}")
                print(f"  Consumer: {r.get('consumer', 'N/A')}")
                print(f"  Level: {r.get('level', 'N/A')}")
                print(f"  Requested: {r.get('requestDate', 'N/A')}")
                if args.cid:
                    print(f"  Contract: {r.get('contractId', 'N/A')}")
        else:
            print(f"Error: {resp.error}")
    else:
        party_id = resolve_party(args.party)
        client = ParticipantClient(args.participant, party_id)
        resp = client.list_query_requests()

        if resp.success:
            if not resp.data:
                print(f"No query requests for {args.party}")
                return
            for r in resp.data:
                print(f"\n{r.get('requestId', 'N/A')}")
                print(f"  Level: {(r.get('params') or {}).get('level', 'N/A')}")
                print(f"  Requested: {r.get('requestDate', 'N/A')}")
                if args.cid:
                    print(f"  Contract: {r.get('contractId', 'N/A')}")
        else:
            print(f"Error: {resp.error}")


def cmd_query_withdraw(args):
    """Withdraw a pending query request."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.withdraw_query_request(args.contract_id)
    if resp.success:
        print("✓ Query request withdrawn")
    else:
        print(f"✗ Failed: {resp.error}")


def cmd_query_proposals(args):
    """List query proposals."""
    if args.all:
        print("Error: Operator API does not support listing all query proposals")
        print("Query proposals are party-specific contracts sent to data providers")
        return

    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.list_query_proposals()

    if resp.success:
        if not resp.data:
            print(f"No query proposals for {args.party}")
            return
        for p in resp.data:
            price_tokens = p.get('tokenCost', 0)
            price_euros = price_tokens / 1_000_000.0
            print(f"\nProposal: {p.get('proposalId', 'N/A')}")
            print(f"  Price: €{price_euros:.2f} ({price_tokens:,} tokens)")
            print(f"  Query Level: {(p.get('params') or {}).get('level', 'N/A')}")
            if args.cid:
                print(f"  Contract: {p.get('contractId', 'N/A')}")
    else:
        print(f"Error: {resp.error}")


def cmd_query_accept(args):
    """Accept a query proposal."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.accept_query_proposal(args.proposal_cid, args.holding_cid)

    if resp.success:
        print("✓ Query proposal accepted")
        if args.cid and resp.data.get('contractId'):
            print(f"  Contract: {resp.data.get('contractId')}")
    else:
        print(f"✗ Failed to accept proposal: {resp.error}")


def cmd_query_reject(args):
    """Decline a quoted price."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.reject_query_proposal(args.proposal_cid)

    if resp.success:
        print("✓ Query proposal rejected")
        # declining also releases the operator's hold on contribution processing
        print("  Contributions held for this query will be processed shortly.")
    else:
        print(f"✗ Failed to reject proposal: {resp.error}")


def cmd_query_reclaim(args):
    """Take back funds locked for a query the operator never completed."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.reclaim_query_funds(args.contract_id)

    if resp.success:
        print("✓ Locked funds reclaimed")
        if args.cid and resp.data.get('contractId'):
            print(f"  Returned holding: {resp.data.get('contractId')}")
        print("  The refund is a real holding - no offer to accept.")
    else:
        # the deadline is enforced on-ledger
        if "Lock time must have passed" in str(resp.error):
            print("✗ Too early: the funding lock has not expired yet (24h from funding).")
            print("  Until then only the operator can fail the query and refund it.")
        else:
            print(f"✗ Reclaim failed: {resp.error}")


def cmd_query_results(args):
    """List query results."""
    if args.all:
        print("Error: Operator API does not support listing all query results")
        print("Query results are party-specific contracts delivered to consumers")
        return

    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.list_query_results()

    if resp.success:
        if not resp.data:
            print(f"No query results for {args.party}")
            return
        for r in resp.data:
            level = (r.get('params') or {}).get('level', 'N/A')
            print(f"\nResult (Level {level}):")
            if args.cid:
                print(f"  Contract: {r.get('contractId', 'N/A')}")
                # pricing audits take the proposalId, reward audits the queryId
                print(f"  Query ID:    {r.get('queryId', 'N/A')}")
                print(f"  Proposal ID: {r.get('proposalId', 'N/A')}")
            # the data was read at pricing, so this differs from the completion time
            if r.get('executionTimestamp'):
                print(f"  Executed at:  {r['executionTimestamp']}  (data read at pricing time)")
            if r.get('completionDate'):
                print(f"  Completed at: {r['completionDate']}")
            for insight in (r.get('results') or []):
                print(f"  Transactions matched: {insight.get('transactionCount', 'N/A')}")
                if insight.get('avgSalePrice') is not None:
                    print(f"  Avg sale price: €{insight['avgSalePrice']:,.2f}")
                if insight.get('avgTrustScore') is not None:
                    print(f"  Avg trust score: {insight['avgTrustScore']}")
                if insight.get('medianEnergyRating'):
                    print(f"  Median energy rating: {insight['medianEnergyRating']}")
                details = insight.get('details')
                if details:
                    price_range = details.get('priceRange') or {}
                    print(f"  Price range: €{price_range.get('from', '?'):,.2f} - €{price_range.get('to', '?'):,.2f}")
                    if details.get('medianPrice') is not None:
                        print(f"  Median price: €{details['medianPrice']:,.2f}")
                    if details.get('avgContributorsPerTx') is not None:
                        print(f"  Avg contributors/transaction: {details['avgContributorsPerTx']}")
                    if details.get('avgDisputesPerTx') is not None:
                        print(f"  Avg disputes/transaction: {details['avgDisputesPerTx']}")
                    if details.get('disputeRate') is not None:
                        print(f"  Dispute rate: {details['disputeRate'] * 100:.1f}%")
                    for dist_name, label in [("propertyTypeDistribution", "Property types"), ("cityDistribution", "Cities")]:
                        dist = details.get(dist_name)
                        if dist:
                            summary = ", ".join(f"{d['key']}: {d['count']}" for d in dist)
                            print(f"  {label}: {summary}")
    else:
        print(f"Error: {resp.error}")


def cmd_query_funded(args):
    """List funded queries - the consumer's own, or every one with --all (operator view)."""
    if args.all:
        resp = OperatorClient().list_funded_queries()
    elif args.party:
        party_id = resolve_party(args.party)
        resp = ParticipantClient(args.participant, party_id).list_funded_queries()
    else:
        print("Error: give a party name, or --all for the operator view")
        return

    if resp.success:
        if not resp.data:
            print("No funded queries")
            return
        for f in resp.data:
            price_tokens = f.get('tokenCost', 0)
            price_euros = price_tokens / 1_000_000.0
            print(f"\n{f.get('queryId', 'N/A')}")
            print(f"  Consumer: {f.get('consumer', 'N/A')}")
            print(f"  Escrow: {f.get('escrow', 'N/A')}")
            print(f"  Price: €{price_euros:.2f} ({price_tokens:,} tokens)")
            if args.cid:
                print(f"  Contract: {f.get('contractId', 'N/A')}")
    else:
        print(f"Error: {resp.error}")


def cmd_audit_request(args):
    """Create a new audit verification request."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)

    # Inclusion is asked about a transaction over a window: contributors cannot see query ids.
    # Checked here so a mistake costs no audit tokens.
    if args.type == "InclusionVerification":
        if not args.transaction_id:
            print("✗ InclusionVerification needs --transaction-id")
            return
        if args.query_id or args.proposal_id:
            print("✗ InclusionVerification does not take --query-id/--proposal-id.")
            print("  It asks 'which queries used my transaction', so naming one presupposes the answer.")
            print("  Narrow with --from-time/--to-time instead; the default window is all of history.")
            return

    params = {}
    if args.query_id:
        params["queryId"] = args.query_id
    if args.proposal_id:
        params["proposalId"] = args.proposal_id
    if args.reward_group_id:
        params["rewardGroupId"] = args.reward_group_id
    if args.transaction_id:
        params["transactionId"] = args.transaction_id
    if args.from_time:
        params["fromTime"] = args.from_time
    if args.to_time:
        params["toTime"] = args.to_time

    resp = client.create_audit_request(args.type, params)
    if resp.success:
        print("✓ Audit request created. Auditor will propose a price.")
        if args.cid:
            print(f"  Contract: {resp.data.get('contractId', 'unknown')}")
    else:
        print(f"✗ Failed: {resp.error}")


def cmd_audit_requests(args):
    """List my audit requests."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.list_audit_requests()

    if resp.success:
        if not resp.data:
            print(f"No audit requests for {args.party}")
            return
        for r in resp.data:
            print(f"\n{r.get('requestId', 'N/A')}")
            print(f"  Type: {r.get('requestType', 'N/A')}")
            if args.cid:
                print(f"  Contract: {r.get('contractId', 'N/A')}")
    else:
        print(f"Error: {resp.error}")


def cmd_audit_cancel(args):
    """Cancel an audit request before it's been proposed on."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.cancel_audit_request(args.request_id)
    if resp.success:
        print("✓ Audit request cancelled")
    else:
        print(f"✗ Failed: {resp.error}")


def cmd_audit_proposals(args):
    """List audit proposals (auditor's price quotes for my requests)."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.list_audit_proposals()

    if resp.success:
        if not resp.data:
            print(f"No audit proposals for {args.party}")
            return
        for p in resp.data:
            cost_tokens = p.get('estimatedCost', 0)
            print(f"\n{p.get('requestId', 'N/A')}")
            print(f"  Type: {p.get('requestType', 'N/A')}")
            print(f"  Estimated Cost: {cost_tokens:,} audit tokens")
            if args.cid:
                print(f"  Contract: {p.get('contractId', 'N/A')}")
    else:
        print(f"Error: {resp.error}")


def cmd_audit_fund(args):
    """Fund an audit proposal with audit tokens, triggering the verification."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.fund_audit_proposal(args.proposal_cid, args.holding_cid)
    if resp.success:
        print("✓ Audit proposal funded. Auditor will now perform verification.")
        if args.cid:
            print(f"  Contract: {resp.data.get('contractId', 'unknown')}")
    else:
        print(f"✗ Failed to fund audit proposal: {resp.error}")


def cmd_audit_reject(args):
    """Reject an audit proposal."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.reject_audit_proposal(args.proposal_cid)
    if resp.success:
        print("✓ Audit proposal rejected")
    else:
        print(f"✗ Failed: {resp.error}")


def cmd_audit_results(args):
    """List my completed audit verification results."""
    party_id = resolve_party(args.party)
    client = ParticipantClient(args.participant, party_id)
    resp = client.list_audit_results()

    if resp.success:
        if not resp.data:
            print(f"No audit results for {args.party}")
            return
        for r in resp.data:
            match = "✓ MATCH" if r.get("matches") else "✗ MISMATCH"
            print(f"\n[{match}] {r.get('requestId', 'N/A')} ({r.get('verificationType', 'N/A')})")
            print(f"  Expected: {r.get('expected', 0):,}  Actual: {r.get('actual', 0):,}")
            # the participant stack compares the stored statistics of both sides
            if r.get("statisticsChecked"):
                agree = "✓ agree" if r.get("statisticsAgree") else "✗ DISAGREE"
                print(f"  Statistics: {agree} (delivered vs auditor's own, compared from the stored lists)")
            print(f"  Details: {r.get('details', 'N/A')}")
            print(f"  Tokens Burned: {r.get('tokensBurned', 0):,}")
            if args.cid:
                print(f"  Contract: {r.get('contractId', 'N/A')}")
    else:
        print(f"Error: {resp.error}")


def cmd_audit_verify_pricing(args):
    """Independently recompute a query's price and compare to what was charged (auditor-stack)."""
    if not args.query_id and not args.proposal_id:
        print("✗ Provide --query-id or --proposal-id")
        return
    auditor = AuditorClient()
    resp = auditor.verify_pricing(query_id=args.query_id, proposal_id=args.proposal_id)
    if resp.success:
        d = resp.data
        match = "✓ MATCH" if d.get("matches") else "✗ MISMATCH"
        print(f"[{match}]")
        print(f"  Expected: {d.get('expected', 0):,}")
        print(f"  Actual:   {d.get('actual', 0):,}")
        print(f"  Price:      {'✓ agrees' if d.get('priceMatches', d.get('matches')) else '✗ DISAGREES'}")
        if d.get("resultChecked"):
            if d.get("resultMatches"):
                print("  Statistics: ✓ every delivered statistic agrees with the auditor's own")
            else:
                print("  Statistics: ✗ the delivered statistics DISAGREE with the auditor's own")
                for disc in d.get("resultDiscrepancies", []):
                    print(f"      - {disc.get('field')}: "
                          f"delivered={disc.get('delivered')} derived={disc.get('derived')}")
        else:
            print("  Statistics: not checked (the query has not completed, so nothing was delivered yet)")
        if d.get("pricingConfigId"):
            print(f"  PricingConfig: {d.get('pricingConfigId')}")

        # derived from the auditor's own replay of the filter, not from the operator's numbers
        derived = d.get("derivedNumTransactions")
        if derived is not None:
            print("\n  Independent re-execution of the query:")
            print(f"    Replayed at:                 {d.get('replayedAt', 'n/a')}"
                  f"{'  (APPROXIMATE - query not completed yet)' if d.get('replayTimeApproximate') else ''}")
            print(f"    Transactions operator claimed: {d.get('claimedNumTransactions', 0):,}")
            print(f"    Transactions auditor matched:  {derived:,}")
            if d.get("metadataMatches"):
                print("    Pricing metadata:            ✓ every field agrees with the operator")
            else:
                print("    Pricing metadata:            ✗ DISAGREES with the operator")
                for disc in d.get("metadataDiscrepancies", []):
                    print(f"      - {disc.get('field')}: "
                          f"claimed={disc.get('claimed')} derived={disc.get('derived')}")
    else:
        print(f"Error: {resp.error}")


def cmd_audit_verify_reward(args):
    """Recompute the requester's own reward share and compare to the actual distribution (auditor-stack)."""
    auditor = AuditorClient()
    requester = resolve_party(args.party)
    resp = auditor.verify_reward(args.query_id, args.transaction_id, requester)
    if resp.success:
        d = resp.data
        match = "\u2713 MATCH" if d.get("matches") else "\u2717 MISMATCH"
        print(f"[{match}]")

        # only the requester's own pair; everything else the auditor derived stays with it
        c = d.get("contributorResult") or {}
        c_match = "\u2713" if c.get("matches") else "\u2717"
        print(f"\n  [{c_match}] {args.party}'s share of {d.get('transactionId', 'n/a')}")
        print(f"    Owed:     {c.get('expectedAmount', 0):,}")
        print(f"    Received: {c.get('actualAmount', 0):,}")

        print("\n  Links behind that figure:")
        for label, key in (("the query paid out the whole pool its price left", "queryTotalMatches"),
                           ("this transaction's group was funded correctly", "groupTotalMatches"),
                           ("the group total was split correctly", "withinGroupMatches")):
            v = d.get(key)
            flag = "-" if v is None else ("\u2713" if v else "\u2717")
            suffix = " (not checked)" if v is None else ""
            print(f"    [{flag}] {label}{suffix}")

        for mode in d.get("failureModes", []):
            print(f"\n  \u2717 {mode}")
    else:
        print(f"Error: {resp.error}")


def cmd_audit_verify_inclusion(args):
    """Check which queries used a given contribution over a time window (auditor-stack)."""
    auditor = AuditorClient()
    resp = auditor.verify_inclusion(args.transaction_id, args.from_time, args.to_time)
    if resp.success:
        print(json.dumps(resp.data, indent=2))
    else:
        print(f"Error: {resp.error}")


def cmd_audit_mints(args):
    """Show every mint the auditor observed, and whether an event justified it."""
    auditor = AuditorClient()
    resp = auditor.observed_mints()
    if not resp.success:
        print(f"Error: {resp.error}")
        return

    d = resp.data
    print(f"Mints observed: {d['observed']}")
    for token, amount in sorted(d["mintedByTokenType"].items()):
        print(f"  {token:<12} {amount:>15,} minted")
    print()
    if d["anomalous"]:
        print(f"  ⚠  {d['anomalous']} audit-token mint(s) with NO event behind them")
    else:
        print("  ✓ every audit-token mint had a workflow event behind it")
    print(f"  ({d['note']})")
    print()
    for m in d["mints"]:
        if args.anomalous and not m["anomalous"]:
            continue
        if m["anomalous"]:
            mark = "ANOMALY"
        elif m["accompaniedBy"]:
            mark = "event"
        else:
            mark = "issued"
        origin = m["accompaniedBy"] or "nothing in its transaction"
        print(f"[{mark:>7}] {m['tokenType']:<12} {m['totalAmount']:>15,}")
        print(f"          arrived with: {origin}")
        if m["reason"]:
            print(f"          operator declared: {m['reason']} / {m['sourceEventId']}")


def cmd_audit_sync_status(_args):
    """Show the auditor's ledger sync status and audit statistics."""
    auditor = AuditorClient()
    resp = auditor.health_detailed()
    if resp.success:
        print(json.dumps(resp.data, indent=2))
    else:
        print(f"Error: {resp.error}")


def cmd_system_health(_args):
    """Check service health."""
    print("Checking services...\n")

    operator = OperatorClient()
    resp = operator.health()
    status = "✓ OK" if resp.success else f"✗ {resp.error}"
    print(f"  Operator (8100):        {status}")

    for i in [1, 2]:
        try:
            client = ParticipantClient(i, "test")
            resp = client.health()
            status = "✓ OK" if resp.success else f"✗ {resp.error}"
        except Exception as e:
            status = f"✗ {e}"
        print(f"  Participant #{i} (808{i}): {status}")


def cmd_system_state(_args):
    """Show system state."""
    operator = OperatorClient()
    resp = operator.get_system_state()
    if resp.success:
        print(json.dumps(resp.data, indent=2))
    else:
        print(f"Error: {resp.error}")


def cmd_system_log(args):
    """Show processing log."""
    operator = OperatorClient()
    if args.contract:
        resp = operator.get_processing_log_for_contract(args.contract)
    else:
        resp = operator.get_processing_log(limit=args.limit)
    if resp.success:
        if not resp.data:
            print("No log entries found")
            return
        for log in resp.data:
            status = "✓" if log["status"] == "success" else "✗"
            print(f"[{status}] {log['action']:40}")
            if args.cid:
                print(f"  Contract: {log['contractId']}")
            if log.get("details"):
                print(f"  Details: {log['details']}")
            if log.get("errorMessage"):
                print(f"  Error: {log['errorMessage']}")
    else:
        print(f"Error: {resp.error}")


def cmd_system_resync(_args):
    """Trigger a full resync from the ledger."""
    operator = OperatorClient()
    resp = operator.trigger_resync()
    if resp.success:
        print(f"✓ {resp.data.get('message', 'Resync started')} (status: {resp.data.get('status', 'unknown')})")
    else:
        print(f"✗ Failed to trigger resync: {resp.error}")


def cmd_system_init_pricing(_args):
    """Initialize PricingConfig on the ledger."""
    import requests

    print("Initializing PricingConfig on ledger...")

    try:
        resp = requests.post(
            "http://localhost:8100/api/pricing/config/init",
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer dev-admin-api-key"
            },
            json={
                "basePrice": 10000000,
                "level1Multiplier": "1.0",
                "level2Multiplier": "3.0",
                "coverageWeight": "2.0",
                "coverageSaturation": "5.0",
                "narrownessWeight": "0.5",
                "trustWeight": "4.0",
                "unit": "TOKENS"
            }
        )

        if resp.status_code == 200:
            data = resp.json()
            print(f"✓ PricingConfig initialized")
            print(f"  Contract ID: {data.get('contractId', 'N/A')}")
        elif resp.status_code == 409:
            data = resp.json()
            print(f"ℹ PricingConfig already exists")
            print(f"  {data.get('error', 'Already initialized')}")
        else:
            print(f"✗ Failed: HTTP {resp.status_code}")
            print(f"  {resp.text}")
    except Exception as e:
        print(f"✗ Error: {e}")


def cmd_system_pricing(_args):
    """Show the current PricingConfig."""
    operator = OperatorClient()
    resp = operator.get_pricing_config()
    if resp.success:
        print(json.dumps(resp.data, indent=2))
    else:
        print(f"Error: {resp.error}")


def cmd_system_pricing_update(args):
    """Archive the current PricingConfig and create a new one with the given values."""
    operator = OperatorClient()
    resp = operator.update_pricing_config(
        base_price=args.base_price,
        level1_multiplier=args.level1,
        level2_multiplier=args.level2,
        coverage_weight=args.coverage_weight,
        coverage_saturation=args.coverage_saturation,
        narrowness_weight=args.narrowness_weight,
        trust_weight=args.trust_weight,
        unit=args.unit,
    )
    if resp.success:
        print(f"✓ PricingConfig updated")
        print(f"  New Contract ID: {resp.data.get('contractId', 'N/A')}")
    else:
        print(f"✗ Failed: {resp.error}")


def cmd_system_pricing_simulate(args):
    """Simulate a query price locally (approximation of the on-ledger formula)."""
    operator = OperatorClient()
    selectivities = [float(x) for x in args.selectivity] if args.selectivity else []
    resp = operator.simulate_price({
        "level": args.level,
        "resultCount": args.results,
        "corpusCount": args.corpus,
        "selectivities": selectivities,
        "trustQuality": args.trust_quality,
    })
    if resp.success:
        price_tokens = resp.data.get("calculatedPrice", 0)
        price_euros = price_tokens / 1_000_000.0
        print(f"Simulated Price: €{price_euros:.2f} ({price_tokens:,} tokens)")
        print(json.dumps(resp.data.get("breakdown", {}), indent=2))
    else:
        print(f"Error: {resp.error}")


# ============================================================
# MAIN ARGUMENT PARSER

def main():
    parser = argparse.ArgumentParser(
        description="DSRS CLI - Data Sharing Reward System",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )

    subparsers = parser.add_subparsers(dest="group", help="Command group")

    holding_parser = subparsers.add_parser("holding", help="Token/holding management")
    holding_sub = holding_parser.add_subparsers(dest="command", help="Holding command")

    p = holding_sub.add_parser("mint", help="Mint tokens (admin only)")
    p.add_argument("parties", nargs="+", help="Party names")
    p.add_argument("--amounts", "-a", nargs="+", help="Amounts (e.g., 1000e 500e)")
    p.add_argument("--amount", default="1000e", help="Default amount (default: 1000e)")

    p = holding_sub.add_parser("topup", help="Top up a consumer's RewardToken balance (admin only)")
    p.add_argument("party", help="Party name")
    p.add_argument("--amount", default="1000e", help="Amount (e.g., 1000e)")
    p.add_argument("--reference", required=True, help="External payment reference (audit trail)")

    p = holding_sub.add_parser("offers", help="List holding offers")
    p.add_argument("party", nargs="?", help="Party name (optional with --all)")
    p.add_argument("--all", action="store_true", help="Show all offers (operator view)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    add_token_arg(p)
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = holding_sub.add_parser("accept", help="Accept a holding offer")
    p.add_argument("party", help="Party name")
    p.add_argument("contract_id", help="Offer contract ID")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show result contract ID")

    p = holding_sub.add_parser("accept-all", help="Accept every pending holding offer for a party")
    p.add_argument("party", help="Party name")
    add_token_arg(p, "Only accept offers of this token type (default: all)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show each resulting holding contract ID")

    p = holding_sub.add_parser("reject", help="Reject a holding offer (tokens return to sender)")
    p.add_argument("party", help="Party name")
    p.add_argument("contract_id", help="Offer contract ID")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show result contract ID")

    p = holding_sub.add_parser("list", help="List token holdings")
    p.add_argument("party", nargs="?", help="Party name (optional with --all)")
    p.add_argument("--all", action="store_true", help="Show all holdings (operator view)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    add_token_arg(p)
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = holding_sub.add_parser("transfer", help="Transfer a holding to another party")
    p.add_argument("party", help="Party name (sender)")
    p.add_argument("contract_id", help="Holding contract ID")
    p.add_argument("recipient", help="Recipient party name")
    p.add_argument("--amount", help="Amount to transfer (e.g., 100e); omit to transfer the whole holding")
    p.add_argument("--reference", help="Free-text note carried on the offer, shown to the recipient when they accept")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show result contract ID")

    p = holding_sub.add_parser("merge-all", help="Merge a party's holdings down to one per token type")
    p.add_argument("party", help="Party name")
    add_token_arg(p, "Only merge this token type (default: both, each into its own holding)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show each resulting holding contract ID")

    p = holding_sub.add_parser("merge", help="Merge other holdings into one")
    p.add_argument("party", help="Party name")
    p.add_argument("contract_id", help="Primary holding contract ID (kept, absorbs the others)")
    p.add_argument("other_contract_ids", nargs="+", help="Other holding contract IDs to merge in")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show result contract ID")

    contrib_parser = subparsers.add_parser("contribution", help="Contribution management")
    contrib_sub = contrib_parser.add_subparsers(dest="command", help="Contribution command")

    p = contrib_sub.add_parser("create", help="Create contribution request")
    p.add_argument("party", help="Party name")
    p.add_argument("role", choices=["Buyer", "Seller", "Broker", "Notary", "TaxAuthority"], help="Role")
    p.add_argument("transaction_id", help="Transaction ID (e.g., TX-2024-LISBON-001)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--city", help="Property city")
    p.add_argument("--street", help="Street address")
    p.add_argument("--postal-code", help="Postal code")
    p.add_argument("--country", default="Portugal", help="Country")
    p.add_argument("--price", type=float, help="Sale price (euros)")
    p.add_argument("--date", required=True, help="Sale date (YYYY-MM-DD); the ledger refuses a contribution without one")
    p.add_argument("--type", help="Property type")
    p.add_argument("--sqm", type=float, help="Square meters")
    p.add_argument("--rooms", type=int, help="Number of rooms")
    p.add_argument("--year", type=int, help="Year built")
    p.add_argument("--energy", choices=["A", "B", "C", "D", "E", "F", "G"], help="Energy rating")
    p.add_argument("--cid", action="store_true", help="Show result contract ID")

    p = contrib_sub.add_parser("list", help="List contributions")
    p.add_argument("party", nargs="?", help="Party name (optional with --all)")
    p.add_argument("--all", action="store_true", help="Show all contributions (operator view)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = contrib_sub.add_parser("requests", help="List contribution requests")
    p.add_argument("party", nargs="?", help="Party name (optional with --all)")
    p.add_argument("--all", action="store_true", help="Show all requests (operator view)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = contrib_sub.add_parser("withdraw", help="Withdraw a pending contribution request")
    p.add_argument("party", help="Party name")
    p.add_argument("contract_id", help="Contribution request contract ID")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")

    dispute_parser = subparsers.add_parser("dispute", help="Dispute management")
    dispute_sub = dispute_parser.add_subparsers(dest="command", help="Dispute command")

    p = dispute_sub.add_parser("list", help="List disputes")
    p.add_argument("party", nargs="?", help="Party name (optional with --all)")
    p.add_argument("--all", action="store_true", help="Show all disputes (operator view)")
    p.add_argument("--pending", action="store_true", help="Only non-unanimous disputes (requires --all)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = dispute_sub.add_parser("vote", help="Vote on a dispute")
    p.add_argument("party", help="Party name")
    p.add_argument("contract_id", help="Dispute contract ID")
    p.add_argument("vote_index", type=int, help="Data version index (0, 1, ...)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")

    p = dispute_sub.add_parser("vote-group", help="Vote as several parties for the same option, in sequence")
    p.add_argument("parties", help="Comma-separated party names, in voting order (e.g. Bob,Diana,Alice)")
    p.add_argument("contract_id", help="Dispute contract ID to start from")
    p.add_argument("vote_index", type=int, help="Option index every listed party votes for")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")

    p = dispute_sub.add_parser("propose-data", help=(
        "Propose a new data alternative in a dispute you're already part of (auto-votes for it)"
    ))
    p.add_argument("party", help="Party name (must already be a contributor in this dispute)")
    p.add_argument("contract_id", help="Dispute contract ID")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--from-option", type=int, help=(
        "Start from an existing option index (see `dispute detail`) and override only the "
        "flags you pass - the usual case, since a proposal only deduplicates into an "
        "existing option when the WHOLE record matches it exactly"
    ))
    p.add_argument("--city", help="Property city")
    p.add_argument("--street", help="Street address")
    p.add_argument("--postal-code", help="Postal code")
    p.add_argument("--country", help="Country (no default: a wrong country breaks dedup)")
    p.add_argument("--price", type=float, help="Sale price (euros)")
    p.add_argument("--date", help="Sale date (YYYY-MM-DD)")
    p.add_argument("--type", help="Property type")
    p.add_argument("--sqm", type=float, help="Square meters")
    p.add_argument("--rooms", type=int, help="Number of rooms")
    p.add_argument("--year", type=int, help="Year built")
    p.add_argument("--energy", choices=["A", "B", "C", "D", "E", "F", "G"], help="Energy rating")

    p = dispute_sub.add_parser("detail", help="Show full dispute detail as a participant")
    p.add_argument("party", help="Party name")
    p.add_argument("contract_id", help="Dispute contract ID")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")

    p = dispute_sub.add_parser("show", help="Show full dispute detail (admin/operator view)")
    p.add_argument("contract_id", help="Dispute contract ID")

    query_parser = subparsers.add_parser("query", help="Query management")
    query_sub = query_parser.add_subparsers(dest="command", help="Query command")

    p = query_sub.add_parser("create", help="Create query request")
    p.add_argument("party", help="Party name")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--level", "-l", type=int, default=1, help="Query level (1=aggregated, 2=individual)")
    p.add_argument("--city", help="Filter by city")
    p.add_argument("--price-min", type=float, help="Min price (euros)")
    p.add_argument("--price-max", type=float, help="Max price (euros)")
    p.add_argument("--trust-min", type=float, help="Min contribution trust score")
    p.add_argument("--trust-max", type=float, help="Max contribution trust score")
    p.add_argument("--sqm-min", type=float, help="Min square meters")
    p.add_argument("--sqm-max", type=float, help="Max square meters")
    p.add_argument("--year-min", type=int, help="Min year built")
    p.add_argument("--year-max", type=int, help="Max year built")
    p.add_argument("--sale-from", help="Earliest sale date (YYYY-MM-DD)")
    p.add_argument("--sale-to", help="Latest sale date (YYYY-MM-DD)")
    p.add_argument("--rooms", help="Room counts (comma-separated); matched as a band between the extremes")
    p.add_argument("--types", help="Property types (comma-separated)")
    p.add_argument("--energy", help="Energy ratings (comma-separated)")
    p.add_argument("--tags", help="Tags (comma-separated); a contribution matches if it carries any of them")
    p.add_argument("--cid", action="store_true", help="Show result contract ID")

    p = query_sub.add_parser("requests", help="List query requests")
    p.add_argument("party", nargs="?", help="Party name (optional with --all)")
    p.add_argument("--all", action="store_true", help="Show all requests (operator view)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = query_sub.add_parser("withdraw", help="Withdraw a pending query request")
    p.add_argument("party", help="Party name")
    p.add_argument("contract_id", help="Query request contract ID")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")

    p = query_sub.add_parser("proposals", help="List query proposals")
    p.add_argument("party", nargs="?", help="Party name (optional with --all)")
    p.add_argument("--all", action="store_true", help="Show all proposals (operator view)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = query_sub.add_parser("accept", help="Accept a query proposal")
    p.add_argument("party", help="Party name")
    p.add_argument("proposal_cid", help="Proposal contract ID")
    p.add_argument("holding_cid", help="Holding contract ID (for payment)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show result contract ID")

    p = query_sub.add_parser("reject", help="Decline a quoted price (releases the contribution-set hold)")
    p.add_argument("party", help="Party name")
    p.add_argument("proposal_cid", help="Proposal contract ID")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")

    p = query_sub.add_parser("results", help="List query results")
    p.add_argument("party", nargs="?", help="Party name (optional with --all)")
    p.add_argument("--all", action="store_true", help="Show all results (operator view)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = query_sub.add_parser("funded", help="List funded queries (own, or --all for operator view)")
    p.add_argument("party", nargs="?", help="Party name (optional with --all)")
    p.add_argument("--all", action="store_true", help="Show every funded query (operator view)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = query_sub.add_parser("reclaim", help="Reclaim funds from a query the operator never completed")
    p.add_argument("party", help="Party name (the consumer who paid)")
    p.add_argument("contract_id", help="FundedQuery contract ID (see `query funded <party> --cid`)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show the returned holding contract ID")

    reward_parser = subparsers.add_parser("reward", help="Reward groups you were paid by")
    reward_sub = reward_parser.add_subparsers(dest="command", help="Reward command")

    p = reward_sub.add_parser("groups", help="List the reward groups that paid you, with the ids needed to audit them")
    p.add_argument("party", help="Party name (a contributor)")
    p.add_argument("--transaction-id", help="Only groups paying for this transaction")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")

    audit_parser = subparsers.add_parser("audit", help="On-ledger audit verification workflow")
    audit_sub = audit_parser.add_subparsers(dest="command", help="Audit command")

    p = audit_sub.add_parser("request", help="Create a new audit verification request")
    p.add_argument("party", help="Party name")
    p.add_argument("type", choices=["PricingVerification", "RewardVerification", "InclusionVerification"], help="Verification type")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--query-id", help="Query ID (for pricing verification)")
    p.add_argument("--proposal-id", help="Proposal ID (for pricing verification)")
    p.add_argument("--reward-group-id", help="RewardGroup ID (for reward verification)")
    p.add_argument("--transaction-id", help="Transaction ID (for inclusion verification)")
    p.add_argument("--from-time", help="Inclusion window start (ISO timestamp, default: all history)")
    p.add_argument("--to-time", help="Inclusion window end (ISO timestamp, default: now)")
    p.add_argument("--cid", action="store_true", help="Show result contract ID")

    p = audit_sub.add_parser("requests", help="List my audit requests")
    p.add_argument("party", help="Party name")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = audit_sub.add_parser("cancel", help="Cancel an audit request")
    p.add_argument("party", help="Party name")
    p.add_argument("request_id", help="Audit request contract ID")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")

    p = audit_sub.add_parser("proposals", help="List audit proposals (auditor's price quotes)")
    p.add_argument("party", help="Party name")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = audit_sub.add_parser("fund", help="Fund an audit proposal with audit tokens")
    p.add_argument("party", help="Party name")
    p.add_argument("proposal_cid", help="Audit proposal contract ID")
    p.add_argument("holding_cid", help="Audit token holding contract ID (for payment)")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show result contract ID")

    p = audit_sub.add_parser("reject", help="Reject an audit proposal")
    p.add_argument("party", help="Party name")
    p.add_argument("proposal_cid", help="Audit proposal contract ID")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")

    p = audit_sub.add_parser("results", help="List my audit verification results")
    p.add_argument("party", help="Party name")
    p.add_argument("--participant", "-p", type=int, default=1, help="Participant number")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    p = audit_sub.add_parser("verify-pricing", help="Independently verify a query's price (auditor-stack)")
    p.add_argument("--query-id", help="Query ID (FundedQuery.queryId)")
    p.add_argument("--proposal-id", help="Proposal ID (QueryProposal.proposalId)")

    p = audit_sub.add_parser("verify-reward", help="Independently verify your own share of a reward distribution (auditor-stack)")
    p.add_argument("party", help="The contributor asking - the answer covers their share and nobody else's")
    p.add_argument("query_id", help="Query ID")
    p.add_argument("transaction_id", help="Transaction ID")

    p = audit_sub.add_parser("mints", help="Show observed mints and whether an event justified each")
    p.add_argument("--anomalous", action="store_true", help="Only audit-token mints with no event behind them")

    p = audit_sub.add_parser("verify-inclusion", help="Check which queries used a contribution (auditor-stack)")
    p.add_argument("transaction_id", help="Transaction ID")
    p.add_argument("from_time", help="Window start, e.g. 2024-01-01T00:00:00 (no trailing Z - this endpoint parses java.time.LocalDateTime, not Instant)")
    p.add_argument("to_time", help="Window end, e.g. 2024-12-31T23:59:59 (no trailing Z)")

    audit_sub.add_parser("sync-status", help="Show auditor ledger sync status (auditor-stack)")

    system_parser = subparsers.add_parser("system", help="System/admin commands")
    system_sub = system_parser.add_subparsers(dest="command", help="System command")

    system_sub.add_parser("health", help="Check service health")

    system_sub.add_parser("state", help="Show system state")

    p = system_sub.add_parser("log", help="Show processing log")
    p.add_argument("-n", "--limit", type=int, default=20, help="Number of entries")
    p.add_argument("--contract", help="Show log entries for one contract ID instead of recent entries")
    p.add_argument("--cid", action="store_true", help="Show full contract IDs")

    system_sub.add_parser("resync", help="Trigger a full resync from the ledger")

    system_sub.add_parser("init-pricing", help="Initialize PricingConfig on ledger (required for queries)")

    system_sub.add_parser("pricing", help="Show the current PricingConfig")

    p = system_sub.add_parser("pricing-update", help="Archive and replace the current PricingConfig")
    p.add_argument("--base-price", type=int, required=True, help="Base price")
    p.add_argument("--level1", required=True, help="Level 1 multiplier")
    p.add_argument("--level2", required=True, help="Level 2 multiplier")
    p.add_argument("--coverage-weight", default="2.0", help="Max premium for holding the whole corpus (default: 2.0)")
    p.add_argument("--coverage-saturation", default="5.0", help="How fast completeness stops paying (default: 5.0)")
    p.add_argument("--narrowness-weight", default="0.5", help="Max premium per fully-narrowed dimension (default: 0.5)")
    p.add_argument("--trust-weight", default="4.0", help="Quality multiplier at the top of the trust scale (default: 4.0)")
    p.add_argument("--unit", default="TOKENS", choices=["TOKENS", "EUR"], help="Unit for --base-price (default: TOKENS)")

    p = system_sub.add_parser("pricing-simulate", help="Simulate a query price locally")
    p.add_argument("--level", "-l", type=int, default=1, help="Query level (1 or 2)")
    p.add_argument("--results", type=int, default=100, help="Transactions the query would match (n)")
    p.add_argument("--corpus", type=int, default=100, help="Transactions in the whole corpus (N); coverage is n/N")
    p.add_argument("--selectivity", action="append", metavar="S",
                   help="Narrowness of one filtered dimension, 0.0 (unfiltered) to 1.0 (a single value). "
                        "Repeat for several: --selectivity 0.8 --selectivity 0.5")
    p.add_argument("--trust-quality", type=float, default=0.0,
                   help="Quality of the delivered data: 0.0 corpus average, 1.0 the best, -1.0 the worst")

    args = parser.parse_args()

    if args.group == 'holding' and args.command == 'mint':
        if not hasattr(args, 'amounts') or not args.amounts:
            args.amounts = [args.amount] * len(args.parties)
        elif len(args.amounts) != len(args.parties):
            parser.error(f"Number of amounts ({len(args.amounts)}) must match number of parties ({len(args.parties)})")

    if not args.group:
        parser.print_help()
        return
    if not args.command:
        if args.group == 'holding':
            holding_parser.print_help()
        elif args.group == 'contribution':
            contrib_parser.print_help()
        elif args.group == 'dispute':
            dispute_parser.print_help()
        elif args.group == 'query':
            query_parser.print_help()
        elif args.group == 'audit':
            audit_parser.print_help()
        elif args.group == 'system':
            system_parser.print_help()
        return

    commands = {
        "holding": {
            "mint": cmd_holding_mint,
            "topup": cmd_holding_topup,
            "offers": cmd_holding_offers,
            "accept": cmd_holding_accept,
            "accept-all": cmd_holding_accept_all,
            "merge-all": cmd_holding_merge_all,
            "reject": cmd_holding_reject,
            "list": cmd_holding_list,
            "transfer": cmd_holding_transfer,
            "merge": cmd_holding_merge,
        },
        "reward": {
            "groups": cmd_reward_groups,
        },
        "contribution": {
            "create": cmd_contribution_create,
            "list": cmd_contribution_list,
            "requests": cmd_contribution_requests,
            "withdraw": cmd_contribution_withdraw,
        },
        "dispute": {
            "list": cmd_dispute_list,
            "vote": cmd_dispute_vote,
            "vote-group": cmd_dispute_vote_group,
            "propose-data": cmd_dispute_propose_data,
            "detail": cmd_dispute_detail,
            "show": cmd_dispute_show,
        },
        "query": {
            "create": cmd_query_create,
            "requests": cmd_query_requests,
            "withdraw": cmd_query_withdraw,
            "proposals": cmd_query_proposals,
            "accept": cmd_query_accept,
            "reject": cmd_query_reject,
            "results": cmd_query_results,
            "funded": cmd_query_funded,
            "reclaim": cmd_query_reclaim,
        },
        "audit": {
            "request": cmd_audit_request,
            "requests": cmd_audit_requests,
            "cancel": cmd_audit_cancel,
            "proposals": cmd_audit_proposals,
            "fund": cmd_audit_fund,
            "reject": cmd_audit_reject,
            "results": cmd_audit_results,
            "verify-pricing": cmd_audit_verify_pricing,
            "verify-reward": cmd_audit_verify_reward,
            "mints": cmd_audit_mints,
            "verify-inclusion": cmd_audit_verify_inclusion,
            "sync-status": cmd_audit_sync_status,
        },
        "system": {
            "health": cmd_system_health,
            "state": cmd_system_state,
            "log": cmd_system_log,
            "resync": cmd_system_resync,
            "init-pricing": cmd_system_init_pricing,
            "pricing": cmd_system_pricing,
            "pricing-update": cmd_system_pricing_update,
            "pricing-simulate": cmd_system_pricing_simulate,
        },
    }

    commands[args.group][args.command](args)


if __name__ == "__main__":
    main()
