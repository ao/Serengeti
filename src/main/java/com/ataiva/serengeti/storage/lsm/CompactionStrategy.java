package com.ataiva.serengeti.storage.lsm;

import com.ataiva.serengeti.performance.PerformanceProfiler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * CompactionStrategy implements advanced compaction strategies for LSM trees
 * to optimize write amplification, space amplification, and read performance.
 * This class supports multiple compaction strategies including:
 * - Size-tiered compaction
 * - Leveled compaction
 * - Hybrid compaction
 */
public class CompactionStrategy {
    private static final Logger LOGGER = Logger.getLogger(CompactionStrategy.class.getName());
    
    private final PerformanceProfiler profiler;
    private CompactionType compactionType;
    private int levelCount;
    private int sizeRatio;
    private double compactionThreshold;
    private AtomicBoolean compactionInProgress;
    
    /**
     * Creates a new CompactionStrategy with the specified type
     * 
     * @param compactionType The type of compaction strategy to use
     */
    public CompactionStrategy(CompactionType compactionType) {
        this.profiler = PerformanceProfiler.getInstance();
        this.compactionType = compactionType;
        this.levelCount = 7; // Default level count for leveled compaction
        this.sizeRatio = 10; // Default size ratio between levels
        this.compactionThreshold = 0.75; // Default threshold to trigger compaction
        this.compactionInProgress = new AtomicBoolean(false);
    }
    
    /**
     * Determines if compaction should be triggered based on the current state
     * 
     * @param sstables List of SSTables in the system
     * @return true if compaction should be triggered, false otherwise
     */
    public boolean shouldCompact(List<SSTable> sstables) {
        String timerId = profiler.startTimer("storage", "compaction_check");
        
        try {
            if (compactionInProgress.get()) {
                return false; // Don't trigger another compaction if one is already in progress
            }
            
            switch (compactionType) {
                case SIZE_TIERED:
                    return shouldCompactSizeTiered(sstables);
                case LEVELED:
                    return shouldCompactLeveled(sstables);
                case HYBRID:
                    return shouldCompactHybrid(sstables);
                default:
                    return false;
            }
        } finally {
            profiler.stopTimer(timerId, "storage.compaction.check_time");
        }
    }
    
    /**
     * Performs compaction on the given SSTables
     * 
     * @param sstables List of SSTables to compact
     * @return List of compacted SSTables
     */
    public List<SSTable> compact(List<SSTable> sstables) {
        if (!compactionInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Compaction already in progress");
        }
        
        String timerId = profiler.startTimer("storage", "compaction");
        
        try {
            switch (compactionType) {
                case SIZE_TIERED:
                    return compactSizeTiered(sstables);
                case LEVELED:
                    return compactLeveled(sstables);
                case HYBRID:
                    return compactHybrid(sstables);
                default:
                    return sstables;
            }
        } finally {
            compactionInProgress.set(false);
            profiler.stopTimer(timerId, "storage.compaction.time");
        }
    }
    
    /**
     * Checks if size-tiered compaction should be triggered
     * 
     * @param sstables List of SSTables
     * @return true if compaction should be triggered
     */
    private boolean shouldCompactSizeTiered(List<SSTable> sstables) {
        // Group SSTables by size tier
        Map<Long, List<SSTable>> sizeGroups = groupBySize(sstables);
        
        // Check if any size group has enough SSTables to trigger compaction
        for (Map.Entry<Long, List<SSTable>> entry : sizeGroups.entrySet()) {
            if (entry.getValue().size() >= 4) { // Trigger compaction when 4 or more SSTables are in the same size tier
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Performs size-tiered compaction
     * 
     * @param sstables List of SSTables to compact
     * @return List of compacted SSTables
     */
    private List<SSTable> compactSizeTiered(List<SSTable> sstables) {
        LOGGER.info("Starting size-tiered compaction");
        
        // Group SSTables by size tier
        Map<Long, List<SSTable>> sizeGroups = groupBySize(sstables);
        
        List<SSTable> result = new ArrayList<>();
        List<SSTable> toCompact = null;
        
        // Find the largest group that needs compaction
        for (Map.Entry<Long, List<SSTable>> entry : sizeGroups.entrySet()) {
            if (entry.getValue().size() >= 4) {
                if (toCompact == null || entry.getValue().size() > toCompact.size()) {
                    toCompact = entry.getValue();
                }
            }
        }
        
        // If we found a group to compact, do it
        if (toCompact != null) {
            LOGGER.info("Compacting " + toCompact.size() + " SSTables in size tier");
            
            // Remove the SSTables we're compacting from the input list
            List<SSTable> remaining = new ArrayList<>(sstables);
            remaining.removeAll(toCompact);
            
            // Create a new SSTable from the merged data
            SSTable compacted = mergeSSTableData(toCompact);
            
            // Add the compacted SSTable and the remaining ones to the result
            result.add(compacted);
            result.addAll(remaining);
            
            LOGGER.info("Size-tiered compaction complete. Reduced " + toCompact.size() + 
                       " SSTables to 1. Total SSTables: " + result.size());
        } else {
            result = sstables;
        }
        
        return result;
    }
    
    /**
     * Checks if leveled compaction should be triggered
     * 
     * @param sstables List of SSTables
     * @return true if compaction should be triggered
     */
    private boolean shouldCompactLeveled(List<SSTable> sstables) {
        // Group SSTables by level
        Map<Integer, List<SSTable>> levelGroups = groupByLevel(sstables);
        
        // Check if any level exceeds its size limit
        for (int level = 0; level < levelCount; level++) {
            List<SSTable> levelSSTables = levelGroups.getOrDefault(level, new ArrayList<>());
            long levelSize = calculateTotalSize(levelSSTables);
            long levelSizeLimit = calculateLevelSizeLimit(level);
            
            if (levelSize > levelSizeLimit) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Performs leveled compaction
     * 
     * @param sstables List of SSTables to compact
     * @return List of compacted SSTables
     */
    private List<SSTable> compactLeveled(List<SSTable> sstables) {
        LOGGER.info("Starting leveled compaction");
        
        // Group SSTables by level
        Map<Integer, List<SSTable>> levelGroups = groupByLevel(sstables);
        
        List<SSTable> result = new ArrayList<>();
        
        // Find the first level that exceeds its size limit
        int levelToCompact = -1;
        for (int level = 0; level < levelCount; level++) {
            List<SSTable> levelSSTables = levelGroups.getOrDefault(level, new ArrayList<>());
            long levelSize = calculateTotalSize(levelSSTables);
            long levelSizeLimit = calculateLevelSizeLimit(level);
            
            if (levelSize > levelSizeLimit) {
                levelToCompact = level;
                break;
            }
        }
        
        // If we found a level to compact, do it
        if (levelToCompact >= 0) {
            LOGGER.info("Compacting level " + levelToCompact);
            
            List<SSTable> levelSSTables = levelGroups.getOrDefault(levelToCompact, new ArrayList<>());
            List<SSTable> nextLevelSSTables = levelGroups.getOrDefault(levelToCompact + 1, new ArrayList<>());
            
            // Select SSTables to compact from the current level
            List<SSTable> toCompact = selectSSTablesToCompact(levelSSTables);
            
            // Find overlapping SSTables in the next level
            List<SSTable> overlappingSSTables = findOverlappingSSTables(toCompact, nextLevelSSTables);
            
            // Combine SSTables to compact
            List<SSTable> allToCompact = new ArrayList<>(toCompact);
            allToCompact.addAll(overlappingSSTables);
            
            // Remove the SSTables we're compacting from the input list
            List<SSTable> remaining = new ArrayList<>(sstables);
            remaining.removeAll(allToCompact);
            
            // Create new SSTables for the next level
            List<SSTable> compacted = createNextLevelSSTables(allToCompact, levelToCompact + 1);
            
            // Add the compacted SSTables and the remaining ones to the result
            result.addAll(compacted);
            result.addAll(remaining);
            
            LOGGER.info("Leveled compaction complete. Compacted " + allToCompact.size() + 
                       " SSTables into " + compacted.size() + ". Total SSTables: " + result.size());
        } else {
            result = sstables;
        }
        
        return result;
    }
    
    /**
     * Checks if hybrid compaction should be triggered
     * 
     * @param sstables List of SSTables
     * @return true if compaction should be triggered
     */
    private boolean shouldCompactHybrid(List<SSTable> sstables) {
        // For hybrid compaction, use size-tiered for L0 and leveled for L1+
        Map<Integer, List<SSTable>> levelGroups = groupByLevel(sstables);
        
        // Check L0 using size-tiered strategy
        List<SSTable> l0SSTables = levelGroups.getOrDefault(0, new ArrayList<>());
        if (l0SSTables.size() >= 4) {
            return true;
        }
        
        // Check other levels using leveled strategy
        for (int level = 1; level < levelCount; level++) {
            List<SSTable> levelSSTables = levelGroups.getOrDefault(level, new ArrayList<>());
            long levelSize = calculateTotalSize(levelSSTables);
            long levelSizeLimit = calculateLevelSizeLimit(level);
            
            if (levelSize > levelSizeLimit) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Performs hybrid compaction
     * 
     * @param sstables List of SSTables to compact
     * @return List of compacted SSTables
     */
    private List<SSTable> compactHybrid(List<SSTable> sstables) {
        LOGGER.info("Starting hybrid compaction");
        
        // Group SSTables by level
        Map<Integer, List<SSTable>> levelGroups = groupByLevel(sstables);
        
        // Check L0 using size-tiered strategy
        List<SSTable> l0SSTables = levelGroups.getOrDefault(0, new ArrayList<>());
        if (l0SSTables.size() >= 4) {
            // Compact L0 using size-tiered strategy and promote to L1
            LOGGER.info("Compacting L0 using size-tiered strategy");
            
            // Remove L0 SSTables from the input list
            List<SSTable> remaining = new ArrayList<>(sstables);
            remaining.removeAll(l0SSTables);
            
            // Create a new SSTable for L1
            SSTable compacted = mergeSSTableData(l0SSTables);
            compacted.setLevel(1);
            
            // Add the compacted SSTable and the remaining ones to the result
            List<SSTable> result = new ArrayList<>();
            result.add(compacted);
            result.addAll(remaining);
            
            LOGGER.info("Hybrid compaction of L0 complete. Reduced " + l0SSTables.size() + 
                       " SSTables to 1. Total SSTables: " + result.size());
            
            return result;
        }
        
        // Check other levels using leveled strategy
        for (int level = 1; level < levelCount; level++) {
            List<SSTable> levelSSTables = levelGroups.getOrDefault(level, new ArrayList<>());
            long levelSize = calculateTotalSize(levelSSTables);
            long levelSizeLimit = calculateLevelSizeLimit(level);
            
            if (levelSize > levelSizeLimit) {
                // Compact this level using leveled strategy
                LOGGER.info("Compacting L" + level + " using leveled strategy");
                
                List<SSTable> nextLevelSSTables = levelGroups.getOrDefault(level + 1, new ArrayList<>());
                
                // Select SSTables to compact from the current level
                List<SSTable> toCompact = selectSSTablesToCompact(levelSSTables);
                
                // Find overlapping SSTables in the next level
                List<SSTable> overlappingSSTables = findOverlappingSSTables(toCompact, nextLevelSSTables);
                
                // Combine SSTables to compact
                List<SSTable> allToCompact = new ArrayList<>(toCompact);
                allToCompact.addAll(overlappingSSTables);
                
                // Remove the SSTables we're compacting from the input list
                List<SSTable> remaining = new ArrayList<>(sstables);
                remaining.removeAll(allToCompact);
                
                // Create new SSTables for the next level
                List<SSTable> compacted = createNextLevelSSTables(allToCompact, level + 1);
                
                // Add the compacted SSTables and the remaining ones to the result
                List<SSTable> result = new ArrayList<>();
                result.addAll(compacted);
                result.addAll(remaining);
                
                LOGGER.info("Hybrid compaction of L" + level + " complete. Compacted " + 
                           allToCompact.size() + " SSTables into " + compacted.size() + 
                           ". Total SSTables: " + result.size());
                
                return result;
            }
        }
        
        // No compaction needed
        return sstables;
    }
    
    /**
     * Groups SSTables by size tier
     * 
     * @param sstables List of SSTables
     * @return Map of size tier to list of SSTables
     */
    private Map<Long, List<SSTable>> groupBySize(List<SSTable> sstables) {
        Map<Long, List<SSTable>> sizeGroups = new HashMap<>();
        
        for (SSTable sstable : sstables) {
            // Round size to the nearest power of 2 to create size tiers
            long size = sstable.getSize();
            long tier = Long.highestOneBit(size);
            
            sizeGroups.computeIfAbsent(tier, k -> new ArrayList<>()).add(sstable);
        }
        
        return sizeGroups;
    }
    
    /**
     * Groups SSTables by level
     * 
     * @param sstables List of SSTables
     * @return Map of level to list of SSTables
     */
    private Map<Integer, List<SSTable>> groupByLevel(List<SSTable> sstables) {
        Map<Integer, List<SSTable>> levelGroups = new HashMap<>();
        
        for (SSTable sstable : sstables) {
            int level = sstable.getLevel();
            levelGroups.computeIfAbsent(level, k -> new ArrayList<>()).add(sstable);
        }
        
        return levelGroups;
    }
    
    /**
     * Calculates the total size of a list of SSTables
     * 
     * @param sstables List of SSTables
     * @return Total size in bytes
     */
    private long calculateTotalSize(List<SSTable> sstables) {
        long totalSize = 0;
        
        for (SSTable sstable : sstables) {
            totalSize += sstable.getSize();
        }
        
        return totalSize;
    }
    
    /**
     * Calculates the size limit for a level
     * 
     * @param level Level number
     * @return Size limit in bytes
     */
    private long calculateLevelSizeLimit(int level) {
        if (level == 0) {
            return 10 * 1024 * 1024; // 10 MB for L0
        } else {
            // Each level is sizeRatio times larger than the previous level
            return 10 * 1024 * 1024 * (long)Math.pow(sizeRatio, level);
        }
    }
    
    /**
     * Selects SSTables to compact from a level
     * 
     * @param levelSSTables List of SSTables in the level
     * @return List of SSTables to compact
     */
    private List<SSTable> selectSSTablesToCompact(List<SSTable> levelSSTables) {
        // For simplicity, select all SSTables in the level
        // In a real implementation, we would select a subset based on various criteria
        return new ArrayList<>(levelSSTables);
    }
    
    /**
     * Finds SSTables in the next level that overlap with the given SSTables
     * 
     * @param sstables SSTables to check for overlap
     * @param nextLevelSSTables SSTables in the next level
     * @return List of overlapping SSTables
     */
    private List<SSTable> findOverlappingSSTables(List<SSTable> sstables, List<SSTable> nextLevelSSTables) {
        List<SSTable> overlapping = new ArrayList<>();
        
        // Find the key range of the SSTables to compact
        String minKey = null;
        String maxKey = null;
        
        for (SSTable sstable : sstables) {
            if (minKey == null || sstable.getMinKey().compareTo(minKey) < 0) {
                minKey = sstable.getMinKey();
            }
            
            if (maxKey == null || sstable.getMaxKey().compareTo(maxKey) > 0) {
                maxKey = sstable.getMaxKey();
            }
        }
        
        // Find overlapping SSTables in the next level
        for (SSTable sstable : nextLevelSSTables) {
            if (sstable.getMaxKey().compareTo(minKey) >= 0 && sstable.getMinKey().compareTo(maxKey) <= 0) {
                overlapping.add(sstable);
            }
        }
        
        return overlapping;
    }
    
    /**
     * Merges data from multiple SSTables into a new SSTable
     * 
     * @param sstables SSTables to merge
     * @return Merged SSTable
     */
    private SSTable mergeSSTableData(List<SSTable> sstables) {
        // In a real implementation, this would merge the actual data
        // For this example, we'll just create a new SSTable with the combined size
        
        long totalSize = calculateTotalSize(sstables);
        String minKey = null;
        String maxKey = null;
        
        for (SSTable sstable : sstables) {
            if (minKey == null || sstable.getMinKey().compareTo(minKey) < 0) {
                minKey = sstable.getMinKey();
            }
            
            if (maxKey == null || sstable.getMaxKey().compareTo(maxKey) > 0) {
                maxKey = sstable.getMaxKey();
            }
        }
        
        SSTable merged = new SSTable();
        merged.setSize(totalSize);
        merged.setMinKey(minKey);
        merged.setMaxKey(maxKey);
        merged.setLevel(sstables.get(0).getLevel());
        
        return merged;
    }
    
    /**
     * Creates new SSTables for the next level from merged data
     * 
     * @param sstables SSTables to merge
     * @param targetLevel Target level for the new SSTables
     * @return List of new SSTables
     */
    private List<SSTable> createNextLevelSSTables(List<SSTable> sstables, int targetLevel) {
        // In a real implementation, this would split the merged data into multiple SSTables
        // For this example, we'll just create a single new SSTable
        
        SSTable merged = mergeSSTableData(sstables);
        merged.setLevel(targetLevel);
        
        List<SSTable> result = new ArrayList<>();
        result.add(merged);
        
        return result;
    }
    
    /**
     * Sets the compaction type
     * 
     * @param compactionType New compaction type
     */
    public void setCompactionType(CompactionType compactionType) {
        this.compactionType = compactionType;
    }
    
    /**
     * Gets the current compaction type
     * 
     * @return Current compaction type
     */
    public CompactionType getCompactionType() {
        return compactionType;
    }
    
    /**
     * Sets the level count for leveled compaction
     * 
     * @param levelCount New level count
     */
    public void setLevelCount(int levelCount) {
        this.levelCount = levelCount;
    }
    
    /**
     * Gets the current level count
     * 
     * @return Current level count
     */
    public int getLevelCount() {
        return levelCount;
    }
    
    /**
     * Sets the size ratio between levels
     * 
     * @param sizeRatio New size ratio
     */
    public void setSizeRatio(int sizeRatio) {
        this.sizeRatio = sizeRatio;
    }
    
    /**
     * Gets the current size ratio
     * 
     * @return Current size ratio
     */
    public int getSizeRatio() {
        return sizeRatio;
    }
    
    /**
     * Sets the compaction threshold
     * 
     * @param compactionThreshold New compaction threshold
     */
    public void setCompactionThreshold(double compactionThreshold) {
        this.compactionThreshold = compactionThreshold;
    }
    
    /**
     * Gets the current compaction threshold
     * 
     * @return Current compaction threshold
     */
    public double getCompactionThreshold() {
        return compactionThreshold;
    }
    
    /**
     * Enum for compaction types
     */
    public enum CompactionType {
        SIZE_TIERED,
        LEVELED,
        HYBRID
    }
}