# Getting Started with Serengeti

This guide will help you get started with the Serengeti distributed database system. It covers installation, basic configuration, and initial usage.

## What is Serengeti?

Serengeti is an autonomous distributed database system that runs on the Java Virtual Machine (JVM). It requires zero configuration or management to set up or maintain. Simply start Serengeti on any number of machines on a controlled network where each machine is a member of the same subnet, and they will automatically connect to each other and create a distributed database.

## System Requirements

Before installing Serengeti, ensure your system meets the following requirements:

- **Java**: JDK 11 or higher
- **Memory**: Minimum 2GB RAM (4GB recommended)
- **Disk Space**: 1GB for installation + additional space for data
- **Network**: All nodes must be on the same subnet
- **Operating System**: Any OS that supports Java (Windows, Linux, macOS)

## Installation

### Option 1: Download Pre-built JAR

1. Download the latest release from the [release page](https://github.com/ao/serengeti/releases)
2. Unzip the downloaded file
3. The directory will contain `serengeti.jar` and supporting files

### Option 2: Build from Source

1. Clone the repository:
   ```
   git clone https://github.com/ao/serengeti.git
   ```

2. Navigate to the project directory:
   ```
   cd serengeti
   ```

3. Build using Maven:
   ```
   mvn clean install
   ```

4. The built JAR will be in the `target` directory:
   ```
   target/serengeti-<version>.jar
   ```
   
   Where `<version>` is the current version of the project.

## Versioning System

Serengeti uses semantic versioning (MAJOR.MINOR.PATCH):

- **MAJOR**: Incremented for incompatible API changes
- **MINOR**: Incremented for new functionality in a backward-compatible manner
- **PATCH**: Incremented for backward-compatible bug fixes

The current version can be found in the `version.txt` file in the project root directory. This file serves as the source of truth for the current version of the application.

## Running Serengeti

### Starting a Single Node

To start a single Serengeti node:

```
java -jar serengeti.jar
```

By default, Serengeti will:
- Create a data directory in the current working directory
- Listen for HTTP connections on port 1985
- Listen for node discovery on port 1986
- Start with default configuration settings

### Command-line Options

You can customize Serengeti's behavior with command-line options:

```
java -jar serengeti.jar [options]
```

Available options:

| Option | Description | Default |
|--------|-------------|---------|
| `--port=<port>` | HTTP server port | 1985 |
| `--discovery-port=<port>` | Node discovery port | 1986 |
| `--data-path=<path>` | Data directory path | ./data |
| `--log-level=<level>` | Logging level (INFO, DEBUG, etc.) | INFO |
| `--config=<file>` | Configuration file path | N/A |

Example:

```
java -jar serengeti.jar --port=8080 --data-path=/var/serengeti/data --log-level=DEBUG
```

### JVM Options

For optimal performance, consider the following JVM options:

```
java -Xmx4g -Xms2g -XX:+UseG1GC -jar serengeti.jar
```

This allocates 2-4GB of heap memory and uses the G1 garbage collector.

## Creating a Multi-Node Cluster

To create a multi-node Serengeti cluster:

1. Ensure all machines are on the same subnet
2. Start Serengeti on each machine using the same command:
   ```
   java -jar serengeti.jar
   ```

3. The nodes will automatically discover each other and form a cluster
4. You can verify the cluster formation through the dashboard

## Accessing Serengeti

### Dashboard

The administrative dashboard is available at:

```
http://<host>:1985/dashboard
```

Replace `<host>` with the hostname or IP address of any Serengeti node.

The dashboard provides:
- Cluster status overview
- Node information
- Database and table management
- Performance metrics
- System logs

### Interactive Console

The interactive query console is available at:

```
http://<host>:1985/interactive
```

This console allows you to:
- Execute SQL queries
- View query results
- Explore database schema
- Save and load queries

## Basic Operations

### Creating a Database

To create a new database:

1. Open the interactive console
2. Execute the following SQL command:
   ```sql
   CREATE DATABASE my_database;
   ```

### Creating a Table

To create a table in your database:

```sql
CREATE TABLE my_database.users (
  id INT,
  name VARCHAR,
  email VARCHAR,
  created_at TIMESTAMP
);
```

### Inserting Data

To insert data into your table:

```sql
INSERT INTO my_database.users (id, name, email, created_at)
VALUES (1, 'John Doe', 'john@example.com', CURRENT_TIMESTAMP);
```

### Querying Data

To query data from your table:

```sql
SELECT * FROM my_database.users WHERE id = 1;
```

### Creating an Index

To create an index for faster queries:

```sql
CREATE INDEX ON my_database.users(email);
```

## Monitoring

### System Logs

Serengeti logs are written to the console and to log files in the `logs` directory. The log level can be configured with the `--log-level` option.

### Metrics

Serengeti exposes metrics that can be viewed:

1. Through the dashboard
2. Via the metrics API endpoint: `http://<host>:1985/api/metrics`
3. Through Prometheus integration (if configured)

## Backup and Recovery

### Creating a Backup

To back up your Serengeti data:

1. Ensure the system is in a consistent state
2. Copy the entire data directory to a backup location:
   ```
   cp -r ./data /backup/serengeti-data-$(date +%Y%m%d)
   ```

### Restoring from Backup

To restore from a backup:

1. Stop all Serengeti nodes
2. Replace the data directory with the backup:
   ```
   rm -rf ./data
   cp -r /backup/serengeti-data-20250101 ./data
   ```
3. Restart the Serengeti nodes

## Common Issues and Solutions

### Node Discovery Issues

**Issue**: Nodes cannot discover each other.

**Solution**:
- Verify all nodes are on the same subnet
- Check firewall settings to ensure ports 1985 and 1986 are open
- Verify network connectivity between nodes

### Performance Issues

**Issue**: Slow query performance.

**Solution**:
- Create indexes on frequently queried columns
- Increase JVM heap size
- Monitor system resources for bottlenecks

### Out of Memory Errors

**Issue**: JVM out of memory errors.

**Solution**:
- Increase heap size with `-Xmx` option
- Limit result set sizes in queries
- Consider adding more nodes to distribute the load

## Next Steps

Now that you have Serengeti up and running, you can:

1. Explore the [Basic Operations](BasicOperations.md) guide for more detailed usage instructions
2. Check the [Troubleshooting](Troubleshooting.md) guide if you encounter issues
3. Read the [Architecture Documentation](../architecture/SystemArchitecture.md) to understand how Serengeti works
4. Join the community forum to connect with other users

## Getting Help

If you encounter any issues or have questions:

- Check the [Troubleshooting](Troubleshooting.md) guide
- Search for similar issues in the [GitHub Issues](https://github.com/ao/serengeti/issues)
- Create a new issue if your problem is not already reported
- Join the community forum for discussion and support

## Conclusion

You now have a running Serengeti distributed database system! The autonomous nature of Serengeti means that most management tasks happen automatically, allowing you to focus on using the database rather than administering it.

As your data and usage grow, Serengeti will automatically adapt by redistributing data and optimizing performance. Simply add more nodes to the network when you need additional capacity, and Serengeti will integrate them into the cluster.