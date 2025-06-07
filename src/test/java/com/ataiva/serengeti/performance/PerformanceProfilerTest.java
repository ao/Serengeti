package com.ataiva.serengeti.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PerformanceProfiler class.
 */
@DisplayName("Performance Profiler Tests")
public class PerformanceProfilerTest {

    private PerformanceProfiler profiler;

    @BeforeEach
    void setUp() {
        profiler = PerformanceProfiler.getInstance();
        profiler.clearMetrics();
        profiler.setEnabled(true);
    }

    @Test
    @DisplayName("Should record and retrieve metrics")
    void testRecordAndRetrieveMetrics() {
        // Record a test metric
        profiler.recordCustomMetric("test-component", "test-operation", "test-metric", 42.0, "units");
        
        // Retrieve all metrics
        List<PerformanceMetric> metrics = profiler.getAllMetrics();
        
        // Verify the metric was recorded
        assertFalse(metrics.isEmpty(), "Metrics list should not be empty");
        assertEquals(1, metrics.size(), "Should have one metric");
        
        PerformanceMetric metric = metrics.get(0);
        assertEquals("test-component", metric.getComponent(), "Component should match");
        assertEquals("test-operation", metric.getOperation(), "Operation should match");
        assertEquals("test-metric", metric.getName(), "Metric name should match");
        assertEquals(42.0, metric.getValue(), "Metric value should match");
        assertEquals("units", metric.getUnit(), "Metric unit should match");
        assertEquals(PerformanceMetric.MetricType.CUSTOM, metric.getType(), "Metric type should match");
    }

    @Test
    @DisplayName("Should filter metrics by component")
    void testFilterMetricsByComponent() {
        // Record metrics for different components
        profiler.recordCustomMetric("component1", "operation1", "metric1", 1.0, "units");
        profiler.recordCustomMetric("component2", "operation2", "metric2", 2.0, "units");
        profiler.recordCustomMetric("component1", "operation3", "metric3", 3.0, "units");
        
        // Filter by component
        List<PerformanceMetric> component1Metrics = profiler.getMetricsByComponent("component1");
        
        // Verify filtering
        assertEquals(2, component1Metrics.size(), "Should have two metrics for component1");
        for (PerformanceMetric metric : component1Metrics) {
            assertEquals("component1", metric.getComponent(), "Component should be component1");
        }
    }

    @Test
    @DisplayName("Should filter metrics by operation")
    void testFilterMetricsByOperation() {
        // Record metrics for different operations
        profiler.recordCustomMetric("component1", "operation1", "metric1", 1.0, "units");
        profiler.recordCustomMetric("component2", "operation2", "metric2", 2.0, "units");
        profiler.recordCustomMetric("component3", "operation1", "metric3", 3.0, "units");
        
        // Filter by operation
        List<PerformanceMetric> operation1Metrics = profiler.getMetricsByOperation("operation1");
        
        // Verify filtering
        assertEquals(2, operation1Metrics.size(), "Should have two metrics for operation1");
        for (PerformanceMetric metric : operation1Metrics) {
            assertEquals("operation1", metric.getOperation(), "Operation should be operation1");
        }
    }

    @Test
    @DisplayName("Should filter metrics by type")
    void testFilterMetricsByType() {
        // Record metrics of different types
        profiler.recordCustomMetric("component1", "operation1", "metric1", 1.0, "units");
        profiler.recordLatency("component2", "operation2", "latency", 100.0);
        profiler.recordMemoryUsage("component3", "operation3", "memory", 1024);
        
        // Filter by type
        List<PerformanceMetric> latencyMetrics = profiler.getMetricsByType(PerformanceMetric.MetricType.LATENCY);
        
        // Verify filtering
        assertEquals(1, latencyMetrics.size(), "Should have one latency metric");
        assertEquals(PerformanceMetric.MetricType.LATENCY, latencyMetrics.get(0).getType(), "Type should be LATENCY");
    }

    @Test
    @DisplayName("Should time operations correctly")
    void testTimingOperations() throws InterruptedException {
        // Start a timer
        String timerId = profiler.startTimer("component", "operation");
        
        // Simulate some work
        Thread.sleep(100);
        
        // Stop the timer
        double duration = profiler.stopTimer(timerId, "operation-time");
        
        // Verify timing
        assertTrue(duration >= 100.0, "Duration should be at least 100ms");
        
        // Check that a metric was recorded
        List<PerformanceMetric> metrics = profiler.getMetricsByName("operation-time");
        assertEquals(1, metrics.size(), "Should have one timing metric");
        assertEquals(PerformanceMetric.MetricType.LATENCY, metrics.get(0).getType(), "Type should be LATENCY");
        assertTrue(metrics.get(0).getValue() >= 100.0, "Recorded duration should be at least 100ms");
    }

    @Test
    @DisplayName("Should generate metric summaries")
    void testMetricSummaries() {
        // Record multiple latency metrics for the same operation
        profiler.recordLatency("component", "operation", "latency", 100.0);
        profiler.recordLatency("component", "operation", "latency", 200.0);
        profiler.recordLatency("component", "operation", "latency", 300.0);
        
        // Get summary
        Map<String, Map<String, Map<String, Object>>> summary = profiler.getMetricsSummary();
        
        // Verify summary
        assertTrue(summary.containsKey("component"), "Summary should contain component");
        assertTrue(summary.get("component").containsKey("operation"), "Component should contain operation");
        assertTrue(summary.get("component").get("operation").containsKey("latency"), "Operation should contain latency");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> latencySummary = (Map<String, Object>) summary.get("component").get("operation").get("latency");
        
        assertEquals(100.0, (double) latencySummary.get("min"), "Min should be 100.0");
        assertEquals(300.0, (double) latencySummary.get("max"), "Max should be 300.0");
        assertEquals(200.0, (double) latencySummary.get("avg"), "Avg should be 200.0");
        assertEquals(3, (int) latencySummary.get("count"), "Count should be 3");
    }

    @Test
    @DisplayName("Should notify listeners of new metrics")
    void testMetricListeners() throws InterruptedException {
        // Create a latch to wait for notification
        CountDownLatch latch = new CountDownLatch(1);
        
        // Create a listener
        Consumer<PerformanceMetric> listener = metric -> {
            if ("test-metric".equals(metric.getName())) {
                latch.countDown();
            }
        };
        
        // Register the listener
        profiler.addListener(listener);
        
        try {
            // Record a metric
            profiler.recordCustomMetric("component", "operation", "test-metric", 42.0, "units");
            
            // Wait for notification
            boolean notified = latch.await(1, TimeUnit.SECONDS);
            
            // Verify notification
            assertTrue(notified, "Listener should have been notified");
        } finally {
            // Clean up
            profiler.removeListener(listener);
        }
    }

    @Test
    @DisplayName("Should respect enabled/disabled state")
    void testEnabledDisabled() {
        // Disable the profiler
        profiler.setEnabled(false);
        
        // Record a metric
        profiler.recordCustomMetric("component", "operation", "metric", 42.0, "units");
        
        // Verify no metrics were recorded
        List<PerformanceMetric> metrics = profiler.getAllMetrics();
        assertTrue(metrics.isEmpty(), "No metrics should be recorded when disabled");
        
        // Re-enable the profiler
        profiler.setEnabled(true);
        
        // Record another metric
        profiler.recordCustomMetric("component", "operation", "metric", 42.0, "units");
        
        // Verify the metric was recorded
        metrics = profiler.getAllMetrics();
        assertEquals(1, metrics.size(), "Metric should be recorded when enabled");
    }

    @Test
    @DisplayName("Should enforce retention limit")
    void testRetentionLimit() {
        // Set a small retention limit
        profiler.setRetentionLimit(5);
        
        // Record more metrics than the limit
        for (int i = 0; i < 10; i++) {
            profiler.recordCustomMetric("component", "operation", "metric" + i, i, "units");
        }
        
        // Verify only the most recent metrics are retained
        List<PerformanceMetric> metrics = profiler.getAllMetrics();
        assertEquals(5, metrics.size(), "Should retain only 5 metrics");
        
        // Verify the retained metrics are the most recent ones
        for (int i = 0; i < 5; i++) {
            assertEquals("metric" + (i + 5), metrics.get(i).getName(), "Should retain metrics 5-9");
        }
    }
}