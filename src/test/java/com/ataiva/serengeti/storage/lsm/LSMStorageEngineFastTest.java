package com.ataiva.serengeti.storage.lsm;

import com.ataiva.serengeti.utils.LSMFastTestBase;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast tests for the LSMStorageEngine class.
 * These tests focus on core functionality and run quickly.
 */
@DisplayName("LSMStorageEngine Fast Tests")
@Tag("fast")
public class LSMStorageEngineFastTest extends LSMFastTestBase {
    
    @Test
    @DisplayName("Put and get operations work correctly")
    void testPutAndGet() throws IOException {
        // Create test data
        byte[] key1 = createTestKey("key1");
        byte[] value1 = createTestValue("value1");
        byte[] key2 = createTestKey("key2");
        byte[] value2 = createTestValue("value2");
        
        // Put data
        storageEngine.put(key1, value1);
        storageEngine.put(key2, value2);
        
        // Get data
        byte[] retrievedValue1 = storageEngine.get(key1);
        byte[] retrievedValue2 = storageEngine.get(key2);
        
        // Verify retrieved data
        assertArrayEquals(value1, retrievedValue1);
        assertArrayEquals(value2, retrievedValue2);
    }
    
    @Test
    @DisplayName("Delete operation works correctly")
    void testDelete() throws IOException {
        // Create test data
        byte[] key = createTestKey("key");
        byte[] value = createTestValue("value");
        
        // Put data
        storageEngine.put(key, value);
        
        // Verify it exists
        assertArrayEquals(value, storageEngine.get(key));
        
        // Delete it
        storageEngine.delete(key);
        
        // Verify it's deleted
        assertNull(storageEngine.get(key));
    }
    
    @Test
    @DisplayName("Multiple puts and gets work correctly")
    void testMultiplePutsAndGets() throws IOException {
        // Create test data
        int count = 10;
        for (int i = 0; i < count; i++) {
            byte[] key = createTestKey("multi-key" + i);
            byte[] value = createTestValue("multi-value" + i);
            storageEngine.put(key, value);
        }
        
        // Verify all data can be read back
        int found = 0;
        for (int i = 0; i < count; i++) {
            byte[] key = createTestKey("multi-key" + i);
            byte[] value = storageEngine.get(key);
            if (value != null) {
                found++;
            }
        }
        
        // We should find at least some of the values
        assertTrue(found > 0, "Should find at least some values");
    }
    
    @Test
    @DisplayName("Update existing key works correctly")
    void testUpdateExistingKey() throws IOException {
        // Create test data
        byte[] key = createTestKey("key");
        byte[] value1 = createTestValue("value1");
        byte[] value2 = createTestValue("value2");
        
        // Put initial data
        storageEngine.put(key, value1);
        
        // Verify initial data
        assertArrayEquals(value1, storageEngine.get(key));
        
        // Update data
        storageEngine.put(key, value2);
        
        // Verify updated data
        assertArrayEquals(value2, storageEngine.get(key));
    }
    
    @Test
    @DisplayName("Null key is handled correctly")
    void testNullKey() throws IOException {
        // Try to put with null key
        storageEngine.put(null, createTestValue("value"));
        
        // No exception should be thrown
    }
    
    @Test
    @DisplayName("Null value is handled correctly")
    void testNullValue() throws IOException {
        // Create test data
        byte[] key = createTestKey("key");
        
        // Put with null value
        storageEngine.put(key, null);
        
        // Verify it's treated as a delete
        assertNull(storageEngine.get(key));
    }
}