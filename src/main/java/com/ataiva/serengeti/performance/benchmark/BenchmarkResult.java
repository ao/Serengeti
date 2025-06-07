package com.ataiva.serengeti.performance.benchmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents the results of a benchmark execution.
 * This class stores performance metrics, execution details, and provides
 * methods for analyzing and persisting benchmark results.
 */
public class BenchmarkResult {
    private final String id;
    private final String suiteName;
    private final LocalDateTime executionTime;
    private final Map<String, Object> executionParams;
    private final List<BenchmarkMetric> metrics;
    private final Map<String, Map<String, List<BenchmarkMetric>>> parameterizedResults;
    private final Map<String, Object> systemInfo;
    
    /**
     * Creates a new benchmark result.
     *
     * @param suiteName The name of the benchmark suite
     */
    public BenchmarkResult(String suiteName) {
        this.id = UUID.randomUUID().toString();
        this.suiteName = suiteName;
        this.executionTime = LocalDateTime.now();
        this.executionParams = new HashMap<>();
        this.metrics = new ArrayList<>();
        this.parameterizedResults = new HashMap<>();
        this.systemInfo = collectSystemInfo();
    }
    
    /**
     * Gets the unique ID of this benchmark result.
     *
     * @return The benchmark result ID
     */
    public String getId() {
        return id;
    }
    
    /**
     * Gets the name of the benchmark suite.
     *
     * @return The benchmark suite name
     */
    public String getSuiteName() {
        return suiteName;
    }
    
    /**
     * Gets the execution time of the benchmark.
     *
     * @return The execution time
     */
    public LocalDateTime getExecutionTime() {
        return executionTime;
    }
    
    /**
     * Gets the execution parameters.
     *
     * @return A map of execution parameters
     */
    public Map<String, Object> getExecutionParams() {
        return new HashMap<>(executionParams);
    }
    
    /**
     * Sets an execution parameter.
     *
     * @param key The parameter key
     * @param value The parameter value
     */
    public void setExecutionParam(String key, Object value) {
        executionParams.put(key, value);
    }
    
    /**
     * Adds a benchmark metric.
     *
     * @param metric The benchmark metric to add
     */
    public void addMetric(BenchmarkMetric metric) {
        metrics.add(metric);
    }
    
    /**
     * Gets all benchmark metrics.
     *
     * @return A list of benchmark metrics
     */
    public List<BenchmarkMetric> getMetrics() {
        return new ArrayList<>(metrics);
    }
    
    /**
     * Adds a parameterized benchmark result.
     *
     * @param paramSetName The parameter set name
     * @param benchmarkName The benchmark name
     * @param metric The benchmark metric
     */
    public void addParameterizedResult(String paramSetName, String benchmarkName, BenchmarkMetric metric) {
        parameterizedResults
            .computeIfAbsent(paramSetName, k -> new HashMap<>())
            .computeIfAbsent(benchmarkName, k -> new ArrayList<>())
            .add(metric);
    }
    
    /**
     * Gets all parameterized benchmark results.
     *
     * @return A map of parameter set names to benchmark names to metrics
     */
    public Map<String, Map<String, List<BenchmarkMetric>>> getParameterizedResults() {
        return new HashMap<>(parameterizedResults);
    }
    
    /**
     * Gets the system information collected during benchmark execution.
     *
     * @return A map of system information
     */
    public Map<String, Object> getSystemInfo() {
        return new HashMap<>(systemInfo);
    }
    
    /**
     * Collects system information.
     *
     * @return A map of system information
     */
    private Map<String, Object> collectSystemInfo() {
        Map<String, Object> info = new HashMap<>();
        
        // Collect JVM information
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVendor", System.getProperty("java.vendor"));
        info.put("jvmName", System.getProperty("java.vm.name"));
        info.put("jvmVersion", System.getProperty("java.vm.version"));
        
        // Collect OS information
        info.put("osName", System.getProperty("os.name"));
        info.put("osVersion", System.getProperty("os.version"));
        info.put("osArch", System.getProperty("os.arch"));
        
        // Collect hardware information
        info.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        info.put("maxMemory", Runtime.getRuntime().maxMemory());
        
        return info;
    }
    
    /**
     * Saves the benchmark results to a file.
     *
     * @param directory The directory to save the file in
     * @param name The name of the file (without extension)
     * @param format The format to save in (json, csv, etc.)
     * @return The path to the saved file
     * @throws IOException if an I/O error occurs
     */
    public Path saveToFile(Path directory, String name, String format) throws IOException {
        // Create the directory if it doesn't exist
        Files.createDirectories(directory);
        
        // Format the file name with timestamp
        String timestamp = executionTime.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String fileName = name + "-" + timestamp + "." + format;
        Path filePath = directory.resolve(fileName);
        
        // Save in the appropriate format
        switch (format.toLowerCase()) {
            case "json":
                saveAsJson(filePath);
                break;
            case "csv":
                saveAsCsv(filePath);
                break;
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
        
        return filePath;
    }
    
    /**
     * Saves the benchmark results as JSON.
     *
     * @param filePath The path to save the file to
     * @throws IOException if an I/O error occurs
     */
    private void saveAsJson(Path filePath) throws IOException {
        JSONObject root = new JSONObject();
        
        // Add metadata
        root.put("id", id);
        root.put("suiteName", suiteName);
        root.put("executionTime", executionTime.toString());
        root.put("executionParams", new JSONObject(executionParams));
        root.put("systemInfo", new JSONObject(systemInfo));
        
        // Add metrics
        JSONArray metricsArray = new JSONArray();
        for (BenchmarkMetric metric : metrics) {
            metricsArray.put(metric.toJson());
        }
        root.put("metrics", metricsArray);
        
        // Add parameterized results
        JSONObject paramResults = new JSONObject();
        for (Map.Entry<String, Map<String, List<BenchmarkMetric>>> paramSetEntry : parameterizedResults.entrySet()) {
            JSONObject benchmarks = new JSONObject();
            
            for (Map.Entry<String, List<BenchmarkMetric>> benchmarkEntry : paramSetEntry.getValue().entrySet()) {
                JSONArray benchmarkMetrics = new JSONArray();
                
                for (BenchmarkMetric metric : benchmarkEntry.getValue()) {
                    benchmarkMetrics.put(metric.toJson());
                }
                
                benchmarks.put(benchmarkEntry.getKey(), benchmarkMetrics);
            }
            
            paramResults.put(paramSetEntry.getKey(), benchmarks);
        }
        root.put("parameterizedResults", paramResults);
        
        // Write to file
        Files.write(filePath, root.toString(2).getBytes(), StandardOpenOption.CREATE);
    }
    
    /**
     * Saves the benchmark results as CSV.
     *
     * @param filePath The path to save the file to
     * @throws IOException if an I/O error occurs
     */
    private void saveAsCsv(Path filePath) throws IOException {
        StringBuilder csv = new StringBuilder();
        
        // Add header
        csv.append("Benchmark,Metric,Value,Unit,Error,Samples\n");
        
        // Add metrics
        for (BenchmarkMetric metric : metrics) {
            csv.append(metric.getBenchmarkName()).append(",");
            csv.append(metric.getMetricName()).append(",");
            csv.append(metric.getValue()).append(",");
            csv.append(metric.getUnit()).append(",");
            csv.append(metric.getError()).append(",");
            csv.append(metric.getSamples()).append("\n");
        }
        
        // Add parameterized results
        for (Map.Entry<String, Map<String, List<BenchmarkMetric>>> paramSetEntry : parameterizedResults.entrySet()) {
            String paramSet = paramSetEntry.getKey();
            
            for (Map.Entry<String, List<BenchmarkMetric>> benchmarkEntry : paramSetEntry.getValue().entrySet()) {
                String benchmark = benchmarkEntry.getKey();
                
                for (BenchmarkMetric metric : benchmarkEntry.getValue()) {
                    csv.append(paramSet).append(".").append(benchmark).append(",");
                    csv.append(metric.getMetricName()).append(",");
                    csv.append(metric.getValue()).append(",");
                    csv.append(metric.getUnit()).append(",");
                    csv.append(metric.getError()).append(",");
                    csv.append(metric.getSamples()).append("\n");
                }
            }
        }
        
        // Write to file
        Files.write(filePath, csv.toString().getBytes(), StandardOpenOption.CREATE);
    }
    
    /**
     * Compares this benchmark result with another to detect regressions.
     *
     * @param other The other benchmark result to compare with
     * @param threshold The regression threshold as a percentage (e.g., 10 for 10%)
     * @return A map of benchmark names to regression status (true if regression detected)
     */
    public Map<String, Boolean> compareWith(BenchmarkResult other, double threshold) {
        Map<String, Boolean> regressions = new HashMap<>();
        
        // Create a map of benchmark names to metrics for the other result
        Map<String, BenchmarkMetric> otherMetrics = new HashMap<>();
        for (BenchmarkMetric metric : other.getMetrics()) {
            otherMetrics.put(metric.getBenchmarkName() + "." + metric.getMetricName(), metric);
        }
        
        // Compare metrics
        for (BenchmarkMetric metric : metrics) {
            String key = metric.getBenchmarkName() + "." + metric.getMetricName();
            BenchmarkMetric otherMetric = otherMetrics.get(key);
            
            if (otherMetric != null) {
                // Calculate percentage difference
                double diff = calculatePercentageDifference(metric.getValue(), otherMetric.getValue());
                
                // Check if the difference exceeds the threshold
                boolean isRegression = diff > threshold;
                
                // For latency and memory metrics, higher values are worse
                if (metric.getMetricName().toLowerCase().contains("latency") ||
                    metric.getMetricName().toLowerCase().contains("memory")) {
                    isRegression = metric.getValue() > otherMetric.getValue() * (1 + threshold / 100);
                } 
                // For throughput metrics, lower values are worse
                else if (metric.getMetricName().toLowerCase().contains("throughput") ||
                         metric.getMetricName().toLowerCase().contains("ops")) {
                    isRegression = metric.getValue() < otherMetric.getValue() * (1 - threshold / 100);
                }
                
                regressions.put(key, isRegression);
            }
        }
        
        return regressions;
    }
    
    /**
     * Calculates the percentage difference between two values.
     *
     * @param value1 The first value
     * @param value2 The second value
     * @return The percentage difference
     */
    private double calculatePercentageDifference(double value1, double value2) {
        if (value2 == 0) {
            return value1 == 0 ? 0 : 100;
        }
        
        return Math.abs((value1 - value2) / value2 * 100);
    }
}