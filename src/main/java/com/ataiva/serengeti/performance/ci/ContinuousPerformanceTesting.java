package com.ataiva.serengeti.performance.ci;

import com.ataiva.serengeti.performance.benchmark.BenchmarkFramework;
import com.ataiva.serengeti.performance.benchmark.BenchmarkResult;
import com.ataiva.serengeti.performance.benchmark.RegressionDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides integration with CI/CD pipelines for continuous performance testing.
 * This class enables automated performance testing as part of the build process,
 * with regression detection and reporting capabilities.
 */
public class ContinuousPerformanceTesting {
    private static final Logger LOGGER = Logger.getLogger(ContinuousPerformanceTesting.class.getName());
    
    private final BenchmarkFramework benchmarkFramework;
    private final Path historyDir;
    private final Path reportsDir;
    private final double regressionThreshold;
    private final Map<String, Object> ciConfig;
    
    /**
     * Creates a new continuous performance testing instance.
     *
     * @param historyDir The directory for storing historical benchmark results
     * @param reportsDir The directory for storing reports
     * @param regressionThreshold The threshold for detecting regressions (percentage)
     */
    public ContinuousPerformanceTesting(Path historyDir, Path reportsDir, double regressionThreshold) {
        this.benchmarkFramework = BenchmarkFramework.getInstance();
        this.historyDir = historyDir;
        this.reportsDir = reportsDir;
        this.regressionThreshold = regressionThreshold;
        this.ciConfig = new HashMap<>();
        
        // Create directories if they don't exist
        try {
            Files.createDirectories(historyDir);
            Files.createDirectories(reportsDir);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to create directories", e);
        }
        
        // Set default configuration
        ciConfig.put("failOnRegression", true);
        ciConfig.put("reportFormat", "json");
        ciConfig.put("generateHtmlReport", true);
        ciConfig.put("trackHistory", true);
    }
    
    /**
     * Sets a configuration parameter.
     *
     * @param key The configuration key
     * @param value The configuration value
     */
    public void setConfig(String key, Object value) {
        ciConfig.put(key, value);
    }
    
    /**
     * Gets a configuration parameter.
     *
     * @param key The configuration key
     * @return The configuration value, or null if not set
     */
    public Object getConfig(String key) {
        return ciConfig.get(key);
    }
    
    /**
     * Gets a configuration parameter with a default value.
     *
     * @param key The configuration key
     * @param defaultValue The default value to return if the key is not set
     * @return The configuration value, or the default value if not set
     */
    public Object getConfig(String key, Object defaultValue) {
        return ciConfig.getOrDefault(key, defaultValue);
    }
    
    /**
     * Runs the specified benchmark suite as part of a CI/CD pipeline.
     *
     * @param suiteName The name of the benchmark suite to run
     * @return true if the benchmark passed (no regressions), false otherwise
     */
    public boolean runBenchmark(String suiteName) {
        LOGGER.info("Running benchmark suite: " + suiteName + " in CI/CD pipeline");
        
        try {
            // Run the benchmark
            BenchmarkResult result = benchmarkFramework.runBenchmark(suiteName);
            
            // Save the result to the history directory if tracking is enabled
            if ((boolean) getConfig("trackHistory", true)) {
                String format = (String) getConfig("reportFormat", "json");
                result.saveToFile(historyDir, suiteName, format);
            }
            
            // Detect regressions
            Map<String, Boolean> regressions = RegressionDetector.detectRegressions(result, historyDir, regressionThreshold);
            
            // Generate regression report
            String regressionReport = RegressionDetector.generateRegressionReport(result, historyDir, regressionThreshold);
            
            // Save the regression report
            saveRegressionReport(suiteName, regressionReport);
            
            // Generate HTML report if enabled
            if ((boolean) getConfig("generateHtmlReport", true)) {
                benchmarkFramework.generateReport();
            }
            
            // Check if there are any regressions
            boolean hasRegressions = regressions.containsValue(true);
            
            // Determine if the build should fail
            boolean failOnRegression = (boolean) getConfig("failOnRegression", true);
            boolean buildPassed = !hasRegressions || !failOnRegression;
            
            // Log the result
            if (hasRegressions) {
                LOGGER.warning("Performance regressions detected in benchmark suite: " + suiteName);
                LOGGER.info("See regression report for details: " + getReportPath(suiteName));
            } else {
                LOGGER.info("No performance regressions detected in benchmark suite: " + suiteName);
            }
            
            return buildPassed;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error running benchmark suite: " + suiteName, e);
            return false;
        }
    }
    
    /**
     * Runs all registered benchmark suites as part of a CI/CD pipeline.
     *
     * @return true if all benchmarks passed (no regressions), false otherwise
     */
    public boolean runAllBenchmarks() {
        LOGGER.info("Running all benchmark suites in CI/CD pipeline");
        
        boolean allPassed = true;
        
        for (String suiteName : benchmarkFramework.getAllBenchmarkSuites().keySet()) {
            boolean passed = runBenchmark(suiteName);
            allPassed = allPassed && passed;
        }
        
        return allPassed;
    }
    
    /**
     * Saves a regression report to the reports directory.
     *
     * @param suiteName The name of the benchmark suite
     * @param report The regression report
     * @throws IOException if an I/O error occurs
     */
    private void saveRegressionReport(String suiteName, String report) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path reportPath = reportsDir.resolve(suiteName + "-regression-" + timestamp + ".txt");
        
        Files.write(reportPath, report.getBytes(), StandardOpenOption.CREATE);
        
        LOGGER.info("Regression report saved to: " + reportPath);
    }
    
    /**
     * Gets the path to the latest regression report for a benchmark suite.
     *
     * @param suiteName The name of the benchmark suite
     * @return The path to the latest regression report, or null if none exists
     * @throws IOException if an I/O error occurs
     */
    public Path getReportPath(String suiteName) throws IOException {
        return Files.list(reportsDir)
            .filter(path -> path.getFileName().toString().startsWith(suiteName + "-regression-"))
            .sorted((p1, p2) -> p2.getFileName().toString().compareTo(p1.getFileName().toString()))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Main method for running continuous performance testing from the command line.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        // Parse command line arguments
        String suiteName = args.length > 0 ? args[0] : null;
        Path historyDir = Paths.get(args.length > 1 ? args[1] : "benchmark-history");
        Path reportsDir = Paths.get(args.length > 2 ? args[2] : "benchmark-reports");
        double threshold = args.length > 3 ? Double.parseDouble(args[3]) : 10.0;
        
        // Create continuous performance testing instance
        ContinuousPerformanceTesting cpt = new ContinuousPerformanceTesting(historyDir, reportsDir, threshold);
        
        // Run benchmarks
        boolean passed;
        if (suiteName != null) {
            passed = cpt.runBenchmark(suiteName);
        } else {
            passed = cpt.runAllBenchmarks();
        }
        
        // Exit with appropriate status code
        System.exit(passed ? 0 : 1);
    }
}