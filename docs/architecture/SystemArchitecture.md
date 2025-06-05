# Serengeti System Architecture

This document provides an overview of the Serengeti distributed database system architecture.

## System Overview

Serengeti is an autonomous distributed database system that runs on the Java Virtual Machine (JVM). It is designed to require zero configuration or management to set up or maintain. Instances automatically connect to each other on the same subnet to create a distributed database with data replication.

![System Architecture Diagram](../../artwork/system_architecture.png)

## Core Components

The Serengeti system consists of the following core components:

### 1. Serengeti Core

The main entry point and coordinator for the system. It initializes and manages all other components.

**Key Responsibilities:**
- System initialization and startup
- Component lifecycle management
- Configuration management
- Shutdown handling

### 2. Storage System

Responsible for data persistence and retrieval.

**Key Components:**
- **Storage**: Manages database objects and provides CRUD operations
- **StorageScheduler**: Periodically persists database state to disk
- **StorageReshuffle**: Handles data redistribution when nodes join or leave
- **LSM Storage Engine**: Implements Log-Structured Merge-tree storage

### 3. Indexing System

Provides efficient data access through indexes.

**Key Components:**
- **IndexManager**: Manages index creation, maintenance, and usage
- **BTreeIndex**: Implements B-tree data structure for efficient lookups

### 4. Query Engine

Processes and executes queries against the database.

**Key Components:**
- **QueryEngine**: Parses and executes queries
- **QueryLog**: Records query history for analysis and optimization

### 5. Network System

Handles communication between nodes in the distributed system.

**Key Responsibilities:**
- Node discovery and connection management
- Data replication across nodes
- Distributed query execution
- Failure detection and recovery

### 6. Server

Provides the interface for clients to interact with the database.

**Key Components:**
- **Server**: Handles client connections and requests
- **ServerConstants**: Defines server configuration constants

### 7. User Interface

Provides web-based interfaces for interacting with the database.

**Key Components:**
- **Dashboard**: Web-based administrative dashboard
- **Interactive**: Interactive query interface

## Data Flow

1. **Client Request**: Client sends a request to the server via HTTP
2. **Request Processing**: Server processes the request and forwards it to the appropriate component
3. **Query Execution**: For read operations, the Query Engine processes the query, using indexes when available
4. **Data Access**: The Storage system retrieves or modifies data as needed
5. **Response**: Results are returned to the client
6. **Persistence**: The StorageScheduler periodically persists changes to disk
7. **Replication**: Changes are replicated to other nodes in the network

## Distributed Architecture

Serengeti operates as a distributed system with the following characteristics:

### Node Discovery

When a Serengeti instance starts, it:
1. Broadcasts its presence on the local subnet
2. Discovers other Serengeti instances
3. Establishes connections with other instances
4. Joins the distributed database network

### Data Distribution

Data is distributed across nodes using:
1. Consistent hashing for data placement
2. Replication for fault tolerance
3. Automatic data redistribution when nodes join or leave

### Fault Tolerance

Serengeti provides fault tolerance through:
1. Data replication across multiple nodes
2. Automatic failure detection
3. Recovery mechanisms when nodes fail
4. Automatic data redistribution

## Scalability

Serengeti scales horizontally by adding more nodes to the network. Key scalability features include:

1. **Automatic Node Integration**: New nodes are automatically integrated into the network
2. **Dynamic Data Distribution**: Data is redistributed as nodes join or leave
3. **Distributed Query Processing**: Queries are processed across multiple nodes
4. **Parallel Operations**: Many operations can be performed in parallel across nodes

## Security

Serengeti's security model includes:

1. **Network Isolation**: Operates within a controlled network on the same subnet
2. **Authentication**: (Future enhancement)
3. **Authorization**: (Future enhancement)
4. **Encryption**: (Future enhancement)

## Future Architecture Enhancements

1. **Multi-region Support**: Extend beyond single subnet to support multi-region deployments
2. **Enhanced Security**: Add authentication, authorization, and encryption
3. **Advanced Replication Strategies**: Implement more sophisticated replication strategies
4. **Pluggable Storage Engines**: Support for different storage engine implementations
5. **Query Optimization**: Advanced query planning and optimization