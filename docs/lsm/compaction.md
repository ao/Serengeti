# LSM Storage Engine Compaction

This document describes the compaction functionality implemented in the LSM (Log-Structured Merge-tree) storage engine of the Serengeti database system.

## Overview

Compaction is a critical process in LSM-tree based storage systems that merges multiple SSTables (Sorted String Tables) into fewer, larger SSTables. This process is essential for maintaining performance and space efficiency as the database grows.

Without compaction, the number of SSTables would continuously increase, leading to:
- Degraded read performance (as more files need to be checked)
- Inefficient space usage (due to redundant or deleted data)
- Increased memory usage for maintaining file handles and metadata

## Compaction Strategy

The Serengeti LSM storage engine implements a size-tiered compaction strategy, which works as follows:

1. When the number of SSTables exceeds a configurable threshold (`compactionTriggerThreshold`), compaction is triggered
2. A subset of SSTables (up to `compactionMaxSSTablesToMerge`) is selected for compaction, typically the oldest ones
3. These SSTables are merged into a single new SSTable
4. The old SSTables are deleted after the new one is successfully created

During the merge process:
- For keys that appear in multiple SSTables, only the newest version is kept
- Tombstones (markers for deleted keys) are preserved only if they are the newest version of a key
- If a key has a tombstone and no newer version exists in any of the SSTables being compacted, the tombstone is kept to indicate deletion

## Configuration

The LSM storage engine's compaction behavior can be configured with the following parameters:

| Parameter | Description | Default Value |
|-----------|-------------|---------------|
| `compactionTriggerThreshold` | Number of SSTables that triggers compaction | 10 |
| `compactionMaxSSTablesToMerge` | Maximum number of SSTables to merge in one compaction | 4 |
| `compactionIntervalMs` | Time between compaction checks in milliseconds | 60000 (1 minute) |

These parameters can be set when creating an LSMStorageEngine instance:

```java
LSMStorageEngine engine = new LSMStorageEngine(
    dataDirectory,
    memTableMaxSize,
    maxImmutableMemTables,
    compactionTriggerThreshold,
    compactionMaxSSTablesToMerge,
    compactionIntervalMs
);
```

## Integration with StorageScheduler

The compaction process is integrated with the StorageScheduler through the LSMStorageScheduler class, which:

1. Periodically checks if compaction is needed for each LSM storage engine
2. Triggers compaction when necessary
3. Manages the lifecycle of LSM storage engines

The LSMStorageScheduler can be configured with the same compaction parameters:

```java
LSMStorageScheduler scheduler = new LSMStorageScheduler(
    compactionTriggerThreshold,
    compactionMaxSSTablesToMerge,
    compactionIntervalMs
);
```

## Performance Impact

Compaction has both positive and negative performance impacts:

### Positive Impacts
- **Improved read performance**: Fewer SSTables to check during reads
- **Reduced space usage**: Removal of redundant and deleted data
- **Better range query performance**: Larger, more efficient SSTables

### Negative Impacts
- **Temporary increased disk I/O**: During compaction, data is read and written
- **Potential write stalls**: If compaction cannot keep up with write load
- **CPU usage**: Merging SSTables requires CPU resources

The CompactionBenchmark class provides tools to measure these performance impacts in your specific environment.

## Monitoring and Tuning

To monitor compaction:
- Check logs for messages about compaction activities
- Monitor disk space usage before and after compaction
- Use the CompactionBenchmark to measure performance impact

To tune compaction:
- Increase `compactionTriggerThreshold` if compaction happens too frequently
- Decrease `compactionTriggerThreshold` if read performance degrades due to too many SSTables
- Adjust `compactionMaxSSTablesToMerge` based on available system resources
- Modify `compactionIntervalMs` to control how often compaction checks occur

## Future Improvements

Potential future improvements to the compaction strategy include:

1. **Leveled compaction**: Organizing SSTables into levels with different size ratios
2. **Tiered compaction with size ratio**: Selecting SSTables for compaction based on similar sizes
3. **Time-window compaction**: Compacting SSTables based on their age
4. **Compaction priority**: Prioritizing compaction of SSTables with high overlap
5. **Background compaction throttling**: Limiting compaction I/O to reduce impact on foreground operations

## Conclusion

Compaction is a critical component of the LSM storage engine that maintains performance and space efficiency. The implemented size-tiered compaction strategy provides a good balance of simplicity and effectiveness, with configurable parameters to adapt to different workloads and system resources.