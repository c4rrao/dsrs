#!/bin/bash
set -e
cd "$(dirname "$0")"
source ./party-ids.sh

export PORT=8103
export LEDGER_HOST=localhost
export LEDGER_PORT=6865
export LEDGER_USE_TLS=false
export USER_ID=auditor_user
export DB_HOST=localhost
export DB_PORT=5452
export DB_NAME=auditor_db
export DB_USER=auditor
export DB_PASSWORD=auditor_secret
export ADMIN_API_KEY=test-admin-key
export SYNC_ON_STARTUP=true
AUDITOR_PARTY=$(party_id Auditor); export AUDITOR_PARTY
OPERATOR_PARTY=$(party_id Operator); export OPERATOR_PARTY

cd ../backend && ./gradlew :auditor-stack:run
