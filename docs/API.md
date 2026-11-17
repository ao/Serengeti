# HTTP API

All bodies are JSON. Requests with a body must send `Content-Type: application/json`, and bodies over
1 MiB are refused. Every route except `GET /health`, `GET /ready` and `POST /v1/setup` needs the HTTP Basic
credentials of an account; see [Accounts](#accounts) for the roles.

| Route | Auth | Purpose |
|---|---|---|
| `GET /health` | none | `{"status":"ok"}` while the process serves HTTP. |
| `GET /ready` | none | `{"status":"ready"}`, or 503 `{"status":"not ready"}` while a readiness check fails. |
| `POST /v1/setup` | setup token | Sets the first admin password. |
| `POST /v1/query` | Basic | Runs one statement from [QUERY.md](QUERY.md). The read role can run SELECT and SHOW only, and an account limited to some databases can name only those. |
| `POST /post` | Basic | The 1.x route name, still served. Same body and result as `/v1/query`. |
| `GET /v1/status` | Basic | Node id, version, uptime in seconds, number of databases, fsync policy, and in a cluster a `cluster` object: size, quorum, peers reachable, whether the node is caught up or stale, and each peer. |
| `POST /v1/password` | Basic | Changes the caller's password. |
| `GET /v1/changes` | Basic | The changes this node applied after a cursor, waiting for one. See [Changes](#changes). |
| `GET /v1/backup` | Basic, admin | Streams a snapshot of the data. See [Backup](#backup). |
| `GET /v1/users` | Basic, admin | The accounts, their roles and their databases. See [Accounts](#accounts). |
| `PUT /v1/users/NAME` | Basic, admin | Creates an account, or changes its password, role or databases. |
| `DELETE /v1/users/NAME` | Basic, admin | Deletes an account. |
| `GET /metrics` | Basic | Prometheus text format. See [Metrics](#metrics). |
| `GET /` | Basic | The console. |

Until the first admin password is set, every route except `/health`, `/ready` and `/v1/setup` answers 503.

## Readiness

```
GET /ready
```

200 `{"status":"ready"}` while the node serves and every readiness check passes; 503
`{"status":"not ready"}` otherwise. It needs no credentials, and the body never says which check failed; the
node logs that. A node that has no admin password yet is ready. Its other routes answer 503 until setup, as
before.

## Metrics

```
GET /metrics
```

Needs Basic credentials of any account. The body is Prometheus text format 0.0.4, sent as
`text/plain; version=0.0.4; charset=utf-8`. Each series below appears once for each label set it has.

| Series | Type | Meaning |
|---|---|---|
| `serengeti_http_requests_total{route,code}` | counter | Requests answered, by route and status code. Code 0 means no response was sent. |
| `serengeti_http_request_duration_seconds{route}` | histogram | Time from receiving a request to the end of its response. Buckets from 5 ms to 10 s. |
| `serengeti_login_failures_total` | counter | Requests with a wrong user name or password. |
| `serengeti_login_limited_total` | counter | Requests refused by the failed-login limit (429). |
| `serengeti_wal_bytes` | gauge | Size of `wal.log` in bytes. |
| `serengeti_wal_fsyncs_total` | counter | Forces of `wal.log` to disk. Zero with `fsync=off`. |
| `serengeti_wal_fsync_seconds_total` | counter | Time spent in those forces. |
| `serengeti_databases` | gauge | Databases that exist. |
| `serengeti_conflicts` | gauge | Rows holding more than one version (see `SHOW CONFLICTS`). |
| `serengeti_data_bytes` | gauge | Estimated heap bytes taken by the rows and index entries held. |
| `serengeti_data_budget_bytes` | gauge | The estimate at which writes that add data are refused (`memory_limit_percent`; 0 when off). |
| `serengeti_heap_bytes` | gauge | The JVM heap, by `kind`: `used` (garbage included), `committed` and `max`. |
| `serengeti_memory_full` | gauge | 1 while writes that add data are refused (`memory_limit_percent`). |
| `serengeti_changes_held` | gauge | Changes the feed holds for `GET /v1/changes` (`changes_buffer`, fewer when they are large). |
| `serengeti_changes_bytes` | gauge | Estimated heap bytes of the changes the feed holds, at most a sixteenth of the heap. |
| `serengeti_ready` | gauge | 1 while `GET /ready` answers 200. |
| `serengeti_disk_failed` | gauge | 1 after a write to disk failed; the node serves nothing from its data until it is restarted. |
| `serengeti_cluster_nodes` | gauge | Nodes in the cluster, this one included; 1 without peers. |
| `serengeti_cluster_reachable_nodes` | gauge | Nodes that answered this one in the last 3 seconds, this one included. |
| `serengeti_cluster_quorum` | gauge | Nodes that make a majority. Majority writes and reads get 503 while fewer are reachable. |
| `serengeti_cluster_caught_up` | gauge | 1 once this node has synced with a majority since it started; 1 without peers. |
| `serengeti_cluster_stale` | gauge | 1 if the node was away longer than `tombstone_ttl_hours` less 20 minutes: it neither syncs nor takes writes (see [CONSISTENCY.md](CONSISTENCY.md)). |
| `serengeti_sync_ops_pulled_total` | counter | Row versions this node pulled from its peers while syncing. |
| `serengeti_tls_expiry_time_seconds{store}` | gauge | Unix time at which the first certificate of `store="keystore"` (this node's chain) or `store="truststore"` (the CAs for peers) expires, as read at start. Absent for a store that is not set. |
| `serengeti_backup_interval_seconds` | gauge | `backup_interval_minutes` in seconds; 0 without `backup_dir` (see CONFIGURATION.md, "Scheduled backups"). |
| `serengeti_backup_last_success_time_seconds` | gauge | Unix time of the newest scheduled backup in `backup_dir`, also one written before this start; 0 for none. |
| `serengeti_backup_failures_total` | counter | Scheduled backups that failed since the node started. |
| `serengeti_backup_last_bytes` | gauge | Size of the last scheduled backup written since the node started. |
| `serengeti_build_info{version}` | gauge | Always 1. |
| `serengeti_start_time_seconds` | gauge | Unix time at which the node started. |

[examples/monitoring/](../examples/monitoring/) has Prometheus alert rules and a Grafana dashboard for
these series.

`route` is one of the paths in the table at the top of this page (`/v1/users/{name}` for every account's
path), or `other` for any other path. No series is
labelled with a user name, an address or query text. Rows are not counted: that would scan every table on each
scrape.

## Request log

Each request writes one line at INFO, to stderr:

```
2026-11-13T10:02:11.402+0000 INFO com.ataiva.serengeti.core.server.HttpApi: request method=POST route=/v1/query status=200 duration_ms=3.118 remote=127.0.0.1 user=admin
```

`user` is the account that authenticated, or `-`. A user name that matched no account is not logged. The line
never holds the body, the query string, the Authorization header or a password. `route` is the label from the
metrics list, so a path a client made up appears as `other`.

## Backup

```
GET /v1/backup
```

Needs an admin account: 401 without credentials, 403 for any other account. The response is
`application/octet-stream` with `Content-Disposition: attachment; filename="serengeti-backup-<UTC time>.jsonl"`.
Restore it with `serengeti restore`, described in [CONFIGURATION.md](CONFIGURATION.md#restore).

The body is the snapshot format: one JSON header line, one line per change, and a trailer that holds the
number of changes and their SHA-256. The state is written to a file in the data directory under the storage
lock (writes wait meanwhile, as for a checkpoint), then sent with its length, and deleted once sent or when the
client goes away: the node needs free space in its data directory as large as the backup. The backup is consistent:
every write acknowledged before the request is in it, and no write is partly in it. A response that stops early
has no trailer, and restore refuses it.

Accounts (`accounts.json`, `users.json`) and the node id are not part of a backup.

`serengeti backup` does the same from the command line, and checks the trailer arrived: in a node's
container, `docker exec NAME serengeti backup > backup.jsonl` (or `-o FILE`, written with mode 0600 and
renamed once complete), and on Kubernetes `kubectl exec serengeti-0 -- serengeti backup > backup.jsonl`.

## Setup

```
POST /v1/setup
{"token": "<contents of data/setup-token>", "password": "at least 12 characters"}
```

200 `{"user":"admin"}`. 403 for a wrong token, 400 for a password that is too short or longer than 72
bytes, 409 once a password is set. The token file is deleted when the password is set.

## Query

```
POST /v1/query
{"query": "SELECT name FROM shop.items WHERE qty > 10 ORDER BY name LIMIT 5"}
```

In a cluster, add `"consistency": "majority"` to read only after syncing with a majority of nodes, so
the result includes every acknowledged write (see [CONSISTENCY.md](CONSISTENCY.md)). The default,
`"local"`, answers from the node's own data.

Writes return 503 when fewer than a majority of nodes are reachable. Add `"ack": "local"` to have the
write acknowledged once it is in this node's log instead; it reaches the other nodes when they are
reachable. `"ack": "majority"` asks for the majority on a node whose default (`write_ack`) is local. On a
single node, `ack` changes nothing.

```
POST /v1/query
{"query": "INSERT INTO shop.items (name) VALUES ('bolt')", "ack": "local"}
```

Reads return the column names and one array per row:

```json
{"type":"rows","columns":["name"],"rows":[["bolt"],["nut"]],"count":2,"truncated":false}
```

`truncated` is true when a `SELECT` without `LIMIT` matched more than 10,000 rows and only the first
10,000 were returned. Writes return the number of rows changed, and `INSERT` also the ids:

```json
{"type":"write","affected":2,"ids":["8f0c...","1d2e..."]}
```

## Password

```
POST /v1/password
{"password": "the new password"}
```

200 `{"user":"admin"}`. The old password stops working at once. The account keeps the role it has when the
change is made, and an account deleted meanwhile gets 401.

## Changes

`GET /v1/changes?db=D&table=T&after=CURSOR&wait=SECONDS&limit=N` returns the changes to database `D`
(and table `T`, if given) that this node applied after the cursor, in the order it applied them: rows
written here, rows merged from other nodes, and tables and databases dropped or created. With nothing new it waits up
to `wait` seconds (0 to 60, default 0) for a change; `limit` is 1 to 10,000 (default 1,000). Any role may
follow changes, except `metrics`. An account limited to some databases gets 403 for any other `db`; `db` is
matched exactly here, so it must be written as the database's name is (lowercase). A long poll checks the
account again when it returns, so a change to the account made meanwhile applies to its answer.

```
GET /v1/changes?db=shop&table=items&after=3f9a1c22e07b:41&wait=30
{"changes": [{"kind": "row", "table": "items", "_id": "bolt", "deleted": false, "row": {"qty": 90},
              "at": 1731400012000, "node": "0b6f3c2e-5d1a-4e8b-9a7c-2f4d6e8a1b3c"}],
 "cursor": "3f9a1c22e07b:44"}
```

Pass the returned `cursor` as `after` next time. `kind` is `row` (with `deleted` and the row's new value),
`drop_table`, `drop_db`, `create_table` or `create_db`; with `table`, the database's `create_db` and
`drop_db` are listed too. Index changes are not listed. A row appears each time a write to it becomes its
value, also an UPDATE that leaves the value as it was; a conflicting version that does not become the
value is not a change (see CONSISTENCY.md). After `create_table` or
`create_db`, read the table or database again: when one is created again after a drop, rows that another
node wrote after the drop can become visible, and they are not listed one by one. A change is listed once
this node has it on disk; with `fsync` `interval` that is after the once-a-second force that covers it, so
up to about a second after the write is acknowledged. A write that fails to reach the disk (a full disk, say) is answered 500, taken
back and never listed, and the node goes on. After an fsync of the log fails, the node no longer knows what
is on disk: the feed answers 503 from then on, until the node is restarted.

To follow a table from the start: take a cursor with `after=now`, read the table with SELECT, then follow
from the cursor. A change can then arrive for a row the SELECT already showed; apply changes by `_id`.

A node keeps its last `changes_buffer` changes (10,000 by default, fewer when they are large); with
`changes_buffer` 0 it keeps none and this route answers 404. They outlive a restart and a crash (`kill -9`,
a machine that loses power): at start the node replays its write-ahead log, which lists the log's changes
again with the same numbers, after the changes it kept in `changes.jsonl` at its last checkpoint (see
CONFIGURATION.md, "Data directory"). A cursor from before the restart goes on where it was, and gets the
same changes it would have got before, as long as they are still held. A write that a crash cut off was
not acknowledged and not listed, so it changes nothing for cursors. A record the stopped node had written but
not yet forced is forced at start, before its change is listed. After a failed fsync that is not enough on
Linux, which reports a write-back error once: the force at start can succeed for a page that never reached
the disk, so a node whose fsync failed (it logs that, and its feed answered 503) can list a change that a
later power cut takes back.

The part of the cursor before the colon is the feed's epoch. The node keeps it in its data directory and
starts a new one only when it can no longer give the same answers: after `serengeti restore`, on a data
directory that was emptied or has no `changes.jsonl` (also the first start on 2.2 after an older version),
after a log that lost a record it had whole (a damaged disk; a record whose length field was damaged
reads as a write cut off, and the records after it are dropped without a new epoch), after a crash in the
milliseconds of a checkpoint between writing the snapshot and emptying the log, and after any stop that was
not clean of a run with `fsync` `off`, whatever the next run has. The node logs which at start. A cursor from another epoch, from another node, or older than
what the node still holds gets 410: read the table again and follow from `after=now`. Cursors belong to the
node that gave them. At most 32 requests wait at once; more answer at once, with whatever there is.

## Accounts

An account has one of four roles:

| Role | May |
|---|---|
| `metrics` | `GET /metrics`, `GET /v1/status` and change its own password; nothing of the data. Every other route answers 403. |
| `read` | Also SELECT and SHOW, the change feed and the console. |
| `write` | Also INSERT, UPDATE, DELETE, and CREATE and DROP of databases, tables and indexes. |
| `admin` | Also manage accounts (`/v1/users`) and take backups. |

`metrics` is for a monitoring system, which then holds a password that cannot read the data.

The first admin comes from `SERENGETI_ADMIN_PASSWORD` or the setup token. An admin then makes the others:

```
PUT /v1/users/dashboard
{"password": "a long password for it", "role": "read"}
```

201 `{"user":"dashboard","role":"read"}` for a new account, 200 for a change. Give any of `password`, `role`
and `databases` (below); what a change leaves out stays as it is, and a new account without a role gets
`write`. `GET /v1/users` returns `{"users": [{"user": "admin", "role": "admin"}, ...]}`; no response ever
contains a password hash. `DELETE /v1/users/NAME` deletes the account (404 if there is none). A name is 1
to 64 ASCII letters, digits, `.`, `_` or `-`; a password is at least 12 characters and at most 72 bytes in
UTF-8. The last admin cannot be deleted or given another role (409).

A read or write account can be limited to some databases:

```
PUT /v1/users/shop-app
{"password": "a long password for it", "role": "write", "databases": ["shop", "shop_archive"]}
```

201 `{"user":"shop-app","role":"write","databases":["shop","shop_archive"]}`. Such an account can run only
statements that name one of its databases. Any other statement that names a database gets 403 `this
account may not use database D`, whether or not `D` exists, before anything is read or written; `SHOW
DATABASES` lists the account's databases that exist, and the change feed is limited the same way. The role
still applies: a limited read account only reads its databases, and creating one of them needs a write
account. The list has 1 to 100 database names, none twice; names are folded to lowercase, as in queries,
and the list comes back sorted. `GET /v1/users` and the answers to `PUT` show it. `"databases": null`
removes the limit. A change that leaves `databases` out keeps the list, and so does the account's own
password change. Admin accounts (accounts and backups reach every database) and metrics accounts (no data)
cannot have a list: that is a 400, and so is giving a limited account one of those roles, unless the same
change has `"databases": null`. A limited account still sees node-wide numbers: the number of databases
in `/v1/status`, the counters of `/metrics` (log size, fsyncs, data size, conflicts, requests), and, in
the change feed's cursor, a sequence that counts changes to every database, so it can tell how often other
databases are written. It sees no names or rows of other databases.

Read, metrics and limited accounts are not in the `users.json` that 2.0 and 2.1 read (they would let a
limited account reach every database), so after a rollback to either they do not exist. A write account
that is given a list leaves `users.json` with that change, and returns to it when the list is removed.

In a cluster, account changes made on one node reach the others within seconds, and a node that was down
catches up when it returns. A list of databases travels with the account; a node refuses an account whose
list it cannot read rather than take it without one. Accounts travel between nodes as bcrypt hashes, over
the cluster's TLS and behind its cluster key. A node that is out of date (see CONSISTENCY.md) still takes
and passes on account changes. Nodes older than 2.2 do not take part: in a cluster being upgraded, an
account made on a 2.2 node works on the 2.2 nodes only.

An account has three parts, each with its own version: the role, the list of databases, and the password.
A change moves the parts it names: `password` the password, `role` the role, `databases` the list. On every
node each part comes from the newest change to it. So two admins who change one account on two nodes at the
same moment, each a different part (a role and a list, a password and a list, a password and a role), both
keep their change. Two changes to the same part keep the newer one: two lists given at once end as the later
list.

An account's own password change (`POST /v1/password`) moves only the password. It never changes the role
or the list, so an account cannot undo its demotion or its list that way, and it loses to a password an
admin set on another node that it had not heard of yet: the admin's password stands.

Making an account and deleting one each start a new version of the whole account, and the newer of those
wins whole. A change made on a node that had not heard of a delete belongs to the account deleted, so it
does not bring it back, however late it is stamped: a reset, a role or a list given there is lost, and the
account signs in with nothing and is not listed. A `PUT` with a password to a name that has no account on
the node that takes it (none, or deleted) makes the account again: `write` and no list unless the change
gives them, and nothing of what was done on other nodes to the account deleted. The admin that the
recovery path below or `POST /v1/setup` makes is a new account in the same way.

An admin or metrics account reaches what its role reaches, whatever list it holds. A list given on one node
while another node makes the account admin stays with the account without effect; if a later change makes
it read or write and leaves `databases` out, that list applies again.

The last-admin rule holds on each node, but two admins deleted on two nodes at once can leave none. The
other accounts keep working. Restart one node with `SERENGETI_ADMIN_PASSWORD` set: once it has pulled the
accounts from a peer (or after 30 s without one), and if there is still no admin, it makes the admin again
with a current version, which replaces the deletions on every node. Or restart it without that setting:
it writes a setup token, and `POST /v1/setup` makes the admin.

## Errors

Errors have a status code and a body of the form `{"error": "message"}`. The message never
contains a stack trace; unexpected failures say `internal error` and are logged by the node. The
clients in [examples/](../examples/) print the message and exit non-zero.

One exception: a request the HTTP server cannot parse at all (a malformed request line, a header
without a colon, a Content-Length that is not a number) gets the server's own 400 with no JSON body,
and a request whose headers are too large has its connection closed. Such a request never reaches
Serengeti's code.

| Status | When |
|---|---|
| 400 | The body is not the expected JSON, the query is invalid, or an account change the rules in [Accounts](#accounts) refuse. Query errors give the position: `expected FROM at position 9`. |
| 401 | Missing or wrong credentials. The response has `WWW-Authenticate: Basic realm="serengeti", charset="UTF-8"`. |
| 403 | Wrong setup token, a request other than GET whose `Origin` header does not match its `Host` header, or a statement or route the account's role does not allow (a write by a read account, accounts or backups by an account that is not admin, anything but `/metrics`, `/v1/status` and `/v1/password` by a metrics account), or a statement or `GET /v1/changes` that names a database a limited account does not have (`this account may not use database D`). |
| 404 | No such route, whatever the method; no such account (`DELETE /v1/users/NAME`); the change feed on a node with `changes_buffer` 0. |
| 405 | Wrong method for the route. `Allow` names the methods it takes. |
| 409 | Setup when an admin password exists, or deleting or demoting the last admin. |
| 413 | Body over 1 MiB. |
| 415 | `Content-Type` is not `application/json`. |
| 429 | Ten failed logins or setup attempts from this address in the last minute. `Retry-After` gives the seconds to wait. |
| 500 | Storage failed (a write that could not reach the disk is taken back) or another unexpected error. |
| 503 | Not set up yet; a write or a `"consistency": "majority"` read that cannot reach a majority (see [CONSISTENCY.md](CONSISTENCY.md)); a write that adds data to a node at its memory limit (`memory_limit_percent`); the change feed after an fsync of the node's log failed; a node that is stopping (`this node is stopping`, with `Connection: close`). |

## Browser use

The console page loads its script from the node and is served with a Content-Security-Policy that allows
no inline script. Cross-site pages cannot send queries with a browser's saved credentials: a form cannot
send `application/json`, and requests other than GET with a foreign `Origin` are refused.
