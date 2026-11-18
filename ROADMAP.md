# Roadmap

This page says what 2.2 does, what might come next, and what Serengeti does not plan to do. Nothing
here has a date.

## What 2.2 does

- Writes go to a write-ahead log with CRC32C checks and are forced to disk before they are acknowledged
  (`SERENGETI_FSYNC`, default `always`); concurrent writes share fsyncs. Snapshots bound the log, and
  recovery drops an incomplete last record. A lock on the data directory keeps a second node off it.
- Tables of JSON rows, and the query language in [docs/QUERY.md](docs/QUERY.md): CREATE and DROP for
  databases, tables and indexes on 1 to 4 columns, SHOW, INSERT (with `ON CONFLICT DO NOTHING` or `DO UPDATE
  SET`, and object and array values), SELECT and SELECT DISTINCT with WHERE (comparisons, IN, LIKE, IS
  NULL), ORDER BY on one or more keys, LIMIT and OFFSET, COUNT, COUNT(DISTINCT), SUM, MIN, MAX and AVG with
  GROUP BY and HAVING, UPDATE (with `SET n = n + 1`) and DELETE. Indexes answer comparisons, IN, LIKE
  prefixes and ORs of them, and give rows in their order for `ORDER BY ... LIMIT`.
- An HTTP API ([docs/API.md](docs/API.md)) in which every route except `GET /health` and `GET /ready`
  needs HTTP Basic credentials. Accounts have a role (metrics, read, write or admin), read and write accounts
  can be limited to some databases and tables, and accounts replicate across the cluster. The first admin password comes from the environment, a file or a one-time setup token.
  Failed logins are limited per address.
- A change feed: `GET /v1/changes` follows a table's changes with a cursor and a long poll; cursors
  outlive a restart of the node.
- A memory limit: writes that add data are refused with 503 before the data fills the heap.
- HTTPS with a PKCS#12 key store, TLS 1.2 and 1.3 only ([docs/CONFIGURATION.md](docs/CONFIGURATION.md)),
  and `serengeti-certs` in the image to make a cluster's certificates.
- Replication across 1 to 7 nodes from a static peer list, or an opt-in address range of at most 256
  addresses ([docs/CONSISTENCY.md](docs/CONSISTENCY.md)). A write is acknowledged once a majority has it
  on disk, or, with `"ack": "local"`, once this node has it. Nodes sync by comparing per-table,
  per-bucket and per-row hashes and pull only the rows that differ. Writes that had not seen each other are all kept
  and listed by `SHOW CONFLICTS` until the next write to the row. Reads can ask for a majority.
  Node-to-node traffic needs a cluster key and uses TLS.
- `GET /ready`, Prometheus metrics at `GET /metrics`, a request log, and backup and restore
  (`GET /v1/backup`, `serengeti backup` or on a schedule into `backup_dir`, and `serengeti restore`).
- `serengeti shell`, `serengeti import` and `serengeti export` (NDJSON and CSV, [docs/IMPORT.md](docs/IMPORT.md)),
  and clients for Python and JavaScript in [clients/](clients/).
- Rolling upgrades from 2.0 and 2.1, and a rollback to either on the same data directory.
- A console at `/`, a container image for linux/amd64 and linux/arm64, and examples for Docker Compose,
  Kubernetes, and Prometheus with Grafana.

2.2 has these limits:

- every node keeps every database in memory;
- an index on several columns is read only for its first columns that a condition fixes (=, IN, then one
  range); a WHERE without a term an index answers scans its table, and so does an OR unless an index
  answers each branch and they find at most 1/16 of the rows;
- ORDER BY sorts the matching rows in memory unless its keys are an index's columns after those the WHERE
  fixes, all in one direction ([docs/QUERY.md](docs/QUERY.md), "ORDER BY through an index");
- no joins and no transactions across statements; nothing is linearizable;
- an account has one role, for every database and table its list gives it: it cannot read one table and
  write another;
- a node keeps answering while up to 1,024 connections (`http_connections`) send or read slowly, and
  degrades past that: put a proxy that limits connections per address in front of a node that the internet
  reaches.

## Possible next steps

Not committed to, and in no particular order: clients published as packages.

## Not planned

- Joins.
- Transactions across statements.
- Multi-region clusters.
