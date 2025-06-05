# Serengeti Troubleshooting Guide

This guide provides solutions for common issues you might encounter when using the Serengeti distributed database system.

## Table of Contents

- [Startup Issues](#startup-issues)
- [Connection Issues](#connection-issues)
- [Node Discovery Issues](#node-discovery-issues)
- [Query Issues](#query-issues)
- [Performance Issues](#performance-issues)
- [Data Issues](#data-issues)
- [Memory Issues](#memory-issues)
- [Disk Space Issues](#disk-space-issues)
- [Network Issues](#network-issues)
- [Dashboard Issues](#dashboard-issues)
- [Diagnostic Tools](#diagnostic-tools)
- [Getting Additional Help](#getting-additional-help)

## Startup Issues

### Serengeti Fails to Start

**Symptoms:**
- Java process terminates immediately after starting
- Error messages in console or log files
- No response on port 1985

**Possible Causes and Solutions:**

1. **Java Version Incompatibility**
   - **Cause**: Using an unsupported Java version
   - **Solution**: Ensure you're using JDK 11 or higher
   - **Verification**: Check Java version with `java -version`

2. **Port Already in Use**
   - **Cause**: Another process is using port 1985 or 1986
   - **Solution**: Stop the conflicting process or use different ports
   - **Verification**: Check for processes using these ports with:
     ```
     # On Linux/macOS
     netstat -tuln | grep '1985\|1986'
     
     # On Windows
     netstat -ano | findstr "1985 1986"
     ```

3. **Insufficient Permissions**
   - **Cause**: Lack of permissions to create files or bind to ports
   - **Solution**: Run with appropriate permissions or change data directory
   - **Verification**: Try running with elevated privileges or specify a different data path:
     ```
     java -jar serengeti.jar --data-path=/path/with/write/permissions
     ```

4. **Corrupted JAR File**
   - **Cause**: The JAR file is corrupted or incomplete
   - **Solution**: Re-download or rebuild the JAR file
   - **Verification**: Check JAR integrity with:
     ```
     jar tf serengeti.jar > /dev/null
     ```

5. **Insufficient Memory**
   - **Cause**: Not enough memory allocated to JVM
   - **Solution**: Increase heap size
   - **Verification**: Start with explicit memory settings:
     ```
     java -Xmx2g -jar serengeti.jar
     ```

### Error in Log Files

Check the log files for specific error messages:

```
# Log file location
./logs/serengeti.log
```

Common error messages and solutions:

| Error Message | Possible Cause | Solution |
|---------------|----------------|----------|
| `java.lang.OutOfMemoryError: Java heap space` | Insufficient heap memory | Increase heap size with `-Xmx` option |
| `java.net.BindException: Address already in use` | Port already in use | Use different port or stop conflicting process |
| `java.io.IOException: Permission denied` | Insufficient file permissions | Run with appropriate permissions or change data directory |
| `java.lang.NoClassDefFoundError` | Missing dependencies | Ensure all dependencies are included in classpath |
| `java.lang.UnsupportedClassVersionError` | Java version too old | Upgrade to JDK 11 or higher |

## Connection Issues

### Cannot Connect to Serengeti

**Symptoms:**
- Cannot access dashboard or interactive console
- Connection refused errors
- Timeouts when trying to connect

**Possible Causes and Solutions:**

1. **Serengeti Not Running**
   - **Cause**: The Serengeti process is not running
   - **Solution**: Start the Serengeti process
   - **Verification**: Check if process is running:
     ```
     # On Linux/macOS
     ps aux | grep serengeti
     
     # On Windows
     tasklist | findstr java
     ```

2. **Firewall Blocking Connection**
   - **Cause**: Firewall rules blocking access to ports 1985/1986
   - **Solution**: Add firewall exceptions for these ports
   - **Verification**: Try connecting from the same machine using localhost:
     ```
     curl http://localhost:1985/api/status
     ```

3. **Incorrect Hostname or IP**
   - **Cause**: Using wrong hostname or IP to connect
   - **Solution**: Verify and use correct hostname or IP
   - **Verification**: Ping the host to verify connectivity:
     ```
     ping hostname
     ```

4. **Server Listening on Different Interface**
   - **Cause**: Server bound to specific network interface
   - **Solution**: Configure server to listen on all interfaces
   - **Verification**: Check listening interfaces:
     ```
     # On Linux/macOS
     netstat -tuln | grep 1985
     
     # On Windows
     netstat -ano | findstr 1985
     ```

## Node Discovery Issues

### Nodes Cannot Discover Each Other

**Symptoms:**
- Nodes show as standalone in dashboard
- Data not replicating across nodes
- Cluster size remains at 1

**Possible Causes and Solutions:**

1. **Different Subnets**
   - **Cause**: Nodes are on different subnets
   - **Solution**: Ensure all nodes are on the same subnet
   - **Verification**: Check IP addresses and subnet masks of all nodes

2. **Multicast/Broadcast Disabled**
   - **Cause**: Network doesn't allow multicast/broadcast
   - **Solution**: Configure network to allow multicast/broadcast or use direct node configuration
   - **Verification**: Test multicast connectivity:
     ```
     # On Linux
     ping -c 4 224.0.0.1
     ```

3. **Firewall Blocking Discovery**
   - **Cause**: Firewall blocking UDP port 1986
   - **Solution**: Add firewall exception for UDP port 1986
   - **Verification**: Temporarily disable firewall to test

4. **Different Discovery Ports**
   - **Cause**: Nodes configured with different discovery ports
   - **Solution**: Ensure all nodes use the same discovery port
   - **Verification**: Check configuration of all nodes

## Query Issues

### Query Returns Error

**Symptoms:**
- Error message when executing query
- Query doesn't complete
- Unexpected query results

**Possible Causes and Solutions:**

1. **Syntax Error**
   - **Cause**: Incorrect SQL syntax
   - **Solution**: Check and correct the SQL syntax
   - **Verification**: Refer to the [Basic Operations](BasicOperations.md) guide for correct syntax

2. **Missing Database or Table**
   - **Cause**: Referenced database or table doesn't exist
   - **Solution**: Create the database/table or use existing ones
   - **Verification**: Check available databases and tables:
     ```sql
     SHOW DATABASES;
     SHOW TABLES IN database_name;
     ```

3. **Permission Issues**
   - **Cause**: Insufficient permissions (in future versions with authentication)
   - **Solution**: Ensure user has appropriate permissions
   - **Verification**: Check user permissions (if applicable)

4. **Query Timeout**
   - **Cause**: Query takes too long to execute
   - **Solution**: Optimize query, add indexes, or increase timeout
   - **Verification**: Check query execution plan:
     ```sql
     EXPLAIN SELECT * FROM database.table WHERE condition;
     ```

### Slow Query Performance

**Symptoms:**
- Queries take longer than expected to complete
- Dashboard shows high query execution times
- System becomes less responsive during queries

**Possible Causes and Solutions:**

1. **Missing Indexes**
   - **Cause**: No indexes on columns used in WHERE clauses or joins
   - **Solution**: Create appropriate indexes
   - **Verification**: Check existing indexes and create new ones:
     ```sql
     SHOW INDEXES ON database.table;
     CREATE INDEX ON database.table(column);
     ```

2. **Large Result Sets**
   - **Cause**: Query returns too many rows
   - **Solution**: Add LIMIT clause or more specific filters
   - **Verification**: Add LIMIT to test:
     ```sql
     SELECT * FROM database.table WHERE condition LIMIT 100;
     ```

3. **Complex Joins**
   - **Cause**: Query involves many tables or complex joins
   - **Solution**: Simplify query or optimize join conditions
   - **Verification**: Break down complex query into simpler parts

4. **System Resource Constraints**
   - **Cause**: Insufficient memory, CPU, or disk I/O
   - **Solution**: Increase system resources or optimize resource usage
   - **Verification**: Monitor system resources during query execution

## Performance Issues

### General System Slowness

**Symptoms:**
- All operations are slower than normal
- High CPU or memory usage
- Disk I/O bottlenecks

**Possible Causes and Solutions:**

1. **Insufficient Resources**
   - **Cause**: Not enough CPU, memory, or disk I/O
   - **Solution**: Increase system resources or add more nodes
   - **Verification**: Monitor resource usage with system tools

2. **Too Many Concurrent Operations**
   - **Cause**: System overloaded with concurrent requests
   - **Solution**: Implement client-side throttling or connection pooling
   - **Verification**: Reduce concurrent operations and observe performance

3. **Background Processes**
   - **Cause**: Background processes consuming resources (e.g., compaction)
   - **Solution**: Schedule background processes during off-peak hours
   - **Verification**: Check system logs for background activity

4. **Large Data Volume**
   - **Cause**: Data volume exceeding optimal capacity
   - **Solution**: Add more nodes to distribute the load
   - **Verification**: Check data size and distribution across nodes

## Data Issues

### Data Inconsistency

**Symptoms:**
- Different results for the same query on different nodes
- Missing or unexpected data
- Replication issues reported in logs

**Possible Causes and Solutions:**

1. **Replication Failures**
   - **Cause**: Data changes not properly replicated
   - **Solution**: Check network connectivity between nodes
   - **Verification**: Check replication status in dashboard

2. **Node Recovery in Progress**
   - **Cause**: Node recovering after failure
   - **Solution**: Wait for recovery to complete
   - **Verification**: Check node status in dashboard

3. **Split Brain Scenario**
   - **Cause**: Network partition causing nodes to operate independently
   - **Solution**: Resolve network issues and allow nodes to reconcile
   - **Verification**: Check network connectivity between all nodes

### Data Corruption

**Symptoms:**
- Error messages about corrupted data files
- Unexpected query results
- System crashes when accessing certain data

**Possible Causes and Solutions:**

1. **Disk Failures**
   - **Cause**: Physical disk issues causing data corruption
   - **Solution**: Replace faulty hardware and restore from replicas
   - **Verification**: Check disk health with system tools

2. **Improper Shutdown**
   - **Cause**: System not shut down properly
   - **Solution**: Always use proper shutdown procedure
   - **Verification**: Check logs for improper shutdown messages

3. **Software Bugs**
   - **Cause**: Bugs in the software causing data corruption
   - **Solution**: Update to latest version and report the issue
   - **Verification**: Check if issue is reproducible and report it

## Memory Issues

### Out of Memory Errors

**Symptoms:**
- `OutOfMemoryError` in logs
- JVM crashes
- System becomes unresponsive

**Possible Causes and Solutions:**

1. **Insufficient Heap Size**
   - **Cause**: JVM heap size too small for data volume
   - **Solution**: Increase heap size with `-Xmx` option
   - **Verification**: Start with larger heap:
     ```
     java -Xmx4g -jar serengeti.jar
     ```

2. **Memory Leaks**
   - **Cause**: Application not releasing memory properly
   - **Solution**: Update to latest version or report the issue
   - **Verification**: Monitor memory usage over time

3. **Large Query Results**
   - **Cause**: Queries returning very large result sets
   - **Solution**: Use LIMIT clause or more specific filters
   - **Verification**: Modify queries to return smaller result sets

4. **Too Many Concurrent Operations**
   - **Cause**: Too many operations consuming memory simultaneously
   - **Solution**: Limit concurrent operations
   - **Verification**: Reduce concurrency and observe memory usage

## Disk Space Issues

### Running Out of Disk Space

**Symptoms:**
- Disk space warnings in logs
- Operations fail with "No space left on device"
- System becomes read-only

**Possible Causes and Solutions:**

1. **Data Growth**
   - **Cause**: Normal data growth exceeding available space
   - **Solution**: Add more disk space or nodes
   - **Verification**: Monitor disk usage trends

2. **Temporary Files Accumulation**
   - **Cause**: Temporary files not being cleaned up
   - **Solution**: Clean up temporary files and restart
   - **Verification**: Check disk usage by directory

3. **Log File Growth**
   - **Cause**: Log files consuming too much space
   - **Solution**: Implement log rotation or reduce log level
   - **Verification**: Check size of log files

4. **Compaction Issues**
   - **Cause**: LSM compaction not running or ineffective
   - **Solution**: Manually trigger compaction or adjust settings
   - **Verification**: Check compaction status and history

## Network Issues

### Network Connectivity Problems

**Symptoms:**
- Nodes losing connection with each other
- Replication delays or failures
- Intermittent availability issues

**Possible Causes and Solutions:**

1. **Network Instability**
   - **Cause**: Unreliable network connection
   - **Solution**: Improve network infrastructure
   - **Verification**: Monitor network metrics (packet loss, latency)

2. **Network Congestion**
   - **Cause**: High network traffic affecting Serengeti
   - **Solution**: Provide dedicated network capacity
   - **Verification**: Check network utilization during issues

3. **Firewall or Security Rules**
   - **Cause**: Changing firewall rules blocking communication
   - **Solution**: Ensure consistent firewall configuration
   - **Verification**: Test connectivity between nodes

4. **DNS Issues**
   - **Cause**: DNS resolution problems
   - **Solution**: Use IP addresses instead of hostnames or fix DNS
   - **Verification**: Test DNS resolution for all nodes

## Dashboard Issues

### Dashboard Not Loading

**Symptoms:**
- Blank page when accessing dashboard
- JavaScript errors in browser console
- Partial loading of dashboard components

**Possible Causes and Solutions:**

1. **Browser Compatibility**
   - **Cause**: Using unsupported browser
   - **Solution**: Use a modern browser (Chrome, Firefox, Edge)
   - **Verification**: Try a different browser

2. **JavaScript Disabled**
   - **Cause**: JavaScript disabled in browser
   - **Solution**: Enable JavaScript
   - **Verification**: Check browser settings

3. **Server-side Rendering Issues**
   - **Cause**: Server problems generating dashboard HTML/JS
   - **Solution**: Check server logs for errors
   - **Verification**: Try accessing raw API endpoints:
     ```
     http://<host>:1985/api/status
     ```

4. **Network Connectivity**
   - **Cause**: Network issues between browser and server
   - **Solution**: Check network connectivity
   - **Verification**: Try accessing from different network

## Diagnostic Tools

### System Logs

The most important diagnostic tool is the system logs:

```
# Log file location
./logs/serengeti.log
```

Key log levels to look for:
- `ERROR`: Serious issues that need attention
- `WARN`: Potential problems that might need investigation
- `INFO`: Normal operational messages

### Dashboard Diagnostics

The dashboard includes diagnostic tools:

1. **System Health**: Overall health status with component-level details
2. **Metrics**: Performance and resource usage metrics
3. **Logs Viewer**: Web interface for viewing logs
4. **Node Status**: Detailed status of all nodes

### Command-line Diagnostics

You can run diagnostics from the command line:

```
java -jar serengeti.jar --diagnose
```

This will:
- Check system requirements
- Verify data integrity
- Test network connectivity
- Report any issues found

### API Diagnostics

The API provides diagnostic endpoints:

```
# Get system status
curl http://<host>:1985/api/status

# Get detailed diagnostics
curl http://<host>:1985/api/diagnostics

# Get metrics
curl http://<host>:1985/api/metrics
```

## Getting Additional Help

If you cannot resolve an issue using this guide:

1. **Check Documentation**: Review other documentation sections for more information
2. **Search Issues**: Check if the issue has been reported in the [GitHub Issues](https://github.com/ao/serengeti/issues)
3. **Create an Issue**: If the problem is new, create a detailed issue report including:
   - Serengeti version
   - Java version
   - Operating system
   - Steps to reproduce
   - Error messages or logs
   - System configuration
4. **Community Forum**: Ask for help in the community forum
5. **Professional Support**: For enterprise deployments, consider professional support options

## Conclusion

This troubleshooting guide covers the most common issues you might encounter when using Serengeti. Remember that Serengeti is designed to be autonomous and self-healing, so many issues may resolve themselves automatically given time. For persistent issues, the diagnostic tools and logs should provide the information needed to identify and resolve the problem.