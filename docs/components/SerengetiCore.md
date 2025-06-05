# Serengeti Core

This document provides an overview of the Serengeti Core component, which serves as the main entry point and coordinator for the Serengeti distributed database system.

## Overview

The Serengeti Core component is responsible for initializing and coordinating all other components of the system. It serves as the central orchestrator that brings together storage, indexing, querying, networking, and server capabilities to create a cohesive distributed database system.

## Key Components

### 1. Serengeti

The `Serengeti` class is the main entry point for the entire system. It:

- Initializes all system components
- Manages component lifecycle
- Coordinates interactions between components
- Handles system startup and shutdown
- Provides access to core system functionality

```java
// Example of Serengeti usage as the main entry point
public static void main(String[] args) {
    Serengeti serengeti = new Serengeti();
    serengeti.initialize();
    serengeti.start();
}
```

### 2. ShutdownHandler

The `ShutdownHandler` component ensures graceful system shutdown:

- Registers shutdown hooks with the JVM
- Coordinates orderly component shutdown
- Ensures data is properly persisted before exit
- Releases system resources

```java
// Example of ShutdownHandler usage
ShutdownHandler shutdownHandler = new ShutdownHandler();
shutdownHandler.registerShutdownHook();
```

### 3. Globals

The `Globals` class provides system-wide constants and utilities:

- Configuration parameters
- System paths
- Default values
- Utility methods

```java
// Example of Globals usage
String dataPath = Globals.DATA_PATH;
int defaultPort = Globals.DEFAULT_PORT;
```

## System Initialization

The initialization process follows these steps:

### 1. Pre-initialization

- Parse command-line arguments
- Set up logging
- Load configuration
- Create necessary directories

```java
// Pre-initialization
Serengeti.parseArguments(args);
Serengeti.setupLogging();
Serengeti.loadConfiguration();
Serengeti.createDirectories();
```

### 2. Component Initialization

Components are initialized in the following order:

1. **Storage**: Initialize the storage system
2. **IndexManager**: Set up the indexing system
3. **Network**: Initialize network communication
4. **QueryEngine**: Prepare the query engine
5. **Server**: Set up the server component

```java
// Component initialization
Storage.init();
IndexManager.init();
Network.init();
QueryEngine.init();
Server.init();
```

### 3. Post-initialization

- Register shutdown hooks
- Start background threads
- Log system startup
- Announce presence on the network

```java
// Post-initialization
ShutdownHandler.registerShutdownHook();
StorageScheduler.start();
Logger.info("Serengeti started successfully");
Network.announcePresence();
```

## Component Coordination

The Serengeti Core coordinates interactions between components:

### Storage and Indexing

- Ensures indexes are updated when data changes
- Coordinates index usage during queries
- Manages index persistence

```java
// Example of storage-indexing coordination
Storage.registerDataChangeListener(change -> {
    IndexManager.updateIndexes(change);
});
```

### Query and Storage

- Routes queries to appropriate storage components
- Manages transaction boundaries
- Coordinates distributed queries

```java
// Example of query-storage coordination
QueryEngine.registerStorageProvider(Storage::getData);
```

### Network and Storage

- Coordinates data replication across nodes
- Manages distributed storage operations
- Handles node join/leave events

```java
// Example of network-storage coordination
Network.registerNodeJoinHandler(node -> {
    StorageReshuffle.handleNodeJoin(node);
});
```

### Server and Query

- Routes client requests to the query engine
- Returns query results to clients
- Manages query timeouts and cancellation

```java
// Example of server-query coordination
Server.registerQueryHandler(query -> {
    return QueryEngine.executeQuery(query);
});
```

## System Lifecycle Management

### Startup Sequence

The complete startup sequence:

1. Load configuration and parse arguments
2. Set up logging and create directories
3. Initialize storage system
4. Initialize indexing system
5. Initialize network communication
6. Initialize query engine
7. Start background threads (StorageScheduler)
8. Start server component
9. Announce presence on network

```java
// Startup sequence
Serengeti serengeti = new Serengeti();
serengeti.initialize();
serengeti.start();
```

### Shutdown Sequence

The shutdown sequence:

1. Stop accepting new connections
2. Complete in-progress operations
3. Perform final data persistence
4. Announce departure from network
5. Stop background threads
6. Release resources
7. Log shutdown completion

```java
// Shutdown sequence
serengeti.shutdown();
```

## Configuration Management

The Serengeti Core manages system configuration:

### Configuration Sources

- Default values in code
- Configuration files
- Command-line arguments
- Environment variables

### Configuration Parameters

| Parameter | Description | Default Value |
|-----------|-------------|---------------|
| `dataPath` | Path for data storage | ./data |
| `httpPort` | HTTP server port | 1985 |
| `discoveryPort` | Network discovery port | 1986 |
| `persistenceIntervalMs` | Storage persistence interval | 60000 (1 minute) |
| `replicationFactor` | Number of data replicas | 3 |
| `logLevel` | Logging level | INFO |

### Configuration Access

Components can access configuration through the Serengeti core:

```java
// Example of configuration access
int httpPort = Serengeti.getConfig("httpPort", 1985);
String dataPath = Serengeti.getConfig("dataPath", "./data");
```

## Error Handling

The Serengeti Core implements system-wide error handling:

### Error Categories

- **Component Initialization Errors**: Errors during component startup
- **Runtime Errors**: Errors during normal operation
- **Resource Errors**: Issues with system resources
- **External Errors**: Problems with external dependencies

### Error Handling Strategies

- **Retry Logic**: Automatic retry for transient errors
- **Graceful Degradation**: Continue with reduced functionality
- **Logging**: Comprehensive error logging
- **Notification**: Alert mechanisms for critical errors

```java
// Example of error handling
try {
    component.initialize();
} catch (TransientException e) {
    // Retry logic
    Serengeti.retry(() -> component.initialize(), 3);
} catch (CriticalException e) {
    // Critical error handling
    Serengeti.handleCriticalError(e);
}
```

## System Monitoring

The Serengeti Core provides system monitoring capabilities:

### Health Checks

- Component status monitoring
- Resource usage tracking
- Performance metrics
- Error rate monitoring

```java
// Example of health check
SystemHealth health = Serengeti.checkHealth();
if (!health.isHealthy()) {
    Logger.warn("System health check failed: " + health.getIssues());
}
```

### Performance Metrics

- Query throughput
- Storage operations
- Network activity
- Resource utilization

```java
// Example of performance metrics
SystemMetrics metrics = Serengeti.getMetrics();
double queryThroughput = metrics.getQueryThroughput();
double storageOperations = metrics.getStorageOperations();
```

### Logging

The Serengeti Core manages system-wide logging:

- Component-specific logging
- Error logging
- Performance logging
- Audit logging

```java
// Example of logging
Logger.info("System initialized successfully");
Logger.error("Failed to start component", exception);
```

## Integration with External Systems

### Prometheus Integration

The Serengeti Core provides Prometheus integration for monitoring:

- Exposes metrics endpoint
- Defines system metrics
- Supports alerting

```java
// Example of Prometheus metrics
PrometheusMetrics.register("query_throughput", queryThroughput);
PrometheusMetrics.register("storage_operations", storageOperations);
```

### JMX Integration

The Serengeti Core exposes JMX beans for management:

- Component status
- Configuration parameters
- Performance metrics
- Management operations

```java
// Example of JMX integration
SerengetiMBean mbean = new SerengetiMBean();
MBeanServer server = ManagementFactory.getPlatformMBeanServer();
server.registerMBean(mbean, new ObjectName("com.ataiva.serengeti:type=Serengeti"));
```

## Command-Line Interface

The Serengeti Core provides a command-line interface:

### Command-Line Arguments

- `--port=<port>`: Set HTTP server port
- `--data-path=<path>`: Set data directory
- `--log-level=<level>`: Set logging level
- `--config=<file>`: Specify configuration file

```
java -jar serengeti.jar --port=1985 --data-path=/var/data/serengeti --log-level=INFO
```

### Interactive Mode

The system can be started in interactive mode for direct interaction:

```
java -jar serengeti.jar --interactive
```

## System Requirements

The Serengeti Core has the following requirements:

- Java 11 or higher
- Minimum 2GB RAM (4GB recommended)
- 1GB disk space for installation
- Additional disk space for data storage
- Network connectivity (same subnet for all nodes)

## Best Practices

1. **Resource Allocation**: Allocate sufficient memory and disk space
2. **Regular Backups**: Implement regular data backups
3. **Monitoring**: Set up monitoring for system health
4. **Logging**: Configure appropriate log levels
5. **Network Configuration**: Ensure proper network setup for node discovery

## Troubleshooting

### Common Issues

| Issue | Possible Causes | Solutions |
|-------|----------------|-----------|
| System fails to start | Missing dependencies, invalid configuration | Check logs, verify configuration |
| Components fail to initialize | Resource issues, conflicts | Check component-specific logs |
| High memory usage | Large datasets, memory leaks | Increase heap size, check for leaks |
| Slow performance | Resource constraints, inefficient queries | Optimize queries, increase resources |

### Diagnostic Tools

- Log analysis
- JVM profiling
- System metrics
- Health checks

```java
// Example of diagnostics
Serengeti.runDiagnostics();
DiagnosticReport report = Serengeti.getDiagnosticReport();
```

## Future Enhancements

1. **Dynamic Configuration**: Runtime configuration changes
2. **Component Hot-swapping**: Replace components without restart
3. **Enhanced Monitoring**: More detailed system metrics
4. **Cluster Management**: Advanced cluster management features
5. **Resource Governance**: Better resource allocation and limits

## Conclusion

The Serengeti Core component serves as the central coordinator for the Serengeti distributed database system. It brings together all the specialized components to create a cohesive, autonomous distributed database that requires minimal configuration and management.