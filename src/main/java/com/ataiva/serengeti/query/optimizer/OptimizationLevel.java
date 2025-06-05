package com.ataiva.serengeti.query.optimizer;

/**
 * Enum representing the different levels of query optimization.
 */
public enum OptimizationLevel {
    /**
     * No optimization - use the original query execution path
     */
    NONE,
    
    /**
     * Low optimization - basic optimizations like index usage
     */
    LOW,
    
    /**
     * Medium optimization - index usage, join order optimization, etc.
     */
    MEDIUM,
    
    /**
     * High optimization - all available optimizations including statistics-based decisions
     */
    HIGH,
    
    /**
     * Experimental optimization - includes experimental features that may not be stable
     */
    EXPERIMENTAL
}