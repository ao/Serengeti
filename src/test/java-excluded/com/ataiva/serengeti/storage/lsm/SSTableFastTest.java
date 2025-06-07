package com.ataiva.serengeti.storage.lsm;

import com.ataiva.serengeti.utils.LSMFastTestBase;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast tests for the SSTable class.
 * These tests focus on core functionality and run quickly.
 */
@DisplayName("SSTable Fast Tests")
@Tag("fast")
public class SSTableFastTest extends LSMFastTestBase {
    
    private MemTable memTable;
    private static final long MAX_SIZE = 1024; // 1KB for fast tests
    
    @BeforeEach
    public void setUpSSTable() {
        memTable = createMemTable(MAX_SIZE);
    }
    
    @Test
    @DisplayName("Create SSTable from MemTable and read data back")
    void testCreateAndRead() throws IOException {
        // Create test data
        byte[] key1 = createTestKey("key1");
        byte[] value1 = createTestValue("value1");
        byte[] key2 = createTestKey("key2");
        byte[] value2 = createTestValue("value2");
        
        // Put data in MemTable
        memTable.put(key1, value1);
        memTable.put(key2, value2);
        
        // Create SSTable
        String fileId = UUID.randomUUID().toString();
        SSTable ssTable = SSTable.create(memTable, tempDir, fileId);
        
        // Read data back
        byte[] retrievedValue1 = ssTable.get(key1);
        byte[] retrievedValue2 = ssTable.get(key2);
        
        // Verify retrieved data
        assertArrayEquals(value1, retrievedValue1);
        assertArrayEquals(value2, retrievedValue2);
        
        // Close the SSTable
        ssTable.close();
    }
    
    @Test
    @DisplayName("Bloom filter correctly identifies non-existent keys")
    void testBloomFilter() throws IOException {
        // Create test data
        byte[] key1 = createTestKey("key1");
        byte[] value1 = createTestValue("value1");
        byte[] key2 = createTestKey("key2");
        byte[] value2 = createTestValue("value2");
        
        // Put data in MemTable
        memTable.put(key1, value1);
        memTable.put(key2, value2);
        
        // Create SSTable
        String fileId = UUID.randomUUID().toString();
        SSTable ssTable = SSTable.create(memTable, tempDir, fileId);
        
        // Check for non-existent key
        byte[] nonExistentKey = createTestKey("nonexistent");
        assertFalse(ssTable.mightContain(nonExistentKey));
        
        // Verify existing keys are detected
        assertTrue(ssTable.mightContain(key1));
        assertTrue(ssTable.mightContain(key2));
        
        // Close the SSTable
        ssTable.close();
    }
    
    @Test
    @DisplayName("Tombstones are handled correctly")
    void testTombstones() throws IOException {
        // Create test data
        byte[] key1 = createTestKey("key1");
        byte[] value1 = createTestValue("value1");
        byte[] key2 = createTestKey("key2");
        byte[] value2 = createTestValue("value2");
        
        // Put data in MemTable
        memTable.put(key1, value1);
        memTable.put(key2, value2);
        
        // Delete key1
        memTable.delete(key1);
        
        // Create SSTable
        String fileId = UUID.randomUUID().toString();
        SSTable ssTable = SSTable.create(memTable, tempDir, fileId);
        
        // Read data back
        byte[] retrievedValue1 = ssTable.get(key1);
        byte[] retrievedValue2 = ssTable.get(key2);
        
        // Verify key1 is deleted (null)
        assertNull(retrievedValue1);
        
        // Verify key2 is still there
        assertArrayEquals(value2, retrievedValue2);
        
        // Close the SSTable
        ssTable.close();
    }
    
    @Test
    @DisplayName("SSTable metadata is correct")
    void testMetadata() throws IOException {
        // Create test data
        byte[] key1 = createTestKey("key1");
        byte[] value1 = createTestValue("value1");
        byte[] key2 = createTestKey("key2");
        byte[] value2 = createTestValue("value2");
        
        // Put data in MemTable
        memTable.put(key1, value1);
        memTable.put(key2, value2);
        
        // Create SSTable
        String fileId = UUID.randomUUID().toString();
        SSTable ssTable = SSTable.create(memTable, tempDir, fileId);
        
        // Get metadata
        SSTable.Metadata metadata = ssTable.getMetadata();
        
        // Verify metadata
        assertEquals(2, metadata.getEntryCount());
        assertEquals(1, metadata.getVersion());
        assertTrue(metadata.getFileSize() > 0);
        
        // Close the SSTable
        ssTable.close();
    }
}