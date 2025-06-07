package com.ataiva.serengeti.storage.io;

import com.ataiva.serengeti.performance.PerformanceProfiler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AsyncIOManager provides asynchronous and batch I/O operations for the storage engine.
 * It improves performance by:
 * 1. Using non-blocking asynchronous I/O operations
 * 2. Batching small writes together to reduce disk I/O overhead
 * 3. Prioritizing reads over writes for better read performance
 * 4. Providing background flush operations for durability
 */
public class AsyncIOManager {
    private static final Logger LOGGER = Logger.getLogger(AsyncIOManager.class.getName());
    private static final int DEFAULT_BATCH_SIZE = 64 * 1024; // 64KB default batch size
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 100; // 100ms default flush interval
    private static final int DEFAULT_IO_THREAD_COUNT = 4; // Default number of I/O threads
    
    private static AsyncIOManager instance;
    
    private final PerformanceProfiler profiler;
    private final ExecutorService ioExecutor;
    private final ScheduledExecutorService flushExecutor;
    private final Map<String, AsynchronousFileChannel> channelCache;
    private final Map<String, LinkedBlockingQueue<WriteOperation>> writeBatches;
    private final AtomicInteger pendingOperations;
    private final int batchSize;
    private final int flushIntervalMs;
    private boolean shutdownRequested;
    
    /**
     * Private constructor for singleton pattern
     */
    private AsyncIOManager() {
        this(DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS, DEFAULT_IO_THREAD_COUNT);
    }
    
    /**
     * Private constructor with custom settings
     * 
     * @param batchSize Size threshold for batching writes
     * @param flushIntervalMs Interval for flushing batched writes
     * @param ioThreadCount Number of I/O threads
     */
    private AsyncIOManager(int batchSize, int flushIntervalMs, int ioThreadCount) {
        this.profiler = PerformanceProfiler.getInstance();
        this.ioExecutor = Executors.newFixedThreadPool(ioThreadCount);
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor();
        this.channelCache = new ConcurrentHashMap<>();
        this.writeBatches = new ConcurrentHashMap<>();
        this.pendingOperations = new AtomicInteger(0);
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.shutdownRequested = false;
        
        // Start the scheduled flush task
        startScheduledFlush();
    }
    
    /**
     * Get the singleton instance of AsyncIOManager
     * 
     * @return AsyncIOManager instance
     */
    public static synchronized AsyncIOManager getInstance() {
        if (instance == null) {
            instance = new AsyncIOManager();
        }
        return instance;
    }
    
    /**
     * Get the singleton instance with custom settings
     * 
     * @param batchSize Size threshold for batching writes
     * @param flushIntervalMs Interval for flushing batched writes
     * @param ioThreadCount Number of I/O threads
     * @return AsyncIOManager instance
     */
    public static synchronized AsyncIOManager getInstance(int batchSize, int flushIntervalMs, int ioThreadCount) {
        if (instance == null) {
            instance = new AsyncIOManager(batchSize, flushIntervalMs, ioThreadCount);
        }
        return instance;
    }
    
    /**
     * Start the scheduled flush task
     */
    private void startScheduledFlush() {
        flushExecutor.scheduleAtFixedRate(() -> {
            try {
                flushAllBatches();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error during scheduled flush", e);
            }
        }, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Asynchronously read data from a file
     * 
     * @param filePath Path to the file
     * @param position Position in the file to read from
     * @param size Number of bytes to read
     * @param callback Callback to invoke when the read completes
     */
    public void readAsync(String filePath, long position, int size, IOCallback<ByteBuffer> callback) {
        String timerId = profiler.startTimer("storage", "async_read", filePath);
        pendingOperations.incrementAndGet();
        
        ioExecutor.submit(() -> {
            try {
                AsynchronousFileChannel channel = getOrCreateChannel(filePath, false);
                ByteBuffer buffer = ByteBuffer.allocate(size);
                
                channel.read(buffer, position, buffer, new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer result, ByteBuffer attachment) {
                        try {
                            attachment.flip();
                            callback.onSuccess(attachment);
                        } catch (Exception e) {
                            callback.onFailure(e);
                        } finally {
                            pendingOperations.decrementAndGet();
                            profiler.stopTimer(timerId, "storage.async_io.read_time");
                        }
                    }
                    
                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment) {
                        try {
                            callback.onFailure(exc);
                        } finally {
                            pendingOperations.decrementAndGet();
                            profiler.stopTimer(timerId, "storage.async_io.read_time");
                        }
                    }
                });
            } catch (Exception e) {
                callback.onFailure(e);
                pendingOperations.decrementAndGet();
                profiler.stopTimer(timerId, "storage.async_io.read_time");
            }
        });
    }
    
    /**
     * Asynchronously write data to a file
     * 
     * @param filePath Path to the file
     * @param position Position in the file to write to
     * @param data Data to write
     * @param callback Callback to invoke when the write completes
     */
    public void writeAsync(String filePath, long position, ByteBuffer data, IOCallback<Integer> callback) {
        String timerId = profiler.startTimer("storage", "async_write", filePath);
        pendingOperations.incrementAndGet();
        
        // Check if we should batch this write
        if (data.remaining() < batchSize / 4) { // Only batch small writes
            queueWriteOperation(filePath, position, data, callback, timerId);
            return;
        }
        
        // For larger writes, perform them directly
        ioExecutor.submit(() -> {
            try {
                AsynchronousFileChannel channel = getOrCreateChannel(filePath, true);
                
                channel.write(data, position, null, new CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer result, Void attachment) {
                        try {
                            callback.onSuccess(result);
                        } catch (Exception e) {
                            callback.onFailure(e);
                        } finally {
                            pendingOperations.decrementAndGet();
                            profiler.stopTimer(timerId, "storage.async_io.write_time");
                        }
                    }
                    
                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        try {
                            callback.onFailure(exc);
                        } finally {
                            pendingOperations.decrementAndGet();
                            profiler.stopTimer(timerId, "storage.async_io.write_time");
                        }
                    }
                });
            } catch (Exception e) {
                callback.onFailure(e);
                pendingOperations.decrementAndGet();
                profiler.stopTimer(timerId, "storage.async_io.write_time");
            }
        });
    }
    
    /**
     * Queue a write operation for batching
     */
    private void queueWriteOperation(String filePath, long position, ByteBuffer data, IOCallback<Integer> callback, String timerId) {
        WriteOperation op = new WriteOperation(position, data.duplicate(), callback, timerId);
        
        writeBatches.computeIfAbsent(filePath, k -> new LinkedBlockingQueue<>()).add(op);
        
        // If the batch size exceeds the threshold, flush it
        if (getBatchSize(filePath) >= batchSize) {
            flushBatch(filePath);
        }
    }
    
    /**
     * Get the current size of a write batch
     * 
     * @param filePath Path to the file
     * @return Current batch size in bytes
     */
    private int getBatchSize(String filePath) {
        LinkedBlockingQueue<WriteOperation> batch = writeBatches.get(filePath);
        if (batch == null) {
            return 0;
        }
        
        int totalSize = 0;
        for (WriteOperation op : batch) {
            totalSize += op.data.remaining();
        }
        
        return totalSize;
    }
    
    /**
     * Flush a specific write batch
     * 
     * @param filePath Path to the file
     */
    public void flushBatch(String filePath) {
        LinkedBlockingQueue<WriteOperation> batch = writeBatches.get(filePath);
        if (batch == null || batch.isEmpty()) {
            return;
        }
        
        // Create a list of operations to flush
        List<WriteOperation> operations = new ArrayList<>();
        batch.drainTo(operations);
        
        if (operations.isEmpty()) {
            return;
        }
        
        String timerId = profiler.startTimer("storage", "batch_flush", filePath);
        
        ioExecutor.submit(() -> {
            try {
                AsynchronousFileChannel channel = getOrCreateChannel(filePath, true);
                
                // Process each operation in the batch
                for (WriteOperation op : operations) {
                    try {
                        // Perform the write synchronously within this thread
                        Future<Integer> future = channel.write(op.data, op.position);
                        int bytesWritten = future.get(); // Wait for completion
                        
                        // Notify success
                        op.callback.onSuccess(bytesWritten);
                    } catch (Exception e) {
                        // Notify failure
                        op.callback.onFailure(e);
                    } finally {
                        pendingOperations.decrementAndGet();
                        profiler.stopTimer(op.timerId, "storage.async_io.write_time");
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error flushing batch for " + filePath, e);
                
                // Notify all callbacks of failure
                for (WriteOperation op : operations) {
                    try {
                        op.callback.onFailure(e);
                    } finally {
                        pendingOperations.decrementAndGet();
                        profiler.stopTimer(op.timerId, "storage.async_io.write_time");
                    }
                }
            } finally {
                profiler.stopTimer(timerId, "storage.async_io.batch_flush_time");
            }
        });
    }
    
    /**
     * Flush all write batches
     */
    public void flushAllBatches() {
        for (String filePath : writeBatches.keySet()) {
            flushBatch(filePath);
        }
    }
    
    /**
     * Get or create an asynchronous file channel
     * 
     * @param filePath Path to the file
     * @param write Whether to open for writing
     * @return AsynchronousFileChannel
     * @throws IOException If an I/O error occurs
     */
    private AsynchronousFileChannel getOrCreateChannel(String filePath, boolean write) throws IOException {
        return channelCache.computeIfAbsent(filePath, path -> {
            try {
                Path file = Paths.get(path);
                if (write) {
                    return AsynchronousFileChannel.open(file, 
                        StandardOpenOption.CREATE, 
                        StandardOpenOption.WRITE, 
                        StandardOpenOption.READ);
                } else {
                    return AsynchronousFileChannel.open(file, StandardOpenOption.READ);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to open channel for " + path, e);
            }
        });
    }
    
    /**
     * Wait for all pending operations to complete
     * 
     * @param timeoutMs Timeout in milliseconds
     * @return true if all operations completed, false if timed out
     * @throws InterruptedException If interrupted while waiting
     */
    public boolean waitForCompletion(long timeoutMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        
        // First flush all batches
        flushAllBatches();
        
        // Then wait for all operations to complete
        while (pendingOperations.get() > 0) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false; // Timed out
            }
            
            Thread.sleep(10);
        }
        
        return true;
    }
    
    /**
     * Shutdown the AsyncIOManager
     */
    public void shutdown() {
        shutdownRequested = true;
        
        try {
            // Flush all pending writes
            flushAllBatches();
            
            // Wait for all operations to complete
            waitForCompletion(5000);
            
            // Shutdown executors
            flushExecutor.shutdown();
            ioExecutor.shutdown();
            
            // Close all channels
            for (AsynchronousFileChannel channel : channelCache.values()) {
                try {
                    channel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error closing channel", e);
                }
            }
            
            channelCache.clear();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during shutdown", e);
        }
    }
    
    /**
     * Get the number of pending I/O operations
     * 
     * @return Number of pending operations
     */
    public int getPendingOperationCount() {
        return pendingOperations.get();
    }
    
    /**
     * Get the batch size threshold
     * 
     * @return Batch size in bytes
     */
    public int getBatchSize() {
        return batchSize;
    }
    
    /**
     * Get the flush interval
     * 
     * @return Flush interval in milliseconds
     */
    public int getFlushIntervalMs() {
        return flushIntervalMs;
    }
    
    /**
     * Check if shutdown has been requested
     * 
     * @return true if shutdown has been requested
     */
    public boolean isShutdownRequested() {
        return shutdownRequested;
    }
    
    /**
     * Represents a queued write operation
     */
    private static class WriteOperation {
        final long position;
        final ByteBuffer data;
        final IOCallback<Integer> callback;
        final String timerId;
        
        WriteOperation(long position, ByteBuffer data, IOCallback<Integer> callback, String timerId) {
            this.position = position;
            this.data = data;
            this.callback = callback;
            this.timerId = timerId;
        }
    }
    
    /**
     * Callback interface for asynchronous I/O operations
     */
    public interface IOCallback<T> {
        void onSuccess(T result);
        void onFailure(Throwable error);
    }
}