# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Releases up to 1.3.4 are described in their
GitHub release notes and tags.

## [Unreleased] - 2.0.0

2.0 replaces the storage, query and server code of 1.x. It is not compatible with 1.x: the HTTP routes,
the query language and the files in the data directory all change.

### Fixed
- The node starts and serves HTTP. 1.3.4 exited with a NullPointerException when `data/` was missing,
  and otherwise held port 1985 without answering, on Java 11, 17 and 21.
- Inserted rows are kept. 1.x wrote schema files only, so rows were lost on restart.

### Added
- A write-ahead log with CRC32C checks, fsync before acknowledging by default (`SERENGETI_FSYNC`), and
  snapshots. Recovery drops a torn tail instead of failing.
- A query language with a hand-written parser: CREATE/DROP DATABASE and TABLE, SHOW, INSERT, SELECT with
  WHERE, ORDER BY and LIMIT, UPDATE, DELETE, COUNT(*). See docs/QUERY.md.
- `POST /v1/query`, `POST /v1/setup`, `POST /v1/password`, `GET /v1/status` and `GET /health`. See
  docs/API.md.
- Configuration from a properties file and `SERENGETI_*` variables, with secrets from variables or
  files. See docs/CONFIGURATION.md.
- HTTPS: `SERENGETI_TLS_KEYSTORE` (PKCS#12) switches the listener to TLS 1.2 and 1.3, with no plaintext
  listener.
- One Docker image for linux/amd64 and linux/arm64, running as a non-root user, with a health check.
- Tests: unit and property tests (`mvn verify`, Java 17 and 21), and container tests against the image
  (`tests/container/run.sh`), including `kill -9` during writes.
- A quick start that CI runs (`docs/quickstart.sh`), and clients for curl, Python (`urllib`) and
  JavaScript (`fetch`) under `examples/`, which CI runs in their own containers.

### Changed
- The default bind address is 127.0.0.1. The image binds all interfaces inside its container and
  publishes nothing.
- `POST /post` takes the same JSON body as `/v1/query` and is kept for 2.0 only.
- The dashboard and interactive console are replaced by one console page at `/`.
- Java 17 or later is required.
- Versions come from Git tags. `release.yml` publishes the image, the jar and the GitHub release for a
  tag; see VERSIONING.md. `version.txt` is removed.

### Removed
- The `admin`/`admin` account.
- Automatic discovery that connected to port 1985 on every address in the subnet. Replication across
  configured peers is planned; until it lands, 2.0 runs as a single node.
- The LSM storage engine, B-tree indexes, query optimizer and the other 1.x modules that the running
  server never used. They remain in the history and the v1.3.4 tag.

### Security
- Every route except `GET /health` needs HTTP Basic credentials, checked against bcrypt hashes in a
  file readable by the owner only. Failed logins are limited per address.
- Request bodies are limited to 1 MiB; JSON routes require `Content-Type: application/json`, and POSTs
  from another origin are refused. The console inserts data as text, under a Content-Security-Policy.
- Errors no longer include stack traces.
