package com.ataiva.serengeti.performance;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Configuration class for the Performance Profiling Framework.
 * This class provides centralized configuration for all profiling components,
 * allowing for fine-tuned control over profiling behavior in different environments.
 */
public class ProfilingConfiguration {
    // Singleton instance
    private static final ProfilingConfiguration INSTANCE = new ProfilingConfiguration();
    
    // Default values
    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_RETENTION_LIMIT = 10000;
    private static final int DEFAULT_SAMPLING_RATE = 100; // 100%
    private static final int DEFAULT_COLLECTION_INTERVAL_SECONDS = 60;
    private static final int DEFAULT_REPORT_INTERVAL_MINUTES = 15;
    private static final ProfilingLevel DEFAULT_PROFILING_LEVEL = ProfilingLevel.DEVELOPMENT;
    
    // Configuration properties
    private boolean enabled;
    private int retentionLimit;
    private int samplingRate; // 1-100 percentage
    private int collectionIntervalSeconds;
    private int reportIntervalMinutes;
    private ProfilingLevel profilingLevel;
    private Map<String, Object> additionalSettings;
    
    /**
     * Profiling levels for different environments.
     */
    public enum ProfilingLevel {
        /**
         * Comprehensive profiling suitable for development environments.
         * Collects detailed metrics with high frequency.
         */
        DEVELOPMENT,
        
        /**
         * Balanced profiling suitable for testing environments.
         * Collects important metrics with moderate frequency.
         */
        TESTING,
        
        /**
         * Minimal profiling suitable for production environments.
         * Collects only critical metrics with low frequency and sampling.
         */
        PRODUCTION,
        
        /**
         * Custom profiling with user-defined settings.
         */
        CUSTOM
    }
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private ProfilingConfiguration() {
        // Initialize with default settings
        reset();
    }
    
    /**
     * Gets the singleton instance of the profiling configuration.
     *
     * @return The profiling configuration instance
     */
    public static ProfilingConfiguration getInstance() {
        return INSTANCE;
    }
    
    /**
     * Resets all configuration properties to their default values.
     */
    public void reset() {
        enabled = DEFAULT_ENABLED;
        retentionLimit = DEFAULT_RETENTION_LIMIT;
        samplingRate = DEFAULT_SAMPLING_RATE;
        collectionIntervalSeconds = DEFAULT_COLLECTION_INTERVAL_SECONDS;
        reportIntervalMinutes = DEFAULT_REPORT_INTERVAL_MINUTES;
        profilingLevel = DEFAULT_PROFILING_LEVEL;
        additionalSettings = new HashMap<>();
    }
    
    /**
     * Applies a predefined profiling level.
     *
     * @param level The profiling level to apply
     */
    public void applyProfilingLevel(ProfilingLevel level) {
        this.profilingLevel = level;
        
        switch (level) {
            case DEVELOPMENT:
                enabled = true;
                retentionLimit = 10000;
                samplingRate = 100;
                collectionIntervalSeconds = 30;
                reportIntervalMinutes = 5;
                break;
                
            case TESTING:
                enabled = true;
                retentionLimit = 5000;
                samplingRate = 50;
                collectionIntervalSeconds = 60;
                reportIntervalMinutes = 15;
                break;
                
            case PRODUCTION:
                enabled = true;
                retentionLimit = 1000;
                samplingRate = 10;
                collectionIntervalSeconds = 300;
                reportIntervalMinutes = 60;
                break;
                
            case CUSTOM:
                // Keep current settings
                break;
        }
    }
    
    /**
     * Checks if profiling is enabled.
     *
     * @return True if profiling is enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Sets whether profiling is enabled.
     *
     * @param enabled True to enable profiling, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    /**
     * Gets the retention limit for metrics.
     *
     * @return The maximum number of metrics to retain
     */
    public int getRetentionLimit() {
        return retentionLimit;
    }
    
    /**
     * Sets the retention limit for metrics.
     *
     * @param retentionLimit The maximum number of metrics to retain
     */
    public void setRetentionLimit(int retentionLimit) {
        this.retentionLimit = retentionLimit;
    }
    
    /**
     * Gets the sampling rate for metrics.
     *
     * @return The sampling rate (1-100 percentage)
     */
    public int getSamplingRate() {
        return samplingRate;
    }
    
    /**
     * Sets the sampling rate for metrics.
     *
     * @param samplingRate The sampling rate (1-100 percentage)
     */
    public void setSamplingRate(int samplingRate) {
        if (samplingRate < 1) {
            this.samplingRate = 1;
        } else if (samplingRate > 100) {
            this.samplingRate = 100;
        } else {
            this.samplingRate = samplingRate;
        }
    }
    
    /**
     * Gets the collection interval for system metrics.
     *
     * @return The collection interval in seconds
     */
    public int getCollectionIntervalSeconds() {
        return collectionIntervalSeconds;
    }
    
    /**
     * Sets the collection interval for system metrics.
     *
     * @param collectionIntervalSeconds The collection interval in seconds
     */
    public void setCollectionIntervalSeconds(int collectionIntervalSeconds) {
        this.collectionIntervalSeconds = collectionIntervalSeconds;
    }
    
    /**
     * Gets the report generation interval.
     *
     * @return The report interval in minutes
     */
    public int getReportIntervalMinutes() {
        return reportIntervalMinutes;
    }
    
    /**
     * Sets the report generation interval.
     *
     * @param reportIntervalMinutes The report interval in minutes
     */
    public void setReportIntervalMinutes(int reportIntervalMinutes) {
        this.reportIntervalMinutes = reportIntervalMinutes;
    }
    
    /**
     * Gets the current profiling level.
     *
     * @return The profiling level
     */
    public ProfilingLevel getProfilingLevel() {
        return profilingLevel;
    }
    
    /**
     * Sets a custom profiling level.
     *
     * @param profilingLevel The profiling level
     */
    public void setProfilingLevel(ProfilingLevel profilingLevel) {
        this.profilingLevel = profilingLevel;
    }
    
    /**
     * Gets an additional setting.
     *
     * @param key The setting key
     * @return The setting value, or null if not found
     */
    public Object getAdditionalSetting(String key) {
        return additionalSettings.get(key);
    }
    
    /**
     * Sets an additional setting.
     *
     * @param key The setting key
     * @param value The setting value
     */
    public void setAdditionalSetting(String key, Object value) {
        additionalSettings.put(key, value);
    }
    
    /**
     * Determines if a metric should be sampled based on the current sampling rate.
     *
     * @return True if the metric should be sampled, false otherwise
     */
    public boolean shouldSample() {
        if (samplingRate >= 100) {
            return true;
        } else if (samplingRate <= 0) {
            return false;
        } else {
            return Math.random() * 100 < samplingRate;
        }
    }
    
    /**
     * Creates a configuration builder for fluent configuration.
     *
     * @return A new configuration builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder class for fluent configuration.
     */
    public static class Builder {
        private final ProfilingConfiguration config;
        
        private Builder() {
            config = getInstance();
        }
        
        public Builder enabled(boolean enabled) {
            config.setEnabled(enabled);
            return this;
        }
        
        public Builder retentionLimit(int limit) {
            config.setRetentionLimit(limit);
            return this;
        }
        
        public Builder samplingRate(int rate) {
            config.setSamplingRate(rate);
            return this;
        }
        
        public Builder collectionInterval(int interval, TimeUnit unit) {
            config.setCollectionIntervalSeconds((int) unit.toSeconds(interval));
            return this;
        }
        
        public Builder reportInterval(int interval, TimeUnit unit) {
            config.setReportIntervalMinutes((int) unit.toMinutes(interval));
            return this;
        }
        
        public Builder profilingLevel(ProfilingLevel level) {
            config.applyProfilingLevel(level);
            return this;
        }
        
        public Builder additionalSetting(String key, Object value) {
            config.setAdditionalSetting(key, value);
            return this;
        }
        
        public ProfilingConfiguration build() {
            return config;
        }
    }
}