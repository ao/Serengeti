# Serengeti Component Interactions

This document describes how the various components of the Serengeti distributed database system interact with each other.

## Component Interaction Overview

The Serengeti system consists of several key components that work together to provide a distributed database solution. The following diagram illustrates the high-level interactions between these components:

```
                  ┌─────────────┐
                  │   Client    │
                  └──────┬──────┘
                         │
                         ▼
┌─────────────┐    ┌──────────┐    ┌─────────────┐
│  Dashboard  │◄───┤  Server  │───►│ Interactive │
└─────────────┘    └────┬─────┘    └─────────────┘
                        │
                        ▼
                ┌───────────────┐
                │ Query Engine  │
                └───────┬───────┘
                        │
          ┌─────────────┼─────────────┐
          │             │             │
          ▼             ▼             ▼
┌─────────────┐  ┌────────────┐  ┌─────────┐
│   Storage   │◄─┤    Index   │  │ Network │
└──────┬──────┘  └────────────┘  └────┬────┘
       │                              │
       ▼                              ▼
┌────────────────┐            ┌─────────────────┐
│StorageScheduler│            │ Other Instances │
└────────────────┘            └─────────────────┘
```

## Key Component Interactions

### 1. Client and Server Interaction

- **Client** connects to the **Server** via HTTP on port 1985
- **Server** processes client requests and routes them to the appropriate component
- **Server** returns responses to the **Client**

```java
// Example of client-server interaction
// Client makes HTTP request to http://localhost:1985/dashboard
// Server processes the request
Server.handleRequest(request);
// Server returns response to client
```

### 2. Server and UI Components Interaction

- **Server** serves the **Dashboard** web interface for administration
- **Server** serves the **Interactive** web interface for query execution
- Both UI components communicate with the **Server** via HTTP

```java
// Example of server-UI interaction
// Server serves dashboard HTML
Server.serveDashboard(request, response);
// Dashboard makes AJAX requests to server for data
Server.handleDashboardDataRequest(request, response);
```

### 3. Server and Query Engine Interaction

- **Server** forwards query requests to the **Query Engine**
- **Query Engine** processes queries and returns results to the **Server**

```java
// Example of server-query engine interaction
// Server receives query request from client
String query = request.getParameter("query");
// Server forwards query to Query Engine
QueryResponseObject result = QueryEngine.executeQuery(query);
// Server returns result to client
response.write(result.toJson());
```

### 4. Query Engine and Storage/Index Interaction

- **Query Engine** uses **Index** for efficient data lookups
- **Query Engine** retrieves data from **Storage**
- **Query Engine** modifies data in **Storage** for write operations

```java
// Example of query engine-storage/index interaction
// Query Engine checks if index exists for the query
if (IndexManager.hasIndex(database, table, column)) {
    // Use index for lookup
    results = IndexManager.lookup(database, table, column, value);
} else {
    // Perform full table scan
    results = Storage.scan(database, table, predicate);
}
```

### 5. Storage and StorageScheduler Interaction

- **StorageScheduler** periodically persists **Storage** state to disk
- **StorageScheduler** runs as a background thread
- **Storage** notifies **StorageScheduler** of significant changes

```java
// Example of storage-storage scheduler interaction
// Storage makes changes to data
Storage.updateTable(database, table, changes);
// StorageScheduler periodically persists changes
StorageScheduler.performPersistToDisk();
```

### 6. Network and Other Components Interaction

- **Network** discovers other Serengeti instances on the subnet
- **Network** replicates data changes to other instances
- **Network** coordinates distributed query execution
- **Network** detects node failures and initiates recovery

```java
// Example of network interaction with other components
// Network discovers other instances
List<Node> nodes = Network.discoverNodes();
// Network replicates data changes
Network.replicateChanges(changes, nodes);
// Network detects node failure
Network.handleNodeFailure(failedNode);
```

### 7. Index and Storage Interaction

- **Index** provides efficient lookup for **Storage** data
- **Storage** notifies **Index** of data changes for index updates

```java
// Example of index-storage interaction
// Storage notifies Index of data changes
Storage.updateTable(database, table, changes);
IndexManager.updateIndexes(database, table, changes);
```

## Component Lifecycle Interactions

### System Startup

1. **Serengeti** initializes all components
2. **Network** discovers other instances
3. **Storage** loads data from disk
4. **Index** builds indexes from loaded data
5. **StorageScheduler** starts background thread
6. **Server** starts listening for client connections

```java
// Example of system startup interaction
Serengeti.main(args);
// Initialize components
Network.init();
Storage.init();
IndexManager.init();
QueryEngine.init();
StorageScheduler.init();
Server.init();
// Start server
Server.start();
```

### System Shutdown

1. **Serengeti** initiates shutdown
2. **Server** stops accepting new connections
3. **StorageScheduler** performs final persistence
4. **Network** notifies other instances
5. **Components** release resources

```java
// Example of system shutdown interaction
// Shutdown hook triggered
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    // Graceful shutdown
    Server.stop();
    StorageScheduler.performFinalPersist();
    Network.notifyShutdown();
    // Release resources
    Storage.close();
    IndexManager.close();
    QueryEngine.close();
}));
```

## Error Handling Interactions

### Storage Errors

- **StorageScheduler** implements retry logic for transient errors
- **StorageScheduler** notifies **Serengeti** of persistent errors
- **Network** may initiate data recovery from other nodes

```java
// Example of error handling interaction
try {
    StorageScheduler.performPersistToDisk();
} catch (IOException e) {
    if (StorageScheduler.isTransientError(e)) {
        // Retry operation
        StorageScheduler.retry();
    } else {
        // Handle persistent error
        Serengeti.handlePersistentError(e);
        // Attempt recovery from network
        Network.recoverData();
    }
}
```

### Network Errors

- **Network** detects node failures
- **Network** initiates data redistribution
- **Storage** and **StorageReshuffle** handle data movement

```java
// Example of network error handling
// Network detects node failure
Network.handleNodeFailure(failedNode);
// Initiate data redistribution
List<DatabaseObject> objectsToRedistribute = Storage.getObjectsForNode(failedNode);
StorageReshuffle.redistributeObjects(objectsToRedistribute);
```

## Transaction Flow

1. **Client** sends transaction request to **Server**
2. **Server** forwards to **Query Engine**
3. **Query Engine** coordinates with **Storage**
4. **Storage** makes changes
5. **StorageScheduler** eventually persists changes
6. **Network** replicates changes to other nodes

```java
// Example of transaction flow
// Client sends transaction
String transaction = "BEGIN; UPDATE users SET status='active' WHERE id=123; COMMIT;";
// Server forwards to Query Engine
QueryResponseObject result = QueryEngine.executeTransaction(transaction);
// Storage makes changes
// StorageScheduler eventually persists changes
// Network replicates changes
Network.replicateChanges(changes, nodes);
```

## Conclusion

The Serengeti system's components interact in a coordinated manner to provide a distributed, autonomous database system. These interactions enable:

1. **Automatic Node Discovery**: Components work together to discover and integrate new nodes
2. **Data Replication**: Components ensure data is replicated across nodes for fault tolerance
3. **Query Processing**: Components collaborate to process and execute queries efficiently
4. **Fault Tolerance**: Components detect and recover from failures
5. **Persistence**: Components ensure data durability through periodic persistence

Understanding these component interactions is essential for developing, debugging, and extending the Serengeti system.