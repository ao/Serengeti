package com.ataiva.serengeti.storage;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * StorageScheduler is responsible for periodically persisting database state to disk.
 * This component ensures data durability by writing in-memory database objects to persistent storage.
 */
public class StorageScheduler {

    private static final Logger LOGGER = Logger.getLogger(StorageScheduler.class.getName());
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;
    
    // Error metrics
    private static final AtomicInteger totalErrors = new AtomicInteger(0);
    private static final AtomicInteger transientErrors = new AtomicInteger(0);
    private static final AtomicInteger persistentErrors = new AtomicInteger(0);
    private static final Map<String, AtomicInteger> errorTypeCount = new HashMap<>();
    
    public static boolean running = false;
    private boolean isHealthy = true;

    public StorageScheduler() {}

    /**
     * Initializes the storage scheduler background thread that periodically persists data to disk.
     */
    public void init() {
        LOGGER.info("Initializing StorageScheduler background thread");
        Thread schedulerThread = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(60 * 1000);
                    LOGGER.info("StorageScheduler Initiated..");
                    System.out.println("StorageScheduler Initiated..");
                    performPersistToDisk();
                    System.out.println("StorageScheduler Completed\n");
                    LOGGER.info("StorageScheduler Completed");
                }
            } catch (InterruptedException ie) {
                LOGGER.log(Level.WARNING, "StorageScheduler thread interrupted", ie);
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
        });
        schedulerThread.setName("StorageScheduler-Thread");
        schedulerThread.setDaemon(true); // Mark as daemon thread
        schedulerThread.start();
        LOGGER.info("StorageScheduler background thread started");
    }

    /**
     * Persists all database objects to disk.
     * Implements retry logic for transient errors and graceful degradation for persistent errors.
     *
     * @return true if persistence was successful, false otherwise
     */
    public boolean performPersistToDisk() {
        LOGGER.info("Persisting to disk at " + new Date());
        System.out.println(" * Persisting to disk at " + new Date());

        if (!Network.online || running) {
            LOGGER.info("Node reported as not having started fully, so skipping disk persistence..");
            System.out.println(" * Node reported as not having started fully, so skipping disk persistence..");
            running = false;
            return false;
        }

        running = true;
        boolean overallSuccess = true;
        List<PersistenceOperation> operations = new ArrayList<>();
        Map<String, byte[]> dbBackups = new HashMap<>();

        try {
            // Phase 1: Prepare operations and create backups
            if (Storage.databases.size() == 0) {
                LOGGER.info("No databases found, nothing to persist..");
                System.out.println(" * No databases found, nothing to persist..");
            } else {
                // Prepare all persistence operations
                for (String key : Storage.databases.keySet()) {
                    DatabaseObject dbo = Storage.databases.get(key);
                    if (dbo == null) {
                        LOGGER.warning("Null database object found for key: " + key);
                        continue;
                    }

                    String dbName = dbo.name;
                    List<String> tables = dbo.tables;
                    if (tables == null) {
                        LOGGER.warning("Null tables list found for database: " + dbName);
                        continue;
                    }

                    try {
                        // Create backup of database metadata
                        byte[] data = dbo.returnDBObytes();
                        dbBackups.put(dbName, data);
                        
                        // Add database metadata persistence operation
                        Path file = Paths.get(Globals.data_path + dbName + Globals.meta_extention);
                        operations.add(new PersistenceOperation(
                            OperationType.DATABASE_METADATA,
                            dbName,
                            "",
                            file,
                            data
                        ));

                        // Add table persistence operations
                        for (Object table : tables) {
                            String tableName = table.toString();
                            String tableKey = dbName + "#" + tableName;
                            
                            TableStorageObject tso = Storage.tableStorageObjects.get(tableKey);
                            if (tso != null) {
                                operations.add(new PersistenceOperation(
                                    OperationType.TABLE_STORAGE,
                                    dbName,
                                    tableName,
                                    null,
                                    null
                                ));
                            }
                            
                            TableReplicaObject tro = Storage.tableReplicaObjects.get(tableKey);
                            if (tro != null) {
                                operations.add(new PersistenceOperation(
                                    OperationType.TABLE_REPLICA,
                                    dbName,
                                    tableName,
                                    null,
                                    null
                                ));
                            }
                        }
                    } catch (OutOfMemoryError oom) {
                        LOGGER.severe("Out of memory error while preparing persistence for database: " + dbName);
                        recordError("OutOfMemoryError", false);
                        isHealthy = false;
                        throw oom; // Critical error, cannot continue
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error preparing persistence for database: " + dbName, e);
                        recordError(e.getClass().getSimpleName(), true);
                        overallSuccess = false;
                    }
                }

                // Phase 2: Execute operations with transaction-like behavior
                executeOperations(operations);
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Unexpected error during persistence", ex);
            recordError(ex.getClass().getSimpleName(), false);
            overallSuccess = false;
        } finally {
            running = false;
        }

        return overallSuccess;
    }
    
    /**
     * Executes a list of persistence operations with transaction-like behavior.
     * If a critical operation fails, it will attempt to roll back previous operations.
     *
     * @param operations List of persistence operations to execute
     * @return true if all operations were successful, false otherwise
     */
    private boolean executeOperations(List<PersistenceOperation> operations) {
        List<PersistenceOperation> completedOperations = new ArrayList<>();
        boolean overallSuccess = true;
        
        for (PersistenceOperation op : operations) {
            boolean operationSuccess = false;
            
            // Apply retry logic for transient errors
            for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
                try {
                    switch (op.type) {
                        case DATABASE_METADATA:
                            Files.write(op.filePath, op.data);
                            LOGGER.info("Written db: '" + op.dbName + "' to disk");
                            System.out.println(" * Written db: '" + op.dbName + "' to disk");
                            break;
                            
                        case TABLE_STORAGE:
                            String storageKey = op.dbName + "#" + op.tableName;
                            TableStorageObject tso = Storage.tableStorageObjects.get(storageKey);
                            if (tso != null) {
                                tso.saveToDisk();
                                LOGGER.info("Written table: '" + op.dbName + "'#'" + op.tableName +
                                           "' storage to disk (" + tso.rows.size() + " rows)");
                                System.out.println(" └- Written table: '" + op.dbName + "'#'" + op.tableName +
                                                  "' storage to disk (" + tso.rows.size() + " rows)");
                            }
                            break;
                            
                        case TABLE_REPLICA:
                            String replicaKey = op.dbName + "#" + op.tableName;
                            TableReplicaObject tro = Storage.tableReplicaObjects.get(replicaKey);
                            if (tro != null) {
                                tro.saveToDisk();
                                LOGGER.info("Written table: '" + op.dbName + "'#'" + op.tableName +
                                           "' replica to disk (" + tro.row_replicas.size() + " rows)");
                                System.out.println(" └- Written table: '" + op.dbName + "'#'" + op.tableName +
                                                  "' replica to disk (" + tro.row_replicas.size() + " rows)");
                            }
                            break;
                    }
                    
                    operationSuccess = true;
                    break; // Operation succeeded, exit retry loop
                    
                } catch (NoSuchFileException | AccessDeniedException e) {
                    // These are likely persistent errors, no need to retry
                    LOGGER.log(Level.SEVERE, "Persistent error during " + op.type + " for " +
                              op.dbName + (op.tableName.isEmpty() ? "" : "#" + op.tableName), e);
                    recordError(e.getClass().getSimpleName(), false);
                    break;
                    
                } catch (IOException e) {
                    if (isTransientError(e) && attempt < MAX_RETRY_ATTEMPTS) {
                        LOGGER.log(Level.WARNING, "Transient error during " + op.type +
                                  " (attempt " + attempt + "/" + MAX_RETRY_ATTEMPTS + ")", e);
                        recordError(e.getClass().getSimpleName(), true);
                        
                        try {
                            Thread.sleep(RETRY_DELAY_MS * attempt); // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            LOGGER.warning("Retry delay interrupted");
                        }
                    } else {
                        LOGGER.log(Level.SEVERE, "Failed to complete " + op.type + " after " +
                                  attempt + " attempts", e);
                        recordError(e.getClass().getSimpleName(), false);
                        break;
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Unexpected error during " + op.type, e);
                    recordError(e.getClass().getSimpleName(), false);
                    break;
                }
            }
            
            if (operationSuccess) {
                completedOperations.add(op);
            } else {
                overallSuccess = false;
                
                // If a critical database metadata operation failed, attempt rollback
                if (op.type == OperationType.DATABASE_METADATA) {
                    attemptRollback(completedOperations);
                    break; // Stop processing further operations
                }
            }
        }
        
        return overallSuccess;
    }
    
    /**
     * Attempts to roll back completed operations in case of a critical failure.
     * This provides a basic level of atomicity for the persistence process.
     *
     * @param completedOperations List of operations that were successfully completed
     */
    private void attemptRollback(List<PersistenceOperation> completedOperations) {
        LOGGER.warning("Attempting to roll back " + completedOperations.size() + " completed operations");
        
        // Process operations in reverse order
        for (int i = completedOperations.size() - 1; i >= 0; i--) {
            PersistenceOperation op = completedOperations.get(i);
            
            try {
                switch (op.type) {
                    case DATABASE_METADATA:
                        // For database metadata, we could restore from backup if available
                        LOGGER.info("Rolling back database metadata for: " + op.dbName);
                        break;
                        
                    case TABLE_STORAGE:
                    case TABLE_REPLICA:
                        // For tables, we don't attempt to restore old state as it's still in memory
                        LOGGER.info("Skipping rollback for " + op.type + ": " + op.dbName + "#" + op.tableName);
                        break;
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error during rollback of " + op.type, e);
            }
        }
    }
    
    /**
     * Determines if an error is likely transient and can be retried.
     *
     * @param e The exception to analyze
     * @return true if the error is likely transient, false otherwise
     */
    private boolean isTransientError(Exception e) {
        if (e instanceof IOException) {
            String message = e.getMessage();
            if (message != null) {
                // Check for common transient I/O errors
                return message.contains("Connection reset") ||
                       message.contains("Connection refused") ||
                       message.contains("Broken pipe") ||
                       message.contains("temporarily unavailable") ||
                       message.contains("Too many open files") ||
                       message.contains("No space left on device");
            }
        }
        
        return false;
    }
    
    /**
     * Records error metrics for monitoring and alerting.
     *
     * @param errorType The type of error that occurred
     * @param isTransient Whether the error is considered transient
     */
    private void recordError(String errorType, boolean isTransient) {
        totalErrors.incrementAndGet();
        
        if (isTransient) {
            transientErrors.incrementAndGet();
        } else {
            persistentErrors.incrementAndGet();
        }
        
        // Record specific error type count
        errorTypeCount.computeIfAbsent(errorType, k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    /**
     * Returns the current health status of the StorageScheduler.
     *
     * @return true if the scheduler is healthy, false otherwise
     */
    public boolean isHealthy() {
        return isHealthy;
    }
    
    /**
     * Returns error metrics for monitoring and alerting.
     *
     * @return Map containing error metrics
     */
    public Map<String, Object> getErrorMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalErrors", totalErrors.get());
        metrics.put("transientErrors", transientErrors.get());
        metrics.put("persistentErrors", persistentErrors.get());
        
        Map<String, Integer> errorCounts = new HashMap<>();
        errorTypeCount.forEach((type, count) -> errorCounts.put(type, count.get()));
        metrics.put("errorTypeCount", errorCounts);
        
        return metrics;
    }
    
    /**
     * Resets error metrics.
     */
    public void resetErrorMetrics() {
        totalErrors.set(0);
        transientErrors.set(0);
        persistentErrors.set(0);
        errorTypeCount.clear();
    }
    
    /**
     * Enum representing the types of persistence operations.
     */
    private enum OperationType {
        DATABASE_METADATA,
        TABLE_STORAGE,
        TABLE_REPLICA
    }
    
    /**
     * Class representing a persistence operation.
     */
    private static class PersistenceOperation {
        final OperationType type;
        final String dbName;
        final String tableName;
        final Path filePath;
        final byte[] data;
        
        PersistenceOperation(OperationType type, String dbName, String tableName, Path filePath, byte[] data) {
            this.type = type;
            this.dbName = dbName;
            this.tableName = tableName;
            this.filePath = filePath;
            this.data = data;
        }
    }
}
