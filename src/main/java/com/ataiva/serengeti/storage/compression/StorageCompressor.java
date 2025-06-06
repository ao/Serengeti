package com.ataiva.serengeti.storage.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.xerial.snappy.Snappy;

/**
 * Provides compression and decompression functionality for the storage system.
 * This class supports multiple compression algorithms (GZIP, LZ4, and Snappy)
 * to reduce the size of stored data with different performance characteristics.
 */
public class StorageCompressor {

    private static final Logger LOGGER = Logger.getLogger(StorageCompressor.class.getName());
    
    /**
     * Compression algorithm to use.
     */
    public enum CompressionAlgorithm {
        /**
         * No compression.
         */
        NONE,
        
        /**
         * GZIP compression.
         */
        GZIP,
        
        /**
         * LZ4 compression - fast compression with moderate compression ratio.
         */
        LZ4,
        
        /**
         * Snappy compression - very fast compression with moderate compression ratio.
         */
        SNAPPY
    }
    
    private final CompressionAlgorithm algorithm;
    private final int compressionLevel;
    
    /**
     * Creates a new StorageCompressor with the specified algorithm and default compression level.
     * 
     * @param algorithm The compression algorithm to use
     */
    public StorageCompressor(CompressionAlgorithm algorithm) {
        this(algorithm, 6); // Default compression level
    }
    
    /**
     * Creates a new StorageCompressor with the specified algorithm and compression level.
     * 
     * @param algorithm The compression algorithm to use
     * @param compressionLevel The compression level (1-9, where 9 is maximum compression)
     */
    public StorageCompressor(CompressionAlgorithm algorithm, int compressionLevel) {
        this.algorithm = algorithm;
        this.compressionLevel = Math.max(1, Math.min(9, compressionLevel)); // Ensure level is between 1 and 9
    }
    
    /**
     * Compresses a string using the configured compression algorithm.
     * 
     * @param data The string to compress
     * @return The compressed data as a byte array, or null if compression failed
     */
    public byte[] compress(String data) {
        if (data == null || data.isEmpty()) {
            return new byte[0];
        }
        
        if (algorithm == CompressionAlgorithm.NONE) {
            return data.getBytes(StandardCharsets.UTF_8);
        }
        
        try {
            switch (algorithm) {
                case GZIP:
                    return compressGzip(data);
                case LZ4:
                    return compressLz4(data);
                case SNAPPY:
                    return compressSnappy(data);
                default:
                    LOGGER.warning("Unknown compression algorithm: " + algorithm + ", using GZIP instead");
                    return compressGzip(data);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error compressing data", e);
            return null;
        }
    }
    
    /**
     * Decompresses a byte array using the configured compression algorithm.
     * 
     * @param data The compressed data
     * @return The decompressed string, or null if decompression failed
     */
    public String decompress(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        
        if (algorithm == CompressionAlgorithm.NONE) {
            return new String(data, StandardCharsets.UTF_8);
        }
        
        try {
            switch (algorithm) {
                case GZIP:
                    return decompressGzip(data);
                case LZ4:
                    return decompressLz4(data);
                case SNAPPY:
                    return decompressSnappy(data);
                default:
                    LOGGER.warning("Unknown compression algorithm: " + algorithm + ", using GZIP instead");
                    return decompressGzip(data);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error decompressing data", e);
            return null;
        }
    }
    
    /**
     * Compresses a string using GZIP compression.
     * 
     * @param data The string to compress
     * @return The compressed data as a byte array
     * @throws IOException If an I/O error occurs
     */
    private byte[] compressGzip(String data) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        
        try (GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream) {
            {
                def.setLevel(compressionLevel);
            }
        }) {
            gzipStream.write(data.getBytes(StandardCharsets.UTF_8));
        }
        
        return byteStream.toByteArray();
    }
    
    /**
     * Decompresses a byte array using GZIP decompression.
     * 
     * @param data The compressed data
     * @return The decompressed string
     * @throws IOException If an I/O error occurs
     */
    private String decompressGzip(byte[] data) throws IOException {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        try (GZIPInputStream gzipStream = new GZIPInputStream(byteStream)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, len);
            }
        }
        
        return outputStream.toString(StandardCharsets.UTF_8.name());
    }
    
    /**
     * Compresses a string using LZ4 compression.
     *
     * @param data The string to compress
     * @return The compressed data as a byte array
     * @throws IOException If an I/O error occurs
     */
    private byte[] compressLz4(String data) throws IOException {
        byte[] inputBytes = data.getBytes(StandardCharsets.UTF_8);
        
        // Create LZ4 factory and compressor
        LZ4Factory factory = LZ4Factory.fastestInstance();
        LZ4Compressor compressor;
        
        // Choose compressor based on compression level
        if (compressionLevel >= 7) {
            // High compression
            compressor = factory.highCompressor();
        } else {
            // Fast compression
            compressor = factory.fastCompressor();
        }
        
        // Calculate max compressed size
        int maxCompressedSize = compressor.maxCompressedLength(inputBytes.length);
        byte[] compressedBytes = new byte[maxCompressedSize + 4]; // +4 for storing original length
        
        // Store original length in first 4 bytes (needed for decompression)
        compressedBytes[0] = (byte) ((inputBytes.length >> 24) & 0xFF);
        compressedBytes[1] = (byte) ((inputBytes.length >> 16) & 0xFF);
        compressedBytes[2] = (byte) ((inputBytes.length >> 8) & 0xFF);
        compressedBytes[3] = (byte) (inputBytes.length & 0xFF);
        
        // Compress data
        int compressedSize = compressor.compress(inputBytes, 0, inputBytes.length,
                                               compressedBytes, 4, maxCompressedSize);
        
        // Create result array with exact size
        byte[] result = new byte[compressedSize + 4]; // +4 for the length header
        System.arraycopy(compressedBytes, 0, result, 0, compressedSize + 4);
        
        return result;
    }
    
    /**
     * Decompresses a byte array using LZ4 decompression.
     *
     * @param data The compressed data
     * @return The decompressed string
     * @throws IOException If an I/O error occurs
     */
    private String decompressLz4(byte[] data) throws IOException {
        if (data.length < 4) {
            throw new IOException("Invalid LZ4 compressed data (too short)");
        }
        
        // Read original length from first 4 bytes
        int originalLength = ((data[0] & 0xFF) << 24) |
                            ((data[1] & 0xFF) << 16) |
                            ((data[2] & 0xFF) << 8) |
                            (data[3] & 0xFF);
        
        // Create LZ4 factory and decompressor
        LZ4Factory factory = LZ4Factory.fastestInstance();
        LZ4FastDecompressor decompressor = factory.fastDecompressor();
        
        // Decompress data
        byte[] decompressedBytes = new byte[originalLength];
        decompressor.decompress(data, 4, decompressedBytes, 0, originalLength);
        
        // Convert to string
        return new String(decompressedBytes, StandardCharsets.UTF_8);
    }
    
    /**
     * Compresses a string using Snappy compression.
     *
     * @param data The string to compress
     * @return The compressed data as a byte array
     * @throws IOException If an I/O error occurs
     */
    private byte[] compressSnappy(String data) throws IOException {
        byte[] inputBytes = data.getBytes(StandardCharsets.UTF_8);
        
        // Snappy doesn't use compression levels, so we ignore compressionLevel
        return Snappy.compress(inputBytes);
    }
    
    /**
     * Decompresses a byte array using Snappy decompression.
     *
     * @param data The compressed data
     * @return The decompressed string
     * @throws IOException If an I/O error occurs
     */
    private String decompressSnappy(byte[] data) throws IOException {
        // Decompress the data
        byte[] decompressedBytes = Snappy.uncompress(data);
        
        // Convert to string
        return new String(decompressedBytes, StandardCharsets.UTF_8);
    }
    
    /**
     * Gets the compression algorithm.
     *
     * @return The compression algorithm
     */
    public CompressionAlgorithm getAlgorithm() {
        return algorithm;
    }
    
    /**
     * Gets the compression level.
     * 
     * @return The compression level
     */
    public int getCompressionLevel() {
        return compressionLevel;
    }
}