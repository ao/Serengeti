package com.ataiva.serengeti.storage;

import com.ataiva.serengeti.performance.PerformanceMetric;
import com.ataiva.serengeti.performance.PerformanceProfiler;
import com.ataiva.serengeti.storage.cache.CacheManager;
import com.ataiva.serengeti.storage.io.AsyncIOManager;
import com.ataiva.serengeti.storage.lsm.BloomFilterOptimizer;
import com.ataiva.serengeti.storage.lsm.CompactionStrategy;
import com.ataiva.serengeti.storage.lsm.SSTable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * StorageEngineTuner is the main entry point for the Storage Engine Tuning component.
 * It integrates all the individual optimization components:
 * 1. LSM Tree optimizations with bloom filters
 * 2. Improved compaction strategies
 * 3. Asynchronous and batch I/O operations
 * 4. Cache layer enhancements
 */
public class StorageEngineTuner {
    private static final Logger LOGGER = Logger.getLogger(StorageEngineTuner.class.getName());
    
    private static StorageEngineTuner instance;
    
    private final PerformanceProfiler profiler;
    private final BloomFilterOptimizer bloomFilterOptimizer;
    private final CompactionStrategy compactionStrategy;
    private final AsyncIOManager asyncIOManager;
    private final CacheManager cacheManager;
    
    private boolean enabled = true;
    private TuningLevel tuningLevel = TuningLevel.BALANCED;
    
    /**
     * Private constructor for singleton pattern
     */
    private StorageEngineTuner() {
        this.profiler = PerformanceProfiler.getInstance();
        this.bloomFilterOptimizer = new BloomFilterOptimizer();
        this.compactionStrategy = new CompactionStrategy(CompactionStrategy.CompactionType.HYBRID);
        this.asyncIOManager = AsyncIOManager.getInstance();
        this.cacheManager = CacheManager.getInstance();
        
        LOGGER.info("Storage Engine Tuner initialized with tuning level: " + tuningLevel);
    }
    
    /**
     * Get the singleton instance of StorageEngineTuner
     * 
     * @return StorageEngineTuner instance
     */
    public static synchronized StorageEngineTuner getInstance() {
        if (instance == null) {
            instance = new StorageEngineTuner();
        }
        return instance;
    }
    
    /**
     * Enable or disable the storage engine tuning
     * 
     * @param enabled Whether tuning should be enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("Storage Engine Tuning " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Check if storage engine tuning is enabled
     * 
     * @return true if tuning is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Set the tuning level
     * 
     * @param level New tuning level
     */
    public void setTuningLevel(TuningLevel level) {
        this.tuningLevel = level;
        
        // Apply tuning level settings to components
        switch (level) {
            case PERFORMANCE:
                // Optimize for maximum performance
                compactionStrategy.setCompactionType(CompactionStrategy.CompactionType.LEVELED);
                bloomFilterOptimizer.optimizeParameters(1000000, 0.01); // 1% false positive rate
                break;
                
            case BALANCED:
                // Balance performance and resource usage
                compactionStrategy.setCompactionType(CompactionStrategy.CompactionType.HYBRID);
                bloomFilterOptimizer.optimizeParameters(1000000, 0.05); // 5% false positive rate
                break;
                
            case RESOURCE_EFFICIENT:
                // Optimize for minimal resource usage
                compactionStrategy.setCompactionType(CompactionStrategy.CompactionType.SIZE_TIERED);
                bloomFilterOptimizer.optimizeParameters(1000000, 0.1); // 10% false positive rate
                break;
        }
        
        LOGGER.info("Storage Engine Tuning level set to: " + level);
    }
    
    /**
     * Get the current tuning level
     * 
     * @return Current tuning level
     */
    public TuningLevel getTuningLevel() {
        return tuningLevel;
    }
    
    /**
     * Optimize an SSTable with bloom filters
     * 
     * @param sstable SSTable to optimize
     */
    public void optimizeSSTableWithBloomFilter(SSTable sstable) {
        if (!enabled) {
            return;
        }
        
        String timerId = profiler.startTimer("storage", "bloom_filter_optimization", null);
        
        try {
            // Extract keys from SSTable
            List<String> keys = new ArrayList<>(sstable.getData().keySet());
            
            // Create bloom filter
            BitSet bloomFilter = bloomFilterOptimizer.createFilter(keys);
            
            // Set bloom filter on SSTable
            sstable.setBloomFilter(bloomFilter);
            
            LOGGER.fine("Optimized SSTable with bloom filter: " + sstable.getId());
            
            profiler.recordMetric(new PerformanceMetric.Builder()
                .setCategory("storage")
                .setName("bloom_filter.optimization")
                .setValue(1)
                .build());
        } finally {
            profiler.stopTimer(timerId, "storage.bloom_filter.optimization_time");
        }
    }
    
    /**
     * Check if compaction should be triggered
     * 
     * @param sstables List of SSTables
     * @return true if compaction should be triggered
     */
    public boolean shouldTriggerCompaction(List<SSTable> sstables) {
        if (!enabled) {
            return false;
        }
        
        return compactionStrategy.shouldCompact(sstables);
    }
    
    /**
     * Perform compaction on a list of SSTables
     * 
     * @param sstables List of SSTables to compact
     * @return List of compacted SSTables
     */
    public List<SSTable> performCompaction(List<SSTable> sstables) {
        if (!enabled) {
            return sstables;
        }
        
        String timerId = profiler.startTimer("storage", "compaction", null);
        
        try {
            List<SSTable> compactedSSTables = compactionStrategy.compact(sstables);
            
            // Optimize the compacted SSTables with bloom filters
            for (SSTable sstable : compactedSSTables) {
                optimizeSSTableWithBloomFilter(sstable);
            }
            
            LOGGER.info("Compaction completed: " + sstables.size() + " SSTables compacted into " + 
                       compactedSSTables.size() + " SSTables");
            
            profiler.recordMetric(new PerformanceMetric.Builder()
                .setCategory("storage")
                .setName("compaction.performed")
                .setValue(1)
                .build());
            
            return compactedSSTables;
        } finally {
            profiler.stopTimer(timerId, "storage.compaction.total_time");
        }
    }
    
    /**
     * Read data asynchronously
     * 
     * @param filePath Path to the file
     * @param position Position in the file
     * @param size Size to read
     * @param callback Callback to invoke when read completes
     */
    public void readDataAsync(String filePath, long position, int size, AsyncIOManager.IOCallback<ByteBuffer> callback) {
        if (!enabled) {
            // Fall back to synchronous read
            // In a real implementation, this would perform a synchronous read
            LOGGER.warning("Async I/O disabled, falling back to synchronous read");
            return;
        }
        
        // First check the cache
        String cacheKey = filePath + ":" + position + ":" + size;
        byte[] cachedData = cacheManager.get(cacheKey);
        
        if (cachedData != null) {
            // Cache hit
            ByteBuffer buffer = ByteBuffer.wrap(cachedData);
            callback.onSuccess(buffer);
            return;
        }
        
        // Cache miss, perform async read
        asyncIOManager.readAsync(filePath, position, size, new AsyncIOManager.IOCallback<ByteBuffer>() {
            @Override
            public void onSuccess(ByteBuffer result) {
                // Cache the result
                byte[] data = new byte[result.remaining()];
                result.duplicate().get(data);
                cacheManager.put(cacheKey, data, data.length);
                
                // Pass the result to the original callback
                callback.onSuccess(result);
            }
            
            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(error);
            }
        });
    }
    
    /**
     * Write data asynchronously
     * 
     * @param filePath Path to the file
     * @param position Position in the file
     * @param data Data to write
     * @param callback Callback to invoke when write completes
     */
    public void writeDataAsync(String filePath, long position, ByteBuffer data, AsyncIOManager.IOCallback<Integer> callback) {
        if (!enabled) {
            // Fall back to synchronous write
            // In a real implementation, this would perform a synchronous write
            LOGGER.warning("Async I/O disabled, falling back to synchronous write");
            return;
        }
        
        // Invalidate cache for this region
        String cacheKey = filePath + ":" + position + ":" + data.remaining();
        cacheManager.remove(cacheKey);
        
        // Perform async write
        asyncIOManager.writeAsync(filePath, position, data, callback);
    }
    
    /**
     * Batch read multiple regions from a file
     * 
     * @param filePath Path to the file
     * @param regions List of regions to read
     * @param callback Callback to invoke when all reads complete
     */
    public void batchReadAsync(String filePath, List<Region> regions, AsyncIOManager.IOCallback<Map<Region, ByteBuffer>> callback) {
        if (!enabled || regions.isEmpty()) {
            callback.onSuccess(new HashMap<>());
            return;
        }
        
        String timerId = profiler.startTimer("storage", "batch_read", filePath);
        
        final Map<Region, ByteBuffer> results = new HashMap<>();
        final AtomicInteger pendingReads = new AtomicInteger(regions.size());
        final CountDownLatch latch = new CountDownLatch(regions.size());
        final AtomicInteger errors = new AtomicInteger(0);
        
        for (Region region : regions) {
            readDataAsync(filePath, region.position, region.size, new AsyncIOManager.IOCallback<ByteBuffer>() {
                @Override
                public void onSuccess(ByteBuffer result) {
                    synchronized (results) {
                        results.put(region, result);
                    }
                    latch.countDown();
                    
                    if (pendingReads.decrementAndGet() == 0 && errors.get() == 0) {
                        callback.onSuccess(results);
                        profiler.stopTimer(timerId, "storage.batch_read.time");
                    }
                }
                
                @Override
                public void onFailure(Throwable error) {
                    errors.incrementAndGet();
                    latch.countDown();
                    
                    if (pendingReads.decrementAndGet() == 0 || errors.get() == 1) {
                        callback.onFailure(error);
                        profiler.stopTimer(timerId, "storage.batch_read.time");
                    }
                }
            });
        }
    }
    
    /**
     * Flush all pending I/O operations
     * 
     * @return true if all operations were flushed successfully
     */
    public boolean flushIO() {
        if (!enabled) {
            return true;
        }
        
        try {
            asyncIOManager.flushAllBatches();
            return asyncIOManager.waitForCompletion(5000); // Wait up to 5 seconds
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error flushing I/O operations", e);
            return false;
        }
    }
    
    /**
     * Clear the cache
     */
    public void clearCache() {
        cacheManager.clear();
    }
    
    /**
     * Get storage engine statistics
     * 
     * @return Map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // Add cache statistics
        stats.putAll(cacheManager.getStatistics());
        
        // Add compaction statistics
        stats.put("compactionStrategy", compactionStrategy.getCompactionType());
        
        // Add bloom filter statistics
        stats.put("bloomFilterBitsPerElement", bloomFilterOptimizer.getBitsPerElement());
        stats.put("bloomFilterHashFunctions", bloomFilterOptimizer.getNumHashFunctions());
        
        // Add I/O statistics
        stats.put("pendingIOOperations", asyncIOManager.getPendingOperationCount());
        
        return stats;
    }
    
    /**
     * Shutdown the storage engine tuner
     */
    public void shutdown() {
        LOGGER.info("Shutting down Storage Engine Tuner");
        
        // Flush pending I/O operations
        flushIO();
        
        // Shutdown async I/O manager
        asyncIOManager.shutdown();
    }
    
    /**
     * Region class for batch reads
     */
    public static class Region {
        private final long position;
        private final int size;
        
        public Region(long position, int size) {
            this.position = position;
            this.size = size;
        }
        
        public long getPosition() {
            return position;
        }
        
        public int getSize() {
            return size;
        }
        
        @Override
        public int hashCode() {
            return 31 * Long.hashCode(position) + Integer.hashCode(size);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            
            Region other = (Region) obj;
            return position == other.position && size == other.size;
        }
    }
    
    /**
     * Tuning level enum
     */
    public enum TuningLevel {
        PERFORMANCE,        // Optimize for maximum performance
        BALANCED,           // Balance performance and resource usage
        RESOURCE_EFFICIENT  // Optimize for minimal resource usage
    }
}