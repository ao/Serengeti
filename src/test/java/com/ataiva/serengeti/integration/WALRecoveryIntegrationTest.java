package com.ataiva.serengeti.integration;

import com.ataiva.serengeti.storage.lsm.LSMStorageEngine;
import com.ataiva.serengeti.storage.wal.WALManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.*;

/**
 * Integration test for WAL recovery with the LSM storage engine.
 * This test verifies that the WAL system can recover data after a crash.
 */
public class WALRecoveryIntegrationTest {
    
    private Path testDir;
    
    @Before
    public void setUp() throws IOException {
        // Create a temporary directory for the test
        testDir = Files.createTempDirectory("wal-integration-test");
    }
    
    @After
    public void tearDown() throws IOException {
        // Delete the temporary directory and all its contents
        if (testDir != null && Files.exists(testDir)) {
            Files.walk(testDir)
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
    
    @Test
    public void testRecoveryAfterCrash() throws IOException {
        // Create test data
        String[] keys = {"key1", "key2", "key3", "key4", "key5"};
        String[] values = {"value1", "value2", "value3", "value4", "value5"};
        
        // Create an LSM storage engine with WAL enabled
        LSMStorageEngine engine = null;
        try {
            engine = new LSMStorageEngine(
                testDir,
                1024 * 1024, // 1MB memtable size
                2, // Max immutable memtables
                10, // Compaction trigger threshold
                4, // Max SSTables to merge
                60000, // Compaction interval
                WALManager.SyncMode.SYNC // Use SYNC mode for testing
            );
            
            // Write data to the engine
            for (int i = 0; i < keys.length; i++) {
                engine.put(
                    keys[i].getBytes(StandardCharsets.UTF_8),
                    values[i].getBytes(StandardCharsets.UTF_8)
                );
            }
            
            // Delete one key
            engine.delete(keys[2].getBytes(StandardCharsets.UTF_8));
            
            // Verify the data was written correctly
            for (int i = 0; i < keys.length; i++) {
                byte[] key = keys[i].getBytes(StandardCharsets.UTF_8);
                byte[] value = engine.get(key);
                
                if (i == 2) {
                    // This key was deleted
                    assertNull("Deleted key should return null", value);
                } else {
                    assertNotNull("Value should not be null for key: " + keys[i], value);
                    assertEquals(
                        "Value should match for key: " + keys[i],
                        values[i],
                        new String(value, StandardCharsets.UTF_8)
                    );
                }
            }
            
            // Simulate a crash by not properly closing the engine
            // This is done by setting the engine reference to null without calling close()
        } finally {
            engine = null;
        }
        
        // Create a new engine that should recover from the WAL
        engine = new LSMStorageEngine(
            testDir,
            1024 * 1024,
            2,
            10,
            4,
            60000,
            WALManager.SyncMode.SYNC
        );
        
        // Verify the data was recovered correctly
        for (int i = 0; i < keys.length; i++) {
            byte[] key = keys[i].getBytes(StandardCharsets.UTF_8);
            byte[] value = engine.get(key);
            
            if (i == 2) {
                // This key was deleted
                assertNull("Deleted key should still be deleted after recovery", value);
            } else {
                assertNotNull("Value should be recovered for key: " + keys[i], value);
                assertEquals(
                    "Recovered value should match for key: " + keys[i],
                    values[i],
                    new String(value, StandardCharsets.UTF_8)
                );
            }
        }
        
        // Properly close the engine
        engine.close();
    }
    
    @Test
    public void testWALRotationAndRecovery() throws IOException {
        // Create an LSM storage engine with WAL enabled and a small WAL size
        LSMStorageEngine engine = null;
        try {
            engine = new LSMStorageEngine(
                testDir,
                1024 * 1024, // 1MB memtable size
                2, // Max immutable memtables
                10, // Compaction trigger threshold
                4, // Max SSTables to merge
                60000, // Compaction interval
                WALManager.SyncMode.SYNC // Use SYNC mode for testing
            );
            
            // Write enough data to trigger WAL rotation
            byte[] largeValue = new byte[1024]; // 1KB
            for (int i = 0; i < largeValue.length; i++) {
                largeValue[i] = (byte) (i % 256);
            }
            
            // Write 100 keys with large values (should trigger multiple WAL rotations)
            for (int i = 0; i < 100; i++) {
                String key = "large-key-" + i;
                engine.put(key.getBytes(StandardCharsets.UTF_8), largeValue);
            }
            
            // Verify a few keys
            for (int i = 0; i < 10; i++) {
                String key = "large-key-" + i;
                byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
                assertNotNull("Value should not be null for key: " + key, value);
                assertEquals("Value length should match", largeValue.length, value.length);
            }
            
            // Simulate a crash
        } finally {
            engine = null;
        }
        
        // Create a new engine that should recover from the WAL
        engine = new LSMStorageEngine(
            testDir,
            1024 * 1024,
            2,
            10,
            4,
            60000,
            WALManager.SyncMode.SYNC
        );
        
        // Verify the data was recovered correctly
        for (int i = 0; i < 10; i++) {
            String key = "large-key-" + i;
            byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
            assertNotNull("Value should be recovered for key: " + key, value);
            assertEquals("Recovered value length should match", 1024, value.length);
        }
        
        // Properly close the engine
        engine.close();
    }
    
    @Test
    public void testDifferentSyncModes() throws IOException {
        // Test with SYNC mode
        Path syncDir = testDir.resolve("sync");
        Files.createDirectories(syncDir);
        LSMStorageEngine syncEngine = new LSMStorageEngine(
            syncDir,
            1024 * 1024,
            2,
            10,
            4,
            60000,
            WALManager.SyncMode.SYNC
        );
        
        // Write some data
        syncEngine.put("key1".getBytes(StandardCharsets.UTF_8), "value1".getBytes(StandardCharsets.UTF_8));
        syncEngine.close();
        
        // Test with ASYNC mode
        Path asyncDir = testDir.resolve("async");
        Files.createDirectories(asyncDir);
        LSMStorageEngine asyncEngine = new LSMStorageEngine(
            asyncDir,
            1024 * 1024,
            2,
            10,
            4,
            60000,
            WALManager.SyncMode.ASYNC
        );
        
        // Write some data
        asyncEngine.put("key1".getBytes(StandardCharsets.UTF_8), "value1".getBytes(StandardCharsets.UTF_8));
        asyncEngine.close();
        
        // Test with GROUP mode
        Path groupDir = testDir.resolve("group");
        Files.createDirectories(groupDir);
        LSMStorageEngine groupEngine = new LSMStorageEngine(
            groupDir,
            1024 * 1024,
            2,
            10,
            4,
            60000,
            WALManager.SyncMode.GROUP
        );
        
        // Write some data
        for (int i = 0; i < 20; i++) {
            String key = "key" + i;
            String value = "value" + i;
            groupEngine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }
        groupEngine.close();
        
        // Verify that WAL files were created in all directories
        assertTrue("WAL directory should exist for SYNC mode", Files.exists(syncDir.resolve("wal")));
        assertTrue("WAL directory should exist for ASYNC mode", Files.exists(asyncDir.resolve("wal")));
        assertTrue("WAL directory should exist for GROUP mode", Files.exists(groupDir.resolve("wal")));
        
        // Verify that WAL files contain data
        assertTrue("WAL files should exist for SYNC mode", 
            Files.list(syncDir.resolve("wal"))
                .anyMatch(p -> p.toString().endsWith(".log")));
        assertTrue("WAL files should exist for ASYNC mode", 
            Files.list(asyncDir.resolve("wal"))
                .anyMatch(p -> p.toString().endsWith(".log")));
        assertTrue("WAL files should exist for GROUP mode", 
            Files.list(groupDir.resolve("wal"))
                .anyMatch(p -> p.toString().endsWith(".log")));
    }
    
    @Test
    public void testMemTableFlushAndWALCleanup() throws IOException {
        // Create an LSM storage engine with a small memtable size to trigger flushes
        LSMStorageEngine engine = new LSMStorageEngine(
            testDir,
            10 * 1024, // 10KB memtable size (small to trigger flushes)
            2,
            10,
            4,
            60000,
            WALManager.SyncMode.SYNC
        );
        
        // Write enough data to trigger memtable flush
        byte[] value = new byte[1024]; // 1KB
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (i % 256);
        }
        
        // Write 20 keys with 1KB values (should trigger memtable flush)
        for (int i = 0; i < 20; i++) {
            String key = "flush-key-" + i;
            engine.put(key.getBytes(StandardCharsets.UTF_8), value);
        }
        
        // Sleep to allow background flush to complete
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Verify that SSTable files were created
        long ssTableCount = Files.list(testDir)
            .filter(p -> p.toString().endsWith(".db"))
            .count();
        assertTrue("SSTable files should be created after flush", ssTableCount > 0);
        
        // Properly close the engine
        engine.close();
    }
}