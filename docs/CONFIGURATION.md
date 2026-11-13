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
| `peers` | none | The other nodes of the cluster, `host:port,host:port`. Not this node itself. See [CONSISTENCY.md](CONSISTENCY.md). |
| `cluster_size` | peers + 1 | Number of nodes, 1 to 7. Writes need a majority of it. Required with `discovery_cidr`. |
| `discovery_cidr` | none | Opt-in: find peers by asking `/internal/hello` on `port` at every address in this range, at most 256 addresses (a /24 or /120). Needs TLS and `cluster_size`. |
| `cluster_plaintext` | none | `allow` lets a node with peers run without TLS, sending the cluster key and data in clear. For a private network only. |
| `tls_truststore` | JDK CAs | PKCS#12 file with the CA certificates that peers' certificates must chain to. Needs its password below. |
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
| trust store password | `SERENGETI_TLS_TRUSTSTORE_PASSWORD` | `tls_truststore_password_file` |
| cluster key (32+ characters) | `SERENGETI_CLUSTER_KEY` | `cluster_key_file` |

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

Back up a stopped node by copying the directory. A running node has no consistent backup yet.
