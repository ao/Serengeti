package com.ataiva.serengeti.query.memory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ataiva.serengeti.performance.PerformanceMetric;
import com.ataiva.serengeti.performance.PerformanceDataCollector;

/**
 * SortSpillManager is a concrete implementation of SpillManager
 * that handles spilling sort data to disk when memory is constrained.
 * It implements external merge sort algorithm for handling large datasets.
 */
public class SortSpillManager extends SpillManager {
    private static final Logger LOGGER = Logger.getLogger(SortSpillManager.class.getName());
    
    // Performance metrics
    private static final String METRIC_SPILL_TIME = "sort_spill_time";
    private static final String METRIC_READ_TIME = "sort_read_time";
    private static final String METRIC_MERGE_TIME = "sort_merge_time";
    private static final String METRIC_SPILL_SIZE = "sort_spill_size";
    
    // Data to be spilled
    private List<List<Object[]>> chunks;
    
    // Comparator for sorting
    private final Comparator<Object[]> comparator;
    
    // Current chunk being processed
    private int currentChunk;
    
    // Performance data collector
    private final PerformanceDataCollector performanceCollector;
    
    // Maximum number of rows per chunk
    private final int maxRowsPerChunk;
    
    /**
     * Constructor
     * @param queryId Query ID
     * @param operationId Operation ID
     * @param chunks Sort chunks
     * @param comparator Comparator for sorting
     * @param performanceCollector Performance data collector
     * @param maxRowsPerChunk Maximum number of rows per chunk
     */
    public SortSpillManager(String queryId, String operationId, 
                           List<List<Object[]>> chunks,
                           Comparator<Object[]> comparator,
                           PerformanceDataCollector performanceCollector,
                           int maxRowsPerChunk) {
        super(queryId, operationId);
        this.chunks = chunks;
        this.comparator = comparator;
        this.currentChunk = 0;
        this.performanceCollector = performanceCollector;
        this.maxRowsPerChunk = maxRowsPerChunk;
    }
    
    /**
     * Spill data to disk
     * @return True if spill succeeded, false if it failed
     */
    @Override
    public boolean spillToDisk() {
        if (currentChunk >= chunks.size()) {
            LOGGER.warning("No more chunks to spill");
            return false;
        }
        
        long startTime = System.nanoTime();
        
        try {
            List<Object[]> chunk = chunks.get(currentChunk);
            
            // Sort the chunk before spilling
            chunk.sort(comparator);
            
            Path spillFile = createSpillFile();
            
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(spillFile)))) {
                
                // Write the chunk to disk
                oos.writeObject(chunk);
                
                // Update statistics
                long chunkSize = Files.size(spillFile);
                bytesSpilled += chunkSize;
                spillCount++;
                
                // Record performance metrics
                if (performanceCollector != null) {
                    performanceCollector.recordMetric(
                        new PerformanceMetric(METRIC_SPILL_SIZE, queryId, operationId, chunkSize)
                    );
                }
                
                LOGGER.fine("Spilled chunk " + currentChunk + " to " + spillFile + 
                           " (" + chunkSize + " bytes)");
                
                // Clear the chunk from memory
                chunk.clear();
                currentChunk++;
                
                return true;
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to spill chunk " + currentChunk + " to disk", e);
            return false;
        } finally {
            long endTime = System.nanoTime();
            
            // Record performance metrics
            if (performanceCollector != null) {
                performanceCollector.recordMetric(
                    new PerformanceMetric(METRIC_SPILL_TIME, queryId, operationId, endTime - startTime)
                );
            }
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
                
                // Read the chunk from disk
                @SuppressWarnings("unchecked")
                List<Object[]> chunk = (List<Object[]>) ois.readObject();
                
                // Add the chunk back to the list
                if (currentChunk > 0) {
                    currentChunk--;
                }
                
                if (currentChunk < chunks.size()) {
                    chunks.set(currentChunk, chunk);
                } else {
                    chunks.add(chunk);
                }
                
                LOGGER.fine("Read chunk from " + spillFile);
                
                // Remove the spill file from the list
                spillFiles.remove(0);
                
                // Delete the spill file
                Files.deleteIfExists(spillFile);
                
                return true;
            }
        } catch (IOException | ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Failed to read chunk from disk", e);
            return false;
        } finally {
            long endTime = System.nanoTime();
            
            // Record performance metrics
            if (performanceCollector != null) {
                performanceCollector.recordMetric(
                    new PerformanceMetric(METRIC_READ_TIME, queryId, operationId, endTime - startTime)
                );
            }
        }
    }
    
    /**
     * Merge all spilled chunks into a single sorted list
     * @return Merged sorted list
     */
    public List<Object[]> mergeChunks() {
        if (spillFiles.isEmpty()) {
            // If no spill files, return the in-memory chunks
            List<Object[]> result = new ArrayList<>();
            for (List<Object[]> chunk : chunks) {
                result.addAll(chunk);
            }
            result.sort(comparator);
            return result;
        }
        
        long startTime = System.nanoTime();
        List<ObjectInputStream> streams = new ArrayList<>();
        List<Object[]> result = new ArrayList<>();
        
        try {
            // Create a priority queue for merging
            PriorityQueue<ChunkEntry> queue = new PriorityQueue<>(
                (a, b) -> comparator.compare(a.value, b.value)
            );
            
            // Open all spill files
            for (int i = 0; i < spillFiles.size(); i++) {
                ObjectInputStream ois = new ObjectInputStream(
                    new BufferedInputStream(Files.newInputStream(spillFiles.get(i)))
                );
                streams.add(ois);
                
                // Read the chunk from disk
                @SuppressWarnings("unchecked")
                List<Object[]> chunk = (List<Object[]>) ois.readObject();
                
                // Add the first element of each chunk to the queue
                if (!chunk.isEmpty()) {
                    queue.add(new ChunkEntry(i, chunk.remove(0), chunk));
                }
            }
            
            // Merge chunks
            while (!queue.isEmpty()) {
                ChunkEntry entry = queue.poll();
                result.add(entry.value);
                
                // Add the next element from the same chunk
                if (!entry.remainingChunk.isEmpty()) {
                    queue.add(new ChunkEntry(
                        entry.chunkIndex, 
                        entry.remainingChunk.remove(0), 
                        entry.remainingChunk
                    ));
                }
                
                // If result size exceeds maxRowsPerChunk, spill to a new file
                if (result.size() >= maxRowsPerChunk) {
                    spillMergedChunk(result);
                    result = new ArrayList<>();
                }
            }
            
            // Clean up
            cleanup();
            
            return result;
        } catch (IOException | ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Failed to merge chunks", e);
            return new ArrayList<>();
        } finally {
            // Close all streams
            for (ObjectInputStream ois : streams) {
                try {
                    ois.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to close stream", e);
                }
            }
            
            long endTime = System.nanoTime();
            
            // Record performance metrics
            if (performanceCollector != null) {
                performanceCollector.recordMetric(
                    new PerformanceMetric(METRIC_MERGE_TIME, queryId, operationId, endTime - startTime)
                );
            }
        }
    }
    
    /**
     * Spill a merged chunk to disk
     * @param chunk Chunk to spill
     */
    private void spillMergedChunk(List<Object[]> chunk) {
        try {
            Path spillFile = createSpillFile();
            
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(spillFile)))) {
                
                // Write the chunk to disk
                oos.writeObject(chunk);
                
                // Update statistics
                long chunkSize = Files.size(spillFile);
                bytesSpilled += chunkSize;
                spillCount++;
                
                LOGGER.fine("Spilled merged chunk to " + spillFile + 
                           " (" + chunkSize + " bytes)");
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to spill merged chunk to disk", e);
        }
    }
    
    /**
     * Get the number of chunks
     * @return Number of chunks
     */
    public int getChunkCount() {
        return chunks.size();
    }
    
    /**
     * Get the current chunk index
     * @return Current chunk index
     */
    public int getCurrentChunk() {
        return currentChunk;
    }
    
    /**
     * Check if all chunks have been spilled
     * @return True if all chunks have been spilled, false otherwise
     */
    public boolean allChunksSpilled() {
        return currentChunk >= chunks.size();
    }
    
    /**
     * Create a new instance for testing
     * @param queryId Query ID
     * @param operationId Operation ID
     * @return SortSpillManager instance
     */
    public static SortSpillManager createForTesting(String queryId, String operationId) {
        List<List<Object[]>> chunks = new ArrayList<>();
        List<Object[]> chunk = new ArrayList<>();
        chunks.add(chunk);
        
        return new SortSpillManager(
            queryId, 
            operationId, 
            chunks, 
            (a, b) -> 0, // No-op comparator
            null, 
            1000
        );
    }
    
    /**
     * Helper class for merge sort
     */
    private static class ChunkEntry {
        final int chunkIndex;
        final Object[] value;
        final List<Object[]> remainingChunk;
        
        ChunkEntry(int chunkIndex, Object[] value, List<Object[]> remainingChunk) {
            this.chunkIndex = chunkIndex;
            this.value = value;
            this.remainingChunk = remainingChunk;
        }
    }
}