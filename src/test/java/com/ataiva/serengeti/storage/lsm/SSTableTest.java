package com.ataiva.serengeti.storage.lsm;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SSTable class.
 */
@DisplayName("SSTable Tests")
@Tag("fast")
public class SSTableTest {
    
    @TempDir
    Path tempDir;
    
    private MemTable memTable;
    private static final long MAX_SIZE = 1024 * 1024; // 1MB
    
    @BeforeEach
    void setUp() {
        memTable = new MemTable(MAX_SIZE);
    }
    
    @Test
    @DisplayName("Create SSTable from MemTable and read data back")
    void testCreateAndRead() throws IOException {
        // Create test data
        byte[] key1 = "key1".getBytes(StandardCharsets.UTF_8);
        byte[] value1 = "value1".getBytes(StandardCharsets.UTF_8);
        byte[] key2 = "key2".getBytes(StandardCharsets.UTF_8);
        byte[] value2 = "value2".getBytes(StandardCharsets.UTF_8);
        
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
        byte[] key1 = "key1".getBytes(StandardCharsets.UTF_8);
        byte[] value1 = "value1".getBytes(StandardCharsets.UTF_8);
        byte[] key2 = "key2".getBytes(StandardCharsets.UTF_8);
        byte[] value2 = "value2".getBytes(StandardCharsets.UTF_8);
        
        // Put data in MemTable
        memTable.put(key1, value1);
        memTable.put(key2, value2);
        
        // Create SSTable
        String fileId = UUID.randomUUID().toString();
        SSTable ssTable = SSTable.create(memTable, tempDir, fileId);
        
        // Check for non-existent key
        byte[] nonExistentKey = "nonexistent".getBytes(StandardCharsets.UTF_8);
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
        byte[] key1 = "key1".getBytes(StandardCharsets.UTF_8);
        byte[] value1 = "value1".getBytes(StandardCharsets.UTF_8);
        byte[] key2 = "key2".getBytes(StandardCharsets.UTF_8);
        byte[] value2 = "value2".getBytes(StandardCharsets.UTF_8);
        
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
        byte[] key1 = "key1".getBytes(StandardCharsets.UTF_8);
        byte[] value1 = "value1".getBytes(StandardCharsets.UTF_8);
        byte[] key2 = "key2".getBytes(StandardCharsets.UTF_8);
        byte[] value2 = "value2".getBytes(StandardCharsets.UTF_8);
        
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
    
    @Test
    @DisplayName("Open existing SSTable file")
    void testOpenExisting() throws IOException {
        // Create test data
        byte[] key1 = "key1".getBytes(StandardCharsets.UTF_8);
        byte[] value1 = "value1".getBytes(StandardCharsets.UTF_8);
        byte[] key2 = "key2".getBytes(StandardCharsets.UTF_8);
        byte[] value2 = "value2".getBytes(StandardCharsets.UTF_8);
        
        // Put data in MemTable
        memTable.put(key1, value1);
        memTable.put(key2, value2);
        
        // Create SSTable
        String fileId = UUID.randomUUID().toString();
        SSTable ssTable = SSTable.create(memTable, tempDir, fileId);
        
        // Close the SSTable
        Path filePath = tempDir.resolve("sstable-" + fileId + ".db");
        ssTable.close();
        
        // Open the SSTable again
        SSTable reopenedSSTable = new SSTable(filePath);
        
        // Read data back
        byte[] retrievedValue1 = reopenedSSTable.get(key1);
        byte[] retrievedValue2 = reopenedSSTable.get(key2);
        
        // Verify retrieved data
        assertArrayEquals(value1, retrievedValue1);
        assertArrayEquals(value2, retrievedValue2);
        
        // Close the reopened SSTable
        reopenedSSTable.close();
    }
    
    @Test
    @DisplayName("Bloom filter has expected false positive rate")
    void testBloomFilterFalsePositiveRate() {
        // Create a bloom filter with 10,000 expected insertions and 1% false positive rate
        SSTable.BloomFilter bloomFilter = new SSTable.BloomFilter(10_000, 0.01);
        
        // Insert 10,000 items
        for (int i = 0; i < 10_000; i++) {
            byte[] key = ("key" + i).getBytes(StandardCharsets.UTF_8);
            bloomFilter.add(key);
        }
        
        // Test 10,000 items that are not in the set
        int falsePositives = 0;
        for (int i = 10_000; i < 20_000; i++) {
            byte[] key = ("key" + i).getBytes(StandardCharsets.UTF_8);
            if (bloomFilter.mightContain(key)) {
                falsePositives++;
            }
        }
        
        // Verify false positive rate is close to expected
        double falsePositiveRate = (double) falsePositives / 10_000;
        assertTrue(falsePositiveRate < 0.02, "False positive rate should be close to 1%");
    }
}