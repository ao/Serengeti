package com.ataiva.serengeti.query.memory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ataiva.serengeti.performance.PerformanceDataCollector;

/**
 * Factory class for creating SpillManager instances.
 * This class provides methods for creating different types of SpillManager
 * instances based on the operation type and requirements.
 */
public class SpillManagerFactory {
    
    // Default maximum rows per chunk for sort operations
    private static final int DEFAULT_MAX_ROWS_PER_CHUNK = 10000;
    
    // Performance data collector
    private final PerformanceDataCollector performanceCollector;
    
    // Custom spill directory
    private final Path spillDirectory;
    
    /**
     * Constructor
     * @param performanceCollector Performance data collector
     */
    public SpillManagerFactory(PerformanceDataCollector performanceCollector) {
        this(performanceCollector, null);
    }
    
    /**
     * Constructor with custom spill directory
     * @param performanceCollector Performance data collector
     * @param spillDirectory Custom spill directory
     */
    public SpillManagerFactory(PerformanceDataCollector performanceCollector, Path spillDirectory) {
        this.performanceCollector = performanceCollector;
        this.spillDirectory = spillDirectory;
    }
    
    /**
     * Create a HashJoinSpillManager
     * @param queryId Query ID
     * @param operationId Operation ID
     * @return HashJoinSpillManager instance
     */
    public HashJoinSpillManager createHashJoinSpillManager(String queryId, String operationId) {
        List<Map<Object, List<Object[]>>> partitions = new ArrayList<>();
        partitions.add(new HashMap<>());
        
        if (spillDirectory != null) {
            return new HashJoinSpillManager(queryId, operationId, partitions, performanceCollector);
        } else {
            return new HashJoinSpillManager(queryId, operationId, partitions, performanceCollector);
        }
    }
    
    /**
     * Create a HashJoinSpillManager with existing partitions
     * @param queryId Query ID
     * @param operationId Operation ID
     * @param partitions Hash join partitions
     * @return HashJoinSpillManager instance
     */
    public HashJoinSpillManager createHashJoinSpillManager(
            String queryId, String operationId, List<Map<Object, List<Object[]>>> partitions) {
        
        if (spillDirectory != null) {
            return new HashJoinSpillManager(queryId, operationId, partitions, performanceCollector);
        } else {
            return new HashJoinSpillManager(queryId, operationId, partitions, performanceCollector);
        }
    }
    
    /**
     * Create a SortSpillManager
     * @param queryId Query ID
     * @param operationId Operation ID
     * @param comparator Comparator for sorting
     * @return SortSpillManager instance
     */
    public SortSpillManager createSortSpillManager(
            String queryId, String operationId, Comparator<Object[]> comparator) {
        
        List<List<Object[]>> chunks = new ArrayList<>();
        chunks.add(new ArrayList<>());
        
        if (spillDirectory != null) {
            return new SortSpillManager(
                queryId, operationId, chunks, comparator, performanceCollector, DEFAULT_MAX_ROWS_PER_CHUNK);
        } else {
            return new SortSpillManager(
                queryId, operationId, chunks, comparator, performanceCollector, DEFAULT_MAX_ROWS_PER_CHUNK);
        }
    }
    
    /**
     * Create a SortSpillManager with existing chunks
     * @param queryId Query ID
     * @param operationId Operation ID
     * @param chunks Sort chunks
     * @param comparator Comparator for sorting
     * @param maxRowsPerChunk Maximum number of rows per chunk
     * @return SortSpillManager instance
     */
    public SortSpillManager createSortSpillManager(
            String queryId, String operationId, List<List<Object[]>> chunks,
            Comparator<Object[]> comparator, int maxRowsPerChunk) {
        
        if (spillDirectory != null) {
            return new SortSpillManager(
                queryId, operationId, chunks, comparator, performanceCollector, maxRowsPerChunk);
        } else {
            return new SortSpillManager(
                queryId, operationId, chunks, comparator, performanceCollector, maxRowsPerChunk);
        }
    }
    
    /**
     * Generate a unique operation ID
     * @return Unique operation ID
     */
    public static String generateOperationId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Get the default spill directory
     * @return Default spill directory
     */
    public static Path getDefaultSpillDirectory() {
        return Paths.get(SpillManager.getDefaultSpillDirectory());
    }
    
    /**
     * Set the default spill directory
     * @param directory Default spill directory
     */
    public static void setDefaultSpillDirectory(String directory) {
        SpillManager.setDefaultSpillDirectory(directory);
    }
}