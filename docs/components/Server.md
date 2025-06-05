# Serengeti Server Component

This document provides an overview of the Server component in the Serengeti distributed database system.

## Overview

The Server component is the interface between clients and the Serengeti database system. It handles client connections, processes requests, serves the web-based user interfaces, and routes operations to the appropriate internal components. The Server component is essential for making the distributed database accessible and usable.

## Key Components

### 1. Server

The `Server` class is the main entry point for the server functionality. It:

- Listens for and accepts client connections
- Processes HTTP requests
- Serves web-based interfaces (Dashboard and Interactive)
- Routes requests to appropriate internal components
- Returns responses to clients

```java
// Example of Server usage
Server server = new Server();
server.start();
server.stop();
```

### 2. ServerConstants

The `ServerConstants` class defines configuration constants for the server:

- Port numbers
- Request timeouts
- Maximum connections
- Resource paths

```java
// Example of ServerConstants usage
int httpPort = ServerConstants.HTTP_PORT; // 1985
int maxConnections = ServerConstants.MAX_CONNECTIONS;
```

### 3. Request Handlers

The Server component includes specialized handlers for different types of requests:

- **DashboardHandler**: Serves the administrative dashboard
- **InteractiveHandler**: Serves the interactive query interface
- **QueryHandler**: Processes query requests
- **APIHandler**: Handles programmatic API requests
- **StaticResourceHandler**: Serves static resources (HTML, CSS, JS)

```java
// Example of request handler registration
server.registerHandler("/dashboard", new DashboardHandler());
server.registerHandler("/interactive", new InteractiveHandler());
server.registerHandler("/api/query", new QueryHandler());
```

## Server Architecture

### HTTP Server

The Server component implements an HTTP server that:

- Listens on port 1985 by default
- Supports HTTP/1.1 protocol
- Handles concurrent connections using a thread pool
- Processes GET, POST, PUT, and DELETE requests

### Request Processing Pipeline

Each client request goes through the following pipeline:

1. **Connection Acceptance**: Server accepts the client connection
2. **Request Parsing**: HTTP request is parsed
3. **Authentication**: (Future enhancement) Request is authenticated
4. **Handler Selection**: Appropriate handler is selected based on the URL
5. **Request Processing**: Handler processes the request
6. **Response Generation**: Response is generated
7. **Response Sending**: Response is sent back to the client

```
Client                                 Server
  |                                      |
  |------ HTTP Request ---------------->|
  |                                      |
  |                                      |--- Parse Request
  |                                      |
  |                                      |--- Select Handler
  |                                      |
  |                                      |--- Process Request
  |                                      |
  |                                      |--- Generate Response
  |                                      |
  |<----- HTTP Response ----------------|
  |                                      |
```

## Web Interfaces

### Dashboard

The Dashboard provides an administrative interface for:

- Monitoring system status
- Viewing node information
- Checking database statistics
- Monitoring performance metrics
- Managing database objects

```
URL: http://<host>:1985/dashboard
```

### Interactive Console

The Interactive Console provides a query interface for:

- Executing SQL queries
- Viewing query results
- Saving and loading queries
- Exploring database schema

```
URL: http://<host>:1985/interactive
```

## API Endpoints

The Server component exposes several API endpoints:

### Query API

```
Endpoint: /api/query
Method: POST
Content-Type: application/json
Body: {"query": "SELECT * FROM users"}
```

Example response:
```json
{
  "success": true,
  "results": [
    {"id": 1, "name": "John Doe"},
    {"id": 2, "name": "Jane Smith"}
  ],
  "executionTime": 15,
  "rowCount": 2
}
```

### Status API

```
Endpoint: /api/status
Method: GET
```

Example response:
```json
{
  "status": "online",
  "uptime": 3600,
  "nodes": 3,
  "databases": 2,
  "version": "1.0.0"
}
```

### Schema API

```
Endpoint: /api/schema
Method: GET
```

Example response:
```json
{
  "databases": [
    {
      "name": "users_db",
      "tables": [
        {
          "name": "users",
          "columns": ["id", "name", "email"]
        }
      ]
    }
  ]
}
```

## Integration with Other Components

### Query Engine Integration

The Server component forwards query requests to the Query Engine:

```java
// Example of Server-QueryEngine integration
String query = request.getParameter("query");
QueryResponseObject result = QueryEngine.executeQuery(query);
response.write(result.toJson());
```

### Storage Integration

The Server component interacts with the Storage system for:

- Schema information
- Database object management
- Status information

```java
// Example of Server-Storage integration
List<DatabaseObject> databases = Storage.getDatabases();
response.write(convertToJson(databases));
```

### Network Integration

The Server component works with the Network component to:

- Obtain node information
- Route requests in a distributed environment
- Aggregate results from multiple nodes

```java
// Example of Server-Network integration
List<Node> nodes = Network.getActiveNodes();
response.write(convertToJson(nodes));
```

## Security Considerations

### Current Implementation

The current Server implementation has basic security features:

- Request validation
- Error handling to prevent information leakage
- Resource limits to prevent DoS attacks

### Future Enhancements

Planned security enhancements include:

- Authentication and authorization
- HTTPS support
- Rate limiting
- Input sanitization
- CSRF protection

## Performance Considerations

### Connection Pooling

The Server uses connection pooling to efficiently handle multiple client connections:

- Maintains a pool of worker threads
- Reuses connections when possible
- Implements connection timeouts
- Monitors connection usage

### Request Throttling

To prevent overload, the Server implements request throttling:

- Limits concurrent requests per client
- Implements backpressure mechanisms
- Prioritizes certain request types
- Rejects requests when overloaded

### Caching

The Server implements caching for improved performance:

- Caches static resources
- Caches frequently accessed data
- Implements cache invalidation
- Uses ETags for conditional requests

## Configuration

The Server component can be configured through the following parameters:

| Parameter | Description | Default Value |
|-----------|-------------|---------------|
| `httpPort` | HTTP server port | 1985 |
| `maxConnections` | Maximum concurrent connections | 100 |
| `requestTimeoutMs` | Request timeout in milliseconds | 30000 (30 seconds) |
| `threadPoolSize` | Size of the worker thread pool | 20 |
| `maxRequestSize` | Maximum request size in bytes | 1048576 (1 MB) |

These parameters can be set in the `ServerConstants` class or through system properties.

## Error Handling

### HTTP Status Codes

The Server uses standard HTTP status codes:

- 200 OK: Successful request
- 400 Bad Request: Invalid request parameters
- 404 Not Found: Resource not found
- 500 Internal Server Error: Server-side error

### Error Responses

Error responses include detailed information:

```json
{
  "success": false,
  "error": {
    "code": "QUERY_SYNTAX_ERROR",
    "message": "Syntax error in SQL query",
    "details": "Unexpected token at position 15"
  }
}
```

### Error Logging

The Server logs errors for troubleshooting:

- Error type and message
- Request information
- Stack traces for internal errors
- Client information

## Monitoring and Metrics

The Server collects various metrics:

- Request count and rate
- Response times
- Error rates
- Active connections
- Resource usage

These metrics can be accessed through the Dashboard or programmatically:

```java
// Example of accessing server metrics
ServerMetrics metrics = Server.getMetrics();
int requestCount = metrics.getRequestCount();
double avgResponseTime = metrics.getAverageResponseTime();
```

## Common Issues and Solutions

| Issue | Possible Causes | Solutions |
|-------|----------------|-----------|
| Connection refused | Server not running, wrong port | Verify server is running, check port configuration |
| Slow response times | Overloaded server, complex queries | Optimize queries, increase server resources |
| "Too many connections" error | Connection limit reached | Increase maxConnections, implement connection pooling in client |
| Out of memory errors | Large result sets, memory leaks | Limit result sizes, check for memory leaks |

## Best Practices

1. **Connection Management**: Properly close connections when done
2. **Request Sizing**: Keep request sizes reasonable
3. **Error Handling**: Implement proper error handling in clients
4. **Monitoring**: Regularly monitor server metrics
5. **Load Testing**: Test with expected and peak loads

## Startup and Shutdown

### Server Startup

The server startup process:

1. Initialize server configuration
2. Create thread pool
3. Register request handlers
4. Open server socket
5. Begin accepting connections

```java
// Server startup sequence
Server server = new Server();
server.initialize();
server.registerHandlers();
server.start();
```

### Server Shutdown

The server shutdown process:

1. Stop accepting new connections
2. Complete in-progress requests
3. Close all connections
4. Release resources
5. Shutdown thread pool

```java
// Server shutdown sequence
server.stopAcceptingConnections();
server.waitForRequestsToComplete(timeoutMs);
server.closeAllConnections();
server.releaseResources();
server.shutdown();
```

## Future Enhancements

1. **HTTPS Support**: Add support for encrypted connections
2. **WebSocket Support**: Enable real-time communication
3. **Authentication**: Implement user authentication
4. **API Versioning**: Support for versioned APIs
5. **Request Routing**: More sophisticated request routing in distributed environments
6. **Compression**: Support for response compression
7. **Cross-Origin Resource Sharing (CORS)**: Better support for cross-origin requests

## Conclusion

The Server component is the gateway to the Serengeti distributed database system, providing both user interfaces and programmatic APIs. Its design balances performance, security, and usability to make the database system accessible while maintaining reliability and efficiency.