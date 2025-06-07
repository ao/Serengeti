package com.ataiva.serengeti.storage.lsm;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SSTable represents a Sorted String Table in the LSM tree storage engine.
 * It is an immutable, sorted file of key-value pairs that is periodically
 * merged with other SSTables during compaction.
 */
public class SSTable {
    private String id;
    private long size;
    private int level;
    private String minKey;
    private String maxKey;
    private long creationTime;
    private Map<String, byte[]> data;
    private BitSet bloomFilter;
    
    /**
     * Creates a new SSTable
     */
    public SSTable() {
        this.id = UUID.randomUUID().toString();
        this.size = 0;
        this.level = 0;
        this.minKey = "";
        this.maxKey = "";
        this.creationTime = System.currentTimeMillis();
        this.data = new HashMap<>();
    }
    
    /**
     * Creates a new SSTable with the specified data
     * 
     * @param data Map of keys to values
     */
    public SSTable(Map<String, byte[]> data) {
        this();
        this.data = new HashMap<>(data);
        
        // Calculate size
        long totalSize = 0;
        String min = null;
        String max = null;
        
        for (Map.Entry<String, byte[]> entry : data.entrySet()) {
            String key = entry.getKey();
            byte[] value = entry.getValue();
            
            totalSize += key.length() + (value != null ? value.length : 0);
            
            if (min == null || key.compareTo(min) < 0) {
                min = key;
            }
            
            if (max == null || key.compareTo(max) > 0) {
                max = key;
            }
        }
        
        this.size = totalSize;
        this.minKey = min != null ? min : "";
        this.maxKey = max != null ? max : "";
    }
    
    /**
     * Gets the value for a key
     * 
     * @param key Key to look up
     * @return Value for the key, or null if not found
     */
    public byte[] get(String key) {
        // Check bloom filter first if available
        if (bloomFilter != null && !bloomFilterMightContain(key)) {
            return null; // Definitely not in this SSTable
        }
        
        return data.get(key);
    }
    
    /**
     * Checks if the bloom filter indicates the key might be present
     * 
     * @param key Key to check
     * @return true if the key might be present, false if definitely not present
     */
    private boolean bloomFilterMightContain(String key) {
        // This would use the actual bloom filter implementation
        // For now, just return true to indicate it might be present
        return true;
    }
    
    /**
     * Gets the unique ID of this SSTable
     * 
     * @return SSTable ID
     */
    public String getId() {
        return id;
    }
    
    /**
     * Gets the size of this SSTable in bytes
     * 
     * @return Size in bytes
     */
    public long getSize() {
        return size;
    }
    
    /**
     * Sets the size of this SSTable
     * 
     * @param size New size in bytes
     */
    public void setSize(long size) {
        this.size = size;
    }
    
    /**
     * Gets the level of this SSTable in the LSM tree
     * 
     * @return Level number
     */
    public int getLevel() {
        return level;
    }
    
    /**
     * Sets the level of this SSTable
     * 
     * @param level New level number
     */
    public void setLevel(int level) {
        this.level = level;
    }
    
    /**
     * Gets the minimum key in this SSTable
     * 
     * @return Minimum key
     */
    public String getMinKey() {
        return minKey;
    }
    
    /**
     * Sets the minimum key in this SSTable
     * 
     * @param minKey New minimum key
     */
    public void setMinKey(String minKey) {
        this.minKey = minKey;
    }
    
    /**
     * Gets the maximum key in this SSTable
     * 
     * @return Maximum key
     */
    public String getMaxKey() {
        return maxKey;
    }
    
    /**
     * Sets the maximum key in this SSTable
     * 
     * @param maxKey New maximum key
     */
    public void setMaxKey(String maxKey) {
        this.maxKey = maxKey;
    }
    
    /**
     * Gets the creation time of this SSTable
     * 
     * @return Creation time in milliseconds since epoch
     */
    public long getCreationTime() {
        return creationTime;
    }
    
    /**
     * Gets the data in this SSTable
     * 
     * @return Map of keys to values
     */
    public Map<String, byte[]> getData() {
        return new HashMap<>(data);
    }
    
    /**
     * Sets the bloom filter for this SSTable
     * 
     * @param bloomFilter Bloom filter to use
     */
    public void setBloomFilter(BitSet bloomFilter) {
        this.bloomFilter = bloomFilter;
    }
    
    /**
     * Gets the bloom filter for this SSTable
     * 
     * @return Bloom filter
     */
    public BitSet getBloomFilter() {
        return bloomFilter;
    }
    
    /**
     * Checks if this SSTable overlaps with another SSTable
     * 
     * @param other Other SSTable to check
     * @return true if the key ranges overlap, false otherwise
     */
    public boolean overlaps(SSTable other) {
        return !(maxKey.compareTo(other.minKey) < 0 || minKey.compareTo(other.maxKey) > 0);
    }
    
    /**
     * Returns a string representation of this SSTable
     * 
     * @return String representation
     */
    @Override
    public String toString() {
        return "SSTable{" +
               "id='" + id + '\'' +
               ", size=" + size +
               ", level=" + level +
               ", keys=" + data.size() +
               ", range=[" + minKey + " to " + maxKey + "]" +
               '}';
    }
}