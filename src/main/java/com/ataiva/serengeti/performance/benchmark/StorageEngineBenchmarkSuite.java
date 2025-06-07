package com.ataiva.serengeti.performance.benchmark;

import com.ataiva.serengeti.storage.StorageEngineTuner;
import com.ataiva.serengeti.storage.StorageFactory;
import com.ataiva.serengeti.storage.StorageImpl;
import com.ataiva.serengeti.storage.StorageResponseObject;
import com.ataiva.serengeti.storage.lsm.SSTable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Benchmark suite for the Storage Engine.
 * This suite tests the performance of various storage operations
 * with different configurations of the StorageEngineTuner.
 */
public class StorageEngineBenchmarkSuite extends AbstractBenchmarkSuite {
    private static final Logger LOGGER = Logger.getLogger(StorageEngineBenchmarkSuite.class.getName());
    
    private static final int SMALL_DATASET_SIZE = 100;
    private static final int MEDIUM_DATASET_SIZE = 1000;
    private static final int LARGE_DATASET_SIZE = 10000;
    
    private StorageImpl storage;
    private StorageEngineTuner tuner;
    private String testDbName;
    private String testTableName;
    private Path testDir;
    private Path testFile;
    
    /**
     * Creates a new StorageEngineBenchmarkSuite.
     */
    public StorageEngineBenchmarkSuite() {
        super("storage-engine", "Benchmark suite for the Storage Engine");
        
        // Define parameter sets for different tuning levels
        Map<String, Map<String, Object>> paramSets = new HashMap<>();
        
        // Performance-optimized parameters
        Map<String, Object> performanceParams = new HashMap<>();
        performanceParams.put("tuningLevel", StorageEngineTuner.TuningLevel.PERFORMANCE);
        performanceParams.put("cacheSize", 10000);
        performanceParams.put("asyncIO", true);
        paramSets.put("performance", performanceParams);
        
        // Balanced parameters
        Map<String, Object> balancedParams = new HashMap<>();
        balancedParams.put("tuningLevel", StorageEngineTuner.TuningLevel.BALANCED);
        balancedParams.put("cacheSize", 5000);
        balancedParams.put("asyncIO", true);
        paramSets.put("balanced", balancedParams);
        
        // Resource-efficient parameters
        Map<String, Object> resourceEfficientParams = new HashMap<>();
        resourceEfficientParams.put("tuningLevel", StorageEngineTuner.TuningLevel.RESOURCE_EFFICIENT);
        resourceEfficientParams.put("cacheSize", 1000);
        resourceEfficientParams.put("asyncIO", false);
        paramSets.put("resource-efficient", resourceEfficientParams);
        
        // Set the parameter sets
        setParameterSets(paramSets);
    }
    
    /**
     * Sets up the benchmark environment.
     *
     * @param params The parameter values
     * @throws Exception if an error occurs during setup
     */
    private void setUp(Map<String, Object> params) throws Exception {
        // Create a unique test database and table name
        testDbName = "perf_db_" + UUID.randomUUID().toString().substring(0, 8);
        testTableName = "perf_table_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Get parameters
        StorageEngineTuner.TuningLevel tuningLevel = (StorageEngineTuner.TuningLevel) params.getOrDefault(
            "tuningLevel", StorageEngineTuner.TuningLevel.BALANCED);
        int cacheSize = (int) params.getOrDefault("cacheSize", 5000);
        
        // Create a StorageImpl instance with the specified parameters
        storage = (StorageImpl) StorageFactory.createStorage(StorageFactory.StorageType.REAL, true, cacheSize);
        
        // Get the tuner instance and configure it
        tuner = StorageEngineTuner.getInstance();
        tuner.setEnabled(true);
        tuner.setTuningLevel(tuningLevel);
        
        // Initialize the storage
        storage.init();
        
        // Create the test database and table
        storage.createDatabase(testDbName);
        storage.createTable(testDbName, testTableName);
        
        // Create a temporary directory for test files
        testDir = Files.createTempDirectory("storage-benchmark");
        testFile = testDir.resolve("test-data.bin");
        
        // Create a test file with some data
        createTestFile(testFile.toFile(), 1024 * 1024); // 1MB test file
    }
    
    /**
     * Tears down the benchmark environment.
     *
     * @throws Exception if an error occurs during teardown
     */
    private void tearDown() throws Exception {
        // Clean up test database if it exists
        if (storage.databaseExists(testDbName)) {
            storage.dropDatabase(testDbName);
        }
        
        // Shutdown the storage
        storage.shutdown();
        
        // Shutdown the tuner
        tuner.shutdown();
        
        // Clean up test files
        Files.deleteIfExists(testFile);
        Files.deleteIfExists(testDir);
    }
    
    /**
     * Create a test file with random data.
     *
     * @param file The file to create
     * @param size The size of the file in bytes
     * @throws IOException if an I/O error occurs
     */
    private void createTestFile(File file, int size) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            Random random = new Random(42); // Fixed seed for reproducibility
            
            int remaining = size;
            while (remaining > 0) {
                int chunk = Math.min(buffer.length, remaining);
                random.nextBytes(buffer);
                fos.write(buffer, 0, chunk);
                remaining -= chunk;
            }
            
            fos.flush();
        }
    }
    
    @Override
    protected BenchmarkResult runWithParams(String paramSetName, Map<String, Object> params) throws Exception {
        LOGGER.info("Running storage engine benchmark with parameter set: " + paramSetName);
        
        // Set up the benchmark environment
        setUp(params);
        
        try {
            // Create a benchmark result
            BenchmarkResult result = new BenchmarkResult(getName() + "-" + paramSetName);
            
            // Add parameter values to the result
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                result.setExecutionParam(entry.getKey(), entry.getValue());
            }
            
            // Run the benchmarks
            runInsertThroughputBenchmark(result);
            runSelectThroughputBenchmark(result);
            runOperationLatencyBenchmark(result);
            runScalabilityBenchmark(result);
            runConcurrencyBenchmark(result);
            runCacheEffectivenessBenchmark(result);
            runBloomFilterBenchmark(result);
            runCompactionBenchmark(result);
            
            return result;
        } finally {
            // Tear down the benchmark environment
            tearDown();
        }
    }
    
    /**
     * Runs the insert throughput benchmark.
     *
     * @param result The benchmark result to add metrics to
     */
    private void runInsertThroughputBenchmark(BenchmarkResult result) {
        LOGGER.info("Running insert throughput benchmark");
        
        // Measure time for small dataset
        long startTime = System.currentTimeMillis();
        insertTestData(SMALL_DATASET_SIZE);
        long smallDatasetTime = System.currentTimeMillis() - startTime;
        
        // Clean up
        storage.delete(testDbName, testTableName, "1", "1");
        
        // Measure time for medium dataset
        startTime = System.currentTimeMillis();
        insertTestData(MEDIUM_DATASET_SIZE);
        long mediumDatasetTime = System.currentTimeMillis() - startTime;
        
        // Clean up
        storage.delete(testDbName, testTableName, "1", "1");
        
        // Measure time for large dataset
        startTime = System.currentTimeMillis();
        insertTestData(LARGE_DATASET_SIZE);
        long largeDatasetTime = System.currentTimeMillis() - startTime;
        
        // Calculate throughput
        double smallThroughput = calculateThroughput(SMALL_DATASET_SIZE, smallDatasetTime);
        double mediumThroughput = calculateThroughput(MEDIUM_DATASET_SIZE, mediumDatasetTime);
        double largeThroughput = calculateThroughput(LARGE_DATASET_SIZE, largeDatasetTime);
        
        // Add metrics to the result
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("InsertThroughput")
            .setMetricName("smallDataset")
            .setValue(smallThroughput)
            .setUnit("ops/sec")
            .setType(BenchmarkMetric.MetricType.THROUGHPUT)
            .build());
        
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("InsertThroughput")
            .setMetricName("mediumDataset")
            .setValue(mediumThroughput)
            .setUnit("ops/sec")
            .setType(BenchmarkMetric.MetricType.THROUGHPUT)
            .build());
        
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("InsertThroughput")
            .setMetricName("largeDataset")
            .setValue(largeThroughput)
            .setUnit("ops/sec")
            .setType(BenchmarkMetric.MetricType.THROUGHPUT)
            .build());
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
            
            storage.insert(testDbName, testTableName, testData);
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
    
    /**
     * Runs the select throughput benchmark.
     *
     * @param result The benchmark result to add metrics to
     */
    private void runSelectThroughputBenchmark(BenchmarkResult result) {
        LOGGER.info("Running select throughput benchmark");
        
        // Insert test data
        insertTestData(LARGE_DATASET_SIZE);
        
        // Measure time for selecting all records
        long startTime = System.currentTimeMillis();
        List<String> results = storage.select(testDbName, testTableName, "*", "1", "1");
        long selectAllTime = System.currentTimeMillis() - startTime;
        
        // Measure time for selecting with a specific condition
        startTime = System.currentTimeMillis();
        results = storage.select(testDbName, testTableName, "*", "value", "50");
        long selectSpecificTime = System.currentTimeMillis() - startTime;
        
        // Add metrics to the result
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("SelectThroughput")
            .setMetricName("selectAll")
            .setValue(selectAllTime)
            .setUnit("ms")
            .setType(BenchmarkMetric.MetricType.LATENCY)
            .build());
        
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("SelectThroughput")
            .setMetricName("selectSpecific")
            .setValue(selectSpecificTime)
            .setUnit("ms")
            .setType(BenchmarkMetric.MetricType.LATENCY)
            .build());
    }
    
    /**
     * Runs the operation latency benchmark.
     *
     * @param result The benchmark result to add metrics to
     */
    private void runOperationLatencyBenchmark(BenchmarkResult result) {
        LOGGER.info("Running operation latency benchmark");
        
        // Measure latency for database creation
        long startTime = System.currentTimeMillis();
        String tempDbName = "temp_db_" + UUID.randomUUID().toString().substring(0, 8);
        storage.createDatabase(tempDbName);
        long createDbLatency = System.currentTimeMillis() - startTime;
        
        // Add metrics to the result
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("OperationLatency")
            .setMetricName("createDatabase")
            .setValue(createDbLatency)
            .setUnit("ms")
            .setType(BenchmarkMetric.MetricType.LATENCY)
            .build());
        
        // Clean up
        storage.dropDatabase(tempDbName);
    }
    
    /**
     * Runs the scalability benchmark.
     *
     * @param result The benchmark result to add metrics to
     */
    private void runScalabilityBenchmark(BenchmarkResult result) {
        LOGGER.info("Running scalability benchmark");
        
        // Test with increasingly larger datasets
        int[] datasetSizes = {100, 1000};
        
        for (int size : datasetSizes) {
            // Create a new table for each test
            String scalabilityTable = "scale_table_" + size;
            storage.createTable(testDbName, scalabilityTable);
            
            // Measure insert time
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < size; i++) {
                JSONObject testData = new JSONObject();
                testData.put("id", i);
                testData.put("name", "Record " + i);
                testData.put("value", i * 10);
                
                storage.insert(testDbName, scalabilityTable, testData);
            }
            long insertTime = System.currentTimeMillis() - startTime;
            
            // Calculate throughput
            double insertThroughput = calculateThroughput(size, insertTime);
            
            // Add metrics to the result
            result.addMetric(new BenchmarkMetric.Builder()
                .setBenchmarkName("Scalability")
                .setMetricName("insertThroughput_" + size)
                .setValue(insertThroughput)
                .setUnit("ops/sec")
                .setType(BenchmarkMetric.MetricType.THROUGHPUT)
                .build());
            
            // Clean up
            storage.dropTable(testDbName, scalabilityTable);
        }
    }
    
    /**
     * Runs the concurrency benchmark.
     *
     * @param result The benchmark result to add metrics to
     * @throws InterruptedException if the benchmark is interrupted
     */
    private void runConcurrencyBenchmark(BenchmarkResult result) throws InterruptedException {
        LOGGER.info("Running concurrency benchmark");
        
        // Create a thread pool
        int threadCount = getIntConfig("threads", Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // Create a countdown latch to synchronize the start of all threads
        CountDownLatch startLatch = new CountDownLatch(1);
        
        // Create a countdown latch to wait for all threads to complete
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        
        // Create an atomic counter to track successful operations
        AtomicInteger successCount = new AtomicInteger(0);
        
        // Start the threads
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Wait for the start signal
                    startLatch.await();
                    
                    // Perform operations
                    for (int j = 0; j < 10; j++) {
                        // Create a unique record
                        JSONObject testData = new JSONObject();
                        testData.put("thread_id", threadId);
                        testData.put("record_id", j);
                        testData.put("value", threadId * 1000 + j);
                        
                        // Insert the record
                        StorageResponseObject insertResponse = storage.insert(testDbName, testTableName, testData);
                        
                        if (insertResponse.success) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // Log exception
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
        
        // Add metrics to the result
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("Concurrency")
            .setMetricName("totalTime")
            .setValue(totalTime)
            .setUnit("ms")
            .setType(BenchmarkMetric.MetricType.LATENCY)
            .build());
    }
    
    /**
     * Runs the cache effectiveness benchmark.
     *
     * @param result The benchmark result to add metrics to
     */
    private void runCacheEffectivenessBenchmark(BenchmarkResult result) {
        LOGGER.info("Running cache effectiveness benchmark");
        
        // Insert test data
        insertTestData(MEDIUM_DATASET_SIZE);
        
        // Perform first select to populate cache
        long startTime = System.currentTimeMillis();
        List<String> firstResults = storage.select(testDbName, testTableName, "*", "1", "1");
        long firstSelectTime = System.currentTimeMillis() - startTime;
        
        // Perform second select which should use cache
        startTime = System.currentTimeMillis();
        List<String> secondResults = storage.select(testDbName, testTableName, "*", "1", "1");
        long secondSelectTime = System.currentTimeMillis() - startTime;
        
        // Calculate cache speedup factor
        double speedupFactor = firstSelectTime / (double) secondSelectTime;
        
        // Add metrics to the result
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("CacheEffectiveness")
            .setMetricName("speedupFactor")
            .setValue(speedupFactor)
            .setUnit("x")
            .setType(BenchmarkMetric.MetricType.CUSTOM)
            .build());
    }
    
    /**
     * Runs the bloom filter benchmark.
     *
     * @param result The benchmark result to add metrics to
     */
    private void runBloomFilterBenchmark(BenchmarkResult result) {
        LOGGER.info("Running bloom filter benchmark");
        
        // Create test data
        Map<String, byte[]> data = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            String key = "key" + i;
            byte[] value = ("value" + i).getBytes(StandardCharsets.UTF_8);
            data.put(key, value);
        }
        
        // Create an SSTable
        SSTable sstable = new SSTable(data);
        
        // Measure time to optimize with bloom filter
        long startTime = System.currentTimeMillis();
        tuner.optimizeSSTableWithBloomFilter(sstable);
        long optimizeTime = System.currentTimeMillis() - startTime;
        
        // Add metrics to the result
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("BloomFilter")
            .setMetricName("optimizeTime")
            .setValue(optimizeTime)
            .setUnit("ms")
            .setType(BenchmarkMetric.MetricType.LATENCY)
            .build());
    }
    
    /**
     * Runs the compaction benchmark.
     *
     * @param result The benchmark result to add metrics to
     */
    private void runCompactionBenchmark(BenchmarkResult result) {
        LOGGER.info("Running compaction benchmark");
        
        // Create test SSTables
        List<SSTable> sstables = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            Map<String, byte[]> data = new HashMap<>();
            for (int j = 0; j < 100; j++) {
                String key = "key" + (i * 100 + j);
                byte[] value = ("value" + (i * 100 + j)).getBytes(StandardCharsets.UTF_8);
                data.put(key, value);
            }
            
            SSTable sstable = new SSTable(data);
            sstable.setLevel(0); // All in level 0 to trigger compaction
            sstables.add(sstable);
        }
        
        // Measure time to perform compaction
        long startTime = System.currentTimeMillis();
        List<SSTable> compacted = tuner.performCompaction(sstables);
        long compactionTime = System.currentTimeMillis() - startTime;
        
        // Add metrics to the result
        result.addMetric(new BenchmarkMetric.Builder()
            .setBenchmarkName("Compaction")
            .setMetricName("compactionTime")
            .setValue(compactionTime)
            .setUnit("ms")
            .setType(BenchmarkMetric.MetricType.LATENCY)
            .build());
    }
}