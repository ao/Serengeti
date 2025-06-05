package com.ataiva.serengeti.unit.storage.lsm;

import com.ataiva.serengeti.storage.lsm.LSMStorageEngine;
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
 * Tests for the compaction functionality in the LSM storage engine.
 */
public class CompactionTest extends TestBase {
    
    private Path tempDir;
    private LSMStorageEngine engine;
    
    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("lsm-compaction-test");
        
        // Create an LSM storage engine with a low compaction threshold
        // to ensure compaction happens during the test
        engine = new LSMStorageEngine(
            tempDir,
            10 * 1024, // 10KB memtable size (small for testing)
            2, // Max immutable memtables
            3, // Compaction threshold (small for testing)
            2, // Max SSTables to merge
            1000 // Compaction interval (1 second for testing)
        );
    }
    
    @After
    public void tearDown() throws IOException {
        // Close the engine
        if (engine != null) {
            engine.close();
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
    }
    
    /**
     * Tests that compaction correctly merges multiple SSTables.
     */
    @Test
    public void testCompaction() throws IOException, InterruptedException {
        // Insert some data to create the first SSTable
        for (int i = 1; i <= 100; i++) {
            String key = String.format("key%03d", i);
            String value = "value" + i;
            engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }
        
        // Force flush by inserting a large value
        byte[] largeValue = new byte[15 * 1024]; // 15KB (larger than memtable size)
        Arrays.fill(largeValue, (byte) 'X');
        engine.put("large-key1".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Insert more data to create the second SSTable
        for (int i = 101; i <= 200; i++) {
            String key = String.format("key%03d", i);
            String value = "value" + i;
            engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }
        
        // Force flush again
        engine.put("large-key2".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Insert more data to create the third SSTable
        for (int i = 201; i <= 300; i++) {
            String key = String.format("key%03d", i);
            String value = "value" + i;
            engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }
        
        // Force flush again
        engine.put("large-key3".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Update some keys to create tombstones and updates
        for (int i = 50; i <= 150; i++) {
            String key = String.format("key%03d", i);
            if (i % 2 == 0) {
                // Delete even keys
                engine.delete(key.getBytes(StandardCharsets.UTF_8));
            } else {
                // Update odd keys
                String value = "updated-value" + i;
                engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
            }
        }
        
        // Force flush again
        engine.put("large-key4".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Count the number of SSTable files before compaction
        int ssTableCountBefore = countSSTableFiles();
        System.out.println("SSTable count before compaction: " + ssTableCountBefore);
        assertTrue("Should have at least 4 SSTables before compaction", ssTableCountBefore >= 4);
        
        // Trigger compaction and wait for it to complete
        engine.triggerCompactionCheck();
        
        // Wait for compaction to complete (may need multiple cycles)
        for (int i = 0; i < 10; i++) {
            Thread.sleep(2000); // Wait 2 seconds
            int currentCount = countSSTableFiles();
            System.out.println("Current SSTable count: " + currentCount);
            if (currentCount < ssTableCountBefore) {
                break; // Compaction has occurred
            }
        }
        
        // Count the number of SSTable files after compaction
        int ssTableCountAfter = countSSTableFiles();
        System.out.println("SSTable count after compaction: " + ssTableCountAfter);
        assertTrue("Compaction should reduce the number of SSTables", ssTableCountAfter < ssTableCountBefore);
        
        // Verify that all data is still accessible
        // Check deleted keys
        for (int i = 50; i <= 150; i += 2) {
            String key = String.format("key%03d", i);
            byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
            assertNull("Key " + key + " should be deleted", value);
        }
        
        // Check updated keys
        for (int i = 51; i <= 149; i += 2) {
            String key = String.format("key%03d", i);
            byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
            assertNotNull("Key " + key + " should exist", value);
            assertEquals("updated-value" + i, new String(value, StandardCharsets.UTF_8));
        }
        
        // Check untouched keys
        for (int i = 201; i <= 300; i++) {
            String key = String.format("key%03d", i);
            byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
            assertNotNull("Key " + key + " should exist", value);
            assertEquals("value" + i, new String(value, StandardCharsets.UTF_8));
        }
        
        // Check large keys
        for (int i = 1; i <= 4; i++) {
            String key = "large-key" + i;
            byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
            assertNotNull("Key " + key + " should exist", value);
            assertEquals(15 * 1024, value.length);
        }
    }
    
    /**
     * Tests that compaction correctly handles tombstones.
     */
    @Test
    public void testCompactionWithTombstones() throws IOException, InterruptedException {
        // Insert some data
        for (int i = 1; i <= 100; i++) {
            String key = String.format("key%03d", i);
            String value = "value" + i;
            engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }
        
        // Force flush
        byte[] largeValue = new byte[15 * 1024];
        Arrays.fill(largeValue, (byte) 'X');
        engine.put("large-key1".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Delete all keys
        for (int i = 1; i <= 100; i++) {
            String key = String.format("key%03d", i);
            engine.delete(key.getBytes(StandardCharsets.UTF_8));
        }
        
        // Force flush again
        engine.put("large-key2".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Insert new data with the same keys
        for (int i = 1; i <= 100; i++) {
            String key = String.format("key%03d", i);
            String value = "new-value" + i;
            engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }
        
        // Force flush again
        engine.put("large-key3".getBytes(StandardCharsets.UTF_8), largeValue);
        
        // Trigger compaction and wait for it to complete
        engine.triggerCompactionCheck();
        Thread.sleep(5000); // Wait for compaction to complete
        
        // Verify that all data has the latest values
        for (int i = 1; i <= 100; i++) {
            String key = String.format("key%03d", i);
            byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
            assertNotNull("Key " + key + " should exist", value);
            assertEquals("new-value" + i, new String(value, StandardCharsets.UTF_8));
        }
    }
    
    /**
     * Counts the number of SSTable files in the temporary directory.
     */
    private int countSSTableFiles() throws IOException {
        List<Path> ssTableFiles = Files.list(tempDir)
            .filter(path -> path.toString().endsWith(".db"))
            .collect(Collectors.toList());
        return ssTableFiles.size();
    }
}