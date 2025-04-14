package com.ataiva.serengeti.utils;

import com.ataiva.serengeti.storage.Storage;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.UUID;

/**
 * Utilities for testing storage functionality.
 */
public class StorageTestUtils {
    
    /**
     * Populates a database with test data.
     * 
     * @param storage The storage instance
     * @param db The database name
     * @param table The table name
     * @param numRecords The number of records to create
     * @return The database and table names used
     */
    public static String[] populateTestData(Storage storage, String db, String table, int numRecords) {
        // Create database and table if they don't exist
        if (db == null || db.isEmpty()) {
            db = "test_db_" + UUID.randomUUID().toString().substring(0, 8);
        }
        
        if (table == null || table.isEmpty()) {
            table = "test_table_" + UUID.randomUUID().toString().substring(0, 8);
        }
        
        storage.createDatabase(db);
        storage.createTable(db, table);
        
        Random random = new Random();
        
        // Insert test records
        for (int i = 0; i < numRecords; i++) {
            JSONObject json = new JSONObject();
            json.put("id", i);
            json.put("name", "Test Record " + i);
            json.put("value", random.nextInt(1000));
            json.put("created_at", System.currentTimeMillis());
            
            storage.insert(db, table, json);
        }
        
        return new String[] { db, table };
    }
    
    /**
     * Creates a test record with random data.
     * 
     * @param id The record ID (optional, will be generated if null)
     * @return A JSONObject representing a test record
     */
    public static JSONObject createTestRecord(String id) {
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        
        Random random = new Random();
        
        JSONObject json = new JSONObject();
        json.put("__uuid", id);
        json.put("name", "Test Record " + id.substring(0, 8));
        json.put("value", random.nextInt(1000));
        json.put("created_at", System.currentTimeMillis());
        
        return json;
    }
    
    /**
     * Verifies that a database directory exists.
     * 
     * @param dataPath The base data path
     * @param db The database name
     * @return true if the directory exists, false otherwise
     */
    public static boolean verifyDatabaseDirectory(String dataPath, String db) {
        File dbDir = new File(dataPath + db);
        return dbDir.exists() && dbDir.isDirectory();
    }
    
    /**
     * Verifies that a table directory exists.
     * 
     * @param dataPath The base data path
     * @param db The database name
     * @param table The table name
     * @return true if the directory exists, false otherwise
     */
    public static boolean verifyTableDirectory(String dataPath, String db, String table) {
        File tableDir = new File(dataPath + db + "/" + table);
        return tableDir.exists() && tableDir.isDirectory();
    }
    
    /**
     * Creates a temporary data directory for testing.
     * 
     * @return The path to the temporary directory
     * @throws Exception If an error occurs
     */
    public static String createTempDataDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("serengeti_test_");
        return tempDir.toString() + "/";
    }
    
    /**
     * Deletes a directory and all its contents.
     * 
     * @param path The directory path
     * @return true if successful, false otherwise
     */
    public static boolean deleteDirectory(String path) {
        File directory = new File(path);
        if (!directory.exists()) {
            return true;
        }
        
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file.getAbsolutePath());
                } else {
                    file.delete();
                }
            }
        }
        
        return directory.delete();
    }
    
    /**
     * Compares two JSONObjects for equality, ignoring specific fields.
     * 
     * @param json1 The first JSONObject
     * @param json2 The second JSONObject
     * @param ignoreFields Fields to ignore in the comparison
     * @return true if the objects are equal (ignoring the specified fields), false otherwise
     */
    public static boolean jsonEquals(JSONObject json1, JSONObject json2, String... ignoreFields) {
        if (json1 == null && json2 == null) {
            return true;
        }
        
        if (json1 == null || json2 == null) {
            return false;
        }
        
        // Create copies of the objects to avoid modifying the originals
        JSONObject copy1 = new JSONObject(json1.toString());
        JSONObject copy2 = new JSONObject(json2.toString());
        
        // Remove ignored fields
        for (String field : ignoreFields) {
            copy1.remove(field);
            copy2.remove(field);
        }
        
        return copy1.toString().equals(copy2.toString());
    }
}