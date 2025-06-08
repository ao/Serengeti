package com.ataiva.serengeti.performance;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Provides visualization capabilities for performance metrics.
 * This class generates HTML reports, JSON data for dashboards,
 * and real-time visualization of performance data.
 */
public class PerformanceVisualizer {
    private static final Logger LOGGER = Logger.getLogger(PerformanceVisualizer.class.getName());
    
    // Singleton instance
    private static final PerformanceVisualizer INSTANCE = new PerformanceVisualizer();
    
    // Reference to the performance profiler
    private final PerformanceProfiler profiler = PerformanceProfiler.getInstance();
    
    // Scheduled executor for periodic report generation
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Default output directory for reports
    private Path outputDirectory = Paths.get("performance-reports");
    
    // Time series data for metrics
    private final Map<String, List<TimeSeriesPoint>> timeSeriesData = new ConcurrentHashMap<>();
    
    // Flag to indicate if the visualizer is running
    private boolean running = false;
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private PerformanceVisualizer() {
        // Initialize with default settings
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create output directory", e);
        }
        
        // Register as a listener for real-time metrics
        profiler.addListener(this::handleNewMetric);
    }
    
    /**
     * Gets the singleton instance of the performance visualizer.
     *
     * @return The performance visualizer instance
     */
    public static PerformanceVisualizer getInstance() {
        return INSTANCE;
    }
    
    /**
     * Sets the output directory for reports.
     *
     * @param directory The output directory path
     */
    public void setOutputDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            this.outputDirectory = directory;
            LOGGER.info("Output directory set to " + directory);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create output directory", e);
        }
    }
    
    /**
     * Starts periodic report generation.
     *
     * @param intervalMinutes The interval between report generations in minutes
     */
    public synchronized void startPeriodicReports(int intervalMinutes) {
        if (running) {
            LOGGER.warning("Performance visualizer is already running");
            return;
        }
        
        running = true;
        
        // Schedule report generation
        scheduler.scheduleAtFixedRate(
            this::generateReports,
            0,
            intervalMinutes,
            TimeUnit.MINUTES
        );
        
        LOGGER.info("Performance visualizer started with interval of " + intervalMinutes + " minutes");
    }
    
    /**
     * Stops periodic report generation.
     */
    public synchronized void stopPeriodicReports() {
        if (!running) {
            LOGGER.warning("Performance visualizer is not running");
            return;
        }
        
        running = false;
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Performance visualizer stopped");
    }
    
    /**
     * Handles a new metric by adding it to the time series data.
     *
     * @param metric The new metric
     */
    private void handleNewMetric(PerformanceMetric metric) {
        String key = getMetricKey(metric);
        List<TimeSeriesPoint> points = timeSeriesData.computeIfAbsent(key, k -> new ArrayList<>());
        
        // Add the new point
        points.add(new TimeSeriesPoint(metric.getTimestamp(), metric.getValue()));
        
        // Limit the number of points to keep memory usage reasonable
        if (points.size() > 1000) {
            points.remove(0);
        }
    }
    
    /**
     * Gets a unique key for a metric.
     *
     * @param metric The metric
     * @return A unique key
     */
    private String getMetricKey(PerformanceMetric metric) {
        return metric.getComponent() + "." + metric.getOperation() + "." + metric.getName();
    }
    
    /**
     * Generates all reports.
     */
    public void generateReports() {
        try {
            generateHtmlReport();
            generateJsonData();
            generateCsvData();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error generating reports", e);
        }
    }
    
    /**
     * Generates an HTML report of performance metrics.
     *
     * @return The path to the generated report
     * @throws IOException If an I/O error occurs
     */
    public Path generateHtmlReport() throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path reportPath = outputDirectory.resolve("performance-report-" + timestamp + ".html");
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("  <title>Serengeti Performance Report</title>\n");
        html.append("  <style>\n");
        html.append("    body { font-family: Arial, sans-serif; margin: 20px; }\n");
        html.append("    h1, h2, h3 { color: #333; }\n");
        html.append("    table { border-collapse: collapse; width: 100%; margin-bottom: 20px; }\n");
        html.append("    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n");
        html.append("    th { background-color: #f2f2f2; }\n");
        html.append("    tr:nth-child(even) { background-color: #f9f9f9; }\n");
        html.append("    .chart-container { width: 100%; height: 400px; margin-bottom: 30px; }\n");
        html.append("  </style>\n");
        html.append("  <script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("  <h1>Serengeti Performance Report</h1>\n");
        html.append("  <p>Generated on: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "</p>\n");
        
        // Add summary section
        html.append("  <h2>Summary</h2>\n");
        Map<String, Map<String, Map<String, Object>>> summary = profiler.getMetricsSummary();
        
        for (Map.Entry<String, Map<String, Map<String, Object>>> componentEntry : summary.entrySet()) {
            String component = componentEntry.getKey();
            html.append("  <h3>" + component + "</h3>\n");
            
            for (Map.Entry<String, Map<String, Object>> operationEntry : componentEntry.getValue().entrySet()) {
                String operation = operationEntry.getKey();
                html.append("  <h4>" + operation + "</h4>\n");
                
                html.append("  <table>\n");
                html.append("    <tr><th>Metric</th><th>Value</th><th>Unit</th></tr>\n");
                
                for (Map.Entry<String, Object> metricEntry : operationEntry.getValue().entrySet()) {
                    String metricName = metricEntry.getKey();
                    Object value = metricEntry.getValue();
                    
                    html.append("    <tr><td>" + metricName + "</td>");
                    
                    if (value instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> valueMap = (Map<String, Object>) value;
                        html.append("<td>");
                        html.append("Min: " + String.format("%.2f", valueMap.get("min")) + "<br>");
                        html.append("Max: " + String.format("%.2f", valueMap.get("max")) + "<br>");
                        html.append("Avg: " + String.format("%.2f", valueMap.get("avg")) + "<br>");
                        html.append("Count: " + valueMap.get("count"));
                        html.append("</td>");
                        html.append("<td>ms</td>");
                    } else {
                        html.append("<td>" + value + "</td>");
                        html.append("<td>-</td>");
                    }
                    
                    html.append("</tr>\n");
                }
                
                html.append("  </table>\n");
            }
        }
        
        // Add charts for time series data
        html.append("  <h2>Charts</h2>\n");
        
        // Group metrics by type for better visualization
        Map<PerformanceMetric.MetricType, List<String>> metricsByType = new HashMap<>();
        
        for (Map.Entry<String, List<TimeSeriesPoint>> entry : timeSeriesData.entrySet()) {
            String key = entry.getKey();
            List<PerformanceMetric> metrics = profiler.getAllMetrics().stream()
                .filter(m -> getMetricKey(m).equals(key))
                .collect(Collectors.toList());
            
            if (!metrics.isEmpty()) {
                PerformanceMetric.MetricType type = metrics.get(0).getType();
                metricsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(key);
            }
        }
        
        // Generate a chart for each metric type
        int chartId = 1;
        for (Map.Entry<PerformanceMetric.MetricType, List<String>> entry : metricsByType.entrySet()) {
            PerformanceMetric.MetricType type = entry.getKey();
            List<String> keys = entry.getValue();
            
            if (keys.isEmpty()) continue;
            
            html.append("  <h3>" + type + " Metrics</h3>\n");
            html.append("  <div class=\"chart-container\">\n");
            html.append("    <canvas id=\"chart" + chartId + "\"></canvas>\n");
            html.append("  </div>\n");
            html.append("  <script>\n");
            html.append("    new Chart(document.getElementById('chart" + chartId + "'), {\n");
            html.append("      type: 'line',\n");
            html.append("      data: {\n");
            html.append("        datasets: [\n");
            
            boolean first = true;
            for (String key : keys) {
                if (!first) html.append(",\n");
                first = false;
                
                List<TimeSeriesPoint> points = timeSeriesData.get(key);
                if (points == null || points.isEmpty()) continue;
                
                html.append("          {\n");
                html.append("            label: '" + key + "',\n");
                html.append("            data: [\n");
                
                boolean firstPoint = true;
                for (TimeSeriesPoint point : points) {
                    if (!firstPoint) html.append(",\n");
                    firstPoint = false;
                    
                    html.append("              { x: " + point.timestamp + ", y: " + point.value + " }");
                }
                
                html.append("\n            ],\n");
                html.append("            borderColor: getRandomColor(),\n");
                html.append("            fill: false\n");
                html.append("          }");
            }
            
            html.append("\n        ]\n");
            html.append("      },\n");
            html.append("      options: {\n");
            html.append("        responsive: true,\n");
            html.append("        scales: {\n");
            html.append("          x: {\n");
            html.append("            type: 'time',\n");
            html.append("            time: {\n");
            html.append("              unit: 'minute'\n");
            html.append("            },\n");
            html.append("            title: {\n");
            html.append("              display: true,\n");
            html.append("              text: 'Time'\n");
            html.append("            }\n");
            html.append("          },\n");
            html.append("          y: {\n");
            html.append("            title: {\n");
            html.append("              display: true,\n");
            html.append("              text: '" + type + "'\n");
            html.append("            }\n");
            html.append("          }\n");
            html.append("        }\n");
            html.append("      }\n");
            html.append("    });\n");
            html.append("  </script>\n");
            
            chartId++;
        }
        
        // Add utility functions
        html.append("  <script>\n");
        html.append("    function getRandomColor() {\n");
        html.append("      const letters = '0123456789ABCDEF';\n");
        html.append("      let color = '#';\n");
        html.append("      for (let i = 0; i < 6; i++) {\n");
        html.append("        color += letters[Math.floor(Math.random() * 16)];\n");
        html.append("      }\n");
        html.append("      return color;\n");
        html.append("    }\n");
        html.append("  </script>\n");
        
        html.append("</body>\n");
        html.append("</html>");
        
        Files.write(reportPath, html.toString().getBytes(), StandardOpenOption.CREATE);
        LOGGER.info("HTML report generated at " + reportPath);
        
        return reportPath;
    }
    
    /**
     * Generates JSON data for dashboards.
     *
     * @return The path to the generated JSON file
     * @throws IOException If an I/O error occurs
     */
    public Path generateJsonData() throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path jsonPath = outputDirectory.resolve("performance-data-" + timestamp + ".json");
        
        JSONObject root = new JSONObject();
        root.put("timestamp", System.currentTimeMillis());
        root.put("generated", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        
        // Add summary data
        JSONObject summary = new JSONObject();
        Map<String, Map<String, Map<String, Object>>> summaryData = profiler.getMetricsSummary();
        
        for (Map.Entry<String, Map<String, Map<String, Object>>> componentEntry : summaryData.entrySet()) {
            String component = componentEntry.getKey();
            JSONObject componentJson = new JSONObject();
            
            for (Map.Entry<String, Map<String, Object>> operationEntry : componentEntry.getValue().entrySet()) {
                String operation = operationEntry.getKey();
                JSONObject operationJson = new JSONObject();
                
                for (Map.Entry<String, Object> metricEntry : operationEntry.getValue().entrySet()) {
                    String metricName = metricEntry.getKey();
                    Object value = metricEntry.getValue();
                    
                    if (value instanceof Map) {
                        operationJson.put(metricName, new JSONObject((Map<?, ?>) value));
                    } else {
                        operationJson.put(metricName, value);
                    }
                }
                
                componentJson.put(operation, operationJson);
            }
            
            summary.put(component, componentJson);
        }
        
        root.put("summary", summary);
        
        // Add time series data
        JSONObject timeSeries = new JSONObject();
        
        for (Map.Entry<String, List<TimeSeriesPoint>> entry : timeSeriesData.entrySet()) {
            String key = entry.getKey();
            List<TimeSeriesPoint> points = entry.getValue();
            
            JSONArray pointsArray = new JSONArray();
            for (TimeSeriesPoint point : points) {
                JSONObject pointJson = new JSONObject();
                pointJson.put("timestamp", point.timestamp);
                pointJson.put("value", point.value);
                pointsArray.put(pointJson);
            }
            
            timeSeries.put(key, pointsArray);
        }
        
        root.put("timeSeries", timeSeries);
        
        Files.write(jsonPath, root.toString().getBytes(), StandardOpenOption.CREATE);
        LOGGER.info("JSON data generated at " + jsonPath);
        
        return jsonPath;
    }
    
    /**
     * Generates CSV data for each metric.
     *
     * @return The directory containing the generated CSV files
     * @throws IOException If an I/O error occurs
     */
    public Path generateCsvData() throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path csvDir = outputDirectory.resolve("csv-" + timestamp);
        Files.createDirectories(csvDir);
        
        // Generate a CSV file for each metric
        for (Map.Entry<String, List<TimeSeriesPoint>> entry : timeSeriesData.entrySet()) {
            String key = entry.getKey();
            List<TimeSeriesPoint> points = entry.getValue();
            
            // Replace dots with underscores for the filename
            String filename = key.replace('.', '_') + ".csv";
            Path csvPath = csvDir.resolve(filename);
            
            StringBuilder csv = new StringBuilder();
            csv.append("timestamp,datetime,value\n");
            
            for (TimeSeriesPoint point : points) {
                String datetime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(point.timestamp),
                    ZoneId.systemDefault()
                ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                
                csv.append(point.timestamp).append(",");
                csv.append(datetime).append(",");
                csv.append(point.value).append("\n");
            }
            
            Files.write(csvPath, csv.toString().getBytes(), StandardOpenOption.CREATE);
        }
        
        LOGGER.info("CSV data generated in directory " + csvDir);
        return csvDir;
    }
    
    /**
     * Inner class representing a point in a time series.
     */
    private static class TimeSeriesPoint {
        final long timestamp;
        final double value;
        
        TimeSeriesPoint(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }
    /**
     * Generates a benchmark report and saves it to the specified path
     *
     * @param outputPath The path where the benchmark report should be saved
     * @return The path to the generated report
     * @throws IOException If there's an error writing the report
     */
    public Path generateBenchmarkReport(Path outputPath) throws IOException {
        // Create the output directory if it doesn't exist
        Files.createDirectories(outputPath.getParent());
        
        // Generate benchmark report content
        StringBuilder report = new StringBuilder();
        report.append("# Benchmark Report\n\n");
        report.append("Generated at: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");
        
        // Get all metrics from the profiler
        List<PerformanceMetric> metrics = profiler.getAllMetrics();
        
        if (metrics.isEmpty()) {
            report.append("No performance metrics available.\n");
        } else {
            // Group metrics by component and operation
            Map<String, Map<String, List<PerformanceMetric>>> groupedMetrics = new HashMap<>();
            
            for (PerformanceMetric metric : metrics) {
                groupedMetrics
                    .computeIfAbsent(metric.getComponent(), k -> new HashMap<>())
                    .computeIfAbsent(metric.getOperation(), k -> new ArrayList<>())
                    .add(metric);
            }
            
            // Generate report sections
            for (Map.Entry<String, Map<String, List<PerformanceMetric>>> componentEntry : groupedMetrics.entrySet()) {
                String component = componentEntry.getKey();
                report.append("## ").append(component).append("\n\n");
                
                for (Map.Entry<String, List<PerformanceMetric>> operationEntry : componentEntry.getValue().entrySet()) {
                    String operation = operationEntry.getKey();
                    List<PerformanceMetric> operationMetrics = operationEntry.getValue();
                    
                    report.append("### ").append(operation).append("\n\n");
                    
                    // Calculate statistics
                    Map<String, List<Double>> metricValues = new HashMap<>();
                    for (PerformanceMetric metric : operationMetrics) {
                        metricValues.computeIfAbsent(metric.getName(), k -> new ArrayList<>()).add(metric.getValue());
                    }
                    
                    for (Map.Entry<String, List<Double>> metricEntry : metricValues.entrySet()) {
                        String metricName = metricEntry.getKey();
                        List<Double> values = metricEntry.getValue();
                        
                        if (!values.isEmpty()) {
                            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
                            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                            double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                            
                            report.append("- **").append(metricName).append("**: ");
                            report.append("Min=").append(String.format("%.2f", min));
                            report.append(", Max=").append(String.format("%.2f", max));
                            report.append(", Avg=").append(String.format("%.2f", avg));
                            report.append(", Count=").append(values.size()).append("\n");
                        }
                    }
                    
                    report.append("\n");
                }
            }
        }
        
        // Write the report to file
        Files.write(outputPath, report.toString().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        LOGGER.info("Benchmark report generated: " + outputPath);
        return outputPath;
    }
}