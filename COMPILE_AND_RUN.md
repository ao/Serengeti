# Building and running

See the [README](README.md#quick-start). In short: `mvn package` builds
`target/Serengeti-<version>-jar-with-dependencies.jar` (Java 17 or later), and `mvn verify` also runs the
unit and property tests. `tests/container/run.sh` builds the Docker image and runs the container tests
against it.
