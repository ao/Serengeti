package com.ataiva.serengeti.query.parser;

/**
 * Enum representing the different types of SQL queries.
 */
public enum QueryType {
    /**
     * Unknown or unspecified query type
     */
    UNKNOWN,
    
    /**
     * SELECT query
     */
    SELECT,
    
    /**
     * INSERT query
     */
    INSERT,
    
    /**
     * UPDATE query
     */
    UPDATE,
    
    /**
     * DELETE query
     */
    DELETE,
    
    /**
     * CREATE TABLE query
     */
    CREATE_TABLE,
    
    /**
     * CREATE DATABASE query
     */
    CREATE_DATABASE,
    
    /**
     * DROP TABLE query
     */
    DROP_TABLE,
    
    /**
     * DROP DATABASE query
     */
    DROP_DATABASE,
    
    /**
     * SHOW TABLES query
     */
    SHOW_TABLES,
    
    /**
     * SHOW DATABASES query
     */
    SHOW_DATABASES
}