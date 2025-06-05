package com.ataiva.serengeti.storage.lsm;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.zip.CRC32;

/**
 * SSTable (Sorted String Table) is an immutable file format that stores key-value pairs in sorted order.
 * It's created by flushing a MemTable to disk when it reaches its size threshold.
 * The file format includes a data section, an index for efficient lookups, and optional Bloom filters.
 */
public class SSTable {
    
    // Magic number to identify SSTable files
    private static final int MAGIC = 0x53535442; // "SSTB" in ASCII
    
    // Current format version
    private static final short VERSION = 1;
    
    // File path of this SSTable
    private final Path filePath;
    
    // In-memory index for fast lookups
    private final NavigableMap<byte[], IndexEntry> index;
    
    // Bloom filter for efficient negative lookups
    private BloomFilter bloomFilter;
    
    // File channel for reading data
    private FileChannel fileChannel;
    
    // Metadata about this SSTable
    private final Metadata metadata;
    
    /**
     * Opens an existing SSTable file for reading.
     * 
     * @param path Path to the SSTable file
     * @throws IOException If an I/O error occurs
     */
    public SSTable(Path path) throws IOException {
        this.filePath = path;
        this.index = new ConcurrentSkipListMap<>(new ByteArrayComparator());
        this.fileChannel = FileChannel.open(path, StandardOpenOption.READ);
        
        // Read the header and load the index
        ByteBuffer headerBuffer = ByteBuffer.allocate(64);
        fileChannel.read(headerBuffer, 0);
        headerBuffer.flip();
        
        int magic = headerBuffer.getInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid SSTable file format");
        }
        
        short version = headerBuffer.getShort();
        if (version > VERSION) {
            throw new IOException("Unsupported SSTable version: " + version);
        }
        
        short flags = headerBuffer.getShort();
        long timestamp = headerBuffer.getLong();
        int entryCount = headerBuffer.getInt();
        long indexOffset = headerBuffer.getLong();
        int headerChecksum = headerBuffer.getInt();
        
        // Skip header checksum verification for now
        
        // Load the index
        loadIndex(indexOffset, entryCount);
        
        // Load the bloom filter if present
        if ((flags & 0x01) != 0) {
            loadBloomFilter();
        }
        
        // Create metadata object
        this.metadata = new Metadata(version, flags, timestamp, entryCount, filePath.toFile().length());
    }
    
    /**
     * Creates a new SSTable from a MemTable.
     * 
     * @param memTable The MemTable to flush to disk
     * @param directory The directory to store the SSTable file
     * @param fileId A unique identifier for this SSTable file
     * @return The created SSTable
     * @throws IOException If an I/O error occurs
     */
    public static SSTable create(MemTable memTable, Path directory, String fileId) throws IOException {
        // Ensure the directory exists
        Files.createDirectories(directory);
        
        // Create the SSTable file
        Path filePath = directory.resolve("sstable-" + fileId + ".db");
        
        // Get a snapshot of the MemTable data
        NavigableMap<byte[], byte[]> data = memTable.getSnapshot();
        
        // Create a bloom filter
        BloomFilter bloomFilter = new BloomFilter(data.size(), 0.01);
        for (byte[] key : data.keySet()) {
            bloomFilter.add(key);
        }
        
        // Create the index and write the data
        try (FileChannel channel = FileChannel.open(filePath, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE, 
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // Start writing data after the header
            long position = 64; // Use a larger header size to avoid overflow
            
            // Write the data blocks and build the index
            Map<byte[], IndexEntry> index = new HashMap<>();
            position = 64; // Start after the header
            
            for (Map.Entry<byte[], byte[]> entry : data.entrySet()) {
                byte[] key = entry.getKey();
                byte[] value = entry.getValue();
                
                // Create index entry
                IndexEntry indexEntry = new IndexEntry(position, key.length + (value != null ? value.length : 0) + 6);
                index.put(key, indexEntry);
                
                // Write key-value pair
                ByteBuffer keyLengthBuffer = ByteBuffer.allocate(2);
                keyLengthBuffer.putShort((short) key.length);
                keyLengthBuffer.flip();
                channel.write(keyLengthBuffer, position);
                position += 2;
                
                ByteBuffer valueLengthBuffer = ByteBuffer.allocate(4);
                valueLengthBuffer.putInt(value != null ? value.length : -1);
                valueLengthBuffer.flip();
                channel.write(valueLengthBuffer, position);
                position += 4;
                
                ByteBuffer keyBuffer = ByteBuffer.wrap(key);
                channel.write(keyBuffer, position);
                position += key.length;
                
                if (value != null) {
                    ByteBuffer valueBuffer = ByteBuffer.wrap(value);
                    channel.write(valueBuffer, position);
                    position += value.length;
                }
            }
            
            // Record the index offset
            long indexOffset = position;
            
            // Write the index
            ByteBuffer indexCountBuffer = ByteBuffer.allocate(4);
            indexCountBuffer.putInt(index.size());
            indexCountBuffer.flip();
            channel.write(indexCountBuffer, position);
            position += 4;
            
            for (Map.Entry<byte[], IndexEntry> entry : index.entrySet()) {
                byte[] key = entry.getKey();
                IndexEntry indexEntry = entry.getValue();
                
                ByteBuffer keyLengthBuffer = ByteBuffer.allocate(2);
                keyLengthBuffer.putShort((short) key.length);
                keyLengthBuffer.flip();
                channel.write(keyLengthBuffer, position);
                position += 2;
                
                ByteBuffer keyBuffer = ByteBuffer.wrap(key);
                channel.write(keyBuffer, position);
                position += key.length;
                
                ByteBuffer offsetBuffer = ByteBuffer.allocate(8);
                offsetBuffer.putLong(indexEntry.offset);
                offsetBuffer.flip();
                channel.write(offsetBuffer, position);
                position += 8;
                
                ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
                sizeBuffer.putInt(indexEntry.size);
                sizeBuffer.flip();
                channel.write(sizeBuffer, position);
                position += 4;
            }
            
            // Write the bloom filter
            long bloomFilterOffset = position;
            byte[] bloomFilterBytes = bloomFilter.toByteArray();
            ByteBuffer bloomFilterBuffer = ByteBuffer.wrap(bloomFilterBytes);
            channel.write(bloomFilterBuffer, position);
            position += bloomFilterBytes.length;
            
            // Write the footer with metadata
            // For now, just a simple checksum
            CRC32 crc = new CRC32();
            crc.update(bloomFilterBytes);
            ByteBuffer checksumBuffer = ByteBuffer.allocate(4);
            checksumBuffer.putInt((int) crc.getValue());
            checksumBuffer.flip();
            channel.write(checksumBuffer, position);
            
            // Now write the header - use a simpler approach
            ByteBuffer headerBuffer = ByteBuffer.allocate(64);
            
            // Write header fields
            headerBuffer.putInt(MAGIC);
            headerBuffer.putShort(VERSION);
            headerBuffer.putShort((short) 0x01); // Flags: has bloom filter
            headerBuffer.putLong(System.currentTimeMillis());
            headerBuffer.putInt(data.size());
            headerBuffer.putLong(indexOffset);
            
            // Add a placeholder for the checksum
            int checksumPosition = headerBuffer.position();
            headerBuffer.putInt(0); // Placeholder
            
            // Calculate header checksum
            headerBuffer.flip();
            headerBuffer.limit(checksumPosition);
            
            crc.reset();
            byte[] headerBytesForChecksum = new byte[checksumPosition];
            headerBuffer.get(headerBytesForChecksum);
            crc.update(headerBytesForChecksum);
            int checksum = (int) crc.getValue();
            
            // Write the checksum
            headerBuffer.limit(checksumPosition + 4);
            headerBuffer.position(checksumPosition);
            headerBuffer.putInt(checksum);
            
            // Write the header to the file
            headerBuffer.flip();
            channel.write(headerBuffer, 0);
        }
        
        // Open the created SSTable
        return new SSTable(filePath);
    }
    
    /**
     * Gets the value for a given key.
     * 
     * @param key The key to look up
     * @return The value, or null if the key doesn't exist or has been deleted
     * @throws IOException If an I/O error occurs
     */
    public byte[] get(byte[] key) throws IOException {
        // Check bloom filter first for a quick negative
        if (bloomFilter != null && !bloomFilter.mightContain(key)) {
            return null;
        }
        
        // Look up in the index
        IndexEntry entry = index.get(key);
        if (entry == null) {
            return null;
        }
        
        // Read the value from disk
        ByteBuffer buffer = ByteBuffer.allocate(entry.size);
        fileChannel.read(buffer, entry.offset);
        buffer.flip();
        
        // Parse the entry
        short keyLength = buffer.getShort();
        int valueLength = buffer.getInt();
        
        // Skip the key
        buffer.position(buffer.position() + keyLength);
        
        // Check if this is a tombstone
        if (valueLength < 0) {
            return null;
        }
        
        // Read the value
        byte[] value = new byte[valueLength];
        buffer.get(value);
        
        // Check if this is an empty byte array (tombstone)
        if (value.length == 0) {
            return null;
        }
        
        return value;
    }
    
    /**
     * Checks if the SSTable might contain a given key.
     * 
     * @param key The key to check
     * @return true if the key might exist, false if it definitely doesn't
     */
    public boolean mightContain(byte[] key) {
        // Check bloom filter first
        if (bloomFilter != null && !bloomFilter.mightContain(key)) {
            return false;
        }
        
        // Check the index
        return index.containsKey(key);
    }
    
    /**
     * Gets the metadata for this SSTable.
     *
     * @return The metadata
     */
    public Metadata getMetadata() {
        return metadata;
    }
    
    /**
     * Gets the file path of this SSTable.
     *
     * @return The file path
     */
    public Path getFilePath() {
        return filePath;
    }
    
    /**
     * Gets the in-memory index for this SSTable.
     * This is used during compaction to efficiently access keys.
     *
     * @return The index map
     */
    public NavigableMap<byte[], IndexEntry> getIndex() {
        return new ConcurrentSkipListMap<>(index);
    }
    
    /**
     * Closes the SSTable, releasing any resources.
     *
     * @throws IOException If an I/O error occurs
     */
    public void close() throws IOException {
        if (fileChannel != null && fileChannel.isOpen()) {
            fileChannel.close();
            fileChannel = null;
        }
    }
    
    /**
     * Loads the index from the SSTable file.
     * 
     * @param indexOffset Offset of the index in the file
     * @param entryCount Number of entries in the index
     * @throws IOException If an I/O error occurs
     */
    private void loadIndex(long indexOffset, int entryCount) throws IOException {
        ByteBuffer countBuffer = ByteBuffer.allocate(4);
        fileChannel.read(countBuffer, indexOffset);
        countBuffer.flip();
        int indexSize = countBuffer.getInt();
        
        if (indexSize != entryCount) {
            throw new IOException("Index entry count mismatch");
        }
        
        long position = indexOffset + 4;
        for (int i = 0; i < indexSize; i++) {
            ByteBuffer keyLengthBuffer = ByteBuffer.allocate(2);
            fileChannel.read(keyLengthBuffer, position);
            keyLengthBuffer.flip();
            short keyLength = keyLengthBuffer.getShort();
            position += 2;
            
            byte[] key = new byte[keyLength];
            ByteBuffer keyBuffer = ByteBuffer.wrap(key);
            fileChannel.read(keyBuffer, position);
            position += keyLength;
            
            ByteBuffer offsetBuffer = ByteBuffer.allocate(8);
            fileChannel.read(offsetBuffer, position);
            offsetBuffer.flip();
            long offset = offsetBuffer.getLong();
            position += 8;
            
            ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
            fileChannel.read(sizeBuffer, position);
            sizeBuffer.flip();
            int size = sizeBuffer.getInt();
            position += 4;
            
            index.put(key, new IndexEntry(offset, size));
        }
    }
    
    /**
     * Loads the bloom filter from the SSTable file.
     * 
     * @throws IOException If an I/O error occurs
     */
    private void loadBloomFilter() throws IOException {
        // For now, a simplified implementation
        // In a real implementation, we would read the bloom filter data from the file
        this.bloomFilter = new BloomFilter(index.size(), 0.01);
        for (byte[] key : index.keySet()) {
            bloomFilter.add(key);
        }
    }
    
    /**
     * Entry in the SSTable index.
     */
    private static class IndexEntry {
        final long offset;
        final int size;
        
        IndexEntry(long offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }
    
    /**
     * Metadata about an SSTable.
     */
    public static class Metadata {
        private final short version;
        private final short flags;
        private final long timestamp;
        private final int entryCount;
        private final long fileSize;
        
        Metadata(short version, short flags, long timestamp, int entryCount, long fileSize) {
            this.version = version;
            this.flags = flags;
            this.timestamp = timestamp;
            this.entryCount = entryCount;
            this.fileSize = fileSize;
        }
        
        public short getVersion() {
            return version;
        }
        
        public short getFlags() {
            return flags;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public int getEntryCount() {
            return entryCount;
        }
        
        public long getFileSize() {
            return fileSize;
        }
        
        @Override
        public String toString() {
            return "SSTable{version=" + version + 
                   ", timestamp=" + new Date(timestamp) + 
                   ", entries=" + entryCount + 
                   ", size=" + fileSize + " bytes}";
        }
    }
    
    /**
     * Simple Bloom filter implementation for efficient negative lookups.
     */
    public static class BloomFilter {
        private final BitSet bits;
        private final int numHashFunctions;
        private final int bitSize;
        
        /**
         * Creates a new Bloom filter.
         * 
         * @param expectedInsertions Expected number of insertions
         * @param falsePositiveProbability Desired false positive probability
         */
        public BloomFilter(int expectedInsertions, double falsePositiveProbability) {
            // Calculate optimal bit size and hash functions
            this.bitSize = optimalBitSize(expectedInsertions, falsePositiveProbability);
            this.numHashFunctions = optimalHashFunctions(expectedInsertions, bitSize);
            this.bits = new BitSet(bitSize);
        }
        
        /**
         * Creates a Bloom filter from a byte array.
         * 
         * @param data The serialized Bloom filter data
         * @param numHashFunctions Number of hash functions
         */
        public BloomFilter(byte[] data, int numHashFunctions) {
            this.bits = BitSet.valueOf(data);
            this.bitSize = bits.size();
            this.numHashFunctions = numHashFunctions;
        }
        
        /**
         * Adds a key to the Bloom filter.
         * 
         * @param key The key to add
         */
        public void add(byte[] key) {
            for (int i = 0; i < numHashFunctions; i++) {
                int hash = hash(key, i);
                bits.set(Math.abs(hash % bitSize));
            }
        }
        
        /**
         * Checks if a key might be in the set.
         * 
         * @param key The key to check
         * @return true if the key might be in the set, false if it definitely isn't
         */
        public boolean mightContain(byte[] key) {
            for (int i = 0; i < numHashFunctions; i++) {
                int hash = hash(key, i);
                if (!bits.get(Math.abs(hash % bitSize))) {
                    return false;
                }
            }
            return true;
        }
        
        /**
         * Converts the Bloom filter to a byte array.
         * 
         * @return The serialized Bloom filter
         */
        public byte[] toByteArray() {
            return bits.toByteArray();
        }
        
        /**
         * Calculates the optimal bit size for a Bloom filter.
         * 
         * @param n Expected number of insertions
         * @param p Desired false positive probability
         * @return The optimal bit size
         */
        private static int optimalBitSize(int n, double p) {
            return (int) (-n * Math.log(p) / (Math.log(2) * Math.log(2)));
        }
        
        /**
         * Calculates the optimal number of hash functions.
         * 
         * @param n Expected number of insertions
         * @param m Bit size
         * @return The optimal number of hash functions
         */
        private static int optimalHashFunctions(int n, int m) {
            return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
        }
        
        /**
         * Computes a hash of the key.
         * 
         * @param key The key to hash
         * @param seed A seed value to generate different hashes
         * @return The hash value
         */
        private int hash(byte[] key, int seed) {
            // Simple implementation of MurmurHash3
            int h1 = seed;
            
            for (byte b : key) {
                h1 ^= b;
                h1 *= 0x5bd1e995;
                h1 ^= h1 >>> 15;
            }
            
            h1 ^= key.length;
            h1 ^= h1 >>> 13;
            h1 *= 0x5bd1e995;
            h1 ^= h1 >>> 15;
            
            return h1;
        }
    }
}