# Configuration

Settings come from three places. Later ones win:

1. the defaults below;
2. a properties file, named by `--config FILE` or `SERENGETI_CONFIG`;
3. environment variables: the setting name in upper case with a `SERENGETI_` prefix, e.g. `port` is
   `SERENGETI_PORT`.

An unknown key in the file is an error, so a typo stops the node instead of being ignored. An
environment variable that names no setting (`SERENGETI_PROT`) is ignored. Any invalid setting makes the
node exit with status 2 and a one-line message.

| Setting | Default | Meaning |
|---|---|---|
| `bind` | `127.0.0.1` | Address to listen on. The Docker image sets `0.0.0.0`. |
| `port` | `1985` | TCP port. |
| `data_dir` | `data` | Data directory, created if missing. The image uses `/data`. |
| `fsync` | `always` | `always`: the log is forced to disk before a write is acknowledged. `interval`: once a second; a machine crash can lose the last second. `off`: the operating system decides. |
| `admin_password_file` | none | File holding the first admin password (at least 12 characters, at most 72 bytes in UTF-8). |
| `peers` | none | The other nodes of the cluster, `host:port,host:port`. Not this node itself. See [CONSISTENCY.md](CONSISTENCY.md). |
| `cluster_size` | peers + 1 | Number of nodes, 1 to 7. Writes need a majority of it. Required with `discovery_cidr`. |
| `discovery_cidr` | none | Opt-in: find peers by asking `/internal/hello` on `port` at every address in this range, at most 256 addresses (a /24 or /120). Needs TLS and `cluster_size`. |
| `write_ack` | `majority` | In a cluster: `majority` acknowledges a write once a majority of nodes has it; `local` once this node has it, even with no peer reachable. Requests override it with `"ack"`. See [CONSISTENCY.md](CONSISTENCY.md). |
| `tombstone_ttl_hours` | `168` | How long a delete's tombstone is kept (it is dropped at the next 10-minute boundary after). A cluster node away longer than this less 20 minutes must be emptied to rejoin. Set it the same on every node. 1 to 87600. |
| `changes_buffer` | `10000` | How many recent changes the node keeps in memory for `GET /v1/changes` (see API.md, "Changes"), 0 to 1,000,000; `0` turns the feed off, and the route answers 404. Each held change keeps its row's value alive, so a large buffer of large rows costs memory. The changes outlive a restart (`changes.jsonl`, below). Started with `0`, the node deletes the feed's files, and the next start with a buffer begins a new epoch. |
| `http_threads` | `64` | HTTP requests that run at once, 1 to 1024. A request holds one of them from when its body is in until its answer starts to go out, so slow clients do not hold them (see [Slow clients](#slow-clients)); half of them at most wait on `GET /v1/changes`. A request that gets none within 10 s is answered 503. |
| `http_connections` | `1024` | HTTP requests read or answered at once: the server's threads, at least `http_threads`, at most 16384. A client that sends or reads slowly holds one of these within the limits in [Slow clients](#slow-clients). |
| `memory_limit_percent` | `60` | Writes that add data (INSERT, UPDATE, CREATE) are refused with 503 once the rows and index entries held are estimated to take this share of the maximum heap; deletes and drops are always taken and make room at once. 10 to 100; `100` turns it off. Every database is held in memory, so this is the node's capacity. The image gives the heap 60% of the container's memory: a 512 MiB container holds an estimated 185 MiB of data with the default. |
| `backup_dir` | none | Directory for scheduled backups, created if missing (mode 0700). Not the data directory or inside it. See [Scheduled backups](#scheduled-backups). The image has an empty `/backups` for a volume. |
| `backup_interval_minutes` | `60` | Minutes between scheduled backups, 1 to 10080 (a week). |
| `backup_keep` | `24` | How many scheduled backups to keep, 1 to 10000; the oldest beyond that are deleted. |
| `cluster_plaintext` | none | `allow` lets a node with peers run without TLS, sending the cluster key and data in clear. For a private network only. Any other value stops the node. |
| `tls_truststore` | JDK CAs | PKCS#12 file with the CA certificates that peers' certificates must chain to. Needs its password below. |
| `tls_keystore` | none | PKCS#12 file with this node's key and certificate chain. When set, the node serves HTTPS only (TLS 1.2 and 1.3) and there is no plaintext listener. Needs the keystore password below. |

A key store for a test node can be made with the JDK's keytool:

```sh
keytool -genkeypair -alias node -keyalg EC -groupname secp256r1 -dname CN=db.example \
  -ext san=dns:db.example -validity 365 -storetype PKCS12 -keystore node.p12
```

For anything longer-lived, use a certificate from your own CA, with the host name clients use in its
subject alternative names.

## Slow clients

A node reads each request and writes its answer on one of up to `http_connections` threads, started as
needed and stopped after a minute idle, and runs at most `http_threads` requests at once. A request takes one
of those only once its body is in, and gives it back when its answer starts to go out, so a client that sends
or reads slowly holds a thread but none of the `http_threads`. The same limits apply to every client, signed
in or not, and cannot be changed:

- A request's line, headers and body must all arrive within 30 s of its first byte, and a connection that
  sends nothing for 30 s is closed.
- After the first 5 s of a body, each further second must bring 16 KiB more of it, or the connection is
  closed. Bodies are read into memory before the request runs; past the first 64 KiB of each, they share a
  sixteenth of the heap (at least 16 MiB), and a body that does not fit is answered 503. Node-to-node
  requests with the cluster key are not counted.
- A write that the client does not take within 30 s closes the connection.
- Answers of 256 KiB or more share a quarter of the heap (at least 48 MiB), counted from before they are
  built (three times their estimated size) until they are written. When one does not fit, writes that have
  waited on their client for a second or more are cut, oldest first; then the request waits up to 10 s for
  room, without holding one of the `http_threads`, and is answered 503 if none comes.
- When all `http_connections` threads are busy, a new connection takes the thread of the client that has
  been sending its headers longest, or else its body, or else waiting for a write to be taken, if for a
  second or more. Otherwise the connection waits for a thread.

`GET /health` and `GET /ready` take none of the `http_threads`, so a liveness or readiness probe is answered
while every one of them is in use. Node-to-node requests that carry the cluster key have an eighth of
`http_threads` (at least 4) kept for them, and wait for the others as any request does when those are taken,
so replication and sync go on while clients hold every shared one.

Each thread reserves 1 MiB for its stack and uses only the pages it reaches: a 1 GiB node held 276 MiB with
1,000 connections sending their headers slowly, against 111 MiB with 64 (`tests/slow/run.sh` on a
c7g.4xlarge). Past `http_connections`, connections wait or take a slow client's thread: with 3,000
connections from six addresses sending headers slowly, a probe got 82 to 89% of its requests answered.
`serengeti_http_cut_total{reason}` (API.md, "Metrics") counts the connections closed by these limits.

## Certificates for a cluster

Nodes in a cluster talk TLS to each other and check each other's certificates against one CA. The image
includes `serengeti-certs`, which makes the CA, a key store per node, the trust store and a random key
store password with keytool:

```sh
docker run --rm --user 0 -v serengeti-certs:/certs --entrypoint serengeti-certs ataiva/serengeti \
  n1=n1.example.com n2=n2.example.com n3=n3.example.com
```

Each certificate names the node (`n1`) and the names after `=` (host names or IP addresses), which must be
the names peers and clients use for it. Point each node at its files: `SERENGETI_TLS_KEYSTORE=/certs/n1.p12`,
`SERENGETI_TLS_TRUSTSTORE=/certs/trust.p12`, and `/certs/password` for both password files; clients use
`/certs/ca.pem`. The CA's private key is deleted once the node certificates are signed, so no node can
make more; to add a node, make new certificates for all of them (or pass `--keep-ca` and keep `ca.p12`
somewhere the nodes cannot read). `examples/cluster/compose.yaml` does all of this for three nodes.

## Secrets

Secrets are never read from the properties file. Each one comes from an environment variable or from a
file named by its `_file` setting (or `SERENGETI_..._FILE`); the variable wins if both are set. A trailing
newline in the file is ignored.

| Secret | Variable | File setting |
|---|---|---|
| first admin password | `SERENGETI_ADMIN_PASSWORD` | `admin_password_file` |
| key store password | `SERENGETI_TLS_KEYSTORE_PASSWORD` | `tls_keystore_password_file` |
| trust store password | `SERENGETI_TLS_TRUSTSTORE_PASSWORD` | `tls_truststore_password_file` |
| cluster key (32+ characters) | `SERENGETI_CLUSTER_KEY` | `cluster_key_file` |

The admin password is used when no admin account exists: at first start, and after every admin was
deleted (see API.md, "Accounts"). While an admin exists it is ignored (the node logs that), and passwords
are changed with `POST /v1/password` or `PUT /v1/users/NAME`.

## Data directory

| File | Contents |
|---|---|
| `wal.log` | Write-ahead log: length, CRC32C and JSON per batch. |
| `snapshot.jsonl` | The state at the last checkpoint. The log is folded into it after 64 MiB. Its first line has a checkpoint id, new at each checkpoint, which `changes.jsonl` names. |
| `changes.jsonl` | The change feed's epoch, the number the log's first change gets, and the last `changes_buffer` changes the feed held at the last checkpoint, which the emptied log no longer has. Written with each checkpoint, as `changes.jsonl.next` until the log is emptied, then renamed. At start the node replays the log after these changes, so cursors outlive a restart. Used only with the snapshot whose id it names; without it, or with another snapshot, the feed starts a new epoch. |
| `changes-marks.jsonl` | Where in the log tombstones were dropped since the last checkpoint (one line each, forced before they are dropped), so that the replay drops them at the same place and lists the same changes. With `fsync` `off`, also the node's starts and clean stops. Emptied at each checkpoint. |
| `indexes.jsonl` | Index definitions, rewritten whenever an index is created or dropped. Kept out of the log so a 2.0 binary can still open the directory. Present once an index has been created. |
| `accounts.json` | Every account as a bcrypt hash (cost 12) with its role, the versions of its changes, and the tombstones of deleted accounts. Mode 0600; a node refuses to start if others can read it. |
| `users.json` | The admin and write accounts again, in the form 2.0 and 2.1 read, so a node can be rolled back (read accounts cannot log in on those). If an older version changed a password here, the next start takes that change, dated by the file's time. Mode 0600; a node refuses to start if others can read it. |
| `setup-token` | One-time setup token (mode 0600), present only until the first admin password is set. |
| `node-id` | A random id for this node, kept across restarts. |
| `last-sync` | In a cluster: when this node last synced with a majority (Unix milliseconds), written at most once a minute. `serengeti restore` writes it too (see [CONSISTENCY.md](CONSISTENCY.md)). |
| `lock` | Empty. The running node holds a lock on it, so a second node started on the same directory (in the same container, or another container on the same volume) stops with exit status 2. The operating system drops the lock when the node exits, also after a crash. |

Back up a running node with `GET /v1/backup` (admin account, see [API.md](API.md#backup)), or let it write
backups on a schedule (`backup_dir`, below). Copying the directory of a stopped node also works; the copy
keeps that node's `node-id`, so start it in place of that node, not beside it. A backup holds the data only:
`accounts.json`, `users.json`, `node-id` and `setup-token` are not in it.

A copy of the directory also carries on the node's change feed. A node started on an older copy (or a disk
snapshot) cannot tell, and a cursor it gave out after the copy was made may then be continued with other
changes: delete `changes.jsonl` from such a copy before starting it, so the feed begins a new epoch.
`serengeti restore` needs no such step. 2.0 and 2.1 ignore the feed's files and the snapshot's id; after a
rollback, 2.2 goes on with the changes the older version logged, or starts a new epoch if that version
wrote a snapshot. 2.1 drops tombstones without recording where, and a peer's older write to such a row
then lists differently on replay, so if 2.1 ran on the directory in a cluster, delete `changes.jsonl` before
starting 2.2 again.

## Scheduled backups

With `backup_dir` set, the node writes a backup there every `backup_interval_minutes` and keeps the newest
`backup_keep`:

```sh
docker run -d --name serengeti -p 127.0.0.1:1985:1985 -v serengeti-data:/data -v serengeti-backups:/backups \
  -e SERENGETI_BACKUP_DIR=/backups -e SERENGETI_ADMIN_PASSWORD ataiva/serengeti
```

- Each backup is `serengeti-backup-NODE-YYYYMMDDTHHMMSSZ-SEQ.jsonl` (UTC; NODE is the first 8 characters of the
  node's id, from `node-id`; SEQ counts the node's backups), mode 0600, in the format of `GET /v1/backup`, so
  `serengeti restore` takes it.
  It is written to a hidden temporary file, forced to disk and then renamed, so a file with that name is
  always whole; a node that stops while writing one leaves only the temporary file, which its next start
  deletes.
- The node checks once a minute whether the interval has passed since its newest backup in the directory,
  also one an earlier run wrote. So a node that restarts more often than the interval still backs up, and
  the first backup of a new directory comes about a minute after the start. A backup is also due when the
  clock is before the newest name (it went back); a name after the clock is reported at start, and the
  schedule then counts from the start.
- Only this node's files with that name are deleted, the lowest SEQ first, when it has more than `backup_keep`,
  and never the one it has just written. Other files in the directory, other nodes' backups among them, are
  left alone, so nodes can share a directory.
- A directory that cannot be made or written, or that is the data directory or inside it (also through a
  link), stops the node at start with exit status 2. A backup that fails later (a full disk, say) is logged
  as a warning and counted in `serengeti_backup_failures_total`; the next check tries again. A node whose
  disk failed writes none (see [Health](#health-readiness-and-logs)).
- The node first writes the backup into its data directory, as `GET /v1/backup` does, and writes wait
  meanwhile; then it copies it into `backup_dir` without holding anything up, so a slow or stuck backup
  volume never stops writes. The data directory needs room for one backup: when less is free than the
  snapshot and the log take, a quarter more and 64 MiB, the backup is skipped and counted as failed rather
  than fill the disk the node writes to. `backup_dir` needs room for about `backup_keep` backups; put it on
  a volume of its own, and better on another disk than the data.
- In a cluster, every node with `backup_dir` backs up what it holds. One node is enough when the cluster is
  healthy; a node that was cut off backs up its own view.
- `serengeti_backup_last_success_time_seconds` and the alert `SerengetiBackupLate` in
  [examples/monitoring](../examples/monitoring/) tell when backups stop.

## Restore

```
serengeti restore [--config FILE] BACKUP_FILE
```

Loads a backup into the data directory that the settings name (`data_dir`, or `SERENGETI_DATA_DIR`). Stop the
node first. Restore holds the directory's lock while it writes, so it stops with "in use by another node" if a
node runs on the directory, and a node cannot start on it halfway through.

- The directory must not hold `wal.log`, `snapshot.jsonl` or `indexes.jsonl`. Existing account files are kept,
  so the restored node keeps its accounts. Without one, the node asks for the setup token or
  `SERENGETI_ADMIN_PASSWORD` again.
- The whole file is checked before anything is written: the format, every change, the count, the SHA-256 and
  that the changes apply. A truncated or altered file is refused, and nothing is written.
- Exit status 0 prints `restored N operations into DIR`. Exit status 2 prints the reason on stderr.

With the image, mount the backup read-only and write into a volume that is empty:

```sh
docker run --rm -v serengeti-restored:/data -v "$PWD:/in:ro" serengeti restore /in/serengeti-backup.jsonl
```

## Health, readiness and logs

- `GET /health` answers while the process serves HTTP. Use it for liveness.
- `GET /ready` answers 200 once the node serves and every readiness check passes. Use it before sending traffic.
  See [API.md](API.md#readiness).
- `GET /metrics` gives Prometheus metrics. It needs credentials. See [API.md](API.md#metrics).
- The node logs one INFO line per request, to stderr. See [API.md](API.md#request-log).
