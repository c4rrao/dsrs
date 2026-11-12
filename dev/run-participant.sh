#!/bin/bash
# A single participant stack serves every party, since they all live on the same Canton node.
set -e
cd "$(dirname "$0")"
source ./party-ids.sh

export PORT=8081
export LEDGER_HOST=localhost
export LEDGER_PORT=6865
export LEDGER_USE_TLS=false
export USER_ID=participant1-backend
export ADMIN_API_KEY=dev-participant1-admin-key
export DB_HOST=localhost
export DB_PORT=5433
export DB_NAME=participant1_db
export DB_USER=participant1
export DB_PASSWORD=participant1_secret
export JWT_SECRET=dev-participant1-secret-key-min-32-chars
export OPERATOR_BASE_URL=http://localhost:8100
export OPERATOR_ADMIN_API_KEY=dev-admin-api-key
OPERATOR_PARTY=$(party_id Operator); export OPERATOR_PARTY
AUDITOR_PARTY=$(party_id Auditor); export AUDITOR_PARTY

cd ../backend && ./gradlew :participant-stack:run
