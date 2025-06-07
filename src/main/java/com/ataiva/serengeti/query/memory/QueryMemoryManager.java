package com.ataiva.serengeti.query.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ataiva.serengeti.performance.PerformanceProfiler;

/**
 * QueryMemoryManager manages memory resources for query execution.
 * It provides memory budgeting, spill-to-disk capabilities, and buffer pool management.
 */
public class QueryMemoryManager {
    private static final Logger LOGGER = Logger.getLogger(QueryMemoryManager.class.getName());
    
    // Singleton instance
    private static QueryMemoryManager instance;
    
    // Memory budget configuration
    private long totalMemoryBudget; // in bytes
    private double queryMemoryFraction; // fraction of total memory for queries
    private long reservedSystemMemory; // memory reserved for system operations
    
    // Active query memory allocations
    private final Map<String, QueryMemoryContext> activeQueries;
    
    // Buffer pool
    private final BufferPool bufferPool;
    
    // Default values
    private static final long DEFAULT_TOTAL_MEMORY_BUDGET = 1024 * 1024 * 1024; // 1GB
    private static final double DEFAULT_QUERY_MEMORY_FRACTION = 0.7; // 70% of total memory
    private static final long DEFAULT_RESERVED_SYSTEM_MEMORY = 256 * 1024 * 1024; // 256MB
    
    /**
     * Private constructor for singleton pattern
     */
    private QueryMemoryManager() {
        this.totalMemoryBudget = DEFAULT_TOTAL_MEMORY_BUDGET;
        this.queryMemoryFraction = DEFAULT_QUERY_MEMORY_FRACTION;
        this.reservedSystemMemory = DEFAULT_RESERVED_SYSTEM_MEMORY;
        this.activeQueries = new ConcurrentHashMap<>();
        this.bufferPool = new BufferPool(getQueryPoolMemory());
    }
    
    /**
     * Get the singleton instance of QueryMemoryManager
     * @return QueryMemoryManager instance
     */
    public static synchronized QueryMemoryManager getInstance() {
        if (instance == null) {
            instance = new QueryMemoryManager();
        }
        return instance;
    }
    
    /**
     * Initialize the memory manager with system information
     * @param availableMemory Total available memory in bytes
     */
    public void initialize(long availableMemory) {
        this.totalMemoryBudget = availableMemory;
        this.bufferPool.resize(getQueryPoolMemory());
        LOGGER.info("QueryMemoryManager initialized with " + (totalMemoryBudget / (1024 * 1024)) + "MB total memory");
    }
    
    /**
     * Create a new query memory context
     * @return Query memory context ID
     */
    public String createQueryContext() {
        String queryId = UUID.randomUUID().toString();
        QueryMemoryContext context = new QueryMemoryContext(queryId, calculateQueryMemoryBudget());
        activeQueries.put(queryId, context);
        LOGGER.fine("Created query memory context: " + queryId + " with budget: " + 
                   (context.getMemoryBudget() / (1024 * 1024)) + "MB");
        return queryId;
    }
    
    /**
     * Release a query memory context
     * @param queryId Query ID
     */
    public void releaseQueryContext(String queryId) {
        QueryMemoryContext context = activeQueries.remove(queryId);
        if (context != null) {
            // Release any allocated memory
            bufferPool.releaseBuffers(queryId);
            LOGGER.fine("Released query memory context: " + queryId);
        }
    }
    
    /**
     * Allocate memory for a query operation
     * @param queryId Query ID
     * @param operationId Operation ID
     * @param bytes Number of bytes to allocate
     * @return True if allocation succeeded, false if it failed
     */
    public boolean allocateMemory(String queryId, String operationId, long bytes) {
        String timerId = PerformanceProfiler.getInstance().startTimer("query", "memory-allocate");
        try {
            QueryMemoryContext context = activeQueries.get(queryId);
            if (context == null) {
                LOGGER.warning("Attempted to allocate memory for unknown query: " + queryId);
                return false;
            }
            
            // Check if allocation would exceed the query's budget
            if (context.getAllocatedBytes() + bytes > context.getMemoryBudget()) {
                // Try to spill to disk if possible
                if (context.canSpillToDisk(operationId)) {
                    spillToDisk(queryId, operationId);
                    context.recordAllocation(operationId, bytes);
                    return true;
                }
                
                LOGGER.warning("Memory allocation failed for query " + queryId + 
                              ": would exceed budget of " + context.getMemoryBudget() + " bytes");
                return false;
            }
            
            // Allocate memory from the buffer pool
            boolean allocated = bufferPool.allocateBuffers(queryId, operationId, bytes);
            if (allocated) {
                context.recordAllocation(operationId, bytes);
                return true;
            } else {
                LOGGER.warning("Buffer pool allocation failed for query " + queryId);
                return false;
            }
        } finally {
            PerformanceProfiler.getInstance().stopTimer(timerId, "query.memory-allocate-time");
        }
    }
    
    /**
     * Free memory for a query operation
     * @param queryId Query ID
     * @param operationId Operation ID
     */
    public void freeMemory(String queryId, String operationId) {
        String timerId = PerformanceProfiler.getInstance().startTimer("query", "memory-free");
        try {
            QueryMemoryContext context = activeQueries.get(queryId);
            if (context == null) {
                LOGGER.warning("Attempted to free memory for unknown query: " + queryId);
                return;
            }
            
            long bytes = context.getOperationAllocation(operationId);
            if (bytes > 0) {
                bufferPool.releaseBuffers(queryId, operationId);
                context.recordDeallocation(operationId);
            }
        } finally {
            PerformanceProfiler.getInstance().stopTimer(timerId, "query.memory-free-time");
        }
    }
    
    /**
     * Spill a query operation to disk
     * @param queryId Query ID
     * @param operationId Operation ID
     * @return True if spill succeeded, false if it failed
     */
    public boolean spillToDisk(String queryId, String operationId) {
        String timerId = PerformanceProfiler.getInstance().startTimer("query", "spill-to-disk");
        try {
            QueryMemoryContext context = activeQueries.get(queryId);
            if (context == null) {
                LOGGER.warning("Attempted to spill to disk for unknown query: " + queryId);
                return false;
            }
            
            // Check if the operation supports spilling
            if (!context.canSpillToDisk(operationId)) {
                LOGGER.warning("Operation does not support spilling to disk: " + operationId);
                return false;
            }
            
            // Get the spill manager for this operation
            SpillManager spillManager = context.getSpillManager(operationId);
            if (spillManager == null) {
                LOGGER.warning("No spill manager for operation: " + operationId);
                return false;
            }
            
            // Perform the spill operation
            boolean success = spillManager.spillToDisk();
            if (success) {
                // Free memory after successful spill
                long bytes = context.getOperationAllocation(operationId);
                bufferPool.releaseBuffers(queryId, operationId);
                context.recordSpill(operationId, bytes);
                LOGGER.fine("Spilled operation " + operationId + " to disk for query " + queryId);
            } else {
                LOGGER.warning("Failed to spill operation " + operationId + " to disk for query " + queryId);
            }
            
            return success;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error spilling to disk", e);
            return false;
        } finally {
            PerformanceProfiler.getInstance().stopTimer(timerId, "query.spill-to-disk-time");
        }
    }
    
    /**
     * Read spilled data from disk for a query operation
     * @param queryId Query ID
     * @param operationId Operation ID
     * @return True if read succeeded, false if it failed
     */
    public boolean readFromDisk(String queryId, String operationId) {
        String timerId = PerformanceProfiler.getInstance().startTimer("query", "read-from-disk");
        try {
            QueryMemoryContext context = activeQueries.get(queryId);
            if (context == null) {
                LOGGER.warning("Attempted to read from disk for unknown query: " + queryId);
                return false;
            }
            
            // Check if the operation has been spilled
            if (!context.hasSpilledData(operationId)) {
                LOGGER.warning("Operation has no spilled data: " + operationId);
                return false;
            }
            
            // Get the spill manager for this operation
            SpillManager spillManager = context.getSpillManager(operationId);
            if (spillManager == null) {
                LOGGER.warning("No spill manager for operation: " + operationId);
                return false;
            }
            
            // Perform the read operation
            boolean success = spillManager.readFromDisk();
            if (success) {
                // Record memory allocation after successful read
                long bytes = context.getSpilledBytes(operationId);
                bufferPool.allocateBuffers(queryId, operationId, bytes);
                context.recordReadFromDisk(operationId, bytes);
                LOGGER.fine("Read operation " + operationId + " from disk for query " + queryId);
            } else {
                LOGGER.warning("Failed to read operation " + operationId + " from disk for query " + queryId);
            }
            
            return success;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error reading from disk", e);
            return false;
        } finally {
            PerformanceProfiler.getInstance().stopTimer(timerId, "query.read-from-disk-time");
        }
    }
    
    /**
     * Register a spill manager for a query operation
     * @param queryId Query ID
     * @param operationId Operation ID
     * @param spillManager Spill manager
     */
    public void registerSpillManager(String queryId, String operationId, SpillManager spillManager) {
        QueryMemoryContext context = activeQueries.get(queryId);
        if (context == null) {
            LOGGER.warning("Attempted to register spill manager for unknown query: " + queryId);
            return;
        }
        
        context.registerSpillManager(operationId, spillManager);
        LOGGER.fine("Registered spill manager for operation " + operationId + " in query " + queryId);
    }
    
    /**
     * Get the memory usage for a query
     * @param queryId Query ID
     * @return Memory usage in bytes, or -1 if the query is not found
     */
    public long getQueryMemoryUsage(String queryId) {
        QueryMemoryContext context = activeQueries.get(queryId);
        if (context == null) {
            return -1;
        }
        
        return context.getAllocatedBytes();
    }
    
    /**
     * Get the total memory budget for queries
     * @return Total memory budget in bytes
     */
    public long getQueryPoolMemory() {
        return (long) (totalMemoryBudget * queryMemoryFraction) - reservedSystemMemory;
    }
    
    /**
     * Calculate the memory budget for a new query
     * @return Memory budget in bytes
     */
    private long calculateQueryMemoryBudget() {
        // Simple strategy: divide available memory by number of active queries + 1
        long availableMemory = getQueryPoolMemory();
        int numQueries = activeQueries.size() + 1;
        return availableMemory / numQueries;
    }
    
    /**
     * Set the total memory budget
     * @param bytes Total memory budget in bytes
     */
    public void setTotalMemoryBudget(long bytes) {
        this.totalMemoryBudget = bytes;
        this.bufferPool.resize(getQueryPoolMemory());
    }
    
    /**
     * Set the query memory fraction
     * @param fraction Fraction of total memory for queries (0.0 to 1.0)
     */
    public void setQueryMemoryFraction(double fraction) {
        if (fraction <= 0.0 || fraction > 1.0) {
            throw new IllegalArgumentException("Query memory fraction must be between 0.0 and 1.0");
        }
        this.queryMemoryFraction = fraction;
        this.bufferPool.resize(getQueryPoolMemory());
    }
    
    /**
     * Set the reserved system memory
     * @param bytes Reserved system memory in bytes
     */
    public void setReservedSystemMemory(long bytes) {
        this.reservedSystemMemory = bytes;
        this.bufferPool.resize(getQueryPoolMemory());
    }
    
    /**
     * Get the buffer pool
     * @return Buffer pool
     */
    public BufferPool getBufferPool() {
        return bufferPool;
    }
    
    /**
     * Get memory usage statistics
     * @return Map of statistics
     */
    public Map<String, Object> getMemoryStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalMemoryBudget", totalMemoryBudget);
        stats.put("queryMemoryFraction", queryMemoryFraction);
        stats.put("reservedSystemMemory", reservedSystemMemory);
        stats.put("queryPoolMemory", getQueryPoolMemory());
        stats.put("activeQueries", activeQueries.size());
        stats.put("bufferPoolStats", bufferPool.getStats());
        
        return stats;
    }
    
    /**
     * Optimize memory usage for a query plan
     * This method analyzes the query plan and adjusts memory allocations
     * to optimize performance while staying within memory constraints.
     *
     * @param plan The query plan to optimize
     * @return The optimized query plan
     */
    public com.ataiva.serengeti.query.optimizer.QueryPlan optimizeMemoryUsage(
            com.ataiva.serengeti.query.optimizer.QueryPlan plan) {
        String timerId = PerformanceProfiler.getInstance().startTimer("query", "memory-optimize");
        try {
            // Get the estimated memory usage from the plan
            long estimatedMemory = plan.getEstimatedMemoryUsage();
            
            // If the plan doesn't have memory usage estimates, return it unchanged
            if (estimatedMemory <= 0) {
                return plan;
            }
            
            // Calculate available memory
            long availableMemory = getQueryPoolMemory() - bufferPool.getAllocatedMemory();
            
            // If we have enough memory, return the plan unchanged
            if (estimatedMemory <= availableMemory) {
                LOGGER.fine("Query plan memory usage (" + estimatedMemory +
                           " bytes) is within available memory (" + availableMemory + " bytes)");
                return plan;
            }
            
            // We need to optimize memory usage
            LOGGER.info("Optimizing query plan memory usage: estimated=" + estimatedMemory +
                       " bytes, available=" + availableMemory + " bytes");
            
            // Set the memory budget in the plan
            plan.setEstimatedMemoryUsage(Math.min(estimatedMemory, availableMemory));
            
            // Return the optimized plan
            return plan;
        } finally {
            PerformanceProfiler.getInstance().stopTimer(timerId, "query.memory-optimize-time");
        }
    }
    
    /**
     * Inner class representing a query's memory context
     */
    private static class QueryMemoryContext {
        private final String queryId;
        private final long memoryBudget;
        private final Map<String, Long> operationAllocations;
        private final Map<String, SpillManager> spillManagers;
        private final Map<String, Long> operationSpilledBytes;
        private long allocatedBytes;
        private long spilledBytes;
        
        public QueryMemoryContext(String queryId, long memoryBudget) {
            this.queryId = queryId;
            this.memoryBudget = memoryBudget;
            this.operationAllocations = new HashMap<>();
            this.spillManagers = new HashMap<>();
            this.operationSpilledBytes = new HashMap<>();
            this.allocatedBytes = 0;
            this.spilledBytes = 0;
        }
        
        public String getQueryId() {
            return queryId;
        }
        
        public long getMemoryBudget() {
            return memoryBudget;
        }
        
        public long getAllocatedBytes() {
            return allocatedBytes;
        }
        
        public long getSpilledBytes() {
            return spilledBytes;
        }
        
        public void recordAllocation(String operationId, long bytes) {
            operationAllocations.put(operationId, bytes);
            allocatedBytes += bytes;
        }
        
        public void recordDeallocation(String operationId) {
            Long bytes = operationAllocations.remove(operationId);
            if (bytes != null) {
                allocatedBytes -= bytes;
            }
        }
        
        public void recordSpill(String operationId, long bytes) {
            spilledBytes += bytes;
            allocatedBytes -= bytes;
            operationSpilledBytes.put(operationId, bytes);
            operationAllocations.remove(operationId);
        }
        
        public void recordReadFromDisk(String operationId, long bytes) {
            spilledBytes -= bytes;
            allocatedBytes += bytes;
            operationAllocations.put(operationId, bytes);
            operationSpilledBytes.remove(operationId);
        }
        
        public long getOperationAllocation(String operationId) {
            return operationAllocations.getOrDefault(operationId, 0L);
        }
        
        public void registerSpillManager(String operationId, SpillManager spillManager) {
            spillManagers.put(operationId, spillManager);
        }
        
        public boolean canSpillToDisk(String operationId) {
            return spillManagers.containsKey(operationId);
        }
        
        public boolean hasSpilledData(String operationId) {
            return operationSpilledBytes.containsKey(operationId);
        }
        
        public long getSpilledBytes(String operationId) {
            return operationSpilledBytes.getOrDefault(operationId, 0L);
        }
        
        public SpillManager getSpillManager(String operationId) {
            return spillManagers.get(operationId);
        }
    }
}