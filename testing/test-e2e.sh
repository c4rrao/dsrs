#!/bin/bash
#
# Walks one scenario end to end and asserts every number it should produce: trust scores, internal
# reputations, deduplication, the privacy floor, the corroboration filter, quoted prices, reward
# offers, the fee split and all three auditor checks, both the HTTP ones and the paid on-ledger
# workflow. Needs a fresh ledger (dev/start-system.sh) and exits non-zero on any failure.
cd "$(dirname "$0")" || exit 1

PASS=0; FAIL=0
SEED_FILE=$(mktemp /tmp/dsrs-seed-XXXX.json)
SEED_GEN=$(mktemp); SEED_LOAD=$(mktemp)
trap 'rm -f "$SEED_FILE" "$SEED_GEN" "$SEED_LOAD"' EXIT
chk() { # chk <label> <expected> <actual>
  if [ "$2" == "$3" ]; then echo "  PASS  $1 = $3"; PASS=$((PASS+1));
  else echo "  FAIL  $1 : expected '$2' got '$3'"; FAIL=$((FAIL+1)); fi
}
coeff() { docker exec dsrs-operator-db psql -U operator -d operator_db -t -c \
  "SELECT contributors_data FROM contributions WHERE transaction_id='$1';" 2>/dev/null \
  | python3 -c "
import sys,json
b=sys.stdin.read().strip()
d=json.loads(b) if b else {}
for p,v in d.items():
    if p.split('::')[0]=='$2': print(v['disputeCoefficient'][:6])
"; }
trust() { python3 cli.py contribution list --all 2>/dev/null | grep -A 2 "$1" | grep "Trust Score" | awk '{print $3}' | cut -c1-6; }
disputes() { python3 cli.py contribution list --all 2>/dev/null | grep -A 4 "$1" | grep "Disputes:" | awk '{print $2}'; }
# Paying splits a holding, so always pay from the largest unlocked reward-token holding.
biggest_holding() { python3 cli.py holding list Charlie --cid 2>/dev/null | python3 -c "
import sys, re
best_amt, best_cid, amt = 0, '', 0
for line in sys.stdin:
    if 'pending offer' in line:
        break
    m = re.search(r'REWARDTOKEN: .*\(([0-9,]+) tokens\)', line)
    if m:
        amt = 0 if 'LOCKED' in line else int(m.group(1).replace(',', ''))
        continue
    m = re.match(r'\s+Contract: (\S+)', line)
    if m and amt > best_amt:
        best_amt, best_cid = amt, m.group(1)
print(best_cid)
"; }

# Largest unlocked audit-token holding -> "<cid> <amount>".
biggest_audit_holding() { python3 cli.py holding list "$1" --token AUDITTOKEN --cid 2>/dev/null | python3 -c "
import sys, re
best_amt, best_cid, amt = 0, '', 0
for line in sys.stdin:
    if 'offer' in line:
        break
    m = re.search(r'AUDITTOKEN: ([0-9,]+) tokens', line)
    if m:
        amt = 0 if 'LOCKED' in line else int(m.group(1).replace(',', ''))
        continue
    m = re.match(r'\s+Contract: (\S+)', line)
    if m and amt > best_amt:
        best_amt, best_cid = amt, m.group(1)
print(best_cid, best_amt)
"; }

# An unlocked audit-token holding of exactly this amount: FundAudit must split before locking,
# or the remainder would exist but be frozen.
has_unlocked_audit() { python3 cli.py holding list "$1" --token AUDITTOKEN 2>/dev/null | python3 -c "
import sys, re
want = int(sys.argv[1])
for line in sys.stdin:
    m = re.search(r'AUDITTOKEN: ([0-9,]+) tokens', line)
    if m and 'LOCKED' not in line and int(m.group(1).replace(',', '')) == want:
        print('yes'); break
else:
    print('no')
" "$2"; }

echo "### Step 3: pricing config"
python3 cli.py system init-pricing >/dev/null 2>&1

# The floor is asserted on-ledger and mirrored in Kotlin; lowering the Kotlin copy alone would
# make the operator quote queries the ledger refuses to complete.
DAML_FLOOR=$(grep -oE '_minTransactionsForPrivacy_ : Int = [0-9]+' ../daml/daml/Constants.daml | grep -oE '[0-9]+$')
KT_FLOOR=$(grep -oE 'MIN_TRANSACTIONS_FOR_PRIVACY: Int = [0-9]+' ../backend/common/src/main/kotlin/com/dsrs/common/types/Constants.kt | grep -oE '[0-9]+$')
chk "privacy floor: DAML and Kotlin agree" "$DAML_FLOOR" "$KT_FLOOR"

echo "### Step 4: Alice contributes"
python3 cli.py contribution create Alice Buyer TX-2024-LISBON-001 --city Lisbon --price 450000 \
  --date 2024-03-15 --type Apartment --sqm 85 --rooms 2 --year 2010 --energy B >/dev/null 2>&1
sleep 7
chk "Lisbon trust (1 contributor)" "1.0000" "$(trust TX-2024-LISBON-001)"

echo "### Step 5: Bob disputes, Alice votes"
python3 cli.py contribution create Bob Seller TX-2024-LISBON-001 --city Lisbon --price 460000 \
  --date 2024-03-15 --type Apartment --sqm 85 --rooms 2 --year 2010 --energy B >/dev/null 2>&1
sleep 9
D=$(python3 cli.py dispute list Alice --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
# A resolving vote creates the mint's offers before the Contribution, so check the vote's own
# output reports the resolution.
VOUT=$(python3 cli.py dispute vote Alice "$D" 1 2>&1)
sleep 8
chk "resolving vote reports RESOLVED" "yes" "$(echo "$VOUT" | grep -q 'Dispute RESOLVED' && echo yes || echo no)"
chk "...and hands back the live Contribution" "yes" \
  "$([ "$(echo "$VOUT" | grep -oE 'Contribution [0-9a-f]+' | awk '{print $2}')" = \
      "$(python3 cli.py contribution list --all --cid 2>/dev/null | grep 'Contract:' | awk '{print $2}')" ] && echo yes || echo no)"
chk "contribution id is readable, no party fingerprint" "CONT-TX-2024-LISBON-001" \
  "$(python3 cli.py contribution list --all 2>/dev/null | grep -oE '^CONT-[A-Z0-9-]+' | head -1)"
chk "Lisbon price after dispute" "460000.00" "$(python3 cli.py contribution list --all 2>/dev/null | grep -A 5 LISBON | grep Price | awk '{print $2}')"
# resolving the dispute minted a 20,000 audit-token bonus to each contributor
chk "accept-all takes the corroboration bonus" "1" "$(python3 cli.py holding accept-all Alice 2>/dev/null | grep -c '^  ✓')"
chk "accept-all is a no-op once nothing is pending" "yes" \
  "$(python3 cli.py holding accept-all Alice 2>/dev/null | grep -q 'No incoming' && echo yes || echo no)"

echo "### Step 5a: Diana corroborates as a second Buyer (redundancy bonus)"
python3 cli.py contribution create Diana Buyer TX-2024-LISBON-001 --city Lisbon --price 460000 \
  --date 2024-03-15 --type Apartment --sqm 85 --rooms 2 --year 2010 --energy B >/dev/null 2>&1
sleep 9
chk "Lisbon trust (redundancy bonus)" "2.1000" "$(trust TX-2024-LISBON-001)"
# Alice's second bonus: two separate 20,000 holdings that merge-all collapses
python3 cli.py holding accept-all Alice >/dev/null 2>&1
sleep 5
chk "two separate audit-token holdings" "2" "$(python3 cli.py holding list Alice --token AUDITTOKEN 2>/dev/null | grep -c AUDITTOKEN)"
python3 cli.py holding merge-all Alice --token AUDITTOKEN >/dev/null 2>&1
sleep 5
chk "merge-all collapses them to one" "1" "$(python3 cli.py holding list Alice --token AUDITTOKEN 2>/dev/null | grep -c AUDITTOKEN)"
chk "...with the full balance" "40,000 tokens" "$(python3 cli.py holding list Alice --token AUDITTOKEN 2>/dev/null | grep -oE '[0-9,]+ tokens' | head -1)"

chk "MergeContribution logged" "MergeContribution" "$(python3 cli.py system log -n 1 2>/dev/null | grep -oE 'MergeContribution' | head -1)"
# A party contributes once per transaction. The ledger would reject a second submission and
# leave its request unconsumed, so the operator has to turn it down itself.
python3 cli.py contribution create Bob Seller TX-2024-LISBON-001 --city Lisbon --price 999000 \
  --date 2024-03-15 --type Apartment --sqm 85 --rooms 2 --year 2010 --energy B >/dev/null 2>&1
sleep 9
chk "resubmission by an existing contributor is rejected" "RejectContribution" "$(python3 cli.py system log -n 1 2>/dev/null | grep -oE 'RejectContribution' | head -1)"
chk "...leaving no stranded ContributionRequest" "0" "$(python3 cli.py contribution requests --all 2>/dev/null | grep -c '^REQ-')"
chk "...and no change to the contributor count" "3" "$(python3 cli.py contribution list --all 2>/dev/null | grep -A 4 'TX: TX-2024-LISBON-001' | grep Contributors | head -1 | awk '{print $2}')"

echo "### Step 5b: Alice joins agreeing (dedup), Eve joins disagreeing (3rd option), all concede to Eve"
python3 cli.py contribution create Bob Seller TX-2024-PORTO-001 --city Porto --price 275000 \
  --date 2024-05-10 --type House --sqm 120 --rooms 4 --year 1998 --energy C >/dev/null 2>&1
sleep 6
python3 cli.py contribution create Diana Buyer TX-2024-PORTO-001 --city Porto --price 280000 \
  --date 2024-05-10 --type House --sqm 120 --rooms 4 --year 1998 --energy C >/dev/null 2>&1
sleep 9
# join 1: same data as Diana, deduplicated, no new option
python3 cli.py contribution create Alice Broker TX-2024-PORTO-001 --city Porto --price 280000 \
  --date 2024-05-10 --type House --sqm 120 --rooms 4 --year 1998 --energy C >/dev/null 2>&1
sleep 9
P=$(python3 cli.py dispute list --all --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
chk "Porto options after agreeing join (dedup: 2 not 3)" "2" "$(python3 cli.py dispute detail Bob "$P" 2>/dev/null | grep -cE '^  Option [0-9]+:')"
# join 2: data matching nobody, appended as a third option
python3 cli.py contribution create Eve Notary TX-2024-PORTO-001 --city Porto --price 285000 \
  --date 2024-05-10 --type House --sqm 120 --rooms 4 --year 1998 --energy C >/dev/null 2>&1
sleep 9
P=$(python3 cli.py dispute list --all --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
chk "Porto options after disagreeing join (appended: 3)" "3" "$(python3 cli.py dispute detail Bob "$P" 2>/dev/null | grep -cE '^  Option [0-9]+:')"
chk "Porto contributors" "4" "$(python3 cli.py dispute detail Bob "$P" 2>/dev/null | grep -oE 'Votes: [0-9]+/[0-9]+' | cut -d/ -f2)"
chk "dispute detail shows where each party stands" "4" "$(python3 cli.py dispute detail Bob "$P" 2>/dev/null | grep -cE '^    (Alice|Bob|Diana|Eve) ')"
VG=$(python3 cli.py dispute vote-group Bob,Diana,Alice "$P" 2 2>&1)
chk "vote-group resolves the dispute" "RESOLVED" "$(echo "$VG" | grep -oE 'RESOLVED' | head -1)"
sleep 9
chk "Porto settles on the newcomer's figure" "285000.00" "$(python3 cli.py contribution list --all 2>/dev/null | grep -A 5 PORTO | grep Price | awk '{print $2}')"
# Everyone starts at 0.5. One winner of four makes the dispute 0.75 decisive, a step of 0.375
# towards 1.0 for the winner and 0.0 for the losers. Voting for the winner does not help: the
# initial position is what counts.
chk "Porto Eve coeff (sole winner)" "0.6875" "$(coeff TX-2024-PORTO-001 Eve)"
chk "Porto Bob coeff (voted for winner, still a loser)" "0.3125" "$(coeff TX-2024-PORTO-001 Bob)"
chk "Porto Alice coeff (joined agreeing, loser)" "0.3125" "$(coeff TX-2024-PORTO-001 Alice)"
chk "Porto Diana coeff (loser)" "0.3125" "$(coeff TX-2024-PORTO-001 Diana)"
chk "Porto trust (4 distinct roles incl. Notary)" "10.000" "$(trust TX-2024-PORTO-001)"

echo "### Step 5c: Bob proposes the value that settles Faro"
python3 cli.py contribution create Bob Seller TX-2024-FARO-001 --city Faro --price 310000 \
  --date 2024-07-20 --type Apartment --sqm 95 --rooms 3 --year 2015 --energy B >/dev/null 2>&1
sleep 6
python3 cli.py contribution create Diana Buyer TX-2024-FARO-001 --city Faro --price 320000 \
  --date 2024-07-20 --type Apartment --sqm 95 --rooms 3 --year 2015 --energy B >/dev/null 2>&1
sleep 9
F=$(python3 cli.py dispute list Bob --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
OUT=$(python3 cli.py dispute propose-data Bob "$F" --from-option 0 --price 315000 2>&1)
chk "propose-data adds a 3rd option" "3" "$(echo "$OUT" | grep -oE 'Options on the table: [0-9]+' | awk '{print $NF}')"
N=$(echo "$OUT" | grep "New dispute contract:" | awk '{print $NF}')
chk "proposer auto-voted for it" "option 2" "$(python3 cli.py dispute detail Bob "$N" 2>/dev/null | grep -E '^    Bob ' | sed -E 's/.*(option [0-9]+) +option.*/\1/')"
python3 cli.py dispute vote Diana "$N" 2 >/dev/null 2>&1
sleep 9
chk "Faro settles on the proposal" "315000.00" "$(python3 cli.py contribution list --all 2>/dev/null | grep -A 5 FARO | grep Price | awk '{print $2}')"
# one winner of two: step 0.25, symmetric around 0.5
chk "Faro Bob coeff (proposer WINS)" "0.6250" "$(coeff TX-2024-FARO-001 Bob)"
chk "Faro Diana coeff (loser)" "0.3750" "$(coeff TX-2024-FARO-001 Diana)"

echo "### Step 5d: Alice disputes Faro a SECOND time and concedes"
python3 cli.py contribution create Alice Broker TX-2024-FARO-001 --city Faro --price 318000 \
  --date 2024-07-20 --type Apartment --sqm 95 --rooms 3 --year 2015 --energy B >/dev/null 2>&1
sleep 9
F2=$(python3 cli.py dispute list --all --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
chk "second dispute opened on an already-resolved tx" "3" "$(python3 cli.py dispute detail Bob "$F2" 2>/dev/null | grep -oE 'Votes: [0-9]+/[0-9]+' | cut -d/ -f2)"
python3 cli.py dispute vote-group Bob,Diana,Alice "$F2" 0 >/dev/null 2>&1
sleep 9
chk "Faro disputeDates accumulated" "2" "$(disputes TX-2024-FARO-001)"
chk "Faro still settled at 315000" "315000.00" "$(python3 cli.py contribution list --all 2>/dev/null | grep -A 5 FARO | grep Price | awk '{print $2}')"
# Two winners of three: step 1/6. Bob wins again and approaches 1.0 without reaching it.
chk "Faro Bob coeff (second win, bounded)" "0.6875" "$(coeff TX-2024-FARO-001 Bob)"
chk "Faro Diana coeff (winner from 0.375)" "0.4791" "$(coeff TX-2024-FARO-001 Diana)"
chk "Faro Alice coeff (conceded, loser from 0.5)" "0.4166" "$(coeff TX-2024-FARO-001 Alice)"
chk "Faro trust with a Broker" "5.0000" "$(trust TX-2024-FARO-001)"

echo "### Step 6: privacy floor rejects a narrow query"
python3 cli.py holding mint Charlie --amount 1000e >/dev/null 2>&1
sleep 7
O=$(python3 cli.py holding offers Charlie --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
python3 cli.py holding accept Charlie "$O" >/dev/null 2>&1
sleep 5
python3 cli.py query create Charlie --level 1 --city Faro >/dev/null 2>&1
sleep 11
chk "narrow query matched exactly 1" "1" "$(python3 cli.py system log -n 2 2>/dev/null | grep -oE 'matches [0-9]+ transaction' | awk '{print $2}' | head -1)"
chk "no proposal was quoted" "none" "$(python3 cli.py query proposals Charlie --cid 2>/dev/null | grep -q 'Proposal:' && echo some || echo none)"

echo "### Step 6: the corroboration gate - a solo contribution is not sellable"
# A transaction with one contributor is not sellable: Cascais is on the ledger with only Bob,
# and the query corpus requires contributor_count > 1.
python3 cli.py contribution create Bob Seller TX-2024-CASCAIS-001 --city Cascais --price 520000 \
  --date 2024-06-01 --type Villa --sqm 180 --rooms 4 --year 2018 --energy A >/dev/null 2>&1
sleep 9
chk "the contribution is on the ledger" "4" "$(python3 cli.py contribution list --all 2>/dev/null | grep -c '^CONT-')"
chk "...with a single contributor" "1" "$(docker exec dsrs-operator-db psql -U operator -d operator_db -t \
  -c "SELECT contributor_count FROM contributions WHERE transaction_id = 'TX-2024-CASCAIS-001';" 2>/dev/null | tr -d ' ')"

echo "### Step 6: broad query - 4 transactions exist, 3 are sellable"
python3 cli.py query create Charlie --level 1 >/dev/null 2>&1
sleep 12
chk "broad query price" "59,730,482" "$(python3 cli.py query proposals Charlie --cid 2>/dev/null | grep -oE '\([0-9,]+ tokens\)' | tail -1 | tr -d '()' | awk '{print $1}')"
PROP=$(python3 cli.py query proposals Charlie --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
HOLD=$(biggest_holding)
python3 cli.py query accept Charlie "$PROP" "$HOLD" >/dev/null 2>&1
sleep 25
# an unfiltered query over four transactions, without Cascais
chk "transactions matched" "3" "$(python3 cli.py query results Charlie 2>/dev/null | grep -oE 'Transactions matched: [0-9]+' | tail -1 | awk '{print $NF}')"
chk "...and the uncorroborated one is not among them" "0" \
  "$(python3 cli.py audit verify-inclusion TX-2024-CASCAIS-001 2026-01-01T00:00:00 2026-12-31T23:59:59 2>/dev/null \
     | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["queries"]))')"

EXEC=$(python3 cli.py query results Charlie --cid 2>/dev/null | grep "Executed at:" | tail -1 | awk '{print $3}')
COMP=$(python3 cli.py query results Charlie --cid 2>/dev/null | grep "Completed at:" | tail -1 | awk '{print $3}')
chk "execution predates completion" "yes" "$([ "$EXEC" \< "$COMP" ] && echo yes || echo no)"

# three groups, three payouts; Bob gets nothing for Cascais, which was not sold
for pair in "Alice 3" "Bob 3" "Diana 3" "Eve 1"; do
  set -- $pair
  chk "$1 reward offers" "$2" "$(python3 cli.py holding offers $1 2>/dev/null | grep -c REWARDTOKEN)"
done
# pin the broad query's ids before later queries add results
QID=$(python3 cli.py query results Charlie --cid 2>/dev/null | grep "Query ID:" | tail -1 | awk '{print $3}')
PROPID=$(python3 cli.py query results Charlie --cid 2>/dev/null | grep "Proposal ID:" | tail -1 | awk '{print $3}')
PROPID_BROAD=$PROPID

chk "operator fee 15%" "8,959,572" "$(python3 cli.py holding list Operator 2>/dev/null | grep REWARDTOKEN | grep -oE '\([0-9,]+ tokens\)' | tr -d '()' | awk '{print $1}')"
# The escrow is the sender of every reward transfer, so almost every offer it sees is outbound
# and not something it can accept.
ESC=$(python3 cli.py holding list Escrow 2>/dev/null)
chk "escrow's reward transfers show as outgoing" "yes" "$(echo "$ESC" | grep -q 'outgoing offer(s) sent by Escrow' && echo yes || echo no)"
chk "...and none of them as acceptable" "no" "$(echo "$ESC" | grep -q 'incoming offer' && echo yes || echo no)"
chk "accept-all finds nothing for the escrow to take" "yes" \
  "$(python3 cli.py holding accept-all Escrow 2>/dev/null | grep -q 'No incoming' && echo yes || echo no)"

chk "auditor fee 2%" "1,194,609" "$(python3 cli.py holding list Auditor 2>/dev/null | grep REWARDTOKEN | grep -oE '\([0-9,]+ tokens\)' | tr -d '()' | awk '{print $1}')"

echo "### Step 6: a second party corroborates, and the record becomes sellable"
# Alice submits identical data, so the operator merges and Cascais passes the filter.
python3 cli.py contribution create Alice Buyer TX-2024-CASCAIS-001 --city Cascais --price 520000 \
  --date 2024-06-01 --type Villa --sqm 180 --rooms 4 --year 2018 --energy A >/dev/null 2>&1
sleep 10
chk "matching data merges rather than disputing" "MergeContribution" \
  "$(python3 cli.py system log -n 1 2>/dev/null | grep -oE 'MergeContribution' | head -1)"
chk "contributor count crosses the gate" "2" "$(docker exec dsrs-operator-db psql -U operator -d operator_db -t \
  -c "SELECT contributor_count FROM contributions WHERE transaction_id = 'TX-2024-CASCAIS-001';" 2>/dev/null | tr -d ' ')"
# Take the 20,000 corroboration bonus so later audits are funded from a known balance. Only
# audit tokens: the reward-token counts below are cumulative.
python3 cli.py holding accept-all Alice --token AUDITTOKEN >/dev/null 2>&1
python3 cli.py holding accept-all Bob --token AUDITTOKEN >/dev/null 2>&1
sleep 3

echo "### Step 6: multi-factor filtered query - narrower match, several pricing factors"
python3 cli.py query create Charlie --level 1 --price-min 300000 --trust-min 0.5 --trust-max 20 \
  --types Apartment,Villa --energy A,B --rooms 2,4 >/dev/null 2>&1
sleep 12
chk "multi-factor query matched 3" "3" "$(docker exec dsrs-operator-db psql -U operator -d operator_db -t \
  -c "SELECT result_count FROM query_proposals ORDER BY created_at DESC LIMIT 1;" 2>/dev/null | tr -d ' ')"
# Unchanged, and not by luck: the query matches the corpus minus its highest-trust member
# (Porto), and for that shape both trustQuality terms are -1/3. n and N are 3 and 4 either way.
chk "multi-factor query price" "41,538,189" "$(python3 cli.py query proposals Charlie --cid 2>/dev/null | grep -oE '\([0-9,]+ tokens\)' | tail -1 | tr -d '()' | awk '{print $1}')"
PROPF=$(python3 cli.py query proposals Charlie --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
HOLD=$(biggest_holding)
python3 cli.py query accept Charlie "$PROPF" "$HOLD" >/dev/null 2>&1
sleep 25
# Cascais was sold by this query, not the broad one, so the 6.7 reward check asks about it here
QIDF=$(python3 cli.py query results Charlie --cid 2>/dev/null | grep "Query ID:" | tail -1 | awk '{print $3}')
# Porto (285,000) is below --price-min, so nobody is paid for it. Cascais is in this time, so
# Alice and Bob are paid for it. Eve only contributed to Porto and stays at 1.
for pair in "Alice 6" "Bob 6" "Diana 5" "Eve 1"; do
  set -- $pair
  chk "$1 reward offers after the filtered query" "$2" "$(python3 cli.py holding offers $1 2>/dev/null | grep -c REWARDTOKEN)"
done

echo "### Step 6: Level 2 query - the richer aggregation"
python3 cli.py query create Charlie --level 2 >/dev/null 2>&1
sleep 12
chk "level 2 price (3x the level 1 multiplier)" "208,034,826" "$(python3 cli.py query proposals Charlie --cid 2>/dev/null | grep -oE '\([0-9,]+ tokens\)' | tail -1 | tr -d '()' | awk '{print $1}')"
PROPL2=$(python3 cli.py query proposals Charlie --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
HOLD=$(biggest_holding)
python3 cli.py query accept Charlie "$PROPL2" "$HOLD" >/dev/null 2>&1
sleep 25
L2=$(python3 cli.py query results Charlie 2>/dev/null)
# four now against three before: the corpus did not grow, one record became sellable
chk "level 2 matched 4" "4" "$(echo "$L2" | grep -oE 'Transactions matched: [0-9]+' | tail -1 | awk '{print $NF}')"
chk "avgContributorsPerTx (3+4+3+2)/4" "3.0" "$(echo "$L2" | grep 'Avg contributors/transaction' | tail -1 | awk '{print $NF}')"
# Faro was disputed twice in step 5d, so the average exceeds what the rate suggests
chk "avgDisputesPerTx (1+1+2+0)/4" "1.0" "$(echo "$L2" | grep 'Avg disputes/transaction' | tail -1 | awk '{print $NF}')"
chk "disputeRate (3 of 4 ever contested)" "75.0%" "$(echo "$L2" | grep 'Dispute rate' | tail -1 | awk '{print $NF}')"

echo "### Step 6.6: auditor verifications (of the BROAD query, pinned above)"
V=$(python3 cli.py audit verify-pricing --proposal-id "$PROPID" 2>&1)
chk "pricing verdict" "MATCH" "$(echo "$V" | grep -oE 'MATCH|MISMATCH' | head -1)"
# Three, not four: the auditor replays at the query's pinned instant, when Cascais still had one
# contributor. Deriving four would report a mismatch against an honest operator.
chk "auditor matched count" "3" "$(echo "$V" | grep 'auditor matched' | awk '{print $NF}')"
chk "metadata agreement" "agrees" "$(echo "$V" | grep -q 'every field agrees' && echo agrees || echo differs)"
# the delivered statistics are recomputed over the same replay too
chk "delivered statistics agree" "agree" "$(echo "$V" | grep -q 'every delivered statistic agrees' && echo agree || echo differ)"

# Rewrite the statistics the auditor stored as delivered, as an operator charging the right
# price for the wrong numbers would. The price still agrees; the check fails on that statistic.
ADB() { docker exec dsrs-auditor-db psql -U auditor -d auditor_db -t -A -c "$1"; }
ORIG_RESULTS=$(ADB "SELECT results_json FROM query_snapshots WHERE status='completed' AND proposal_id='$PROPID';")
ADB "UPDATE query_snapshots SET results_json = regexp_replace(results_json, '\"avgSalePrice\": \"[0-9.]+\"', '\"avgSalePrice\": \"999999.0000000000\"') WHERE status='completed' AND proposal_id='$PROPID';" >/dev/null
VT=$(python3 cli.py audit verify-pricing --proposal-id "$PROPID" 2>&1)
chk "a wrong answer at the right price fails" "MISMATCH" "$(echo "$VT" | grep -oE 'MATCH|MISMATCH' | head -1)"
chk "...with the price itself agreeing" "agrees" "$(echo "$VT" | grep -q 'Price: *✓ agrees' && echo agrees || echo differs)"
chk "...naming the statistic" "1" "$(echo "$VT" | grep -c 'avgSalePrice: delivered=999999')"
ADB "UPDATE query_snapshots SET results_json = \$j\$$ORIG_RESULTS\$j\$ WHERE status='completed' AND proposal_id='$PROPID';" >/dev/null
chk "...and restored, it agrees again" "MATCH" "$(python3 cli.py audit verify-pricing --proposal-id "$PROPID" 2>&1 | grep -oE 'MATCH|MISMATCH' | head -1)"

# A reward audit answers for the requester alone, so the split is read back one contributor at
# a time.
owed() { python3 cli.py audit verify-reward "$1" "$2" "$3" 2>&1 \
  | grep -oE 'Owed: +[0-9,]+' | grep -oE '[0-9,]+$'; }

R=$(python3 cli.py audit verify-reward Alice "$QID" TX-2024-LISBON-001 2>&1)
chk "reward verdict (Lisbon, as Alice)" "MATCH" "$(echo "$R" | grep -oE 'MATCH|MISMATCH' | head -1)"
chk "no failure modes" "0" "$(echo "$R" | grep -c '^  ✗ ')"
chk "every link behind the figure passed" "3" "$(echo "$R" | grep -c '^    \[✓\]')"

# Check the payload: no figure from the query, no other contributor's share, no other party id.
export DSRS_CHK_QID="$QID"
chk "reward answer withholds the query's figures" "clean" "$(python3 - <<'PY' 2>&1 | tail -1
import json, os, client, parties
alice = parties.TEST_PARTIES["alice"]["name"]
d = client.AuditorClient().verify_reward(
    os.environ["DSRS_CHK_QID"], "TX-2024-LISBON-001", alice).data or {}
withheld = ("contributorResults", "totalExpected", "totalActual", "queryTokenCost", "operatorFee",
            "escrowFee", "auditorFee", "distributablePool", "matchedTransactionCount",
            "rewardGroupCount", "expectedGroupTotal", "actualGroupTotal", "queryGroupsFound",
            "queryTotalDistributed")
leaked = [k for k in withheld if k in d]
# the echoed ids are the caller's, and a funded query id embeds the consumer's party
blob = json.dumps({k: v for k, v in d.items() if k not in ("queryId", "transactionId")})
others = [p["name"] for k, p in parties.TEST_PARTIES.items()
          if p["name"] != alice and p["name"] in blob]
print(",".join(leaked + others) or "clean")
PY
)"
chk "...and the one row it returns is the requester's own" "yes" "$(python3 - <<'PY' 2>&1 | tail -1
import os, client, parties
alice = parties.TEST_PARTIES["alice"]["name"]
d = client.AuditorClient().verify_reward(
    os.environ["DSRS_CHK_QID"], "TX-2024-LISBON-001", alice).data or {}
print("yes" if (d.get("contributorResult") or {}).get("contributor") == alice else "no")
PY
)"
# Charlie bought the query; the payout is not his business
chk "a non-contributor is refused, not answered" "refused" \
  "$(python3 cli.py audit verify-reward Charlie "$QID" TX-2024-LISBON-001 2>&1 \
     | grep -q 'did not contribute' && echo refused || echo answered)"

chk "Faro Bob owed (Seller, 2 disputes won -> 0.6875)" "4,074,175" "$(owed Bob "$QID" TX-2024-FARO-001)"
chk "Faro Diana owed (Buyer, 0.4791)" "2,839,575" "$(owed Diana "$QID" TX-2024-FARO-001)"
# Alice conceded both Faro disputes and still out-earns Bob, who won both: her Broker
# coefficient of 3.0 outweighs a reputation that can only approach 1.0.
chk "Faro Alice owed (Broker, conceded) > Bob" "7,407,592" "$(owed Alice "$QID" TX-2024-FARO-001)"

# Lisbon has two Buyers, Alice and Diana. Diana earns 4:3 against Alice because Alice lost the
# step 5 dispute (0.5 -> 0.375) and Diana joined afterwards at 0.5.
chk "Lisbon Alice owed (Buyer, loser 0.375)" "1,121,433" "$(owed Alice "$QID" TX-2024-LISBON-001)"
chk "Lisbon Diana owed (Buyer, 4:3 over Alice)" "1,495,244" "$(owed Diana "$QID" TX-2024-LISBON-001)"
chk "Lisbon Bob owed (Seller, winner 0.625)" "3,398,285" "$(owed Bob "$QID" TX-2024-LISBON-001)"

# Lisbon was matched by all three completed queries. In Porto, Eve's Notary role is half the
# group's weight and her reputation (0.6875) makes it 0.34375 of 0.5: 68.75% of the group, 11x
# Bob's share.
chk "Porto Eve owed (Notary 5.0x, sole winner)" "19,691,852" "$(owed Eve "$QID" TX-2024-PORTO-001)"
chk "Porto Alice owed (Broker 3.0x, loser)" "5,370,503" "$(owed Alice "$QID" TX-2024-PORTO-001)"
chk "Porto Bob owed (Seller 1.0x, loser)" "1,790,166" "$(owed Bob "$QID" TX-2024-PORTO-001)"

chk "inclusion queries" "3" "$(python3 cli.py audit verify-inclusion TX-2024-LISBON-001 2026-01-01T00:00:00 2026-12-31T23:59:59 2>/dev/null | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["queries"]))')"

echo "### Step 6.5: an audit belongs to the party it is about"
# Alice cannot audit Charlie's queries. DeclineAuditRequest consumes the request with a reason
# and she is never quoted a price.
python3 cli.py audit request Alice PricingVerification --query-id "$PROPID_BROAD" >/dev/null 2>&1
sleep 9
# The decline leaves no contract, so the evidence is the request gone and no proposal made.
chk "audit request without standing is consumed" "0" "$(python3 cli.py audit requests Alice 2>/dev/null | grep -c '^AUDIT-REQ')"
chk "...and no price was ever proposed" "0" "$(python3 cli.py audit proposals Alice 2>/dev/null | grep -c '^AUDIT-REQ')"

# Charlie paid for the query, so it is his to audit; CompleteQuery minted him audit tokens
python3 cli.py holding accept-all Charlie --token AUDITTOKEN >/dev/null 2>&1
sleep 5
python3 cli.py audit request Charlie PricingVerification --query-id "$PROPID_BROAD" >/dev/null 2>&1
sleep 10
chk "the consumer's own audit is quoted a price" "1" "$(python3 cli.py audit proposals Charlie 2>/dev/null | grep -c '^AUDIT-REQ')"

# The on-ledger pricing check covers the delivered statistics too, compared by Charlie's own
# participant stack from the two stored lists.
read -r CH CAMT <<<"$(biggest_audit_holding Charlie)"
CPROP=$(python3 cli.py audit proposals Charlie --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
python3 cli.py audit fund Charlie "$CPROP" "$CH" >/dev/null 2>&1
sleep 14
CRES=$(python3 cli.py audit results Charlie 2>/dev/null)
chk "the pricing audit completed on-ledger" "1" "$(echo "$CRES" | grep -c 'PRICINGVERIFICATION')"
chk "...with a verdict" "MATCH" "$(echo "$CRES" | grep -oE 'MATCH|MISMATCH' | head -1)"
chk "...that covers the delivered statistics" "agree" "$(echo "$CRES" | grep -q 'Statistics: ✓ agree' && echo agree || echo differ)"

# Inclusion takes a transaction and a window; contributors cannot see query ids. Rejected
# client-side so the mistake costs no audit tokens.
chk "inclusion rejects --query-id" "1" "$(python3 cli.py audit request Alice InclusionVerification \
  --transaction-id TX-2024-LISBON-001 --query-id "$PROPID_BROAD" 2>&1 | grep -c 'does not take')"
chk "inclusion needs a transaction" "1" "$(python3 cli.py audit request Alice InclusionVerification 2>&1 \
  | grep -c 'needs --transaction-id')"

# Alice contributed to Lisbon, so the question is hers
python3 cli.py audit request Alice InclusionVerification --transaction-id TX-2024-LISBON-001 >/dev/null 2>&1
sleep 10
chk "contributor's inclusion audit is quoted a price" "1" "$(python3 cli.py audit proposals Alice 2>/dev/null | grep -c '^AUDIT-REQ')"

# FundAudit splits off exactly the quoted cost and locks only that, so the rest of the balance
# stays usable.
read -r AH AAMT <<<"$(biggest_audit_holding Alice)"
ACOST=$(python3 cli.py audit proposals Alice 2>/dev/null | grep 'Estimated Cost:' | tail -1 | grep -oE '[0-9,]+' | head -1)
ACOSTN=${ACOST//,/}
APROP=$(python3 cli.py audit proposals Alice --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
python3 cli.py audit fund Alice "$APROP" "$AH" >/dev/null 2>&1
sleep 14
chk "funding consumes the holding it split" "gone" \
  "$(python3 cli.py holding list Alice --token AUDITTOKEN --cid 2>/dev/null | grep -q "$AH" && echo present || echo gone)"
chk "...and the balance beyond the fee stays UNLOCKED" "yes" "$(has_unlocked_audit Alice $(( AAMT - ACOSTN )))"
chk "the audit completed on-ledger" "1" "$(python3 cli.py audit results Alice 2>/dev/null | grep -c 'INCLUSIONVERIFICATION')"
chk "...with a verdict" "MATCH" "$(python3 cli.py audit results Alice 2>/dev/null | grep -oE 'MATCH|MISMATCH' | head -1)"
chk "...and burned exactly what it quoted" "$ACOST" \
  "$(python3 cli.py audit results Alice 2>/dev/null | grep 'Tokens Burned:' | head -1 | grep -oE '[0-9,]+')"
# matched and rewarded in all three queries, as the HTTP check reported
chk "...naming every query that used the data" "3 of 3" \
  "$(python3 cli.py audit results Alice 2>/dev/null | grep -oE 'matched [0-9]+ of [0-9]+' | head -1 | sed 's/matched //')"

# the remainder is usable: a second audit funds straight from it
python3 cli.py audit request Alice InclusionVerification --transaction-id TX-2024-FARO-001 >/dev/null 2>&1
sleep 10
read -r AH2 AAMT2 <<<"$(biggest_audit_holding Alice)"
APROP2=$(python3 cli.py audit proposals Alice --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
python3 cli.py audit fund Alice "$APROP2" "$AH2" >/dev/null 2>&1
sleep 14
chk "a second audit funds out of the remainder" "2" \
  "$(python3 cli.py audit results Alice 2>/dev/null | grep -c 'INCLUSIONVERIFICATION')"
chk "...and both fees are gone from the same lineage" "yes" "$(has_unlocked_audit Alice $(( AAMT2 - ACOSTN )))"

echo "### Step 6.7: the system refuses to sell"
bal() { python3 cli.py holding list Charlie 2>/dev/null | grep REWARDTOKEN \
  | grep -oE '\([0-9,]+ tokens\)' | tr -d '(),a-z ' | paste -sd+ | bc; }
BEFORE=$(bal)
RESULTS_BEFORE=$(python3 cli.py query results Charlie 2>/dev/null | grep -c "Result (Level")

# The dispute has to come first: contribution processing is held from pricing until the reward
# is distributed, so a later dispute would land after the query was answered. The floor is
# therefore enforced at pricing.
python3 cli.py contribution create Diana Buyer TX-2024-CASCAIS-001 --city Cascais --price 530000 \
  --date 2024-06-01 --type Villa --sqm 180 --rooms 4 --year 2018 --energy A >/dev/null 2>&1
sleep 10
chk "disputed transaction is archived" "0" "$(python3 cli.py contribution list --all 2>/dev/null | grep -c CASCAIS)"

# only 2 of the 3 sellable transactions over 300k are left (the dispute archived Cascais)
python3 cli.py query create Charlie --level 1 --price-min 300000 >/dev/null 2>&1
sleep 12

chk "operator rejected the query" "RejectQueryRequest" \
  "$(python3 cli.py system log -n 4 2>/dev/null | grep -oE 'RejectQueryRequest' | head -1)"
chk "rejection names the privacy floor" "yes" \
  "$(python3 cli.py system log -n 4 2>/dev/null | grep -q 'minimum 3 required to preserve contributor privacy' && echo yes || echo no)"
chk "no price was ever quoted" "0" \
  "$(python3 cli.py query proposals Charlie 2>/dev/null | grep -c '^PROPOSAL')"
chk "nothing was delivered" "$RESULTS_BEFORE" \
  "$(python3 cli.py query results Charlie 2>/dev/null | grep -c 'Result (Level')"
chk "the consumer was never charged" "$BEFORE" "$(bal)"

# The dispute archived a contribution the earlier query was priced over. That query must still
# verify: the auditor matches by validity window, not by the state column updated on archival.
V=$(python3 cli.py audit verify-pricing --proposal-id "$PROPID_BROAD" 2>&1)
chk "past query still verifiable after archival" "MATCH" "$(echo "$V" | grep -oE 'MATCH|MISMATCH' | head -1)"
chk "still matches the same 3 transactions" "3" "$(echo "$V" | grep 'auditor matched' | awk '{print $NF}')"
chk "reward still verifiable after archival" "MATCH" \
  "$(python3 cli.py audit verify-reward Alice "$QID" TX-2024-LISBON-001 2>&1 | grep -oE 'MATCH|MISMATCH' | head -1)"

echo "### The dispute resolves by unanimous vote, changing who the contributors are"
# Cascais's reward must be checked against the contributors it had when it was paid, not the
# third one the dispute added.
DC=$(python3 cli.py dispute list --all --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')

VC=$(python3 cli.py dispute vote-group Bob,Alice,Diana "$DC" 0 2>&1)
chk "Cascais settles unanimously on the original figure" "RESOLVED" "$(echo "$VC" | grep -oE 'RESOLVED' | head -1)"
sleep 10
chk "disputed transaction is restored" "4" "$(python3 cli.py contribution list --all 2>/dev/null | grep -c '^CONT-')"

# Bob and Alice can verify their shares; Diana, who joined later, is refused.
chk "reward verified against contributors as paid (Bob)" "MATCH" \
  "$(python3 cli.py audit verify-reward Bob "$QIDF" TX-2024-CASCAIS-001 2>&1 | grep -oE 'MATCH|MISMATCH' | head -1)"
chk "...and for Alice" "MATCH" \
  "$(python3 cli.py audit verify-reward Alice "$QIDF" TX-2024-CASCAIS-001 2>&1 | grep -oE 'MATCH|MISMATCH' | head -1)"
chk "a contributor who joined after the payout is refused" "refused" \
  "$(python3 cli.py audit verify-reward Diana "$QIDF" TX-2024-CASCAIS-001 2>&1 \
     | grep -q 'did not contribute' && echo refused || echo answered)"

echo "### Step 6.8: seeding a corpus with corroborating contributors"
# The seed pipeline: a transaction generated with N contributors must land with N, which only
# holds if each submission is visible before the next is sent.
cd seed || exit 1
python3 allocate_seed_parties.py --pool-size 30 >/dev/null 2>&1
python3 generate_seed_data.py --count 12 --out "$SEED_FILE" >"$SEED_GEN" 2>&1
chk "generator emits multi-contributor transactions" "yes" \
  "$(grep -qE 'contributors per transaction:.*[234]x[0-9]' "$SEED_GEN" && echo yes || echo no)"
python3 load_seed_data.py --file "$SEED_FILE" --workers 4 >"$SEED_LOAD" 2>&1
chk "every seeded contribution succeeded" "0" "$(grep -oE '[0-9]+ failed' "$SEED_LOAD" | tail -1 | awk '{print $1}')"
cd ..

# concurrent submissions would race into separate records and these histograms would differ
GEN_SHAPE=$(python3 -c "
import json,collections,sys
d=json.load(open('$SEED_FILE'))
c=collections.Counter(collections.Counter(r['transactionId'] for r in d).values())
print(','.join(f'{k}:{v}' for k,v in sorted(c.items())))")
LEDGER_SHAPE=$(docker exec dsrs-operator-db psql -U operator -d operator_db -t -c \
  "SELECT n||':'||c FROM (SELECT jsonb_array_length(jsonb_path_query_array(contributors_data::jsonb,'\$.keyvalue().key')) AS n, count(*) AS c FROM contributions WHERE transaction_id LIKE 'TX-SEED-%' GROUP BY 1 ORDER BY 1) t;" 2>/dev/null | tr -d ' ' | grep -v '^$' | paste -sd,)
chk "ledger matches the generated contributor shape" "$GEN_SHAPE" "$LEDGER_SHAPE"

# the quality term needs trust scores that vary
chk "seeded corpus has a spread of trust scores" "yes" \
  "$(docker exec dsrs-operator-db psql -U operator -d operator_db -t -c \
      "SELECT count(DISTINCT total_trust_score) FROM contributions WHERE transaction_id LIKE 'TX-SEED-%';" 2>/dev/null \
      | tr -d ' ' | grep -v '^$' | head -1 | awk '{print ($1 > 1) ? "yes" : "no"}')"

echo ""
echo "### Mint provenance: audit tokens can only come from a workflow event"
# Audit tokens are only earned in workflows, so one minted outside a workflow is an anomaly.
# Reward tokens minted alone against off-platform payment are expected and not flagged.
MINTS=$(python3 cli.py audit mints 2>/dev/null)
chk "every audit-token mint had an event behind it" "yes" \
  "$(echo "$MINTS" | grep -q 'every audit-token mint had a workflow event' && echo yes || echo no)"
chk "audit tokens were actually minted" "yes" \
  "$(echo "$MINTS" | grep -q 'AUDITTOKEN' && echo yes || echo no)"
# the mint is attributed to the choice it was nested under, not just a contract beside it
chk "workflow mints name the choice that caused them" "yes" \
  "$(echo "$MINTS" | grep -A 1 '\[  event\] AUDITTOKEN' | grep -qE 'arrived with: [A-Za-z]+\.(MergeContribution|CompleteQuery|Vote|DistributeInGroup)' && echo yes || echo no)"
# the entitlement behind a workflow mint is transient, so only the tree stream shows it
chk "workflow mints expose the declared reason and source" "yes" \
  "$(echo "$MINTS" | grep -A 2 '\[  event\] AUDITTOKEN' | grep -qE 'operator declared: (corroboration_bonus|query_completion|dispute_resolution|reward_distribution) / .+' && echo yes || echo no)"
# the operator's reward-token issuance arrives on its own and is recorded, not flagged
chk "operator reward-token issuance is recorded, not flagged" "yes" \
  "$(echo "$MINTS" | grep -q '\[ issued\] REWARDTOKEN' && echo yes || echo no)"
chk "...and declares no source event rather than inventing one" "yes" \
  "$(echo "$MINTS" | grep -A 2 '\[ issued\] REWARDTOKEN' | grep -q 'no-source-event' && echo yes || echo no)"

echo "### The contribution set is held still while a query is priced"
# The contribution-set lock. Placed last because it adds a transaction and the assertions above
# depend on the corpus. (grep -c prints 0 and exits 1 on no match, hence the head -1.)
deferrals() { grep -c 'Deferring ContributionRequest' ../dev/logs/operator.log 2>/dev/null | head -1; }
DEFERRED_BEFORE=$(deferrals); DEFERRED_BEFORE=${DEFERRED_BEFORE:-0}
python3 cli.py query create Charlie --level 1 --price-min 200000 >/dev/null 2>&1
sleep 6
python3 cli.py contribution create Eve Broker TX-2024-LOCKTEST-001 --city Braga --price 275000 \
  --date 2024-08-01 --type Apartment --sqm 88 --rooms 3 --year 2015 --energy C >/dev/null 2>&1
sleep 5
DEFERRED_AFTER=$(deferrals); DEFERRED_AFTER=${DEFERRED_AFTER:-0}
chk "a contribution submitted while a query holds the set is deferred" "yes" \
  "$([ "$DEFERRED_AFTER" -gt "$DEFERRED_BEFORE" ] && echo yes || echo no)"
chk "...and is not on the ledger yet" "no" \
  "$(python3 cli.py contribution list --all 2>/dev/null | grep -q 'TX-2024-LOCKTEST-001' && echo yes || echo no)"

# rejecting the quote frees the set straight away instead of after the 5-minute lease
LOCKPROP=$(python3 cli.py query proposals Charlie --cid 2>/dev/null | grep "Contract:" | tail -1 | awk '{print $2}')
python3 cli.py query reject Charlie "$LOCKPROP" >/dev/null 2>&1
sleep 14
chk "rejecting the quote releases the set" "yes" \
  "$(python3 cli.py contribution list --all 2>/dev/null | grep -q 'TX-2024-LOCKTEST-001' && echo yes || echo no)"
chk "...and the deferred request was picked up by the reconciler" "yes" \
  "$(python3 cli.py system log -n 80 2>/dev/null | grep -q 'deferred, processed by reconciler' && echo yes || echo no)"

echo "### Onboarding: register, approve, and act as the party that creates"
# Registration and approval are not used anywhere else, so exercise them here. Stops short of
# contributing so the counts above do not move.
PURL="http://localhost:8081"; PKEY="dev-participant1-admin-key"
NEWUSER="onboard_$(date +%s)"; NEWPARTY="Onboard$(date +%s)"

REG=$(curl -s -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"$NEWUSER\",\"password\":\"e2e-test-pw\",\"party\":\"$NEWPARTY\"}" \
  "$PURL/api/auth/register")
chk "registration is accepted and starts pending" "pending" "$(echo "$REG" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("status",""))' 2>/dev/null)"

# a pending account cannot act
chk "a pending account cannot log in" "403" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
      -d "{\"username\":\"$NEWUSER\",\"password\":\"e2e-test-pw\"}" "$PURL/api/auth/login")"

NEWID=$(curl -s -H "Authorization: Bearer $PKEY" "$PURL/api/admin/users/pending" \
  | python3 -c "import sys,json;print(next((u['id'] for u in json.load(sys.stdin) if u['username']=='$NEWUSER'),''))" 2>/dev/null)
chk "approval allocates the party on Canton" "200" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $PKEY" \
      "$PURL/api/admin/users/$NEWID/approve")"

# approval must store the qualified id Canton returned, or every submission would fail
LOGIN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"$NEWUSER\",\"password\":\"e2e-test-pw\"}" "$PURL/api/auth/login")
chk "login returns the qualified party, not the hint" "yes" \
  "$(echo "$LOGIN" | python3 -c "import sys,json;p=json.load(sys.stdin).get('party','');print('yes' if p.startswith('$NEWPARTY::') else 'no')" 2>/dev/null)"
chk "...and the token it issues is accepted by the ledger routes" "200" \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(echo "$LOGIN" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null)" \
      "$PURL/api/contributions/requests")"

echo "### A contributor can find the reward groups that paid them"
# A contributor can get the groupId a reward audit needs in two ways: the groups they observe
# (read from history, since a group is archived as it pays) and the reference on the payout.
RG=$(python3 cli.py reward groups Alice 2>/dev/null)
chk "Alice sees the groups that paid her" "yes" \
  "$(echo "$RG" | grep -q '^  DIST-' && echo yes || echo no)"
chk "...naming a transaction she contributed to" "yes" \
  "$(echo "$RG" | grep -q 'transaction: TX-2024-LISBON-001' && echo yes || echo no)"
chk "...and reporting them as distributed, not pending" "0" \
  "$(echo "$RG" | grep -c 'PENDING')"
# Charlie contributed to nothing, so he observes no group
chk "the consumer is an observer of no group" "yes" \
  "$(python3 cli.py reward groups Charlie 2>/dev/null | grep -q 'No reward groups' && echo yes || echo no)"

# the same id arrives on the payout offer
chk "a reward payout names the group that paid it" "yes" \
  "$(python3 cli.py holding offers Bob 2>/dev/null | grep -qE '^    for: reward: DIST-' && echo yes || echo no)"
chk "a corroboration bonus says what it is for" "yes" \
  "$(python3 cli.py holding offers Diana 2>/dev/null | grep -qE '^    for: (corroboration_bonus|dispute_resolution): ' && echo yes || echo no)"

# the id is directly usable: standing is granted and a price comes back
GID=$(echo "$RG" | grep -oE 'DIST-[^ ]+' | head -1)
python3 cli.py audit request Alice RewardVerification --reward-group-id "$GID" >/dev/null 2>&1
sleep 10
chk "the id it hands over is accepted for a reward audit" "yes" \
  "$(python3 cli.py audit proposals Alice 2>/dev/null | grep -q 'REWARDVERIFICATION' && echo yes || echo no)"

echo "==================== $PASS passed, $FAIL failed ===================="
exit $FAIL
