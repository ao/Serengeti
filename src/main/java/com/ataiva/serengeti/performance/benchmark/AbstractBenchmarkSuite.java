package com.ataiva.serengeti.performance.benchmark;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class for benchmark suites.
 * This class provides common functionality for benchmark suites,
 * making it easier to implement specific benchmark suites.
 */
public abstract class AbstractBenchmarkSuite implements BenchmarkSuite {
    private static final Logger LOGGER = Logger.getLogger(AbstractBenchmarkSuite.class.getName());
    
    protected final String name;
    protected final String description;
    protected final Map<String, Object> config = new HashMap<>();
    protected final Map<String, Map<String, Object>> parameterSets = new HashMap<>();
    
    /**
     * Creates a new abstract benchmark suite.
     *
     * @param name The name of the benchmark suite
     * @param description The description of the benchmark suite
     */
    protected AbstractBenchmarkSuite(String name, String description) {
        this.name = name;
        this.description = description;
        
        // Set default configuration
        config.put("warmupIterations", 3);
        config.put("measurementIterations", 5);
        config.put("forks", 1);
        config.put("threads", Runtime.getRuntime().availableProcessors());
        config.put("timeUnit", "ms");
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    @Override
    public void configure(Map<String, Object> config) {
        if (config != null) {
            this.config.putAll(config);
        }
    }
    
    @Override
    public Map<String, Map<String, Object>> getParameterSets() {
        return new HashMap<>(parameterSets);
    }
    
    @Override
    public void setParameterSets(Map<String, Map<String, Object>> parameterSets) {
        this.parameterSets.clear();
        if (parameterSets != null) {
            this.parameterSets.putAll(parameterSets);
        }
    }
    
    /**
     * Gets a configuration parameter.
     *
     * @param key The configuration key
     * @return The configuration value, or null if not set
     */
    protected Object getConfig(String key) {
        return config.get(key);
    }
    
    /**
     * Gets a configuration parameter with a default value.
     *
     * @param key The configuration key
     * @param defaultValue The default value to return if the key is not set
     * @return The configuration value, or the default value if not set
     */
    protected Object getConfig(String key, Object defaultValue) {
        return config.getOrDefault(key, defaultValue);
    }
    
    /**
     * Gets an integer configuration parameter.
     *
     * @param key The configuration key
     * @param defaultValue The default value to return if the key is not set or not an integer
     * @return The configuration value as an integer
     */
    protected int getIntConfig(String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING, "Invalid integer configuration value: " + key + " = " + value);
            }
        }
        return defaultValue;
    }
    
    /**
     * Gets a double configuration parameter.
     *
     * @param key The configuration key
     * @param defaultValue The default value to return if the key is not set or not a double
     * @return The configuration value as a double
     */
    protected double getDoubleConfig(String key, double defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING, "Invalid double configuration value: " + key + " = " + value);
            }
        }
        return defaultValue;
    }
    
    /**
     * Gets a boolean configuration parameter.
     *
     * @param key The configuration key
     * @param defaultValue The default value to return if the key is not set or not a boolean
     * @return The configuration value as a boolean
     */
    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }
    
    /**
     * Gets a string configuration parameter.
     *
     * @param key The configuration key
     * @param defaultValue The default value to return if the key is not set
     * @return The configuration value as a string
     */
    protected String getStringConfig(String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    /**
     * Runs the benchmark with the specified parameter set.
     *
     * @param paramSetName The parameter set name
     * @param params The parameter values
     * @return The benchmark result
     * @throws Exception if an error occurs during benchmark execution
     */
    protected abstract BenchmarkResult runWithParams(String paramSetName, Map<String, Object> params) throws Exception;
    
    @Override
    public BenchmarkResult run() throws Exception {
        LOGGER.info("Running benchmark suite: " + name);
        
        BenchmarkResult result = new BenchmarkResult(name);
        
        // Add configuration to the result
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            result.setExecutionParam(entry.getKey(), entry.getValue());
        }
        
        // Run with default parameters if no parameter sets are defined
        if (parameterSets.isEmpty()) {
            LOGGER.info("Running with default parameters");
            runWithParams("default", new HashMap<>());
            return result;
        }
        
        // Run with each parameter set
        for (Map.Entry<String, Map<String, Object>> entry : parameterSets.entrySet()) {
            String paramSetName = entry.getKey();
            Map<String, Object> params = entry.getValue();
            
            LOGGER.info("Running with parameter set: " + paramSetName);
            
            try {
                BenchmarkResult paramResult = runWithParams(paramSetName, params);
                
                // Add metrics from parameter result to the main result
                for (BenchmarkMetric metric : paramResult.getMetrics()) {
                    result.addParameterizedResult(paramSetName, metric.getBenchmarkName(), metric);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error running benchmark with parameter set: " + paramSetName, e);
            }
        }
        
        return result;
    }
}