#!/usr/bin/env bash
#
# Usage:
#   ./stop-system.sh                    # stacks and Navigator, Canton keeps running
#   ./stop-system.sh --all              # also Canton and the databases, ledger kept
#   ./stop-system.sh --all --wipe       # ...and the ledger volumes destroyed
#   ./stop-system.sh --navigator-port 7500
#
# Stacks are killed by port rather than by name: the Gradle launcher can die while the JVM
# behind it keeps the port.

set -uo pipefail

DEV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

STOP_CANTON=0
WIPE=0
NAVIGATOR_PORT=7500

say()  { printf '  %s\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --all)            STOP_CANTON=1; shift ;;
        --wipe)           WIPE=1; STOP_CANTON=1; shift ;;
        --navigator-port) NAVIGATOR_PORT="${2:-7500}"; shift 2 ;;
        -h|--help)        sed -n '3,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)                printf '  Unknown argument: %s\n' "$1" >&2; exit 1 ;;
    esac
done

port_pid() {
    ss -ltnp 2>/dev/null | grep ":$1 " | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2
}

stop_port() {
    local port=$1 label=$2 pid
    pid="$(port_pid "$port")"
    if [ -z "$pid" ]; then
        say "$label was not running (:$port free)"
        return 0
    fi
    kill -9 "$pid" 2>/dev/null
    for _ in $(seq 1 20); do
        sleep 0.5
        [ -z "$(port_pid "$port")" ] && { ok "stopped $label (pid $pid)"; return 0; }
    done
    warn "$label on :$port did not release the port"
    return 1
}

printf '\n\033[1m═══ Stopping ═══\033[0m\n'

stop_port 8100 "operator"
stop_port 8101 "escrow"
stop_port 8081 "participant"
stop_port 8103 "auditor"
stop_port "$NAVIGATOR_PORT" "navigator"

# The Gradle wrapper launchers outlive the JVMs they started and hold a daemon open.
LEFTOVER="$(pgrep -f 'GradleWrapperMain' 2>/dev/null)"
if [ -n "$LEFTOVER" ]; then
    printf '%s\n' "$LEFTOVER" | while read -r pid; do kill -9 "$pid" 2>/dev/null; done
    ok "cleaned up Gradle wrapper processes"
fi

if [ "$STOP_CANTON" -eq 1 ]; then
    cd "$DEV_DIR" || exit 1
    if [ "$WIPE" -eq 1 ]; then
        warn "destroying the ledger volumes (--wipe)"
        docker-compose down -v >/dev/null 2>&1 && ok "Canton and databases down, volumes removed"
    else
        docker-compose down >/dev/null 2>&1 && ok "Canton and databases down, volumes kept"
    fi
else
    say "Canton and the databases are still running (--all to stop them)"
fi

printf '\n'
