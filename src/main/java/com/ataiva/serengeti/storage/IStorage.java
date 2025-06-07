package com.ataiva.serengeti.storage;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Interface for the Serengeti storage system.
 * Defines the contract for storage implementations.
 */
public interface IStorage {
    
    /**
     * Load Meta Databases to Memory
     */
    void loadMetaDatabasesToMemory();
    
    /**
     * Load All Storage Objects to Memory
     */
    void loadAllStorageObjectsToMemory();
    
    /**
     * Load All Replica Objects to Memory
     */
    void loadAllReplicaObjectsToMemory();
    
    /**
     * Get a List of existing Databases
     * @return List of database names
     */
    List<String> getDatabases();
    
    /**
     * Get Databases from file system
     * @param getFromFileSystem Whether to get from file system
     * @return List of database names
     */
    List<String> getDatabases(boolean getFromFileSystem);
    
    /**
     * Scan meta information and return a list of Databases and Tables included
     * @return Map of database names to table names
     */
    Map<String, List<String>> getDatabasesTablesMeta();
    
    /**
     * Select data from a table
     * @param db Database name
     * @param table Table name
     * @param selectWhat Columns to select
     * @param col Where column
     * @param val Where value
     * @return List of selected data
     */
    List<String> select(String db, String table, String selectWhat, String col, String val);
    
    /**
     * Insert data into a table
     * @param db Database name
     * @param table Table name
     * @param json Data to insert
     * @return Storage response object
     */
    StorageResponseObject insert(String db, String table, JSONObject json);
    
    /**
     * Insert data into a table
     * @param db Database name
     * @param table Table name
     * @param json Data to insert
     * @param isReplicationAction Whether this is a replication action
     * @return Storage response object
     */
    StorageResponseObject insert(String db, String table, JSONObject json, boolean isReplicationAction);
    
    /**
     * Update data in a table
     * @param db Database name
     * @param table Table name
     * @param update_key Update key
     * @param update_val Update value
     * @param where_col Where column
     * @param where_val Where value
     * @return Whether the update was successful
     */
    boolean update(String db, String table, String update_key, String update_val, String where_col, String where_val);
    
    /**
     * Update data in a table
     * @param db Database name
     * @param table Table name
     * @param update_key Update key
     * @param update_val Update value
     * @param where_col Where column
     * @param where_val Where value
     * @param isReplicationAction Whether this is a replication action
     * @return Whether the update was successful
     */
    boolean update(String db, String table, String update_key, String update_val, String where_col, String where_val, boolean isReplicationAction);
    
    /**
     * Delete data from a table
     * @param db Database name
     * @param table Table name
     * @param where_col Where column
     * @param where_val Where value
     * @return Whether the delete was successful
     */
    boolean delete(String db, String table, String where_col, String where_val);
    
    /**
     * Delete data from a table
     * @param db Database name
     * @param table Table name
     * @param where_col Where column
     * @param where_val Where value
     * @param isReplicationAction Whether this is a replication action
     * @return Whether the delete was successful
     */
    boolean delete(String db, String table, String where_col, String where_val, boolean isReplicationAction);
    
    /**
     * Check if a database exists
     * @param db Database name
     * @return Whether the database exists
     */
    boolean databaseExists(String db);
    
    /**
     * Create a new database
     * @param db Database name
     * @return Whether the database was created
     */
    boolean createDatabase(String db);
    
    /**
     * Create a new database
     * @param db Database name
     * @param isReplicationAction Whether this is a replication action
     * @return Whether the database was created
     */
    boolean createDatabase(String db, boolean isReplicationAction);
    
    /**
     * Drop a database
     * @param db Database name
     * @return Whether the database was dropped
     */
    boolean dropDatabase(String db);
    
    /**
     * Create a new table
     * @param db Database name
     * @param table Table name
     * @return Whether the table was created
     */
    boolean createTable(String db, String table);
    
    /**
     * Create a new table
     * @param db Database name
     * @param table Table name
     * @param isReplicationAction Whether this is a replication action
     * @return Whether the table was created
     */
    boolean createTable(String db, String table, boolean isReplicationAction);
    
    /**
     * Check if a table exists
     * @param db Database name
     * @param table Table name
     * @return Whether the table exists
     */
    boolean tableExists(String db, String table);
    
    /**
     * Drop a table
     * @param db Database name
     * @param table Table name
     * @return Whether the table was dropped
     */
    boolean dropTable(String db, String table);
    
    /**
     * Get a list of tables in a database
     * @param db Database name
     * @return List of table names
     */
    List<String> getTables(String db);
    
    /**
     * Delete everything in the storage
     */
    void deleteEverything();
    
    /**
     * Initialize the storage system
     */
    void init();
    
    /**
     * Shutdown the storage system
     */
    void shutdown();
}