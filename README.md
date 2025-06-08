# Serengeti - The Autonomous Distributed Database

![Java CI with Maven](https://github.com/ao/Serengeti/workflows/Java%20CI%20with%20Maven/badge.svg)
![CI](https://github.com/ao/Serengeti/workflows/CI/badge.svg)
![Fast Tests](https://github.com/ao/Serengeti/workflows/Fast%20Tests/badge.svg)
![StorageScheduler Tests](https://github.com/ao/Serengeti/workflows/StorageScheduler%20Tests/badge.svg)

![Serengeti Dashboard](artwork/dash1.png?raw=true "Serengeti Dashboard")

## Overview

Serengeti is a next-generation autonomous distributed database system designed for modern applications that demand high availability, scalability, and zero-configuration management. Built on the JVM, Serengeti brings enterprise-grade distributed database capabilities with unprecedented ease of deployment and maintenance.

### Key Features

- **Zero Configuration**: Deploy and forget - no complex setup or ongoing maintenance required
- **Autonomous Operation**: Self-organizing, self-healing distributed architecture
- **Automatic Node Discovery**: Nodes automatically find each other on the same subnet
- **Seamless Scaling**: Add or remove nodes without downtime or manual data redistribution
- **Fault Tolerance**: Automatic data replication and recovery from node failures
- **SQL-like Query Interface**: Familiar query language for easy data manipulation
- **Web-based Dashboard**: Intuitive interface for monitoring and management
- **High Performance**: Optimized storage engine and query processing

Simply start Serengeti on any number of machines on a controlled network where each machine is a member of the same subnet. Each instance will automatically connect to each other and create a distributed database.

Data is replicated across the network for redundancy, and when a new node joins, it automatically receives the existing database structure and replication information. If a node fails, the system automatically detects the failure and redistributes the data to maintain availability and redundancy.

## System Architecture

Serengeti's architecture consists of several key components working together to provide a robust, distributed database system:

![Serengeti Architecture](artwork/serengeti_architecture.svg)

### Core Components

- **Serengeti Core**: System initialization and component lifecycle management
- **Server**: Handles client connections and provides web interfaces
- **Query Engine**: Processes and optimizes database queries
- **Storage System**: Manages data persistence with an LSM storage engine
- **Indexing System**: Provides efficient data access through B-tree indexes
- **Network System**: Enables communication between nodes in the distributed system

For a detailed architectural overview, see the [Architectural Diagram](docs/architecture/ArchitecturalDiagram.md) and [System Architecture](docs/architecture/SystemArchitecture.md) documentation.

## Getting Started

### Quick Start with Docker

The fastest way to get started with Serengeti is using Docker:

```bash
docker pull ataiva/serengeti:latest
docker run -p 1985:1985 ataiva/serengeti:latest
```

Then access the dashboard at `http://localhost:1985/dashboard`

### Using Pre-built JAR

1. Download the latest release from the [releases page](https://github.com/ao/serengeti/releases)
2. Unzip the package
3. Run the application:
   ```bash
   java -jar serengeti.jar
   ```

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/ao/serengeti.git
   ```

2. Build with Maven:
   ```bash
   mvn clean install
   ```

3. Run the application:
   ```bash
   java -jar target/serengeti-<version>.jar
   ```

Where `<version>` is the current version of the project.

## Interacting with Serengeti

Once Serengeti is running, you can interact with it through:

- **Dashboard**: Access the administrative dashboard at `http://<host>:1985/dashboard`
- **Interactive Console**: Execute queries through the interactive console at `http://<host>:1985/interactive`
- **REST API**: Programmatically interact with the database through the REST API

## Testing

Serengeti includes comprehensive testing frameworks to ensure reliability and performance:

### Comprehensive Test Suite

```bash
mvn test
```

### Fast Test Suite

For rapid feedback during development:

```bash
./run_fast_tests.sh  # On Linux/Mac
run_fast_tests.bat   # On Windows
```

Or directly with Maven:

```bash
mvn test -Pfast-tests
```

### StorageScheduler Tests

The StorageScheduler is a critical component responsible for data durability. Dedicated scripts are provided for testing:

```bash
# Linux/macOS
./run_storage_scheduler_tests.sh --all            # Run all tests
./run_storage_scheduler_tests.sh --fast           # Run only fast tests
./run_storage_scheduler_tests.sh --comprehensive  # Run only comprehensive tests

# Windows
run_storage_scheduler_tests.bat --all             # Run all tests
run_storage_scheduler_tests.bat --fast            # Run only fast tests
run_storage_scheduler_tests.bat --comprehensive   # Run only comprehensive tests
```

For detailed information about the testing strategy, see [StorageScheduler Testing Strategy](docs/testing/StorageSchedulerTestingStrategy.md).

## Documentation

Serengeti includes comprehensive documentation to help you understand, use, and contribute to the project:

### User Guides

- [Getting Started](docs/user-guides/GettingStarted.md) - Installation, configuration, and initial usage
- [Basic Operations](docs/user-guides/BasicOperations.md) - Common database operations and queries
- [Troubleshooting](docs/user-guides/Troubleshooting.md) - Solutions for common issues

### Architecture Documentation

- [System Architecture](docs/architecture/SystemArchitecture.md) - Overview of the Serengeti system architecture
- [Architectural Diagram](docs/architecture/ArchitecturalDiagram.md) - Visual representation of the system architecture
- [Component Interactions](docs/architecture/ComponentInteractions.md) - How components interact with each other
- [Design Decisions](docs/architecture/DesignDecisions.md) - Key design decisions and trade-offs

### Component Documentation

- [Serengeti Core](docs/components/SerengetiCore.md) - The main Serengeti class and system initialization
- [Storage System](docs/storage/StorageSystem.md) - Overview of the storage system
- [Write-Ahead Logging](docs/storage/WriteAheadLogging.md) - Crash recovery using Write-Ahead Logging
- [Query Engine](docs/components/QueryEngine.md) - How the query engine processes queries
- [Network Component](docs/components/Network.md) - Network communication between nodes
- [Server Component](docs/components/Server.md) - The server component that handles client requests
- [Indexing System](docs/indexing/IndexingSystem.md) - Overview of the indexing system
- [LSM Compaction](docs/lsm/compaction.md) - LSM storage engine compaction process

### Contributing

- [Contributing Guide](CONTRIBUTING.md) - How to contribute to Serengeti
- [Changelog](CHANGELOG.md) - History of changes to the project

For a complete list of documentation, see the [Documentation Index](docs/README.md).

## Requirements

- JDK 11 or higher
- Maven 3.6+ (for building from source)
- Network environment where nodes can discover each other (same subnet)

## Use Cases

Serengeti is ideal for:

- **Microservices Architectures**: Provide a distributed data layer for microservices
- **Edge Computing**: Deploy database capabilities at the edge with minimal configuration
- **High-Availability Systems**: Ensure data availability even during node failures
- **Scalable Applications**: Easily scale database capacity by adding nodes
- **Development and Testing**: Quickly spin up a distributed database for development and testing

## Community and Support

- [GitHub Issues](https://github.com/ao/serengeti/issues) - Report bugs or request features
- [GitHub Discussions](https://github.com/ao/serengeti/discussions) - Ask questions and discuss ideas
- [Contributing](CONTRIBUTING.md) - Learn how to contribute to the project

## License

Serengeti is open-source software licensed under the [LICENSE](LICENSE) file in the repository.
