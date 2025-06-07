package com.ataiva.serengeti.performance;

import java.util.concurrent.TimeUnit;

/**
 * Represents a single performance metric with a name, value, and unit.
 * This is used by the performance profiling framework to track various
 * performance indicators across the system.
 */
public class PerformanceMetric {
    private final String name;
    private final double value;
    private final String unit;
    private final MetricType type;
    private final long timestamp;
    private final String component;
    private final String operation;

    /**
     * Enum representing the type of metric being recorded.
     */
    public enum MetricType {
        LATENCY,      // Time taken to complete an operation
        THROUGHPUT,   // Operations per unit time
        MEMORY_USAGE, // Memory consumption
        CPU_USAGE,    // CPU utilization
        IO_RATE,      // I/O operations per unit time
        ERROR_RATE,   // Errors per unit time
        CUSTOM        // Custom metric type
    }

    /**
     * Creates a new performance metric.
     *
     * @param name      The name of the metric
     * @param value     The value of the metric
     * @param unit      The unit of measurement
     * @param type      The type of metric
     * @param component The component being measured
     * @param operation The operation being measured
     */
    public PerformanceMetric(String name, double value, String unit, MetricType type, 
                            String component, String operation) {
        this.name = name;
        this.value = value;
        this.unit = unit;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.component = component;
        this.operation = operation;
    }

    /**
     * Gets the name of the metric.
     *
     * @return The name of the metric
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the value of the metric.
     *
     * @return The value of the metric
     */
    public double getValue() {
        return value;
    }

    /**
     * Gets the unit of measurement.
     *
     * @return The unit of measurement
     */
    public String getUnit() {
        return unit;
    }

    /**
     * Gets the type of metric.
     *
     * @return The type of metric
     */
    public MetricType getType() {
        return type;
    }

    /**
     * Gets the timestamp when the metric was recorded.
     *
     * @return The timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the component being measured.
     *
     * @return The component name
     */
    public String getComponent() {
        return component;
    }

    /**
     * Gets the operation being measured.
     *
     * @return The operation name
     */
    public String getOperation() {
        return operation;
    }

    @Override
    public String toString() {
        return String.format("%s.%s.%s: %.2f %s (%s)", 
            component, operation, name, value, unit, type);
    }
}