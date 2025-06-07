package com.ataiva.serengeti.performance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Detects and analyzes performance bottlenecks in the system.
 * This class uses performance metrics to identify slow operations,
 * resource constraints, and other performance issues.
 */
public class BottleneckDetector {
    private static final Logger LOGGER = Logger.getLogger(BottleneckDetector.class.getName());
    
    // Singleton instance
    private static final BottleneckDetector INSTANCE = new BottleneckDetector();
    
    // Reference to the performance profiler
    private final PerformanceProfiler profiler = PerformanceProfiler.getInstance();
    
    // Reference to the historical data store
    private final HistoricalDataStore historyStore = HistoricalDataStore.getInstance();
    
    // Configuration
    private final ProfilingConfiguration config = ProfilingConfiguration.getInstance();
    
    // Thresholds for bottleneck detection
    private double latencyThresholdMs = 100.0;
    private double cpuThresholdPercent = 80.0;
    private double memoryThresholdPercent = 80.0;
    private double ioThresholdOpsPerSec = 1000.0;
    private double errorThresholdPercent = 1.0;
    
    // Cache for bottleneck analysis results
    private final Map<String, BottleneckAnalysisResult> analysisCache = new HashMap<>();
    private long analysisCacheExpiryMs = TimeUnit.MINUTES.toMillis(5);
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private BottleneckDetector() {
        // Initialize with default settings
    }
    
    /**
     * Gets the singleton instance of the bottleneck detector.
     *
     * @return The bottleneck detector instance
     */
    public static BottleneckDetector getInstance() {
        return INSTANCE;
    }
    
    /**
     * Sets the thresholds for bottleneck detection.
     *
     * @param latencyThresholdMs The latency threshold in milliseconds
     * @param cpuThresholdPercent The CPU usage threshold in percent
     * @param memoryThresholdPercent The memory usage threshold in percent
     * @param ioThresholdOpsPerSec The I/O operations per second threshold
     * @param errorThresholdPercent The error rate threshold in percent
     */
    public void setThresholds(double latencyThresholdMs, double cpuThresholdPercent,
                             double memoryThresholdPercent, double ioThresholdOpsPerSec,
                             double errorThresholdPercent) {
        this.latencyThresholdMs = latencyThresholdMs;
        this.cpuThresholdPercent = cpuThresholdPercent;
        this.memoryThresholdPercent = memoryThresholdPercent;
        this.ioThresholdOpsPerSec = ioThresholdOpsPerSec;
        this.errorThresholdPercent = errorThresholdPercent;
        
        LOGGER.info("Bottleneck detection thresholds set to: " +
                   "latency=" + latencyThresholdMs + "ms, " +
                   "cpu=" + cpuThresholdPercent + "%, " +
                   "memory=" + memoryThresholdPercent + "%, " +
                   "io=" + ioThresholdOpsPerSec + " ops/sec, " +
                   "error=" + errorThresholdPercent + "%");
    }
    
    /**
     * Sets the analysis cache expiry time.
     *
     * @param expiryTimeMs The cache expiry time in milliseconds
     */
    public void setAnalysisCacheExpiryMs(long expiryTimeMs) {
        this.analysisCacheExpiryMs = expiryTimeMs;
    }
    
    /**
     * Detects bottlenecks in the system based on current metrics.
     *
     * @return A list of detected bottlenecks
     */
    public List<Bottleneck> detectBottlenecks() {
        if (!config.isEnabled()) {
            return Collections.emptyList();
        }
        
        List<Bottleneck> bottlenecks = new ArrayList<>();
        
        try {
            // Get all metrics
            List<PerformanceMetric> metrics = profiler.getAllMetrics();
            
            // Check for latency bottlenecks
            detectLatencyBottlenecks(metrics, bottlenecks);
            
            // Check for CPU bottlenecks
            detectCpuBottlenecks(metrics, bottlenecks);
            
            // Check for memory bottlenecks
            detectMemoryBottlenecks(metrics, bottlenecks);
            
            // Check for I/O bottlenecks
            detectIoBottlenecks(metrics, bottlenecks);
            
            // Check for error rate bottlenecks
            detectErrorRateBottlenecks(metrics, bottlenecks);
            
            // Sort bottlenecks by severity (descending)
            Collections.sort(bottlenecks, Comparator.comparing(Bottleneck::getSeverity).reversed());
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error detecting bottlenecks", e);
        }
        
        return bottlenecks;
    }
    
    /**
     * Detects latency bottlenecks.
     *
     * @param metrics The metrics to analyze
     * @param bottlenecks The list to add detected bottlenecks to
     */
    private void detectLatencyBottlenecks(List<PerformanceMetric> metrics, List<Bottleneck> bottlenecks) {
        // Group latency metrics by component and operation
        Map<String, Map<String, List<PerformanceMetric>>> latencyMetrics = metrics.stream()
            .filter(m -> m.getType() == PerformanceMetric.MetricType.LATENCY)
            .collect(Collectors.groupingBy(
                PerformanceMetric::getComponent,
                Collectors.groupingBy(PerformanceMetric::getOperation)
            ));
        
        // Check each component and operation for latency bottlenecks
        for (Map.Entry<String, Map<String, List<PerformanceMetric>>> componentEntry : latencyMetrics.entrySet()) {
            String component = componentEntry.getKey();
            
            for (Map.Entry<String, List<PerformanceMetric>> operationEntry : componentEntry.getValue().entrySet()) {
                String operation = operationEntry.getKey();
                List<PerformanceMetric> operationMetrics = operationEntry.getValue();
                
                // Calculate average latency
                double avgLatency = operationMetrics.stream()
                    .mapToDouble(PerformanceMetric::getValue)
                    .average()
                    .orElse(0.0);
                
                // Check if average latency exceeds threshold
                if (avgLatency > latencyThresholdMs) {
                    Bottleneck bottleneck = new Bottleneck(
                        component,
                        operation,
                        "High latency",
                        "Average latency of " + String.format("%.2f", avgLatency) + " ms exceeds threshold of " + latencyThresholdMs + " ms",
                        calculateSeverity(avgLatency, latencyThresholdMs),
                        Bottleneck.BottleneckType.LATENCY
                    );
                    
                    bottlenecks.add(bottleneck);
                }
            }
        }
    }
    
    /**
     * Detects CPU bottlenecks.
     *
     * @param metrics The metrics to analyze
     * @param bottlenecks The list to add detected bottlenecks to
     */
    private void detectCpuBottlenecks(List<PerformanceMetric> metrics, List<Bottleneck> bottlenecks) {
        // Find CPU usage metrics
        List<PerformanceMetric> cpuMetrics = metrics.stream()
            .filter(m -> m.getType() == PerformanceMetric.MetricType.CPU_USAGE)
            .collect(Collectors.toList());
        
        // Check system CPU usage
        cpuMetrics.stream()
            .filter(m -> m.getComponent().equals("system") && m.getName().equals("system.load"))
            .findFirst()
            .ifPresent(metric -> {
                double cpuUsage = metric.getValue();
                
                if (cpuUsage > cpuThresholdPercent) {
                    Bottleneck bottleneck = new Bottleneck(
                        "system",
                        "cpu",
                        "High CPU usage",
                        "System CPU usage of " + String.format("%.2f", cpuUsage) + "% exceeds threshold of " + cpuThresholdPercent + "%",
                        calculateSeverity(cpuUsage, cpuThresholdPercent),
                        Bottleneck.BottleneckType.CPU
                    );
                    
                    bottlenecks.add(bottleneck);
                }
            });
    }
    
    /**
     * Detects memory bottlenecks.
     *
     * @param metrics The metrics to analyze
     * @param bottlenecks The list to add detected bottlenecks to
     */
    private void detectMemoryBottlenecks(List<PerformanceMetric> metrics, List<Bottleneck> bottlenecks) {
        // Find memory usage metrics
        List<PerformanceMetric> memoryMetrics = metrics.stream()
            .filter(m -> m.getType() == PerformanceMetric.MetricType.MEMORY_USAGE || 
                   (m.getType() == PerformanceMetric.MetricType.CUSTOM && m.getName().contains("utilization")))
            .collect(Collectors.toList());
        
        // Check heap utilization
        memoryMetrics.stream()
            .filter(m -> m.getComponent().equals("jvm") && m.getName().equals("heap.utilization"))
            .findFirst()
            .ifPresent(metric -> {
                double heapUtilization = metric.getValue();
                
                if (heapUtilization > memoryThresholdPercent) {
                    Bottleneck bottleneck = new Bottleneck(
                        "jvm",
                        "memory",
                        "High heap utilization",
                        "Heap utilization of " + String.format("%.2f", heapUtilization) + "% exceeds threshold of " + memoryThresholdPercent + "%",
                        calculateSeverity(heapUtilization, memoryThresholdPercent),
                        Bottleneck.BottleneckType.MEMORY
                    );
                    
                    bottlenecks.add(bottleneck);
                }
            });
    }
    
    /**
     * Detects I/O bottlenecks.
     *
     * @param metrics The metrics to analyze
     * @param bottlenecks The list to add detected bottlenecks to
     */
    private void detectIoBottlenecks(List<PerformanceMetric> metrics, List<Bottleneck> bottlenecks) {
        // Find I/O rate metrics
        List<PerformanceMetric> ioMetrics = metrics.stream()
            .filter(m -> m.getType() == PerformanceMetric.MetricType.IO_RATE)
            .collect(Collectors.toList());
        
        // Check each I/O metric
        for (PerformanceMetric metric : ioMetrics) {
            double ioRate = metric.getValue();
            
            if (ioRate > ioThresholdOpsPerSec) {
                Bottleneck bottleneck = new Bottleneck(
                    metric.getComponent(),
                    metric.getOperation(),
                    "High I/O rate",
                    "I/O rate of " + String.format("%.2f", ioRate) + " ops/sec exceeds threshold of " + ioThresholdOpsPerSec + " ops/sec",
                    calculateSeverity(ioRate, ioThresholdOpsPerSec),
                    Bottleneck.BottleneckType.IO
                );
                
                bottlenecks.add(bottleneck);
            }
        }
    }
    
    /**
     * Detects error rate bottlenecks.
     *
     * @param metrics The metrics to analyze
     * @param bottlenecks The list to add detected bottlenecks to
     */
    private void detectErrorRateBottlenecks(List<PerformanceMetric> metrics, List<Bottleneck> bottlenecks) {
        // Find error rate metrics
        List<PerformanceMetric> errorMetrics = metrics.stream()
            .filter(m -> m.getType() == PerformanceMetric.MetricType.ERROR_RATE)
            .collect(Collectors.toList());
        
        // Check each error rate metric
        for (PerformanceMetric metric : errorMetrics) {
            double errorRate = metric.getValue();
            
            if (errorRate > errorThresholdPercent) {
                Bottleneck bottleneck = new Bottleneck(
                    metric.getComponent(),
                    metric.getOperation(),
                    "High error rate",
                    "Error rate of " + String.format("%.2f", errorRate) + "% exceeds threshold of " + errorThresholdPercent + "%",
                    calculateSeverity(errorRate, errorThresholdPercent),
                    Bottleneck.BottleneckType.ERROR
                );
                
                bottlenecks.add(bottleneck);
            }
        }
    }
    
    /**
     * Calculates the severity of a bottleneck based on how much it exceeds the threshold.
     *
     * @param value The value to check
     * @param threshold The threshold value
     * @return The severity (0.0-1.0)
     */
    private double calculateSeverity(double value, double threshold) {
        if (value <= threshold) {
            return 0.0;
        }
        
        // Calculate severity based on how much the value exceeds the threshold
        double exceedFactor = value / threshold;
        
        // Cap severity at 1.0
        return Math.min(1.0, (exceedFactor - 1.0) / 2.0);
    }
    
    /**
     * Analyzes a specific bottleneck to provide more detailed information.
     *
     * @param bottleneck The bottleneck to analyze
     * @return The analysis result
     */
    public BottleneckAnalysisResult analyzeBottleneck(Bottleneck bottleneck) {
        List<String> insights = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();
        
        // Add basic insights based on bottleneck type
        switch (bottleneck.getType()) {
            case LATENCY:
                insights.add("High latency can be caused by CPU contention, memory pressure, or I/O bottlenecks.");
                insights.add("Consider optimizing the code or increasing resources for this component.");
                break;
                
            case CPU:
                insights.add("High CPU usage can be caused by inefficient algorithms or insufficient CPU resources.");
                insights.add("Consider optimizing the code or increasing CPU resources.");
                break;
                
            case MEMORY:
                insights.add("High memory usage can be caused by memory leaks or insufficient memory resources.");
                insights.add("Consider optimizing memory usage or increasing memory resources.");
                break;
                
            case IO:
                insights.add("High I/O rate can be caused by inefficient I/O operations or insufficient I/O resources.");
                insights.add("Consider optimizing I/O operations or increasing I/O resources.");
                break;
                
            case ERROR:
                insights.add("High error rate can indicate bugs or system failures.");
                insights.add("Investigate the errors and fix the underlying issues.");
                break;
        }
        
        return new BottleneckAnalysisResult(bottleneck, insights, details);
    }
    
    /**
     * Checks if CPU usage is high.
     *
     * @return True if CPU usage is high, false otherwise
     */
    private boolean isCpuUsageHigh() {
        // Get CPU metrics
        List<PerformanceMetric> cpuMetrics = profiler.getAllMetrics().stream()
            .filter(m -> m.getType() == PerformanceMetric.MetricType.CPU_USAGE)
            .collect(Collectors.toList());
        
        // Check if any CPU metric exceeds the threshold
        return cpuMetrics.stream()
            .anyMatch(m -> m.getValue() > cpuThresholdPercent);
    }
    
    /**
     * Checks if memory usage is high.
     *
     * @return True if memory usage is high, false otherwise
     */
    private boolean isMemoryUsageHigh() {
        // Get memory metrics
        List<PerformanceMetric> memoryMetrics = profiler.getAllMetrics().stream()
            .filter(m -> m.getType() == PerformanceMetric.MetricType.MEMORY_USAGE || 
                   (m.getType() == PerformanceMetric.MetricType.CUSTOM && m.getName().contains("utilization")))
            .collect(Collectors.toList());
        
        // Check if any memory metric exceeds the threshold
        return memoryMetrics.stream()
            .anyMatch(m -> m.getValue() > memoryThresholdPercent);
    }
    
    /**
     * Checks if thread count is high.
     *
     * @return True if thread count is high, false otherwise
     */
    private boolean isThreadCountHigh() {
        // Get thread metrics
        return profiler.getAllMetrics().stream()
            .filter(m -> m.getComponent().equals("jvm") && m.getOperation().equals("threads") && m.getName().equals("thread.count"))
            .findFirst()
            .map(m -> m.getValue() > 100) // Arbitrary threshold
            .orElse(false);
    }
    
    /**
     * Checks if GC activity is high.
     *
     * @return True if GC activity is high, false otherwise
     */
    private boolean isGcActivityHigh() {
        // Get GC metrics
        List<PerformanceMetric> gcMetrics = profiler.getAllMetrics().stream()
            .filter(m -> m.getComponent().equals("jvm") && m.getOperation().equals("gc"))
            .collect(Collectors.toList());
        
        // Check if there are any GC metrics
        return !gcMetrics.isEmpty();
    }
    
    /**
     * Calculates a percentile value for a list of metrics.
     *
     * @param metrics The metrics to analyze
     * @param percentile The percentile to calculate (0-100)
     * @return The percentile value
     */
    private double calculatePercentile(List<PerformanceMetric> metrics, int percentile) {
        if (metrics == null || metrics.isEmpty()) {
            return 0.0;
        }
        
        // Sort values
        List<Double> values = metrics.stream()
            .map(PerformanceMetric::getValue)
            .sorted()
            .collect(Collectors.toList());
        
        // Calculate index
        int index = (int) Math.ceil(percentile / 100.0 * values.size()) - 1;
        if (index < 0) {
            index = 0;
        }
        
        // Return percentile value
        return values.get(index);
    }
    
    /**
     * Calculates the standard deviation of a list of metrics.
     *
     * @param metrics The metrics to analyze
     * @return The standard deviation
     */
    private double calculateStandardDeviation(List<PerformanceMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return 0.0;
        }
        
        // Calculate mean
        double mean = metrics.stream()
            .mapToDouble(PerformanceMetric::getValue)
            .average()
            .orElse(0.0);
        
        // Calculate sum of squared differences
        double sumSquaredDiff = metrics.stream()
            .mapToDouble(m -> {
                double diff = m.getValue() - mean;
                return diff * diff;
            })
            .sum();
        
        // Calculate standard deviation
        return Math.sqrt(sumSquaredDiff / metrics.size());
    }
}