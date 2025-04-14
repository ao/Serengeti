package ms.ao.serengeti.storage.lsm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LSMStorageEngine is the main class that coordinates the LSM-Tree components.
 * It manages the active MemTable, immutable MemTables waiting to be flushed,
 * and SSTables on disk. It also handles compaction of SSTables.
 */
public class LSMStorageEngine implements AutoCloseable {
    
    private static final Logger LOGGER = Logger.getLogger(LSMStorageEngine.class.getName());
    
    // Configuration
    private final Path dataDirectory;
    private final long memTableMaxSize;
    private final int maxImmutableMemTables;
    
    // Active MemTable for writes
    private volatile MemTable activeMemTable;
    
    // Immutable MemTables waiting to be flushed to disk
    private final Queue<MemTable> immutableMemTables;
    
    // SSTables on disk
    private final List<SSTable> ssTables;
    
    // SSTable ID generator
    private final AtomicLong ssTableIdGenerator;
    
    // Background threads
    private final Thread flushThread;
    private final Thread compactionThread;
    
    // Control flags
    private volatile boolean running;
    
    /**
     * Creates a new LSMStorageEngine.
     * 
     * @param dataDirectory Directory to store SSTable files
     * @param memTableMaxSize Maximum size of a MemTable before flushing
     * @param maxImmutableMemTables Maximum number of immutable MemTables to keep in memory
     * @throws IOException If an I/O error occurs
     */
    public LSMStorageEngine(Path dataDirectory, long memTableMaxSize, int maxImmutableMemTables) throws IOException {
        this.dataDirectory = dataDirectory;
        this.memTableMaxSize = memTableMaxSize;
        this.maxImmutableMemTables = maxImmutableMemTables;
        
        // Create data directory if it doesn't exist
        Files.createDirectories(dataDirectory);
        
        // Initialize data structures
        this.activeMemTable = new MemTable(memTableMaxSize);
        this.immutableMemTables = new LinkedList<>();
        this.ssTables = new ArrayList<>();
        this.ssTableIdGenerator = new AtomicLong(System.currentTimeMillis());
        
        // Load existing SSTables
        loadExistingSSTables();
        
        // Start background threads
        this.running = true;
        this.flushThread = new Thread(this::flushLoop);
        this.flushThread.setName("lsm-flush-thread");
        this.flushThread.setDaemon(true);
        this.flushThread.start();
        
        this.compactionThread = new Thread(this::compactionLoop);
        this.compactionThread.setName("lsm-compaction-thread");
        this.compactionThread.setDaemon(true);
        this.compactionThread.start();
    }
    
    /**
     * Puts a key-value pair into the storage engine.
     * 
     * @param key The key as a byte array
     * @param value The value as a byte array
     * @throws IOException If an I/O error occurs
     */
    public synchronized void put(byte[] key, byte[] value) throws IOException {
        // Check if key is null
        if (key == null) {
            return;
        }
        
        // Put in active MemTable
        boolean shouldFlush = activeMemTable.put(key, value);
        
        // If MemTable is full, make it immutable and create a new active MemTable
        if (shouldFlush) {
            makeActiveMemTableImmutable();
        }
    }
    
    /**
     * Deletes a key from the storage engine.
     * 
     * @param key The key to delete
     * @throws IOException If an I/O error occurs
     */
    public synchronized void delete(byte[] key) throws IOException {
        // Check if key is null
        if (key == null) {
            return;
        }
        
        // Mark as deleted in active MemTable
        boolean shouldFlush = activeMemTable.delete(key);
        
        // If MemTable is full, make it immutable and create a new active MemTable
        if (shouldFlush) {
            makeActiveMemTableImmutable();
        }
    }
    
    /**
     * Gets the value for a given key.
     * 
     * @param key The key to look up
     * @return The value, or null if the key doesn't exist or has been deleted
     * @throws IOException If an I/O error occurs
     */
    public byte[] get(byte[] key) throws IOException {
        // Check if key is null
        if (key == null) {
            return null;
        }
        
        // Check active MemTable first
        byte[] value = activeMemTable.get(key);
        if (value != null) {
            return value;
        }
        
        // Check immutable MemTables
        synchronized (immutableMemTables) {
            for (MemTable memTable : immutableMemTables) {
                value = memTable.get(key);
                if (value != null) {
                    return value;
                }
            }
        }
        
        // Check SSTables from newest to oldest
        synchronized (ssTables) {
            for (int i = ssTables.size() - 1; i >= 0; i--) {
                SSTable ssTable = ssTables.get(i);
                if (ssTable.mightContain(key)) {
                    value = ssTable.get(key);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        
        // Key not found
        return null;
    }
    
    /**
     * Makes the active MemTable immutable and creates a new active MemTable.
     * 
     * @throws IOException If an I/O error occurs
     */
    private synchronized void makeActiveMemTableImmutable() throws IOException {
        // Add current active MemTable to immutable list
        synchronized (immutableMemTables) {
            immutableMemTables.add(activeMemTable);
        }
        
        // Create a new active MemTable
        activeMemTable = new MemTable(memTableMaxSize);
        
        // Notify flush thread
        synchronized (flushThread) {
            flushThread.notify();
        }
    }
    
    /**
     * Background thread that flushes immutable MemTables to disk as SSTables.
     */
    private void flushLoop() {
        while (running) {
            try {
                // Wait if no immutable MemTables
                synchronized (flushThread) {
                    while (running && immutableMemTables.isEmpty()) {
                        flushThread.wait();
                    }
                }
                
                if (!running) {
                    break;
                }
                
                // Get the oldest immutable MemTable
                MemTable memTableToFlush;
                synchronized (immutableMemTables) {
                    memTableToFlush = immutableMemTables.poll();
                }
                
                if (memTableToFlush != null && !memTableToFlush.isEmpty()) {
                    // Flush to disk
                    String fileId = String.format("%016x", ssTableIdGenerator.incrementAndGet());
                    SSTable ssTable = SSTable.create(memTableToFlush, dataDirectory, fileId);
                    
                    // Add to list of SSTables
                    synchronized (ssTables) {
                        ssTables.add(ssTable);
                    }
                    
                    LOGGER.info("Flushed MemTable to SSTable: " + fileId);
                    
                    // Notify compaction thread
                    synchronized (compactionThread) {
                        compactionThread.notify();
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error in flush thread", e);
            }
        }
    }
    
    /**
     * Background thread that compacts SSTables.
     */
    private void compactionLoop() {
        while (running) {
            try {
                // Wait for a while
                synchronized (compactionThread) {
                    compactionThread.wait(60000); // 1 minute
                }
                
                if (!running) {
                    break;
                }
                
                // Check if compaction is needed
                synchronized (ssTables) {
                    if (ssTables.size() >= 10) { // Simple threshold for now
                        // In a real implementation, we would use a more sophisticated compaction strategy
                        LOGGER.info("Compaction needed, but not implemented yet");
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error in compaction thread", e);
            }
        }
    }
    
    /**
     * Loads existing SSTables from the data directory.
     * 
     * @throws IOException If an I/O error occurs
     */
    private void loadExistingSSTables() throws IOException {
        // Find all SSTable files
        Files.list(dataDirectory)
            .filter(path -> path.toString().endsWith(".db"))
            .forEach(path -> {
                try {
                    // Open SSTable
                    SSTable ssTable = new SSTable(path);
                    
                    // Add to list
                    synchronized (ssTables) {
                        ssTables.add(ssTable);
                    }
                    
                    LOGGER.info("Loaded SSTable: " + path.getFileName());
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to load SSTable: " + path, e);
                }
            });
    }
    
    /**
     * Closes the storage engine, flushing any pending data and releasing resources.
     * 
     * @throws IOException If an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        // Stop background threads
        running = false;
        
        // Notify threads to wake up
        synchronized (flushThread) {
            flushThread.notify();
        }
        synchronized (compactionThread) {
            compactionThread.notify();
        }
        
        // Wait for threads to finish
        try {
            flushThread.join(5000);
            compactionThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        try {
            // Flush active MemTable if not empty
            if (!activeMemTable.isEmpty()) {
                try {
                    String fileId = String.format("%016x", ssTableIdGenerator.incrementAndGet());
                    SSTable.create(activeMemTable, dataDirectory, fileId);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to flush active MemTable", e);
                }
            }
            
            // Flush immutable MemTables
            synchronized (immutableMemTables) {
                for (MemTable memTable : immutableMemTables) {
                    if (!memTable.isEmpty()) {
                        try {
                            String fileId = String.format("%016x", ssTableIdGenerator.incrementAndGet());
                            SSTable.create(memTable, dataDirectory, fileId);
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to flush immutable MemTable", e);
                        }
                    }
                }
                immutableMemTables.clear();
            }
            
            // Close SSTables
            synchronized (ssTables) {
                for (SSTable ssTable : ssTables) {
                    try {
                        ssTable.close();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to close SSTable", e);
                    }
                }
                ssTables.clear();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during storage engine shutdown", e);
        }
    }
    
    /**
     * Simple demonstration of the LSM storage engine.
     */
    public static void main(String[] args) {
        try {
            // Create a temporary directory for the demo
            Path tempDir = Files.createTempDirectory("lsm-demo");
            
            // Create the storage engine
            try (LSMStorageEngine engine = new LSMStorageEngine(tempDir, 1024 * 1024, 2)) {
                System.out.println("LSM Storage Engine Demo");
                System.out.println("======================");
                System.out.println("Data directory: " + tempDir);
                
                // Insert some data
                System.out.println("\nInserting data...");
                for (int i = 1; i <= 10; i++) {
                    String key = "key" + i;
                    String value = "value" + i;
                    engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Inserted: " + key + " -> " + value);
                }
                
                // Read the data back
                System.out.println("\nReading data...");
                for (int i = 1; i <= 10; i++) {
                    String key = "key" + i;
                    byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Read: " + key + " -> " + new String(value, StandardCharsets.UTF_8));
                }
                
                // Update some data
                System.out.println("\nUpdating data...");
                for (int i = 1; i <= 5; i++) {
                    String key = "key" + i;
                    String value = "updated-value" + i;
                    engine.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Updated: " + key + " -> " + value);
                }
                
                // Delete some data
                System.out.println("\nDeleting data...");
                for (int i = 6; i <= 8; i++) {
                    String key = "key" + i;
                    engine.delete(key.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Deleted: " + key);
                }
                
                // Read the data again
                System.out.println("\nReading data after updates and deletes...");
                for (int i = 1; i <= 10; i++) {
                    String key = "key" + i;
                    byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
                    if (value != null) {
                        System.out.println("Read: " + key + " -> " + new String(value, StandardCharsets.UTF_8));
                    } else {
                        System.out.println("Read: " + key + " -> (deleted)");
                    }
                }
                
                // Force a flush by inserting a lot of data
                System.out.println("\nForcing flush by inserting a lot of data...");
                byte[] largeValue = new byte[100 * 1024]; // 100KB
                Arrays.fill(largeValue, (byte) 'X');
                for (int i = 1; i <= 20; i++) {
                    String key = "large-key" + i;
                    engine.put(key.getBytes(StandardCharsets.UTF_8), largeValue);
                    System.out.println("Inserted large value for: " + key);
                }
                
                // Wait a bit for background operations
                System.out.println("\nWaiting for background operations...");
                Thread.sleep(2000);
                
                // Read some of the large values
                System.out.println("\nReading large values...");
                for (int i = 1; i <= 5; i++) {
                    String key = "large-key" + i;
                    byte[] value = engine.get(key.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Read: " + key + " -> " + (value != null ? value.length + " bytes" : "null"));
                }
                
                System.out.println("\nDemo completed successfully!");
            }
            
            // Clean up the temporary directory
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("Failed to delete: " + path);
                    }
                });
        } catch (Exception e) {
            System.err.println("Error in demo: " + e.getMessage());
            e.printStackTrace();
        }
    }
}