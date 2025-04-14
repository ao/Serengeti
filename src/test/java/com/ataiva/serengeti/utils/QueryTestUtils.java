package com.ataiva.serengeti.utils;

import com.ataiva.serengeti.query.QueryEngine;
import org.json.JSONObject;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Utilities for testing query functionality.
 */
public class QueryTestUtils {
    
    /**
     * Executes a query and returns the result.
     * 
     * @param query The query to execute
     * @return The query result
     */
    public static List<JSONObject> executeQuery(String query) {
        return QueryEngine.query(query);
    }
    
    /**
     * Asserts that a query result matches the expected condition.
     * 
     * @param query The query to execute
     * @param assertion A predicate that tests the query result
     */
    public static void assertQueryResult(String query, Predicate<List<JSONObject>> assertion) {
        List<JSONObject> result = executeQuery(query);
        assertTrue(assertion.test(result), "Query result did not match expected condition");
    }
    
    /**
     * Creates a test database query.
     * 
     * @param dbName The database name
     * @return The create database query
     */
    public static String createDatabaseQuery(String dbName) {
        return "create database " + dbName;
    }
    
    /**
     * Creates a test table query.
     * 
     * @param dbName The database name
     * @param tableName The table name
     * @return The create table query
     */
    public static String createTableQuery(String dbName, String tableName) {
        return "create table " + dbName + "." + tableName;
    }
    
    /**
     * Creates an insert query.
     * 
     * @param dbName The database name
     * @param tableName The table name
     * @param data A JSONObject containing the data to insert
     * @return The insert query
     */
    public static String insertQuery(String dbName, String tableName, JSONObject data) {
        StringBuilder query = new StringBuilder();
        query.append("insert into ").append(dbName).append(".").append(tableName).append(" (");
        
        StringBuilder columns = new StringBuilder();
        StringBuilder values = new StringBuilder();
        
        boolean first = true;
        for (String key : data.keySet()) {
            if (!first) {
                columns.append(", ");
                values.append(", ");
            }
            columns.append(key);
            values.append("'").append(data.get(key).toString()).append("'");
            first = false;
        }
        
        query.append(columns).append(") values(").append(values).append(")");
        
        return query.toString();
    }
    
    /**
     * Creates a select query.
     * 
     * @param dbName The database name
     * @param tableName The table name
     * @param columns The columns to select (use "*" for all columns)
     * @param whereColumn The column to filter on (optional)
     * @param whereValue The value to filter on (optional)
     * @return The select query
     */
    public static String selectQuery(String dbName, String tableName, String columns, String whereColumn, String whereValue) {
        StringBuilder query = new StringBuilder();
        query.append("select ").append(columns).append(" from ").append(dbName).append(".").append(tableName);
        
        if (whereColumn != null && !whereColumn.isEmpty() && whereValue != null) {
            query.append(" where ").append(whereColumn).append("='").append(whereValue).append("'");
        }
        
        return query.toString();
    }
    
    /**
     * Creates an update query.
     * 
     * @param dbName The database name
     * @param tableName The table name
     * @param updateColumn The column to update
     * @param updateValue The new value
     * @param whereColumn The column to filter on
     * @param whereValue The value to filter on
     * @return The update query
     */
    public static String updateQuery(String dbName, String tableName, String updateColumn, String updateValue, String whereColumn, String whereValue) {
        return "update " + dbName + "." + tableName + " set " + updateColumn + "='" + updateValue + "' where " + whereColumn + "='" + whereValue + "'";
    }
    
    /**
     * Creates a delete query.
     * 
     * @param dbName The database name
     * @param tableName The table name
     * @param whereColumn The column to filter on
     * @param whereValue The value to filter on
     * @return The delete query
     */
    public static String deleteQuery(String dbName, String tableName, String whereColumn, String whereValue) {
        return "delete " + dbName + "." + tableName + " where " + whereColumn + "='" + whereValue + "'";
    }
    
    /**
     * Creates a drop table query.
     * 
     * @param dbName The database name
     * @param tableName The table name
     * @return The drop table query
     */
    public static String dropTableQuery(String dbName, String tableName) {
        return "drop table " + dbName + "." + tableName;
    }
    
    /**
     * Creates a drop database query.
     * 
     * @param dbName The database name
     * @return The drop database query
     */
    public static String dropDatabaseQuery(String dbName) {
        return "drop database " + dbName;
    }
    
    /**
     * Creates a show databases query.
     * 
     * @return The show databases query
     */
    public static String showDatabasesQuery() {
        return "show databases";
    }
    
    /**
     * Creates a show tables query.
     * 
     * @param dbName The database name
     * @return The show tables query
     */
    public static String showTablesQuery(String dbName) {
        return "show " + dbName + " tables";
    }
}