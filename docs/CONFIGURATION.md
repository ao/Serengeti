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
| `tombstone_ttl_hours` | `168` | How long a delete's tombstone is kept, and so the longest a cluster node may be away and still rejoin with its data. 1 to 87600. |
| `changes_buffer` | `10000` | How many recent changes the node keeps in memory for `GET /v1/changes` (see API.md, "Changes"), 0 to 1,000,000; `0` turns the feed off, and the route answers 404. Each held change keeps its row's value alive, so a large buffer of large rows costs memory. |
| `http_threads` | `64` | Threads that answer HTTP requests, 1 to 1024. A request holds one while it is read, run and answered, so a client that sends slowly holds one for up to 30 s; half of them at most wait on `GET /v1/changes`. |
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
| `snapshot.jsonl` | The state at the last checkpoint. The log is folded into it after 64 MiB. |
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

## Scheduled backups

With `backup_dir` set, the node writes a backup there every `backup_interval_minutes` and keeps the newest
`backup_keep`:

```sh
docker run -d --name serengeti -p 127.0.0.1:1985:1985 -v serengeti-data:/data -v serengeti-backups:/backups \
  -e SERENGETI_BACKUP_DIR=/backups -e SERENGETI_ADMIN_PASSWORD ataiva/serengeti
```

- Each backup is `serengeti-backup-NODE-YYYYMMDDTHHMMSSZ.jsonl` (UTC; NODE is the first 8 characters of the
  node's id, from `node-id`), mode 0600, in the format of `GET /v1/backup`, so `serengeti restore` takes it.
  It is written to a hidden temporary file, forced to disk and then renamed, so a file with that name is
  always whole; a node that stops while writing one leaves only the temporary file, which its next start
  deletes.
- The node checks once a minute whether the interval has passed since its newest backup in the directory,
  also one an earlier run wrote. So a node that restarts more often than the interval still backs up, and
  the first backup of a new directory comes about a minute after the start. A backup is also due when the
  clock is before the newest name (it went back); a name after the clock is reported at start, and the
  schedule then counts from the start.
- Only this node's files with that name are deleted, the oldest first, when it has more than `backup_keep`,
  and never the one it has just written. Other files in the directory, other nodes' backups among them, are
  left alone, so nodes can share a directory.
- A directory that cannot be made or written, or that is the data directory or inside it (also through a
  link), stops the node at start with exit status 2. A backup that fails later (a full disk, say) is logged
  as a warning and counted in `serengeti_backup_failures_total`; the next check tries again. A node whose
  disk failed writes none (see [Health](#health-readiness-and-logs)).
- The node first writes the backup into its data directory, as `GET /v1/backup` does, and writes wait
  meanwhile; then it copies it into `backup_dir` without holding anything up, so a slow or stuck backup
  volume never stops writes. The data directory needs room for one backup, and `backup_dir` for about
  `backup_keep` of them; put it on a volume of its own, and better on another disk than the data.
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
