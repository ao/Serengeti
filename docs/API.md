# HTTP API

All bodies are JSON. Requests with a body must send `Content-Type: application/json`, and bodies over
1 MiB are refused. Every route except `GET /health`, `GET /ready` and `POST /v1/setup` needs the HTTP Basic
credentials of an account; see [Accounts](#accounts) for the roles.

| Route | Auth | Purpose |
|---|---|---|
| `GET /health` | none | `{"status":"ok"}` while the process serves HTTP. |
| `GET /ready` | none | `{"status":"ready"}`, or 503 `{"status":"not ready"}` while a readiness check fails. |
| `POST /v1/setup` | setup token | Sets the first admin password. |
| `POST /v1/query` | Basic | Runs one statement from [QUERY.md](QUERY.md). The read role can run SELECT and SHOW only. |
| `POST /post` | Basic | The 1.x route name, kept for 2.0 only. Same body and result as `/v1/query`. |
| `GET /v1/status` | Basic | Node id, version, uptime in seconds, number of databases, fsync policy. |
| `POST /v1/password` | Basic | Changes the caller's password. |
| `GET /v1/changes` | Basic | The changes this node applied after a cursor, waiting for one. See [Changes](#changes). |
| `GET /v1/backup` | Basic, admin | Streams a snapshot of the data. See [Backup](#backup). |
| `GET /v1/users` | Basic, admin | The accounts and their roles. See [Accounts](#accounts). |
| `PUT /v1/users/NAME` | Basic, admin | Creates an account, or changes its password or role. |
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
| `serengeti_build_info{version}` | gauge | Always 1. |
| `serengeti_start_time_seconds` | gauge | Unix time at which the node started. |

`route` is one of the paths in the table at the top of this page, or `other` for any other path. No series is
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
number of changes and their SHA-256. The state is copied under the storage lock, so the backup is consistent:
every write acknowledged before the request is in it, and no write is partly in it. A response that stops early
has no trailer, and restore refuses it.

Accounts (`users.json`) and the node id are not part of a backup.

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

200 `{"user":"admin"}`. The old password stops working at once.

## Changes

`GET /v1/changes?db=D&table=T&after=CURSOR&wait=SECONDS&limit=N` returns the changes to database `D`
(and table `T`, if given) that this node applied after the cursor, in the order it applied them: rows
written here, rows merged from other nodes, and tables and databases dropped. With nothing new it waits up
to `wait` seconds (0 to 60, default 0) for a change; `limit` is 1 to 10,000 (default 1,000). Any role may
follow changes.

```
GET /v1/changes?db=shop&table=items&after=3f9a1c22e07b:41&wait=30
{"changes": [{"kind": "row", "table": "items", "_id": "bolt", "deleted": false, "row": {"qty": 90},
              "at": 1731400012000, "node": "0b6f3c2e-5d1a-4e8b-9a7c-2f4d6e8a1b3c"}],
 "cursor": "3f9a1c22e07b:44"}
```

Pass the returned `cursor` as `after` next time. `kind` is `row` (with `deleted` and the row's new value),
`drop_table` or `drop_db`. A row appears when its value changes; a conflicting version that does not
become the value is not a change (see CONSISTENCY.md).

To follow a table from the start: take a cursor with `after=now`, read the table with SELECT, then follow
from the cursor. A change can then arrive for a row the SELECT already showed; apply changes by `_id`.

A node keeps its last `changes_buffer` changes (10,000 by default) in memory, from when it started. A
cursor from before a restart, from another node, or older than what the node still holds gets 410: read
the table again and follow from `after=now`. Cursors belong to the node that gave them. At most 32 requests
wait at once; more answer at once, with whatever there is.

## Accounts

An account has one of three roles:

| Role | May |
|---|---|
| `read` | SELECT and SHOW, `/v1/status`, `/metrics`, the console, and change its own password. |
| `write` | Also INSERT, UPDATE, DELETE, and CREATE and DROP of databases, tables and indexes. |
| `admin` | Also manage accounts (`/v1/users`) and take backups. |

The first admin comes from `SERENGETI_ADMIN_PASSWORD` or the setup token. An admin then makes the others:

```
PUT /v1/users/dashboard
{"password": "a long password for it", "role": "read"}
```

201 `{"user":"dashboard","role":"read"}` for a new account, 200 for a change. Give `role` alone to change the
role, `password` alone to change the password (the role is kept; a new account without a role gets
`write`). `GET /v1/users` returns `{"users": [{"user": "admin", "role": "admin"}, ...]}`; no response ever
contains a password hash. `DELETE /v1/users/NAME` deletes the account. A name is 1 to 64 letters, digits,
`.`, `_` or `-`; a password is 12 to 72 bytes. The last admin cannot be deleted or given another role (409).

In a cluster, account changes made on one node reach the others within seconds: each change carries a
version, the newest change to a name wins on every node, and a node that was down catches up when it
returns. Accounts travel between nodes as bcrypt hashes, over the cluster's TLS and behind its cluster key.
Nodes older than 2.2 do not take part: in a cluster being upgraded, an account made on a 2.2 node works on
the 2.2 nodes only.

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
| 400 | The body is not the expected JSON, or the query is invalid. Query errors give the position: `expected FROM at position 9`. |
| 401 | Missing or wrong credentials. The response has `WWW-Authenticate: Basic realm="serengeti"`. |
| 403 | Wrong setup token, a POST whose `Origin` header names another site, or a statement or route the account's role does not allow (a write by a read account, accounts or backups by an account that is not admin). |
| 404 | No such route, whatever the method. |
| 405 | Wrong method for the route. `Allow` names the methods it takes. |
| 409 | Setup when an admin password exists, or deleting or demoting the last admin. |
| 413 | Body over 1 MiB. |
| 415 | `Content-Type` is not `application/json`. |
| 429 | Ten failed logins or setup attempts from this address in the last minute. `Retry-After` gives the seconds to wait. |
| 500 | Storage failed or another unexpected error. |
| 503 | Not set up yet. |

## Browser use

The console page loads its script from the node and is served with a Content-Security-Policy that allows
no inline script. Cross-site pages cannot send queries with a browser's saved credentials: a form cannot
send `application/json`, and POSTs with a foreign `Origin` are refused.
