package com.ataiva.serengeti.query.memory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ataiva.serengeti.performance.PerformanceProfiler;

/**
 * HashJoinSpillManager is a concrete implementation of SpillManager
 * that handles spilling hash join data to disk when memory is constrained.
 */
public class HashJoinSpillManager extends SpillManager {
    private static final Logger LOGGER = Logger.getLogger(HashJoinSpillManager.class.getName());
    
    // Performance metrics
    private static final String METRIC_SPILL_TIME = "hash_join_spill_time";
    private static final String METRIC_READ_TIME = "hash_join_read_time";
    private static final String METRIC_SPILL_SIZE = "hash_join_spill_size";
    
    // Data to be spilled
    private List<Map<Object, List<Object[]>>> partitions;
    
    // Current partition being processed
    private int currentPartition;
    
    // Performance profiler
    private final PerformanceProfiler profiler;
    
    /**
     * Constructor
     * @param queryId Query ID
     * @param operationId Operation ID
     * @param partitions Hash join partitions
     * @param performanceCollector Performance data collector
     */
    public HashJoinSpillManager(String queryId, String operationId,
                               List<Map<Object, List<Object[]>>> partitions,
                               PerformanceProfiler profiler) {
        super(queryId, operationId);
        this.partitions = partitions;
        this.currentPartition = 0;
        this.profiler = profiler != null ? profiler : PerformanceProfiler.getInstance();
    }
    
    /**
     * Spill data to disk
     * @return True if spill succeeded, false if it failed
     */
    @Override
    public boolean spillToDisk() {
        if (currentPartition >= partitions.size()) {
            LOGGER.warning("No more partitions to spill");
            return false;
        }
        
        long startTime = System.nanoTime();
        
        try {
            Map<Object, List<Object[]>> partition = partitions.get(currentPartition);
            Path spillFile = createSpillFile();
            
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(spillFile)))) {
                
                // Write the partition to disk
                oos.writeObject(partition);
                
                // Update statistics
                long partitionSize = Files.size(spillFile);
                bytesSpilled += partitionSize;
                spillCount++;
                
                // Record performance metrics
                profiler.recordMemoryUsage("query", "hash_join_spill", METRIC_SPILL_SIZE, partitionSize);
                
                LOGGER.fine("Spilled partition " + currentPartition + " to " + spillFile + 
                           " (" + partitionSize + " bytes)");
                
                // Clear the partition from memory
                partition.clear();
                currentPartition++;
                
                return true;
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to spill partition " + currentPartition + " to disk", e);
            return false;
        } finally {
            long endTime = System.nanoTime();
            
            // Record performance metrics
            profiler.recordLatency("query", "hash_join_spill", METRIC_SPILL_TIME, (endTime - startTime) / 1_000_000.0);
        }
    }
    
    /**
     * Read spilled data from disk
     * @return True if read succeeded, false if it failed
     */
    @Override
    public boolean readFromDisk() {
        if (spillFiles.isEmpty()) {
            LOGGER.warning("No spill files to read");
            return false;
        }
        
        long startTime = System.nanoTime();
        
        try {
            Path spillFile = spillFiles.get(0);
            
            try (ObjectInputStream ois = new ObjectInputStream(
                    new BufferedInputStream(Files.newInputStream(spillFile)))) {
                
                // Read the partition from disk
                @SuppressWarnings("unchecked")
                Map<Object, List<Object[]>> partition = (Map<Object, List<Object[]>>) ois.readObject();
                
                // Add the partition back to the list
                if (currentPartition > 0) {
                    currentPartition--;
                }
                
                if (currentPartition < partitions.size()) {
                    partitions.set(currentPartition, partition);
                } else {
                    partitions.add(partition);
                }
                
                LOGGER.fine("Read partition from " + spillFile);
                
                // Remove the spill file from the list
                spillFiles.remove(0);
                
                // Delete the spill file
                Files.deleteIfExists(spillFile);
                
                return true;
            }
        } catch (IOException | ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Failed to read partition from disk", e);
            return false;
        } finally {
            long endTime = System.nanoTime();
            
            // Record performance metrics
            profiler.recordLatency("query", "hash_join_read", METRIC_READ_TIME, (endTime - startTime) / 1_000_000.0);
        }
    }
    
    /**
     * Get the number of partitions
     * @return Number of partitions
     */
    public int getPartitionCount() {
        return partitions.size();
    }
    
    /**
     * Get the current partition index
     * @return Current partition index
     */
    public int getCurrentPartition() {
        return currentPartition;
    }
    
    /**
     * Check if all partitions have been spilled
     * @return True if all partitions have been spilled, false otherwise
     */
    public boolean allPartitionsSpilled() {
        return currentPartition >= partitions.size();
    }
    
    /**
     * Create a new instance for testing
     * @param queryId Query ID
     * @param operationId Operation ID
     * @return HashJoinSpillManager instance
     */
    public static HashJoinSpillManager createForTesting(String queryId, String operationId) {
        List<Map<Object, List<Object[]>>> partitions = new ArrayList<>();
        Map<Object, List<Object[]>> partition = new HashMap<>();
        partitions.add(partition);
        
        return new HashJoinSpillManager(queryId, operationId, partitions, PerformanceProfiler.getInstance());
    }
}