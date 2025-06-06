# NetworkImpl Component

## Overview

The NetworkImpl class is a robust implementation of the Network component for the Serengeti distributed database system. It handles node discovery, message passing, and failure detection in a distributed environment. This implementation replaces the mock implementation with a production-ready solution that provides better reliability, performance, and error handling.

## Key Features

- **Robust Node Discovery**: Scans the network to find other Serengeti nodes using parallel processing for efficiency
- **Heartbeat Mechanism**: Regularly sends heartbeats to detect node failures and maintain an up-to-date view of the network
- **Connection Management**: Efficiently manages HTTP connections to reduce overhead
- **Thread Pool Management**: Uses dedicated thread pools for network operations and heartbeat processing
- **Error Handling**: Comprehensive error handling with appropriate logging
- **Graceful Shutdown**: Properly cleans up resources during shutdown

## Architecture

The NetworkImpl class extends the base Network class and implements all required functionality with improved robustness and performance. It uses the following components:

1. **Thread Pools**:
   - `networkExecutor`: A cached thread pool for network operations
   - `heartbeatScheduler`: A scheduled thread pool for heartbeat operations

2. **Data Structures**:
   - `nodeRegistry`: A concurrent hash map for storing node information
   - `availableNodes`: A synchronized map for tracking available nodes

3. **Network Operations**:
   - Node discovery
   - Heartbeat sending and processing
   - Node timeout detection
   - Network metadata synchronization

## Usage

### Creating a NetworkImpl Instance

The NetworkImpl class can be instantiated directly or through the NetworkFactory:

```java
// Direct instantiation with default configuration
NetworkImpl network = new NetworkImpl();

// Direct instantiation with custom configuration
NetworkImpl network = new NetworkImpl(
    8080,   // communicationPort
    1986,   // discoveryPort
    5000,   // heartbeatIntervalMs
    15000,  // nodeTimeoutMs
    3000,   // discoveryTimeoutMs
    3       // maxRetransmissions
);

// Using the NetworkFactory (recommended)
Network network = NetworkFactory.createNetwork(NetworkFactory.NetworkType.REAL);
```

### Initialization

After creating a NetworkImpl instance, it must be initialized:

```java
network.init();
```

This will:
1. Determine the local IP address
2. Start the node discovery process
3. Start the heartbeat scheduler

### Node Discovery

Node discovery happens automatically after initialization. The NetworkImpl class will:

1. Scan the network for other Serengeti nodes
2. Update the node registry with discovered nodes
3. Remove nodes that are no longer available
4. Request network metadata from other nodes

### Communication

The NetworkImpl class provides methods for communicating with other nodes:

```java
// Communicate with a single node
String response = network.communicateQueryLogSingleNode(nodeId, nodeIp, jsonString);

// Communicate with all nodes
JSONArray responses = network.communicateQueryLogAllNodes(jsonString);
```

### Shutdown

When the application is shutting down, the NetworkImpl should be properly shut down:

```java
network.shutdown();
```

This will:
1. Stop the node discovery process
2. Shutdown the thread pools
3. Release all resources

## Configuration Parameters

- **communicationPort**: Port used for HTTP communication (default: Globals.port_default)
- **discoveryPort**: Port used for node discovery (default: 1986)
- **heartbeatIntervalMs**: Interval between heartbeat messages (default: 5000 ms)
- **nodeTimeoutMs**: Timeout after which a node is considered failed (default: 15000 ms)
- **discoveryTimeoutMs**: Timeout for discovery operations (default: 3000 ms)
- **maxRetransmissions**: Maximum number of message retransmissions (default: 3)

## Error Handling

The NetworkImpl class handles various error scenarios:

1. **Connection Errors**: When a node is unreachable, the connection error is logged and the operation fails gracefully
2. **Timeout Errors**: When a node doesn't respond within the timeout period, the operation is aborted
3. **Parse Errors**: When received data cannot be parsed, the error is logged and the operation fails gracefully
4. **Node Failures**: When a node fails to respond to heartbeats, it is removed from the available nodes list

## Logging

The NetworkImpl class uses Java's built-in logging framework to log important events and errors. Log levels are:

- **INFO**: Important events like node discovery, initialization, and shutdown
- **WARNING**: Non-critical errors that don't affect overall operation
- **SEVERE**: Critical errors that may affect system operation
- **FINE**: Detailed information for debugging

## Integration with Other Components

The NetworkImpl class integrates with other Serengeti components:

1. **Server**: The NetworkImpl notifies the Server when it's ready to serve requests
2. **StorageReshuffle**: The NetworkImpl notifies StorageReshuffle when a node is lost
3. **Storage**: The NetworkImpl helps synchronize database and table information across nodes

## Performance Considerations

The NetworkImpl class is designed for performance:

1. **Parallel Processing**: Uses thread pools for parallel operations
2. **Connection Reuse**: Minimizes connection overhead
3. **Efficient Node Discovery**: Uses optimized network scanning
4. **Heartbeat Optimization**: Balances responsiveness with network overhead

## Testing

The NetworkImpl class has comprehensive unit and integration tests:

1. **Unit Tests**: Test individual methods in isolation
2. **Integration Tests**: Test integration with other components
3. **Mock Tests**: Test behavior with mock network connections

## Future Improvements

Potential future improvements for the NetworkImpl class:

1. **Dynamic Configuration**: Allow configuration changes at runtime
2. **Advanced Failure Detection**: Implement more sophisticated failure detection algorithms
3. **Network Topology Awareness**: Optimize communication based on network topology
4. **Security Enhancements**: Add authentication and encryption
5. **Performance Metrics**: Collect and expose performance metrics