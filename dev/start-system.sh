#!/usr/bin/env bash
#
# Usage:
#   ./start-system.sh
#   ./start-system.sh --keep-ledger                # restart the stacks only
#   ./start-system.sh --navigator-port 7500        # also start Daml Navigator
#
# Without --keep-ledger the ledger is wiped (docker-compose down -v). Stack output goes to
# dev/logs/. Stop with ./stop-system.sh.

set -uo pipefail

DEV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DEV_DIR/.." && pwd)"
LOG_DIR="$DEV_DIR/logs"

NAVIGATOR_PORT=""
KEEP_LEDGER=0
RUN_NAVIGATOR=0

# Readiness is a bound port, not /health: a stack that failed to restart can leave the old
# process answering /health against party ids that no longer exist.
OPERATOR_PORT=8100
ESCROW_PORT=8101
AUDITOR_PORT=8103

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
say()  { printf '  %s\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }
die()  { printf '\n  \033[31m✗ %s\033[0m\n\n' "$1" >&2; exit 1; }

usage() {
    sed -n '3,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --navigator-port) NAVIGATOR_PORT="${2:-}"; RUN_NAVIGATOR=1; shift 2 || die "--navigator-port needs a value" ;;
        --keep-ledger)    KEEP_LEDGER=1; shift ;;
        -h|--help)        usage 0 ;;
        *)                printf '\n  Unknown argument: %s\n' "$1" >&2; usage 1 ;;
    esac
done

if [ "$RUN_NAVIGATOR" -eq 1 ]; then
    [[ "$NAVIGATOR_PORT" =~ ^[0-9]+$ ]] || die "--navigator-port must be a number, got '$NAVIGATOR_PORT'"
fi
PARTICIPANT_PORT=8081

port_pid() {
    ss -ltnp 2>/dev/null | grep ":$1 " | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2
}

free_port() {
    local port=$1 label=$2 pid
    pid="$(port_pid "$port")"
    [ -n "$pid" ] || return 0
    say "stopping $label already on :$port (pid $pid)"
    kill -9 "$pid" 2>/dev/null
    for _ in $(seq 1 20); do
        sleep 0.5
        [ -z "$(port_pid "$port")" ] && return 0
    done
    die "port $port is still held after killing pid $pid"
}

wait_for_port() {
    local port=$1 label=$2 logfile=$3 timeout=${4:-300} waited=0
    printf '  waiting for %s on :%s ' "$label" "$port"
    while [ -z "$(port_pid "$port")" ]; do
        sleep 3; waited=$((waited + 3))
        printf '.'
        if [ "$waited" -ge "$timeout" ]; then
            printf '\n'
            [ -f "$logfile" ] && { warn "last 20 lines of $(basename "$logfile"):"; tail -20 "$logfile" | sed 's/^/      /'; }
            die "$label did not come up within ${timeout}s"
        fi
    done
    printf '\n'
    ok "$label listening on :$port (pid $(port_pid "$port"))"
}

start_stack() {
    local label=$1 script=$2 port=$3; shift 3
    local logfile="$LOG_DIR/$label.log"
    free_port "$port" "$label"
    say "starting $label -> logs/$label.log"
    # The subshell's own output is redirected too, otherwise it keeps our stdout open and
    # `./start-system.sh | tee` never sees EOF.
    ( cd "$DEV_DIR" && setsid nohup "./$script" "$@" </dev/null >"$logfile" 2>&1 & ) >/dev/null 2>&1
    wait_for_port "$port" "$label" "$logfile"
}

health() {
    local label=$1 port=$2 body
    body="$(curl -s -m 10 "http://localhost:$port/health" 2>/dev/null)"
    if printf '%s' "$body" | grep -q '"status"[[:space:]]*:[[:space:]]*"healthy"'; then
        printf '  \033[32m✓\033[0m %-14s :%-5s %s\n' "$label" "$port" "$(printf '%s' "$body" | tr -d '\n ' )"
        return 0
    fi
    printf '  \033[31m✗\033[0m %-14s :%-5s %s\n' "$label" "$port" "${body:-no response}"
    return 1
}

bold ""
bold "═══ Data Sharing Reward System ═══"
bold ""

command -v docker  >/dev/null || die "docker is not on PATH"
command -v ss      >/dev/null || die "ss is not on PATH (install iproute2)"
docker info >/dev/null 2>&1   || die "the docker daemon is not reachable - is it running?"
[ "$RUN_NAVIGATOR" -eq 1 ] && { command -v daml >/dev/null || die "daml is not on PATH (needed for Navigator)"; }

mkdir -p "$LOG_DIR"

bold "[1/6] Canton + databases"
cd "$DEV_DIR" || die "cannot cd to $DEV_DIR"

# A stale network makes `up` fail with "needs to be recreated"; `down` without -v fixes that
# and keeps the volumes.
compose_up() {
    local out
    if out="$(docker-compose up -d 2>&1)"; then return 0; fi
    if printf '%s' "$out" | grep -q "needs to be recreated"; then
        warn "docker-compose network is stale, recreating it (volumes are kept)"
        docker-compose down >/dev/null 2>&1
        if out="$(docker-compose up -d 2>&1)"; then return 0; fi
    fi
    printf '%s\n' "$out" | sed 's/^/      /'
    return 1
}

canton_running() {
    docker ps --filter name=dsrs-canton --filter status=running --format '{{.Names}}' 2>/dev/null | grep -q dsrs-canton
}

if [ "$KEEP_LEDGER" -eq 1 ]; then
    # Canton cannot be restarted onto an existing ledger: the bootstrap re-enables parties that
    # already exist and the container exits. So the ledger is kept only by not touching Canton.
    if ! canton_running; then
        die "--keep-ledger needs Canton already running, and it is not.
    Canton cannot be restarted onto an existing ledger: it boots with --bootstrap, and
    re-enabling parties that already exist fails with TOPOLOGY_MAPPING_ALREADY_EXISTS,
    which stops the container. Re-run without --keep-ledger to start a fresh ledger."
    fi
    ok "Canton left running untouched - ledger and party ids preserved (--keep-ledger)"
else
    warn "wiping the ledger (docker-compose down -v) - pass --keep-ledger to preserve it"
    # Navigator reads the party list once at startup, so one that outlives a wipe shows dead parties.
    [ -n "$NAVIGATOR_PORT" ] && free_port "$NAVIGATOR_PORT" "navigator"
    docker-compose down -v >/dev/null 2>&1
    compose_up || die "docker-compose up failed"

    printf '  waiting for Canton bootstrap '
    BOOTSTRAPPED=0
    for _ in $(seq 1 80); do
        if docker logs dsrs-canton 2>&1 | grep -q "Bootstrap complete"; then BOOTSTRAPPED=1; break; fi
        if ! canton_running; then
            printf '\n'
            warn "last 20 lines of Canton's log:"; docker logs --tail 20 dsrs-canton 2>&1 | sed 's/^/      /'
            die "the Canton container stopped while bootstrapping"
        fi
        sleep 3; printf '.'
    done
    printf '\n'
    [ "$BOOTSTRAPPED" -eq 1 ] || {
        warn "last 20 lines of Canton's log:"; docker logs --tail 20 dsrs-canton 2>&1 | sed 's/^/      /'
        die "Canton did not finish bootstrapping within 240s"
    }
    ok "Canton bootstrapped"
fi

[ -n "$(port_pid 6865)" ] || ss -ltn 2>/dev/null | grep -q ":6865 " || die "Canton's ledger API is not listening on :6865"
docker logs dsrs-canton 2>&1 | grep -E "^  (Operator|Auditor|Escrow|Alice|Bob|Charlie|Diana|Eve):" | sed 's/^/    /'

bold ""
bold "[2/6] Backend stacks"
# The escrow must be up before any query proposal needs an acceptor.
start_stack operator    run-operator.sh    "$OPERATOR_PORT"
start_stack escrow      run-escrow.sh      "$ESCROW_PORT"
start_stack participant run-participant.sh "$PARTICIPANT_PORT"
start_stack auditor     run-auditor.sh     "$AUDITOR_PORT"

bold ""
bold "[3/6] Auditor ledger sync"
printf '  waiting for the auditor to reach "streaming" '
SYNCED=0
for _ in $(seq 1 40); do
    if curl -s -m 5 "http://localhost:$AUDITOR_PORT/health/detailed" 2>/dev/null | grep -q '"syncStatus"[[:space:]]*:[[:space:]]*"streaming"'; then
        SYNCED=1; break
    fi
    sleep 3; printf '.'
done
printf '\n'
if [ "$SYNCED" -eq 1 ]; then
    ok "auditor is streaming the ledger"
else
    warn "auditor did not report \"streaming\" within 120s - verification commands may not work yet"
fi

bold ""
bold "[4/6] Party IDs"
# Party ids change on every wipe, and the CLI reads them from testing/parties.py.
cd "$ROOT_DIR/testing" || die "cannot cd to $ROOT_DIR/testing"
if PARTY_OUT="$(./update-parties.sh 2>&1)"; then
    ok "testing/parties.py regenerated"
    printf '%s\n' "$PARTY_OUT" | grep -E "^  (Operator|Auditor|Escrow|Alice|Bob|Charlie|Diana|Eve):" | sed 's/^/    /'
else
    printf '%s\n' "$PARTY_OUT" | sed 's/^/      /'
    die "update-parties.sh failed - the CLI will not work until this succeeds"
fi

bold ""
bold "[5/6] Navigator"
if [ "$RUN_NAVIGATOR" -eq 1 ]; then
    free_port "$NAVIGATOR_PORT" "navigator"
    say "starting navigator -> logs/navigator.log"
    ( cd "$ROOT_DIR/daml" && setsid nohup daml navigator server localhost 6865 --port "$NAVIGATOR_PORT" \
        </dev/null >"$LOG_DIR/navigator.log" 2>&1 & ) >/dev/null 2>&1
    wait_for_port "$NAVIGATOR_PORT" "navigator" "$LOG_DIR/navigator.log" 180
    say "open http://localhost:$NAVIGATOR_PORT"
else
    say "skipped (no --navigator-port)"
fi

bold ""
bold "[6/6] Health checks"
FAILED=0
health "operator"    "$OPERATOR_PORT"    || FAILED=1
health "escrow"      "$ESCROW_PORT"      || FAILED=1
health "participant" "$PARTICIPANT_PORT" || FAILED=1
health "auditor"     "$AUDITOR_PORT"     || FAILED=1

bold ""
if [ "$FAILED" -ne 0 ]; then
    die "one or more stacks are unhealthy - see dev/logs/"
fi

bold "═══ Ready ═══"
say ""
[ "$RUN_NAVIGATOR" -eq 1 ] && say "Navigator:    http://localhost:$NAVIGATOR_PORT"
say "CLI:          cd $ROOT_DIR/testing"
say "Pricing:      python3 cli.py system init-pricing     (required once per fresh ledger)"
say "Test:         ./test-e2e.sh                           (end-to-end test, needs a fresh ledger)"
say "Logs:         $LOG_DIR"
say "Stop:         $DEV_DIR/stop-system.sh"
say ""
