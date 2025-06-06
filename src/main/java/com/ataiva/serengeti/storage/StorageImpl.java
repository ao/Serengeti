package com.ataiva.serengeti.storage;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.lsm.LSMStorageEngine;
import com.ataiva.serengeti.storage.lsm.LSMStorageScheduler;
import com.ataiva.serengeti.query.QueryLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of the Storage component for the Serengeti distributed database system.
 * This class provides a robust implementation with improved error handling, performance,
 * and integration with the LSM storage engine.
 */
public class StorageImpl extends Storage {

    private static final Logger LOGGER = Logger.getLogger(StorageImpl.class.getName());
    
    // LSM storage engines for each database
    private final Map<String, LSMStorageEngine> lsmEngines;
    
    // LSM storage scheduler
    private final LSMStorageScheduler lsmScheduler;
    
    // Cache for frequently accessed data
    private final Map<String, byte[]> dataCache;
    private final int maxCacheSize;
    private final boolean cacheEnabled;
    
    // Error tracking
    private final Map<String, Integer> errorCounts;
    private boolean isHealthy = true;
    
    /**
     * Constructor with default configuration.
     */
    public StorageImpl() {
        this(true, 1000);
    }
    
    /**
     * Constructor with custom configuration.
     * 
     * @param cacheEnabled Whether to enable data caching
     * @param maxCacheSize Maximum number of items in the cache
     */
    public StorageImpl(boolean cacheEnabled, int maxCacheSize) {
        super(); // Call the parent constructor to initialize basic structures
        
        this.cacheEnabled = cacheEnabled;
        this.maxCacheSize = maxCacheSize;
        this.dataCache = cacheEnabled ? new ConcurrentHashMap<>() : null;
        this.errorCounts = new ConcurrentHashMap<>();
        this.lsmEngines = new ConcurrentHashMap<>();
        this.lsmScheduler = new LSMStorageScheduler();
        
        LOGGER.info("StorageImpl initialized with configuration: " +
                "cacheEnabled=" + cacheEnabled +
                ", maxCacheSize=" + maxCacheSize);
    }
    
    /**
     * Initialize the storage implementation.
     */
    public void init() {
        LOGGER.info("Initializing StorageImpl");
        
        try {
            // Initialize the LSM scheduler
            lsmScheduler.init();
            
            // Ensure data directory exists
            File dataDir = new File(Globals.data_path);
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }
            
            // Load metadata and objects
            loadMetaDatabasesToMemory();
            loadAllStorageObjectsToMemory();
            loadAllReplicaObjectsToMemory();
            
            LOGGER.info("StorageImpl initialized successfully");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize StorageImpl", e);
            recordError("InitializationError");
            isHealthy = false;
        }
    }
    
    /**
     * Shutdown the storage implementation.
     */
    public void shutdown() {
        LOGGER.info("Shutting down StorageImpl");
        
        try {
            // Close all LSM engines
            for (Map.Entry<String, LSMStorageEngine> entry : lsmEngines.entrySet()) {
                try {
                    entry.getValue().close();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error closing LSM engine for database: " + entry.getKey(), e);
                }
            }
            
            // Clear caches
            if (dataCache != null) {
                dataCache.clear();
            }
            
            LOGGER.info("StorageImpl shutdown complete");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during StorageImpl shutdown", e);
        }
    }
    
    /**
     * Get an LSM storage engine for a database.
     * 
     * @param dbName The database name
     * @return The LSM storage engine
     */
    public LSMStorageEngine getLSMEngine(String dbName) {
        try {
            return lsmScheduler.getLSMEngine(dbName);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to get LSM engine for database: " + dbName, e);
            recordError("LSMEngineError");
            return null;
        }
    }
    
    /**
     * Record an error.
     * 
     * @param errorType The type of error
     */
    private void recordError(String errorType) {
        errorCounts.compute(errorType, (k, v) -> v == null ? 1 : v + 1);
        
        // Log the error count
        LOGGER.warning("Error count for " + errorType + ": " + errorCounts.get(errorType));
        
        // Check if we should mark the system as unhealthy
        if (errorCounts.get(errorType) >= 10) {
            isHealthy = false;
            LOGGER.severe("System marked as unhealthy due to excessive " + errorType + " errors");
        }
    }
    
    /**
     * Invalidate cache entries for a database.
     * 
     * @param db The database name
     */
    private void invalidateCache(String db) {
        if (cacheEnabled && dataCache != null) {
            // Remove all cache entries for this database
            dataCache.keySet().removeIf(key -> key.startsWith(db + "#"));
            LOGGER.fine("Cache invalidated for database: " + db);
        }
    }
    
    /**
     * Invalidate cache entries for a table.
     * 
     * @param db The database name
     * @param table The table name
     */
    private void invalidateCache(String db, String table) {
        if (cacheEnabled && dataCache != null) {
            // Remove all cache entries for this table
            dataCache.keySet().removeIf(key -> key.startsWith(db + "#" + table + "#"));
            LOGGER.fine("Cache invalidated for table: " + db + "." + table);
        }
    }
    
    /**
     * Check if the storage implementation is healthy.
     * 
     * @return true if healthy, false otherwise
     */
    public boolean isHealthy() {
        return isHealthy;
    }
    
    /**
     * Get error metrics.
     * 
     * @return A map of error types and counts
     */
    public Map<String, Integer> getErrorMetrics() {
        return new HashMap<>(errorCounts);
    }
    
    /**
     * Reset error metrics.
     */
    public void resetErrorMetrics() {
        errorCounts.clear();
        isHealthy = true;
        LOGGER.info("Error metrics reset");
    }
}