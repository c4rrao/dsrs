# Data Sharing Reward System

Prototype of a platform that rewards real estate professionals for sharing transaction data,
built for the MSc thesis *Blockchain-Based Reward System for Information Sharing* (Instituto
Superior Técnico, 2026). The rules live in Daml smart contracts on Canton. Four Kotlin services
act on them, one for each infrastructure role (operator, escrow, auditor) and one shared by
contributors and consumers (participant).

## Layout

| Path | Contents |
|---|---|
| `daml/` | The ledger model: contributions and disputes, queries, pricing, rewards, tokens and audits. Contract tests are in `daml/daml/Test/`. |
| `backend/` | The four services and their shared library, with unit tests. |
| `canton/` | Canton configuration, the bootstrap script that allocates parties and uploads the DAR, and the Dockerfile for the Canton image. |
| `dev/` | Docker Compose for Canton and the databases, and the scripts that start and stop the whole system. |
| `testing/` | A command-line client (`cli.py`), the end-to-end test, and a generator for synthetic transaction data (`seed/`). |

## Requirements

- Daml SDK 2.9.3
- JDK 21
- Docker with `docker-compose`, plus `curl` and `ss`
- Python 3 with `pip install -r testing/requirements.txt`
- A local Canton 2.8.12 image. There is no public one, so put `canton-open-source-2.8.12.tar.gz`
  from the [Canton v2.8.12 release](https://github.com/digital-asset/canton/releases/tag/v2.8.12)
  in `canton/docker-build/` and run `docker build -t dsrs-canton-2.8.12:local .` there.

## Running the tests

Contract tests:

```bash
cd daml
daml test
```

Service unit tests. The services use Java bindings generated from the DAR, so build it first:

```bash
cd daml && daml build
cd ../backend && ./gradlew test
```

End-to-end test, against a running system on a fresh ledger:

```bash
cd dev && ./start-system.sh          # wipes the ledger; stacks log to dev/logs/
cd ../testing && ./test-e2e.sh
cd ../dev && ./stop-system.sh --all
```

`start-system.sh` brings up Canton, the databases and the four services (operator on 8100, escrow
on 8101, participant on 8081, auditor on 8103, Ledger API on 6865). Once it is up,
`python3 testing/cli.py --help` lists what the client can do.
