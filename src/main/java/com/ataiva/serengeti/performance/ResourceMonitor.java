package com.ataiva.serengeti.performance;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Monitors system resources and reports metrics to the performance profiler.
 * This class provides detailed tracking of CPU, memory, disk I/O, and thread utilization.
 */
public class ResourceMonitor {
    private static final Logger LOGGER = Logger.getLogger(ResourceMonitor.class.getName());
    
    // Singleton instance
    private static final ResourceMonitor INSTANCE = new ResourceMonitor();
    
    // Reference to the performance profiler
    private final PerformanceProfiler profiler = PerformanceProfiler.getInstance();
    
    // Configuration
    private final ProfilingConfiguration config = ProfilingConfiguration.getInstance();
    
    // Scheduled executor for periodic monitoring
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // JMX beans for system metrics
    private final OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    
    // Previous values for calculating deltas
    private final Map<String, Long> previousValues = new HashMap<>();
    
    // Flag to indicate if the monitor is running
    private boolean running = false;
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private ResourceMonitor() {
        // Initialize with default settings
    }
    
    /**
     * Gets the singleton instance of the resource monitor.
     *
     * @return The resource monitor instance
     */
    public static ResourceMonitor getInstance() {
        return INSTANCE;
    }
    
    /**
     * Starts resource monitoring.
     */
    public synchronized void start() {
        if (running) {
            LOGGER.warning("Resource monitor is already running");
            return;
        }
        
        running = true;
        
        // Schedule resource monitoring
        scheduler.scheduleAtFixedRate(
            this::collectResourceMetrics,
            0,
            config.getCollectionIntervalSeconds(),
            TimeUnit.SECONDS
        );
        
        LOGGER.info("Resource monitor started with interval of " + 
                   config.getCollectionIntervalSeconds() + " seconds");
    }
    
    /**
     * Stops resource monitoring.
     */
    public synchronized void stop() {
        if (!running) {
            LOGGER.warning("Resource monitor is not running");
            return;
        }
        
        running = false;
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Resource monitor stopped");
    }
    
    /**
     * Collects resource metrics.
     */
    private void collectResourceMetrics() {
        if (!config.isEnabled() || !config.shouldSample()) {
            return;
        }
        
        try {
            collectCpuMetrics();
            collectMemoryMetrics();
            collectDiskMetrics();
            collectThreadMetrics();
            collectGcMetrics();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error collecting resource metrics", e);
        }
    }
    
    /**
     * Collects CPU metrics.
     */
    private void collectCpuMetrics() {
        // System CPU load
        double systemLoad = osMXBean.getSystemLoadAverage();
        if (systemLoad >= 0) {  // -1 means not available
            profiler.recordCpuUsage("system", "cpu", "system.load", systemLoad);
        }
        
        // Process CPU load
        try {
            // Try to access com.sun.management.OperatingSystemMXBean methods using reflection
            // since they are not part of the standard API
            Method processCpuLoad = osMXBean.getClass().getMethod("getProcessCpuLoad");
            double processLoad = (double) processCpuLoad.invoke(osMXBean);
            if (processLoad >= 0) {
                profiler.recordCpuUsage("process", "cpu", "process.load", processLoad * 100.0);
            }
            
            Method processCpuTime = osMXBean.getClass().getMethod("getProcessCpuTime");
            long cpuTime = (long) processCpuTime.invoke(osMXBean);
            
            // Calculate CPU time delta
            Long previousCpuTime = previousValues.get("process.cpu.time");
            if (previousCpuTime != null) {
                long delta = cpuTime - previousCpuTime;
                profiler.recordCustomMetric("process", "cpu", "process.cpu.time.delta", 
                                          delta / 1_000_000.0, "ms");
            }
            previousValues.put("process.cpu.time", cpuTime);
            
        } catch (Exception e) {
            // These methods might not be available on all JVMs
            LOGGER.log(Level.FINE, "Could not access detailed CPU metrics", e);
        }
        
        // Available processors
        int availableProcessors = osMXBean.getAvailableProcessors();
        profiler.recordCustomMetric("system", "cpu", "system.processors", 
                                  availableProcessors, "count");
    }
    
    /**
     * Collects memory metrics.
     */
    private void collectMemoryMetrics() {
        // JVM heap memory
        long heapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryMXBean.getHeapMemoryUsage().getMax();
        long heapCommitted = memoryMXBean.getHeapMemoryUsage().getCommitted();
        
        profiler.recordMemoryUsage("jvm", "memory", "heap.used", heapUsed);
        profiler.recordMemoryUsage("jvm", "memory", "heap.max", heapMax);
        profiler.recordMemoryUsage("jvm", "memory", "heap.committed", heapCommitted);
        
        // Calculate heap utilization percentage
        if (heapMax > 0) {
            double heapUtilization = (double) heapUsed / heapMax * 100.0;
            profiler.recordCustomMetric("jvm", "memory", "heap.utilization", 
                                      heapUtilization, "%");
        }
        
        // JVM non-heap memory
        long nonHeapUsed = memoryMXBean.getNonHeapMemoryUsage().getUsed();
        long nonHeapCommitted = memoryMXBean.getNonHeapMemoryUsage().getCommitted();
        
        profiler.recordMemoryUsage("jvm", "memory", "nonheap.used", nonHeapUsed);
        profiler.recordMemoryUsage("jvm", "memory", "nonheap.committed", nonHeapCommitted);
        
        // System memory
        try {
            // Try to access com.sun.management.OperatingSystemMXBean methods using reflection
            Method totalMemory = osMXBean.getClass().getMethod("getTotalPhysicalMemorySize");
            Method freeMemory = osMXBean.getClass().getMethod("getFreePhysicalMemorySize");
            
            long totalPhysicalMemory = (long) totalMemory.invoke(osMXBean);
            long freePhysicalMemory = (long) freeMemory.invoke(osMXBean);
            long usedPhysicalMemory = totalPhysicalMemory - freePhysicalMemory;
            
            profiler.recordMemoryUsage("system", "memory", "physical.total", totalPhysicalMemory);
            profiler.recordMemoryUsage("system", "memory", "physical.free", freePhysicalMemory);
            profiler.recordMemoryUsage("system", "memory", "physical.used", usedPhysicalMemory);
            
            // Calculate physical memory utilization percentage
            double physicalUtilization = (double) usedPhysicalMemory / totalPhysicalMemory * 100.0;
            profiler.recordCustomMetric("system", "memory", "physical.utilization", 
                                      physicalUtilization, "%");
            
        } catch (Exception e) {
            // These methods might not be available on all JVMs
            LOGGER.log(Level.FINE, "Could not access detailed memory metrics", e);
        }
    }
    
    /**
     * Collects disk metrics.
     */
    private void collectDiskMetrics() {
        // Get disk usage for the current directory
        File currentDir = new File(".");
        File rootDir = new File("/");
        
        collectDiskMetricsForPath(currentDir, "current");
        collectDiskMetricsForPath(rootDir, "root");
        
        // Try to get disk I/O statistics using reflection
        try {
            // These methods are platform-specific and might not be available
            Method diskReads = osMXBean.getClass().getMethod("getSystemLoadAverage");
            Method diskWrites = osMXBean.getClass().getMethod("getSystemLoadAverage");
            
            // This is just a placeholder - actual implementation would use
            // platform-specific APIs to get disk I/O statistics
            
        } catch (Exception e) {
            // These methods might not be available on all JVMs
            LOGGER.log(Level.FINE, "Could not access detailed disk metrics", e);
        }
    }
    
    /**
     * Collects disk metrics for a specific path.
     *
     * @param path The path to collect metrics for
     * @param name The name to use in metrics
     */
    private void collectDiskMetricsForPath(File path, String name) {
        long totalSpace = path.getTotalSpace();
        long freeSpace = path.getFreeSpace();
        long usableSpace = path.getUsableSpace();
        
        profiler.recordCustomMetric("system", "disk", name + ".total", 
                                  totalSpace / (1024.0 * 1024.0), "MB");
        profiler.recordCustomMetric("system", "disk", name + ".free", 
                                  freeSpace / (1024.0 * 1024.0), "MB");
        profiler.recordCustomMetric("system", "disk", name + ".usable", 
                                  usableSpace / (1024.0 * 1024.0), "MB");
        
        // Calculate disk utilization percentage
        if (totalSpace > 0) {
            double diskUtilization = (double) (totalSpace - freeSpace) / totalSpace * 100.0;
            profiler.recordCustomMetric("system", "disk", name + ".utilization", 
                                      diskUtilization, "%");
        }
    }
    
    /**
     * Collects thread metrics.
     */
    private void collectThreadMetrics() {
        // Thread counts
        int threadCount = threadMXBean.getThreadCount();
        int peakThreadCount = threadMXBean.getPeakThreadCount();
        int daemonThreadCount = threadMXBean.getDaemonThreadCount();
        long totalStartedThreadCount = threadMXBean.getTotalStartedThreadCount();
        
        profiler.recordCustomMetric("jvm", "threads", "thread.count", threadCount, "count");
        profiler.recordCustomMetric("jvm", "threads", "thread.peak", peakThreadCount, "count");
        profiler.recordCustomMetric("jvm", "threads", "thread.daemon", daemonThreadCount, "count");
        profiler.recordCustomMetric("jvm", "threads", "thread.total", totalStartedThreadCount, "count");
        
        // Thread CPU time if supported
        if (threadMXBean.isThreadCpuTimeSupported() && threadMXBean.isThreadCpuTimeEnabled()) {
            long[] threadIds = threadMXBean.getAllThreadIds();
            long totalCpuTime = 0;
            long totalUserTime = 0;
            
            for (long threadId : threadIds) {
                long cpuTime = threadMXBean.getThreadCpuTime(threadId);
                long userTime = threadMXBean.getThreadUserTime(threadId);
                
                if (cpuTime != -1) {
                    totalCpuTime += cpuTime;
                }
                
                if (userTime != -1) {
                    totalUserTime += userTime;
                }
            }
            
            // Convert nanoseconds to milliseconds
            profiler.recordCustomMetric("jvm", "threads", "thread.cpu.time", 
                                      totalCpuTime / 1_000_000.0, "ms");
            profiler.recordCustomMetric("jvm", "threads", "thread.user.time", 
                                      totalUserTime / 1_000_000.0, "ms");
        }
    }
    
    /**
     * Collects garbage collection metrics.
     */
    private void collectGcMetrics() {
        try {
            // Get all garbage collectors
            java.lang.management.GarbageCollectorMXBean[] gcBeans = 
                ManagementFactory.getGarbageCollectorMXBeans().toArray(
                    new java.lang.management.GarbageCollectorMXBean[0]);
            
            for (java.lang.management.GarbageCollectorMXBean gcBean : gcBeans) {
                String name = gcBean.getName().replace(" ", "_").toLowerCase();
                long collectionCount = gcBean.getCollectionCount();
                long collectionTime = gcBean.getCollectionTime();
                
                profiler.recordCustomMetric("jvm", "gc", name + ".count", collectionCount, "count");
                profiler.recordCustomMetric("jvm", "gc", name + ".time", collectionTime, "ms");
                
                // Calculate deltas
                String countKey = "gc." + name + ".count";
                String timeKey = "gc." + name + ".time";
                
                Long previousCount = previousValues.get(countKey);
                Long previousTime = previousValues.get(timeKey);
                
                if (previousCount != null && previousTime != null) {
                    long countDelta = collectionCount - previousCount;
                    long timeDelta = collectionTime - previousTime;
                    
                    profiler.recordCustomMetric("jvm", "gc", name + ".count.delta", countDelta, "count");
                    profiler.recordCustomMetric("jvm", "gc", name + ".time.delta", timeDelta, "ms");
                }
                
                previousValues.put(countKey, collectionCount);
                previousValues.put(timeKey, collectionTime);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not access GC metrics", e);
        }
    }
    
    /**
     * Takes a snapshot of current resource usage.
     *
     * @return A map of resource metrics
     */
    public Map<String, Object> takeResourceSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        
        try {
            // CPU metrics
            snapshot.put("cpu.system.load", osMXBean.getSystemLoadAverage());
            snapshot.put("cpu.available.processors", osMXBean.getAvailableProcessors());
            
            // Memory metrics
            snapshot.put("memory.heap.used", memoryMXBean.getHeapMemoryUsage().getUsed());
            snapshot.put("memory.heap.max", memoryMXBean.getHeapMemoryUsage().getMax());
            snapshot.put("memory.nonheap.used", memoryMXBean.getNonHeapMemoryUsage().getUsed());
            
            // Thread metrics
            snapshot.put("threads.count", threadMXBean.getThreadCount());
            snapshot.put("threads.peak", threadMXBean.getPeakThreadCount());
            snapshot.put("threads.daemon", threadMXBean.getDaemonThreadCount());
            snapshot.put("threads.total", threadMXBean.getTotalStartedThreadCount());
            
            // Disk metrics
            File currentDir = new File(".");
            snapshot.put("disk.current.free", currentDir.getFreeSpace());
            snapshot.put("disk.current.total", currentDir.getTotalSpace());
            
            // Try to get additional metrics using reflection
            try {
                Method processCpuLoad = osMXBean.getClass().getMethod("getProcessCpuLoad");
                snapshot.put("cpu.process.load", (double) processCpuLoad.invoke(osMXBean) * 100.0);
                
                Method totalMemory = osMXBean.getClass().getMethod("getTotalPhysicalMemorySize");
                Method freeMemory = osMXBean.getClass().getMethod("getFreePhysicalMemorySize");
                
                snapshot.put("memory.physical.total", totalMemory.invoke(osMXBean));
                snapshot.put("memory.physical.free", freeMemory.invoke(osMXBean));
            } catch (Exception e) {
                // Ignore reflection errors
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error taking resource snapshot", e);
        }
        
        return snapshot;
    }
}