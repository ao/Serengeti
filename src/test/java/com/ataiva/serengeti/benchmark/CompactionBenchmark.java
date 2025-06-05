package com.ataiva.serengeti.benchmark;

import com.ataiva.serengeti.storage.lsm.LSMStorageEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark to measure the performance impact of compaction in the LSM storage engine.
 * This benchmark compares read and write performance before and after compaction.
 */
public class CompactionBenchmark {
    
    private static final int NUM_KEYS = 100_000;
    private static final int KEY_SIZE = 16;
    private static final int VALUE_SIZE = 100;
    private static final int NUM_OPERATIONS = 10_000;
    
    private Path tempDir;
    private LSMStorageEngine engine;
    private Random random = new Random();
    private byte[][] keys;
    
    /**
     * Main method to run the benchmark.
     */
    public static void main(String[] args) {
        CompactionBenchmark benchmark = new CompactionBenchmark();
        try {
            benchmark.setup();
            benchmark.runBenchmark();
            benchmark.cleanup();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Sets up the benchmark environment.
     */
    private void setup() throws IOException {
        System.out.println("Setting up benchmark environment...");
        
        // Create a temporary directory
        tempDir = Files.createTempDirectory("lsm-compaction-benchmark");
        
        // Create an LSM storage engine with specific compaction settings
        engine = new LSMStorageEngine(
            tempDir,
            10 * 1024 * 1024, // 10MB memtable size
            4, // Max immutable memtables
            10, // Compaction threshold
            4, // Max SSTables to merge
            60000 // Compaction interval (1 minute)
        );
        
        // Generate random keys
        System.out.println("Generating " + NUM_KEYS + " random keys...");
        keys = new byte[NUM_KEYS][];
        for (int i = 0; i < NUM_KEYS; i++) {
            keys[i] = generateRandomKey(KEY_SIZE);
        }
        
        System.out.println("Setup complete.");
    }
    
    /**
     * Runs the benchmark.
     */
    private void runBenchmark() throws IOException, InterruptedException {
        System.out.println("\nRunning benchmark...");
        
        // Phase 1: Insert data to create multiple SSTables
        System.out.println("\nPhase 1: Inserting data to create multiple SSTables...");
        long insertStart = System.nanoTime();
        
        for (int i = 0; i < NUM_KEYS; i++) {
            byte[] value = generateRandomValue(VALUE_SIZE);
            engine.put(keys[i], value);
            
            // Force flush every 10,000 keys to create multiple SSTables
            if (i > 0 && i % 10000 == 0) {
                byte[] largeValue = new byte[15 * 1024 * 1024]; // 15MB (larger than memtable size)
                Arrays.fill(largeValue, (byte) 'X');
                engine.put(("large-key-" + i).getBytes(StandardCharsets.UTF_8), largeValue);
                System.out.println("  Inserted " + i + " keys, forced flush");
            }
        }
        
        long insertEnd = System.nanoTime();
        double insertTimeSeconds = (insertEnd - insertStart) / 1_000_000_000.0;
        System.out.println("Insert time: " + insertTimeSeconds + " seconds");
        System.out.println("Insert throughput: " + (NUM_KEYS / insertTimeSeconds) + " ops/sec");
        
        // Phase 2: Measure read performance before compaction
        System.out.println("\nPhase 2: Measuring read performance before compaction...");
        long readBeforeStart = System.nanoTime();
        
        for (int i = 0; i < NUM_OPERATIONS; i++) {
            int keyIndex = random.nextInt(NUM_KEYS);
            byte[] value = engine.get(keys[keyIndex]);
            if (i > 0 && i % 1000 == 0) {
                System.out.println("  Completed " + i + " reads");
            }
        }
        
        long readBeforeEnd = System.nanoTime();
        double readBeforeTimeSeconds = (readBeforeEnd - readBeforeStart) / 1_000_000_000.0;
        System.out.println("Read time before compaction: " + readBeforeTimeSeconds + " seconds");
        System.out.println("Read throughput before compaction: " + (NUM_OPERATIONS / readBeforeTimeSeconds) + " ops/sec");
        
        // Count SSTable files before compaction
        int ssTableCountBefore = countSSTableFiles();
        System.out.println("\nSSTable count before compaction: " + ssTableCountBefore);
        
        // Phase 3: Trigger compaction and wait for it to complete
        System.out.println("\nPhase 3: Triggering compaction...");
        engine.triggerCompactionCheck();
        
        // Wait for compaction to complete
        System.out.println("Waiting for compaction to complete...");
        Thread.sleep(10000); // Wait 10 seconds
        
        // Count SSTable files after compaction
        int ssTableCountAfter = countSSTableFiles();
        System.out.println("SSTable count after compaction: " + ssTableCountAfter);
        
        // Phase 4: Measure read performance after compaction
        System.out.println("\nPhase 4: Measuring read performance after compaction...");
        long readAfterStart = System.nanoTime();
        
        for (int i = 0; i < NUM_OPERATIONS; i++) {
            int keyIndex = random.nextInt(NUM_KEYS);
            byte[] value = engine.get(keys[keyIndex]);
            if (i > 0 && i % 1000 == 0) {
                System.out.println("  Completed " + i + " reads");
            }
        }
        
        long readAfterEnd = System.nanoTime();
        double readAfterTimeSeconds = (readAfterEnd - readAfterStart) / 1_000_000_000.0;
        System.out.println("Read time after compaction: " + readAfterTimeSeconds + " seconds");
        System.out.println("Read throughput after compaction: " + (NUM_OPERATIONS / readAfterTimeSeconds) + " ops/sec");
        
        // Phase 5: Measure write performance after compaction
        System.out.println("\nPhase 5: Measuring write performance after compaction...");
        long writeAfterStart = System.nanoTime();
        
        for (int i = 0; i < NUM_OPERATIONS; i++) {
            int keyIndex = random.nextInt(NUM_KEYS);
            byte[] value = generateRandomValue(VALUE_SIZE);
            engine.put(keys[keyIndex], value);
            if (i > 0 && i % 1000 == 0) {
                System.out.println("  Completed " + i + " writes");
            }
        }
        
        long writeAfterEnd = System.nanoTime();
        double writeAfterTimeSeconds = (writeAfterEnd - writeAfterStart) / 1_000_000_000.0;
        System.out.println("Write time after compaction: " + writeAfterTimeSeconds + " seconds");
        System.out.println("Write throughput after compaction: " + (NUM_OPERATIONS / writeAfterTimeSeconds) + " ops/sec");
        
        // Summary
        System.out.println("\nBenchmark Summary:");
        System.out.println("------------------");
        System.out.println("SSTable count before compaction: " + ssTableCountBefore);
        System.out.println("SSTable count after compaction: " + ssTableCountAfter);
        System.out.println("Read throughput before compaction: " + (NUM_OPERATIONS / readBeforeTimeSeconds) + " ops/sec");
        System.out.println("Read throughput after compaction: " + (NUM_OPERATIONS / readAfterTimeSeconds) + " ops/sec");
        System.out.println("Read performance improvement: " + 
                          String.format("%.2f%%", (readBeforeTimeSeconds / readAfterTimeSeconds - 1) * 100));
        System.out.println("Write throughput after compaction: " + (NUM_OPERATIONS / writeAfterTimeSeconds) + " ops/sec");
    }
    
    /**
     * Cleans up the benchmark environment.
     */
    private void cleanup() throws IOException {
        System.out.println("\nCleaning up...");
        
        // Close the engine
        if (engine != null) {
            engine.close();
        }
        
        // Delete the temporary directory
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("Failed to delete: " + path);
                    }
                });
        }
        
        System.out.println("Cleanup complete.");
    }
    
    /**
     * Generates a random key of the specified size.
     */
    private byte[] generateRandomKey(int size) {
        byte[] key = new byte[size];
        random.nextBytes(key);
        return key;
    }
    
    /**
     * Generates a random value of the specified size.
     */
    private byte[] generateRandomValue(int size) {
        byte[] value = new byte[size];
        random.nextBytes(value);
        return value;
    }
    
    /**
     * Counts the number of SSTable files in the temporary directory.
     */
    private int countSSTableFiles() throws IOException {
        return (int) Files.list(tempDir)
            .filter(path -> path.toString().endsWith(".db"))
            .count();
    }
}