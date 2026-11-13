# Versioning

Serengeti uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html), MAJOR.MINOR.PATCH.

- A release is a Git tag named `vMAJOR.MINOR.PATCH`, such as `v2.0.0`. The tagged commit is the release.
- Tags are not moved or reused. A wrong release is fixed by the next version.
- `pom.xml` on main keeps the next version with a `-SNAPSHOT` suffix (`2.0.0-SNAPSHOT`). Nobody commits a
  version bump for a release. `release.yml` sets the version from the tag while it builds, so the jar's
  file name, `serengeti --version`, `GET /health` and `GET /v1/status` all show the tag's version.
- A pre-release is a tag with a suffix, such as `v2.0.0-rc.1`. It is published as a GitHub pre-release and
  as the image tag `2.0.0-rc.1`, and it does not change `latest`.

## What the parts mean

- MAJOR: an incompatible change to the HTTP routes, the query language, the files in the data directory or
  the configuration settings. 2.0.0 is a MAJOR release: it is not compatible with 1.x.
- MINOR: a new feature that keeps existing clients, data and settings working.
- PATCH: a fix that keeps all of them working.

## Artifacts

Each release tag produces, from `.github/workflows/release.yml`:

- the image `ataiva/serengeti` for linux/amd64 and linux/arm64, with the tag `X.Y.Z`, and `latest` for
  releases that are not pre-releases;
- the jar `serengeti-X.Y.Z.jar` and its SHA-256 checksum, attached to a GitHub release whose notes are the
  matching section of CHANGELOG.md.

Releases up to 1.3.4 are described in their tags and GitHub releases. Their version numbers are not
reused.

See [docs/RELEASING.md](docs/RELEASING.md) for the steps.
