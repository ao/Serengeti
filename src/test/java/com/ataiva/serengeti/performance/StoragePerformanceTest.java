package com.ataiva.serengeti.performance;

import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageFactory;
import com.ataiva.serengeti.storage.StorageImpl;
import com.ataiva.serengeti.storage.StorageResponseObject;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Performance tests for the StorageImpl class.
 * These tests evaluate the performance characteristics of the StorageImpl class
 * under various conditions and workloads.
 */
@Category(PerformanceTest.class)
public class StoragePerformanceTest {

    private StorageImpl storageImpl;
    private String testDbName;
    private String testTableName;
    private static final int SMALL_DATASET_SIZE = 100;
    private static final int MEDIUM_DATASET_SIZE = 1000;
    private static final int LARGE_DATASET_SIZE = 10000;
    private static final int THREAD_COUNT = 10;
    
    @Before
    public void setUp() throws Exception {
        // Create a unique test database and table name
        testDbName = "perf_db_" + UUID.randomUUID().toString().substring(0, 8);
        testTableName = "perf_table_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Create a StorageImpl instance with performance-oriented configuration
        // Enable caching with a large cache size for performance testing
        storageImpl = (StorageImpl) StorageFactory.createStorage(StorageFactory.StorageType.REAL, true, 10000);
        
        // Initialize the storage
        storageImpl.init();
        
        // Create the test database and table
        storageImpl.createDatabase(testDbName);
        storageImpl.createTable(testDbName, testTableName);
    }
    
    @After
    public void tearDown() throws Exception {
        // Clean up test database if it exists
        if (storageImpl.databaseExists(testDbName)) {
            storageImpl.dropDatabase(testDbName);
        }
        
        // Shutdown the storage
        storageImpl.shutdown();
    }
    
    /**
     * Test the throughput of insert operations.
     */
    @Test
    public void testInsertThroughput() {
        System.out.println("Testing insert throughput...");
        
        // Measure time for small dataset
        long startTime = System.currentTimeMillis();
        insertTestData(SMALL_DATASET_SIZE);
        long smallDatasetTime = System.currentTimeMillis() - startTime;
        
        // Clean up
        storageImpl.delete(testDbName, testTableName, "1", "1");
        
        // Measure time for medium dataset
        startTime = System.currentTimeMillis();
        insertTestData(MEDIUM_DATASET_SIZE);
        long mediumDatasetTime = System.currentTimeMillis() - startTime;
        
        // Clean up
        storageImpl.delete(testDbName, testTableName, "1", "1");
        
        // Measure time for large dataset
        startTime = System.currentTimeMillis();
        insertTestData(LARGE_DATASET_SIZE);
        long largeDatasetTime = System.currentTimeMillis() - startTime;
        
        // Print results
        System.out.println("Insert throughput results:");
        System.out.println("Small dataset (" + SMALL_DATASET_SIZE + " records): " + smallDatasetTime + "ms, " 
                + calculateThroughput(SMALL_DATASET_SIZE, smallDatasetTime) + " ops/sec");
        System.out.println("Medium dataset (" + MEDIUM_DATASET_SIZE + " records): " + mediumDatasetTime + "ms, " 
                + calculateThroughput(MEDIUM_DATASET_SIZE, mediumDatasetTime) + " ops/sec");
        System.out.println("Large dataset (" + LARGE_DATASET_SIZE + " records): " + largeDatasetTime + "ms, " 
                + calculateThroughput(LARGE_DATASET_SIZE, largeDatasetTime) + " ops/sec");
        
        // Verify that the throughput is acceptable
        // This is a simple check to ensure that the throughput doesn't degrade too much with larger datasets
        double smallThroughput = calculateThroughput(SMALL_DATASET_SIZE, smallDatasetTime);
        double largeThroughput = calculateThroughput(LARGE_DATASET_SIZE, largeDatasetTime);
        
        // The large dataset throughput should be at least 50% of the small dataset throughput
        assertTrue("Large dataset throughput should be at least 50% of small dataset throughput", 
                largeThroughput >= smallThroughput * 0.5);
    }
    
    /**
     * Test the throughput of select operations.
     */
    @Test
    public void testSelectThroughput() {
        System.out.println("Testing select throughput...");
        
        // Insert test data
        insertTestData(LARGE_DATASET_SIZE);
        
        // Measure time for selecting all records
        long startTime = System.currentTimeMillis();
        List<String> results = storageImpl.select(testDbName, testTableName, "*", "1", "1");
        long selectAllTime = System.currentTimeMillis() - startTime;
        
        // Measure time for selecting with a specific condition
        startTime = System.currentTimeMillis();
        results = storageImpl.select(testDbName, testTableName, "*", "value", "50");
        long selectSpecificTime = System.currentTimeMillis() - startTime;
        
        // Print results
        System.out.println("Select throughput results:");
        System.out.println("Select all records: " + selectAllTime + "ms");
        System.out.println("Select specific records: " + selectSpecificTime + "ms");
        
        // Verify that the select operations return the expected number of records
        assertFalse("Select all should return records", results.isEmpty());
    }
    
    /**
     * Test the latency of various storage operations.
     */
    @Test
    public void testOperationLatency() {
        System.out.println("Testing operation latency...");
        
        // Measure latency for database creation
        long startTime = System.currentTimeMillis();
        String tempDbName = "temp_db_" + UUID.randomUUID().toString().substring(0, 8);
        storageImpl.createDatabase(tempDbName);
        long createDbLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for table creation
        startTime = System.currentTimeMillis();
        String tempTableName = "temp_table_" + UUID.randomUUID().toString().substring(0, 8);
        storageImpl.createTable(tempDbName, tempTableName);
        long createTableLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a single insert
        JSONObject testData = new JSONObject();
        testData.put("name", "Latency Test");
        testData.put("value", 100);
        
        startTime = System.currentTimeMillis();
        StorageResponseObject insertResponse = storageImpl.insert(tempDbName, tempTableName, testData);
        long insertLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a single select
        startTime = System.currentTimeMillis();
        List<String> results = storageImpl.select(tempDbName, tempTableName, "*", "name", "Latency Test");
        long selectLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a single update
        startTime = System.currentTimeMillis();
        storageImpl.update(tempDbName, tempTableName, "value", "200", "name", "Latency Test");
        long updateLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for a single delete
        startTime = System.currentTimeMillis();
        storageImpl.delete(tempDbName, tempTableName, "name", "Latency Test");
        long deleteLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for dropping a table
        startTime = System.currentTimeMillis();
        storageImpl.dropTable(tempDbName, tempTableName);
        long dropTableLatency = System.currentTimeMillis() - startTime;
        
        // Measure latency for dropping a database
        startTime = System.currentTimeMillis();
        storageImpl.dropDatabase(tempDbName);
        long dropDbLatency = System.currentTimeMillis() - startTime;
        
        // Print results
        System.out.println("Operation latency results:");
        System.out.println("Create database: " + createDbLatency + "ms");
        System.out.println("Create table: " + createTableLatency + "ms");
        System.out.println("Insert: " + insertLatency + "ms");
        System.out.println("Select: " + selectLatency + "ms");
        System.out.println("Update: " + updateLatency + "ms");
        System.out.println("Delete: " + deleteLatency + "ms");
        System.out.println("Drop table: " + dropTableLatency + "ms");
        System.out.println("Drop database: " + dropDbLatency + "ms");
        
        // Verify that the latencies are within acceptable ranges
        // These are arbitrary thresholds and should be adjusted based on actual performance requirements
        assertTrue("Create database latency should be less than 100ms", createDbLatency < 100);
        assertTrue("Create table latency should be less than 100ms", createTableLatency < 100);
        assertTrue("Insert latency should be less than 50ms", insertLatency < 50);
        assertTrue("Select latency should be less than 50ms", selectLatency < 50);
        assertTrue("Update latency should be less than 50ms", updateLatency < 50);
        assertTrue("Delete latency should be less than 50ms", deleteLatency < 50);
        assertTrue("Drop table latency should be less than 100ms", dropTableLatency < 100);
        assertTrue("Drop database latency should be less than 100ms", dropDbLatency < 100);
    }
    
    /**
     * Test the scalability of the storage implementation with increasing data size.
     */
    @Test
    public void testScalability() {
        System.out.println("Testing scalability...");
        
        // Test with increasingly larger datasets
        int[] datasetSizes = {100, 1000, 10000};
        
        for (int size : datasetSizes) {
            // Create a new table for each test
            String scalabilityTable = "scale_table_" + size;
            storageImpl.createTable(testDbName, scalabilityTable);
            
            // Measure insert time
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < size; i++) {
                JSONObject testData = new JSONObject();
                testData.put("id", i);
                testData.put("name", "Record " + i);
                testData.put("value", i * 10);
                
                storageImpl.insert(testDbName, scalabilityTable, testData);
            }
            long insertTime = System.currentTimeMillis() - startTime;
            
            // Measure select time
            startTime = System.currentTimeMillis();
            List<String> results = storageImpl.select(testDbName, scalabilityTable, "*", "1", "1");
            long selectTime = System.currentTimeMillis() - startTime;
            
            // Print results
            System.out.println("Scalability results for dataset size " + size + ":");
            System.out.println("Insert time: " + insertTime + "ms, " 
                    + calculateThroughput(size, insertTime) + " ops/sec");
            System.out.println("Select time: " + selectTime + "ms");
            
            // Clean up
            storageImpl.dropTable(testDbName, scalabilityTable);
        }
    }
    
    /**
     * Test the concurrency handling of the storage implementation.
     */
    @Test
    public void testConcurrency() throws Exception {
        System.out.println("Testing concurrency...");
        
        // Create a thread pool
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        // Create a countdown latch to synchronize the start of all threads
        CountDownLatch startLatch = new CountDownLatch(1);
        
        // Create a countdown latch to wait for all threads to complete
        CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
        
        // Create an atomic counter to track successful operations
        AtomicInteger successCount = new AtomicInteger(0);
        
        // Create a list to store any exceptions that occur
        List<Exception> exceptions = new ArrayList<>();
        
        // Start the threads
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Wait for the start signal
                    startLatch.await();
                    
                    // Perform operations
                    for (int j = 0; j < 100; j++) {
                        // Create a unique record
                        JSONObject testData = new JSONObject();
                        testData.put("thread_id", threadId);
                        testData.put("record_id", j);
                        testData.put("value", threadId * 1000 + j);
                        
                        // Insert the record
                        StorageResponseObject insertResponse = storageImpl.insert(testDbName, testTableName, testData);
                        
                        if (insertResponse.success) {
                            successCount.incrementAndGet();
                        }
                        
                        // Select the record
                        List<String> results = storageImpl.select(testDbName, testTableName, "*", "thread_id", String.valueOf(threadId));
                        
                        // Update the record
                        storageImpl.update(testDbName, testTableName, "value", String.valueOf(threadId * 2000 + j), 
                                "thread_id", String.valueOf(threadId));
                    }
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    // Signal that this thread is complete
                    completionLatch.countDown();
                }
            });
        }
        
        // Start all threads
        long startTime = System.currentTimeMillis();
        startLatch.countDown();
        
        // Wait for all threads to complete
        boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
        long totalTime = System.currentTimeMillis() - startTime;
        
        // Shutdown the executor
        executor.shutdown();
        
        // Print results
        System.out.println("Concurrency test results:");
        System.out.println("Total time: " + totalTime + "ms");
        System.out.println("Successful operations: " + successCount.get() + " out of " + (THREAD_COUNT * 100));
        System.out.println("Exceptions: " + exceptions.size());
        
        // Print any exceptions that occurred
        for (Exception e : exceptions) {
            System.err.println("Exception in concurrency test: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Verify that all threads completed
        assertTrue("All threads should complete within the timeout", completed);
        
        // Verify that most operations were successful
        // Allow for some failures due to concurrency conflicts
        assertTrue("At least 90% of operations should succeed", 
                successCount.get() >= THREAD_COUNT * 100 * 0.9);
        
        // Verify that no exceptions occurred
        assertTrue("No exceptions should occur during concurrency test", exceptions.isEmpty());
    }
    
    /**
     * Test the effectiveness of the cache.
     */
    @Test
    public void testCacheEffectiveness() {
        System.out.println("Testing cache effectiveness...");
        
        // Insert test data
        insertTestData(MEDIUM_DATASET_SIZE);
        
        // Perform first select to populate cache
        long startTime = System.currentTimeMillis();
        List<String> firstResults = storageImpl.select(testDbName, testTableName, "*", "1", "1");
        long firstSelectTime = System.currentTimeMillis() - startTime;
        
        // Perform second select which should use cache
        startTime = System.currentTimeMillis();
        List<String> secondResults = storageImpl.select(testDbName, testTableName, "*", "1", "1");
        long secondSelectTime = System.currentTimeMillis() - startTime;
        
        // Print results
        System.out.println("Cache effectiveness results:");
        System.out.println("First select time (cache miss): " + firstSelectTime + "ms");
        System.out.println("Second select time (cache hit): " + secondSelectTime + "ms");
        System.out.println("Cache speedup factor: " + (firstSelectTime / (double) secondSelectTime));
        
        // Verify that the second select is faster due to caching
        assertTrue("Second select should be faster than first select", secondSelectTime < firstSelectTime);
        
        // Verify that the cache speedup factor is significant
        // A speedup factor of at least 2x is expected with caching
        assertTrue("Cache speedup factor should be at least 2x", 
                firstSelectTime / (double) secondSelectTime >= 2.0);
    }
    
    /**
     * Helper method to insert test data.
     * 
     * @param count The number of records to insert
     */
    private void insertTestData(int count) {
        for (int i = 0; i < count; i++) {
            JSONObject testData = new JSONObject();
            testData.put("id", i);
            testData.put("name", "Test Record " + i);
            testData.put("value", i);
            
            storageImpl.insert(testDbName, testTableName, testData);
        }
    }
    
    /**
     * Helper method to calculate throughput in operations per second.
     * 
     * @param operations The number of operations performed
     * @param timeMs The time taken in milliseconds
     * @return The throughput in operations per second
     */
    private double calculateThroughput(int operations, long timeMs) {
        return operations / (timeMs / 1000.0);
    }
}