# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Releases up to 1.3.4 are described in their
GitHub release notes and tags.

## [2.1.0] - 2026-11-23

### Added
- Writes that do not wait for a majority: `"ack": "local"` on a query, or `SERENGETI_WRITE_ACK=local` as a
  node's default. The write is acknowledged once it is in this node's log, also when no peer is
  reachable, and reaches the other nodes through sync. Nodes cut off from each other keep taking writes
  and converge when they meet. See docs/CONSISTENCY.md.
- Conflicting writes are kept. A row write records which writes to the row its node had seen, so two
  writes that had not seen each other are both kept: the newer is the row's value on every node, as
  before, and `SHOW CONFLICTS IN db.table` lists all versions until the next write to the row resolves
  them. New metric `serengeti_conflicts`. Log and snapshot records gain an optional `seen` field; 2.0
  ignores it, and 2.1 reads 2.0's files and peers.
- Indexes: `CREATE INDEX ON db.table (column)`, `DROP INDEX ON db.table (column)` and `SHOW INDEXES IN
  db.table`. A WHERE clause that compares an indexed column with a value (or one term of an AND that does)
  reads only the matching rows instead of scanning the table; results do not change. `_id` lookups and
  ranges never scan. Definitions replicate, each node builds its own, and nodes still on 2.0 in a cluster
  being upgraded are not sent them. They live in `indexes.jsonl`, not in the log, so a 2.0 binary can
  still open the data directory after a rollback. See docs/QUERY.md, "Indexes".
- `tests/upgrade/run.sh`, run in CI: a 2.0.0 node's data opened by this build, and a 3-node 2.0.0 cluster
  upgraded one node at a time under writes.
- Tombstones are dropped after `tombstone_ttl_hours` (default 168), and only once every peer has synced
  with the node by buckets since it started, so a 2.0 node or a node restored from a backup cannot bring a
  deleted row back (`serengeti restore` records the backup's newest change in `last-sync`). A cluster node
  that has not synced with a majority for longer than the TTL is stale: it neither syncs nor takes writes,
  but hands on, once to each peer, the changes stamped after its last majority sync, so writes it
  acknowledged while cut off are not lost.

### Fixed
- A node answering large requests (a SELECT of 20 MB, say) could be killed by the kernel (2.0.0 too): each
  answer went to the socket in one write, and the JDK keeps a direct buffer of the largest write per thread,
  outside the heap. Answers are written in slices of 64 KiB, and the image sets
  `-Djdk.nio.maxCachedBufferSize=262144`. Found by 2.2's fourth review.
- A node that had missed writes could run itself and its peers out of heap while catching up: it asked
  every peer at once for the rows of every differing bucket, in pages of 200 buckets, and a few thousand
  missed inserts touch most buckets. A node now runs at most two background row pulls at once, and four
  more for majority reads, sizes each page from the last (about 2,000 rows or 4 MiB), and builds at most
  four pages for its peers at once, answering 503 to more. Found by 2.2's soak test;
  `tests/memory/catchup.sh` reproduces it.
- A running node sometimes answered nothing for 20 to 83 s (2.0.0 has this too). It set the JDK server's
  60 s limit on reading a response; the server closes an overdue connection while holding the set that
  every request passes through, and closing a TLS connection waits for a write blocked on a peer that
  stopped reading, so every request thread waited. There is no response limit now; the 30 s limit on
  sending a request stays. A graceful stop, which could hang the same way until docker killed the node,
  waits for the HTTP server 3 s at most. Found by the soak test on the 2.2 branch.
- A node that was stopping answered 500 for about a second, also to writes it had already logged: it closed
  its cluster layer before its HTTP server. It now stops the HTTP server first and answers what still
  arrives with 503 "this node is stopping".
- A string holding an unpaired UTF-16 surrogate (`'\ud800'`) was stored as `?`, so two such `_id`s became one
  row after a restart. JSON with an unpaired surrogate, a body that is not valid UTF-8, and a body that
  cannot be read (a malformed chunked body) are now refused with 400; `\u` escapes take ASCII hex only.
- Nothing stopped a second node from opening a data directory in use (a second container on the same
  volume), and both would write the same log. A node holds a lock on `data_dir/lock`; a second one stops
  with exit status 2. `serengeti restore` takes the lock too.
- A SELECT listing tens of thousands of columns ran the node out of memory; a SELECT lists at most 256.
- A peer that does not answer no longer makes the others look unreachable. Each sync round waited for
  every peer in turn, so while a cut-off peer's calls ran to their timeout the reachable peers went
  uncontacted for longer than the 3-second window, and a node that had a majority refused writes with
  503. Each peer now syncs on its own. 2.0.0 has this bug.
- Responses no longer wait about 40 ms on a kept-alive connection. The JDK's HTTP server leaves Nagle's
  algorithm on unless `sun.net.httpserver.nodelay` is set, so each response waited for the client's delayed
  ACK and one connection did about 24 requests a second, node-to-node calls included. The node now sets it
  (an explicit `-Dsun.net.httpserver.nodelay=false` still wins). 2.0.0 has this bug.
- A log record with a valid checksum that cannot be read no longer counts as a torn tail. 2.0 truncated
  the log there, dropping that record and every one after it; such a record now stops the node from
  starting and nothing is discarded. Ops of a kind this version does not know (from a newer version, on
  the wire or in files after a downgrade) are skipped instead of failing the whole batch.
- After a failed fsync the node serves nothing from its data (queries, peers, `/ready`) until it is
  restarted: a write applied in memory may not be on disk. 2.0 kept serving.

### Changed
- Sync compares one hash per table, then per-bucket sums, and pulls only the rows that differ. 2.0
  pulled every database whose digest differed, so its traffic grew with the data under steady writes.
  Nodes still sync with 2.0 peers the old way, so a cluster can be upgraded one node at a time.
- Concurrent writes share fsyncs (group commit) instead of each waiting for its own under a lock. A write
  is still acknowledged only once it is on disk.
- A failed fsync now stops all further writes on the node instead of being retried.

### Removed
- org.json, lz4-java and snappy-java, which the 2.0 jar contained but did not use.

### Known issues, fixed in 2.2.0
- Two different deletes of one row look equal to sync, which compares live rows only: a node that holds
  the older delete does not get the newer one. A write made meanwhile on a node that was cut off,
  concurrent with both deletes and older than the newer one, can then show as the row's value on that
  node, also to a majority read.
- In a cluster, a statement right after a CREATE DATABASE, TABLE or INDEX can reach a node the change has
  not reached yet and get `does not exist`, for example behind a load balancer. Retrying it works.

## [2.0.0] - 2026-11-13

2.0 replaces the storage, query and server code of 1.x. It is not compatible with 1.x: the HTTP routes,
the query language and the files in the data directory all change.

### Fixed
- The node starts and serves HTTP. 1.3.4 exited with a NullPointerException when `data/` was missing,
  and otherwise held port 1985 without answering, on Java 11, 17 and 21.
- Inserted rows are kept. 1.x wrote schema files only, so rows were lost on restart.

### Added
- A write-ahead log with CRC32C checks, fsync before acknowledging by default (`SERENGETI_FSYNC`), and
  snapshots. Recovery drops a torn tail instead of failing.
- A query language with a hand-written parser: CREATE/DROP DATABASE and TABLE, SHOW, INSERT, SELECT with
  WHERE, ORDER BY and LIMIT, UPDATE, DELETE, COUNT(*). See docs/QUERY.md.
- `POST /v1/query`, `POST /v1/setup`, `POST /v1/password`, `GET /v1/status` and `GET /health`. See
  docs/API.md.
- Configuration from a properties file and `SERENGETI_*` variables, with secrets from variables or
  files. See docs/CONFIGURATION.md.
- HTTPS: `SERENGETI_TLS_KEYSTORE` (PKCS#12) switches the listener to TLS 1.2 and 1.3, with no plaintext
  listener.
- Replication across 1 to 7 nodes: majority writes, digest-based catch-up, `"consistency": "majority"`
  reads, a cluster key for `/internal/*`, and HTTPS between nodes with certificate and host name checks.
  A node with peers needs TLS or `SERENGETI_CLUSTER_PLAINTEXT=allow`. See docs/CONSISTENCY.md.
- One Docker image for linux/amd64 and linux/arm64, running as a non-root user, with a health check.
- `GET /ready` (no credentials; 503 while a readiness check fails) and `GET /metrics` (Prometheus text format,
  Basic credentials). See docs/API.md.
- One INFO log line per request, with method, route label, status, duration, address and user name. Never the
  body, the query string or credentials.
- `GET /v1/backup` streams a consistent snapshot to an admin account. `serengeti restore BACKUP_FILE` loads one
  into an empty data directory; accounts are not included. See docs/CONFIGURATION.md.
- Snapshot files, and so backups, carry a SHA-256 of their contents in the trailer. A backup without one is
  refused on restore.
- Tests: unit and property tests (`mvn verify`, Java 17 and 21), and container tests against the image
  (`tests/container/run.sh`), including `kill -9` during writes.
- A quick start that CI runs (`docs/quickstart.sh`), and clients for curl, Python (`urllib`) and
  JavaScript (`fetch`) under `examples/`, which CI runs in their own containers.

### Changed
- The default bind address is 127.0.0.1. The image binds all interfaces inside its container and
  publishes nothing.
- `POST /post` takes the same JSON body as `/v1/query` and is kept for 2.0 only.
- The dashboard and interactive console are replaced by one console page at `/`.
- Java 17 or later is required.
- Versions come from Git tags. `release.yml` publishes the image, the jar and the GitHub release for a
  tag; see VERSIONING.md. `version.txt` is removed.

### Removed
- The `admin`/`admin` account.
- Automatic discovery that connected to port 1985 on every address in the subnet. Peers are now listed
  in `SERENGETI_PEERS`, or found in an opt-in range of at most 256 addresses.
- The LSM storage engine, B-tree indexes, query optimizer and the other 1.x modules that the running
  server never used. They remain in the history and the v1.3.4 tag.

### Security
- Every route except `GET /health` needs HTTP Basic credentials, checked against bcrypt hashes in a
  file readable by the owner only. Failed logins are limited per address.
- Request bodies are limited to 1 MiB; JSON routes require `Content-Type: application/json`, and POSTs
  from another origin are refused. The console inserts data as text, under a Content-Security-Policy.
- Errors no longer include stack traces.
