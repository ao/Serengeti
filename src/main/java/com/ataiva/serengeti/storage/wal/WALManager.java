package com.ataiva.serengeti.storage.wal;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * WALManager is responsible for managing Write-Ahead Logging operations.
 * It handles writing operations to the log before they are applied to the MemTable,
 * as well as recovery from the log during startup.
 */
public class WALManager implements AutoCloseable {
    
    private static final Logger LOGGER = Logger.getLogger(WALManager.class.getName());
    
    // WAL file constants
    private static final int MAGIC = 0x57414C4F; // "WALO" in ASCII (WAL Operation)
    private static final short VERSION = 1;
    private static final int HEADER_SIZE = 16; // Magic(4) + Version(2) + Flags(2) + Timestamp(8)
    
    // Operation types
    public static final byte OP_PUT = 1;
    public static final byte OP_DELETE = 2;
    
    // Sync modes
    public enum SyncMode {
        SYNC,       // Sync after every write
        ASYNC,      // Don't sync explicitly (rely on OS)
        GROUP       // Sync after a group of writes or time interval
    }
    
    // WAL configuration
    private final Path walDirectory;
    private final long maxWalSize;
    private final SyncMode syncMode;
    private final int groupCommitSize;
    private final long groupCommitIntervalMs;
    
    // Current WAL file state
    private Path currentWalPath;
    private FileChannel walChannel;
    private final AtomicLong sequenceNumber;
    private long currentWalSize;
    private int uncommittedWrites;
    private long lastSyncTime;
    
    // Checkpoint tracking
    private final Map<String, Long> checkpoints;
    
    /**
     * Creates a new WALManager with default settings.
     * 
     * @param walDirectory Directory to store WAL files
     * @throws IOException If an I/O error occurs
     */
    public WALManager(Path walDirectory) throws IOException {
        this(walDirectory, 64 * 1024 * 1024, SyncMode.GROUP, 100, 1000);
    }
    
    /**
     * Creates a new WALManager with custom settings.
     * 
     * @param walDirectory Directory to store WAL files
     * @param maxWalSize Maximum size of a WAL file before rotation
     * @param syncMode Mode for syncing WAL to disk
     * @param groupCommitSize Number of writes before syncing in GROUP mode
     * @param groupCommitIntervalMs Time interval for syncing in GROUP mode
     * @throws IOException If an I/O error occurs
     */
    public WALManager(Path walDirectory, long maxWalSize, SyncMode syncMode, 
                      int groupCommitSize, long groupCommitIntervalMs) throws IOException {
        this.walDirectory = walDirectory;
        this.maxWalSize = maxWalSize;
        this.syncMode = syncMode;
        this.groupCommitSize = groupCommitSize;
        this.groupCommitIntervalMs = groupCommitIntervalMs;
        
        this.sequenceNumber = new AtomicLong(0);
        this.uncommittedWrites = 0;
        this.lastSyncTime = System.currentTimeMillis();
        this.checkpoints = new HashMap<>();
        
        // Create WAL directory if it doesn't exist
        Files.createDirectories(walDirectory);
        
        // Initialize WAL file
        createNewWalFile();
    }
    
    /**
     * Logs a PUT operation to the WAL.
     * 
     * @param key The key as a byte array
     * @param value The value as a byte array
     * @return The sequence number assigned to this operation
     * @throws IOException If an I/O error occurs
     */
    public synchronized long logPut(byte[] key, byte[] value) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        
        // Check if we need to rotate the WAL file
        checkRotation();
        
        // Get next sequence number
        long seqNum = sequenceNumber.incrementAndGet();
        
        // Calculate record size
        int keyLength = key.length;
        int valueLength = value != null ? value.length : -1;
        int recordSize = 1 + 8 + 4 + 4 + keyLength + (valueLength > 0 ? valueLength : 0) + 4;
        // op_type(1) + seq_num(8) + key_len(4) + val_len(4) + key + value + crc(4)
        
        // Prepare record buffer
        ByteBuffer buffer = ByteBuffer.allocate(recordSize);
        buffer.put(OP_PUT);
        buffer.putLong(seqNum);
        buffer.putInt(keyLength);
        buffer.putInt(valueLength);
        buffer.put(key);
        if (valueLength > 0) {
            buffer.put(value);
        }
        
        // Calculate and add CRC
        CRC32 crc = new CRC32();
        buffer.flip();
        byte[] dataForCrc = new byte[buffer.limit() - 4]; // Exclude space for CRC
        buffer.get(dataForCrc);
        crc.update(dataForCrc);
        buffer.position(buffer.limit() - 4);
        buffer.putInt((int) crc.getValue());
        
        // Write to WAL
        buffer.flip();
        walChannel.write(buffer);
        currentWalSize += recordSize;
        uncommittedWrites++;
        
        // Handle syncing based on mode
        handleSync();
        
        return seqNum;
    }
    
    /**
     * Logs a DELETE operation to the WAL.
     * 
     * @param key The key as a byte array
     * @return The sequence number assigned to this operation
     * @throws IOException If an I/O error occurs
     */
    public synchronized long logDelete(byte[] key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        
        // Check if we need to rotate the WAL file
        checkRotation();
        
        // Get next sequence number
        long seqNum = sequenceNumber.incrementAndGet();
        
        // Calculate record size
        int keyLength = key.length;
        int recordSize = 1 + 8 + 4 + 4 + keyLength + 4;
        // op_type(1) + seq_num(8) + key_len(4) + val_len(4) + key + crc(4)
        
        // Prepare record buffer
        ByteBuffer buffer = ByteBuffer.allocate(recordSize);
        buffer.put(OP_DELETE);
        buffer.putLong(seqNum);
        buffer.putInt(keyLength);
        buffer.putInt(-1); // Value length for delete is -1
        buffer.put(key);
        
        // Calculate and add CRC
        CRC32 crc = new CRC32();
        buffer.flip();
        byte[] dataForCrc = new byte[buffer.limit() - 4]; // Exclude space for CRC
        buffer.get(dataForCrc);
        crc.update(dataForCrc);
        buffer.position(buffer.limit() - 4);
        buffer.putInt((int) crc.getValue());
        
        // Write to WAL
        buffer.flip();
        walChannel.write(buffer);
        currentWalSize += recordSize;
        uncommittedWrites++;
        
        // Handle syncing based on mode
        handleSync();
        
        return seqNum;
    }
    
    /**
     * Forces any changes made to the WAL to be written to disk.
     * 
     * @throws IOException If an I/O error occurs
     */
    public synchronized void sync() throws IOException {
        if (walChannel != null && walChannel.isOpen()) {
            walChannel.force(true);
            uncommittedWrites = 0;
            lastSyncTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Creates a checkpoint for the current state of the WAL.
     * This is used to track which WAL files can be safely deleted.
     * 
     * @param checkpointName A name for this checkpoint
     * @return The sequence number of this checkpoint
     */
    public synchronized long checkpoint(String checkpointName) {
        long currentSeq = sequenceNumber.get();
        checkpoints.put(checkpointName, currentSeq);
        LOGGER.info("Created checkpoint '" + checkpointName + "' at sequence " + currentSeq);
        return currentSeq;
    }
    
    /**
     * Removes a checkpoint, indicating that WAL entries up to this checkpoint
     * are no longer needed for recovery.
     * 
     * @param checkpointName The name of the checkpoint to remove
     * @return The sequence number of the removed checkpoint, or -1 if not found
     */
    public synchronized long removeCheckpoint(String checkpointName) {
        Long seqNum = checkpoints.remove(checkpointName);
        if (seqNum != null) {
            LOGGER.info("Removed checkpoint '" + checkpointName + "' at sequence " + seqNum);
            return seqNum;
        }
        return -1;
    }
    
    /**
     * Cleans up WAL files that are no longer needed for recovery.
     * This is called after a successful flush of a MemTable to an SSTable.
     * 
     * @param upToSequence The sequence number up to which WAL entries can be deleted
     * @throws IOException If an I/O error occurs
     */
    public synchronized void cleanupWAL(long upToSequence) throws IOException {
        // Find the minimum checkpoint sequence number
        long minCheckpoint = checkpoints.values().stream()
            .min(Long::compare)
            .orElse(Long.MAX_VALUE);
        
        // We can only delete WAL files up to the minimum of upToSequence and minCheckpoint
        long safeSequence = Math.min(upToSequence, minCheckpoint);
        
        // List all WAL files
        List<Path> walFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(walDirectory, "wal-*.log")) {
            for (Path path : stream) {
                walFiles.add(path);
            }
        }
        
        // Sort by creation time (which is encoded in the filename)
        walFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
        
        // Delete WAL files that are no longer needed
        for (Path walFile : walFiles) {
            // Extract sequence range from filename
            String filename = walFile.getFileName().toString();
            String[] parts = filename.replace("wal-", "").replace(".log", "").split("-");
            if (parts.length == 2) {
                try {
                    long endSeq = Long.parseLong(parts[1]);
                    if (endSeq <= safeSequence && !walFile.equals(currentWalPath)) {
                        Files.delete(walFile);
                        LOGGER.info("Deleted WAL file: " + filename);
                    }
                } catch (NumberFormatException e) {
                    LOGGER.warning("Invalid WAL filename format: " + filename);
                }
            }
        }
    }
    
    /**
     * Replays WAL files to recover the state of the MemTable after a crash.
     * 
     * @param consumer A function that processes each recovered operation
     * @throws IOException If an I/O error occurs
     */
    public void recover(WALRecoveryConsumer consumer) throws IOException {
        LOGGER.info("Starting WAL recovery");
        
        // List all WAL files
        List<Path> walFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(walDirectory, "wal-*.log")) {
            for (Path path : stream) {
                walFiles.add(path);
            }
        }
        
        // Sort by creation time (which is encoded in the filename)
        walFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
        
        // Track the highest sequence number seen
        long highestSeqNum = 0;
        int totalRecovered = 0;
        
        // Process each WAL file
        for (Path walFile : walFiles) {
            LOGGER.info("Recovering from WAL file: " + walFile.getFileName());
            
            try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.READ)) {
                // Read and verify header
                ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
                channel.read(headerBuffer, 0);
                headerBuffer.flip();
                
                int magic = headerBuffer.getInt();
                if (magic != MAGIC) {
                    LOGGER.warning("Invalid WAL file format in " + walFile.getFileName());
                    continue;
                }
                
                short version = headerBuffer.getShort();
                if (version > VERSION) {
                    LOGGER.warning("Unsupported WAL version in " + walFile.getFileName());
                    continue;
                }
                
                // Skip flags and timestamp for now
                headerBuffer.getShort(); // flags
                headerBuffer.getLong(); // timestamp
                
                // Read records
                long position = HEADER_SIZE;
                while (position < channel.size()) {
                    // Read record type and sequence number
                    ByteBuffer recordHeader = ByteBuffer.allocate(13); // op_type(1) + seq_num(8) + key_len(4)
                    channel.read(recordHeader, position);
                    recordHeader.flip();
                    
                    byte opType = recordHeader.get();
                    long seqNum = recordHeader.getLong();
                    int keyLength = recordHeader.getInt();
                    
                    // Update highest sequence number
                    highestSeqNum = Math.max(highestSeqNum, seqNum);
                    
                    // Read value length
                    ByteBuffer valueLengthBuffer = ByteBuffer.allocate(4);
                    channel.read(valueLengthBuffer, position + 13);
                    valueLengthBuffer.flip();
                    int valueLength = valueLengthBuffer.getInt();
                    
                    // Read key
                    ByteBuffer keyBuffer = ByteBuffer.allocate(keyLength);
                    channel.read(keyBuffer, position + 17);
                    keyBuffer.flip();
                    byte[] key = new byte[keyLength];
                    keyBuffer.get(key);
                    
                    // Read value if present
                    byte[] value = null;
                    if (valueLength > 0) {
                        ByteBuffer valueBuffer = ByteBuffer.allocate(valueLength);
                        channel.read(valueBuffer, position + 17 + keyLength);
                        valueBuffer.flip();
                        value = new byte[valueLength];
                        valueBuffer.get(value);
                    }
                    
                    // Read CRC
                    ByteBuffer crcBuffer = ByteBuffer.allocate(4);
                    channel.read(crcBuffer, position + 17 + keyLength + (valueLength > 0 ? valueLength : 0));
                    crcBuffer.flip();
                    int storedCrc = crcBuffer.getInt();
                    
                    // Verify CRC
                    CRC32 crc = new CRC32();
                    ByteBuffer dataForCrc = ByteBuffer.allocate(13 + 4 + keyLength + (valueLength > 0 ? valueLength : 0));
                    dataForCrc.put(opType);
                    dataForCrc.putLong(seqNum);
                    dataForCrc.putInt(keyLength);
                    dataForCrc.putInt(valueLength);
                    dataForCrc.put(key);
                    if (valueLength > 0) {
                        dataForCrc.put(value);
                    }
                    dataForCrc.flip();
                    byte[] dataBytes = new byte[dataForCrc.limit()];
                    dataForCrc.get(dataBytes);
                    crc.update(dataBytes);
                    
                    if (storedCrc != (int) crc.getValue()) {
                        LOGGER.warning("CRC mismatch in WAL record at position " + position);
                        // Skip to next record - in a production system, we might want to handle this more gracefully
                        position += 17 + keyLength + (valueLength > 0 ? valueLength : 0) + 4;
                        continue;
                    }
                    
                    // Process the record
                    switch (opType) {
                        case OP_PUT:
                            consumer.onPut(seqNum, key, value);
                            break;
                        case OP_DELETE:
                            consumer.onDelete(seqNum, key);
                            break;
                        default:
                            LOGGER.warning("Unknown operation type in WAL: " + opType);
                    }
                    
                    totalRecovered++;
                    
                    // Move to next record
                    position += 17 + keyLength + (valueLength > 0 ? valueLength : 0) + 4;
                }
            }
        }
        
        // Update sequence number to be higher than any recovered entry
        if (highestSeqNum > 0) {
            sequenceNumber.set(highestSeqNum);
        }
        
        LOGGER.info("WAL recovery completed. Recovered " + totalRecovered + " operations. " +
                   "Highest sequence number: " + highestSeqNum);
    }
    
    /**
     * Checks if WAL rotation is needed and performs it if necessary.
     * 
     * @throws IOException If an I/O error occurs
     */
    private synchronized void checkRotation() throws IOException {
        if (currentWalSize >= maxWalSize) {
            // Sync current WAL before rotation
            sync();
            
            // Close current WAL file
            if (walChannel != null && walChannel.isOpen()) {
                walChannel.close();
            }
            
            // Create new WAL file
            createNewWalFile();
        }
    }
    
    /**
     * Creates a new WAL file.
     * 
     * @throws IOException If an I/O error occurs
     */
    private synchronized void createNewWalFile() throws IOException {
        // Generate filename with timestamp and sequence range
        long timestamp = System.currentTimeMillis();
        long startSeq = sequenceNumber.get() + 1;
        String filename = String.format("wal-%d-%d.log", timestamp, startSeq);
        currentWalPath = walDirectory.resolve(filename);
        
        // Create file and open channel
        walChannel = FileChannel.open(currentWalPath, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE, 
                StandardOpenOption.TRUNCATE_EXISTING);
        
        // Write header
        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
        headerBuffer.putInt(MAGIC);
        headerBuffer.putShort(VERSION);
        headerBuffer.putShort((short) 0); // Flags
        headerBuffer.putLong(timestamp);
        headerBuffer.flip();
        walChannel.write(headerBuffer, 0);
        
        // Reset state
        currentWalSize = HEADER_SIZE;
        uncommittedWrites = 0;
        lastSyncTime = System.currentTimeMillis();
        
        LOGGER.info("Created new WAL file: " + filename);
    }
    
    /**
     * Handles syncing the WAL based on the configured sync mode.
     * 
     * @throws IOException If an I/O error occurs
     */
    private synchronized void handleSync() throws IOException {
        switch (syncMode) {
            case SYNC:
                // Sync after every write
                sync();
                break;
                
            case GROUP:
                // Sync after a group of writes or time interval
                long currentTime = System.currentTimeMillis();
                if (uncommittedWrites >= groupCommitSize || 
                    (currentTime - lastSyncTime) >= groupCommitIntervalMs) {
                    sync();
                }
                break;
                
            case ASYNC:
                // Don't sync explicitly
                break;
        }
    }
    
    /**
     * Closes the WAL manager, ensuring all data is synced to disk.
     * 
     * @throws IOException If an I/O error occurs
     */
    @Override
    public synchronized void close() throws IOException {
        if (walChannel != null && walChannel.isOpen()) {
            sync();
            walChannel.close();
            walChannel = null;
        }
    }
    
    /**
     * Interface for consuming recovered WAL operations.
     */
    public interface WALRecoveryConsumer {
        /**
         * Called for each PUT operation recovered from the WAL.
         * 
         * @param sequenceNumber The sequence number of the operation
         * @param key The key as a byte array
         * @param value The value as a byte array
         */
        void onPut(long sequenceNumber, byte[] key, byte[] value);
        
        /**
         * Called for each DELETE operation recovered from the WAL.
         * 
         * @param sequenceNumber The sequence number of the operation
         * @param key The key as a byte array
         */
        void onDelete(long sequenceNumber, byte[] key);
    }
}