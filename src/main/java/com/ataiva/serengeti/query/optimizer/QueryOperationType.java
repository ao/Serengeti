package com.ataiva.serengeti.query.optimizer;

/**
 * Enum representing the different types of operations in a query execution plan.
 */
public enum QueryOperationType {
    /**
     * Unknown or unspecified operation type
     */
    UNKNOWN,
    
    /**
     * Table scan - reads all rows from a table
     */
    TABLE_SCAN,
    
    /**
     * Index scan - uses an index to find specific rows
     */
    INDEX_SCAN,
    
    /**
     * Range scan - uses an index to find rows in a range
     */
    RANGE_SCAN,
    
    /**
     * Filter - applies a filter condition to rows
     */
    FILTER,
    
    /**
     * Project - selects specific columns from rows
     */
    PROJECT,
    
    /**
     * Nested loop join - joins tables using nested loops
     */
    NESTED_LOOP_JOIN,
    
    /**
     * Build hash table - builds a hash table for a hash join
     */
    BUILD_HASH_TABLE,
    
    /**
     * Hash join probe - probes a hash table for matching rows
     */
    HASH_JOIN_PROBE,
    
    /**
     * Probe hash table - alias for HASH_JOIN_PROBE for backward compatibility
     */
    PROBE_HASH_TABLE,
    
    /**
     * Sort - sorts rows based on columns
     */
    SORT,
    
    /**
     * Merge - merges sorted rows
     */
    MERGE,
    
    /**
     * Aggregate - performs aggregation operations
     */
    AGGREGATE,
    
    /**
     * Group - groups rows based on columns
     */
    GROUP,
    
    /**
     * Limit - limits the number of rows returned
     */
    LIMIT,
    
    /**
     * Offset - skips a number of rows
     */
    OFFSET,
    
    /**
     * Distinct - removes duplicate rows
     */
    DISTINCT,
    
    /**
     * Union - combines results from multiple queries
     */
    UNION,
    
    /**
     * Intersect - finds common rows between multiple queries
     */
    INTERSECT,
    
    /**
     * Except - finds rows in one query but not in another
     */
    EXCEPT,
    
    /**
     * Subquery - executes a subquery
     */
    SUBQUERY
}