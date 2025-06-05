package com.ataiva.serengeti.benchmark;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageScheduler;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark tests for the StorageScheduler component.
 * 
 * These benchmarks measure the performance of the StorageScheduler under different conditions:
 * - Different database sizes
 * - Different numbers of tables
 * - Different data sizes
 * - Different concurrency levels
 * 
 * The benchmarks focus on the following metrics:
 * - Throughput: Operations per second
 * - Average time: Average time per operation
 * - Sample time: Distribution of operation times
 * 
 * To run these benchmarks:
 * 1. Use the provided scripts: run_benchmarks.sh or run_benchmarks.bat
 * 2. Or run directly with: mvn clean test -Pbenchmark
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 2)
public class StorageSchedulerBenchmark {

    private StorageScheduler storageScheduler;
    private Map<String, DatabaseObject> originalDatabases;
    private Map<String, TableStorageObject> originalTableStorageObjects;
    private Map<String, TableReplicaObject> originalTableReplicaObjects;
    private boolean originalNetworkOnline;
    private boolean originalRunning;
    private String originalDataPath;
    private Path tempDir;

    /**
     * Setup method that runs before each benchmark iteration.
     * Creates a temporary directory and initializes the StorageScheduler with test data.
     */
    @Setup(Level.Iteration)
    public void setup() throws IOException {
        // Backup original static state
        originalDatabases = Storage.databases;
        originalTableStorageObjects = Storage.tableStorageObjects;
        originalTableReplicaObjects = Storage.tableReplicaObjects;
        originalNetworkOnline = Network.online;
        originalRunning = StorageScheduler.running;
        originalDataPath = Globals.data_path;
        
        // Create temporary directory for test data
        tempDir = Files.createTempDirectory("storage-scheduler-benchmark");
        
        // Setup clean test state
        Storage.databases = new HashMap<>();
        Storage.tableStorageObjects = new HashMap<>();
        Storage.tableReplicaObjects = new HashMap<>();
        Network.online = true;
        StorageScheduler.running = false;
        Globals.data_path = tempDir.toString() + "/";
        
        // Initialize StorageScheduler
        storageScheduler = new StorageScheduler();
    }

    /**
     * Teardown method that runs after each benchmark iteration.
     * Restores the original state and deletes the temporary directory.
     */
    @TearDown(Level.Iteration)
    public void tearDown() throws IOException {
        // Restore original static state
        Storage.databases = originalDatabases;
        Storage.tableStorageObjects = originalTableStorageObjects;
        Storage.tableReplicaObjects = originalTableReplicaObjects;
        Network.online = originalNetworkOnline;
        StorageScheduler.running = originalRunning;
        Globals.data_path = originalDataPath;
        
        // Delete temporary directory
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(file -> file.delete());
    }

    /**
     * Benchmark for the basic persistence operation with a small database.
     * This measures the baseline performance of the StorageScheduler.
     */
    @Benchmark
    public void benchmarkBasicPersistence(Blackhole blackhole) {
        setupSmallDatabase();
        boolean result = storageScheduler.performPersistToDisk();
        blackhole.consume(result);
    }

    /**
     * Benchmark for persistence with a medium-sized database.
     * This measures how the StorageScheduler scales with moderate data.
     */
    @Benchmark
    public void benchmarkMediumDatabasePersistence(Blackhole blackhole) {
        setupMediumDatabase();
        boolean result = storageScheduler.performPersistToDisk();
        blackhole.consume(result);
    }

    /**
     * Benchmark for persistence with a large database.
     * This measures how the StorageScheduler scales with large data volumes.
     */
    @Benchmark
    public void benchmarkLargeDatabasePersistence(Blackhole blackhole) {
        setupLargeDatabase();
        boolean result = storageScheduler.performPersistToDisk();
        blackhole.consume(result);
    }

    /**
     * Benchmark for persistence with multiple small databases.
     * This measures how the StorageScheduler handles multiple databases.
     */
    @Benchmark
    public void benchmarkMultipleDatabases(Blackhole blackhole) {
        setupMultipleDatabases(10);
        boolean result = storageScheduler.performPersistToDisk();
        blackhole.consume(result);
    }

    /**
     * Benchmark for persistence with a database containing many tables.
     * This measures how the StorageScheduler scales with the number of tables.
     */
    @Benchmark
    public void benchmarkManyTables(Blackhole blackhole) {
        setupDatabaseWithManyTables(1, 50);
        boolean result = storageScheduler.performPersistToDisk();
        blackhole.consume(result);
    }

    /**
     * Benchmark for persistence with a database containing large tables.
     * This measures how the StorageScheduler handles large table data.
     */
    @Benchmark
    public void benchmarkLargeTables(Blackhole blackhole) {
        setupDatabaseWithLargeTables();
        boolean result = storageScheduler.performPersistToDisk();
        blackhole.consume(result);
    }

    /**
     * Helper method to set up a small database for benchmarking.
     */
    private void setupSmallDatabase() {
        setupDatabaseWithTables("testdb", 1, 10);
    }

    /**
     * Helper method to set up a medium-sized database for benchmarking.
     */
    private void setupMediumDatabase() {
        setupDatabaseWithTables("testdb", 5, 100);
    }

    /**
     * Helper method to set up a large database for benchmarking.
     */
    private void setupLargeDatabase() {
        setupDatabaseWithTables("testdb", 10, 1000);
    }

    /**
     * Helper method to set up multiple databases for benchmarking.
     */
    private void setupMultipleDatabases(int count) {
        for (int i = 0; i < count; i++) {
            setupDatabaseWithTables("testdb" + i, 2, 50);
        }
    }

    /**
     * Helper method to set up a database with many tables for benchmarking.
     */
    private void setupDatabaseWithManyTables(int dbCount, int tableCount) {
        for (int i = 0; i < dbCount; i++) {
            setupDatabaseWithTables("testdb" + i, tableCount, 10);
        }
    }

    /**
     * Helper method to set up a database with large tables for benchmarking.
     */
    private void setupDatabaseWithLargeTables() {
        String dbName = "largedb";
        
        // Create database object
        DatabaseObject dbo = new DatabaseObject();
        dbo.name = dbName;
        dbo.tables = new ArrayList<>();
        
        // Add a single large table
        String tableName = "largetable";
        dbo.tables.add(tableName);
        
        // Create large data for the table
        TableStorageObject tso = new TableStorageObject();
        tso.rows = new HashMap<>();
        for (int i = 0; i < 10000; i++) {
            tso.rows.put("key" + i, generateLargeData(1024)); // 1KB per row
        }
        
        TableReplicaObject tro = new TableReplicaObject();
        tro.row_replicas = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            tro.row_replicas.put("key" + i, generateLargeData(1024)); // 1KB per row
        }
        
        // Add to storage maps
        Storage.databases.put(dbName, dbo);
        Storage.tableStorageObjects.put(dbName + "#" + tableName, tso);
        Storage.tableReplicaObjects.put(dbName + "#" + tableName, tro);
    }

    /**
     * Helper method to set up a database with tables for benchmarking.
     */
    private void setupDatabaseWithTables(String dbName, int tableCount, int rowsPerTable) {
        // Create database object
        DatabaseObject dbo = new DatabaseObject();
        dbo.name = dbName;
        dbo.tables = new ArrayList<>();
        
        // Add tables
        for (int i = 0; i < tableCount; i++) {
            String tableName = "table" + i;
            dbo.tables.add(tableName);
            
            // Create table storage object
            TableStorageObject tso = new TableStorageObject();
            tso.rows = new HashMap<>();
            for (int j = 0; j < rowsPerTable; j++) {
                tso.rows.put("key" + j, "value" + j);
            }
            
            // Create table replica object
            TableReplicaObject tro = new TableReplicaObject();
            tro.row_replicas = new HashMap<>();
            for (int j = 0; j < rowsPerTable / 10; j++) { // Fewer replicas than rows
                tro.row_replicas.put("key" + j, "replica" + j);
            }
            
            // Add to storage maps
            Storage.tableStorageObjects.put(dbName + "#" + tableName, tso);
            Storage.tableReplicaObjects.put(dbName + "#" + tableName, tro);
        }
        
        // Add database to storage map
        Storage.databases.put(dbName, dbo);
    }

    /**
     * Helper method to generate large data for benchmarking.
     */
    private String generateLargeData(int sizeInBytes) {
        StringBuilder sb = new StringBuilder(sizeInBytes);
        Random random = new Random();
        for (int i = 0; i < sizeInBytes; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }

    /**
     * Main method to run the benchmarks from the command line.
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(StorageSchedulerBenchmark.class.getSimpleName())
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}