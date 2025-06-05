# Serengeti Basic Operations

This guide covers the basic operations you can perform with the Serengeti distributed database system. It provides detailed instructions for common database tasks.

## Table of Contents

- [Database Operations](#database-operations)
- [Table Operations](#table-operations)
- [Data Operations](#data-operations)
- [Query Operations](#query-operations)
- [Index Operations](#index-operations)
- [Transaction Operations](#transaction-operations)
- [User Interface Operations](#user-interface-operations)

## Database Operations

### Listing Databases

To list all databases in the system:

```sql
SHOW DATABASES;
```

Example output:
```
+---------------+
| Database Name |
+---------------+
| users_db      |
| products_db   |
| analytics_db  |
+---------------+
```

### Creating a Database

To create a new database:

```sql
CREATE DATABASE database_name;
```

Example:
```sql
CREATE DATABASE customers_db;
```

### Dropping a Database

To delete a database and all its tables:

```sql
DROP DATABASE database_name;
```

Example:
```sql
DROP DATABASE old_analytics_db;
```

### Getting Database Information

To view detailed information about a database:

```sql
DESCRIBE DATABASE database_name;
```

Example output:
```
Database: customers_db
Created: 2025-06-01 14:30:22
Tables: 3
Size: 256 MB
Replicas: 3
Nodes: node1.example.com, node2.example.com, node3.example.com
```

## Table Operations

### Listing Tables

To list all tables in a database:

```sql
SHOW TABLES IN database_name;
```

Example:
```sql
SHOW TABLES IN customers_db;
```

Example output:
```
+---------------+
| Table Name    |
+---------------+
| customers     |
| orders        |
| payments      |
+---------------+
```

### Creating a Table

To create a new table:

```sql
CREATE TABLE database_name.table_name (
  column1 data_type,
  column2 data_type,
  ...
);
```

Example:
```sql
CREATE TABLE customers_db.contacts (
  id INT,
  first_name VARCHAR,
  last_name VARCHAR,
  email VARCHAR,
  phone VARCHAR,
  created_at TIMESTAMP
);
```

Supported data types:
- `INT`: Integer values
- `FLOAT`: Floating-point values
- `VARCHAR`: Variable-length character strings
- `BOOLEAN`: True/false values
- `TIMESTAMP`: Date and time values
- `BLOB`: Binary large objects

### Dropping a Table

To delete a table and all its data:

```sql
DROP TABLE database_name.table_name;
```

Example:
```sql
DROP TABLE customers_db.old_contacts;
```

### Describing a Table

To view the structure of a table:

```sql
DESCRIBE TABLE database_name.table_name;
```

Example:
```sql
DESCRIBE TABLE customers_db.contacts;
```

Example output:
```
+------------+-----------+
| Column     | Type      |
+------------+-----------+
| id         | INT       |
| first_name | VARCHAR   |
| last_name  | VARCHAR   |
| email      | VARCHAR   |
| phone      | VARCHAR   |
| created_at | TIMESTAMP |
+------------+-----------+
```

### Altering a Table

To add a column to an existing table:

```sql
ALTER TABLE database_name.table_name ADD COLUMN column_name data_type;
```

Example:
```sql
ALTER TABLE customers_db.contacts ADD COLUMN address VARCHAR;
```

To drop a column from a table:

```sql
ALTER TABLE database_name.table_name DROP COLUMN column_name;
```

Example:
```sql
ALTER TABLE customers_db.contacts DROP COLUMN phone;
```

## Data Operations

### Inserting Data

To insert a single row:

```sql
INSERT INTO database_name.table_name (column1, column2, ...)
VALUES (value1, value2, ...);
```

Example:
```sql
INSERT INTO customers_db.contacts (id, first_name, last_name, email, created_at)
VALUES (1, 'John', 'Doe', 'john@example.com', CURRENT_TIMESTAMP);
```

To insert multiple rows:

```sql
INSERT INTO database_name.table_name (column1, column2, ...)
VALUES 
  (value1_1, value1_2, ...),
  (value2_1, value2_2, ...),
  ...;
```

Example:
```sql
INSERT INTO customers_db.contacts (id, first_name, last_name, email)
VALUES 
  (2, 'Jane', 'Smith', 'jane@example.com'),
  (3, 'Bob', 'Johnson', 'bob@example.com');
```

### Updating Data

To update existing rows:

```sql
UPDATE database_name.table_name
SET column1 = value1, column2 = value2, ...
WHERE condition;
```

Example:
```sql
UPDATE customers_db.contacts
SET email = 'john.doe@example.com'
WHERE id = 1;
```

### Deleting Data

To delete rows:

```sql
DELETE FROM database_name.table_name
WHERE condition;
```

Example:
```sql
DELETE FROM customers_db.contacts
WHERE id = 3;
```

To delete all rows from a table:

```sql
DELETE FROM database_name.table_name;
```

### Bulk Import

To import data from a CSV file (via the interactive console):

1. Click on the "Import" button in the interactive console
2. Select the target database and table
3. Upload your CSV file
4. Map the CSV columns to table columns
5. Click "Import" to start the import process

## Query Operations

### Basic Select

To retrieve all columns from a table:

```sql
SELECT * FROM database_name.table_name;
```

Example:
```sql
SELECT * FROM customers_db.contacts;
```

To retrieve specific columns:

```sql
SELECT column1, column2, ... FROM database_name.table_name;
```

Example:
```sql
SELECT first_name, last_name, email FROM customers_db.contacts;
```

### Filtering Data

To filter rows with a WHERE clause:

```sql
SELECT * FROM database_name.table_name
WHERE condition;
```

Example:
```sql
SELECT * FROM customers_db.contacts
WHERE last_name = 'Smith';
```

Common operators for conditions:
- `=`: Equal to
- `<>` or `!=`: Not equal to
- `>`: Greater than
- `<`: Less than
- `>=`: Greater than or equal to
- `<=`: Less than or equal to
- `LIKE`: Pattern matching
- `IN`: Value in a set
- `BETWEEN`: Value in a range

Examples:
```sql
-- Pattern matching
SELECT * FROM customers_db.contacts
WHERE email LIKE '%@example.com';

-- Value in a set
SELECT * FROM customers_db.contacts
WHERE id IN (1, 3, 5);

-- Value in a range
SELECT * FROM customers_db.contacts
WHERE created_at BETWEEN '2025-01-01' AND '2025-06-01';
```

### Sorting Results

To sort query results:

```sql
SELECT * FROM database_name.table_name
ORDER BY column1 [ASC|DESC], column2 [ASC|DESC], ...;
```

Example:
```sql
SELECT * FROM customers_db.contacts
ORDER BY last_name ASC, first_name ASC;
```

### Limiting Results

To limit the number of rows returned:

```sql
SELECT * FROM database_name.table_name
LIMIT count;
```

Example:
```sql
SELECT * FROM customers_db.contacts
LIMIT 10;
```

To use pagination:

```sql
SELECT * FROM database_name.table_name
LIMIT count OFFSET start;
```

Example:
```sql
-- Get rows 11-20
SELECT * FROM customers_db.contacts
LIMIT 10 OFFSET 10;
```

### Aggregation

To perform aggregation operations:

```sql
SELECT aggregate_function(column)
FROM database_name.table_name
[GROUP BY column];
```

Supported aggregate functions:
- `COUNT`: Count rows
- `SUM`: Sum values
- `AVG`: Average of values
- `MIN`: Minimum value
- `MAX`: Maximum value

Examples:
```sql
-- Count all contacts
SELECT COUNT(*) FROM customers_db.contacts;

-- Count contacts by last name
SELECT last_name, COUNT(*) as count
FROM customers_db.contacts
GROUP BY last_name;
```

### Joins

To join tables:

```sql
SELECT *
FROM database_name.table1
JOIN database_name.table2 ON table1.column = table2.column;
```

Example:
```sql
SELECT customers.first_name, customers.last_name, orders.order_date, orders.amount
FROM customers_db.customers
JOIN customers_db.orders ON customers.id = orders.customer_id;
```

Join types:
- `JOIN` or `INNER JOIN`: Returns rows when there is a match in both tables
- `LEFT JOIN`: Returns all rows from the left table and matched rows from the right table
- `RIGHT JOIN`: Returns all rows from the right table and matched rows from the left table
- `FULL JOIN`: Returns rows when there is a match in one of the tables

### Subqueries

To use a subquery:

```sql
SELECT *
FROM database_name.table_name
WHERE column IN (SELECT column FROM database_name.other_table WHERE condition);
```

Example:
```sql
SELECT *
FROM customers_db.customers
WHERE id IN (SELECT customer_id FROM customers_db.orders WHERE amount > 1000);
```

## Index Operations

### Creating an Index

To create an index on a column:

```sql
CREATE INDEX ON database_name.table_name(column);
```

Example:
```sql
CREATE INDEX ON customers_db.contacts(email);
```

To create a composite index on multiple columns:

```sql
CREATE INDEX ON database_name.table_name(column1, column2);
```

Example:
```sql
CREATE INDEX ON customers_db.contacts(last_name, first_name);
```

### Listing Indexes

To list all indexes in a database:

```sql
SHOW INDEXES IN database_name;
```

Example:
```sql
SHOW INDEXES IN customers_db;
```

To list indexes for a specific table:

```sql
SHOW INDEXES ON database_name.table_name;
```

Example:
```sql
SHOW INDEXES ON customers_db.contacts;
```

### Dropping an Index

To drop an index:

```sql
DROP INDEX ON database_name.table_name(column);
```

Example:
```sql
DROP INDEX ON customers_db.contacts(email);
```

## Transaction Operations

### Starting a Transaction

To start a transaction:

```sql
BEGIN;
```

### Committing a Transaction

To commit a transaction:

```sql
COMMIT;
```

### Rolling Back a Transaction

To roll back a transaction:

```sql
ROLLBACK;
```

### Transaction Example

```sql
BEGIN;
INSERT INTO customers_db.customers (id, name) VALUES (1, 'New Customer');
INSERT INTO customers_db.orders (id, customer_id, amount) VALUES (101, 1, 500);
COMMIT;
```

## User Interface Operations

### Using the Dashboard

The dashboard provides a graphical interface for:

1. **Monitoring**: View system status, node information, and performance metrics
2. **Management**: Create and manage databases and tables
3. **Administration**: View logs, check system health, and perform maintenance tasks

To access the dashboard:
```
http://<host>:1985/dashboard
```

Key dashboard features:
- **Overview**: System status and key metrics
- **Nodes**: Information about all nodes in the cluster
- **Databases**: List of databases with size and table count
- **Tables**: Table structure and statistics
- **Queries**: Recent queries with execution time and status
- **Logs**: System logs for troubleshooting
- **Settings**: System configuration options

### Using the Interactive Console

The interactive console provides a web-based interface for executing queries:

To access the interactive console:
```
http://<host>:1985/interactive
```

Key interactive console features:
- **Query Editor**: Write and execute SQL queries
- **Results View**: View query results in tabular format
- **History**: Access previously executed queries
- **Export**: Export query results to CSV or JSON
- **Schema Browser**: Explore database schema
- **Saved Queries**: Save frequently used queries

## Best Practices

1. **Use Indexes Wisely**: Create indexes on columns used frequently in WHERE clauses and joins
2. **Limit Result Sets**: Use LIMIT to avoid retrieving unnecessarily large result sets
3. **Use Transactions**: Wrap related operations in transactions to ensure consistency
4. **Regular Backups**: Implement a regular backup strategy
5. **Monitor Performance**: Use the dashboard to monitor system performance
6. **Optimize Queries**: Review and optimize slow-running queries

## Conclusion

This guide covered the basic operations you can perform with the Serengeti distributed database system. For more advanced topics, refer to the other documentation sections:

- [Getting Started Guide](GettingStarted.md)
- [Troubleshooting Guide](Troubleshooting.md)
- [Architecture Documentation](../architecture/SystemArchitecture.md)