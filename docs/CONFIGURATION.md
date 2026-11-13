# Configuration

Settings come from three places. Later ones win:

1. the defaults below;
2. a properties file, named by `--config FILE` or `SERENGETI_CONFIG`;
3. environment variables: the setting name in upper case with a `SERENGETI_` prefix, e.g. `port` is
   `SERENGETI_PORT`.

An unknown key in the file is an error, so a typo stops the node instead of being ignored. Any invalid
setting makes the node exit with status 2 and a one-line message.

| Setting | Default | Meaning |
|---|---|---|
| `bind` | `127.0.0.1` | Address to listen on. The Docker image sets `0.0.0.0`. |
| `port` | `1985` | TCP port. |
| `data_dir` | `data` | Data directory, created if missing. The image uses `/data`. |
| `fsync` | `always` | `always`: the log is forced to disk before a write is acknowledged. `interval`: once a second; a machine crash can lose the last second. `off`: the operating system decides. |
| `admin_password_file` | none | File holding the first admin password (12 to 72 bytes). |
| `peers` | none | Reserved for replication. Refused in this build. |
| `discovery_cidr` | none | Reserved for replication. Refused in this build. |
| `tls_keystore` | none | PKCS#12 file with this node's key and certificate chain. When set, the node serves HTTPS only (TLS 1.2 and 1.3) and there is no plaintext listener. Needs the keystore password below. |

A key store for a test node can be made with the JDK's keytool:

```sh
keytool -genkeypair -alias node -keyalg EC -groupname secp256r1 -dname CN=db.example \
  -ext san=dns:db.example -validity 365 -storetype PKCS12 -keystore node.p12
```

For anything longer-lived, use a certificate from your own CA, with the host name clients use in its
subject alternative names.

## Secrets

Secrets are never read from the properties file. Each one comes from an environment variable or from a
file named by its `_file` setting (or `SERENGETI_..._FILE`); the variable wins if both are set. A trailing
newline in the file is ignored.

| Secret | Variable | File setting |
|---|---|---|
| first admin password | `SERENGETI_ADMIN_PASSWORD` | `admin_password_file` |
| key store password | `SERENGETI_TLS_KEYSTORE_PASSWORD` | `tls_keystore_password_file` |

The admin password is only used when there is no account yet. Once `users.json` exists, it is ignored
(the node logs that), and the password is changed with `POST /v1/password`.

## Data directory

| File | Contents |
|---|---|
| `wal.log` | Write-ahead log: length, CRC32C and JSON per batch. |
| `snapshot.jsonl` | The state at the last checkpoint. The log is folded into it after 64 MiB. |
| `users.json` | Accounts as bcrypt hashes (cost 12). Mode 0600; a node refuses to start if others can read it. |
| `setup-token` | One-time setup token (mode 0600), present only until the first admin password is set. |
| `node-id` | A random id for this node, kept across restarts. |

Back up a running node with `GET /v1/backup` (admin account, see [API.md](API.md#backup)). Copying the directory
of a stopped node also works. A backup holds the data only: `users.json`, `node-id` and `setup-token` are not in it.

## Restore

```
serengeti restore [--config FILE] BACKUP_FILE
```

Loads a backup into the data directory that the settings name (`data_dir`, or `SERENGETI_DATA_DIR`). Stop the
node first. Restore does not lock the directory, and running it beside a node that writes to the same files
is not supported.

- The directory must not hold `wal.log` or `snapshot.jsonl`. An existing `users.json` is kept, so the restored
  node keeps its accounts. Without one, the node asks for the setup token or `SERENGETI_ADMIN_PASSWORD` again.
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
