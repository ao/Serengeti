package com.ataiva.serengeti.performance;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.storage.StorageResponseObject;
import com.ataiva.serengeti.utils.TestBase;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for the Storage component.
 */
@DisplayName("Storage Performance Tests")
@Tag("performance")
class StoragePerformanceTest extends TestBase {
    
    private String testDb;
    private String testTable;
    
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        
        // Create unique test database and table names for each test
        testDb = generateRandomDatabaseName();
        testTable = generateRandomTableName();
        
        // Create the test database and table
        storage.createDatabase(testDb);
        storage.createTable(testDb, testTable);
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        // Clean up after each test
        storage.dropDatabase(testDb);
        
        super.tearDown();
    }
    
    @Test
    @DisplayName("Measure database creation performance")
    void testDatabaseCreationPerformance() {
        int numDatabases = 10;
        List<String> dbNames = new ArrayList<>();
        
        for (int i = 0; i < numDatabases; i++) {
            dbNames.add("perf_db_" + i);
        }
        
        // Warm-up
        for (String dbName : dbNames) {
            storage.createDatabase(dbName);
            storage.dropDatabase(dbName);
        }
        
        // Measure performance
        long startTime = System.nanoTime();
        
        for (String dbName : dbNames) {
            storage.createDatabase(dbName);
        }
        
        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMillis = durationNanos / 1_000_000.0;
        
        System.out.println("Created " + numDatabases + " databases in " + durationMillis + "ms");
        System.out.println("Average time per database: " + (durationMillis / numDatabases) + "ms");
        
        // Clean up
        for (String dbName : dbNames) {
            storage.dropDatabase(dbName);
        }
        
        // Assert that the performance is within acceptable limits
        assertTrue(durationMillis < 5000, "Database creation performance is too slow");
    }
    
    @Test
    @DisplayName("Measure table creation performance")
    void testTableCreationPerformance() {
        int numTables = 10;
        List<String> tableNames = new ArrayList<>();
        
        for (int i = 0; i < numTables; i++) {
            tableNames.add("perf_table_" + i);
        }
        
        // Warm-up
        for (String tableName : tableNames) {
            storage.createTable(testDb, tableName);
            storage.dropTable(testDb, tableName);
        }
        
        // Measure performance
        long startTime = System.nanoTime();
        
        for (String tableName : tableNames) {
            storage.createTable(testDb, tableName);
        }
        
        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMillis = durationNanos / 1_000_000.0;
        
        System.out.println("Created " + numTables + " tables in " + durationMillis + "ms");
        System.out.println("Average time per table: " + (durationMillis / numTables) + "ms");
        
        // Clean up
        for (String tableName : tableNames) {
            storage.dropTable(testDb, tableName);
        }
        
        // Assert that the performance is within acceptable limits
        assertTrue(durationMillis < 5000, "Table creation performance is too slow");
    }
    
    @ParameterizedTest
    @ValueSource(ints = {10, 100, 1000})
    @DisplayName("Measure data insertion performance")
    void testDataInsertionPerformance(int numRecords) {
        // Warm-up
        for (int i = 0; i < 10; i++) {
            JSONObject data = new JSONObject();
            data.put("id", i);
            data.put("name", "Record " + i);
            data.put("value", i * 10);
            
            storage.insert(testDb, testTable, data);
        }
        
        // Clean up warm-up data
        storage.dropTable(testDb, testTable);
        storage.createTable(testDb, testTable);
        
        // Measure performance
        long startTime = System.nanoTime();
        
        for (int i = 0; i < numRecords; i++) {
            JSONObject data = new JSONObject();
            data.put("id", i);
            data.put("name", "Record " + i);
            data.put("value", i * 10);
            
            storage.insert(testDb, testTable, data);
        }
        
        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMillis = durationNanos / 1_000_000.0;
        
        System.out.println("Inserted " + numRecords + " records in " + durationMillis + "ms");
        System.out.println("Average time per record: " + (durationMillis / numRecords) + "ms");
        System.out.println("Insertion rate: " + (numRecords * 1000 / durationMillis) + " records/second");
        
        // Assert that the performance is within acceptable limits
        // The acceptable limit depends on the number of records
        double acceptableLimit = numRecords * 50; // 50ms per record
        assertTrue(durationMillis < acceptableLimit, "Data insertion performance is too slow");
    }
    
    @Test
    @DisplayName("Measure data selection performance")
    void testDataSelectionPerformance() {
        int numRecords = 100;
        
        // Insert test data
        for (int i = 0; i < numRecords; i++) {
            JSONObject data = new JSONObject();
            data.put("id", i);
            data.put("name", "Record " + i);
            data.put("value", i * 10);
            
            storage.insert(testDb, testTable, data);
        }
        
        // Warm-up
        for (int i = 0; i < 10; i++) {
            storage.select(testDb, testTable, "*", "", "");
        }
        
        // Measure performance
        long startTime = System.nanoTime();
        
        List<String> results = storage.select(testDb, testTable, "*", "", "");
        
        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMillis = durationNanos / 1_000_000.0;
        
        System.out.println("Selected " + results.size() + " records in " + durationMillis + "ms");
        System.out.println("Average time per record: " + (durationMillis / results.size()) + "ms");
        System.out.println("Selection rate: " + (results.size() * 1000 / durationMillis) + " records/second");
        
        // Assert that the performance is within acceptable limits
        assertTrue(durationMillis < 5000, "Data selection performance is too slow");
    }
    
    @Test
    @DisplayName("Measure concurrent data insertion performance")
    void testConcurrentDataInsertionPerformance() throws Exception {
        int numThreads = 4;
        int numRecordsPerThread = 25;
        int totalRecords = numThreads * numRecordsPerThread;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        // Warm-up
        for (int i = 0; i < 10; i++) {
            JSONObject data = new JSONObject();
            data.put("id", i);
            data.put("name", "Record " + i);
            data.put("value", i * 10);
            
            storage.insert(testDb, testTable, data);
        }
        
        // Clean up warm-up data
        storage.dropTable(testDb, testTable);
        storage.createTable(testDb, testTable);
        
        // Measure performance
        long startTime = System.nanoTime();
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < numRecordsPerThread; i++) {
                        JSONObject data = new JSONObject();
                        data.put("thread", threadId);
                        data.put("id", i);
                        data.put("name", "Record " + threadId + "-" + i);
                        data.put("value", i * 10);
                        
                        storage.insert(testDb, testTable, data);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Wait for all threads to complete
        latch.await();
        
        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMillis = durationNanos / 1_000_000.0;
        
        // Verify that all records were inserted
        List<String> results = storage.select(testDb, testTable, "*", "", "");
        
        System.out.println("Concurrently inserted " + totalRecords + " records in " + durationMillis + "ms");
        System.out.println("Average time per record: " + (durationMillis / totalRecords) + "ms");
        System.out.println("Insertion rate: " + (totalRecords * 1000 / durationMillis) + " records/second");
        
        // Assert that the performance is within acceptable limits
        assertTrue(durationMillis < 10000, "Concurrent data insertion performance is too slow");
        assertEquals(totalRecords, results.size(), "Not all records were inserted");
        
        // Shut down the executor
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
    
    @Test
    @DisplayName("Measure data update performance")
    void testDataUpdatePerformance() {
        int numRecords = 100;
        
        // Insert test data
        for (int i = 0; i < numRecords; i++) {
            JSONObject data = new JSONObject();
            data.put("id", i);
            data.put("name", "Record " + i);
            data.put("value", i * 10);
            
            storage.insert(testDb, testTable, data);
        }
        
        // Warm-up
        for (int i = 0; i < 10; i++) {
            storage.update(testDb, testTable, "value", String.valueOf(i * 20), "id", String.valueOf(i));
        }
        
        // Measure performance
        long startTime = System.nanoTime();
        
        for (int i = 0; i < numRecords; i++) {
            storage.update(testDb, testTable, "value", String.valueOf(i * 20), "id", String.valueOf(i));
        }
        
        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMillis = durationNanos / 1_000_000.0;
        
        System.out.println("Updated " + numRecords + " records in " + durationMillis + "ms");
        System.out.println("Average time per record: " + (durationMillis / numRecords) + "ms");
        System.out.println("Update rate: " + (numRecords * 1000 / durationMillis) + " records/second");
        
        // Assert that the performance is within acceptable limits
        assertTrue(durationMillis < 10000, "Data update performance is too slow");
    }
    
    @Test
    @DisplayName("Measure data deletion performance")
    void testDataDeletionPerformance() {
        int numRecords = 100;
        
        // Insert test data
        for (int i = 0; i < numRecords; i++) {
            JSONObject data = new JSONObject();
            data.put("id", i);
            data.put("name", "Record " + i);
            data.put("value", i * 10);
            
            storage.insert(testDb, testTable, data);
        }
        
        // Warm-up
        for (int i = 0; i < 10; i++) {
            storage.delete(testDb, testTable, "id", String.valueOf(i));
        }
        
        // Measure performance
        long startTime = System.nanoTime();
        
        for (int i = 10; i < numRecords; i++) {
            storage.delete(testDb, testTable, "id", String.valueOf(i));
        }
        
        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMillis = durationNanos / 1_000_000.0;
        
        System.out.println("Deleted " + (numRecords - 10) + " records in " + durationMillis + "ms");
        System.out.println("Average time per record: " + (durationMillis / (numRecords - 10)) + "ms");
        System.out.println("Deletion rate: " + ((numRecords - 10) * 1000 / durationMillis) + " records/second");
        
        // Assert that the performance is within acceptable limits
        assertTrue(durationMillis < 10000, "Data deletion performance is too slow");
    }
}