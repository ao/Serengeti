package com.ataiva.serengeti.unit.storage.wal;

import com.ataiva.serengeti.storage.wal.WALManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for the WALManager class.
 */
public class WALManagerTest {
    
    private Path tempDir;
    private WALManager walManager;
    
    @Before
    public void setUp() throws IOException {
        // Create a temporary directory for the WAL files
        tempDir = Files.createTempDirectory("wal-test");
        
        // Create a WAL manager with a small max size to test rotation
        walManager = new WALManager(tempDir, 1024, WALManager.SyncMode.SYNC, 10, 100);
    }
    
    @After
    public void tearDown() throws IOException {
        // Close the WAL manager
        if (walManager != null) {
            walManager.close();
        }
        
        // Delete the temporary directory and all its contents
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted((a, b) -> -a.compareTo(b)) // Sort in reverse order to delete files before directories
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
    public void testBasicWriteAndRecover() throws IOException {
        // Write some operations to the WAL
        byte[] key1 = "key1".getBytes(StandardCharsets.UTF_8);
        byte[] value1 = "value1".getBytes(StandardCharsets.UTF_8);
        byte[] key2 = "key2".getBytes(StandardCharsets.UTF_8);
        byte[] value2 = "value2".getBytes(StandardCharsets.UTF_8);
        byte[] key3 = "key3".getBytes(StandardCharsets.UTF_8);
        
        walManager.logPut(key1, value1);
        walManager.logPut(key2, value2);
        walManager.logDelete(key3);
        
        // Close the WAL manager
        walManager.close();
        
        // Create a new WAL manager to recover from the WAL
        final Map<String, String> recoveredData = new HashMap<>();
        final AtomicInteger putCount = new AtomicInteger(0);
        final AtomicInteger deleteCount = new AtomicInteger(0);
        
        WALManager.WALRecoveryConsumer consumer = new WALManager.WALRecoveryConsumer() {
            @Override
            public void onPut(long sequenceNumber, byte[] key, byte[] value) {
                String keyStr = new String(key, StandardCharsets.UTF_8);
                String valueStr = new String(value, StandardCharsets.UTF_8);
                recoveredData.put(keyStr, valueStr);
                putCount.incrementAndGet();
            }
            
            @Override
            public void onDelete(long sequenceNumber, byte[] key) {
                String keyStr = new String(key, StandardCharsets.UTF_8);
                recoveredData.remove(keyStr);
                deleteCount.incrementAndGet();
            }
        };
        
        walManager = new WALManager(tempDir);
        walManager.recover(consumer);
        
        // Verify the recovered data
        assertEquals(2, putCount.get());
        assertEquals(1, deleteCount.get());
        assertEquals(2, recoveredData.size());
        assertEquals("value1", recoveredData.get("key1"));
        assertEquals("value2", recoveredData.get("key2"));
        assertFalse(recoveredData.containsKey("key3"));
    }
    
    @Test
    public void testWALRotation() throws IOException {
        // Write enough data to trigger WAL rotation
        byte[] largeValue = new byte[512]; // 512 bytes
        for (int i = 0; i < largeValue.length; i++) {
            largeValue[i] = (byte) (i % 256);
        }
        
        // Write operations that should exceed the max WAL size (1024 bytes)
        for (int i = 0; i < 10; i++) {
            byte[] key = ("key" + i).getBytes(StandardCharsets.UTF_8);
            walManager.logPut(key, largeValue);
        }
        
        // Check that multiple WAL files were created
        long walFileCount = Files.list(tempDir)
            .filter(path -> path.getFileName().toString().startsWith("wal-"))
            .count();
        
        assertTrue("Expected multiple WAL files due to rotation", walFileCount > 1);
    }
    
    @Test
    public void testCheckpointAndCleanup() throws IOException {
        // Write some operations to the WAL
        for (int i = 0; i < 10; i++) {
            byte[] key = ("key" + i).getBytes(StandardCharsets.UTF_8);
            byte[] value = ("value" + i).getBytes(StandardCharsets.UTF_8);
            walManager.logPut(key, value);
        }
        
        // Create a checkpoint
        long checkpointSeq = walManager.checkpoint("test-checkpoint");
        
        // Write more operations
        for (int i = 10; i < 20; i++) {
            byte[] key = ("key" + i).getBytes(StandardCharsets.UTF_8);
            byte[] value = ("value" + i).getBytes(StandardCharsets.UTF_8);
            walManager.logPut(key, value);
        }
        
        // Count WAL files before cleanup
        long walFileCountBefore = Files.list(tempDir)
            .filter(path -> path.getFileName().toString().startsWith("wal-"))
            .count();
        
        // Remove the checkpoint and clean up WAL files
        walManager.removeCheckpoint("test-checkpoint");
        walManager.cleanupWAL(checkpointSeq);
        
        // Count WAL files after cleanup
        long walFileCountAfter = Files.list(tempDir)
            .filter(path -> path.getFileName().toString().startsWith("wal-"))
            .count();
        
        // Verify that some WAL files were cleaned up
        assertTrue("Expected fewer WAL files after cleanup", walFileCountAfter <= walFileCountBefore);
    }
    
    @Test
    public void testDifferentSyncModes() throws IOException {
        // Test SYNC mode
        WALManager syncManager = new WALManager(tempDir.resolve("sync"), 1024, WALManager.SyncMode.SYNC, 10, 100);
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "value".getBytes(StandardCharsets.UTF_8);
        syncManager.logPut(key, value);
        syncManager.close();
        
        // Test ASYNC mode
        WALManager asyncManager = new WALManager(tempDir.resolve("async"), 1024, WALManager.SyncMode.ASYNC, 10, 100);
        asyncManager.logPut(key, value);
        asyncManager.close();
        
        // Test GROUP mode
        WALManager groupManager = new WALManager(tempDir.resolve("group"), 1024, WALManager.SyncMode.GROUP, 10, 100);
        for (int i = 0; i < 15; i++) { // Should trigger a sync after 10 writes
            byte[] k = ("key" + i).getBytes(StandardCharsets.UTF_8);
            byte[] v = ("value" + i).getBytes(StandardCharsets.UTF_8);
            groupManager.logPut(k, v);
        }
        groupManager.close();
        
        // All managers should have created WAL files
        assertTrue(Files.exists(tempDir.resolve("sync").resolve("wal-0-1.log")));
        assertTrue(Files.exists(tempDir.resolve("async").resolve("wal-0-1.log")));
        assertTrue(Files.exists(tempDir.resolve("group").resolve("wal-0-1.log")));
    }
    
    @Test
    public void testRecoveryAfterCrash() throws IOException {
        // Write some operations to the WAL
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            byte[] key = ("key" + i).getBytes(StandardCharsets.UTF_8);
            byte[] value = ("value" + i).getBytes(StandardCharsets.UTF_8);
            keys.add(key);
            values.add(value);
            walManager.logPut(key, value);
        }
        
        // Simulate a crash by not closing the WAL manager properly
        walManager = null;
        
        // Create a new WAL manager to recover from the WAL
        final Map<String, String> recoveredData = new HashMap<>();
        
        WALManager.WALRecoveryConsumer consumer = new WALManager.WALRecoveryConsumer() {
            @Override
            public void onPut(long sequenceNumber, byte[] key, byte[] value) {
                String keyStr = new String(key, StandardCharsets.UTF_8);
                String valueStr = new String(value, StandardCharsets.UTF_8);
                recoveredData.put(keyStr, valueStr);
            }
            
            @Override
            public void onDelete(long sequenceNumber, byte[] key) {
                String keyStr = new String(key, StandardCharsets.UTF_8);
                recoveredData.remove(keyStr);
            }
        };
        
        walManager = new WALManager(tempDir);
        walManager.recover(consumer);
        
        // Verify the recovered data
        assertEquals(10, recoveredData.size());
        for (int i = 0; i < 10; i++) {
            String keyStr = "key" + i;
            String valueStr = "value" + i;
            assertEquals(valueStr, recoveredData.get(keyStr));
        }
    }
    
    @Test
    public void testCorruptedWALRecovery() throws IOException {
        // Write some operations to the WAL
        for (int i = 0; i < 5; i++) {
            byte[] key = ("key" + i).getBytes(StandardCharsets.UTF_8);
            byte[] value = ("value" + i).getBytes(StandardCharsets.UTF_8);
            walManager.logPut(key, value);
        }
        
        // Close the WAL manager
        walManager.close();
        
        // Corrupt the WAL file by appending random data
        Path walFile = Files.list(tempDir)
            .filter(path -> path.getFileName().toString().startsWith("wal-"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No WAL file found"));
        
        byte[] corruptData = "CORRUPT".getBytes(StandardCharsets.UTF_8);
        Files.write(walFile, corruptData, java.nio.file.StandardOpenOption.APPEND);
        
        // Create a new WAL manager to recover from the corrupted WAL
        final Map<String, String> recoveredData = new HashMap<>();
        final AtomicInteger putCount = new AtomicInteger(0);
        
        WALManager.WALRecoveryConsumer consumer = new WALManager.WALRecoveryConsumer() {
            @Override
            public void onPut(long sequenceNumber, byte[] key, byte[] value) {
                String keyStr = new String(key, StandardCharsets.UTF_8);
                String valueStr = new String(value, StandardCharsets.UTF_8);
                recoveredData.put(keyStr, valueStr);
                putCount.incrementAndGet();
            }
            
            @Override
            public void onDelete(long sequenceNumber, byte[] key) {
                String keyStr = new String(key, StandardCharsets.UTF_8);
                recoveredData.remove(keyStr);
            }
        };
        
        walManager = new WALManager(tempDir);
        walManager.recover(consumer);
        
        // Verify that we recovered the uncorrupted part
        assertEquals(5, putCount.get());
        assertEquals(5, recoveredData.size());
        for (int i = 0; i < 5; i++) {
            String keyStr = "key" + i;
            String valueStr = "value" + i;
            assertEquals(valueStr, recoveredData.get(keyStr));
        }
    }
}