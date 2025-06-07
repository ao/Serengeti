package com.ataiva.serengeti.performance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central performance profiling framework for the Serengeti system.
 * This class provides methods for recording and analyzing performance metrics
 * across different components of the system.
 */
public class PerformanceProfiler {
    private static final Logger LOGGER = Logger.getLogger(PerformanceProfiler.class.getName());
    
    // Singleton instance
    private static final PerformanceProfiler INSTANCE = new PerformanceProfiler();
    
    // Configuration
    private final ProfilingConfiguration config = ProfilingConfiguration.getInstance();
    
    // Storage for metrics
    private final CopyOnWriteArrayList<PerformanceMetric> metrics = new CopyOnWriteArrayList<>();
    
    // Active timers for measuring operation durations
    private final ConcurrentHashMap<String, Long> activeTimers = new ConcurrentHashMap<>();
    
    // Counters for throughput measurements
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    
    // Listeners for real-time metric notifications
    private final List<Consumer<PerformanceMetric>> listeners = new ArrayList<>();
    
    // Configuration
    private boolean enabled = true;
    private int retentionLimit = 10000; // Maximum number of metrics to retain
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private PerformanceProfiler() {
        // Initialize with default settings
    }
    
    /**
     * Gets the singleton instance of the performance profiler.
     *
     * @return The performance profiler instance
     */
    public static PerformanceProfiler getInstance() {
        return INSTANCE;
    }
    
    /**
     * Enables or disables the performance profiler.
     *
     * @param enabled True to enable, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("Performance profiler " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Sets the retention limit for metrics.
     *
     * @param limit The maximum number of metrics to retain
     */
    public void setRetentionLimit(int limit) {
        this.retentionLimit = limit;
        LOGGER.info("Performance profiler retention limit set to " + limit);
        
        // Trim metrics if needed
        trimMetricsIfNeeded();
    }
    
    /**
     * Starts a timer for measuring operation duration.
     *
     * @param component The component being measured
     * @param operation The operation being measured
     * @return A unique timer ID for stopping the timer later
     */
    public String startTimer(String component, String operation) {
        if (!enabled || !config.shouldSample()) return null;
        
        String timerId = component + "." + operation + "." + System.nanoTime();
        activeTimers.put(timerId, System.nanoTime());
        return timerId;
    }
    
    /**
     * Stops a timer and records the duration as a latency metric.
     *
     * @param timerId The timer ID returned by startTimer
     * @param metricName The name of the metric to record
     * @return The duration in milliseconds, or -1 if the timer was not found
     */
    public double stopTimer(String timerId, String metricName) {
        if (!enabled || timerId == null) return -1;
        
        Long startTime = activeTimers.remove(timerId);
        if (startTime == null) {
            LOGGER.warning("Timer not found: " + timerId);
            return -1;
        }
        
        long duration = System.nanoTime() - startTime;
        double durationMs = duration / 1_000_000.0;
        
        String[] parts = timerId.split("\\.", 3);
        if (parts.length < 2) {
            LOGGER.warning("Invalid timer ID format: " + timerId);
            return durationMs;
        }
        
        String component = parts[0];
        String operation = parts[1];
        
        recordMetric(new PerformanceMetric(
            metricName,
            durationMs,
            "ms",
            PerformanceMetric.MetricType.LATENCY,
            component,
            operation
        ));
        
        return durationMs;
    }
    
    /**
     * Increments a counter for throughput measurements.
     *
     * @param component The component being measured
     * @param operation The operation being measured
     * @param counterName The name of the counter
     * @return The new counter value
     */
    public long incrementCounter(String component, String operation, String counterName) {
        if (!enabled || !config.shouldSample()) return -1;
        
        String key = component + "." + operation + "." + counterName;
        AtomicLong counter = counters.computeIfAbsent(key, k -> new AtomicLong(0));
        long value = counter.incrementAndGet();
        
        // Record a throughput metric every 100 increments
        if (value % 100 == 0) {
            recordThroughputMetric(component, operation, counterName);
        }
        
        return value;
    }
    
    /**
     * Records a throughput metric based on counter values.
     *
     * @param component The component being measured
     * @param operation The operation being measured
     * @param counterName The name of the counter
     */
    private void recordThroughputMetric(String component, String operation, String counterName) {
        String key = component + "." + operation + "." + counterName;
        AtomicLong counter = counters.get(key);
        if (counter == null) return;
        
        // Calculate operations per second based on the last minute
        // In a real implementation, we would track timestamps of increments
        // For now, we'll just use the current value as an approximation
        double opsPerSecond = counter.get() / 60.0;
        
        recordMetric(new PerformanceMetric(
            counterName + ".throughput",
            opsPerSecond,
            "ops/sec",
            PerformanceMetric.MetricType.THROUGHPUT,
            component,
            operation
        ));
    }
    
    /**
     * Records a memory usage metric.
     *
     * @param component The component being measured
     * @param operation The operation being measured
     * @param metricName The name of the metric
     * @param bytes The memory usage in bytes
     */
    public void recordMemoryUsage(String component, String operation, String metricName, long bytes) {
        if (!enabled || !config.shouldSample()) return;
        
        recordMetric(new PerformanceMetric(
            metricName,
            bytes,
            "bytes",
            PerformanceMetric.MetricType.MEMORY_USAGE,
            component,
            operation
        ));
    }
    
    /**
     * Records a CPU usage metric.
     *
     * @param component The component being measured
     * @param operation The operation being measured
     * @param metricName The name of the metric
     * @param percentage The CPU usage as a percentage
     */
    public void recordCpuUsage(String component, String operation, String metricName, double percentage) {
        if (!enabled || !config.shouldSample()) return;
        
        recordMetric(new PerformanceMetric(
            metricName,
            percentage,
            "%",
            PerformanceMetric.MetricType.CPU_USAGE,
            component,
            operation
        ));
    }
    
    /**
     * Records an I/O rate metric.
     *
     * @param component The component being measured
     * @param operation The operation being measured
     * @param metricName The name of the metric
     * @param opsPerSecond The I/O operations per second
     */
    public void recordIoRate(String component, String operation, String metricName, double opsPerSecond) {
        if (!enabled || !config.shouldSample()) return;
        
        recordMetric(new PerformanceMetric(
            metricName,
            opsPerSecond,
            "ops/sec",
            PerformanceMetric.MetricType.IO_RATE,
            component,
            operation
        ));
    }
    
    /**
     * Records an error rate metric.
     *
     * @param component The component being measured
     * @param operation The operation being measured
     * @param metricName The name of the metric
     * @param errorsPerSecond The errors per second
     */
    public void recordErrorRate(String component, String operation, String metricName, double errorsPerSecond) {
        if (!enabled || !config.shouldSample()) return;
        
        recordMetric(new PerformanceMetric(
            metricName,
            errorsPerSecond,
            "errors/sec",
            PerformanceMetric.MetricType.ERROR_RATE,
            component,
            operation
        ));
    }
    
    /**
     * Records a latency metric.
     *
     * @param component The component being measured
     * @param operation The operation being measured
     * @param metricName The name of the metric
     * @param latencyMs The latency in milliseconds
     */
    public void recordLatency(String component, String operation, String metricName, double latencyMs) {
        if (!enabled || !config.shouldSample()) return;
        
        recordMetric(new PerformanceMetric(
            metricName,
            latencyMs,
            "ms",
            PerformanceMetric.MetricType.LATENCY,
            component,
            operation
        ));
    }
    
    /**
     * Records a custom metric.
     *
     * @param component The component being measured
     * @param operation The operation being measured
     * @param metricName The name of the metric
     * @param value The value of the metric
     * @param unit The unit of measurement
     */
    public void recordCustomMetric(String component, String operation, String metricName, 
                                  double value, String unit) {
        if (!enabled || !config.shouldSample()) return;
        
        recordMetric(new PerformanceMetric(
            metricName,
            value,
            unit,
            PerformanceMetric.MetricType.CUSTOM,
            component,
            operation
        ));
    }
    
    /**
     * Records a metric and notifies listeners.
     *
     * @param metric The metric to record
     */
    private void recordMetric(PerformanceMetric metric) {
        metrics.add(metric);
        
        // Notify listeners
        for (Consumer<PerformanceMetric> listener : listeners) {
            try {
                listener.accept(metric);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying metric listener", e);
            }
        }
        
        // Trim metrics if needed
        trimMetricsIfNeeded();
        
        // Log the metric
        LOGGER.fine(metric.toString());
    }
    
    /**
     * Trims the metrics list if it exceeds the retention limit.
     */
    private void trimMetricsIfNeeded() {
        if (metrics.size() > retentionLimit) {
            // Remove oldest metrics
            int toRemove = metrics.size() - retentionLimit;
            for (int i = 0; i < toRemove; i++) {
                metrics.remove(0);
            }
        }
    }
    
    /**
     * Adds a listener for real-time metric notifications.
     *
     * @param listener The listener to add
     */
    public synchronized void addListener(Consumer<PerformanceMetric> listener) {
        listeners.add(listener);
    }
    
    /**
     * Removes a listener.
     *
     * @param listener The listener to remove
     */
    public synchronized void removeListener(Consumer<PerformanceMetric> listener) {
        listeners.remove(listener);
    }
    
    /**
     * Gets all recorded metrics.
     *
     * @return An unmodifiable list of all metrics
     */
    public List<PerformanceMetric> getAllMetrics() {
        return Collections.unmodifiableList(metrics);
    }
    
    /**
     * Gets metrics filtered by component.
     *
     * @param component The component to filter by
     * @return A list of metrics for the specified component
     */
    public List<PerformanceMetric> getMetricsByComponent(String component) {
        List<PerformanceMetric> result = new ArrayList<>();
        for (PerformanceMetric metric : metrics) {
            if (metric.getComponent().equals(component)) {
                result.add(metric);
            }
        }
        return result;
    }
    
    /**
     * Gets metrics filtered by operation.
     *
     * @param operation The operation to filter by
     * @return A list of metrics for the specified operation
     */
    public List<PerformanceMetric> getMetricsByOperation(String operation) {
        List<PerformanceMetric> result = new ArrayList<>();
        for (PerformanceMetric metric : metrics) {
            if (metric.getOperation().equals(operation)) {
                result.add(metric);
            }
        }
        return result;
    }
    
    /**
     * Gets metrics filtered by type.
     *
     * @param type The metric type to filter by
     * @return A list of metrics of the specified type
     */
    public List<PerformanceMetric> getMetricsByType(PerformanceMetric.MetricType type) {
        List<PerformanceMetric> result = new ArrayList<>();
        for (PerformanceMetric metric : metrics) {
            if (metric.getType() == type) {
                result.add(metric);
            }
        }
        return result;
    }
    
    /**
     * Gets metrics filtered by name.
     *
     * @param name The metric name to filter by
     * @return A list of metrics with the specified name
     */
    public List<PerformanceMetric> getMetricsByName(String name) {
        List<PerformanceMetric> result = new ArrayList<>();
        for (PerformanceMetric metric : metrics) {
            if (metric.getName().equals(name)) {
                result.add(metric);
            }
        }
        return result;
    }
    
    /**
     * Gets metrics filtered by time range.
     *
     * @param startTime The start time in milliseconds since epoch
     * @param endTime The end time in milliseconds since epoch
     * @return A list of metrics within the specified time range
     */
    public List<PerformanceMetric> getMetricsByTimeRange(long startTime, long endTime) {
        List<PerformanceMetric> result = new ArrayList<>();
        for (PerformanceMetric metric : metrics) {
            long timestamp = metric.getTimestamp();
            if (timestamp >= startTime && timestamp <= endTime) {
                result.add(metric);
            }
        }
        return result;
    }
    
    /**
     * Clears all recorded metrics.
     */
    public void clearMetrics() {
        metrics.clear();
        LOGGER.info("All performance metrics cleared");
    }
    
    /**
     * Gets a summary of metrics by component and operation.
     *
     * @return A map of component to operation to metric summaries
     */
    public Map<String, Map<String, Map<String, Object>>> getMetricsSummary() {
        Map<String, Map<String, Map<String, Object>>> summary = new HashMap<>();
        
        for (PerformanceMetric metric : metrics) {
            String component = metric.getComponent();
            String operation = metric.getOperation();
            String name = metric.getName();
            
            // Get or create component map
            Map<String, Map<String, Object>> componentMap = summary.computeIfAbsent(
                component, k -> new HashMap<>());
            
            // Get or create operation map
            Map<String, Object> operationMap = componentMap.computeIfAbsent(
                operation, k -> new HashMap<>());
            
            // Update metric summary
            if (metric.getType() == PerformanceMetric.MetricType.LATENCY) {
                // For latency, track min, max, avg
                @SuppressWarnings("unchecked")
                Map<String, Object> latencyMap = (Map<String, Object>) operationMap.computeIfAbsent(
                    name, k -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("min", Double.MAX_VALUE);
                        m.put("max", 0.0);
                        m.put("sum", 0.0);
                        m.put("count", 0);
                        return m;
                    });
                
                double value = metric.getValue();
                latencyMap.put("min", Math.min((double) latencyMap.get("min"), value));
                latencyMap.put("max", Math.max((double) latencyMap.get("max"), value));
                latencyMap.put("sum", (double) latencyMap.get("sum") + value);
                latencyMap.put("count", (int) latencyMap.get("count") + 1);
                latencyMap.put("avg", (double) latencyMap.get("sum") / (int) latencyMap.get("count"));
            } else {
                // For other metrics, just use the latest value
                operationMap.put(name, metric.getValue());
            }
        }
        
        return summary;
    }
    
    /**
     * Creates a profiled version of a Runnable that measures execution time.
     *
     * @param component The component name for metrics
     * @param operation The operation name for metrics
     * @param runnable The runnable to profile
     * @return A profiled runnable
     */
    public Runnable profiledRunnable(String component, String operation, Runnable runnable) {
        return () -> {
            String timerId = startTimer(component, operation);
            try {
                runnable.run();
            } finally {
                stopTimer(timerId, operation + ".execution-time");
            }
        };
    }
    
    /**
     * Profiles a method execution.
     *
     * @param component The component name for metrics
     * @param operation The operation name for metrics
     * @param runnable The code to execute and profile
     */
    public void profileExecution(String component, String operation, Runnable runnable) {
        if (!enabled || !config.shouldSample()) {
            runnable.run();
            return;
        }
        
        String timerId = startTimer(component, operation);
        try {
            runnable.run();
        } finally {
            stopTimer(timerId, operation + ".execution-time");
        }
    }
    
    /**
     * Profiles a method execution and returns its result.
     *
     * @param <T> The return type
     * @param component The component name for metrics
     * @param operation The operation name for metrics
     * @param supplier The code to execute and profile
     * @return The result of the execution
     */
    public <T> T profileExecution(String component, String operation, java.util.function.Supplier<T> supplier) {
        if (!enabled || !config.shouldSample()) {
            return supplier.get();
        }
        
        String timerId = startTimer(component, operation);
        try {
            return supplier.get();
        } finally {
            stopTimer(timerId, operation + ".execution-time");
        }
    }
}