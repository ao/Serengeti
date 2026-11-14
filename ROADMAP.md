# Roadmap

This page says what 2.1 does, what might come next, and what Serengeti does not plan to do. Nothing
here has a date.

## What 2.1 does

- Writes go to a write-ahead log with CRC32C checks and are forced to disk before they are acknowledged
  (`SERENGETI_FSYNC`, default `always`); concurrent writes share fsyncs. Snapshots bound the log, and
  recovery drops an incomplete last record.
- Tables of JSON rows, and the query language in [docs/QUERY.md](docs/QUERY.md): CREATE and DROP for
  databases, tables and single-column indexes, SHOW, INSERT, SELECT with WHERE, ORDER BY and LIMIT,
  COUNT(*), UPDATE and DELETE.
- An HTTP API ([docs/API.md](docs/API.md)) in which every route except `GET /health` and `GET /ready`
  needs HTTP Basic credentials. The admin password comes from the environment, a file or a one-time
  setup token. Failed logins are limited per address.
- HTTPS with a PKCS#12 key store, TLS 1.2 and 1.3 only ([docs/CONFIGURATION.md](docs/CONFIGURATION.md)).
- Replication across 1 to 7 nodes from a static peer list, or an opt-in address range of at most 256
  addresses ([docs/CONSISTENCY.md](docs/CONSISTENCY.md)). A write is acknowledged once a majority has it
  on disk, or, with `"ack": "local"`, once this node has it. Nodes sync by comparing per-table and
  per-bucket hashes and pull only the rows that differ. Writes that had not seen each other are all kept
  and listed by `SHOW CONFLICTS` until the next write to the row. Reads can ask for a majority.
  Node-to-node traffic needs a cluster key and uses TLS.
- `GET /ready`, Prometheus metrics at `GET /metrics`, a request log, and backup and restore
  (`GET /v1/backup`, `serengeti restore`).
- Rolling upgrades from 2.0, and a rollback to 2.0 on the same data directory.
- A console at `/`, and a container image for linux/amd64 and linux/arm64.

2.1 has these limits:

- every node keeps every database in memory;
- indexes cover one column each; a WHERE without an indexed comparison scans its table;
- no joins and no transactions across statements; nothing is linearizable;
- accounts are not replicated, and there is one account, `admin`.

## Possible next steps

Not committed to, and in no particular order: accounts with read-only roles; replicating accounts;
a change feed (follow a table's writes over HTTP); indexes on more than one column; clients for Python
and JavaScript beyond the examples.

## Not planned

- Joins.
- Transactions across statements.
- Multi-region clusters.
