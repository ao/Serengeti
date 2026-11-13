# Roadmap

This page says what 2.0 does, what might come next, and what Serengeti does not plan to do. Nothing
here has a date.

## What 2.0 does

- Writes go to a write-ahead log with CRC32C checks and are forced to disk before they are acknowledged
  (`SERENGETI_FSYNC`, default `always`). Snapshots bound the log, and recovery drops an incomplete last
  record.
- Tables of JSON rows, and the query language in [docs/QUERY.md](docs/QUERY.md): CREATE and DROP for
  databases and tables, SHOW, INSERT, SELECT with WHERE, ORDER BY and LIMIT, COUNT(*), UPDATE and DELETE.
- An HTTP API ([docs/API.md](docs/API.md)) in which every route except `GET /health` and `GET /ready`
  needs HTTP Basic credentials. The admin password comes from the environment, a file or a one-time
  setup token. Failed logins are limited per address.
- HTTPS with a PKCS#12 key store, TLS 1.2 and 1.3 only ([docs/CONFIGURATION.md](docs/CONFIGURATION.md)).
- Replication across 1 to 7 nodes from a static peer list, or an opt-in address range of at most 256
  addresses. A write is acknowledged once a majority has it on disk; nodes that were down or partitioned
  catch up; reads can ask for a majority. Node-to-node traffic needs a cluster key and uses TLS
  ([docs/CONSISTENCY.md](docs/CONSISTENCY.md)).
- `GET /ready`, Prometheus metrics at `GET /metrics`, a request log, and backup and restore
  (`GET /v1/backup`, `serengeti restore`).
- A console at `/`, and a container image for linux/amd64 and linux/arm64.

2.0 has these limits, and they stay in 2.0.x:

- no indexes, so every WHERE scans its table;
- no joins and no transactions across statements;
- conflicting writes to one row keep the higher version (last writer wins); nothing is linearizable;
- a node that differs from a peer pulls the whole database, so databases should fit comfortably in
  memory on every node;
- accounts are not replicated, and there is one account, `admin`.

## Possible next steps

Not committed to, and in no particular order: secondary indexes; accounts with read-only roles;
replicating accounts; syncing only the rows that differ instead of whole databases.

## Not planned

- Joins.
- Transactions across statements.
- Multi-region clusters.
