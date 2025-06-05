package com.ataiva.serengeti.integration;

import com.ataiva.serengeti.storage.lsm.LSMStorageEngine;
import com.ataiva.serengeti.storage.lsm.LSMStorageScheduler;
import com.ataiva.serengeti.utils.TestBase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Integration tests for the LSMStorageScheduler.
 */
public class LSMStorageSchedulerIntegrationTest extends TestBase {
    
    private Path tempDir;
    private LSMStorageScheduler scheduler;
    
    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("lsm-scheduler-test");
        
        // Create a data directory structure
        Files.createDirectories(tempDir.resolve("data").resolve("lsm"));
        
        // Set system property for data path
        System.setProperty("serengeti.data.path", tempDir.resolve("data").toString());
        
        // Create a scheduler with a low compaction threshold and interval
        scheduler = new LSMStorageScheduler(3, 2, 2000);
    }
    
    @After
    public void tearDown() throws IOException {
        // Close the scheduler
        if (scheduler != null) {
            scheduler.close();
        }
        
        // Delete the temporary directory
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("Failed to delete: " + path);
                    }
                });
        }
        
        // Reset system property
        System.clearProperty("serengeti.data.path");
    }
    
    /**
     * Tests that the LSMStorageScheduler correctly triggers compaction.
     */
    @Test
    public void testSchedulerTriggersCompaction() throws IOException, InterruptedException {
        // Initialize the scheduler
        scheduler.init();
        
        // Get an LSM engine for a test database
        LSMStorageEngine engine = scheduler.getLSMEngine("test_db");
        
        // Insert data to create multiple SSTables
        byte[] largeValue = new byte[100 * 1024]; // 100KB
        Arrays.fill(largeValue, (byte) 'X');
        
        // First batch of data
        for (int i = 1; i <= 100; i++) {
            String key = String.format("key%03d", i);
            String value = "value" + i;
            engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }
        engine.put("large-key1".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Second batch of data
        for (int i = 101; i <= 200; i++) {
            String key = String.format("key%03d", i);
            String value = "value" + i;
            engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }
        engine.put("large-key2".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Third batch of data
        for (int i = 201; i <= 300; i++) {
            String key = String.format("key%03d", i);
            String value = "value" + i;
            engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }
        engine.put("large-key3".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Fourth batch with updates and deletes
        for (int i = 1; i <= 100; i++) {
            String key = String.format("key%03d", i);
            if (i % 2 == 0) {
                engine.delete(key.getBytes(StandardCharsets.UTF_8));
            } else {
                String value = "updated-value" + i;
                engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
            }
        }
        engine.put("large-key4".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Count SSTable files before compaction
        int ssTableCountBefore = countSSTableFiles("test_db");
        System.out.println("SSTable count before compaction: " + ssTableCountBefore);
        assertTrue("Should have at least 4 SSTables before compaction", ssTableCountBefore >= 4);
        
        // Wait for the scheduler to trigger compaction
        // The scheduler checks every 2 seconds (as configured)
        Thread.sleep(10000); // Wait 10 seconds
        
        // Count SSTable files after compaction
        int ssTableCountAfter = countSSTableFiles("test_db");
        System.out.println("SSTable count after compaction: " + ssTableCountAfter);
        assertTrue("Compaction should reduce the number of SSTables", ssTableCountAfter < ssTableCountBefore);
        
        // Verify data integrity
        // Check deleted keys
        for (int i = 2; i <= 100; i += 2) {
            String key = String.format("key%03d", i);
            byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
            assertNull("Key " + key + " should be deleted", value);
        }
        
        // Check updated keys
        for (int i = 1; i <= 99; i += 2) {
            String key = String.format("key%03d", i);
            byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
            assertNotNull("Key " + key + " should exist", value);
            assertEquals("updated-value" + i, new String(value, StandardCharsets.UTF_8));
        }
        
        // Check untouched keys
        for (int i = 101; i <= 300; i++) {
            String key = String.format("key%03d", i);
            byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
            assertNotNull("Key " + key + " should exist", value);
            assertEquals("value" + i, new String(value, StandardCharsets.UTF_8));
        }
    }
    
    /**
     * Counts the number of SSTable files for a specific database.
     */
    private int countSSTableFiles(String dbName) throws IOException {
        Path dbPath = tempDir.resolve("data").resolve("lsm").resolve(dbName);
        if (!Files.exists(dbPath)) {
            return 0;
        }
        
        List<Path> ssTableFiles = Files.list(dbPath)
            .filter(path -> path.toString().endsWith(".db"))
            .collect(Collectors.toList());
        return ssTableFiles.size();
    }
}