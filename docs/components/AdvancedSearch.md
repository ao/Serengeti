# Advanced Search Features

This document describes the advanced search capabilities implemented in the Serengeti query system.

## Overview

The Serengeti query system now supports the following advanced search features:

1. **Range Queries**: Search for values within specific ranges using comparison operators
2. **Full-Text Search**: Search for text content using tokenization and relevance scoring
3. **Regular Expression Matching**: Search for patterns in text using regular expressions
4. **Fuzzy Matching**: Search for approximate text matches using edit distance

These features significantly enhance the query capabilities of the Serengeti database system, allowing for more powerful and flexible data retrieval.

## Range Queries

Range queries allow you to search for values that fall within a specific range using comparison operators.

### Supported Operators

- `>` (greater than)
- `<` (less than)
- `>=` (greater than or equal to)
- `<=` (less than or equal to)

### Syntax

```sql
SELECT * FROM database.table WHERE column>value
SELECT * FROM database.table WHERE column<value
SELECT * FROM database.table WHERE column>=value
SELECT * FROM database.table WHERE column<=value
```

### Examples

```sql
-- Find all users older than 30
SELECT * FROM users.profiles WHERE age>30

-- Find all products with price less than 50
SELECT * FROM store.products WHERE price<50

-- Find all transactions with amount greater than or equal to 1000
SELECT * FROM finance.transactions WHERE amount>=1000

-- Find all tasks with priority less than or equal to 3
SELECT * FROM projects.tasks WHERE priority<=3
```

### Implementation Details

Range queries are implemented using B-tree indexes for efficient retrieval. When a range query is executed:

1. The system checks if an index exists for the column being queried
2. If an index exists, it uses the `findRowsInRange` method to efficiently find matching rows
3. If no index exists, it performs a full table scan with filtering

For optimal performance, it's recommended to create indexes on columns that are frequently used in range queries:

```sql
CREATE INDEX ON database.table(column)
```

## Full-Text Search

Full-text search allows you to search for text content within documents using tokenization and relevance scoring.

### Syntax

```sql
SELECT * FROM database.table WHERE column CONTAINS 'search terms'
```

### Examples

```sql
-- Find all documents containing the word "database"
SELECT * FROM docs.articles WHERE content CONTAINS 'database'

-- Find all products with descriptions containing "wireless" and "bluetooth"
SELECT * FROM store.products WHERE description CONTAINS 'wireless bluetooth'
```

### Creating Full-Text Indexes

To optimize full-text search performance, you can create full-text indexes on text columns:

```sql
CREATE FULLTEXT INDEX ON database.table(column)
```

### Managing Full-Text Indexes

```sql
-- Show all full-text indexes
SHOW FULLTEXT INDEXES

-- Show full-text indexes for a specific table
SHOW FULLTEXT INDEXES ON database.table

-- Drop a full-text index
DROP FULLTEXT INDEX ON database.table(column)
```

### Implementation Details

Full-text search is implemented using an inverted index that maps tokens to document IDs and relevance scores. The search process includes:

1. Tokenization: Breaking text into individual words
2. Stop word removal: Filtering out common words like "a", "the", "is"
3. Relevance scoring: Calculating TF-IDF (Term Frequency-Inverse Document Frequency) scores
4. Result ranking: Returning results sorted by relevance

Results include a `__relevance` score that indicates how well each document matches the search query.

## Regular Expression Matching

Regular expression matching allows you to search for patterns in text using powerful regex syntax.

### Syntax

```sql
SELECT * FROM database.table WHERE column REGEX 'pattern'
```

### Examples

```sql
-- Find all email addresses from a specific domain
SELECT * FROM users.contacts WHERE email REGEX '.*@example\\.com'

-- Find all phone numbers in a specific format
SELECT * FROM users.contacts WHERE phone REGEX '\\d{3}-\\d{3}-\\d{4}'

-- Find all product codes matching a pattern
SELECT * FROM inventory.items WHERE code REGEX '^[A-Z]{2}\\d{4}$'
```

### Implementation Details

Regular expression matching is implemented using Java's `Pattern` and `Matcher` classes. The system:

1. Compiles the regex pattern
2. Performs a full table scan, applying the pattern to each row
3. Returns rows where the pattern matches

Note that regex matching can be computationally expensive for large datasets or complex patterns. Use specific patterns and limit the scope of your queries when possible.

## Fuzzy Matching

Fuzzy matching allows you to search for approximate text matches using edit distance (Levenshtein distance).

### Syntax

```sql
SELECT * FROM database.table WHERE column FUZZY 'approximate text'
```

### Examples

```sql
-- Find names similar to "John Smith" (handles typos and variations)
SELECT * FROM users.profiles WHERE name FUZZY 'John Smith'

-- Find products with names similar to "Bluetooth Headphones"
SELECT * FROM store.products WHERE name FUZZY 'Bluetooth Headphones'
```

### Implementation Details

Fuzzy matching is implemented using the Levenshtein distance algorithm, which calculates the minimum number of single-character edits (insertions, deletions, or substitutions) required to change one string into another.

The system:

1. Calculates the edit distance between the search term and each field value
2. Returns rows where the edit distance is less than or equal to a threshold (default: 2)
3. Sorts results by edit distance (lower is better)

Results include a `__fuzzy_distance` score that indicates how closely each result matches the search term.

## Performance Considerations

Advanced search features can be computationally intensive, especially for large datasets. Consider the following performance tips:

1. **Use Indexes**: Create appropriate indexes for columns frequently used in queries
   - Regular B-tree indexes for range queries
   - Full-text indexes for text search

2. **Query Optimization**:
   - Be specific in your search terms
   - Limit the scope of your queries when possible
   - Use the most appropriate search feature for your use case

3. **Resource Considerations**:
   - Regular expression and fuzzy matching can be CPU-intensive
   - Full-text search requires additional memory for the inverted index
   - Range queries with indexes are generally the most efficient

## Example Use Cases

### E-commerce Product Search

```sql
-- Find products in a price range
SELECT * FROM products WHERE price>=10 AND price<=50

-- Find products with specific features in the description
SELECT * FROM products WHERE description CONTAINS 'wireless charging'

-- Find products with model numbers matching a pattern
SELECT * FROM products WHERE model REGEX '^ABC\\d{3}$'

-- Find products with names similar to user input (handles typos)
SELECT * FROM products WHERE name FUZZY 'Bluetooth Headphones'
```

### Document Management

```sql
-- Find documents created within a date range
SELECT * FROM documents WHERE created_at>=1620000000 AND created_at<=1630000000

-- Find documents containing specific topics
SELECT * FROM documents WHERE content CONTAINS 'machine learning algorithms'

-- Find documents with specific file patterns
SELECT * FROM documents WHERE filename REGEX '.*\\.pdf$'

-- Find documents with titles similar to a reference
SELECT * FROM documents WHERE title FUZZY 'Annual Financial Report'
```

## Conclusion

The advanced search features in Serengeti provide powerful tools for querying and retrieving data. By combining range queries, full-text search, regular expression matching, and fuzzy matching, you can build sophisticated search functionality that meets a wide range of use cases.