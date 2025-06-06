package com.ataiva.serengeti.integration;

import com.ataiva.serengeti.storage.Storage;
import com.ataiva.serengeti.storage.StorageImpl;
import com.ataiva.serengeti.storage.compression.StorageCompressor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Integration tests for the StorageCompressor class with the StorageImpl class.
 * These tests verify that compression works correctly when integrated with the storage system.
 */
public class StorageCompressionIntegrationTest {

    private static final String TEST_DB_NAME = "test_compression_db";
    private static final String TEST_TABLE_NAME = "test_compression_table";
    private static final String TEST_DATA_DIR = "test_data";
    
    private Storage storage;
    private Path testDataPath;
    
    @Before
    public void setUp() throws Exception {
        // Create test data directory
        testDataPath = Paths.get(TEST_DATA_DIR);
        Files.createDirectories(testDataPath);
        
        // Initialize storage with different compression algorithms
        storage = new StorageImpl(TEST_DATA_DIR);
        
        // Create test database and table
        storage.createDatabase(TEST_DB_NAME);
        
        // Define table schema
        Map<String, String> schema = new HashMap<>();
        schema.put("id", "STRING");
        schema.put("name", "STRING");
        schema.put("data", "STRING");
        
        storage.createTable(TEST_DB_NAME, TEST_TABLE_NAME, schema);
    }
    
    @After
    public void tearDown() throws Exception {
        // Clean up test data
        deleteDirectory(testDataPath.toFile());
    }
    
    /**
     * Test storing and retrieving data with GZIP compression.
     */
    @Test
    public void testStorageWithGzipCompression() throws Exception {
        // Configure storage to use GZIP compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP));
        
        // Insert test data
        Map<String, Object> record = createLargeRecord("gzip_test");
        String id = (String) record.get("id");
        
        storage.insertRecord(TEST_DB_NAME, TEST_TABLE_NAME, record);
        
        // Retrieve the record
        Map<String, Object> retrievedRecord = storage.getRecord(TEST_DB_NAME, TEST_TABLE_NAME, "id", id);
        
        // Verify the record was retrieved correctly
        assertNotNull("Retrieved record should not be null", retrievedRecord);
        assertEquals("Record ID should match", id, retrievedRecord.get("id"));
        assertEquals("Record name should match", record.get("name"), retrievedRecord.get("name"));
        assertEquals("Record data should match", record.get("data"), retrievedRecord.get("data"));
    }
    
    /**
     * Test storing and retrieving data with LZ4 compression.
     */
    @Test
    public void testStorageWithLz4Compression() throws Exception {
        // Configure storage to use LZ4 compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4));
        
        // Insert test data
        Map<String, Object> record = createLargeRecord("lz4_test");
        String id = (String) record.get("id");
        
        storage.insertRecord(TEST_DB_NAME, TEST_TABLE_NAME, record);
        
        // Retrieve the record
        Map<String, Object> retrievedRecord = storage.getRecord(TEST_DB_NAME, TEST_TABLE_NAME, "id", id);
        
        // Verify the record was retrieved correctly
        assertNotNull("Retrieved record should not be null", retrievedRecord);
        assertEquals("Record ID should match", id, retrievedRecord.get("id"));
        assertEquals("Record name should match", record.get("name"), retrievedRecord.get("name"));
        assertEquals("Record data should match", record.get("data"), retrievedRecord.get("data"));
    }
    
    /**
     * Test storing and retrieving data with Snappy compression.
     */
    @Test
    public void testStorageWithSnappyCompression() throws Exception {
        // Configure storage to use Snappy compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY));
        
        // Insert test data
        Map<String, Object> record = createLargeRecord("snappy_test");
        String id = (String) record.get("id");
        
        storage.insertRecord(TEST_DB_NAME, TEST_TABLE_NAME, record);
        
        // Retrieve the record
        Map<String, Object> retrievedRecord = storage.getRecord(TEST_DB_NAME, TEST_TABLE_NAME, "id", id);
        
        // Verify the record was retrieved correctly
        assertNotNull("Retrieved record should not be null", retrievedRecord);
        assertEquals("Record ID should match", id, retrievedRecord.get("id"));
        assertEquals("Record name should match", record.get("name"), retrievedRecord.get("name"));
        assertEquals("Record data should match", record.get("data"), retrievedRecord.get("data"));
    }
    
    /**
     * Test storing and retrieving data with no compression.
     */
    @Test
    public void testStorageWithNoCompression() throws Exception {
        // Configure storage to use no compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.NONE));
        
        // Insert test data
        Map<String, Object> record = createLargeRecord("no_compression_test");
        String id = (String) record.get("id");
        
        storage.insertRecord(TEST_DB_NAME, TEST_TABLE_NAME, record);
        
        // Retrieve the record
        Map<String, Object> retrievedRecord = storage.getRecord(TEST_DB_NAME, TEST_TABLE_NAME, "id", id);
        
        // Verify the record was retrieved correctly
        assertNotNull("Retrieved record should not be null", retrievedRecord);
        assertEquals("Record ID should match", id, retrievedRecord.get("id"));
        assertEquals("Record name should match", record.get("name"), retrievedRecord.get("name"));
        assertEquals("Record data should match", record.get("data"), retrievedRecord.get("data"));
    }
    
    /**
     * Test comparing file sizes with different compression algorithms.
     */
    @Test
    public void testCompressionFileSizes() throws Exception {
        // Create a large record
        Map<String, Object> record = createLargeRecord("compression_comparison");
        String id = (String) record.get("id");
        
        // Test with no compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.NONE));
        storage.insertRecord(TEST_DB_NAME, TEST_TABLE_NAME + "_none", record);
        long noCompressionSize = getTableSize(TEST_DB_NAME, TEST_TABLE_NAME + "_none");
        
        // Test with GZIP compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP));
        storage.insertRecord(TEST_DB_NAME, TEST_TABLE_NAME + "_gzip", record);
        long gzipSize = getTableSize(TEST_DB_NAME, TEST_TABLE_NAME + "_gzip");
        
        // Test with LZ4 compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4));
        storage.insertRecord(TEST_DB_NAME, TEST_TABLE_NAME + "_lz4", record);
        long lz4Size = getTableSize(TEST_DB_NAME, TEST_TABLE_NAME + "_lz4");
        
        // Test with Snappy compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY));
        storage.insertRecord(TEST_DB_NAME, TEST_TABLE_NAME + "_snappy", record);
        long snappySize = getTableSize(TEST_DB_NAME, TEST_TABLE_NAME + "_snappy");
        
        // Verify that compressed sizes are smaller than uncompressed size
        assertTrue("GZIP compressed size should be smaller than uncompressed size", gzipSize < noCompressionSize);
        assertTrue("LZ4 compressed size should be smaller than uncompressed size", lz4Size < noCompressionSize);
        assertTrue("Snappy compressed size should be smaller than uncompressed size", snappySize < noCompressionSize);
        
        // Log the compression ratios
        System.out.println("Compression Ratios:");
        System.out.println("No Compression: " + noCompressionSize + " bytes (100%)");
        System.out.println("GZIP: " + gzipSize + " bytes (" +
                          String.format("%.2f", 100.0 * gzipSize / noCompressionSize) + "%)");
        System.out.println("LZ4: " + lz4Size + " bytes (" +
                          String.format("%.2f", 100.0 * lz4Size / noCompressionSize) + "%)");
        System.out.println("Snappy: " + snappySize + " bytes (" +
                          String.format("%.2f", 100.0 * snappySize / noCompressionSize) + "%)");
    }
    
    /**
     * Test changing compression algorithm on existing data.
     */
    @Test
    public void testChangingCompressionAlgorithm() throws Exception {
        // Insert data with GZIP compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP));
        Map<String, Object> record = createLargeRecord("changing_compression");
        String id = (String) record.get("id");
        
        storage.insertRecord(TEST_DB_NAME, TEST_TABLE_NAME, record);
        
        // Change to LZ4 compression
        ((StorageImpl) storage).setCompressor(new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4));
        
        // Retrieve the record (should still work even though it was compressed with GZIP)
        Map<String, Object> retrievedRecord = storage.getRecord(TEST_DB_NAME, TEST_TABLE_NAME, "id", id);
        
        // Verify the record was retrieved correctly
        assertNotNull("Retrieved record should not be null", retrievedRecord);
        assertEquals("Record ID should match", id, retrievedRecord.get("id"));
        assertEquals("Record name should match", record.get("name"), retrievedRecord.get("name"));
        assertEquals("Record data should match", record.get("data"), retrievedRecord.get("data"));
        
        // Update the record (should now be stored with LZ4 compression)
        retrievedRecord.put("name", "Updated Name");
        storage.updateRecord(TEST_DB_NAME, TEST_TABLE_NAME, "id", id, retrievedRecord);
        
        // Retrieve the updated record
        Map<String, Object> updatedRecord = storage.getRecord(TEST_DB_NAME, TEST_TABLE_NAME, "id", id);
        
        // Verify the record was updated correctly
        assertNotNull("Updated record should not be null", updatedRecord);
        assertEquals("Updated record ID should match", id, updatedRecord.get("id"));
        assertEquals("Updated record name should match", "Updated Name", updatedRecord.get("name"));
        assertEquals("Updated record data should match", record.get("data"), updatedRecord.get("data"));
    }
    
    /**
     * Helper method to create a large record with random data.
     */
    private Map<String, Object> createLargeRecord(String namePrefix) {
        Map<String, Object> record = new HashMap<>();
        String id = UUID.randomUUID().toString();
        String name = namePrefix + "_" + id.substring(0, 8);
        
        // Create a large data field (100 KB)
        StringBuilder dataBuilder = new StringBuilder(100 * 1024);
        Random random = new Random();
        for (int i = 0; i < 1000; i++) {
            dataBuilder.append("Line ").append(i).append(": ")
                      .append(UUID.randomUUID().toString())
                      .append(" - Random value: ")
                      .append(random.nextDouble())
                      .append("\n");
        }
        
        record.put("id", id);
        record.put("name", name);
        record.put("data", dataBuilder.toString());
        
        return record;
    }
    
    /**
     * Helper method to get the size of a table's data files.
     */
    private long getTableSize(String dbName, String tableName) {
        Path tablePath = testDataPath.resolve(dbName).resolve(tableName);
        File tableDir = tablePath.toFile();
        
        if (!tableDir.exists() || !tableDir.isDirectory()) {
            return 0;
        }
        
        long size = 0;
        File[] files = tableDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                }
            }
        }
        
        return size;
    }
    
    /**
     * Helper method to recursively delete a directory.
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}