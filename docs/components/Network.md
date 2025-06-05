# Serengeti Network Component

This document provides an overview of the Network component in the Serengeti distributed database system.

## Overview

The Network component is responsible for all communication between Serengeti nodes in the distributed database system. It enables node discovery, data replication, distributed query execution, and failure detection. The Network component is essential for Serengeti's autonomous distributed nature, allowing instances to automatically connect and form a cohesive database system.

## Key Components

### 1. Network

The `Network` class is the main entry point for network functionality. It:

- Discovers other Serengeti instances on the subnet
- Establishes and maintains connections between nodes
- Handles message passing between nodes
- Detects node failures and initiates recovery
- Coordinates distributed operations

```java
// Example of Network usage
List<Node> nodes = Network.discoverNodes();
Network.sendMessage(targetNode, message);
Network.broadcastMessage(message);
```

### 2. Node Discovery

The node discovery mechanism allows Serengeti instances to find each other on the network:

- Uses UDP broadcast/multicast for initial discovery
- Maintains a membership list of active nodes
- Periodically verifies node availability
- Handles new node announcements

```java
// Example of node discovery
Network.startDiscovery();
List<Node> activeNodes = Network.getActiveNodes();
```

### 3. Message Passing

The message passing system enables communication between nodes:

- Supports synchronous and asynchronous messages
- Implements reliable delivery with acknowledgments
- Handles message serialization and deserialization
- Provides message prioritization

```java
// Example of message passing
Message message = new Message(MessageType.REPLICATE_DATA, payload);
Network.sendMessage(targetNode, message);

// Asynchronous message with callback
Network.sendMessageAsync(targetNode, message, response -> {
    // Handle response
});
```

### 4. Failure Detection

The failure detection mechanism identifies when nodes become unavailable:

- Uses heartbeat messages to monitor node health
- Implements an adaptive failure detector
- Distinguishes between transient and permanent failures
- Initiates recovery procedures when failures are detected

```java
// Example of failure detection
boolean isNodeAlive = Network.isNodeAlive(node);
Network.registerFailureListener(node, event -> {
    // Handle node failure
});
```

## Network Protocol

### Message Types

The Network component supports various message types:

| Message Type | Description | Priority |
|--------------|-------------|----------|
| DISCOVERY | Node discovery and announcement | High |
| HEARTBEAT | Node health monitoring | High |
| QUERY | Distributed query execution | Medium |
| REPLICATE | Data replication | Medium |
| RESHUFFLE | Data redistribution | Low |
| METADATA | Metadata synchronization | Medium |
| RECOVERY | Failure recovery | High |

### Message Format

Each network message contains:

- Message ID: Unique identifier for the message
- Source Node: The node that sent the message
- Destination Node: The intended recipient (or broadcast)
- Message Type: The type of message
- Timestamp: When the message was created
- Payload: The message content
- Checksum: For message integrity verification

### Communication Patterns

The Network component implements several communication patterns:

#### Request-Response

Used for operations that require a response:

```
Node A                    Node B
  |                         |
  |------- Request -------->|
  |                         |
  |<------ Response --------|
  |                         |
```

#### One-way Notification

Used for informational messages that don't require a response:

```
Node A                    Node B
  |                         |
  |------ Notification ---->|
  |                         |
```

#### Broadcast

Used to send a message to all nodes:

```
Node A                    All Nodes
  |                         |
  |------- Broadcast ------>|
  |                         |
```

#### Gossip

Used for disseminating information gradually through the network:

```
Node A          Node B          Node C
  |               |               |
  |--- Gossip --->|               |
  |               |--- Gossip --->|
  |               |               |
```

## Distributed Operations

### Distributed Query Execution

The Network component supports distributed query execution:

- Routes query fragments to appropriate nodes
- Transfers intermediate results between nodes
- Aggregates results from multiple nodes
- Handles failures during query execution

```java
// Example of distributed query execution
DistributedQueryPlan plan = QueryEngine.createDistributedPlan(query);
Network.executeDistributedQuery(plan, results -> {
    // Process aggregated results
});
```

### Data Replication

The Network component handles data replication across nodes:

- Synchronizes data changes to replica nodes
- Ensures consistency between replicas
- Handles conflict resolution
- Manages replication acknowledgments

```java
// Example of data replication
ReplicationRequest request = new ReplicationRequest(tableData, replicaNodes);
Network.replicateData(request, status -> {
    // Handle replication status
});
```

### Data Redistribution

When nodes join or leave, the Network component coordinates data redistribution:

- Calculates new data placement
- Transfers data between nodes
- Updates routing information
- Verifies successful redistribution

```java
// Example of data redistribution
RedistributionPlan plan = StorageReshuffle.createPlan(oldNodes, newNodes);
Network.executeRedistribution(plan, status -> {
    // Handle redistribution status
});
```

## Network Topology

### Peer-to-Peer Architecture

Serengeti uses a peer-to-peer network architecture:

- All nodes are equal (no master/slave)
- Any node can initiate operations
- Direct communication between any two nodes
- Resilient to individual node failures

### Consistent Hashing

The network uses consistent hashing for:

- Determining data placement
- Routing queries to appropriate nodes
- Minimizing data movement when topology changes

```java
// Example of consistent hashing for routing
Node responsibleNode = Network.getNodeForKey(key);
Network.routeRequestToNode(responsibleNode, request);
```

## Network Configuration

The Network component can be configured through the following parameters:

| Parameter | Description | Default Value |
|-----------|-------------|---------------|
| `discoveryPort` | Port used for node discovery | 1986 |
| `communicationPort` | Port used for node communication | 1987 |
| `heartbeatIntervalMs` | Time between heartbeat messages | 1000 (1 second) |
| `nodeTimeoutMs` | Time after which a node is considered failed | 5000 (5 seconds) |
| `discoveryTimeoutMs` | Timeout for discovery operations | 3000 (3 seconds) |
| `maxRetransmissions` | Maximum message retransmission attempts | 3 |

## Error Handling

### Network Partitions

The Network component handles network partitions:

- Detects when parts of the network become isolated
- Implements partition tolerance strategies
- Reconciles state when partitions heal
- Prevents "split-brain" scenarios

### Message Failures

For message delivery failures:

- Implements automatic retransmission
- Uses exponential backoff for retries
- Notifies higher-level components of persistent failures
- Provides delivery guarantees (at-least-once, at-most-once)

### Node Failures

When node failures are detected:

- Updates the membership list
- Notifies affected components
- Initiates data recovery if needed
- Adjusts routing information

## Security Considerations

### Current Implementation

The current Network implementation relies on:

- Network isolation (same subnet)
- Implicit trust between nodes
- No encryption or authentication

### Future Enhancements

Planned security enhancements include:

- Node authentication
- Encrypted communication
- Access control for operations
- Secure node discovery

## Performance Considerations

### Bandwidth Usage

The Network component optimizes bandwidth usage:

- Compresses messages when beneficial
- Batches small messages when possible
- Prioritizes messages based on importance
- Implements flow control to prevent overload

### Latency Optimization

To minimize latency:

- Uses connection pooling
- Implements request pipelining
- Prioritizes time-sensitive messages
- Adapts to network conditions

## Monitoring and Troubleshooting

### Network Metrics

The Network component collects various metrics:

- Message throughput (messages/second)
- Bandwidth usage (bytes/second)
- Message latency (milliseconds)
- Failure rates and types
- Node connectivity status

```java
// Example of accessing network metrics
NetworkMetrics metrics = Network.getMetrics();
double messageLatency = metrics.getAverageLatency();
int activeConnections = metrics.getActiveConnectionCount();
```

### Common Issues and Solutions

| Issue | Possible Causes | Solutions |
|-------|----------------|-----------|
| Node discovery failure | Firewall blocking UDP, network configuration | Check firewall settings, verify subnet configuration |
| High message latency | Network congestion, overloaded nodes | Optimize message size, check network infrastructure |
| Connection failures | Node crashes, network issues | Implement proper error handling, check network stability |
| Data inconsistency | Replication failures, network partitions | Verify replication status, implement consistency checks |

## Integration with Other Components

### Storage Integration

The Network component interacts with the Storage system to:
- Replicate data changes across nodes
- Retrieve data for distributed queries
- Coordinate data redistribution

### Query Engine Integration

The Network component works with the Query Engine to:
- Execute distributed queries
- Transfer query results between nodes
- Optimize query routing

### Server Integration

The Network component interacts with the Server to:
- Provide node status information
- Route client requests to appropriate nodes
- Aggregate responses from multiple nodes

## Best Practices

1. **Network Configuration**: Ensure all nodes are on the same subnet
2. **Resource Allocation**: Allocate sufficient bandwidth and CPU for network operations
3. **Error Handling**: Implement proper error handling for network failures
4. **Monitoring**: Regularly monitor network metrics and performance
5. **Testing**: Test with various network conditions and failure scenarios

## Future Enhancements

1. **Multi-region Support**: Extend beyond single subnet to support multi-region deployments
2. **Enhanced Security**: Add authentication, authorization, and encryption
3. **Advanced Routing**: Implement more sophisticated routing strategies
4. **Network Optimization**: Further optimize for various network conditions
5. **Cross-subnet Discovery**: Support for node discovery across different subnets

## Conclusion

The Network component is a fundamental part of the Serengeti distributed database system, enabling autonomous operation across multiple nodes. Its capabilities for node discovery, message passing, failure detection, and distributed operations are essential for the system's distributed nature and fault tolerance.