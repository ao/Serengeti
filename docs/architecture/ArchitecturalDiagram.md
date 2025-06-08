# Serengeti Architectural Diagram

This document provides a comprehensive architectural diagram of the Serengeti autonomous distributed database system.

## System Architecture Overview

The following diagram illustrates the high-level architecture of the Serengeti system, showing the main components, their interactions, the distributed nature of the system, and key data flows:

![Serengeti Architecture](../../artwork/serengeti_architecture.svg)

## Component Descriptions

### 1. Serengeti Core
The main entry point and coordinator for the system. It initializes and manages all other components, handles configuration, and coordinates the system lifecycle.

### 2. Server Layer
Provides the interface between clients and the Serengeti database system. It handles HTTP requests, serves web-based interfaces (Dashboard and Interactive), and routes operations to appropriate internal components.

### 3. Query Layer
Processes and executes queries against the database. It includes:
- **Query Parser**: Parses SQL queries into an abstract syntax tree
- **Query Optimizer**: Improves execution plans for efficiency
- **Query Executor**: Runs the optimized query plans
- **Query Cache**: Stores results of frequent queries

### 4. Storage Layer
Responsible for data persistence, retrieval, and management. It includes:
- **Storage**: Main entry point for storage operations
- **Storage Scheduler**: Periodically persists database state to disk
- **LSM Storage Engine**: Implements Log-Structured Merge-tree storage with MemTables and SSTables
- **Write-Ahead Log (WAL)**: Ensures data durability and crash recovery

### 5. Index Layer
Provides efficient data access through indexes:
- **Index Manager**: Manages index creation, maintenance, and usage
- **B-Tree Index**: Implements B-tree data structure for efficient lookups

### 6. Network Layer
Handles communication between nodes in the distributed system:
- **Node Discovery**: Finds other Serengeti instances on the network
- **Failure Detector**: Identifies when nodes become unavailable
- **Data Replication**: Replicates data across nodes for redundancy

## Distributed Architecture

The diagram illustrates how multiple Serengeti nodes communicate with each other to form a distributed database system. Key aspects include:

1. **Node Communication**: Nodes communicate with each other through the Network component
2. **Data Replication**: Data is replicated across nodes for fault tolerance
3. **Distributed Query Execution**: Queries can be executed across multiple nodes
4. **Automatic Node Discovery**: New nodes are automatically discovered and integrated

## Key Data Flows

### Query Processing Flow
1. Client sends a query to the Server
2. Server forwards the query to the Query Engine
3. Query Engine parses, optimizes, and executes the query
4. Storage and Index components provide data access
5. Results are returned to the client

### Data Persistence Flow
1. Data changes are made in the Storage component
2. Changes are logged to the Write-Ahead Log
3. Storage Scheduler periodically persists changes to disk
4. LSM Storage Engine manages on-disk storage format

### Replication Flow
1. Data changes in one node are captured
2. Network component replicates changes to other nodes
3. Other nodes apply the changes to their local storage
4. Acknowledgments are sent back to the originating node

### Node Join/Leave Flow
1. New node announces itself via Node Discovery
2. Existing nodes detect the new node
3. Data redistribution occurs to include the new node
4. Similar process happens in reverse when a node leaves