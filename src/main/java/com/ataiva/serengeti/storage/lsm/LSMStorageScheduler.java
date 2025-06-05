package com.ataiva.serengeti.storage.lsm;

import com.ataiva.serengeti.storage.StorageScheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LSMStorageScheduler extends the base StorageScheduler to add LSM-specific
 * functionality, particularly compaction scheduling and management.
 */
public class LSMStorageScheduler extends StorageScheduler {
    
    private static final Logger LOGGER = Logger.getLogger(LSMStorageScheduler.class.getName());
    
    // Map of LSM storage engines by database name
    private final Map<String, LSMStorageEngine> lsmEngines;
    
    // Compaction configuration
    private final int compactionTriggerThreshold;
    private final int compactionMaxSSTablesToMerge;
    private final long compactionIntervalMs;
    
    // Base directory for LSM data
    private final Path lsmBaseDirectory;
    
    /**
     * Creates a new LSMStorageScheduler with default compaction settings.
     */
    public LSMStorageScheduler() {
        this(10, 4, 60000);
    }
    
    /**
     * Creates a new LSMStorageScheduler with custom compaction settings.
     * 
     * @param compactionTriggerThreshold Number of SSTables that triggers compaction
     * @param compactionMaxSSTablesToMerge Maximum number of SSTables to merge in one compaction
     * @param compactionIntervalMs Time between compaction checks in milliseconds
     */
    public LSMStorageScheduler(int compactionTriggerThreshold, int compactionMaxSSTablesToMerge, long compactionIntervalMs) {
        super();
        this.lsmEngines = new ConcurrentHashMap<>();
        this.compactionTriggerThreshold = compactionTriggerThreshold;
        this.compactionMaxSSTablesToMerge = compactionMaxSSTablesToMerge;
        this.compactionIntervalMs = compactionIntervalMs;
        this.lsmBaseDirectory = Paths.get("data", "lsm");
    }
    
    /**
     * Initializes the LSM storage scheduler, starting background threads for
     * persistence and compaction.
     */
    @Override
    public void init() {
        // Call the parent init method to start the persistence thread
        super.init();
        
        // Start a separate thread for LSM-specific operations
        new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(compactionIntervalMs);
                    System.out.println("LSMStorageScheduler checking compaction needs...");
                    checkCompactionNeeds();
                    System.out.println("LSMStorageScheduler compaction check completed\n");
                }
            } catch (InterruptedException ie) {
                LOGGER.log(Level.WARNING, "LSM compaction thread interrupted", ie);
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    /**
     * Gets or creates an LSM storage engine for a database.
     * 
     * @param dbName The database name
     * @return The LSM storage engine
     * @throws IOException If an I/O error occurs
     */
    public LSMStorageEngine getLSMEngine(String dbName) throws IOException {
        return lsmEngines.computeIfAbsent(dbName, name -> {
            try {
                Path dbPath = lsmBaseDirectory.resolve(name);
                return new LSMStorageEngine(
                    dbPath,
                    1024 * 1024, // 1MB memtable size
                    2, // Max immutable memtables
                    compactionTriggerThreshold,
                    compactionMaxSSTablesToMerge,
                    compactionIntervalMs
                );
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to create LSM engine for database: " + name, e);
                throw new RuntimeException("Failed to create LSM engine", e);
            }
        });
    }
    
    /**
     * Checks if any LSM engines need compaction and triggers it if necessary.
     */
    private void checkCompactionNeeds() {
        System.out.println(" * Checking compaction needs at " + new Date());
        
        for (Map.Entry<String, LSMStorageEngine> entry : lsmEngines.entrySet()) {
            String dbName = entry.getKey();
            LSMStorageEngine engine = entry.getValue();
            
            try {
                // Trigger compaction by notifying the compaction thread
                // The actual decision to compact is made in the compactionLoop method
                System.out.println(" * Triggering compaction check for database: " + dbName);
                engine.triggerCompactionCheck();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error triggering compaction for database: " + dbName, e);
            }
        }
    }
    
    /**
     * Closes all LSM engines, ensuring data is properly flushed.
     */
    public void close() {
        for (Map.Entry<String, LSMStorageEngine> entry : lsmEngines.entrySet()) {
            String dbName = entry.getKey();
            LSMStorageEngine engine = entry.getValue();
            
            try {
                System.out.println(" * Closing LSM engine for database: " + dbName);
                engine.close();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error closing LSM engine for database: " + dbName, e);
            }
        }
    }
}