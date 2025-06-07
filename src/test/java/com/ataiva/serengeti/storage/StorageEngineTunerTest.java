package com.ataiva.serengeti.storage;

import com.ataiva.serengeti.storage.StorageEngineTuner.Region;
import com.ataiva.serengeti.storage.io.AsyncIOManager;
import com.ataiva.serengeti.storage.lsm.SSTable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Test class for StorageEngineTuner
 */
public class StorageEngineTunerTest {
    
    private StorageEngineTuner tuner;
    private Path testDir;
    private Path testFile;
    private String testFilePath;
    
    @Before
    public void setUp() throws IOException {
        tuner = StorageEngineTuner.getInstance();
        tuner.setEnabled(true);
        tuner.setTuningLevel(StorageEngineTuner.TuningLevel.BALANCED);
        
        // Create a temporary directory for test files
        testDir = Files.createTempDirectory("storage-tuner-test");
        testFile = testDir.resolve("test-data.bin");
        testFilePath = testFile.toString();
        
        // Create a test file with some data
        createTestFile(testFile.toFile(), 1024 * 1024); // 1MB test file
    }
    
    @After
    public void tearDown() throws IOException {
        // Shutdown the tuner
        tuner.shutdown();
        
        // Clean up test files
        Files.deleteIfExists(testFile);
        Files.deleteIfExists(testDir);
    }
    
    /**
     * Create a test file with random data
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
    
    @Test
    public void testBloomFilterOptimization() {
        // Create a test SSTable
        Map<String, byte[]> data = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            String key = "key" + i;
            byte[] value = ("value" + i).getBytes(StandardCharsets.UTF_8);
            data.put(key, value);
        }
        
        SSTable sstable = new SSTable(data);
        
        // Optimize with bloom filter
        tuner.optimizeSSTableWithBloomFilter(sstable);
        
        // Verify bloom filter was created
        assertNotNull("Bloom filter should be created", sstable.getBloomFilter());
    }
    
    @Test
    public void testCompaction() {
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
        
        // Check if compaction should be triggered
        boolean shouldCompact = tuner.shouldTriggerCompaction(sstables);
        
        // Perform compaction
        List<SSTable> compacted = tuner.performCompaction(sstables);
        
        // Verify compaction results
        assertNotNull("Compacted SSTables should not be null", compacted);
        assertTrue("Compaction should reduce the number of SSTables", compacted.size() < sstables.size());
    }
    
    @Test
    public void testAsyncRead() throws InterruptedException {
        // Create a latch to wait for async operation
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ByteBuffer> result = new AtomicReference<>();
        AtomicBoolean success = new AtomicBoolean(false);
        
        // Perform async read
        tuner.readDataAsync(testFilePath, 0, 1024, new AsyncIOManager.IOCallback<ByteBuffer>() {
            @Override
            public void onSuccess(ByteBuffer data) {
                result.set(data);
                success.set(true);
                latch.countDown();
            }
            
            @Override
            public void onFailure(Throwable error) {
                error.printStackTrace();
                latch.countDown();
            }
        });
        
        // Wait for completion
        assertTrue("Async read should complete within timeout", latch.await(5, TimeUnit.SECONDS));
        
        // Verify results
        assertTrue("Async read should succeed", success.get());
        assertNotNull("Read result should not be null", result.get());
        assertEquals("Read result should have correct size", 1024, result.get().remaining());
    }
    
    @Test
    public void testAsyncWrite() throws InterruptedException, IOException {
        // Create data to write
        byte[] data = new byte[1024];
        new Random(42).nextBytes(data);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        // Create a latch to wait for async operation
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        
        // Perform async write
        tuner.writeDataAsync(testFilePath, 1024, buffer, new AsyncIOManager.IOCallback<Integer>() {
            @Override
            public void onSuccess(Integer bytesWritten) {
                success.set(true);
                latch.countDown();
            }
            
            @Override
            public void onFailure(Throwable error) {
                error.printStackTrace();
                latch.countDown();
            }
        });
        
        // Wait for completion
        assertTrue("Async write should complete within timeout", latch.await(5, TimeUnit.SECONDS));
        
        // Verify results
        assertTrue("Async write should succeed", success.get());
        
        // Flush I/O operations
        assertTrue("I/O flush should succeed", tuner.flushIO());
        
        // Verify the data was written correctly
        byte[] readData = new byte[1024];
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(testFilePath, "r")) {
            file.seek(1024);
            file.readFully(readData);
        }
        
        assertArrayEquals("Written data should match read data", data, readData);
    }
    
    @Test
    public void testBatchRead() throws InterruptedException {
        // Create regions to read
        List<Region> regions = new ArrayList<>();
        regions.add(new Region(0, 1024));
        regions.add(new Region(2048, 1024));
        regions.add(new Region(4096, 1024));
        
        // Create a latch to wait for async operation
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Map<Region, ByteBuffer>> results = new AtomicReference<>();
        AtomicBoolean success = new AtomicBoolean(false);
        
        // Perform batch read
        tuner.batchReadAsync(testFilePath, regions, new AsyncIOManager.IOCallback<Map<Region, ByteBuffer>>() {
            @Override
            public void onSuccess(Map<Region, ByteBuffer> data) {
                results.set(data);
                success.set(true);
                latch.countDown();
            }
            
            @Override
            public void onFailure(Throwable error) {
                error.printStackTrace();
                latch.countDown();
            }
        });
        
        // Wait for completion
        assertTrue("Batch read should complete within timeout", latch.await(5, TimeUnit.SECONDS));
        
        // Verify results
        assertTrue("Batch read should succeed", success.get());
        assertNotNull("Read results should not be null", results.get());
        assertEquals("Should have results for all regions", regions.size(), results.get().size());
        
        // Verify each region's data
        for (Region region : regions) {
            ByteBuffer buffer = results.get().get(region);
            assertNotNull("Should have data for region " + region.getPosition(), buffer);
            assertEquals("Region data should have correct size", region.getSize(), buffer.remaining());
        }
    }
    
    @Test
    public void testCacheHitAndMiss() throws InterruptedException {
        // First read should be a cache miss
        CountDownLatch latch1 = new CountDownLatch(1);
        AtomicLong firstReadTime = new AtomicLong(0);
        
        long startTime1 = System.nanoTime();
        tuner.readDataAsync(testFilePath, 0, 1024, new AsyncIOManager.IOCallback<ByteBuffer>() {
            @Override
            public void onSuccess(ByteBuffer data) {
                firstReadTime.set(System.nanoTime() - startTime1);
                latch1.countDown();
            }
            
            @Override
            public void onFailure(Throwable error) {
                error.printStackTrace();
                latch1.countDown();
            }
        });
        
        assertTrue("First read should complete within timeout", latch1.await(5, TimeUnit.SECONDS));
        
        // Second read of the same data should be a cache hit and faster
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicLong secondReadTime = new AtomicLong(0);
        
        long startTime2 = System.nanoTime();
        tuner.readDataAsync(testFilePath, 0, 1024, new AsyncIOManager.IOCallback<ByteBuffer>() {
            @Override
            public void onSuccess(ByteBuffer data) {
                secondReadTime.set(System.nanoTime() - startTime2);
                latch2.countDown();
            }
            
            @Override
            public void onFailure(Throwable error) {
                error.printStackTrace();
                latch2.countDown();
            }
        });
        
        assertTrue("Second read should complete within timeout", latch2.await(5, TimeUnit.SECONDS));
        
        // The second read should be faster due to caching
        assertTrue("Second read should be faster than first read", secondReadTime.get() < firstReadTime.get());
        
        // Get cache statistics
        Map<String, Object> stats = tuner.getStatistics();
        assertTrue("Cache hit count should be positive", ((Number)stats.get("hitCount")).longValue() > 0);
    }
    
    @Test
    public void testTuningLevels() {
        // Test performance tuning level
        tuner.setTuningLevel(StorageEngineTuner.TuningLevel.PERFORMANCE);
        assertEquals("Tuning level should be PERFORMANCE", StorageEngineTuner.TuningLevel.PERFORMANCE, tuner.getTuningLevel());
        
        // Test balanced tuning level
        tuner.setTuningLevel(StorageEngineTuner.TuningLevel.BALANCED);
        assertEquals("Tuning level should be BALANCED", StorageEngineTuner.TuningLevel.BALANCED, tuner.getTuningLevel());
        
        // Test resource efficient tuning level
        tuner.setTuningLevel(StorageEngineTuner.TuningLevel.RESOURCE_EFFICIENT);
        assertEquals("Tuning level should be RESOURCE_EFFICIENT", StorageEngineTuner.TuningLevel.RESOURCE_EFFICIENT, tuner.getTuningLevel());
    }
    
    @Test
    public void testDisablingTuner() throws InterruptedException {
        // Disable the tuner
        tuner.setEnabled(false);
        assertFalse("Tuner should be disabled", tuner.isEnabled());
        
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
            sstable.setLevel(0);
            sstables.add(sstable);
        }
        
        // Check if compaction should be triggered (should be false when disabled)
        boolean shouldCompact = tuner.shouldTriggerCompaction(sstables);
        assertFalse("Compaction should not be triggered when tuner is disabled", shouldCompact);
        
        // Perform compaction (should return original SSTables when disabled)
        List<SSTable> compacted = tuner.performCompaction(sstables);
        assertEquals("Compaction should not occur when tuner is disabled", sstables.size(), compacted.size());
        
        // Re-enable the tuner
        tuner.setEnabled(true);
        assertTrue("Tuner should be enabled", tuner.isEnabled());
    }
}