#!/bin/bash
set -e
cd "$(dirname "$0")"
source ./party-ids.sh

export PORT=8101
export LEDGER_HOST=localhost
export LEDGER_PORT=6865
export LEDGER_USE_TLS=false
export USER_ID=escrow-backend
export ESCROW_API_KEY=dev-escrow-api-key
ESCROW_PARTY=$(party_id Escrow); export ESCROW_PARTY

cd ../backend && ./gradlew :escrow-stack:run
