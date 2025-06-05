package com.ataiva.serengeti.query.cache;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * QueryCache provides caching functionality for query results to improve performance
 * for frequently executed queries.
 */
public class QueryCache {
    private static final Logger LOGGER = Logger.getLogger(QueryCache.class.getName());
    
    // Singleton instance
    private static QueryCache instance;
    
    // Cache of query results
    private final Map<String, CacheEntry> cache;
    
    // Cache configuration
    private long maxCacheSize;
    private long maxEntryLifetimeMs;
    private boolean cacheEnabled;
    
    // Cache statistics
    private long hits;
    private long misses;
    private long evictions;
    
    // Executor for background cache maintenance
    private final ScheduledExecutorService scheduler;
    
    // Default cache settings
    private static final long DEFAULT_MAX_CACHE_SIZE = 1000;
    private static final long DEFAULT_MAX_ENTRY_LIFETIME_MS = 5 * 60 * 1000; // 5 minutes
    
    /**
     * Private constructor for singleton pattern
     */
    private QueryCache() {
        this.cache = new ConcurrentHashMap<>();
        this.maxCacheSize = DEFAULT_MAX_CACHE_SIZE;
        this.maxEntryLifetimeMs = DEFAULT_MAX_ENTRY_LIFETIME_MS;
        this.cacheEnabled = true;
        this.hits = 0;
        this.misses = 0;
        this.evictions = 0;
        this.scheduler = Executors.newScheduledThreadPool(1);
        
        // Schedule periodic cache cleanup
        scheduler.scheduleAtFixedRate(
            this::cleanupExpiredEntries,
            1,
            1,
            TimeUnit.MINUTES
        );
    }
    
    /**
     * Get the singleton instance of QueryCache
     * @return QueryCache instance
     */
    public static synchronized QueryCache getInstance() {
        if (instance == null) {
            instance = new QueryCache();
        }
        return instance;
    }
    
    /**
     * Check if a query result is in the cache
     * @param queryKey Query key
     * @return True if the query is in the cache and not expired
     */
    public boolean containsQuery(String queryKey) {
        if (!cacheEnabled) {
            return false;
        }
        
        CacheEntry entry = cache.get(queryKey);
        if (entry == null) {
            return false;
        }
        
        // Check if the entry has expired
        if (entry.isExpired()) {
            cache.remove(queryKey);
            evictions++;
            return false;
        }
        
        return true;
    }
    
    /**
     * Get a query result from the cache
     * @param queryKey Query key
     * @return Query result or null if not in cache
     */
    public List<String> getQueryResult(String queryKey) {
        if (!cacheEnabled) {
            misses++;
            return null;
        }
        
        CacheEntry entry = cache.get(queryKey);
        if (entry == null) {
            misses++;
            return null;
        }
        
        // Check if the entry has expired
        if (entry.isExpired()) {
            cache.remove(queryKey);
            evictions++;
            misses++;
            return null;
        }
        
        // Update access time and hit count
        entry.updateAccessTime();
        hits++;
        
        return entry.getResult();
    }
    
    /**
     * Put a query result in the cache
     * @param queryKey Query key
     * @param result Query result
     */
    public void putQueryResult(String queryKey, List<String> result) {
        if (!cacheEnabled) {
            return;
        }
        
        // Check if we need to evict entries to make room
        if (cache.size() >= maxCacheSize) {
            evictLeastRecentlyUsed();
        }
        
        // Create a new cache entry
        CacheEntry entry = new CacheEntry(result);
        cache.put(queryKey, entry);
        
        LOGGER.fine("Cached query result for key: " + queryKey);
    }
    
    /**
     * Invalidate a specific query in the cache
     * @param queryKey Query key
     */
    public void invalidateQuery(String queryKey) {
        cache.remove(queryKey);
    }
    
    /**
     * Invalidate all queries related to a specific table
     * @param database Database name
     * @param table Table name
     */
    public void invalidateTable(String database, String table) {
        String tableKey = database + "." + table;
        
        // Remove all entries that contain this table
        cache.entrySet().removeIf(entry -> entry.getKey().contains(tableKey));
        
        LOGGER.info("Invalidated cache entries for table: " + tableKey);
    }
    
    /**
     * Clear the entire cache
     */
    public void clearCache() {
        cache.clear();
        LOGGER.info("Cache cleared");
    }
    
    /**
     * Clean up expired entries in the cache
     */
    private void cleanupExpiredEntries() {
        try {
            long now = System.currentTimeMillis();
            int removedCount = 0;
            
            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                if (entry.getValue().getCreationTime() + maxEntryLifetimeMs < now) {
                    cache.remove(entry.getKey());
                    removedCount++;
                    evictions++;
                }
            }
            
            if (removedCount > 0) {
                LOGGER.info("Removed " + removedCount + " expired cache entries");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during cache cleanup", e);
        }
    }
    
    /**
     * Evict the least recently used entry from the cache
     */
    private void evictLeastRecentlyUsed() {
        String lruKey = null;
        long oldestAccessTime = Long.MAX_VALUE;
        
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().getLastAccessTime() < oldestAccessTime) {
                oldestAccessTime = entry.getValue().getLastAccessTime();
                lruKey = entry.getKey();
            }
        }
        
        if (lruKey != null) {
            cache.remove(lruKey);
            evictions++;
            LOGGER.fine("Evicted least recently used cache entry: " + lruKey);
        }
    }
    
    /**
     * Enable or disable the cache
     * @param enabled True to enable, false to disable
     */
    public void setCacheEnabled(boolean enabled) {
        this.cacheEnabled = enabled;
        if (!enabled) {
            clearCache();
        }
    }
    
    /**
     * Set the maximum cache size
     * @param maxSize Maximum number of entries in the cache
     */
    public void setMaxCacheSize(long maxSize) {
        this.maxCacheSize = maxSize;
        
        // If the new size is smaller than the current cache size, evict entries
        while (cache.size() > maxCacheSize) {
            evictLeastRecentlyUsed();
        }
    }
    
    /**
     * Set the maximum entry lifetime
     * @param maxLifetimeMs Maximum lifetime in milliseconds
     */
    public void setMaxEntryLifetime(long maxLifetimeMs) {
        this.maxEntryLifetimeMs = maxLifetimeMs;
    }
    
    /**
     * Get the current cache size
     * @return Number of entries in the cache
     */
    public int getCacheSize() {
        return cache.size();
    }
    
    /**
     * Get cache hit count
     * @return Number of cache hits
     */
    public long getHits() {
        return hits;
    }
    
    /**
     * Get cache miss count
     * @return Number of cache misses
     */
    public long getMisses() {
        return misses;
    }
    
    /**
     * Get cache eviction count
     * @return Number of cache evictions
     */
    public long getEvictions() {
        return evictions;
    }
    
    /**
     * Get cache hit ratio
     * @return Hit ratio (hits / (hits + misses))
     */
    public double getHitRatio() {
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0;
    }
    
    /**
     * Reset cache statistics
     */
    public void resetStatistics() {
        hits = 0;
        misses = 0;
        evictions = 0;
    }
    
    /**
     * Shutdown the cache
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Generate a cache key for a query
     * @param database Database name
     * @param table Table name
     * @param selectColumns Columns to select
     * @param whereColumn Column in WHERE clause
     * @param whereValue Value in WHERE clause
     * @param whereOperator Operator in WHERE clause
     * @return Cache key
     */
    public static String generateCacheKey(String database, String table, 
                                         String selectColumns, String whereColumn, 
                                         String whereValue, String whereOperator) {
        return String.format("%s.%s|%s|%s|%s|%s", 
                            database, table, selectColumns, 
                            whereColumn, whereValue, whereOperator);
    }
    
    /**
     * Generate a cache key for a prepared statement
     * @param statementId Prepared statement ID
     * @param parameters Parameter values
     * @return Cache key
     */
    public static String generatePreparedStatementCacheKey(String statementId, Object[] parameters) {
        StringBuilder sb = new StringBuilder(statementId);
        sb.append("|");
        
        if (parameters != null) {
            for (Object param : parameters) {
                sb.append(param).append(",");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Inner class representing a cache entry
     */
    private static class CacheEntry {
        private final List<String> result;
        private final long creationTime;
        private long lastAccessTime;
        private int accessCount;
        
        /**
         * Constructor
         * @param result Query result
         */
        public CacheEntry(List<String> result) {
            this.result = result;
            this.creationTime = System.currentTimeMillis();
            this.lastAccessTime = this.creationTime;
            this.accessCount = 0;
        }
        
        /**
         * Get the query result
         * @return Query result
         */
        public List<String> getResult() {
            return result;
        }
        
        /**
         * Get the creation time
         * @return Creation time in milliseconds
         */
        public long getCreationTime() {
            return creationTime;
        }
        
        /**
         * Get the last access time
         * @return Last access time in milliseconds
         */
        public long getLastAccessTime() {
            return lastAccessTime;
        }
        
        /**
         * Get the access count
         * @return Number of times this entry has been accessed
         */
        public int getAccessCount() {
            return accessCount;
        }
        
        /**
         * Update the access time and increment the access count
         */
        public void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount++;
        }
        
        /**
         * Check if this entry has expired
         * @return True if expired
         */
        public boolean isExpired() {
            long maxLifetime = QueryCache.getInstance().maxEntryLifetimeMs;
            return System.currentTimeMillis() - creationTime > maxLifetime;
        }
    }
}