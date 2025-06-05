# Serengeti Documentation

Welcome to the Serengeti documentation. This index provides links to all available documentation for the Serengeti distributed database system.

## Getting Started

- [Main README](../README.md) - Project overview and basic usage
- [Compilation and Running](../COMPILE_AND_RUN.md) - How to compile and run Serengeti
- [Docker Setup](../DOCKER_SETUP.md) - Setting up Serengeti with Docker

## Architecture

- [System Architecture](architecture/SystemArchitecture.md) - Overview of the Serengeti system architecture
- [Component Interactions](architecture/ComponentInteractions.md) - How components interact with each other
- [Design Decisions](architecture/DesignDecisions.md) - Key design decisions and trade-offs

## Components

### Core Components

- [Serengeti Core](components/SerengetiCore.md) - The main Serengeti class and system initialization

### Storage

- [Storage System](storage/StorageSystem.md) - Overview of the storage system
- [Write-Ahead Logging](storage/WriteAheadLogging.md) - Crash recovery using Write-Ahead Logging
- [Storage Scheduler](storage/StorageSchedulerErrorHandling.md) - Error handling in the Storage Scheduler
- [LSM Compaction](lsm/compaction.md) - LSM storage engine compaction process

### Indexing

- [Indexing System](indexing/IndexingSystem.md) - Overview of the indexing system

### Query

- [Query Engine](components/QueryEngine.md) - How the query engine processes queries

### Network

- [Network Component](components/Network.md) - Network communication between nodes

### Server

- [Server Component](components/Server.md) - The server component that handles client requests

## Testing

- [Storage Scheduler Testing Strategy](testing/StorageSchedulerTestingStrategy.md) - Testing strategy for the Storage Scheduler
- [Fast Tests](../src/test/java/com/ataiva/serengeti/fast/README.md) - Information about the fast test suite

## User Guides

- [Getting Started Guide](user-guides/GettingStarted.md) - Guide for new users
- [Basic Operations](user-guides/BasicOperations.md) - Common operations in Serengeti
- [Troubleshooting](user-guides/Troubleshooting.md) - Solutions to common issues

## Contributing

- [Contributing Guide](../CONTRIBUTING.md) - How to contribute to Serengeti
- [Changelog](../CHANGELOG.md) - History of changes to the project

## API Documentation

- [JavaDoc](https://example.com/javadoc/) - JavaDoc API documentation (external link)