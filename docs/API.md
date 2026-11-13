# HTTP API

All bodies are JSON. Requests with a body must send `Content-Type: application/json`, and bodies over
1 MiB are refused. Every route except `GET /health` needs HTTP Basic credentials; the only account in this
version is `admin`.

| Route | Auth | Purpose |
|---|---|---|
| `GET /health` | none | `{"status":"ok"}` while the process serves HTTP. |
| `GET /ready` | none | `{"status":"ready"}`, or 503 `{"status":"not ready"}` while a readiness check fails. |
| `POST /v1/setup` | setup token | Sets the first admin password. |
| `POST /v1/query` | Basic | Runs one statement from [QUERY.md](QUERY.md). |
| `POST /post` | Basic | The 1.x route name, kept for 2.0 only. Same body and result as `/v1/query`. |
| `GET /v1/status` | Basic | Node id, version, uptime in seconds, number of databases, fsync policy. |
| `POST /v1/password` | Basic | Changes the caller's password. |
| `GET /v1/backup` | Basic, admin | Streams a snapshot of the data. See [Backup](#backup). |
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
`"local"`, answers from the node's own data. Writes return 503 when fewer than a majority of nodes are
reachable.

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

## Errors

Errors have a status code and a body of the form `{"error": "message"}`. The message never
contains a stack trace; unexpected failures say `internal error` and are logged by the node. The
clients in [examples/](../examples/) print the message and exit non-zero.

| Status | When |
|---|---|
| 400 | The body is not the expected JSON, or the query is invalid. Query errors give the position: `expected FROM at position 9`. |
| 401 | Missing or wrong credentials. The response has `WWW-Authenticate: Basic realm="serengeti"`. |
| 403 | Wrong setup token, a POST whose `Origin` header names another site, or a backup asked for by an account that is not admin. |
| 404 | No such route. |
| 405 | Wrong method for the route. |
| 409 | Setup when an admin password exists. |
| 413 | Body over 1 MiB. |
| 415 | `Content-Type` is not `application/json`. |
| 429 | Ten failed logins or setup attempts from this address in the last minute. `Retry-After` gives the seconds to wait. |
| 500 | Storage failed or another unexpected error. |
| 503 | Not set up yet. |

## Browser use

The console page loads its script from the node and is served with a Content-Security-Policy that allows
no inline script. Cross-site pages cannot send queries with a browser's saved credentials: a form cannot
send `application/json`, and POSTs with a foreign `Origin` are refused.
