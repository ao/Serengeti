# Serengeti

Serengeti is a small replicated database in one jar. Databases hold tables, tables hold JSON rows, and
you query them over an authenticated HTTP API with a short SQL-like language. A cluster is 1 to 7 nodes
that each hold every database.

What sets it apart is what happens when the network splits. By default a write needs a majority of the
nodes, like most replicated databases. Ask for `"ack": "local"` instead and a node that is cut off keeps
accepting writes; when the network comes back the nodes merge, and every node ends up with the same rows.
Two writes to the same row that never saw each other are not silently dropped: the newer one becomes the
row's value everywhere, and `SHOW CONFLICTS` lists both until the next write to the row settles it.

That suits places where the link to the other nodes is the least reliable part: a shop, a depot, a
vehicle, a site office.

![A three-node cluster: one node is cut off, both sides keep writing, and they converge when the network heals](docs/demo.svg)

The recording is a real run of [docs/demo.sh](docs/demo.sh), which anyone with Docker can run. CI runs it
on every change.

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

The console is at http://127.0.0.1:1985/ and asks for the same user name and password. The image also
has a shell that prints results as a table:

```
$ docker exec -it -e SERENGETI_PASSWORD serengeti serengeti shell
serengeti> SELECT name, qty FROM shop.items ORDER BY qty
name | qty
-----+----
bolt | 120
nut  | 300
(2 rows, 8 ms)
```

`-c 'STATEMENT'` runs statements and exits, and `--format tsv` or `--format json` suit scripts;
`serengeti shell --help` lists the rest.

To set the password when the node first starts, pass `-e SERENGETI_ADMIN_PASSWORD` to `docker run`
(the value is taken from your shell), and skip the setup step.

The [examples](examples/) run the same steps from curl, Python (`urllib`) and JavaScript (`fetch`). The
quick start is run by [docs/quickstart.sh](docs/quickstart.sh), and the examples by
[examples/run.sh](examples/run.sh); both are run in CI.

Without Docker, build with `mvn package` (Java 17 or later) and run
`java -jar target/Serengeti-2.1.0-SNAPSHOT-jar-with-dependencies.jar`. It listens on 127.0.0.1:1985 and
keeps its data in `./data`.

## A cluster

A cluster is the same image with `SERENGETI_PEERS`, a shared cluster key and TLS between the nodes.
[examples/cluster/compose.yaml](examples/cluster/compose.yaml) runs three nodes with Docker Compose, and
the image's `serengeti-certs` makes their certificates:

```sh
cd examples/cluster
printf 'SERENGETI_ADMIN_PASSWORD=%s\nSERENGETI_CLUSTER_KEY=%s\n' "$SERENGETI_PASSWORD" "$(head -c 32 /dev/urandom | base64)" > .env
docker compose run --rm certs
docker compose up -d
```

The comments in the Compose file say how to reach the nodes, and
[docs/CONFIGURATION.md](docs/CONFIGURATION.md) has the settings. CI runs this example as written.

## Writes in a cluster

A write goes to any node. With the default, `"ack": "majority"`, the node answers 200 once a majority of
the cluster has the write on disk, and 503 when it cannot reach a majority. With `"ack": "local"`, it
answers 200 once the write is on its own disk and passes it on to the nodes it can reach; the rest get it
through sync when they can. Choose per request, or set `SERENGETI_WRITE_ACK` for a node's default.

```
POST /v1/query
{"query": "UPDATE shop.items SET qty = 90 WHERE _id = 'bolt'", "ack": "local"}
```

Reads answer from the node's own data unless you ask for `"consistency": "majority"`, which syncs with a
majority first. [docs/CONSISTENCY.md](docs/CONSISTENCY.md) has the details, including how conflicts are
detected (a version vector per row write) and what a 503 means.

## Indexes

```sh
q "CREATE INDEX ON shop.items (qty)"
q "SELECT name FROM shop.items WHERE qty > 200"
```

A `WHERE` that compares an indexed column with a value, alone or as one term of an AND, reads only the
matching rows; anything else scans the table. Lookups by `_id` never scan. Indexes change how fast a
query runs, not what it returns. [docs/QUERY.md](docs/QUERY.md) has the rules.

## Accounts

The admin makes an account for each application or person, with one of three roles: `read` (SELECT and
SHOW), `write` (also writes and schema changes) or `admin` (also accounts and backups).

```sh
curl -s -u "admin:$SERENGETI_PASSWORD" -X PUT -H 'Content-Type: application/json' \
  --data '{"password": "a long password for the dashboard", "role": "read"}' \
  http://127.0.0.1:1985/v1/users/dashboard; echo
# {"user":"dashboard","role":"read"}
```

A write by a read account gets 403. In a cluster, accounts replicate like rows: the newest change to a
name wins on every node, and a node that was down catches up. [docs/API.md](docs/API.md#accounts) has the
rest.

## Following changes

`GET /v1/changes` returns the changes a node applied after a cursor, waiting up to 60 s for the next one,
so a cache, a search index or a screen can follow a table instead of polling it:

```sh
cursor=$(curl -s -u "admin:$SERENGETI_PASSWORD" 'http://127.0.0.1:1985/v1/changes?db=shop&after=now' \
  | sed 's/.*"cursor":"\([^"]*\)".*/\1/')
curl -s -u "admin:$SERENGETI_PASSWORD" "http://127.0.0.1:1985/v1/changes?db=shop&table=items&after=$cursor&wait=30"
```

The [clients](clients/) wrap this as `follow()`. A node keeps its recent changes in memory only, so a
cursor from before a restart gets 410: read the table again and follow from now. See
[docs/API.md](docs/API.md#changes).

## How it compares

| | Serengeti 2.1 | rqlite | CouchDB | cr-sqlite |
|---|---|---|---|---|
| Shape | one jar or image, HTTP API | one binary, HTTP API | server, HTTP API | SQLite extension |
| Data and queries | JSON rows, a small SQL-like language with single-column indexes, no joins | SQLite, full SQL | JSON documents, Mango and map/reduce views | SQLite, full SQL |
| Writes when a node is cut off | refused by default; accepted with `"ack": "local"` | refused on the minority side | accepted | accepted (it is local) |
| Concurrent writes to one row | newer is the value; both listed by `SHOW CONFLICTS` | serialised through the Raft leader | deterministic winner; the others kept as conflicts | merged per column (last-writer-wins, counters) |
| Replication | built in, 1 to 7 nodes | built in (Raft) | built in | you move the changes yourself |

rqlite is the better choice when every write must be linearizable; it has also been in production use
for years. CouchDB has a decade of production use, offline-first clients and a much richer query model.
Serengeti is smaller than both, and young: 2.0 was its first usable release.

## Tested

GitHub Actions runs these on every change:

- 255 unit and property tests on Java 17 and 21, among them a property test that runs random writes and
  syncs on three replicas and checks, against the true causal history, that each replica keeps exactly
  the writes no other write had seen;
- 58 checks against the built image on amd64 and arm64, including `kill -9` during writes;
- 37 checks on a 3-node TLS cluster in Docker: nodes killed and restarted, one partitioned with iptables,
  one with fsync slowed by a second, local writes on both sides of a partition. Every write that was
  acknowledged must be on every node at the end;
- 44 checks that upgrade a 2.0.0 node and a 3-node 2.0.0 cluster, one node at a time under writes, and
  roll a node back to 2.0.0 after it has created an index;
- the quick start above, the examples and the recorded demo, run as written.

## Measured

On one dedicated EC2 c7g.2xlarge (8 Graviton3 vCPUs, gp3 EBS at 3000 IOPS), nodes and load generator in
Docker on that machine, medians of three runs. [docs/BENCHMARKS.md](docs/BENCHMARKS.md) has the method,
the spread and every table.

| | 2.0.0 | 2.1 |
|---|---:|---:|
| One node, each write fsynced, 32 clients | 351 writes/s | 2,661 writes/s |
| Three nodes, majority writes, 32 clients | 263 writes/s | 1,298 writes/s |
| Lookup by `_id` in 100,000 rows, 8 clients | 612 reads/s | 16,216 reads/s |
| Traffic between nodes at 20 writes/s, 100,000 rows | 21.6 MB/s | 64 KB/s |
| Write latency (p50) in that cluster | 211 ms | 7.2 ms |

A table scan runs at about the same speed in both (2.1 is 3% faster at 10,000 rows, 8% slower at 100,000).
Both were run with `sun.net.httpserver.nodelay` set; without it 2.0.0 waits about 40 ms per request on a
kept-alive connection, and 2.1 sets it itself.

## When not to use it

- The data does not fit in memory on every node: each node keeps all of it in memory, and refuses writes
  that add data (503) once it is estimated to fill `memory_limit_percent` of the heap.
- You need joins, transactions across statements, or indexes on more than one column. A `WHERE` without
  an indexed comparison scans its table.
- You need every write serialised, such as a balance that must never go below zero across nodes.
- You need more than 7 nodes, or nodes that hold only part of the data.
- You need permissions per database or table: an account's role (read, write or admin) applies to all of
  them.
- Clients you do not trust can reach the node directly. It serves 64 requests at a time, and a client that
  sends its request slowly holds one of them for up to 30 s, so 64 such connections, renewed, stall it. Put
  a proxy that buffers requests (nginx, Caddy, HAProxy) in front of a node that untrusted networks reach.

## Security defaults

- There is no built-in account. The admin password comes from the environment, a file, or the one-time
  setup token.
- Every route except `GET /health` needs HTTP Basic credentials. Ten failed attempts from one address in
  a minute block it for the rest of that minute.
- The jar listens on 127.0.0.1 unless `SERENGETI_BIND` says otherwise. The image binds all interfaces
  inside its container and publishes nothing; publish to 127.0.0.1 unless other machines need it.
- Nodes authenticate each other with a shared cluster key and talk over TLS, unless you explicitly allow
  plaintext on a private network.
- Without TLS on the client API, passwords cross the network in clear. Set `SERENGETI_TLS_KEYSTORE` (see
  docs/CONFIGURATION.md) before other machines connect, or reach the node through an SSH tunnel.

## Documentation

- [docs/QUERY.md](docs/QUERY.md): the query language, comparison rules and limits.
- [docs/API.md](docs/API.md): HTTP routes, authentication, metrics and errors.
- [docs/CONSISTENCY.md](docs/CONSISTENCY.md): replication, write acknowledgement, conflicts and what was tested.
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md): settings, defaults and the data directory.
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md): measured throughput and latency.
- [clients/](clients/): Python and JavaScript clients, one file each with no dependencies: queries,
  failover for reads across nodes, the change feed and accounts.
- [examples/](examples/): the HTTP API from curl, Python and JavaScript with nothing else; a three-node
  cluster with [Docker Compose](examples/cluster/) and on [Kubernetes](examples/kubernetes/).
- [CHANGELOG.md](CHANGELOG.md), [ROADMAP.md](ROADMAP.md), [VERSIONING.md](VERSIONING.md) and
  [CONTRIBUTING.md](CONTRIBUTING.md).

Upgrading from 2.0: 2.1 reads 2.0's data directory and syncs with 2.0 nodes, so a cluster can be upgraded
one node at a time. 1.x (up to v1.3.4) is not compatible: its HTTP routes, on-disk files and
`admin`/`admin` login are gone.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
