# HTTP API

All bodies are JSON. Requests with a body must send `Content-Type: application/json`, and bodies over
1 MiB are refused. Every route except `GET /health` needs HTTP Basic credentials; the only account in this
version is `admin`.

| Route | Auth | Purpose |
|---|---|---|
| `GET /health` | none | `{"status":"ok"}` while the process serves HTTP. |
| `POST /v1/setup` | setup token | Sets the first admin password. |
| `POST /v1/query` | Basic | Runs one statement from [QUERY.md](QUERY.md). |
| `POST /post` | Basic | The 1.x route name, kept for 2.0 only. Same body and result as `/v1/query`. |
| `GET /v1/status` | Basic | Node id, version, uptime in seconds, number of databases, fsync policy. |
| `POST /v1/password` | Basic | Changes the caller's password. |
| `GET /` | Basic | The console. |

Until the first admin password is set, every route except `/health` and `/v1/setup` answers 503.

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
| 403 | Wrong setup token, or a POST whose `Origin` header names another site. |
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
