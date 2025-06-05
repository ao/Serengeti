# Serengeti Storage System

This document provides an overview of the Storage System in the Serengeti distributed database.

## Overview

The Storage System is a core component of Serengeti responsible for data persistence, retrieval, and management. It ensures that data is stored reliably, can be accessed efficiently, and is properly distributed across the network.

## Key Components

The Storage System consists of the following key components:

### 1. Storage

The `Storage` class is the main entry point for the Storage System. It provides:

- CRUD operations for database objects
- Data structure management
- In-memory data storage
- Interface for other components to access stored data

```java
// Example of Storage usage
DatabaseObject db = Storage.getDatabase("users_db");
TableStorageObject table = Storage.getTable("users_db", "profiles");
Storage.insertRow("users_db", "profiles", rowData);
```

### 2. StorageScheduler

The `StorageScheduler` is responsible for periodically persisting database state to disk. It:

- Runs as a background thread
- Executes at regular intervals (default: 60 seconds)
- Ensures data durability by writing to disk
- Implements error handling and retry mechanisms
- Manages transaction-like behavior for persistence operations

```java
// StorageScheduler is typically initialized by the Serengeti core
StorageScheduler scheduler = new StorageScheduler();
scheduler.start();

// It can be manually triggered if needed
scheduler.performPersistToDisk();
```

For detailed information about error handling in the StorageScheduler, see [StorageScheduler Error Handling](StorageSchedulerErrorHandling.md).

### 3. StorageReshuffle

The `StorageReshuffle` component handles data redistribution when nodes join or leave the network. It:

- Calculates optimal data placement
- Moves data between nodes
- Ensures balanced data distribution
- Maintains replication requirements

```java
// Example of StorageReshuffle usage when a node joins
StorageReshuffle.handleNodeJoin(newNode);

// Example of StorageReshuffle usage when a node leaves
StorageReshuffle.handleNodeLeave(departingNode);
```

### 4. LSM Storage Engine

The Log-Structured Merge (LSM) storage engine provides the underlying storage mechanism. It consists of:

- **MemTable**: In-memory sorted structure for recent writes
- **SSTable**: Immutable on-disk sorted files
- **Compaction**: Process of merging SSTables for efficiency
- **LSMStorageEngine**: Main class that coordinates these components
- **LSMStorageScheduler**: Specialized scheduler for LSM operations

```java
// Example of LSM Storage Engine usage
LSMStorageEngine engine = new LSMStorageEngine(dataDirectory);
engine.put(key, value);
byte[] result = engine.get(key);
engine.delete(key);
```

For detailed information about the compaction process, see [LSM Compaction](../lsm/compaction.md).

## Data Model

### Database Objects

The Storage System organizes data using the following hierarchy:

1. **DatabaseObject**: Represents a logical database
   - Contains multiple tables
   - Has metadata such as name, creation time, etc.

2. **TableStorageObject**: Represents a table within a database
   - Contains rows of data
   - Has schema information
   - Stores metadata such as indexes, constraints, etc.

3. **TableReplicaObject**: Represents a replica of a table
   - Contains the same data as the original table
   - Distributed across different nodes for redundancy

```java
// Example data model hierarchy
DatabaseObject db = new DatabaseObject("users_db");
TableStorageObject usersTable = new TableStorageObject("users", db);
TableReplicaObject userTableReplica = new TableReplicaObject(usersTable, targetNode);
```

### Data Serialization

The Storage System uses Java serialization for persisting objects to disk:

- **Standard Serialization**: For most objects
- **Custom Serialization**: For performance-critical components
- **AppendingObjectOutputStream**: For efficient appending to existing files

## Persistence Mechanism

### Disk Storage Format

Data is stored on disk using the following structure:

```
data/
├── server.constants
├── database_1/
│   ├── metadata.ser
│   ├── table_1.ser
│   ├── table_1_replica_1.ser
│   ├── table_1_replica_2.ser
│   ├── table_2.ser
│   └── ...
├── database_2/
│   └── ...
└── ...
```

### Persistence Process

The persistence process follows these steps:

1. **Preparation**: Validate the current state and prepare for persistence
2. **Metadata Persistence**: Save database metadata
3. **Table Persistence**: Save table data and structure
4. **Replica Persistence**: Save replica information
5. **Cleanup**: Remove temporary files and perform cleanup

## Data Distribution

### Consistent Hashing

The Storage System uses consistent hashing to determine data placement:

- Each node is assigned a position on a hash ring
- Data is assigned to nodes based on key hashing
- When nodes join or leave, only a fraction of data needs to move

### Replication

Data is replicated across multiple nodes for fault tolerance:

- Default replication factor is 3
- Replicas are placed on different nodes
- Read operations can be served by any replica
- Write operations are coordinated across all replicas

## Error Handling

The Storage System implements comprehensive error handling:

- **Transient Errors**: Temporary issues that may resolve with retries
- **Persistent Errors**: Serious issues requiring intervention
- **Retry Logic**: Exponential backoff for transient errors
- **Graceful Degradation**: System continues to function with non-critical errors

For detailed information about error handling, see [StorageScheduler Error Handling](StorageSchedulerErrorHandling.md).

## Performance Considerations

### Write Performance

- Writes are initially stored in memory for fast performance
- Periodic flushing to disk in batches
- LSM structure optimizes write performance

### Read Performance

- Frequently accessed data may be cached in memory
- Indexes improve read performance for specific queries
- Read operations can be distributed across replicas

### Optimization Techniques

- **Compaction**: Regular merging of SSTables to optimize storage
- **Bloom Filters**: Reduce unnecessary disk reads
- **Caching**: Keep frequently accessed data in memory
- **Batch Processing**: Group operations for efficiency

## Configuration

The Storage System can be configured through the following parameters:

| Parameter | Description | Default Value |
|-----------|-------------|---------------|
| `persistenceIntervalMs` | Time between persistence operations | 60000 (1 minute) |
| `maxRetryAttempts` | Maximum retry attempts for transient errors | 3 |
| `replicationFactor` | Number of replicas for each table | 3 |
| `dataDirectory` | Directory for storing data files | ./data |

## Integration with Other Components

### Query Engine Integration

The Storage System integrates with the Query Engine to:
- Retrieve data for queries
- Apply updates from write operations
- Provide metadata for query planning

### Index Integration

The Storage System works with the Indexing System to:
- Update indexes when data changes
- Use indexes for efficient data retrieval
- Maintain index consistency

### Network Integration

The Storage System interacts with the Network component to:
- Replicate data across nodes
- Coordinate distributed operations
- Handle node joins and departures

## Best Practices

1. **Regular Monitoring**: Monitor disk usage and performance metrics
2. **Backup Strategy**: Implement regular backups for disaster recovery
3. **Resource Planning**: Ensure adequate disk space and memory
4. **Performance Tuning**: Adjust configuration parameters based on workload

## Future Enhancements

1. **Pluggable Storage Engines**: Support for different storage engine implementations
2. **Advanced Compression**: Implement data compression for storage efficiency
3. **Tiered Storage**: Support for hot/cold data tiering
4. **Point-in-time Recovery**: Enhanced recovery capabilities

## Conclusion

The Storage System is a critical component of Serengeti that provides reliable, efficient data storage and retrieval. Its design balances performance, durability, and fault tolerance to support the distributed nature of the Serengeti database system.