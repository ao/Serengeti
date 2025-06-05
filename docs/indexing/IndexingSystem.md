# Serengeti Indexing System

This document describes the indexing system implemented in the Serengeti database to improve query performance.

## Overview

The Serengeti indexing system uses B-tree data structures to provide efficient lookups for frequently queried columns. Indexes significantly improve query performance, especially for large datasets, by avoiding full table scans when searching for specific values.

### Key Components

1. **BTreeIndex**: Implements a B-tree data structure for efficient lookups
2. **IndexManager**: Manages index creation, maintenance, and usage
3. **Query Integration**: Automatically uses indexes when available for queries
4. **Automatic Indexing**: Monitors query patterns and creates indexes for frequently queried columns

## Using Indexes in Queries

When you execute a query with a WHERE clause, the query engine automatically checks if an index exists for the column being queried. If an index exists, it will be used to speed up the query.

For example:

```sql
SELECT * FROM users WHERE username='johndoe';
```

If an index exists on the `username` column, this query will use the index to find the matching rows instead of scanning the entire table.

You can see if an index was used by checking the `explain` field in the query response, which will contain "Used index on [column]" if an index was used.

## Creating and Managing Indexes

### Creating an Index

To create an index on a column, use the `CREATE INDEX` command:

```sql
CREATE INDEX ON database.table(column);
```

For example:

```sql
CREATE INDEX ON users.profiles(username);
```

### Dropping an Index

To drop an index, use the `DROP INDEX` command:

```sql
DROP INDEX ON database.table(column);
```

For example:

```sql
DROP INDEX ON users.profiles(username);
```

### Listing Indexes

To list all indexes in the database:

```sql
SHOW INDEXES;
```

To list indexes for a specific table:

```sql
SHOW INDEXES ON database.table;
```

## Automatic Indexing

The Serengeti database includes an automatic indexing feature that monitors query patterns and creates indexes for frequently queried columns. This helps optimize performance without requiring manual index management.

### How Automatic Indexing Works

1. The system tracks how often each column is used in WHERE clauses
2. When a column is queried more than a configurable threshold number of times, an index is automatically created
3. The system limits the number of automatic indexes per table to avoid excessive resource usage

### Configuring Automatic Indexing

Automatic indexing can be configured through the IndexManager:

```java
// Enable or disable automatic indexing
Serengeti.indexManager.setAutoIndexingEnabled(true);

// Set the threshold for automatic indexing (number of queries before creating an index)
Serengeti.indexManager.setAutoIndexThreshold(100);

// Set the maximum number of indexes per table
Serengeti.indexManager.setMaxIndexesPerTable(5);
```

## Index Maintenance

Indexes are automatically maintained when data is modified:

- When rows are inserted, the indexes are updated to include the new data
- When rows are updated, the indexes are updated to reflect the changes
- When rows are deleted, the indexes are updated to remove references to the deleted data

## Best Practices

1. **Create indexes on columns used frequently in WHERE clauses**
   - Primary keys and foreign keys are good candidates for indexing
   - Columns with high cardinality (many unique values) benefit most from indexing

2. **Avoid over-indexing**
   - Each index increases storage requirements and slows down write operations
   - Let the automatic indexing system identify the most important columns to index

3. **Monitor index usage**
   - Use `SHOW INDEXES` to see what indexes exist
   - Check the `explain` field in query responses to see if indexes are being used

4. **Consider rebuilding indexes periodically**
   - If a table undergoes many changes, rebuilding its indexes can improve performance

## Performance Considerations

- Indexes significantly speed up read operations but slightly slow down write operations
- The performance benefit of indexes increases with the size of the table
- Indexes are most effective when queries are selective (return a small portion of the table)
- B-tree indexes support both equality searches (`column = value`) and range searches (`column > value`)

## Implementation Details

The indexing system is implemented using the following classes:

- `BTreeIndex`: Implements a B-tree data structure for efficient lookups
- `IndexManager`: Manages index creation, maintenance, and usage
- Integration with `QueryEngine` for automatic index usage in queries
- Integration with `Storage` for index maintenance during data modifications

Indexes are persisted to disk and loaded into memory on startup, ensuring they survive system restarts.