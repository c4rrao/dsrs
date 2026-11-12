# Sourced by the run-*.sh scripts. Party ids change on every ledger wipe, so they are read from
# what bootstrap.canton printed rather than configured.
party_id() {
    local id
    id=$(docker logs dsrs-canton 2>&1 | grep "$1 Party:" | tail -1 | awk '{print $NF}')
    if [ -z "$id" ]; then
        echo "could not find the $1 party in the Canton logs, is Canton running?" >&2
        exit 1
    fi
    echo "$id"
}
