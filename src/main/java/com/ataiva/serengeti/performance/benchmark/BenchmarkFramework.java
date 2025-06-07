package com.ataiva.serengeti.performance.benchmark;

import com.ataiva.serengeti.performance.PerformanceProfiler;
import com.ataiva.serengeti.performance.PerformanceVisualizer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central framework for managing and executing benchmarks across the Serengeti system.
 * This class provides methods for registering, configuring, and running benchmarks,
 * as well as analyzing and reporting benchmark results.
 */
public class BenchmarkFramework {
    private static final Logger LOGGER = Logger.getLogger(BenchmarkFramework.class.getName());
    
    // Singleton instance
    private static final BenchmarkFramework INSTANCE = new BenchmarkFramework();
    
    // References to other performance components
    private final PerformanceProfiler profiler;
    private final PerformanceVisualizer visualizer;
    
    // Registry of available benchmarks
    private final Map<String, BenchmarkSuite> benchmarkSuites = new ConcurrentHashMap<>();
    
    // Default output directory for benchmark results
    private Path outputDirectory = Paths.get("benchmark-results");
    
    // Configuration settings
    private final Map<String, Object> globalConfig = new HashMap<>();
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private BenchmarkFramework() {
        this.profiler = PerformanceProfiler.getInstance();
        this.visualizer = PerformanceVisualizer.getInstance();
        
        // Set default configuration
        globalConfig.put("warmupIterations", 3);
        globalConfig.put("measurementIterations", 5);
        globalConfig.put("forks", 1);
        globalConfig.put("threads", Runtime.getRuntime().availableProcessors());
        globalConfig.put("timeUnit", "ms");
        globalConfig.put("reportFormat", "json");
        
        LOGGER.info("Benchmark Framework initialized");
    }
    
    /**
     * Gets the singleton instance of the benchmark framework.
     *
     * @return The benchmark framework instance
     */
    public static BenchmarkFramework getInstance() {
        return INSTANCE;
    }
    
    /**
     * Sets the output directory for benchmark results.
     *
     * @param directory The output directory path
     */
    public void setOutputDirectory(Path directory) {
        this.outputDirectory = directory;
        LOGGER.info("Benchmark output directory set to: " + directory);
    }
    
    /**
     * Gets the current output directory for benchmark results.
     *
     * @return The output directory path
     */
    public Path getOutputDirectory() {
        return outputDirectory;
    }
    
    /**
     * Sets a global configuration parameter.
     *
     * @param key The configuration key
     * @param value The configuration value
     */
    public void setConfig(String key, Object value) {
        globalConfig.put(key, value);
        LOGGER.fine("Set global config: " + key + " = " + value);
    }
    
    /**
     * Gets a global configuration parameter.
     *
     * @param key The configuration key
     * @return The configuration value, or null if not set
     */
    public Object getConfig(String key) {
        return globalConfig.get(key);
    }
    
    /**
     * Gets a global configuration parameter with a default value.
     *
     * @param key The configuration key
     * @param defaultValue The default value to return if the key is not set
     * @return The configuration value, or the default value if not set
     */
    public Object getConfig(String key, Object defaultValue) {
        return globalConfig.getOrDefault(key, defaultValue);
    }
    
    /**
     * Registers a benchmark suite with the framework.
     *
     * @param name The name of the benchmark suite
     * @param suite The benchmark suite instance
     */
    public void registerBenchmarkSuite(String name, BenchmarkSuite suite) {
        benchmarkSuites.put(name, suite);
        LOGGER.info("Registered benchmark suite: " + name);
    }
    
    /**
     * Gets a registered benchmark suite by name.
     *
     * @param name The name of the benchmark suite
     * @return The benchmark suite, or null if not found
     */
    public BenchmarkSuite getBenchmarkSuite(String name) {
        return benchmarkSuites.get(name);
    }
    
    /**
     * Gets all registered benchmark suites.
     *
     * @return A map of benchmark suite names to instances
     */
    public Map<String, BenchmarkSuite> getAllBenchmarkSuites() {
        return new HashMap<>(benchmarkSuites);
    }
    
    /**
     * Runs a specific benchmark suite.
     *
     * @param name The name of the benchmark suite to run
     * @return The benchmark results
     * @throws IllegalArgumentException if the benchmark suite is not found
     */
    public BenchmarkResult runBenchmark(String name) {
        BenchmarkSuite suite = benchmarkSuites.get(name);
        if (suite == null) {
            throw new IllegalArgumentException("Benchmark suite not found: " + name);
        }
        
        LOGGER.info("Running benchmark suite: " + name);
        
        try {
            // Apply global configuration to the suite
            suite.configure(globalConfig);
            
            // Run the benchmark
            BenchmarkResult result = suite.run();
            
            // Save the results
            saveResults(name, result);
            
            return result;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error running benchmark suite: " + name, e);
            throw new RuntimeException("Benchmark execution failed", e);
        }
    }
    
    /**
     * Runs all registered benchmark suites.
     *
     * @return A map of benchmark suite names to results
     */
    public Map<String, BenchmarkResult> runAllBenchmarks() {
        LOGGER.info("Running all benchmark suites");
        
        Map<String, BenchmarkResult> results = new HashMap<>();
        
        for (String name : benchmarkSuites.keySet()) {
            try {
                BenchmarkResult result = runBenchmark(name);
                results.put(name, result);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error running benchmark suite: " + name, e);
            }
        }
        
        return results;
    }
    
    /**
     * Saves benchmark results to the output directory.
     *
     * @param name The name of the benchmark suite
     * @param result The benchmark results
     */
    private void saveResults(String name, BenchmarkResult result) {
        try {
            String format = (String) globalConfig.getOrDefault("reportFormat", "json");
            Path resultPath = result.saveToFile(outputDirectory, name, format);
            LOGGER.info("Benchmark results saved to: " + resultPath);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error saving benchmark results", e);
        }
    }
    
    /**
     * Generates a comprehensive report of all benchmark results.
     *
     * @return The path to the generated report
     */
    public Path generateReport() {
        try {
            // Use the visualizer to generate a report
            return visualizer.generateBenchmarkReport(outputDirectory);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error generating benchmark report", e);
            throw new RuntimeException("Report generation failed", e);
        }
    }
    
    /**
     * Compares benchmark results with historical data to detect regressions.
     *
     * @param result The current benchmark result
     * @param threshold The regression threshold as a percentage (e.g., 10 for 10%)
     * @return A map of benchmark names to regression status (true if regression detected)
     */
    public Map<String, Boolean> detectRegressions(BenchmarkResult result, double threshold) {
        // Implementation will be added in the RegressionDetector class
        return RegressionDetector.detectRegressions(result, outputDirectory, threshold);
    }
}