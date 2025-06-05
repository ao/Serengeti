package com.ataiva.serengeti.query.optimizer;

/**
 * Enum representing the different types of query execution plans.
 */
public enum QueryPlanType {
    /**
     * Unknown or unspecified plan type
     */
    UNKNOWN,
    
    /**
     * Full table scan plan - reads all rows from a table
     */
    FULL_TABLE_SCAN,
    
    /**
     * Index scan plan - uses an index to find specific rows
     */
    INDEX_SCAN,
    
    /**
     * Range scan plan - uses an index to find rows in a range
     */
    RANGE_SCAN,
    
    /**
     * Nested loop join plan - uses nested loops to join tables
     */
    NESTED_LOOP_JOIN,
    
    /**
     * Hash join plan - uses hash tables to join tables
     */
    HASH_JOIN,
    
    /**
     * Sort-merge join plan - sorts tables and merges them
     */
    SORT_MERGE_JOIN,
    
    /**
     * Aggregation plan - performs aggregation operations
     */
    AGGREGATION,
    
    /**
     * Subquery plan - executes a subquery
     */
    SUBQUERY
}