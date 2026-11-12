
# a single participant stack serves every party
PARTICIPANT_STACKS = {
    1: {
        "base_url": "http://localhost:8081",
        "jwt_secret": "dev-participant1-secret-key-min-32-chars",
        "canton_ledger_port": 6865,
    },
}

OPERATOR_STACK = {
    "base_url": "http://localhost:8100",
    "admin_api_key": "dev-admin-api-key",
    "canton_ledger_port": 6865,
}

ESCROW_STACK = {
    "base_url": "http://localhost:8101",
    "api_key": "dev-escrow-api-key",
    "canton_ledger_port": 6865,
}

AUDITOR_STACK = {
    "base_url": "http://localhost:8103",
    "admin_api_key": "test-admin-key",
    "canton_ledger_port": 6865,
}

# Party ids change on every ledger wipe, so they live in the gitignored parties.py written by
# update-parties.sh.
try:
    from parties import TEST_PARTIES, OPERATOR_PARTY, AUDITOR_PARTY, ESCROW_PARTY
except ImportError as e:
    raise ImportError(
        "testing/parties.py not found. Run ./update-parties.sh (Canton must be "
        "running) to generate it."
    ) from e

# seconds
POLL_INTERVAL = 1.0
MAX_WAIT_TIME = 30.0
