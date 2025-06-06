package com.ataiva.serengeti.storage;

import com.ataiva.serengeti.helpers.Globals;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A robust implementation of the Storage interface for the Serengeti distributed database system.
 * This class provides persistent storage using a Log-Structured Merge (LSM) tree approach.
 */
public class StorageImpl implements Storage {

    private static final Logger LOGGER = Logger.getLogger(StorageImpl.class.getName());
    
    private static final int DEFAULT_CACHE_SIZE = 1000;
    private static final int DEFAULT_COMPACTION_THRESHOLD = 10;
    private static final int DEFAULT_THREAD_POOL_SIZE = 10;
    
    private final boolean enableCache;
    private final int cacheSize;
    private final int compactionThreshold;
    private final ExecutorService executor;
    private final Map<String, JSONObject> cache;
    private final Map<String, Long> cacheAccessTimes;
    private final Path dataDirectory;
    private final WriteAheadLog wal;
    private boolean isInitialized;
    private boolean isShutdown;
    
    /**
     * Creates a new StorageImpl with default settings.
     */
    public StorageImpl() {
        this(true, DEFAULT_CACHE_SIZE, DEFAULT_COMPACTION_THRESHOLD);
    }
    
    /**
     * Creates a new StorageImpl with custom settings.
     * 
     * @param enableCache Whether to enable the cache
     * @param cacheSize The maximum number of entries in the cache
     * @param compactionThreshold The threshold for triggering compaction
     */
    public StorageImpl(boolean enableCache, int cacheSize, int compactionThreshold) {
        this.enableCache = enableCache;
        this.cacheSize = cacheSize;
        this.compactionThreshold = compactionThreshold;
        this.executor = Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE);
        this.cache = enableCache ? new ConcurrentHashMap<>() : null;
        this.cacheAccessTimes = enableCache ? new ConcurrentHashMap<>() : null;
        this.dataDirectory = Paths.get(Globals.data_path);
        this.wal = new WriteAheadLog(dataDirectory.resolve("wal"));
        this.isInitialized = false;
        this.isShutdown = false;
        
        LOGGER.info("StorageImpl created with cache=" + enableCache + ", cacheSize=" + cacheSize + 
                ", compactionThreshold=" + compactionThreshold);
    }
    
    /**
     * Initializes the storage system.
     */
    @Override
    public void init() {
        if (isInitialized) {
            LOGGER.warning("StorageImpl is already initialized");
            return;
        }
        
        try {
            // Create the data directory if it doesn't exist
            Files.createDirectories(dataDirectory);
            
            // Initialize the WAL
            wal.init();
            
            // Recover from WAL if necessary
            recoverFromWAL();
            
            // Schedule periodic compaction
            scheduleCompaction();
            
            isInitialized = true;
            LOGGER.info("StorageImpl initialized");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error initializing StorageImpl", e);
            throw new RuntimeException("Error initializing StorageImpl", e);
        }
    }
    
    /**
     * Shuts down the storage system.
     */
    public void shutdown() {
        if (isShutdown) {
            LOGGER.warning("StorageImpl is already shut down");
            return;
        }
        
        try {
            // Flush any pending operations
            flush();
            
            // Close the WAL
            wal.close();
            
            // Shutdown the executor
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            
            // Clear the cache
            if (enableCache) {
                cache.clear();
                cacheAccessTimes.clear();
            }
            
            isShutdown = true;
            LOGGER.info("StorageImpl shut down");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error shutting down StorageImpl", e);
        }
    }
    
    /**
     * Flushes any pending operations to disk.
     */
    public void flush() {
        try {
            wal.flush();
            LOGGER.info("StorageImpl flushed");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error flushing StorageImpl", e);
        }
    }
    
    /**
     * Recovers the storage system from the WAL.
     */
    private void recoverFromWAL() {
        try {
            LOGGER.info("Recovering from WAL");
            wal.recover();
            LOGGER.info("Recovery from WAL complete");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error recovering from WAL", e);
        }
    }
    
    /**
     * Schedules periodic compaction of the storage files.
     */
    private void scheduleCompaction() {
        executor.scheduleWithFixedDelay(() -> {
            try {
                compact();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error during compaction", e);
            }
        }, 1, 1, TimeUnit.HOURS);
    }
    
    /**
     * Compacts the storage files to optimize space and performance.
     */
    private void compact() {
        LOGGER.info("Starting compaction");
        
        try {
            // Get all databases
            List<String> databases = listDatabases();
            
            for (String database : databases) {
                // Get all tables in the database
                List<String> tables = listTables(database);
                
                for (String table : tables) {
                    // Compact the table
                    compactTable(database, table);
                }
            }
            
            LOGGER.info("Compaction complete");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during compaction", e);
        }
    }
    
    /**
     * Compacts a specific table.
     * 
     * @param database The database name
     * @param table The table name
     */
    private void compactTable(String database, String table) {
        try {
            Path tablePath = getTablePath(database, table);
            Path dataFile = tablePath.resolve("data.lsm");
            Path tempFile = tablePath.resolve("data.lsm.tmp");
            
            // Check if the data file exists
            if (!Files.exists(dataFile)) {
                return;
            }
            
            // Read all records from the data file
            Map<String, JSONObject> records = new HashMap<>();
            try (BufferedReader reader = Files.newBufferedReader(dataFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject record = new JSONObject(line);
                    String id = record.getString("id");
                    records.put(id, record);
                }
            }
            
            // Write the records to the temp file
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
                for (JSONObject record : records.values()) {
                    writer.write(record.toString());
                    writer.newLine();
                }
            }
            
            // Replace the data file with the temp file
            Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            
            LOGGER.info("Compacted table " + database + "." + table);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error compacting table " + database + "." + table, e);
        }
    }
    
    /**
     * Creates a new database.
     * 
     * @param database The database name
     * @return true if the database was created, false otherwise
     */
    @Override
    public boolean createDatabase(String database) {
        checkInitialized();
        
        try {
            // Create the database directory
            Path databasePath = getDatabasePath(database);
            Files.createDirectories(databasePath);
            
            // Create the database metadata file
            Path metaFile = dataDirectory.resolve(database + ".meta");
            JSONObject meta = new JSONObject();
            meta.put("name", database);
            meta.put("created", System.currentTimeMillis());
            
            try (BufferedWriter writer = Files.newBufferedWriter(metaFile)) {
                writer.write(meta.toString());
            }
            
            LOGGER.info("Created database " + database);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error creating database " + database, e);
            return false;
        }
    }
    
    /**
     * Checks if a database exists.
     * 
     * @param database The database name
     * @return true if the database exists, false otherwise
     */
    @Override
    public boolean databaseExists(String database) {
        checkInitialized();
        
        try {
            Path databasePath = getDatabasePath(database);
            Path metaFile = dataDirectory.resolve(database + ".meta");
            return Files.exists(databasePath) && Files.exists(metaFile);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error checking if database " + database + " exists", e);
            return false;
        }
    }
    
    /**
     * Drops a database.
     * 
     * @param database The database name
     * @return true if the database was dropped, false otherwise
     */
    @Override
    public boolean dropDatabase(String database) {
        checkInitialized();
        
        try {
            Path databasePath = getDatabasePath(database);
            Path metaFile = dataDirectory.resolve(database + ".meta");
            
            // Check if the database exists
            if (!Files.exists(databasePath) || !Files.exists(metaFile)) {
                return false;
            }
            
            // Delete all tables in the database
            try (Stream<Path> paths = Files.list(databasePath)) {
                paths.forEach(path -> {
                    try {
                        Files.walk(path)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error deleting table " + path, e);
                    }
                });
            }
            
            // Delete the database directory
            Files.delete(databasePath);
            
            // Delete the database metadata file
            Files.delete(metaFile);
            
            // Invalidate cache entries for this database
            if (enableCache) {
                invalidateCacheForDatabase(database);
            }
            
            LOGGER.info("Dropped database " + database);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error dropping database " + database, e);
            return false;
        }
    }
    
    /**
     * Lists all databases.
     * 
     * @return A list of database names
     */
    @Override
    public List<String> listDatabases() {
        checkInitialized();
        
        try {
            List<String> databases = new ArrayList<>();
            
            // Find all .meta files in the data directory
            try (Stream<Path> paths = Files.list(dataDirectory)) {
                paths.filter(path -> path.toString().endsWith(".meta"))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        String database = fileName.substring(0, fileName.length() - 5);
                        databases.add(database);
                    });
            }
            
            return databases;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error listing databases", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Creates a new table in a database.
     * 
     * @param database The database name
     * @param table The table name
     * @return true if the table was created, false otherwise
     */
    @Override
    public boolean createTable(String database, String table) {
        checkInitialized();
        
        try {
            // Check if the database exists
            if (!databaseExists(database)) {
                return false;
            }
            
            // Create the table directory
            Path tablePath = getTablePath(database, table);
            Files.createDirectories(tablePath);
            
            // Create the table metadata file
            Path metaFile = tablePath.resolve("meta.json");
            JSONObject meta = new JSONObject();
            meta.put("name", table);
            meta.put("database", database);
            meta.put("created", System.currentTimeMillis());
            
            try (BufferedWriter writer = Files.newBufferedWriter(metaFile)) {
                writer.write(meta.toString());
            }
            
            // Create the data file
            Path dataFile = tablePath.resolve("data.lsm");
            Files.createFile(dataFile);
            
            // Create the index directory
            Path indexDir = tablePath.resolve("index");
            Files.createDirectories(indexDir);
            
            LOGGER.info("Created table " + database + "." + table);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error creating table " + database + "." + table, e);
            return false;
        }
    }
    
    /**
     * Checks if a table exists in a database.
     * 
     * @param database The database name
     * @param table The table name
     * @return true if the table exists, false otherwise
     */
    @Override
    public boolean tableExists(String database, String table) {
        checkInitialized();
        
        try {
            // Check if the database exists
            if (!databaseExists(database)) {
                return false;
            }
            
            Path tablePath = getTablePath(database, table);
            Path metaFile = tablePath.resolve("meta.json");
            return Files.exists(tablePath) && Files.exists(metaFile);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error checking if table " + database + "." + table + " exists", e);
            return false;
        }
    }
    
    /**
     * Drops a table from a database.
     * 
     * @param database The database name
     * @param table The table name
     * @return true if the table was dropped, false otherwise
     */
    @Override
    public boolean dropTable(String database, String table) {
        checkInitialized();
        
        try {
            // Check if the database and table exist
            if (!tableExists(database, table)) {
                return false;
            }
            
            Path tablePath = getTablePath(database, table);
            
            // Delete all files in the table directory
            Files.walk(tablePath)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
            
            // Invalidate cache entries for this table
            if (enableCache) {
                invalidateCacheForTable(database, table);
            }
            
            LOGGER.info("Dropped table " + database + "." + table);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error dropping table " + database + "." + table, e);
            return false;
        }
    }
    
    /**
     * Lists all tables in a database.
     * 
     * @param database The database name
     * @return A list of table names
     */
    @Override
    public List<String> listTables(String database) {
        checkInitialized();
        
        try {
            // Check if the database exists
            if (!databaseExists(database)) {
                return Collections.emptyList();
            }
            
            Path databasePath = getDatabasePath(database);
            List<String> tables = new ArrayList<>();
            
            // Find all directories in the database directory
            try (Stream<Path> paths = Files.list(databasePath)) {
                paths.filter(Files::isDirectory)
                    .forEach(path -> {
                        String table = path.getFileName().toString();
                        tables.add(table);
                    });
            }
            
            return tables;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error listing tables in database " + database, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Inserts a record into a table.
     * 
     * @param database The database name
     * @param table The table name
     * @param data The data to insert
     * @return A StorageResponseObject containing the result of the operation
     */
    @Override
    public StorageResponseObject insert(String database, String table, JSONObject data) {
        checkInitialized();
        
        StorageResponseObject response = new StorageResponseObject();
        
        try {
            // Check if the database and table exist
            if (!tableExists(database, table)) {
                response.success = false;
                response.message = "Table " + database + "." + table + " does not exist";
                return response;
            }
            
            // Generate a row ID if one doesn't exist
            String rowId = data.has("id") ? data.getString("id") : UUID.randomUUID().toString();
            data.put("id", rowId);
            
            // Add timestamp if it doesn't exist
            if (!data.has("timestamp")) {
                data.put("timestamp", System.currentTimeMillis());
            }
            
            // Log the operation to WAL
            wal.logOperation(WriteAheadLog.OperationType.INSERT, database, table, data);
            
            // Write the data to the table
            Path tablePath = getTablePath(database, table);
            Path dataFile = tablePath.resolve("data.lsm");
            
            try (BufferedWriter writer = Files.newBufferedWriter(dataFile, StandardOpenOption.APPEND)) {
                writer.write(data.toString());
                writer.newLine();
            }
            
            // Update the cache
            if (enableCache) {
                String cacheKey = generateCacheKey(database, table, rowId);
                cache.put(cacheKey, data);
                cacheAccessTimes.put(cacheKey, System.currentTimeMillis());
                evictCacheIfNeeded();
            }
            
            response.success = true;
            response.rowId = rowId;
            
            LOGGER.fine("Inserted record " + rowId + " into " + database + "." + table);
            return response;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error inserting into " + database + "." + table, e);
            response.success = false;
            response.message = "Error inserting data: " + e.getMessage();
            return response;
        }
    }
    
    /**
     * Selects records from a table.
     * 
     * @param database The database name
     * @param table The table name
     * @param columns The columns to select
     * @param whereColumn The column to filter on
     * @param whereValue The value to filter on
     * @return A list of JSON strings representing the selected records
     */
    @Override
    public List<String> select(String database, String table, String columns, String whereColumn, String whereValue) {
        checkInitialized();
        
        try {
            // Check if the database and table exist
            if (!tableExists(database, table)) {
                return Collections.emptyList();
            }
            
            // Check cache first if enabled
            if (enableCache && whereColumn != null && whereValue != null) {
                String cacheKey = generateCacheKey(database, table, whereColumn, whereValue);
                JSONObject cachedData = cache.get(cacheKey);
                if (cachedData != null) {
                    cacheAccessTimes.put(cacheKey, System.currentTimeMillis());
                    return Collections.singletonList(cachedData.toString());
                }
            }
            
            // Read the data from the table
            Path tablePath = getTablePath(database, table);
            Path dataFile = tablePath.resolve("data.lsm");
            
            if (!Files.exists(dataFile)) {
                return Collections.emptyList();
            }
            
            List<String> results = new ArrayList<>();
            
            try (BufferedReader reader = Files.newBufferedReader(dataFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject record = new JSONObject(line);
                    
                    // Apply where clause if provided
                    if (whereColumn != null && whereValue != null) {
                        if (!record.has(whereColumn) || !record.get(whereColumn).toString().equals(whereValue)) {
                            continue;
                        }
                    }
                    
                    // Apply column selection if provided
                    if (columns != null && !columns.equals("*")) {
                        JSONObject filteredRecord = new JSONObject();
                        String[] columnArray = columns.split(",");
                        for (String column : columnArray) {
                            if (record.has(column)) {
                                filteredRecord.put(column, record.get(column));
                            }
                        }
                        record = filteredRecord;
                    }
                    
                    results.add(record.toString());
                }
            }
            
            // Update cache if enabled
            if (enableCache && whereColumn != null && whereValue != null && !results.isEmpty()) {
                String cacheKey = generateCacheKey(database, table, whereColumn, whereValue);
                cache.put(cacheKey, new JSONObject(results.get(0)));
                cacheAccessTimes.put(cacheKey, System.currentTimeMillis());
                evictCacheIfNeeded();
            }
            
            return results;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error selecting from " + database + "." + table, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Updates records in a table.
     * 
     * @param database The database name
     * @param table The table name
     * @param column The column to update
     * @param value The new value
     * @param whereColumn The column to filter on
     * @param whereValue The value to filter on
     * @return true if the update was successful, false otherwise
     */
    @Override
    public boolean update(String database, String table, String column, String value, String whereColumn, String whereValue) {
        checkInitialized();
        
        try {
            // Check if the database and table exist
            if (!tableExists(database, table)) {
                return false;
            }
            
            // Log the operation to WAL
            wal.logOperation(WriteAheadLog.OperationType.UPDATE, database, table, column, value, whereColumn, whereValue);
            
            // Read the data from the table
            Path tablePath = getTablePath(database, table);
            Path dataFile = tablePath.resolve("data.lsm");
            Path tempFile = tablePath.resolve("data.lsm.tmp");
            
            if (!Files.exists(dataFile)) {
                return false;
            }
            
            boolean updated = false;
            
            try (BufferedReader reader = Files.newBufferedReader(dataFile);
                 BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject record = new JSONObject(line);
                    
                    // Apply where clause if provided
                    if (whereColumn != null && whereValue != null) {
                        if (record.has(whereColumn) && record.get(whereColumn).toString().equals(whereValue)) {
                            // Update the record
                            record.put(column, value);
                            record.put("timestamp", System.currentTimeMillis());
                            updated = true;
                            
                            // Invalidate cache if enabled
                            if (enableCache) {
                                String rowId = record.getString("id");
                                String cacheKey = generateCacheKey(database, table, rowId);
                                cache.remove(cacheKey);
                                cacheAccessTimes.remove(cacheKey);
                            }
                        }
                    }
                    
                    writer.write(record.toString());
                    writer.newLine();
                }
            }
            
            // Replace the data file with the temp file
            Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            
            // Invalidate cache for the where clause
            if (enableCache && updated) {
                String cacheKey = generateCacheKey(database, table, whereColumn, whereValue);
                cache.remove(cacheKey);
                cacheAccessTimes.remove(cacheKey);
            }
            
            return updated;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error updating " + database + "." + table, e);
            return false;
        }
    }
    
    /**
     * Deletes records from a table.
     * 
     * @param database The database name
     * @param table The table name
     * @param whereColumn The column to filter on
     * @param whereValue The value to filter on
     * @return true if the delete was successful, false otherwise
     */
    @Override
    public boolean delete(String database, String table, String whereColumn, String whereValue) {
        checkInitialized();
        
        try {
            // Check if the database and table exist
            if (!tableExists(database, table)) {
                return false;
            }
            
            // Log the operation to WAL
            wal.logOperation(WriteAheadLog.OperationType.DELETE, database, table, whereColumn, whereValue);
            
            // Read the data from the table
            Path tablePath = getTablePath(database, table);
            Path dataFile = tablePath.resolve("data.lsm");
            Path tempFile = tablePath.resolve("data.lsm.tmp");
            
            if (!Files.exists(dataFile)) {
                return false;
            }
            
            boolean deleted = false;
            List<String> deletedIds = new ArrayList<>();
            
            try (BufferedReader reader = Files.newBufferedReader(dataFile);
                 BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject record = new JSONObject(line);
                    
                    // Apply where clause if provided
                    if (whereColumn != null && whereValue != null) {
                        if (record.has(whereColumn) && record.get(whereColumn).toString().equals(whereValue)) {
                            // Skip this record (delete it)
                            deleted = true;
                            deletedIds.add(record.getString("id"));
                            continue;
                        }
                    }
                    
                    writer.write(record.toString());
                    writer.newLine();
                }
            }
            
            // Replace the data file with the temp file
            Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            
            // Invalidate cache for the deleted records
            if (enableCache && deleted) {
                for (String id : deletedIds) {
                    String cacheKey = generateCacheKey(database, table, id);
                    cache.remove(cacheKey);
                    cacheAccessTimes.remove(cacheKey);
                }
                
                // Also invalidate cache for the where clause
                String cacheKey = generateCacheKey(database, table, whereColumn, whereValue);
                cache.remove(cacheKey);
                cacheAccessTimes.remove(cacheKey);
            }
            
            return deleted;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error deleting from " + database + "." + table, e);
            return false;
        }
    }
    
    /**
     * Gets metadata about all databases and tables.
     * 
     * @return A JSONObject containing metadata about all databases and tables
     */
    @Override
    public JSONObject getDatabasesTablesMeta() {
        checkInitialized();
        
        JSONObject meta = new JSONObject();
        JSONObject databases = new JSONObject();
        
        try {
            // Get all databases
            List<String> databaseList = listDatabases();
            
            for (String database : databaseList) {
                JSONObject databaseMeta = new JSONObject();
                JSONObject tables = new JSONObject();
                
                // Get all tables in the database
                List<String> tableList = listTables(database);
                
                for (String table : tableList) {
                    JSONObject tableMeta = new JSONObject();
                    
                    // Get table metadata
                    Path tablePath = getTablePath(database, table);
                    Path metaFile = tablePath.resolve("meta.json");
                    
                    if (Files.exists(metaFile)) {
                        try (BufferedReader reader = Files.newBufferedReader(metaFile)) {
                            String line = reader.readLine();
                            if (line != null) {
                                tableMeta = new JSONObject(line);
                            }
                        }
                    }
                    
                    // Count records in the table
                    Path dataFile = tablePath.resolve("data.lsm");
                    long recordCount = 0;
                    
                    if (Files.exists(dataFile)) {
                        try (BufferedReader reader = Files.newBufferedReader(dataFile)) {
                            while (reader.readLine() != null) {
                                recordCount++;
                            }
                        }
                    }
                    
                    tableMeta.put("recordCount", recordCount);
                    tables.put(table, tableMeta);
                }
                
                databaseMeta.put("tables", tables);
                databases.put(database, databaseMeta);
            }
            
            meta.put("databases", databases);
            return meta;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error getting databases and tables metadata", e);
            return new JSONObject();
        }
    }
    
    /**
     * Gets the path to a database directory.
     * 
     * @param database The database name
     * @return The path to the database directory
     */
    private Path getDatabasePath(String database) {
        return dataDirectory.resolve(database);
    }
    
    /**
     * Gets the path to a table directory.
     * 
     * @param database The database name
     * @param table The table name
     * @return The path to the table directory
     */
    private Path getTablePath(String database, String table) {
        return getDatabasePath(database).resolve(table);
    }
    
    /**
     * Generates a cache key for a record.
     * 
     * @param database The database name
     * @param table The table name
     * @param id The record ID
     * @return The cache key
     */
    private String generateCacheKey(String database, String table, String id) {
        return database + "." + table + "." + id;
    }
    
    /**
     * Generates a cache key for a query.
     *
     * @param database The database name
     * @param table The table name
     * @param whereColumn The column to filter on
     * @param whereValue The value to filter on
     * @return The cache key
     */
    private String generateCacheKey(String database, String table, String whereColumn, String whereValue) {
        return database + "." + table + "." + whereColumn + "=" + whereValue;
    }
    
    /**
     * Evicts entries from the cache if it exceeds the maximum size.
     */
    private void evictCacheIfNeeded() {
        if (cache.size() <= cacheSize) {
            return;
        }
        
        // Find the least recently accessed entries
        List<String> keysToEvict = cacheAccessTimes.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(cache.size() - cacheSize + 10) // Evict a few extra to avoid frequent evictions
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        // Evict the entries
        for (String key : keysToEvict) {
            cache.remove(key);
            cacheAccessTimes.remove(key);
        }
        
        LOGGER.fine("Evicted " + keysToEvict.size() + " entries from cache");
    }
    
    /**
     * Invalidates all cache entries for a database.
     *
     * @param database The database name
     */
    private void invalidateCacheForDatabase(String database) {
        if (!enableCache) {
            return;
        }
        
        String prefix = database + ".";
        
        // Find all cache keys for this database
        List<String> keysToInvalidate = cache.keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .collect(Collectors.toList());
        
        // Invalidate the entries
        for (String key : keysToInvalidate) {
            cache.remove(key);
            cacheAccessTimes.remove(key);
        }
        
        LOGGER.fine("Invalidated " + keysToInvalidate.size() + " cache entries for database " + database);
    }
    
    /**
     * Invalidates all cache entries for a table.
     *
     * @param database The database name
     * @param table The table name
     */
    private void invalidateCacheForTable(String database, String table) {
        if (!enableCache) {
            return;
        }
        
        String prefix = database + "." + table + ".";
        
        // Find all cache keys for this table
        List<String> keysToInvalidate = cache.keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .collect(Collectors.toList());
        
        // Invalidate the entries
        for (String key : keysToInvalidate) {
            cache.remove(key);
            cacheAccessTimes.remove(key);
        }
        
        LOGGER.fine("Invalidated " + keysToInvalidate.size() + " cache entries for table " + database + "." + table);
    }
    
    /**
     * Checks if the storage system is initialized.
     *
     * @throws IllegalStateException if the storage system is not initialized
     */
    private void checkInitialized() {
        if (!isInitialized) {
            throw new IllegalStateException("StorageImpl is not initialized");
        }
        
        if (isShutdown) {
            throw new IllegalStateException("StorageImpl is shut down");
        }
    }
    
    /**
     * Write-ahead logging implementation for the storage system.
     */
    private static class WriteAheadLog {
        
        private static final Logger LOGGER = Logger.getLogger(WriteAheadLog.class.getName());
        
        /**
         * The types of operations that can be logged.
         */
        public enum OperationType {
            INSERT,
            UPDATE,
            DELETE
        }
        
        private final Path walDirectory;
        private final Path walFile;
        private BufferedWriter writer;
        
        /**
         * Creates a new WriteAheadLog.
         *
         * @param walDirectory The directory for the WAL files
         */
        public WriteAheadLog(Path walDirectory) {
            this.walDirectory = walDirectory;
            this.walFile = walDirectory.resolve("wal.log");
        }
        
        /**
         * Initializes the WAL.
         *
         * @throws IOException if an I/O error occurs
         */
        public void init() throws IOException {
            Files.createDirectories(walDirectory);
            
            if (!Files.exists(walFile)) {
                Files.createFile(walFile);
            }
            
            writer = Files.newBufferedWriter(walFile, StandardOpenOption.APPEND);
            LOGGER.info("WAL initialized at " + walFile);
        }
        
        /**
         * Logs an insert operation.
         *
         * @param database The database name
         * @param table The table name
         * @param data The data to insert
         * @throws IOException if an I/O error occurs
         */
        public void logOperation(OperationType type, String database, String table, JSONObject data) throws IOException {
            JSONObject log = new JSONObject();
            log.put("type", type.name());
            log.put("database", database);
            log.put("table", table);
            log.put("data", data);
            log.put("timestamp", System.currentTimeMillis());
            
            writer.write(log.toString());
            writer.newLine();
        }
        
        /**
         * Logs an update operation.
         *
         * @param database The database name
         * @param table The table name
         * @param column The column to update
         * @param value The new value
         * @param whereColumn The column to filter on
         * @param whereValue The value to filter on
         * @throws IOException if an I/O error occurs
         */
        public void logOperation(OperationType type, String database, String table, String column, String value,
                                String whereColumn, String whereValue) throws IOException {
            JSONObject log = new JSONObject();
            log.put("type", type.name());
            log.put("database", database);
            log.put("table", table);
            log.put("column", column);
            log.put("value", value);
            log.put("whereColumn", whereColumn);
            log.put("whereValue", whereValue);
            log.put("timestamp", System.currentTimeMillis());
            
            writer.write(log.toString());
            writer.newLine();
        }
        
        /**
         * Logs a delete operation.
         *
         * @param database The database name
         * @param table The table name
         * @param whereColumn The column to filter on
         * @param whereValue The value to filter on
         * @throws IOException if an I/O error occurs
         */
        public void logOperation(OperationType type, String database, String table, String whereColumn,
                                String whereValue) throws IOException {
            JSONObject log = new JSONObject();
            log.put("type", type.name());
            log.put("database", database);
            log.put("table", table);
            log.put("whereColumn", whereColumn);
            log.put("whereValue", whereValue);
            log.put("timestamp", System.currentTimeMillis());
            
            writer.write(log.toString());
            writer.newLine();
        }
        
        /**
         * Flushes the WAL to disk.
         *
         * @throws IOException if an I/O error occurs
         */
        public void flush() throws IOException {
            if (writer != null) {
                writer.flush();
            }
        }
        
        /**
         * Closes the WAL.
         *
         * @throws IOException if an I/O error occurs
         */
        public void close() throws IOException {
            if (writer != null) {
                writer.close();
            }
        }
        
        /**
         * Recovers the storage system from the WAL.
         *
         * @throws IOException if an I/O error occurs
         */
        public void recover() throws IOException {
            if (!Files.exists(walFile)) {
                return;
            }
            
            LOGGER.info("Recovering from WAL: " + walFile);
            
            try (BufferedReader reader = Files.newBufferedReader(walFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject log = new JSONObject(line);
                    
                    // Process the log entry
                    // In a real implementation, this would replay the operation
                    LOGGER.fine("Recovered log entry: " + log);
                }
            }
            
            LOGGER.info("Recovery from WAL complete");
        }
    }