package com.ataiva.serengeti.storage.lsm;

import com.ataiva.serengeti.storage.StorageScheduler;
import com.ataiva.serengeti.storage.wal.WALManager;

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
    
    // WAL configuration
    private final WALManager.SyncMode walSyncMode;
    private final long walMaxSize;
    private final int walGroupCommitSize;
    private final long walGroupCommitIntervalMs;
    
    // Base directory for LSM data
    private final Path lsmBaseDirectory;
    
    /**
     * Creates a new LSMStorageScheduler with default compaction settings.
     */
    public LSMStorageScheduler() {
        this(10, 4, 60000, WALManager.SyncMode.GROUP, 64 * 1024 * 1024, 100, 1000);
    }
    
    /**
     * Creates a new LSMStorageScheduler with custom compaction settings.
     * 
     * @param compactionTriggerThreshold Number of SSTables that triggers compaction
     * @param compactionMaxSSTablesToMerge Maximum number of SSTables to merge in one compaction
     * @param compactionIntervalMs Time between compaction checks in milliseconds
     */
    public LSMStorageScheduler(int compactionTriggerThreshold, int compactionMaxSSTablesToMerge, long compactionIntervalMs) {
        this(compactionTriggerThreshold, compactionMaxSSTablesToMerge, compactionIntervalMs,
             WALManager.SyncMode.GROUP, 64 * 1024 * 1024, 100, 1000);
    }
    
    /**
     * Creates a new LSMStorageScheduler with custom compaction and WAL settings.
     *
     * @param compactionTriggerThreshold Number of SSTables that triggers compaction
     * @param compactionMaxSSTablesToMerge Maximum number of SSTables to merge in one compaction
     * @param compactionIntervalMs Time between compaction checks in milliseconds
     * @param walSyncMode WAL sync mode (SYNC, ASYNC, GROUP)
     * @param walMaxSize Maximum size of a WAL file before rotation
     * @param walGroupCommitSize Number of writes before syncing in GROUP mode
     * @param walGroupCommitIntervalMs Time interval for syncing in GROUP mode
     */
    public LSMStorageScheduler(int compactionTriggerThreshold, int compactionMaxSSTablesToMerge,
                              long compactionIntervalMs, WALManager.SyncMode walSyncMode,
                              long walMaxSize, int walGroupCommitSize, long walGroupCommitIntervalMs) {
        super();
        this.lsmEngines = new ConcurrentHashMap<>();
        this.compactionTriggerThreshold = compactionTriggerThreshold;
        this.compactionMaxSSTablesToMerge = compactionMaxSSTablesToMerge;
        this.compactionIntervalMs = compactionIntervalMs;
        this.walSyncMode = walSyncMode;
        this.walMaxSize = walMaxSize;
        this.walGroupCommitSize = walGroupCommitSize;
        this.walGroupCommitIntervalMs = walGroupCommitIntervalMs;
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
                    compactionIntervalMs,
                    walSyncMode
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
    
    /**
     * Gets the current WAL sync mode.
     *
     * @return The WAL sync mode
     */
    public WALManager.SyncMode getWalSyncMode() {
        return walSyncMode;
    }
    
    /**
     * Gets the maximum size of a WAL file before rotation.
     *
     * @return The maximum WAL file size in bytes
     */
    public long getWalMaxSize() {
        return walMaxSize;
    }
    
    /**
     * Gets the number of writes before syncing in GROUP mode.
     *
     * @return The group commit size
     */
    public int getWalGroupCommitSize() {
        return walGroupCommitSize;
    }
    
    /**
     * Gets the time interval for syncing in GROUP mode.
     *
     * @return The group commit interval in milliseconds
     */
    public long getWalGroupCommitIntervalMs() {
        return walGroupCommitIntervalMs;
    }
}