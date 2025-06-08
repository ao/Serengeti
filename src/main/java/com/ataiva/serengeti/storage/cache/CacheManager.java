package com.ataiva.serengeti.storage.cache;

import com.ataiva.serengeti.performance.PerformanceMetric;
import com.ataiva.serengeti.performance.PerformanceProfiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

/**
 * CacheManager provides an enhanced caching layer for the storage engine.
 * It implements multiple caching strategies and optimizations:
 * 1. Multi-level cache hierarchy (L1/L2)
 * 2. Adaptive cache sizing based on access patterns
 * 3. Multiple eviction policies (LRU, LFU, FIFO)
 * 4. Cache admission policies to prevent cache pollution
 * 5. Cache prefetching for sequential access patterns
 */
public class CacheManager {
    private static final Logger LOGGER = Logger.getLogger(CacheManager.class.getName());
    
    // Default cache sizes
    private static final int DEFAULT_L1_CACHE_SIZE = 64 * 1024 * 1024; // 64MB
    private static final int DEFAULT_L2_CACHE_SIZE = 256 * 1024 * 1024; // 256MB
    
    // Singleton instance
    private static CacheManager instance;
    
    // Performance profiler
    private final PerformanceProfiler profiler;
    
    // Cache configuration
    private final int maxL1CacheSize;
    private final int maxL2CacheSize;
    private final EvictionPolicy evictionPolicy;
    private final boolean adaptiveSizing;
    private final boolean prefetchingEnabled;
    
    // Cache statistics
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    
    // L1 cache (fast, smaller)
    private final Map<String, CacheEntry> l1Cache;
    private final ReadWriteLock l1Lock = new ReentrantReadWriteLock();
    private int currentL1Size = 0;
    
    // L2 cache (slower, larger)
    private final Map<String, CacheEntry> l2Cache;
    private final ReadWriteLock l2Lock = new ReentrantReadWriteLock();
    private int currentL2Size = 0;
    
    // Access pattern detection for prefetching
    private final Map<String, List<String>> accessPatterns = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccessTimes = new ConcurrentHashMap<>();
    
    /**
     * Private constructor for singleton pattern
     */
    private CacheManager() {
        this(DEFAULT_L1_CACHE_SIZE, DEFAULT_L2_CACHE_SIZE, EvictionPolicy.LRU, true, true);
    }
    
    /**
     * Private constructor with custom settings
     * 
     * @param maxL1CacheSize Maximum size of L1 cache in bytes
     * @param maxL2CacheSize Maximum size of L2 cache in bytes
     * @param evictionPolicy Cache eviction policy
     * @param adaptiveSizing Whether to enable adaptive cache sizing
     * @param prefetchingEnabled Whether to enable prefetching
     */
    private CacheManager(int maxL1CacheSize, int maxL2CacheSize, EvictionPolicy evictionPolicy, 
                        boolean adaptiveSizing, boolean prefetchingEnabled) {
        this.profiler = PerformanceProfiler.getInstance();
        this.maxL1CacheSize = maxL1CacheSize;
        this.maxL2CacheSize = maxL2CacheSize;
        this.evictionPolicy = evictionPolicy;
        this.adaptiveSizing = adaptiveSizing;
        this.prefetchingEnabled = prefetchingEnabled;
        
        // Initialize caches based on eviction policy
        switch (evictionPolicy) {
            case LRU:
                this.l1Cache = Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                        return false; // We handle eviction manually
                    }
                });
                this.l2Cache = Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                        return false; // We handle eviction manually
                    }
                });
                break;
            case LFU:
                this.l1Cache = new ConcurrentHashMap<>();
                this.l2Cache = new ConcurrentHashMap<>();
                break;
            case FIFO:
                this.l1Cache = Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                        return false; // We handle eviction manually
                    }
                });
                this.l2Cache = Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                        return false; // We handle eviction manually
                    }
                });
                break;
            default:
                this.l1Cache = new ConcurrentHashMap<>();
                this.l2Cache = new ConcurrentHashMap<>();
                break;
        }
        
        LOGGER.info("Cache manager initialized with L1 size: " + formatSize(maxL1CacheSize) + 
                   ", L2 size: " + formatSize(maxL2CacheSize) + 
                   ", policy: " + evictionPolicy);
    }
    
    /**
     * Get the singleton instance of CacheManager
     * 
     * @return CacheManager instance
     */
    public static synchronized CacheManager getInstance() {
        if (instance == null) {
            instance = new CacheManager();
        }
        return instance;
    }
    
    /**
     * Get the singleton instance with custom settings
     * 
     * @param maxL1CacheSize Maximum size of L1 cache in bytes
     * @param maxL2CacheSize Maximum size of L2 cache in bytes
     * @param evictionPolicy Cache eviction policy
     * @param adaptiveSizing Whether to enable adaptive cache sizing
     * @param prefetchingEnabled Whether to enable prefetching
     * @return CacheManager instance
     */
    public static synchronized CacheManager getInstance(int maxL1CacheSize, int maxL2CacheSize, 
                                                      EvictionPolicy evictionPolicy, 
                                                      boolean adaptiveSizing,
                                                      boolean prefetchingEnabled) {
        if (instance == null) {
            instance = new CacheManager(maxL1CacheSize, maxL2CacheSize, evictionPolicy, 
                                       adaptiveSizing, prefetchingEnabled);
        }
        return instance;
    }
    
    /**
     * Get a value from the cache
     * 
     * @param key Cache key
     * @return Cached value, or null if not found
     */
    public byte[] get(String key) {
        String timerId = profiler.startTimer("storage", "cache_get");
        // Store key in a local variable since we can't pass it to startTimer
        String cacheKey = key;
        
        try {
            // Check L1 cache first
            l1Lock.readLock().lock();
            try {
                CacheEntry entry = l1Cache.get(key);
                if (entry != null) {
                    // L1 cache hit
                    hits.incrementAndGet();
                    entry.incrementAccessCount();
                    recordAccess(key);
                    
                    profiler.recordCustomMetric("storage", "cache", "cache.l1_hit", 1, "count");
                    
                    return entry.getValue();
                }
            } finally {
                l1Lock.readLock().unlock();
            }
            
            // Check L2 cache
            l2Lock.readLock().lock();
            try {
                CacheEntry entry = l2Cache.get(key);
                if (entry != null) {
                    // L2 cache hit - promote to L1
                    hits.incrementAndGet();
                    entry.incrementAccessCount();
                    recordAccess(key);
                    
                    profiler.recordCustomMetric("storage", "cache", "cache.l2_hit", 1, "count");
                    
                    // Promote to L1 cache if it passes admission policy
                    if (shouldAdmitToL1(entry)) {
                        promoteToL1(key, entry);
                    }
                    
                    return entry.getValue();
                }
            } finally {
                l2Lock.readLock().unlock();
            }
            
            // Cache miss
            misses.incrementAndGet();
            
            profiler.recordCustomMetric("storage", "cache", "cache.miss", 1, "count");
            
            return null;
        } finally {
            profiler.stopTimer(timerId, "storage.cache.get_time");
        }
    }
    
    /**
     * Put a value in the cache
     * 
     * @param key Cache key
     * @param value Value to cache
     * @param size Size of the value in bytes
     */
    public void put(String key, byte[] value, int size) {
        String timerId = profiler.startTimer("storage", "cache_put");
        // Store key in a local variable since we can't pass it to startTimer
        String cacheKey = key;
        
        try {
            CacheEntry entry = new CacheEntry(value, size);
            
            // Determine which cache level to use based on size and access pattern
            if (size <= maxL1CacheSize / 10 && shouldAdmitToL1(entry)) { // Small values go to L1
                putInL1(key, entry);
            } else if (size <= maxL2CacheSize / 2) { // Medium values go to L2
                putInL2(key, entry);
            } else {
                // Value is too large for cache
                LOGGER.fine("Value too large for cache: " + key + " (" + formatSize(size) + ")");
            }
            
            // Record this access for pattern detection
            recordAccess(key);
            
            // Perform prefetching if enabled
            if (prefetchingEnabled) {
                prefetchRelatedKeys(key);
            }
        } finally {
            profiler.stopTimer(timerId, "storage.cache.put_time");
        }
    }
    
    /**
     * Put a value in the L1 cache
     * 
     * @param key Cache key
     * @param entry Cache entry
     */
    private void putInL1(String key, CacheEntry entry) {
        l1Lock.writeLock().lock();
        try {
            // Check if we need to make room
            if (currentL1Size + entry.getSize() > maxL1CacheSize) {
                evictFromL1(entry.getSize());
            }
            
            // Add to L1 cache
            l1Cache.put(key, entry);
            currentL1Size += entry.getSize();
            
            profiler.recordCustomMetric("storage", "cache", "cache.l1_put", 1, "count");
        } finally {
            l1Lock.writeLock().unlock();
        }
    }
    
    /**
     * Put a value in the L2 cache
     * 
     * @param key Cache key
     * @param entry Cache entry
     */
    private void putInL2(String key, CacheEntry entry) {
        l2Lock.writeLock().lock();
        try {
            // Check if we need to make room
            if (currentL2Size + entry.getSize() > maxL2CacheSize) {
                evictFromL2(entry.getSize());
            }
            
            // Add to L2 cache
            l2Cache.put(key, entry);
            currentL2Size += entry.getSize();
            
            profiler.recordCustomMetric("storage", "cache", "cache.l2_put", 1, "count");
        } finally {
            l2Lock.writeLock().unlock();
        }
    }
    
    /**
     * Promote an entry from L2 to L1 cache
     * 
     * @param key Cache key
     * @param entry Cache entry
     */
    private void promoteToL1(String key, CacheEntry entry) {
        // Remove from L2
        l2Lock.writeLock().lock();
        try {
            l2Cache.remove(key);
            currentL2Size -= entry.getSize();
        } finally {
            l2Lock.writeLock().unlock();
        }
        
        // Add to L1
        putInL1(key, entry);
        
        profiler.recordCustomMetric("storage", "cache.promotion", "increment", 1, "count");
    }
    
    /**
     * Evict entries from L1 cache to make room
     * 
     * @param sizeNeeded Amount of space needed in bytes
     */
    private void evictFromL1(int sizeNeeded) {
        int spaceFreed = 0;
        
        switch (evictionPolicy) {
            case LRU:
                spaceFreed = evictLRUFromL1(sizeNeeded);
                break;
            case LFU:
                spaceFreed = evictLFUFromL1(sizeNeeded);
                break;
            case FIFO:
                spaceFreed = evictFIFOFromL1(sizeNeeded);
                break;
        }
        
        evictions.addAndGet(spaceFreed > 0 ? 1 : 0);
    }
    
    /**
     * Evict entries from L2 cache to make room
     * 
     * @param sizeNeeded Amount of space needed in bytes
     */
    private void evictFromL2(int sizeNeeded) {
        int spaceFreed = 0;
        
        switch (evictionPolicy) {
            case LRU:
                spaceFreed = evictLRUFromL2(sizeNeeded);
                break;
            case LFU:
                spaceFreed = evictLFUFromL2(sizeNeeded);
                break;
            case FIFO:
                spaceFreed = evictFIFOFromL2(sizeNeeded);
                break;
        }
        
        evictions.addAndGet(spaceFreed > 0 ? 1 : 0);
    }
    
    /**
     * Evict entries from L1 cache using LRU policy
     * 
     * @param sizeNeeded Amount of space needed in bytes
     * @return Amount of space freed in bytes
     */
    private int evictLRUFromL1(int sizeNeeded) {
        int spaceFreed = 0;
        
        // For LinkedHashMap with access order, the oldest entries are at the beginning
        List<String> keysToRemove = new ArrayList<>();
        
        for (Map.Entry<String, CacheEntry> entry : l1Cache.entrySet()) {
            keysToRemove.add(entry.getKey());
            spaceFreed += entry.getValue().getSize();
            
            if (spaceFreed >= sizeNeeded) {
                break;
            }
        }
        
        // Remove the selected entries
        for (String key : keysToRemove) {
            CacheEntry entry = l1Cache.remove(key);
            if (entry != null) {
                currentL1Size -= entry.getSize();
                
                // Optionally demote to L2 instead of completely evicting
                if (adaptiveSizing && entry.getAccessCount() > 1) {
                    putInL2(key, entry);
                }
            }
        }
        
        return spaceFreed;
    }
    
    /**
     * Evict entries from L2 cache using LRU policy
     * 
     * @param sizeNeeded Amount of space needed in bytes
     * @return Amount of space freed in bytes
     */
    private int evictLRUFromL2(int sizeNeeded) {
        int spaceFreed = 0;
        
        // For LinkedHashMap with access order, the oldest entries are at the beginning
        List<String> keysToRemove = new ArrayList<>();
        
        for (Map.Entry<String, CacheEntry> entry : l2Cache.entrySet()) {
            keysToRemove.add(entry.getKey());
            spaceFreed += entry.getValue().getSize();
            
            if (spaceFreed >= sizeNeeded) {
                break;
            }
        }
        
        // Remove the selected entries
        for (String key : keysToRemove) {
            CacheEntry entry = l2Cache.remove(key);
            if (entry != null) {
                currentL2Size -= entry.getSize();
            }
        }
        
        return spaceFreed;
    }
    
    /**
     * Evict entries from L1 cache using LFU policy
     * 
     * @param sizeNeeded Amount of space needed in bytes
     * @return Amount of space freed in bytes
     */
    private int evictLFUFromL1(int sizeNeeded) {
        int spaceFreed = 0;
        
        // Find entries with lowest access count
        List<Map.Entry<String, CacheEntry>> entries = new ArrayList<>(l1Cache.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().getAccessCount(), b.getValue().getAccessCount()));
        
        List<String> keysToRemove = new ArrayList<>();
        
        for (Map.Entry<String, CacheEntry> entry : entries) {
            keysToRemove.add(entry.getKey());
            spaceFreed += entry.getValue().getSize();
            
            if (spaceFreed >= sizeNeeded) {
                break;
            }
        }
        
        // Remove the selected entries
        for (String key : keysToRemove) {
            CacheEntry entry = l1Cache.remove(key);
            if (entry != null) {
                currentL1Size -= entry.getSize();
                
                // Optionally demote to L2 instead of completely evicting
                if (adaptiveSizing && entry.getAccessCount() > 1) {
                    putInL2(key, entry);
                }
            }
        }
        
        return spaceFreed;
    }
    
    /**
     * Evict entries from L2 cache using LFU policy
     * 
     * @param sizeNeeded Amount of space needed in bytes
     * @return Amount of space freed in bytes
     */
    private int evictLFUFromL2(int sizeNeeded) {
        int spaceFreed = 0;
        
        // Find entries with lowest access count
        List<Map.Entry<String, CacheEntry>> entries = new ArrayList<>(l2Cache.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().getAccessCount(), b.getValue().getAccessCount()));
        
        List<String> keysToRemove = new ArrayList<>();
        
        for (Map.Entry<String, CacheEntry> entry : entries) {
            keysToRemove.add(entry.getKey());
            spaceFreed += entry.getValue().getSize();
            
            if (spaceFreed >= sizeNeeded) {
                break;
            }
        }
        
        // Remove the selected entries
        for (String key : keysToRemove) {
            CacheEntry entry = l2Cache.remove(key);
            if (entry != null) {
                currentL2Size -= entry.getSize();
            }
        }
        
        return spaceFreed;
    }
    
    /**
     * Evict entries from L1 cache using FIFO policy
     * 
     * @param sizeNeeded Amount of space needed in bytes
     * @return Amount of space freed in bytes
     */
    private int evictFIFOFromL1(int sizeNeeded) {
        int spaceFreed = 0;
        
        // For LinkedHashMap with insertion order, the oldest entries are at the beginning
        List<String> keysToRemove = new ArrayList<>();
        
        for (Map.Entry<String, CacheEntry> entry : l1Cache.entrySet()) {
            keysToRemove.add(entry.getKey());
            spaceFreed += entry.getValue().getSize();
            
            if (spaceFreed >= sizeNeeded) {
                break;
            }
        }
        
        // Remove the selected entries
        for (String key : keysToRemove) {
            CacheEntry entry = l1Cache.remove(key);
            if (entry != null) {
                currentL1Size -= entry.getSize();
                
                // Optionally demote to L2 instead of completely evicting
                if (adaptiveSizing && entry.getAccessCount() > 1) {
                    putInL2(key, entry);
                }
            }
        }
        
        return spaceFreed;
    }
    
    /**
     * Evict entries from L2 cache using FIFO policy
     * 
     * @param sizeNeeded Amount of space needed in bytes
     * @return Amount of space freed in bytes
     */
    private int evictFIFOFromL2(int sizeNeeded) {
        int spaceFreed = 0;
        
        // For LinkedHashMap with insertion order, the oldest entries are at the beginning
        List<String> keysToRemove = new ArrayList<>();
        
        for (Map.Entry<String, CacheEntry> entry : l2Cache.entrySet()) {
            keysToRemove.add(entry.getKey());
            spaceFreed += entry.getValue().getSize();
            
            if (spaceFreed >= sizeNeeded) {
                break;
            }
        }
        
        // Remove the selected entries
        for (String key : keysToRemove) {
            CacheEntry entry = l2Cache.remove(key);
            if (entry != null) {
                currentL2Size -= entry.getSize();
            }
        }
        
        return spaceFreed;
    }
    
    /**
     * Determine if an entry should be admitted to L1 cache
     * 
     * @param entry Cache entry
     * @return true if the entry should be admitted to L1
     */
    private boolean shouldAdmitToL1(CacheEntry entry) {
        // Simple admission policy based on access count and size
        if (entry.getSize() > maxL1CacheSize / 4) {
            return false; // Too large for L1
        }
        
        if (entry.getAccessCount() >= 2) {
            return true; // Accessed multiple times
        }
        
        // For small entries, admit more freely
        return entry.getSize() < 1024;
    }
    
    /**
     * Record an access for pattern detection
     * 
     * @param key Cache key
     */
    private void recordAccess(String key) {
        long now = System.currentTimeMillis();
        
        // Find the previous key accessed
        String previousKey = null;
        long previousTime = 0;
        
        for (Map.Entry<String, Long> entry : lastAccessTimes.entrySet()) {
            if (entry.getValue() > previousTime && !entry.getKey().equals(key)) {
                previousKey = entry.getKey();
                previousTime = entry.getValue();
            }
        }
        
        // Record this access time
        lastAccessTimes.put(key, now);
        
        // If we found a previous key and it was accessed recently, record the pattern
        if (previousKey != null && now - previousTime < 1000) { // Within 1 second
            accessPatterns.computeIfAbsent(previousKey, k -> new ArrayList<>()).add(key);
            
            // Limit pattern size
            List<String> pattern = accessPatterns.get(previousKey);
            if (pattern.size() > 10) {
                pattern.remove(0);
            }
        }
    }
    
    /**
     * Prefetch related keys based on access patterns
     * 
     * @param key Cache key
     */
    private void prefetchRelatedKeys(String key) {
        List<String> relatedKeys = accessPatterns.get(key);
        if (relatedKeys == null || relatedKeys.isEmpty()) {
            return;
        }
        
        // Trigger prefetch for the most commonly accessed related keys
        Map<String, Integer> frequency = new HashMap<>();
        for (String relatedKey : relatedKeys) {
            frequency.put(relatedKey, frequency.getOrDefault(relatedKey, 0) + 1);
        }
        
        // Sort by frequency
        List<Map.Entry<String, Integer>> sortedFrequency = new ArrayList<>(frequency.entrySet());
        sortedFrequency.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        // Prefetch the top 3 most frequent keys
        for (int i = 0; i < Math.min(3, sortedFrequency.size()); i++) {
            String keyToPrefetch = sortedFrequency.get(i).getKey();
            
            // Only prefetch if not already in cache
            if (!l1Cache.containsKey(keyToPrefetch) && !l2Cache.containsKey(keyToPrefetch)) {
                // In a real implementation, this would trigger an asynchronous prefetch
                // For this example, we just log it
                LOGGER.fine("Prefetching related key: " + keyToPrefetch);
                
                profiler.recordCustomMetric("storage", "cache.prefetch", "increment", 1, "count");
            }
        }
    }
    
    /**
     * Remove a value from the cache
     * 
     * @param key Cache key
     */
    public void remove(String key) {
        // Remove from L1
        l1Lock.writeLock().lock();
        try {
            CacheEntry entry = l1Cache.remove(key);
            if (entry != null) {
                currentL1Size -= entry.getSize();
            }
        } finally {
            l1Lock.writeLock().unlock();
        }
        
        // Remove from L2
        l2Lock.writeLock().lock();
        try {
            CacheEntry entry = l2Cache.remove(key);
            if (entry != null) {
                currentL2Size -= entry.getSize();
            }
        } finally {
            l2Lock.writeLock().unlock();
        }
    }
    
    /**
     * Clear the cache
     */
    public void clear() {
        // Clear L1
        l1Lock.writeLock().lock();
        try {
            l1Cache.clear();
            currentL1Size = 0;
        } finally {
            l1Lock.writeLock().unlock();
        }
        
        // Clear L2
        l2Lock.writeLock().lock();
        try {
            l2Cache.clear();
            currentL2Size = 0;
        } finally {
            l2Lock.writeLock().unlock();
        }
        
        // Clear access patterns
        accessPatterns.clear();
        lastAccessTimes.clear();
    }
    
    /**
     * Get cache statistics
     * 
     * @return Map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        long totalHits = hits.get();
        long totalMisses = misses.get();
        long totalAccesses = totalHits + totalMisses;
        
        stats.put("hitCount", totalHits);
        stats.put("missCount", totalMisses);
        stats.put("hitRatio", totalAccesses > 0 ? (double)totalHits / totalAccesses : 0);
        stats.put("evictionCount", evictions.get());
        stats.put("l1Size", currentL1Size);
        stats.put("l2Size", currentL2Size);
        stats.put("l1EntryCount", l1Cache.size());
        stats.put("l2EntryCount", l2Cache.size());
        stats.put("l1Utilization", (double)currentL1Size / maxL1CacheSize);
        stats.put("l2Utilization", (double)currentL2Size / maxL2CacheSize);
        
        return stats;
    }
    
    /**
     * Format a size in bytes to a human-readable string
     * 
     * @param bytes Size in bytes
     * @return Formatted string (e.g., "1.5 MB")
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * Cache entry class
     */
    private static class CacheEntry {
        private final byte[] value;
        private final int size;
        private final long creationTime;
        private long accessCount;
        
        public CacheEntry(byte[] value, int size) {
            this.value = value;
            this.size = size;
            this.creationTime = System.currentTimeMillis();
            this.accessCount = 1;
        }
        
        public byte[] getValue() {
            return value;
        }
        
        public int getSize() {
            return size;
        }
        
        public long getCreationTime() {
            return creationTime;
        }
        
        public long getAccessCount() {
            return accessCount;
        }
        
        public void incrementAccessCount() {
            accessCount++;
        }
    }
    
    /**
     * Cache eviction policy enum
     */
    public enum EvictionPolicy {
        LRU,  // Least Recently Used
        LFU,  // Least Frequently Used
        FIFO  // First In First Out
    }
}