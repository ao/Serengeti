package com.ataiva.serengeti.performance;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects performance data from various components of the system and
 * sends it to the PerformanceProfiler. This class implements the data
 * collection pipeline for the performance profiling framework.
 */
public class PerformanceDataCollector {
    private static final Logger LOGGER = Logger.getLogger(PerformanceDataCollector.class.getName());
    
    // Singleton instance
    private static final PerformanceDataCollector INSTANCE = new PerformanceDataCollector();
    
    // Reference to the performance profiler
    private final PerformanceProfiler profiler = PerformanceProfiler.getInstance();
    
    // Scheduled executor for periodic data collection
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // JMX beans for system metrics
    private final OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    
    // Collection of component-specific collectors
    private final Map<String, ComponentCollector> componentCollectors = new ConcurrentHashMap<>();
    
    // Flag to indicate if the collector is running
    private boolean running = false;
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private PerformanceDataCollector() {
        // Initialize with default settings
    }
    
    /**
     * Gets the singleton instance of the performance data collector.
     *
     * @return The performance data collector instance
     */
    public static PerformanceDataCollector getInstance() {
        return INSTANCE;
    }
    
    /**
     * Starts the data collection process.
     *
     * @param intervalSeconds The interval between data collections in seconds
     */
    public synchronized void start(int intervalSeconds) {
        if (running) {
            LOGGER.warning("Performance data collector is already running");
            return;
        }
        
        running = true;
        
        // Schedule system metrics collection
        scheduler.scheduleAtFixedRate(
            this::collectSystemMetrics,
            0,
            intervalSeconds,
            TimeUnit.SECONDS
        );
        
        LOGGER.info("Performance data collector started with interval of " + intervalSeconds + " seconds");
    }
    
    /**
     * Stops the data collection process.
     */
    public synchronized void stop() {
        if (!running) {
            LOGGER.warning("Performance data collector is not running");
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
        
        LOGGER.info("Performance data collector stopped");
    }
    
    /**
     * Registers a component collector.
     *
     * @param componentName The name of the component
     * @param collector The component collector
     */
    public void registerComponentCollector(String componentName, ComponentCollector collector) {
        componentCollectors.put(componentName, collector);
        LOGGER.info("Registered component collector for " + componentName);
    }
    
    /**
     * Unregisters a component collector.
     *
     * @param componentName The name of the component
     */
    public void unregisterComponentCollector(String componentName) {
        componentCollectors.remove(componentName);
        LOGGER.info("Unregistered component collector for " + componentName);
    }
    
    /**
     * Collects system metrics.
     */
    private void collectSystemMetrics() {
        try {
            // Collect CPU metrics
            double systemLoad = osMXBean.getSystemLoadAverage();
            if (systemLoad >= 0) {  // -1 means not available
                profiler.recordCpuUsage("system", "overall", "system.load", systemLoad);
            }
            
            int availableProcessors = osMXBean.getAvailableProcessors();
            profiler.recordCustomMetric("system", "overall", "system.processors", 
                                      availableProcessors, "count");
            
            // Collect memory metrics
            long heapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
            long heapMax = memoryMXBean.getHeapMemoryUsage().getMax();
            long nonHeapUsed = memoryMXBean.getNonHeapMemoryUsage().getUsed();
            
            profiler.recordMemoryUsage("jvm", "memory", "heap.used", heapUsed);
            profiler.recordMemoryUsage("jvm", "memory", "heap.max", heapMax);
            profiler.recordMemoryUsage("jvm", "memory", "nonheap.used", nonHeapUsed);
            
            double heapUtilization = (double) heapUsed / heapMax * 100.0;
            profiler.recordCustomMetric("jvm", "memory", "heap.utilization", 
                                      heapUtilization, "%");
            
            // Collect thread metrics
            int threadCount = threadMXBean.getThreadCount();
            int peakThreadCount = threadMXBean.getPeakThreadCount();
            long totalStartedThreadCount = threadMXBean.getTotalStartedThreadCount();
            
            profiler.recordCustomMetric("jvm", "threads", "thread.count", 
                                      threadCount, "count");
            profiler.recordCustomMetric("jvm", "threads", "thread.peak", 
                                      peakThreadCount, "count");
            profiler.recordCustomMetric("jvm", "threads", "thread.total", 
                                      totalStartedThreadCount, "count");
            
            // Collect GC metrics
            // In a real implementation, we would use GarbageCollectorMXBean
            // For simplicity, we'll skip that here
            
            // Collect component-specific metrics
            collectComponentMetrics();
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error collecting system metrics", e);
        }
    }
    
    /**
     * Collects metrics from registered component collectors.
     */
    private void collectComponentMetrics() {
        for (Map.Entry<String, ComponentCollector> entry : componentCollectors.entrySet()) {
            String componentName = entry.getKey();
            ComponentCollector collector = entry.getValue();
            
            try {
                collector.collectMetrics();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error collecting metrics for component " + componentName, e);
            }
        }
    }
    
    /**
     * Interface for component-specific metric collectors.
     */
    public interface ComponentCollector {
        /**
         * Collects metrics for a specific component.
         */
        void collectMetrics();
    }
    
    /**
     * Creates a query engine collector.
     *
     * @return A component collector for the query engine
     */
    public static ComponentCollector createQueryEngineCollector() {
        return new ComponentCollector() {
            @Override
            public void collectMetrics() {
                // Implementation will be added when integrating with the query engine
                // This would collect metrics like query execution times, cache hit rates, etc.
            }
        };
    }
    
    /**
     * Creates a storage engine collector.
     *
     * @return A component collector for the storage engine
     */
    public static ComponentCollector createStorageEngineCollector() {
        return new ComponentCollector() {
            @Override
            public void collectMetrics() {
                // Implementation will be added when integrating with the storage engine
                // This would collect metrics like read/write throughput, compaction stats, etc.
            }
        };
    }
    
    /**
     * Creates a network collector.
     *
     * @return A component collector for the network subsystem
     */
    public static ComponentCollector createNetworkCollector() {
        return new ComponentCollector() {
            @Override
            public void collectMetrics() {
                // Implementation will be added when integrating with the network subsystem
                // This would collect metrics like connection counts, message throughput, etc.
            }
        };
    }
    
    /**
     * Creates a server collector.
     *
     * @return A component collector for the server
     */
    public static ComponentCollector createServerCollector() {
        return new ComponentCollector() {
            @Override
            public void collectMetrics() {
                // Implementation will be added when integrating with the server
                // This would collect metrics like request rates, response times, etc.
            }
        };
    }
}