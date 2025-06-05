# Write-Ahead Logging (WAL) in Serengeti

## Overview

Write-Ahead Logging (WAL) is a technique used in database systems to ensure data durability and crash recovery. The basic principle is simple: before making any changes to the data, first log the changes to a separate log file (the WAL). This ensures that if the system crashes during a write operation, the changes can be recovered from the log when the system restarts.

In Serengeti, WAL has been implemented to enhance the reliability of the LSM (Log-Structured Merge-tree) storage engine. This document explains how the WAL system works, its configuration options, and how it integrates with the rest of the storage system.

## Architecture

The WAL system in Serengeti consists of the following components:

1. **WALManager**: The core component responsible for writing operations to the WAL, managing WAL files, and recovering from the WAL after a crash.

2. **LSMStorageEngine Integration**: The LSM storage engine has been modified to use the WAL before making any changes to the MemTable.

3. **LSMStorageScheduler Configuration**: The storage scheduler provides configuration options for the WAL system.

### WAL File Format

Each WAL file has the following structure:

1. **Header**: Contains a magic number, version, flags, and timestamp.
2. **Records**: Each record contains:
   - Operation type (PUT or DELETE)
   - Sequence number
   - Key length
   - Value length
   - Key data
   - Value data
   - CRC32 checksum

WAL files are named with a timestamp and sequence number (e.g., `wal-1622037600000-0001.log`).

## How It Works

### Writing to the WAL

1. When a `put` or `delete` operation is performed on the LSM storage engine, it first logs the operation to the WAL.
2. The WAL manager writes the operation to the current WAL file and assigns it a sequence number.
3. Depending on the sync mode, the WAL manager may force the data to be written to disk immediately.
4. Only after the operation has been successfully logged to the WAL, the LSM storage engine applies the operation to the MemTable.

### WAL Rotation

WAL files are rotated when they reach a configurable size limit. When a WAL file is rotated:

1. The current WAL file is synced to disk.
2. A new WAL file is created with a new timestamp and sequence number.
3. Subsequent operations are written to the new WAL file.

### Checkpoints and Cleanup

To prevent the WAL from growing indefinitely, the system uses checkpoints to track which WAL files are still needed for recovery:

1. When a MemTable is made immutable, a checkpoint is created in the WAL.
2. When the MemTable is successfully flushed to an SSTable, the checkpoint is removed.
3. WAL files that contain only operations up to the oldest remaining checkpoint can be safely deleted.

### Recovery Process

When the system starts up, it checks for existing WAL files. If WAL files are found, the recovery process is initiated:

1. WAL files are read in order of their creation time.
2. Each valid record is extracted and its checksum is verified.
3. The operations are applied to the MemTable in the same order they were originally performed.
4. After recovery is complete, normal operations resume.

## Configuration Options

The WAL system can be configured with the following options:

### Sync Modes

The WAL system supports three sync modes:

1. **SYNC**: Every write operation is immediately synced to disk. This provides the highest durability but may impact performance.
2. **ASYNC**: Write operations are not explicitly synced to disk. The operating system decides when to flush data to disk. This provides the best performance but may lose data in case of a crash.
3. **GROUP**: Write operations are synced to disk after a certain number of operations or a time interval, whichever comes first. This is a compromise between durability and performance.

### Size Limits

- **WAL Max Size**: The maximum size of a WAL file before rotation.
- **Group Commit Size**: The number of write operations before syncing in GROUP mode.
- **Group Commit Interval**: The time interval for syncing in GROUP mode.

## Integration with LSM Storage Engine

The LSM storage engine has been modified to use the WAL system:

1. The `LSMStorageEngine` constructor now initializes a `WALManager`.
2. The `put` and `delete` methods now log operations to the WAL before applying them to the MemTable.
3. During startup, the engine recovers from the WAL before loading existing SSTables.
4. When a MemTable is flushed to disk, the corresponding WAL checkpoint is removed and WAL files are cleaned up.

## Configuration in LSMStorageScheduler

The `LSMStorageScheduler` provides configuration options for the WAL system:

```java
// Create a storage scheduler with custom WAL settings
LSMStorageScheduler scheduler = new LSMStorageScheduler(
    10,                          // compactionTriggerThreshold
    4,                           // compactionMaxSSTablesToMerge
    60000,                       // compactionIntervalMs
    WALManager.SyncMode.GROUP,   // walSyncMode
    64 * 1024 * 1024,            // walMaxSize (64MB)
    100,                         // walGroupCommitSize
    1000                         // walGroupCommitIntervalMs
);
```

## Performance Considerations

The WAL system improves data durability but may impact write performance, depending on the sync mode:

- **SYNC mode**: Provides the highest durability but may significantly impact write performance.
- **ASYNC mode**: Provides the best write performance but may lose data in case of a crash.
- **GROUP mode**: Provides a good balance between durability and performance.

The WAL file size and group commit parameters can be tuned based on the specific workload and hardware:

- Larger WAL files reduce the frequency of file creation but may increase recovery time.
- Larger group commit sizes improve throughput but may increase the amount of data lost in case of a crash.
- Shorter group commit intervals improve durability but may reduce throughput.

## Testing

The WAL system includes comprehensive unit and integration tests:

- `WALManagerTest`: Tests the core WAL functionality, including writing, recovery, rotation, and cleanup.
- `WALRecoveryIntegrationTest`: Tests the integration with the LSM storage engine, including recovery after a simulated crash.

## Conclusion

The Write-Ahead Logging system significantly improves the reliability of the Serengeti storage system by ensuring that operations can be recovered after a crash. By configuring the sync mode and other parameters, users can balance durability and performance based on their specific requirements.