package com.ataiva.serengeti.performance.benchmark;

import org.json.JSONObject;

import java.time.LocalDateTime;

/**
 * Represents a single metric collected during benchmark execution.
 * This class stores the metric name, value, unit, and other metadata.
 */
public class BenchmarkMetric {
    private final String benchmarkName;
    private final String metricName;
    private final double value;
    private final String unit;
    private final double error;
    private final int samples;
    private final LocalDateTime timestamp;
    private final MetricType type;
    
    /**
     * Enum representing the type of benchmark metric.
     */
    public enum MetricType {
        THROUGHPUT,   // Operations per unit time
        LATENCY,      // Time taken to complete an operation
        MEMORY_USAGE, // Memory consumption
        CPU_USAGE,    // CPU utilization
        IO_RATE,      // I/O operations per unit time
        ERROR_RATE,   // Errors per unit time
        CUSTOM        // Custom metric type
    }
    
    /**
     * Builder for creating BenchmarkMetric instances.
     */
    public static class Builder {
        private String benchmarkName;
        private String metricName;
        private double value;
        private String unit = "";
        private double error = 0.0;
        private int samples = 1;
        private LocalDateTime timestamp = LocalDateTime.now();
        private MetricType type = MetricType.CUSTOM;
        
        /**
         * Sets the benchmark name.
         *
         * @param benchmarkName The benchmark name
         * @return This builder
         */
        public Builder setBenchmarkName(String benchmarkName) {
            this.benchmarkName = benchmarkName;
            return this;
        }
        
        /**
         * Sets the metric name.
         *
         * @param metricName The metric name
         * @return This builder
         */
        public Builder setMetricName(String metricName) {
            this.metricName = metricName;
            return this;
        }
        
        /**
         * Sets the metric value.
         *
         * @param value The metric value
         * @return This builder
         */
        public Builder setValue(double value) {
            this.value = value;
            return this;
        }
        
        /**
         * Sets the metric unit.
         *
         * @param unit The metric unit
         * @return This builder
         */
        public Builder setUnit(String unit) {
            this.unit = unit;
            return this;
        }
        
        /**
         * Sets the metric error.
         *
         * @param error The metric error
         * @return This builder
         */
        public Builder setError(double error) {
            this.error = error;
            return this;
        }
        
        /**
         * Sets the number of samples.
         *
         * @param samples The number of samples
         * @return This builder
         */
        public Builder setSamples(int samples) {
            this.samples = samples;
            return this;
        }
        
        /**
         * Sets the timestamp.
         *
         * @param timestamp The timestamp
         * @return This builder
         */
        public Builder setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        /**
         * Sets the metric type.
         *
         * @param type The metric type
         * @return This builder
         */
        public Builder setType(MetricType type) {
            this.type = type;
            return this;
        }
        
        /**
         * Builds a new BenchmarkMetric instance.
         *
         * @return A new BenchmarkMetric instance
         */
        public BenchmarkMetric build() {
            if (benchmarkName == null || benchmarkName.isEmpty()) {
                throw new IllegalStateException("Benchmark name is required");
            }
            if (metricName == null || metricName.isEmpty()) {
                throw new IllegalStateException("Metric name is required");
            }
            
            return new BenchmarkMetric(this);
        }
    }
    
    /**
     * Private constructor used by the Builder.
     *
     * @param builder The builder
     */
    private BenchmarkMetric(Builder builder) {
        this.benchmarkName = builder.benchmarkName;
        this.metricName = builder.metricName;
        this.value = builder.value;
        this.unit = builder.unit;
        this.error = builder.error;
        this.samples = builder.samples;
        this.timestamp = builder.timestamp;
        this.type = builder.type;
    }
    
    /**
     * Gets the benchmark name.
     *
     * @return The benchmark name
     */
    public String getBenchmarkName() {
        return benchmarkName;
    }
    
    /**
     * Gets the metric name.
     *
     * @return The metric name
     */
    public String getMetricName() {
        return metricName;
    }
    
    /**
     * Gets the metric value.
     *
     * @return The metric value
     */
    public double getValue() {
        return value;
    }
    
    /**
     * Gets the metric unit.
     *
     * @return The metric unit
     */
    public String getUnit() {
        return unit;
    }
    
    /**
     * Gets the metric error.
     *
     * @return The metric error
     */
    public double getError() {
        return error;
    }
    
    /**
     * Gets the number of samples.
     *
     * @return The number of samples
     */
    public int getSamples() {
        return samples;
    }
    
    /**
     * Gets the timestamp.
     *
     * @return The timestamp
     */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    /**
     * Gets the metric type.
     *
     * @return The metric type
     */
    public MetricType getType() {
        return type;
    }
    
    /**
     * Converts this metric to a JSON object.
     *
     * @return A JSON object representing this metric
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("benchmarkName", benchmarkName);
        json.put("metricName", metricName);
        json.put("value", value);
        json.put("unit", unit);
        json.put("error", error);
        json.put("samples", samples);
        json.put("timestamp", timestamp.toString());
        json.put("type", type.name());
        return json;
    }
    
    @Override
    public String toString() {
        return String.format("%s.%s: %.3f %s (±%.3f, %d samples)",
            benchmarkName, metricName, value, unit, error, samples);
    }
}