# Serengeti

Serengeti is a small document-table database in one jar: databases hold tables, tables hold JSON rows,
and you query them over an authenticated HTTP API with a short SQL-like language. It writes every change
to a checksummed write-ahead log before answering.

This is the 2.0 line, a rewrite of 1.x. The 2.0 branch runs as a single node. Replication across a fixed
set of peers is being built and is refused at start until it lands (see [Status](#status)).

## Quick start

```sh
docker build -t serengeti .
docker run -d --name serengeti -p 127.0.0.1:1985:1985 -v serengeti-data:/data \
  -e SERENGETI_ADMIN_PASSWORD='choose a long password' serengeti
```

```sh
q() { curl -s -u "admin:choose a long password" -H 'Content-Type: application/json' \
        --data "{\"query\": \"$1\"}" http://127.0.0.1:1985/v1/query; echo; }

q "CREATE DATABASE shop"
q "CREATE TABLE shop.items"
q "INSERT INTO shop.items (name, qty) VALUES ('bolt', 120), ('nut', 300)"
q "SELECT name, qty FROM shop.items WHERE qty > 200"
# {"type":"rows","columns":["name","qty"],"rows":[["nut",300]],"count":1,"truncated":false}
```

The console is at http://127.0.0.1:1985/ and asks for the same user name and password.

Without `SERENGETI_ADMIN_PASSWORD`, the first start writes a one-time token to `/data/setup-token` and
refuses every request except `GET /health` until the password is set with it:

```sh
token=$(docker exec serengeti cat /data/setup-token)
curl -s -H 'Content-Type: application/json' \
  --data "{\"token\": \"$token\", \"password\": \"choose a long password\"}" http://127.0.0.1:1985/v1/setup
```

Without Docker, build with `mvn package` (Java 17 or later) and run
`java -jar target/Serengeti-2.0.0-SNAPSHOT-jar-with-dependencies.jar`. It listens on 127.0.0.1:1985 and
keeps its data in `./data`.

## Documentation

- [docs/QUERY.md](docs/QUERY.md): the query language, comparison rules and limits.
- [docs/API.md](docs/API.md): HTTP routes, authentication and errors.
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md): settings, defaults and the data directory.

## Security defaults

- There is no built-in account. The admin password comes from the environment, a file, or the one-time
  setup token.
- Every route except `GET /health` needs HTTP Basic credentials. Ten failed attempts from one address in
  a minute block it for the rest of that minute.
- The jar listens on 127.0.0.1 unless `SERENGETI_BIND` says otherwise. The image binds all interfaces
  inside its container and publishes nothing; publish to 127.0.0.1 unless other machines need it.
- Without TLS, passwords cross the network in clear. Set `SERENGETI_TLS_KEYSTORE` (see
  docs/CONFIGURATION.md) before other machines connect, or reach the node through an SSH tunnel.

## Status

What works in this branch, with tests (`mvn verify`, and `tests/container/run.sh` against the image):

- single-node storage: write-ahead log with CRC32C, fsync before acknowledging by default, snapshots,
  recovery that drops a torn tail; five rounds of `kill -9` during inserts lose no acknowledged insert;
- the query language in docs/QUERY.md, with property tests on the parser;
- the authenticated API and console;
- HTTPS with a PKCS#12 key store, TLS 1.2 and 1.3 only;
- `GET /ready`, Prometheus metrics at `GET /metrics`, and one request log line per request;
- backup over HTTP (`GET /v1/backup`, admin) and `serengeti restore` into an empty data directory;
- one image for linux/amd64 and linux/arm64.

Not yet done: replication, its node-to-node TLS and its fault tests. There are no indexes; every `WHERE` scans
the table. There are no joins and no transactions across statements.

1.x (up to v1.3.4) is not compatible: its HTTP routes, on-disk files and `admin`/`admin` login are gone.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
