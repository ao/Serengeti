package com.ataiva.serengeti.testdata;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for cleaning up test data after StorageScheduler tests.
 */
public class StorageSchedulerTestDataCleaner {

    /**
     * Cleans up the Storage static fields by restoring the original state.
     *
     * @param originalState The original state to restore
     */
    public static void restoreStorageState(Map<String, Object> originalState) {
        if (originalState == null) {
            // If no original state is provided, just clear everything
            Storage.databases = new HashMap<>();
            Storage.tableStorageObjects = new HashMap<>();
            Storage.tableReplicaObjects = new HashMap<>();
            Network.online = true;
            return;
        }
        
        Storage.databases = (Map<String, DatabaseObject>) originalState.get("databases");
        Storage.tableStorageObjects = (Map<String, TableStorageObject>) originalState.get("tableStorageObjects");
        Storage.tableReplicaObjects = (Map<String, TableReplicaObject>) originalState.get("tableReplicaObjects");
        Network.online = (boolean) originalState.get("networkOnline");
    }
    
    /**
     * Cleans up all test data files from the data directory.
     *
     * @param dataPath The data directory path
     * @return true if cleanup was successful, false otherwise
     */
    public static boolean cleanupDataFiles(String dataPath) {
        File dataDir = new File(dataPath);
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            return true; // Nothing to clean up
        }
        
        return deleteDirectory(dataDir);
    }
    
    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param directory The directory to delete
     * @return true if successful, false otherwise
     */
    private static boolean deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        return directory.delete();
    }
    
    /**
     * Cleans up test data for a specific database.
     *
     * @param dataPath The data directory path
     * @param dbName The database name
     * @return true if cleanup was successful, false otherwise
     */
    public static boolean cleanupDatabase(String dataPath, String dbName) {
        // Delete database metadata file
        File dbFile = new File(dataPath + dbName + Globals.meta_extention);
        if (dbFile.exists()) {
            dbFile.delete();
        }
        
        // Delete database directory
        File dbDir = new File(dataPath + dbName);
        if (dbDir.exists() && dbDir.isDirectory()) {
            return deleteDirectory(dbDir);
        }
        
        return true;
    }
    
    /**
     * Cleans up test data for a specific table.
     *
     * @param dataPath The data directory path
     * @param dbName The database name
     * @param tableName The table name
     * @return true if cleanup was successful, false otherwise
     */
    public static boolean cleanupTable(String dataPath, String dbName, String tableName) {
        File tableDir = new File(dataPath + dbName + "/" + tableName);
        if (tableDir.exists() && tableDir.isDirectory()) {
            return deleteDirectory(tableDir);
        }
        
        return true;
    }
    
    /**
     * Verifies that test data has been properly cleaned up.
     *
     * @param dataPath The data directory path
     * @param dbName The database name
     * @return true if cleanup was successful, false otherwise
     */
    public static boolean verifyDatabaseCleanup(String dataPath, String dbName) {
        // Check database metadata file
        File dbFile = new File(dataPath + dbName + Globals.meta_extention);
        if (dbFile.exists()) {
            return false;
        }
        
        // Check database directory
        File dbDir = new File(dataPath + dbName);
        return !dbDir.exists();
    }
    
    /**
     * Verifies that test data has been properly cleaned up.
     *
     * @param dataPath The data directory path
     * @param dbName The database name
     * @param tableName The table name
     * @return true if cleanup was successful, false otherwise
     */
    public static boolean verifyTableCleanup(String dataPath, String dbName, String tableName) {
        File tableDir = new File(dataPath + dbName + "/" + tableName);
        return !tableDir.exists();
    }
    
    /**
     * Creates a temporary data directory for testing and returns the path.
     *
     * @return The path to the temporary directory
     * @throws IOException If an error occurs
     */
    public static String createTempDataDirectory() throws IOException {
        Path tempDir = Files.createTempDirectory("storage_scheduler_test_");
        return tempDir.toString() + File.separator;
    }
    
    /**
     * Sets the Globals.data_path to a temporary directory for testing.
     *
     * @return The original data path for restoration
     * @throws Exception If an error occurs
     */
    public static String setTempDataPath() throws Exception {
        String originalDataPath = getGlobalsDataPath();
        String tempDataPath = createTempDataDirectory();
        setGlobalsDataPath(tempDataPath);
        return originalDataPath;
    }
    
    /**
     * Restores the original Globals.data_path.
     *
     * @param originalDataPath The original data path to restore
     * @throws Exception If an error occurs
     */
    public static void restoreDataPath(String originalDataPath) throws Exception {
        setGlobalsDataPath(originalDataPath);
    }
    
    /**
     * Gets the current data path from Globals.
     *
     * @return The current data path
     * @throws Exception If an error occurs
     */
    private static String getGlobalsDataPath() throws Exception {
        java.lang.reflect.Field field = Globals.class.getDeclaredField("data_path");
        field.setAccessible(true);
        return (String) field.get(null);
    }
    
    /**
     * Sets the data path in Globals.
     *
     * @param path The new data path
     * @throws Exception If an error occurs
     */
    private static void setGlobalsDataPath(String path) throws Exception {
        java.lang.reflect.Field field = Globals.class.getDeclaredField("data_path");
        field.setAccessible(true);
        field.set(null, path);
    }
}