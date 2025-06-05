# Serengeti - The Autonomous Distributed Database

![Java CI with Maven](https://github.com/ao/Serengeti/workflows/Java%20CI%20with%20Maven/badge.svg)
![CI](https://github.com/ao/Serengeti/workflows/CI/badge.svg)
![Fast Tests](https://github.com/ao/Serengeti/workflows/Fast%20Tests/badge.svg)
![StorageScheduler Tests](https://github.com/ao/Serengeti/workflows/StorageScheduler%20Tests/badge.svg)

![Serengeti Dashboard](artwork/dash1.png?raw=true "Serengeti Dashboard")

This software application proves the potential for an autonomous distributed database system.

Serengeti runs on any machine through the JVM and requires zero configuration or management to setup or maintain.

Simply start Serengeti on any number of machines on a controlled network where each machine is a member of the same subnet. Each instance will automatically connect to each other and create a distributed database. 

Data will be replicated across the network and when a new node joins, it will automatically be given the existing databases and tables layout along with all replication information.

If one of the instances dies, the other nodes will check back and wait for a short recovery before reallocating the database pieces that were on that node to other nodes across the network.  

## How do I interact with it?
Once Serengeti is running, you simply connect to `http://<localhost_or_node_ip>:1985/dashboard` to get going.

## Requirements
This project was built on IntelliJ IDEA under JDK 11 runtime.

## Is there a JAR available?
Yes, take a look at the [release page](https://github.com/ao/serengeti/releases)

Currently version 0.0.1 is the only version, so [grab it here](https://github.com/ao/serengeti/releases/download/0.0.1/ADD_0.0.1.zip)

Unzip it and then simply run `java -jar serengeti.jar`

## Build it yourself?
Yes, of course you can!

`git clone https://github.com/ao/serengeti.git`

### Using IntelliJ?

`Open in IntelliJ IDEA.`

`Edit configurations..`

`+ Application`

Set the `classpath` to `Serengeti` and the `Main class` to `Serengeti`

`Run the application!`

### Using Maven on the commandline?

`mvn clean install`

`java -jar target/serengeti-1.0-SNAPSHOT.jar`

## Testing

### Running the Comprehensive Test Suite

To run the comprehensive test suite:

```bash
mvn test
```

### Running the Fast Test Suite

For rapid feedback during development, use the fast test suite which completes in under 2 minutes:

```bash
./run_fast_tests.sh  # On Linux/Mac
run_fast_tests.bat   # On Windows
```

Or directly with Maven:

```bash
mvn test -Pfast-tests
```

For more information about the fast test suite, see [Fast Test Suite README](src/test/java/com/ataiva/serengeti/fast/README.md).

### StorageScheduler Tests

The StorageScheduler is a critical component in Serengeti responsible for periodically persisting database state to disk. It ensures data durability by:
- Running as a background thread that executes at regular intervals
- Saving all database metadata to disk
- Persisting table storage objects and table replica objects
- Managing concurrent access to prevent data corruption

The StorageScheduler testing is organized into two categories:
- **Comprehensive Tests**: Thorough validation of all aspects of the component
- **Fast Tests**: Quick feedback during development, focusing on core functionality

#### Running StorageScheduler Tests

Dedicated scripts are provided for running StorageScheduler tests:

```bash
# Linux/macOS
./run_storage_scheduler_tests.sh --all       # Run all tests
./run_storage_scheduler_tests.sh --fast      # Run only fast tests
./run_storage_scheduler_tests.sh --comprehensive  # Run only comprehensive tests

# Windows
run_storage_scheduler_tests.bat --all        # Run all tests
run_storage_scheduler_tests.bat --fast       # Run only fast tests
run_storage_scheduler_tests.bat --comprehensive   # Run only comprehensive tests
```

For detailed information about the StorageScheduler testing strategy, see [StorageScheduler Testing Strategy](docs/testing/StorageSchedulerTestingStrategy.md).

#### CI/CD Integration

StorageScheduler tests are fully integrated into the CI/CD pipeline:

- **Fast Tests**: StorageScheduler fast tests run automatically as part of the Fast Tests workflow
- **Comprehensive Tests**: A dedicated workflow runs comprehensive, integration, and mutation tests
- **Quality Gates**: The pipeline enforces strict coverage thresholds (90% line, 85% branch, 100% method)
- **Mutation Testing**: Ensures tests are effective at catching bugs by verifying they fail when code is mutated

CI/CD workflows run automatically on:
- Pushes to main, master, and develop branches
- Pull requests to these branches
- Changes to StorageScheduler code or tests
- Manual triggers via GitHub Actions

For more details on CI/CD integration, see the [CI/CD Integration](docs/testing/StorageSchedulerTestingStrategy.md#cicd-integration) section in the testing strategy document.

## Problems?
[Create an issue](https://github.com/ao/serengeti/issues/new) if you need help

