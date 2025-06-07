package com.ataiva.serengeti.performance.benchmark;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Detects performance regressions by comparing benchmark results with historical data.
 * This class provides methods for analyzing benchmark results and identifying
 * performance degradations that exceed specified thresholds.
 */
public class RegressionDetector {
    private static final Logger LOGGER = Logger.getLogger(RegressionDetector.class.getName());
    
    /**
     * Detects regressions by comparing a benchmark result with historical data.
     *
     * @param currentResult The current benchmark result
     * @param historyDir The directory containing historical benchmark results
     * @param threshold The regression threshold as a percentage (e.g., 10 for 10%)
     * @return A map of benchmark names to regression status (true if regression detected)
     */
    public static Map<String, Boolean> detectRegressions(BenchmarkResult currentResult, Path historyDir, double threshold) {
        try {
            // Find the most recent historical result for the same benchmark suite
            BenchmarkResult historicalResult = findMostRecentResult(historyDir, currentResult.getSuiteName());
            
            if (historicalResult == null) {
                LOGGER.info("No historical data found for benchmark suite: " + currentResult.getSuiteName());
                return new HashMap<>();
            }
            
            // Compare the current result with the historical result
            return currentResult.compareWith(historicalResult, threshold);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error detecting regressions", e);
            return new HashMap<>();
        }
    }
    
    /**
     * Finds the most recent benchmark result for a specific suite.
     *
     * @param historyDir The directory containing historical benchmark results
     * @param suiteName The name of the benchmark suite
     * @return The most recent benchmark result, or null if none found
     * @throws IOException if an I/O error occurs
     */
    private static BenchmarkResult findMostRecentResult(Path historyDir, String suiteName) throws IOException {
        if (!Files.exists(historyDir) || !Files.isDirectory(historyDir)) {
            return null;
        }
        
        // Find all JSON files for the specified suite
        List<Path> resultFiles = Files.list(historyDir)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".json"))
            .filter(path -> path.getFileName().toString().startsWith(suiteName + "-"))
            .collect(Collectors.toList());
        
        if (resultFiles.isEmpty()) {
            return null;
        }
        
        // Sort by file name (which includes timestamp) in descending order
        resultFiles.sort(Comparator.comparing(Path::getFileName).reversed());
        
        // Load the most recent result
        Path mostRecentFile = resultFiles.get(0);
        return loadBenchmarkResult(mostRecentFile);
    }
    
    /**
     * Loads a benchmark result from a JSON file.
     *
     * @param filePath The path to the JSON file
     * @return The benchmark result
     * @throws IOException if an I/O error occurs
     */
    private static BenchmarkResult loadBenchmarkResult(Path filePath) throws IOException {
        String content = new String(Files.readAllBytes(filePath));
        JSONObject json = new JSONObject(content);
        
        String suiteName = json.getString("suiteName");
        BenchmarkResult result = new BenchmarkResult(suiteName);
        
        // Load execution parameters
        JSONObject paramsJson = json.getJSONObject("executionParams");
        for (String key : paramsJson.keySet()) {
            result.setExecutionParam(key, paramsJson.get(key));
        }
        
        // Load metrics
        json.getJSONArray("metrics").forEach(obj -> {
            JSONObject metricJson = (JSONObject) obj;
            BenchmarkMetric metric = new BenchmarkMetric.Builder()
                .setBenchmarkName(metricJson.getString("benchmarkName"))
                .setMetricName(metricJson.getString("metricName"))
                .setValue(metricJson.getDouble("value"))
                .setUnit(metricJson.getString("unit"))
                .setError(metricJson.getDouble("error"))
                .setSamples(metricJson.getInt("samples"))
                .setType(BenchmarkMetric.MetricType.valueOf(metricJson.getString("type")))
                .build();
            
            result.addMetric(metric);
        });
        
        return result;
    }
    
    /**
     * Analyzes regression trends over time.
     *
     * @param historyDir The directory containing historical benchmark results
     * @param suiteName The name of the benchmark suite
     * @param metricName The name of the metric to analyze
     * @param limit The maximum number of historical results to analyze
     * @return A list of metric values over time
     * @throws IOException if an I/O error occurs
     */
    public static List<Double> analyzeRegressionTrend(Path historyDir, String suiteName, 
                                                     String benchmarkName, String metricName, int limit) throws IOException {
        if (!Files.exists(historyDir) || !Files.isDirectory(historyDir)) {
            return new ArrayList<>();
        }
        
        // Find all JSON files for the specified suite
        List<Path> resultFiles = Files.list(historyDir)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".json"))
            .filter(path -> path.getFileName().toString().startsWith(suiteName + "-"))
            .collect(Collectors.toList());
        
        if (resultFiles.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Sort by file name (which includes timestamp)
        resultFiles.sort(Comparator.comparing(Path::getFileName));
        
        // Limit the number of results
        if (resultFiles.size() > limit) {
            resultFiles = resultFiles.subList(resultFiles.size() - limit, resultFiles.size());
        }
        
        // Extract metric values from each result
        List<Double> values = new ArrayList<>();
        
        for (Path file : resultFiles) {
            try {
                BenchmarkResult result = loadBenchmarkResult(file);
                
                for (BenchmarkMetric metric : result.getMetrics()) {
                    if (metric.getBenchmarkName().equals(benchmarkName) && 
                        metric.getMetricName().equals(metricName)) {
                        values.add(metric.getValue());
                        break;
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error loading benchmark result from " + file, e);
            }
        }
        
        return values;
    }
    
    /**
     * Generates a regression report for a benchmark result.
     *
     * @param currentResult The current benchmark result
     * @param historyDir The directory containing historical benchmark results
     * @param threshold The regression threshold as a percentage
     * @return A report of detected regressions
     */
    public static String generateRegressionReport(BenchmarkResult currentResult, Path historyDir, double threshold) {
        Map<String, Boolean> regressions = detectRegressions(currentResult, historyDir, threshold);
        
        if (regressions.isEmpty()) {
            return "No historical data available for comparison.";
        }
        
        StringBuilder report = new StringBuilder();
        report.append("Regression Analysis Report\n");
        report.append("=========================\n\n");
        report.append("Benchmark Suite: ").append(currentResult.getSuiteName()).append("\n");
        report.append("Threshold: ").append(threshold).append("%\n\n");
        
        int regressionCount = 0;
        
        report.append("Results:\n");
        for (Map.Entry<String, Boolean> entry : regressions.entrySet()) {
            String status = entry.getValue() ? "REGRESSION" : "OK";
            if (entry.getValue()) {
                regressionCount++;
            }
            
            report.append(String.format("  %-50s %s\n", entry.getKey(), status));
        }
        
        report.append("\nSummary: ");
        if (regressionCount == 0) {
            report.append("No regressions detected.");
        } else {
            report.append(regressionCount).append(" regression(s) detected out of ")
                  .append(regressions.size()).append(" metrics.");
        }
        
        return report.toString();
    }
}