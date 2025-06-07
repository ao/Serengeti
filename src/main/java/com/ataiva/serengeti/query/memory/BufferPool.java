package com.ataiva.serengeti.query.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * BufferPool manages memory buffers for query execution.
 * It provides efficient allocation and deallocation of memory buffers.
 */
public class BufferPool {
    private static final Logger LOGGER = Logger.getLogger(BufferPool.class.getName());
    
    // Total memory capacity of the buffer pool
    private long capacity;
    
    // Currently allocated memory
    private final AtomicLong allocatedMemory;
    
    // Buffer allocations by query and operation
    private final Map<String, Map<String, Long>> queryBuffers;
    
    // Statistics
    private long totalAllocations;
    private long totalDeallocations;
    private long peakMemoryUsage;
    
    /**
     * Constructor
     * @param capacity Total memory capacity in bytes
     */
    public BufferPool(long capacity) {
        this.capacity = capacity;
        this.allocatedMemory = new AtomicLong(0);
        this.queryBuffers = new ConcurrentHashMap<>();
        this.totalAllocations = 0;
        this.totalDeallocations = 0;
        this.peakMemoryUsage = 0;
        
        LOGGER.info("BufferPool initialized with capacity: " + (capacity / (1024 * 1024)) + "MB");
    }
    
    /**
     * Resize the buffer pool
     * @param newCapacity New capacity in bytes
     */
    public synchronized void resize(long newCapacity) {
        if (newCapacity < allocatedMemory.get()) {
            LOGGER.warning("Cannot resize buffer pool to " + newCapacity + 
                          " bytes (smaller than current allocation of " + allocatedMemory.get() + " bytes)");
            return;
        }
        
        this.capacity = newCapacity;
        LOGGER.info("BufferPool resized to: " + (capacity / (1024 * 1024)) + "MB");
    }
    
    /**
     * Allocate buffers for a query operation
     * @param queryId Query ID
     * @param operationId Operation ID
     * @param bytes Number of bytes to allocate
     * @return True if allocation succeeded, false if it failed
     */
    public boolean allocateBuffers(String queryId, String operationId, long bytes) {
        // Check if there's enough capacity
        if (allocatedMemory.get() + bytes > capacity) {
            LOGGER.warning("Buffer allocation failed: not enough capacity");
            return false;
        }
        
        // Update allocated memory
        allocatedMemory.addAndGet(bytes);
        
        // Update peak memory usage
        long currentUsage = allocatedMemory.get();
        if (currentUsage > peakMemoryUsage) {
            peakMemoryUsage = currentUsage;
        }
        
        // Record the allocation
        queryBuffers.computeIfAbsent(queryId, k -> new ConcurrentHashMap<>())
                   .put(operationId, bytes);
        
        // Update statistics
        totalAllocations++;
        
        LOGGER.fine("Allocated " + bytes + " bytes for query " + queryId + ", operation " + operationId);
        return true;
    }
    
    /**
     * Release buffers for a query operation
     * @param queryId Query ID
     * @param operationId Operation ID
     */
    public void releaseBuffers(String queryId, String operationId) {
        Map<String, Long> operationBuffers = queryBuffers.get(queryId);
        if (operationBuffers == null) {
            return;
        }
        
        Long bytes = operationBuffers.remove(operationId);
        if (bytes != null) {
            allocatedMemory.addAndGet(-bytes);
            totalDeallocations++;
            LOGGER.fine("Released " + bytes + " bytes for query " + queryId + ", operation " + operationId);
        }
        
        // Remove the query entry if there are no more operations
        if (operationBuffers.isEmpty()) {
            queryBuffers.remove(queryId);
        }
    }
    
    /**
     * Release all buffers for a query
     * @param queryId Query ID
     */
    public void releaseBuffers(String queryId) {
        Map<String, Long> operationBuffers = queryBuffers.remove(queryId);
        if (operationBuffers == null) {
            return;
        }
        
        long totalBytes = 0;
        for (Long bytes : operationBuffers.values()) {
            totalBytes += bytes;
            totalDeallocations++;
        }
        
        allocatedMemory.addAndGet(-totalBytes);
        LOGGER.fine("Released " + totalBytes + " bytes for query " + queryId);
    }
    
    /**
     * Get the total capacity of the buffer pool
     * @return Capacity in bytes
     */
    public long getCapacity() {
        return capacity;
    }
    
    /**
     * Get the currently allocated memory
     * @return Allocated memory in bytes
     */
    public long getAllocatedMemory() {
        return allocatedMemory.get();
    }
    
    /**
     * Get the available memory in the buffer pool
     * @return Available memory in bytes
     */
    public long getAvailableMemory() {
        return capacity - allocatedMemory.get();
    }
    
    /**
     * Get the memory usage percentage
     * @return Memory usage percentage (0-100)
     */
    public double getMemoryUsagePercentage() {
        return (double) allocatedMemory.get() / capacity * 100.0;
    }
    
    /**
     * Get the number of active queries
     * @return Number of active queries
     */
    public int getActiveQueryCount() {
        return queryBuffers.size();
    }
    
    /**
     * Get the memory allocated for a query
     * @param queryId Query ID
     * @return Allocated memory in bytes, or 0 if the query is not found
     */
    public long getQueryMemory(String queryId) {
        Map<String, Long> operationBuffers = queryBuffers.get(queryId);
        if (operationBuffers == null) {
            return 0;
        }
        
        long total = 0;
        for (Long bytes : operationBuffers.values()) {
            total += bytes;
        }
        
        return total;
    }
    
    /**
     * Get buffer pool statistics
     * @return Map of statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("capacity", capacity);
        stats.put("allocatedMemory", allocatedMemory.get());
        stats.put("availableMemory", getAvailableMemory());
        stats.put("memoryUsagePercentage", getMemoryUsagePercentage());
        stats.put("activeQueries", getActiveQueryCount());
        stats.put("totalAllocations", totalAllocations);
        stats.put("totalDeallocations", totalDeallocations);
        stats.put("peakMemoryUsage", peakMemoryUsage);
        
        return stats;
    }
    
    /**
     * Get the total size (capacity) of the buffer pool
     * @return Total size in bytes
     */
    public long getTotalSize() {
        return capacity;
    }
}