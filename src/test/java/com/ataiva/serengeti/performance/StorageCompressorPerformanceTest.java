package com.ataiva.serengeti.performance;

import com.ataiva.serengeti.storage.compression.StorageCompressor;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.Ignore;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Performance tests for the StorageCompressor class.
 * These tests compare the performance of different compression algorithms.
 * 
 * Note: These tests are marked with @Ignore by default as they are meant to be run
 * manually when evaluating compression performance, not as part of the regular test suite.
 */
@Ignore("Performance tests are not meant to be run as part of the regular test suite")
public class StorageCompressorPerformanceTest {

    private static final Logger LOGGER = Logger.getLogger(StorageCompressorPerformanceTest.class.getName());
    
    // Test data sizes
    private static final int SMALL_DATA_SIZE = 10 * 1024; // 10 KB
    private static final int MEDIUM_DATA_SIZE = 1 * 1024 * 1024; // 1 MB
    private static final int LARGE_DATA_SIZE = 10 * 1024 * 1024; // 10 MB
    
    // Test data types
    private static String smallRepeatingData;
    private static String mediumRepeatingData;
    private static String largeRepeatingData;
    private static String smallRandomData;
    private static String mediumRandomData;
    private static String largeRandomData;
    
    @BeforeClass
    public static void setUp() {
        // Generate test data
        smallRepeatingData = generateRepeatingData(SMALL_DATA_SIZE);
        mediumRepeatingData = generateRepeatingData(MEDIUM_DATA_SIZE);
        largeRepeatingData = generateRepeatingData(LARGE_DATA_SIZE);
        
        smallRandomData = generateRandomData(SMALL_DATA_SIZE);
        mediumRandomData = generateRandomData(MEDIUM_DATA_SIZE);
        largeRandomData = generateRandomData(LARGE_DATA_SIZE);
    }
    
    /**
     * Generate repeating pattern data of approximately the specified size.
     */
    private static String generateRepeatingData(int size) {
        StringBuilder sb = new StringBuilder(size);
        String pattern = "This is a repeating pattern that should compress well. ";
        
        while (sb.length() < size) {
            sb.append(pattern);
        }
        
        return sb.toString();
    }
    
    /**
     * Generate random data of approximately the specified size.
     */
    private static String generateRandomData(int size) {
        Random random = new Random(42); // Fixed seed for reproducibility
        StringBuilder sb = new StringBuilder(size);
        
        // Generate random printable ASCII characters
        while (sb.length() < size) {
            char c = (char) (32 + random.nextInt(95)); // ASCII 32-126
            sb.append(c);
        }
        
        return sb.toString();
    }
    
    /**
     * Test compression performance with small repeating data.
     */
    @Test
    public void testCompressionPerformanceSmallRepeatingData() {
        LOGGER.info("Testing compression performance with small repeating data (" + 
                    smallRepeatingData.length() + " bytes)");
        
        // Test GZIP compression
        StorageCompressor gzipCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        long gzipStartTime = System.nanoTime();
        byte[] gzipCompressed = gzipCompressor.compress(smallRepeatingData);
        long gzipEndTime = System.nanoTime();
        double gzipCompressionTime = (gzipEndTime - gzipStartTime) / 1_000_000.0; // ms
        
        // Test LZ4 compression
        StorageCompressor lz4Compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4);
        long lz4StartTime = System.nanoTime();
        byte[] lz4Compressed = lz4Compressor.compress(smallRepeatingData);
        long lz4EndTime = System.nanoTime();
        double lz4CompressionTime = (lz4EndTime - lz4StartTime) / 1_000_000.0; // ms
        
        // Test Snappy compression
        StorageCompressor snappyCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY);
        long snappyStartTime = System.nanoTime();
        byte[] snappyCompressed = snappyCompressor.compress(smallRepeatingData);
        long snappyEndTime = System.nanoTime();
        double snappyCompressionTime = (snappyEndTime - snappyStartTime) / 1_000_000.0; // ms
        
        // Log results
        logCompressionResults("Small Repeating", smallRepeatingData.getBytes(StandardCharsets.UTF_8).length,
                             gzipCompressed.length, gzipCompressionTime,
                             lz4Compressed.length, lz4CompressionTime,
                             snappyCompressed.length, snappyCompressionTime);
    }
    
    /**
     * Test decompression performance with small repeating data.
     */
    @Test
    public void testDecompressionPerformanceSmallRepeatingData() {
        // Compress data first
        StorageCompressor gzipCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        byte[] gzipCompressed = gzipCompressor.compress(smallRepeatingData);
        
        StorageCompressor lz4Compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4);
        byte[] lz4Compressed = lz4Compressor.compress(smallRepeatingData);
        
        StorageCompressor snappyCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY);
        byte[] snappyCompressed = snappyCompressor.compress(smallRepeatingData);
        
        // Test GZIP decompression
        long gzipStartTime = System.nanoTime();
        String gzipDecompressed = gzipCompressor.decompress(gzipCompressed);
        long gzipEndTime = System.nanoTime();
        double gzipDecompressionTime = (gzipEndTime - gzipStartTime) / 1_000_000.0; // ms
        
        // Test LZ4 decompression
        long lz4StartTime = System.nanoTime();
        String lz4Decompressed = lz4Compressor.decompress(lz4Compressed);
        long lz4EndTime = System.nanoTime();
        double lz4DecompressionTime = (lz4EndTime - lz4StartTime) / 1_000_000.0; // ms
        
        // Test Snappy decompression
        long snappyStartTime = System.nanoTime();
        String snappyDecompressed = snappyCompressor.decompress(snappyCompressed);
        long snappyEndTime = System.nanoTime();
        double snappyDecompressionTime = (snappyEndTime - snappyStartTime) / 1_000_000.0; // ms
        
        // Log results
        logDecompressionResults("Small Repeating",
                               gzipCompressed.length, gzipDecompressionTime,
                               lz4Compressed.length, lz4DecompressionTime,
                               snappyCompressed.length, snappyDecompressionTime);
    }
    
    /**
     * Test compression performance with medium repeating data.
     */
    @Test
    public void testCompressionPerformanceMediumRepeatingData() {
        LOGGER.info("Testing compression performance with medium repeating data (" +
                    mediumRepeatingData.length() + " bytes)");
        
        // Test GZIP compression
        StorageCompressor gzipCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        long gzipStartTime = System.nanoTime();
        byte[] gzipCompressed = gzipCompressor.compress(mediumRepeatingData);
        long gzipEndTime = System.nanoTime();
        double gzipCompressionTime = (gzipEndTime - gzipStartTime) / 1_000_000.0; // ms
        
        // Test LZ4 compression
        StorageCompressor lz4Compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4);
        long lz4StartTime = System.nanoTime();
        byte[] lz4Compressed = lz4Compressor.compress(mediumRepeatingData);
        long lz4EndTime = System.nanoTime();
        double lz4CompressionTime = (lz4EndTime - lz4StartTime) / 1_000_000.0; // ms
        
        // Test Snappy compression
        StorageCompressor snappyCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY);
        long snappyStartTime = System.nanoTime();
        byte[] snappyCompressed = snappyCompressor.compress(mediumRepeatingData);
        long snappyEndTime = System.nanoTime();
        double snappyCompressionTime = (snappyEndTime - snappyStartTime) / 1_000_000.0; // ms
        
        // Log results
        logCompressionResults("Medium Repeating", mediumRepeatingData.getBytes(StandardCharsets.UTF_8).length,
                             gzipCompressed.length, gzipCompressionTime,
                             lz4Compressed.length, lz4CompressionTime,
                             snappyCompressed.length, snappyCompressionTime);
    }
    
    /**
     * Test decompression performance with medium repeating data.
     */
    @Test
    public void testDecompressionPerformanceMediumRepeatingData() {
        // Compress data first
        StorageCompressor gzipCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        byte[] gzipCompressed = gzipCompressor.compress(mediumRepeatingData);
        
        StorageCompressor lz4Compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4);
        byte[] lz4Compressed = lz4Compressor.compress(mediumRepeatingData);
        
        StorageCompressor snappyCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY);
        byte[] snappyCompressed = snappyCompressor.compress(mediumRepeatingData);
        
        // Test GZIP decompression
        long gzipStartTime = System.nanoTime();
        String gzipDecompressed = gzipCompressor.decompress(gzipCompressed);
        long gzipEndTime = System.nanoTime();
        double gzipDecompressionTime = (gzipEndTime - gzipStartTime) / 1_000_000.0; // ms
        
        // Test LZ4 decompression
        long lz4StartTime = System.nanoTime();
        String lz4Decompressed = lz4Compressor.decompress(lz4Compressed);
        long lz4EndTime = System.nanoTime();
        double lz4DecompressionTime = (lz4EndTime - lz4StartTime) / 1_000_000.0; // ms
        
        // Test Snappy decompression
        long snappyStartTime = System.nanoTime();
        String snappyDecompressed = snappyCompressor.decompress(snappyCompressed);
        long snappyEndTime = System.nanoTime();
        double snappyDecompressionTime = (snappyEndTime - snappyStartTime) / 1_000_000.0; // ms
        
        // Log results
        logDecompressionResults("Medium Repeating",
                               gzipCompressed.length, gzipDecompressionTime,
                               lz4Compressed.length, lz4DecompressionTime,
                               snappyCompressed.length, snappyDecompressionTime);
    }
    
    /**
     * Test compression performance with small random data.
     */
    @Test
    public void testCompressionPerformanceSmallRandomData() {
        LOGGER.info("Testing compression performance with small random data (" +
                    smallRandomData.length() + " bytes)");
        
        // Test GZIP compression
        StorageCompressor gzipCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.GZIP);
        long gzipStartTime = System.nanoTime();
        byte[] gzipCompressed = gzipCompressor.compress(smallRandomData);
        long gzipEndTime = System.nanoTime();
        double gzipCompressionTime = (gzipEndTime - gzipStartTime) / 1_000_000.0; // ms
        
        // Test LZ4 compression
        StorageCompressor lz4Compressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.LZ4);
        long lz4StartTime = System.nanoTime();
        byte[] lz4Compressed = lz4Compressor.compress(smallRandomData);
        long lz4EndTime = System.nanoTime();
        double lz4CompressionTime = (lz4EndTime - lz4StartTime) / 1_000_000.0; // ms
        
        // Test Snappy compression
        StorageCompressor snappyCompressor = new StorageCompressor(StorageCompressor.CompressionAlgorithm.SNAPPY);
        long snappyStartTime = System.nanoTime();
        byte[] snappyCompressed = snappyCompressor.compress(smallRandomData);
        long snappyEndTime = System.nanoTime();
        double snappyCompressionTime = (snappyEndTime - snappyStartTime) / 1_000_000.0; // ms
        
        // Log results
        logCompressionResults("Small Random", smallRandomData.getBytes(StandardCharsets.UTF_8).length,
                             gzipCompressed.length, gzipCompressionTime,
                             lz4Compressed.length, lz4CompressionTime,
                             snappyCompressed.length, snappyCompressionTime);
    }
    
    /**
     * Helper method to log compression results.
     */
    private void logCompressionResults(String dataType, int originalSize,
                                     int gzipSize, double gzipTime,
                                     int lz4Size, double lz4Time) {
        LOGGER.info("Compression Results for " + dataType + " Data:");
        LOGGER.info("Original Size: " + originalSize + " bytes");
        LOGGER.info("GZIP: " + gzipSize + " bytes (" +
                   String.format("%.2f", 100.0 * gzipSize / originalSize) + "%), " +
                   String.format("%.2f", gzipTime) + " ms");
        LOGGER.info("LZ4: " + lz4Size + " bytes (" +
                   String.format("%.2f", 100.0 * lz4Size / originalSize) + "%), " +
                   String.format("%.2f", lz4Time) + " ms");
        LOGGER.info("Speed Ratio (GZIP:LZ4): 1:" + String.format("%.2f", gzipTime / lz4Time));
        LOGGER.info("");
    }
    
    /**
     * Helper method to log compression results for all three algorithms.
     */
    private void logCompressionResults(String dataType, int originalSize,
                                     int gzipSize, double gzipTime,
                                     int lz4Size, double lz4Time,
                                     int snappySize, double snappyTime) {
        LOGGER.info("Compression Results for " + dataType + " Data:");
        LOGGER.info("Original Size: " + originalSize + " bytes");
        LOGGER.info("GZIP: " + gzipSize + " bytes (" +
                   String.format("%.2f", 100.0 * gzipSize / originalSize) + "%), " +
                   String.format("%.2f", gzipTime) + " ms");
        LOGGER.info("LZ4: " + lz4Size + " bytes (" +
                   String.format("%.2f", 100.0 * lz4Size / originalSize) + "%), " +
                   String.format("%.2f", lz4Time) + " ms");
        LOGGER.info("Snappy: " + snappySize + " bytes (" +
                   String.format("%.2f", 100.0 * snappySize / originalSize) + "%), " +
                   String.format("%.2f", snappyTime) + " ms");
        LOGGER.info("Speed Ratio (GZIP:LZ4:Snappy): 1:" +
                   String.format("%.2f", gzipTime / lz4Time) + ":" +
                   String.format("%.2f", gzipTime / snappyTime));
        LOGGER.info("");
    }
    
    /**
     * Helper method to log decompression results.
     */
    private void logDecompressionResults(String dataType,
                                       int gzipSize, double gzipTime,
                                       int lz4Size, double lz4Time) {
        LOGGER.info("Decompression Results for " + dataType + " Data:");
        LOGGER.info("GZIP: " + gzipSize + " bytes, " + String.format("%.2f", gzipTime) + " ms");
        LOGGER.info("LZ4: " + lz4Size + " bytes, " + String.format("%.2f", lz4Time) + " ms");
        LOGGER.info("Speed Ratio (GZIP:LZ4): 1:" + String.format("%.2f", gzipTime / lz4Time));
        LOGGER.info("");
    }
    
    /**
     * Helper method to log decompression results for all three algorithms.
     */
    private void logDecompressionResults(String dataType,
                                       int gzipSize, double gzipTime,
                                       int lz4Size, double lz4Time,
                                       int snappySize, double snappyTime) {
        LOGGER.info("Decompression Results for " + dataType + " Data:");
        LOGGER.info("GZIP: " + gzipSize + " bytes, " + String.format("%.2f", gzipTime) + " ms");
        LOGGER.info("LZ4: " + lz4Size + " bytes, " + String.format("%.2f", lz4Time) + " ms");
        LOGGER.info("Snappy: " + snappySize + " bytes, " + String.format("%.2f", snappyTime) + " ms");
        LOGGER.info("Speed Ratio (GZIP:LZ4:Snappy): 1:" +
                   String.format("%.2f", gzipTime / lz4Time) + ":" +
                   String.format("%.2f", gzipTime / snappyTime));
        LOGGER.info("");
    }
}