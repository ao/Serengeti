package com.ataiva.serengeti.storage.lsm;

import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * MemTable is an in-memory data structure that stores key-value pairs in sorted order.
 * It's the first component in the LSM-Tree architecture where writes are initially stored.
 * Once it reaches a certain size, it's flushed to disk as an immutable SSTable.
 */
public class MemTable {
    
    // The actual data structure storing the key-value pairs
    private final NavigableMap<byte[], byte[]> data;
    
    // Tracks the current size of the MemTable in bytes
    private final AtomicLong sizeInBytes;
    
    // Maximum size before flushing to disk
    private final long maxSizeInBytes;
    
    // ByteArrayComparator for comparing byte arrays
    private static final ByteArrayComparator COMPARATOR = new ByteArrayComparator();
    
    /**
     * Creates a new MemTable with the specified maximum size.
     * 
     * @param maxSizeInBytes Maximum size in bytes before flushing to disk
     */
    public MemTable(long maxSizeInBytes) {
        this.data = new ConcurrentSkipListMap<>(COMPARATOR);
        this.sizeInBytes = new AtomicLong(0);
        this.maxSizeInBytes = maxSizeInBytes;
    }
    
    /**
     * Puts a key-value pair into the MemTable.
     * 
     * @param key The key as a byte array
     * @param value The value as a byte array
     * @return true if the MemTable should be flushed to disk, false otherwise
     */
    public boolean put(byte[] key, byte[] value) {
        // Check if key is null
        if (key == null) {
            return false;
        }
        
        // Calculate the size of this entry
        long entrySize = key.length + (value != null ? value.length : 0);
        
        // Update the data structure
        // Handle null value case for tombstones
        byte[] oldValue = value != null ? data.put(key, value) : data.put(key, new byte[0]);
        
        // Update the size tracking
        if (oldValue != null) {
            // If we're replacing an existing value, subtract the old value's size and add the new value's size
            long oldValueSize = oldValue.length;
            long sizeDifference = (value != null ? value.length : 0) - oldValueSize;
            sizeInBytes.addAndGet(sizeDifference);
        } else {
            // If it's a new key, add the full entry size
            sizeInBytes.addAndGet(entrySize);
        }
        
        // Check if we've exceeded the size limit
        return sizeInBytes.get() >= maxSizeInBytes;
    }
    
    /**
     * Marks a key as deleted by storing a tombstone value.
     * 
     * @param key The key to delete
     * @return true if the MemTable should be flushed to disk, false otherwise
     */
    public boolean delete(byte[] key) {
        // Check if key is null
        if (key == null) {
            return false;
        }
        
        // Use an empty byte array as a tombstone to mark deletion
        return put(key, new byte[0]);
    }
    
    /**
     * Gets the value for a given key.
     * 
     * @param key The key to look up
     * @return The value, or null if the key doesn't exist or has been deleted
     */
    public byte[] get(byte[] key) {
        byte[] value = data.get(key);
        // If the value is an empty byte array (tombstone), return null
        if (value != null && value.length == 0) {
            return null;
        }
        return value;
    }
    
    /**
     * Checks if the MemTable contains a given key.
     * 
     * @param key The key to check
     * @return true if the key exists, false otherwise
     */
    public boolean containsKey(byte[] key) {
        return data.containsKey(key);
    }
    
    /**
     * Returns the current size of the MemTable in bytes.
     * 
     * @return The size in bytes
     */
    public long getSizeInBytes() {
        return sizeInBytes.get();
    }
    
    /**
     * Returns the number of entries in the MemTable.
     * 
     * @return The entry count
     */
    public int size() {
        return data.size();
    }
    
    /**
     * Returns whether the MemTable is empty.
     * 
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }
    
    /**
     * Iterates through all entries in the MemTable in sorted order.
     * 
     * @param consumer A function that processes each key-value pair
     */
    public void forEach(BiConsumer<byte[], byte[]> consumer) {
        for (Map.Entry<byte[], byte[]> entry : data.entrySet()) {
            consumer.accept(entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * Returns a snapshot of the current data as a NavigableMap.
     * This is used when flushing the MemTable to disk.
     * 
     * @return A NavigableMap containing all current entries
     */
    public NavigableMap<byte[], byte[]> getSnapshot() {
        // Create a new map with the same comparator
        NavigableMap<byte[], byte[]> snapshot = new ConcurrentSkipListMap<>(COMPARATOR);
        // Copy all entries
        snapshot.putAll(data);
        return snapshot;
    }
    
    /**
     * Clears all data from the MemTable.
     * This is called after the MemTable has been successfully flushed to disk.
     */
    public void clear() {
        data.clear();
        sizeInBytes.set(0);
    }
    
    // No inner ByteArrayComparator class needed anymore as we use the standalone one
}