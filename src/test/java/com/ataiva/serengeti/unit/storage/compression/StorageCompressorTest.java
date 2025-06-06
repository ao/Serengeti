package com.ataiva.serengeti.unit.storage.compression;

import com.ataiva.serengeti.storage.compression.StorageCompressor;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Unit tests for the StorageCompressor class.
 */
public class StorageCompressorTest {

    /**
     * Test compression and decompression with GZIP.
     */
    @Test
    public void testGzipCompression() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        
        String originalData = "This is a test string that should be compressed and then decompressed.";
        
        // Compress the data
        byte[] compressedData = compressor.compress(originalData);
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Verify that the compressed data is smaller than the original data
        assertTrue("Compressed data should be smaller than original data",
                compressedData.length < originalData.getBytes(StandardCharsets.UTF_8).length);
        
        // Decompress the data
        String decompressedData = compressor.decompress(compressedData);
        
        // Verify that the decompressed data matches the original data
        assertEquals("Decompressed data should match original data", originalData, decompressedData);
    }
    
    /**
     * Test compression and decompression with LZ4.
     */
    @Test
    public void testLz4Compression() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4);
        
        String originalData = "This is a test string that should be compressed and then decompressed using LZ4.";
        
        // Compress the data
        byte[] compressedData = compressor.compress(originalData);
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Verify that the compressed data is smaller than the original data
        // Note: For very small strings, LZ4 might not compress well due to overhead
        // So we don't assert size here
        
        // Decompress the data
        String decompressedData = compressor.decompress(compressedData);
        
        // Verify that the decompressed data matches the original data
        assertEquals("Decompressed data should match original data", originalData, decompressedData);
    }
    
    /**
     * Test compression and decompression with Snappy.
     */
    @Test
    public void testSnappyCompression() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY);
        
        String originalData = "This is a test string that should be compressed and then decompressed using Snappy.";
        
        // Compress the data
        byte[] compressedData = compressor.compress(originalData);
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Verify that the compressed data is smaller than the original data
        // Note: For very small strings, Snappy might not compress well due to overhead
        // So we don't assert size here
        
        // Decompress the data
        String decompressedData = compressor.decompress(compressedData);
        
        // Verify that the decompressed data matches the original data
        assertEquals("Decompressed data should match original data", originalData, decompressedData);
    }
    
    /**
     * Test compression and decompression with no compression.
     */
    @Test
    public void testNoCompression() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.NONE);
        
        String originalData = "This is a test string that should not be compressed.";
        
        // Compress the data
        byte[] compressedData = compressor.compress(originalData);
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Verify that the compressed data is the same size as the original data
        assertEquals("Compressed data should be the same size as original data", 
                originalData.getBytes(StandardCharsets.UTF_8).length, compressedData.length);
        
        // Decompress the data
        String decompressedData = compressor.decompress(compressedData);
        
        // Verify that the decompressed data matches the original data
        assertEquals("Decompressed data should match original data", originalData, decompressedData);
    }
    
    /**
     * Test compression and decompression with different compression levels.
     */
    @Test
    public void testCompressionLevels() {
        // Create a large string to compress
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("This is line ").append(i).append(" of the test data.\n");
        }
        String originalData = sb.toString();
        
        // Compress with different levels
        StorageCompressor lowCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP, 1);
        StorageCompressor highCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP, 9);
        
        byte[] lowCompressedData = lowCompressor.compress(originalData);
        byte[] highCompressedData = highCompressor.compress(originalData);
        
        // Verify that both compressed data are not null
        assertNotNull("Low compressed data should not be null", lowCompressedData);
        assertNotNull("High compressed data should not be null", highCompressedData);
        
        // Verify that both compressed data are smaller than the original data
        assertTrue("Low compressed data should be smaller than original data", 
                lowCompressedData.length < originalData.getBytes(StandardCharsets.UTF_8).length);
        assertTrue("High compressed data should be smaller than original data", 
                highCompressedData.length < originalData.getBytes(StandardCharsets.UTF_8).length);
        
        // Verify that high compression level produces smaller data than low compression level
        assertTrue("High compression level should produce smaller data than low compression level", 
                highCompressedData.length <= lowCompressedData.length);
        
        // Decompress both data
        String lowDecompressedData = lowCompressor.decompress(lowCompressedData);
        String highDecompressedData = highCompressor.decompress(highCompressedData);
        
        // Verify that both decompressed data match the original data
        assertEquals("Low decompressed data should match original data", originalData, lowDecompressedData);
        assertEquals("High decompressed data should match original data", originalData, highDecompressedData);
    }
    
    /**
     * Test compression and decompression with empty data.
     */
    @Test
    public void testEmptyData() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        
        // Compress empty string
        byte[] compressedData = compressor.compress("");
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Verify that the compressed data is empty
        assertEquals("Compressed data should be empty", 0, compressedData.length);
        
        // Decompress the data
        String decompressedData = compressor.decompress(compressedData);
        
        // Verify that the decompressed data is an empty string
        assertEquals("Decompressed data should be an empty string", "", decompressedData);
    }
    
    /**
     * Test compression and decompression with null data.
     */
    @Test
    public void testNullData() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        
        // Compress null
        byte[] compressedData = compressor.compress(null);
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Verify that the compressed data is empty
        assertEquals("Compressed data should be empty", 0, compressedData.length);
        
        // Decompress null
        String decompressedData = compressor.decompress(null);
        
        // Verify that the decompressed data is an empty string
        assertEquals("Decompressed data should be an empty string", "", decompressedData);
    }
    
    /**
     * Test compression and decompression with binary data.
     */
    @Test
    public void testBinaryData() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        
        // Create random binary data
        byte[] originalBytes = new byte[1000];
        new Random().nextBytes(originalBytes);
        String originalData = new String(originalBytes, StandardCharsets.ISO_8859_1);
        
        // Compress the data
        byte[] compressedData = compressor.compress(originalData);
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Decompress the data
        String decompressedData = compressor.decompress(compressedData);
        
        // Verify that the decompressed data matches the original data
        assertEquals("Decompressed data should match original data", originalData, decompressedData);
    }
    
    /**
     * Test compression and decompression with large data using GZIP.
     */
    @Test
    public void testLargeDataGzip() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        
        // Create a large string with repeating pattern (highly compressible)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            sb.append("This is a repeating pattern that should compress well. ");
        }
        String originalData = sb.toString();
        
        // Compress the data
        byte[] compressedData = compressor.compress(originalData);
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Verify that the compressed data is much smaller than the original data
        assertTrue("Compressed data should be much smaller than original data",
                (double) compressedData.length / originalData.getBytes(StandardCharsets.UTF_8).length < 0.1);
        
        // Decompress the data
        String decompressedData = compressor.decompress(compressedData);
        
        // Verify that the decompressed data matches the original data
        assertEquals("Decompressed data should match original data", originalData, decompressedData);
    }
    
    /**
     * Test compression and decompression with large data using LZ4.
     */
    @Test
    public void testLargeDataLz4() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4);
        
        // Create a large string with repeating pattern (highly compressible)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            sb.append("This is a repeating pattern that should compress well with LZ4. ");
        }
        String originalData = sb.toString();
        
        // Compress the data
        byte[] compressedData = compressor.compress(originalData);
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Verify that the compressed data is much smaller than the original data
        assertTrue("Compressed data should be much smaller than original data",
                (double) compressedData.length / originalData.getBytes(StandardCharsets.UTF_8).length < 0.1);
        
        // Decompress the data
        String decompressedData = compressor.decompress(compressedData);
        
        // Verify that the decompressed data matches the original data
        assertEquals("Decompressed data should match original data", originalData, decompressedData);
    }
    
    /**
     * Test compression and decompression with large data using Snappy.
     */
    @Test
    public void testLargeDataSnappy() {
        StorageCompressor compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY);
        
        // Create a large string with repeating pattern (highly compressible)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            sb.append("This is a repeating pattern that should compress well with Snappy. ");
        }
        String originalData = sb.toString();
        
        // Compress the data
        byte[] compressedData = compressor.compress(originalData);
        
        // Verify that the compressed data is not null
        assertNotNull("Compressed data should not be null", compressedData);
        
        // Verify that the compressed data is much smaller than the original data
        assertTrue("Compressed data should be much smaller than original data",
                (double) compressedData.length / originalData.getBytes(StandardCharsets.UTF_8).length < 0.1);
        
        // Decompress the data
        String decompressedData = compressor.decompress(compressedData);
        
        // Verify that the decompressed data matches the original data
        assertEquals("Decompressed data should match original data", originalData, decompressedData);
    }
    
    /**
     * Test LZ4 compression with different compression levels.
     */
    @Test
    public void testLz4CompressionLevels() {
        // Create a large string to compress
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("This is line ").append(i).append(" of the test data for LZ4 compression.\n");
        }
        String originalData = sb.toString();
        
        // Compress with different levels
        StorageCompressor lowCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4, 1);
        StorageCompressor highCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4, 9);
        
        byte[] lowCompressedData = lowCompressor.compress(originalData);
        byte[] highCompressedData = highCompressor.compress(originalData);
        
        // Verify that both compressed data are not null
        assertNotNull("Low compressed data should not be null", lowCompressedData);
        assertNotNull("High compressed data should not be null", highCompressedData);
        
        // Verify that both compressed data are smaller than the original data
        assertTrue("Low compressed data should be smaller than original data",
                lowCompressedData.length < originalData.getBytes(StandardCharsets.UTF_8).length);
        assertTrue("High compressed data should be smaller than original data",
                highCompressedData.length < originalData.getBytes(StandardCharsets.UTF_8).length);
        
        // Verify that high compression level produces smaller or equal data than low compression level
        // Note: In LZ4, higher compression level might not always produce smaller output
        // but should generally be more efficient for highly compressible data
        
        // Decompress both data
        String lowDecompressedData = lowCompressor.decompress(lowCompressedData);
        String highDecompressedData = highCompressor.decompress(highCompressedData);
        
        // Verify that both decompressed data match the original data
        assertEquals("Low decompressed data should match original data", originalData, lowDecompressedData);
        assertEquals("High decompressed data should match original data", originalData, highDecompressedData);
    }
    
    /**
     * Test Snappy compression with different compression levels.
     * Note: Snappy doesn't actually use compression levels, but the StorageCompressor
     * class should handle the compression level parameter gracefully.
     */
    @Test
    public void testSnappyCompressionLevels() {
        // Create a large string to compress
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("This is line ").append(i).append(" of the test data for Snappy compression.\n");
        }
        String originalData = sb.toString();
        
        // Compress with different levels (should be ignored by Snappy)
        StorageCompressor lowCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY, 1);
        StorageCompressor highCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY, 9);
        
        byte[] lowCompressedData = lowCompressor.compress(originalData);
        byte[] highCompressedData = highCompressor.compress(originalData);
        
        // Verify that both compressed data are not null
        assertNotNull("Low compressed data should not be null", lowCompressedData);
        assertNotNull("High compressed data should not be null", highCompressedData);
        
        // Verify that both compressed data are smaller than the original data
        assertTrue("Low compressed data should be smaller than original data",
                lowCompressedData.length < originalData.getBytes(StandardCharsets.UTF_8).length);
        assertTrue("High compressed data should be smaller than original data",
                highCompressedData.length < originalData.getBytes(StandardCharsets.UTF_8).length);
        
        // Since Snappy ignores compression levels, the compressed sizes should be very similar
        // We don't assert exact equality because there might be small variations due to implementation details
        
        // Decompress both data
        String lowDecompressedData = lowCompressor.decompress(lowCompressedData);
        String highDecompressedData = highCompressor.decompress(highCompressedData);
        
        // Verify that both decompressed data match the original data
        assertEquals("Low decompressed data should match original data", originalData, lowDecompressedData);
        assertEquals("High decompressed data should match original data", originalData, highDecompressedData);
    }
}