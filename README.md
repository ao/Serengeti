# Serengeti

Serengeti is a small document-table database in one jar: databases hold tables, tables hold JSON rows,
and you query them over an authenticated HTTP API with a short SQL-like language. It writes every change
to a checksummed write-ahead log before answering.

This is the 2.0 line, a rewrite of 1.x. It runs as one node, or as a cluster of up to 7 that each hold
every database and acknowledge a write once a majority has it on disk ([docs/CONSISTENCY.md](docs/CONSISTENCY.md)).

## Quick start

Build the image and start one node. The node writes a one-time setup token to `/data/setup-token` on its
first start and refuses every request except `GET /health` until the admin password is set with it:

```sh
docker build -t serengeti .
docker run -d --name serengeti -p 127.0.0.1:1985:1985 -v serengeti-data:/data serengeti
```

Set the admin password and run queries. The commands keep the password in `SERENGETI_PASSWORD`; choose
your own.

```sh
# Wait until the node answers, then set the admin password with the one-time setup token.
for _ in $(seq 60); do curl -sf http://127.0.0.1:1985/health >/dev/null && break; sleep 1; done
export SERENGETI_PASSWORD='choose a long password'
token=$(docker exec serengeti cat /data/setup-token)
curl -s -H 'Content-Type: application/json' \
  --data "{\"token\": \"$token\", \"password\": \"$SERENGETI_PASSWORD\"}" http://127.0.0.1:1985/v1/setup; echo
# {"user":"admin"}
```

```sh
q() { curl -s -u "admin:$SERENGETI_PASSWORD" -H 'Content-Type: application/json' \
        --data "{\"query\": \"$1\"}" http://127.0.0.1:1985/v1/query; echo; }

q "CREATE DATABASE shop"
q "CREATE TABLE shop.items"
q "INSERT INTO shop.items (name, qty) VALUES ('bolt', 120), ('nut', 300)"
q "SELECT name, qty FROM shop.items WHERE qty > 200"
# {"type":"rows","columns":["name","qty"],"rows":[["nut",300]],"count":1,"truncated":false}
```

The console is at http://127.0.0.1:1985/ and asks for the same user name and password.

To set the password when the node first starts, pass `-e SERENGETI_ADMIN_PASSWORD` to `docker run`
(the value is taken from your shell), and skip the setup step. See docs/CONFIGURATION.md.

The [examples](examples/) run the same steps from curl, Python (`urllib`) and JavaScript (`fetch`). The
quick start is run by [docs/quickstart.sh](docs/quickstart.sh), and the examples by
[examples/run.sh](examples/run.sh); both are run in CI.

Without Docker, build with `mvn package` (Java 17 or later) and run
`java -jar target/Serengeti-2.0.0-SNAPSHOT-jar-with-dependencies.jar`. It listens on 127.0.0.1:1985 and
keeps its data in `./data`.

## Documentation

- [docs/QUERY.md](docs/QUERY.md): the query language, comparison rules and limits.
- [docs/API.md](docs/API.md): HTTP routes, authentication and errors.
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md): settings, defaults and the data directory.
- [examples/](examples/): clients for curl, Python and JavaScript.
- [CHANGELOG.md](CHANGELOG.md), [ROADMAP.md](ROADMAP.md), [VERSIONING.md](VERSIONING.md) and
  [CONTRIBUTING.md](CONTRIBUTING.md).

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
- replication across 1 to 7 nodes with majority writes, catch-up after restarts and partitions, and
  majority reads; `tests/cluster/run.sh` kills, partitions and slows nodes of a 3-node TLS cluster in
  Docker and checks that no acknowledged write is lost;
- one image for linux/amd64 and linux/arm64.

Not yet done: `/ready`, `/metrics`, backup and restore. There are no indexes; every `WHERE` scans the
table. There are no joins and no transactions across statements. Conflicting writes to one row are
resolved last-writer-wins, not serialised.

1.x (up to v1.3.4) is not compatible: its HTTP routes, on-disk files and `admin`/`admin` login are gone.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
