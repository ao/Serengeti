# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Releases up to 1.3.4 are described in their
GitHub release notes and tags.

## [Unreleased] - 2.2.0

### Added
- `serengeti-certs` in the image makes a cluster's CA, node key stores, trust store and password with
  keytool, and deletes the CA key afterwards unless asked to keep it. `examples/cluster/compose.yaml` runs
  three TLS nodes with Docker Compose, publishing to 127.0.0.1 only; CI runs it.
- `examples/kubernetes/`: a three-node cluster as a StatefulSet with TLS, a volume per pod, a ClusterIP
  Service and a disruption budget, pods as uid 10001 on a read-only root (and `fsGroupChangePolicy:
  OnRootMismatch`, so the kubelet leaves the account files' modes alone). `run.sh` runs it on a kind
  cluster (pods ready, a write on every pod, a deleted pod back with its data); CI runs it.
- Metrics for the cluster and the node's state: `serengeti_ready`, `serengeti_disk_failed`,
  `serengeti_cluster_nodes`, `serengeti_cluster_reachable_nodes`, `serengeti_cluster_quorum`,
  `serengeti_cluster_caught_up`, `serengeti_cluster_stale` and `serengeti_sync_ops_pulled_total`; and
  `serengeti_tls_expiry_time_seconds{store}`, when the first certificate of the key store or trust store
  expires (a start also logs a warning within 30 days of it, and an error once it has passed).
- `examples/monitoring/`: Prometheus and Grafana for the Compose cluster. Prometheus scrapes every node
  over HTTPS with an account of the new `metrics` role; 18 alert rules, each with a promtool unit test; a dashboard of 14
  panels. `run.sh` checks the scrapes, every dashboard query and an alert for a stopped node; CI runs it.
- A change feed: `GET /v1/changes?db=&table=&after=&wait=` returns the changes a node applied after a
  cursor (rows written there or merged from peers, tables and databases dropped or created), long-polling
  up to 60 s. A change is listed once the node has it on disk, and the feed answers 503 after an fsync of
  the log fails. A node keeps the last `changes_buffer` (10,000) changes, fewer when together they would
  take more than a sixteenth of the heap (metrics `serengeti_changes_held` and `serengeti_changes_bytes`);
  an older cursor gets 410. See docs/API.md, "Changes".
- Change feed cursors outlive a restart, a crash and a checkpoint on the same node. At start the node replays
  its log with the feed attached, which lists the log's changes again with the same numbers; each
  checkpoint writes the epoch and the last `changes_buffer` changes to `changes.jsonl`, and
  `changes-marks.jsonl` records where tombstones were dropped so the replay drops them at the same place.
  Nothing more is written per write. The epoch changes only after a restore, on an emptied directory, after
  a log that lost a whole record, after a crash inside a checkpoint's last steps, and with `fsync` `off`
  after a stop that was not clean. With `fsync` `interval`, a change is now listed after the force that
  covers it. See docs/CONFIGURATION.md, "Data directory".
- Accounts and roles. An admin creates, changes and deletes accounts over `/v1/users`; each account is
  `metrics` (`/metrics` and `/v1/status` only), `read` (SELECT and SHOW), `write` (also writes and schema
  changes) or `admin` (also accounts and backups), and a write by a read account is refused with 403. In a
  cluster, accounts replicate, and a node that was down, or is out of date, catches up. An account's role,
  list of databases and password merge apart, each from the newest change to it, so a password reset, a role
  and a list given on different nodes at the same moment all stand. An account's own password change moves
  only the password, and loses to an admin's reset it had not seen. Making and deleting an account each
  start a new version of it that wins whole, so no change made on a node that had not heard of a delete
  brings the account back, and an account made again takes nothing done to the one deleted. If two admins
  deleted on two nodes at once leave
  none, the other accounts keep working, and a restart with `SERENGETI_ADMIN_PASSWORD` (or a setup token)
  makes the admin again (in a cluster, once it has pulled the accounts from a peer, so an admin that was
  replaced does not come back). Account changes stamped more than 60 s ahead of a node's clock are left out
  until the clocks agree, as row changes are. Accounts are kept in `accounts.json`; `users.json` keeps the
  admin and write accounts readable by 2.0 and 2.1, and a password one of them changed there is taken back
  on the next start as the account's own change, so it does not undo a 2.2 admin's delete or role change.
  See docs/API.md, "Accounts".
- Accounts limited to some databases: `PUT /v1/users/NAME` with `"databases": ["shop", ...]` (1 to 100
  names) limits a read or write account to those, and `"databases": null` removes the limit. Any statement
  that names another database, whether or not it exists, and the change feed for one, get 403 `this account
  may not use database D` before anything is read or written; `SHOW DATABASES` lists only the account's
  databases. The role still applies. Admin and metrics accounts cannot have a list (400). The list
  replicates with the account like a role change, and a node refuses an account whose list is malformed. A
  limited account is not written to `users.json`, so it does not exist after a rollback to 2.0 or 2.1.
  Each statement type states which database it names in one place, so a statement added later does not
  compile until that is decided.
- A memory limit: once the rows and index entries held are estimated to take `memory_limit_percent`
  (default 60) of the heap, writes that add data get 503 instead of the node running out of memory and
  exiting. Reads, deletes and drops are still served, and a delete makes room at once. The estimate is kept
  in step with every change, so it needs no garbage collection to be current. Metrics
  `serengeti_data_bytes`, `serengeti_data_budget_bytes`, `serengeti_memory_full` and `serengeti_heap_bytes`.
  `tests/memory/run.sh` fills a node in a 512 MiB container through its API, with large rows and with small
  rows under two indexes, and checks that a full node takes a backup, restarts, takes a delete of every row
  and restarts again.
- Clients for Python (standard library only) and JavaScript (`fetch`), one file each, in `clients/`:
  queries, reads that move on to the next node, writes that are never sent twice, the change feed,
  accounts and TLS with a cluster's own CA. `clients/run.sh` tests both against the image; CI runs it.
- `serengeti shell`, and a `serengeti` command in the image: statements typed at a prompt, read from a
  script or given with `-c`, with results as a table, tab-separated values or JSON. A statement runs at
  `;`, or at the end of a line once it is complete. In a node's container, `docker exec -it NAME serengeti
  shell` reaches that node, trusting its own certificate, and signs in as admin with
  `SERENGETI_ADMIN_PASSWORD` when it is set. `serengeti backup` takes a backup the same way, to standard
  output or a 0600 file, and checks that it is complete: `kubectl exec serengeti-0 -- serengeti backup >
  backup.jsonl`.
- Scheduled backups: with `backup_dir` set, a node writes a backup there every `backup_interval_minutes`
  (60) and keeps its newest `backup_keep` (24), each a 0600 file
  `serengeti-backup-NODE-YYYYMMDDTHHMMSSZ-SEQ.jsonl` written to a temporary file, forced to disk and renamed;
  nodes can share a directory. The backup is made in the data directory and then copied, so a slow backup
  volume never holds up writes. The next one is due an interval after the newest, also across restarts. A
  directory inside the data directory (also through a link), or one that cannot be written, stops the node
  at start. `/metrics` reports the time of the newest and the failures; the image has an empty `/backups`
  for a volume.
- `tests/edge/run.sh`, a conformance and limits suite for the HTTP API and the query language: about 1,140
  checks plus 5,000 generated statements per run, concurrency, kill -9 and backup cases. It found the bugs
  listed under Fixed; CI runs it.
- `tests/soak/run.sh`, a randomized fault test for 3- to 7-node TLS clusters: clients write and read through
  every node while a seeded schedule kills, stops, pauses, partitions and slows nodes, then every node is
  checked against what the clients were told (nothing acknowledged lost, no deleted row back, nodes equal,
  indexes equal to scans, every running node answering). docs/SOAK.md has what it covers and the runs; CI
  runs it for 5 minutes. It found the two shutdown and stall bugs under Fixed.
- `c [NOT] IN (v1, v2, ...)`, 1 to 1,000 values: exactly `c = v1 OR c = v2 ...`, so a null in the list
  makes NOT IN never true. On an indexed column or `_id`, IN reads the rows of each value.
- `c [NOT] LIKE 'pattern'`, with `%` for any run of characters, `_` for one UTF-16 code unit, and `\%`,
  `\_` and `\\`; case-sensitive. A value that is not a string makes LIKE false. On an indexed column or
  `_id`, a pattern that starts with a literal prefix reads only the strings with that prefix.
- Aggregates and GROUP BY: `COUNT(*)`, `COUNT(c)`, `SUM`, `MIN`, `MAX` and `AVG`, `GROUP BY` one or more
  columns, and ORDER BY a GROUP BY column or an aggregate of the select list. SUM and AVG skip values that
  are not numbers; a SUM of integers is exact and fails with 400 if it does not fit in 64 bits. MIN and MAX
  use the ORDER BY order. At most 10,000 groups are returned and 100,000 held while a statement runs.
  `sum`, `min`, `max`, `avg`, `group` and `like` still work as names. See docs/QUERY.md, "Aggregates and
  GROUP BY".
- `SELECT DISTINCT a, b`: the rows of `SELECT a, b ... GROUP BY a, b`, with the same equality, order and
  caps (`SELECT DISTINCT would hold more than 100,000 rows`). It takes columns only, and ORDER BY names
  columns of its list.
- `COUNT(DISTINCT c)`: the distinct non-null values of c, equal as GROUP BY makes them, in a column
  `count(distinct c)`. One statement holds at most 100,000 such values over all its groups, else 400
  `COUNT(DISTINCT) would hold more than 100,000 values`.
- HAVING, after GROUP BY or with a select list of aggregates only: WHERE's form, with a GROUP BY column or
  an aggregate on each left side. The aggregate need not be in the select list. HAVING drops groups before
  ORDER BY and LIMIT; the 100,000-group cap counts groups before it.
- ORDER BY on up to 32 keys, each ASC or DESC. Rows equal on every key keep `_id` order, and groups their
  GROUP BY order. With `_id` ascending as the first key, a SELECT still reads in `_id` order and stops
  after its page.
- `distinct` and `having` still work as names: `SELECT distinct FROM t` reads a column called distinct,
  and `SELECT DISTINCT distinct FROM t` its distinct values. See docs/QUERY.md, "SELECT DISTINCT" and
  "HAVING".
- docs/QUERY.md, "Reading a large table": keyset paging with `WHERE _id > 'last' ORDER BY _id LIMIT n`.
- `UPDATE ... SET c = column + number` and `- number`, such as `SET stock = stock - 1 WHERE _id = 'a' AND
  stock > 0`. The value is computed from the row under the node's write lock, so concurrent increments on
  one node lose nothing. Two integers give an exact integer, and a result outside 64 bits fails the
  statement; with a double on either side the result is the double nearest the exact value; a missing or
  null column gives null; a string, boolean, object or array fails the statement with 400, naming the row
  and column, and nothing is written. The result is written as a normal row write, so increments made on
  two nodes before either received the other's are a conflict (SHOW CONFLICTS), which the next write
  resolves. Only a column plus or minus a number: no other operators, parentheses or functions. 2.1 nodes
  answer this form with a parse error. See docs/QUERY.md, "UPDATE from the row".
- `INSERT ... ON CONFLICT DO NOTHING` and `INSERT ... ON CONFLICT DO UPDATE SET ...`, such as `INSERT INTO
  t (_id, n) VALUES ('a', 1) ON CONFLICT DO UPDATE SET n = n + 1`. Under the write lock, each tuple whose
  `_id` is not a row of the table is inserted; one whose `_id` is a row (also one in conflict; a deleted row
  is none) is skipped, or has UPDATE's assignments applied to that row, reading it as UPDATE does, and the
  tuple's other values are not written. The column list must have `_id`, one `_id` twice is refused, and a
  row that cannot be computed fails the statement with nothing written. The answer adds `inserted`,
  `updated` and `skipped` to `affected` and `ids` (docs/API.md); the shell prints them. ON, CONFLICT, DO and
  NOTHING are not reserved. DO NOTHING makes a retried insert safe; DO UPDATE with arithmetic does not. 2.1
  nodes answer ON CONFLICT with a parse error. See docs/QUERY.md, "INSERT ... ON CONFLICT".
- Indexes on 2 to 4 columns: `CREATE INDEX ON db.t (a, b)`, `DROP INDEX ON db.t (a, b)`, and `a, b` in
  `SHOW INDEXES`. The index keeps the rows in the ORDER BY order of a, then b. A WHERE whose top-level AND
  has `=`, IN or a LIKE without wildcards on its first columns, and then any of `<`, `<=`, `>`, `>=` and a
  LIKE prefix on the next, reads only the rows those terms allow (IN lists as every combination of their
  values, up to 1,000), and so does a range on the first column alone; SELECT, COUNT(*), GROUP BY, UPDATE
  and DELETE use it. Of the indexes that can
  answer (and of single-column indexes and `_id`), the read of the fewest rows is used, then the one that
  names the most columns; a range too large to gather gives way to the next. Its entries count towards
  the memory limit. The definition replicates and is kept in
  `indexes.jsonl` with the columns joined by commas in the `column` field: a 2.1 node holds it as an index
  on a column no row has (empty and unused) and keeps it, so it is back after a rollback to 2.1 and an
  upgrade. Single-column indexes are unchanged. See docs/QUERY.md, "Indexes on several columns".
- SELECT with ORDER BY and a LIMIT (or the 10,000 cap) reads an index in order and stops once it has
  its rows, when the ORDER BY keys are the columns of an index after those the WHERE fixes with `=` or IN,
  all ASC or all DESC: `ORDER BY at DESC LIMIT 10` with an index on `at`, `WHERE customer = 'c1' ORDER BY
  placed DESC LIMIT 10` with one on `(customer, placed)`. A range on the first ordered column narrows the
  read. On 200,000 rows in-process (c7g.4xlarge container limited to 4 CPUs, JDK 17), `ORDER BY at DESC
  LIMIT 10` takes 0.07 ms instead of 293. The answer is the sort's, row for row. When the WHERE has terms
  the read does not answer, or its keys stop before the index's last column, it reads a share of the rows
  the query would read without it (1/96 of a scan's, 1/7 to 1/16 of an index read's), then reads and sorts as
  before: a condition that no row meets costs about a sixth more (32 ms instead of 28 on 200,000 rows of 10
  columns, c7g.4xlarge). To place rows
  in that order, an index now also keeps the rows where its column is missing, null, an object or an array
  (found by no lookup, as before): 40 bytes each on one column, counted towards the memory limit. That
  applies to existing indexes from the first start on 2.2, so a node close to `memory_limit_percent` with
  indexes on sparse columns can refuse writes after the upgrade. See docs/QUERY.md, "ORDER BY through an
  index".
- An OR whose every branch an index or `_id` answers is read as the union of the branches' reads, in `_id`
  order and each row once, instead of scanning the table: `WHERE status = 'open' OR assignee = 'me'`, `WHERE
  kind = 1 AND (a = 1 OR b = 2)`, `WHERE (customer = 'c1' AND placed > '2026-06') OR customer = 'c2'`. Each
  branch (a term, or an AND of terms) takes the read a WHERE of its own would; an OR in an AND is one more
  read to choose from, counted as the sum of its branches' rows. It is used when those reads find at most
  1/16 of the table's rows, are at most 32 and look up at most 4,000 values; otherwise the OR scans as
  before. LIMIT and keyset pages stop after their rows. On 200,000 rows in-process (c7g.2xlarge container
  limited to 4 CPUs, JDK 17), an OR of two columns that each match 0.1% of the rows takes 0.84 ms instead of
  28. A page whose read gathers its ids first (a union, or a range on a column, as in 2.1) now first reads in
  `_id` order for a share of that size, then goes on through the gathered read after the last `_id` it got:
  `WHERE a < 31 OR b < 31 LIMIT 10` takes 0.03 ms instead of 3.9, and `WHERE a < 62 LIMIT 10` 0.015 instead
  of 4.6 (200,000 rows, in-process, c7g.4xlarge). See docs/QUERY.md, "OR through indexes".
- `LIMIT n OFFSET m`, and `OFFSET m` alone: the rows a SELECT gives without them, in its order, less the
  first m, then at most n. Every form takes them: rows in `_id` order, sorted or read through an index in
  order, SELECT DISTINCT, GROUP BY with HAVING, and aggregates without GROUP BY, which now take LIMIT too
  and whose one row `OFFSET 1` or `LIMIT 0` leaves out. Without LIMIT the 10,000-row cap applies after the
  OFFSET, and `truncated` says whether more rows follow the page. OFFSET plus LIMIT (or the cap) may be at
  most 100,000; past that the statement is refused (400) with a message that points to keyset pages, which
  stay the way to read a large table: an OFFSET page reads every row before it. OFFSET is not reserved.
  See docs/QUERY.md, "LIMIT and OFFSET".
- `serengeti import DB.TABLE [FILE]` reads NDJSON, or CSV with a header (`--csv`, RFC 4180), and inserts the
  rows over `POST /v1/query` in batched INSERTs of up to 1,000 rows, each request within the 1 MiB body
  limit. Keys fold to lowercase column names; each row needs a string `_id`, unless `--generate-ids`. In
  CSV, a quoted field is a string, an empty unquoted one leaves the column out, and with `--csv-types` an
  unquoted `true`, `false` or JSON number is that value. `--on-conflict skip|update|fail` (default fail)
  uses ON CONFLICT DO NOTHING, DO UPDATE SET of each column the line has, or a plain INSERT. Integers
  beyond 64 bits, numbers beyond a double, NaN and unpaired surrogates are refused with their line. A
  refused line is reported with its number once every line before it is written, so a rerun with
  `--on-conflict skip` finishes the job; a 503, 429 or failed connection is retried up to 8 times, 0.5 s
  to 8 s apart. It prints the rows read, inserted, updated and skipped; exit status 0, 1 (a line refused),
  2 (arguments, connection, account) or 3 (the node stayed unavailable). `--create` makes the table.
- `serengeti export DB.TABLE` writes every row in `_id` order as NDJSON (null values left out), or with
  `--csv` as CSV with a header of every column and strings quoted, to standard output or `-o FILE` (mode
  0600, renamed once complete). It reads keyset pages (`WHERE _id > ... ORDER BY _id LIMIT n`), sized down
  for large rows, so it works for a table of any size and uses no OFFSET; it is not a snapshot. `--where`
  takes a condition, parsed before it is sent. Exported NDJSON imports back as the same rows and types,
  `-0.0`, integers past 2^53, Unicode and nested values included; CSV with `--csv-types` keeps strings,
  numbers and booleans. 100,000 rows of five columns import in 2.1 s and export in 0.55 s (c7g.4xlarge,
  node in the same JVM). Both commands connect as `serengeti shell` does. See docs/IMPORT.md.
- Object and array values in INSERT tuples and on the right of SET, written as in JSON with the query
  language's values: `VALUES ('a', ['x', 2], {'k': {'n': -0.0}})`, `SET size = {'d': 6}`. Keys are strings,
  each once; values nest at most 64 deep. Conditions still compare with literals only. The engine already
  sorted, grouped and counted such values (docs/QUERY.md), but no statement could write them, so `serengeti
  import` could not load nested JSON. 2.1 nodes answer them with a parse error. See docs/QUERY.md, "Values".

### Fixed
- An index key kept the value of the row that first had it alive after that row changed or was deleted
  while another row kept an equal value (2.1 too), and the memory limit did not count it: up to the size of
  the indexed values. The key now takes a remaining row's value.
- A newer delete did not reach a node that held an older delete of the same row (2.1 too): sync compared
  sums that count only live rows, and two tombstones count nothing there. A write made on a node that was
  cut off, concurrent with both deletes and older than the newer one, could then show on that node as the
  row's value, also to a majority read. 2.2 nodes now also keep sums that count tombstones and compare
  those with each other (`GET /internal/v3/buckets`, and a `full` root per table in `/internal/v2/roots`,
  which 2.1 ignores); with 2.1 peers they compare as before. Tombstones now expire on 10-minute boundaries
  of the wall clock with the same cutoff on every node, and sync leaves expired ones out, so nodes that
  have dropped them and nodes that may not yet compare equal. Two nodes compare tombstones only while
  their cutoffs agree, so the seconds around a boundary, or a different `tombstone_ttl_hours` on two
  nodes (logged), do not make them pull expired tombstones every round. A node away longer than
  `tombstone_ttl_hours` less 20 minutes is now stale (was: the TTL), which leaves a node that comes back
  in time at least 20 minutes to pull the deletes it missed. Found by the soak (5 nodes, seed 31337,
  check 8) and review.
- Nothing stopped a second node from opening a data directory in use, for example a second container on the
  same volume, and both would write the same log. A node now holds a lock on `data_dir/lock` while it runs;
  a second one stops with exit status 2 and says the directory is in use. `serengeti restore` takes the
  lock too. On a file system without locks, the node logs a warning and starts.
- A string holding an unpaired UTF-16 surrogate (`'\ud800'`) was accepted and stored as `?`, so two such
  `_id`s became one row after a restart. JSON with an unpaired surrogate is now refused with 400.
- A request body that is not valid UTF-8 was stored with U+FFFD in place of the bad bytes; it is now 400.
  `\u` escapes take ASCII hex digits only (full-width digits were accepted).
- A request body that could not be read (a malformed chunked body, a chunked trailer) gave 500; it is 400.
- Any method on a path that is not a route is 404 (was 405), and a 405 names the allowed methods in
  `Allow`.
- `DROP INDEX IF EXISTS` on a table or database that does not exist answers 0 affected, as `DROP TABLE IF
  EXISTS` does (was 400).
- The node served 16 requests at a time, so 16 clients sending slowly held every thread; it now serves 64,
  and threads stop when idle (`http_threads` sets the number). A client that stalls is still dropped after
  30 s. README, "When not to use it", says to put a buffering proxy in front of a node that untrusted
  networks reach.
- Starting, checkpointing and taking a backup each held a second copy of all the data in memory (the
  whole snapshot as a list, the log's batches, every row as an operation), so a node near its memory limit
  could run out of memory while doing them. They now read and write one operation at a time, a backup is
  written to a file in the data directory and then sent with its length, and a log record is encoded as
  it is written. Node ids read from disk or from peers are shared instead of copied into every version.
- A statement that changed a few hundred thousand rows built its log record in memory several times over
  (a JSON tree, a string, bytes and a buffer): deleting 286,000 rows from a full 512 MiB node ran it out of
  memory.
- Starting replayed each log record whole (its bytes, a string, a JSON tree and its ops at once), so a
  node that deleted its rows when full did not start again: the delete was one record of tens of MB. A
  record over 1 MiB is now checked against its checksum in pieces and its ops parsed one at a time.
- A SELECT listing tens of thousands of columns (a 1 MiB body holds 60,000) ran the node out of memory:
  each row carried a slot for each. A statement now lists at most 256 items, GROUP BY at most 32 columns,
  and a LIKE pattern is at most 1,000 characters; more is a 400.
- A node that was stopping (SIGTERM, `docker stop`) answered 500 for about a second, also to writes it had
  already logged: it closed its cluster layer before its HTTP server, which goes on serving kept-alive
  connections while requests in flight finish. It now stops the HTTP server first, answers what still
  arrives with 503 "this node is stopping" and closes the connection, and only then closes the cluster
  and the storage. Found by `tests/soak`, in every run with graceful stops.
- A running node sometimes answered nothing for 20 to 83 s (2.0.0 has this too). It set the JDK server's
  60 s limit on reading a response; the server closes an overdue connection while holding the set that
  every request passes through, and closing a TLS connection waits for a write blocked on a peer that
  stopped reading (one cut off by a partition), so every request thread waited. There is no response limit
  now: a client that stops reading holds only its own thread. The 30 s limit on sending a request stays.
  Found by `tests/soak` (thread dumps of the stalls).
- A graceful stop sometimes waited out docker's 10 s grace and was killed: the JDK server's stop closes
  every connection, and closing a TLS connection waits for a write blocked on a peer that stopped reading.
  The node now waits for it 3 s at most, then closes its cluster layer and storage and exits.
- A node that had missed writes could run itself and its peers out of heap while catching up (2.1 too):
  it asked every peer at once for the rows of every differing bucket, in pages of 200 buckets, and a few
  thousand missed inserts touch most buckets. A node now runs at most two background row pulls at once,
  and four more for majority reads, sizes each page from the last (about 2,000 rows or 4 MiB), and builds
  at most four pages for its peers at once, answering 503 to more. A majority read's pulls, whole
  databases from 2.0 peers included, stop once the read has answered or given up. Found by the 5-node soak
  hour; `tests/memory/catchup.sh` reproduces it.
- An admin password of more than 72 bytes in `SERENGETI_ADMIN_PASSWORD` or its file passed the settings
  check, and the node then exited with status 1 and a stack trace; it is now refused with exit status 2 and
  a one-line message, like a short one.
- `GET /v1/backup` on a node whose data directory had no room for the backup closed the connection without
  an answer; it now answers 503 with a message, and logs the cause.
- The startup warning of a node without an admin said every request but `/health` was refused; `/ready`
  and `/v1/setup` answer too, and it now says so. A node with `advertise` set, which nothing has used,
  logs that it is ignored.

### Changed
- A node catching up pulls only the rows that differ, not every row of each bucket that differs. For the
  differing buckets it asks a peer for each row's id and a hash of its versions
  (`GET /internal/v3/hashes`, about 40 bytes a row), and pulls the rows whose hashes differ or that only
  the peer has (`POST /internal/v3/rows`, at most 2,000 ids, about 4 MiB by the sizes the peer sends with
  the hashes). Buckets a node holds nothing of are pulled whole in pages sized by the peer's rows and bytes
  per bucket, and a bucket bigger than a page by id; before, the first page of 16 buckets was sized blind
  (16 x 10,000 rows for a node without a 10-million-row table). A few thousand missed inserts touch most
  buckets, so a node used to pull most of each table from each peer, and each peer most of it from the
  node: in `tests/memory/catchup.sh` (126,000 rows of 1 KB, 6,000 missed) the restarted node received
  275 MB and sent 244 to 313 MB, and now receives 21 MB and sends 1 to 7 MB (3 runs each, EC2
  c7g.4xlarge). Peers on 2.1 answer 404 to these routes and are pulled from by whole buckets as before.
- `cluster_plaintext` takes only `allow`. Another value (`yes`, `true`) meant "not allowed" without a word;
  it now stops the node with exit status 2, like any other invalid setting.
- `SELECT COUNT(*)` without WHERE answers from a count of the table's rows kept with every change, instead
  of visiting them: on 100,000 rows, 15,100 requests/s at 8 clients against 973 (docs/BENCHMARKS.md).
- A SELECT without ORDER BY, or with `ORDER BY _id` ascending, stops reading once it has its rows (one past
  the LIMIT or the cap) instead of visiting the rest of the table, and `ORDER BY _id` ascending no longer
  sorts, since rows are read in that order. A keyset page reads only its own rows.
- A SELECT whose ORDER BY sorts keeps only the first OFFSET + LIMIT matching rows as it reads
  (OFFSET + 10,001 without LIMIT): it holds up to four times that many, sorts them and drops the rest
  each time it gets there, instead of holding and sorting every matching row. It still reads every
  matching row.
- The image sets `MALLOC_ARENA_MAX=2`. glibc otherwise keeps a malloc arena per thread: filling a node in a
  512 MiB container, that memory reached about 150 MB the JVM does not count (measured with Native Memory
  Tracking against the container's anonymous memory), and the kernel killed the node before its memory
  limit refused a write.
- The image uses G1 (2.1's image left the choice to the JVM, which takes SerialGC in a container under
  2 GiB) and gives the heap 60% of the container's memory (was 75%), leaving room for the log's
  page cache: at 75% a container filling with data was killed by the kernel before the heap was full.
- Each request's log line costs less inside the console handler's lock, which every line passes through
  and which bounded the rate of the cheapest requests: the line names its source, so the record no longer
  walks the stack for it, and the node formats log lines itself instead of parsing SimpleFormatter's
  pattern for each record. The text is the same. Lookups by `_id` at 8 clients went from 14,633 to 20,767
  a second on one node (2.1: 16,643; EC2 c7g.2xlarge, docs/BENCHMARKS.md).

## [Unreleased] - 2.1.0

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
