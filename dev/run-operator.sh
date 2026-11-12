#!/bin/bash
set -e
cd "$(dirname "$0")"
source ./party-ids.sh

export PORT=8100
export LEDGER_HOST=localhost
export LEDGER_PORT=6865
export LEDGER_USE_TLS=false
export USER_ID=operator-backend
export DB_HOST=localhost
export DB_PORT=5450
export DB_NAME=operator_db
export DB_USER=operator
export DB_PASSWORD=operator_secret
export ADMIN_API_KEY=dev-admin-api-key
OPERATOR_PARTY=$(party_id Operator); export OPERATOR_PARTY
ESCROW_PARTY=$(party_id Escrow); export ESCROW_PARTY
AUDITOR_PARTY=$(party_id Auditor); export AUDITOR_PARTY

cd ../backend && ./gradlew :operator-stack:run
