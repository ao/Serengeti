# Roadmap

This page says what 2.0 does, what comes next, and what Serengeti does not plan to do. The planned items
are listed in the order they will be built. None has a date.

## What 2.0 does

- One node per process, with its data in one directory. Writes go to a write-ahead log with CRC32C checks
  and are forced to disk before they are acknowledged (`SERENGETI_FSYNC`, default `always`). Snapshots
  bound the log, and recovery drops an incomplete last record.
- Tables of JSON rows, and the query language in [docs/QUERY.md](docs/QUERY.md): CREATE and DROP for
  databases and tables, SHOW, INSERT, SELECT with WHERE, ORDER BY and LIMIT, COUNT(*), UPDATE and DELETE.
- An HTTP API ([docs/API.md](docs/API.md)) in which every route except `GET /health` needs HTTP Basic
  credentials. The admin password comes from the environment, a file or a one-time setup token. Failed
  logins are limited per address.
- HTTPS with a PKCS#12 key store, TLS 1.2 and 1.3 only ([docs/CONFIGURATION.md](docs/CONFIGURATION.md)).
- A console at `/`, and a container image for linux/amd64 and linux/arm64.

2.0 has these limits, and they stay in 2.0.x:

- one node; there is no replication yet;
- no indexes, so every WHERE scans its table;
- no joins and no transactions across statements.

## Planned

1. Replication across 1 to 7 nodes. A static peer list. Each write is applied on the node that
   receives it and sent to every peer, and the client is told it succeeded once a majority of nodes,
   including that node, has it on disk. Rows carry a version (a hybrid logical clock and the node id), and
   concurrent writes to the same row keep the higher version. Node-to-node traffic needs a cluster key and
   uses TLS. A node that was down catches up from a peer before it accepts writes. With fewer than a
   majority reachable, writes fail rather than being accepted.
2. `GET /ready`. Answers 200 once the data is loaded and, in a cluster, the node has caught up.
3. `GET /metrics`. Prometheus text format, behind authentication.
4. Backup and restore. `GET /v1/backup` streams a consistent snapshot to an admin, and
   `serengeti restore FILE` loads one into an empty data directory.

Replication will not give linearizable reads or writes: two clients writing the same row through different
nodes at the same time get the higher version, not a queue.

## Not planned

- Joins.
- Transactions across statements.
- Multi-region clusters.
