# Roadmap

This page says what 2.2 does, what might come next, and what Serengeti does not plan to do. Nothing
here has a date.

## What 2.2 does

- Writes go to a write-ahead log with CRC32C checks and are forced to disk before they are acknowledged
  (`SERENGETI_FSYNC`, default `always`); concurrent writes share fsyncs. Snapshots bound the log, and
  recovery drops an incomplete last record. A lock on the data directory keeps a second node off it.
- Tables of JSON rows, and the query language in [docs/QUERY.md](docs/QUERY.md): CREATE and DROP for
  databases, tables and single-column indexes, SHOW, INSERT, SELECT with WHERE (comparisons, IN, LIKE,
  IS NULL), ORDER BY and LIMIT, COUNT, SUM, MIN, MAX and AVG with GROUP BY, UPDATE and DELETE.
- An HTTP API ([docs/API.md](docs/API.md)) in which every route except `GET /health` and `GET /ready`
  needs HTTP Basic credentials. Accounts have a role (metrics, read, write or admin) and replicate across the
  cluster. The first admin password comes from the environment, a file or a one-time setup token.
  Failed logins are limited per address.
- A change feed: `GET /v1/changes` follows a table's changes with a cursor and a long poll.
- A memory limit: writes that add data are refused with 503 before the data fills the heap.
- HTTPS with a PKCS#12 key store, TLS 1.2 and 1.3 only ([docs/CONFIGURATION.md](docs/CONFIGURATION.md)),
  and `serengeti-certs` in the image to make a cluster's certificates.
- Replication across 1 to 7 nodes from a static peer list, or an opt-in address range of at most 256
  addresses ([docs/CONSISTENCY.md](docs/CONSISTENCY.md)). A write is acknowledged once a majority has it
  on disk, or, with `"ack": "local"`, once this node has it. Nodes sync by comparing per-table and
  per-bucket hashes and pull only the rows that differ. Writes that had not seen each other are all kept
  and listed by `SHOW CONFLICTS` until the next write to the row. Reads can ask for a majority.
  Node-to-node traffic needs a cluster key and uses TLS.
- `GET /ready`, Prometheus metrics at `GET /metrics`, a request log, and backup and restore
  (`GET /v1/backup`, `serengeti backup` or on a schedule into `backup_dir`, and `serengeti restore`).
- `serengeti shell`, and clients for Python and JavaScript in [clients/](clients/).
- Rolling upgrades from 2.0 and 2.1, and a rollback to either on the same data directory.
- A console at `/`, a container image for linux/amd64 and linux/arm64, and examples for Docker Compose,
  Kubernetes, and Prometheus with Grafana.

2.2 has these limits:

- every node keeps every database in memory;
- indexes cover one column each; a WHERE without an indexed comparison scans its table;
- no joins and no transactions across statements; nothing is linearizable;
- a role applies to every database: there are no permissions per database or table;
- the HTTP server serves 64 requests at a time and is not hardened against slow clients: put a buffering
  proxy in front of a node that untrusted networks reach.

## Possible next steps

Not committed to, and in no particular order: permissions per database; indexes on more than one column;
DISTINCT and HAVING; clients published as packages; a change feed that survives a restart.

## Not planned

- Joins.
- Transactions across statements.
- Multi-region clusters.
