package com.ataiva.serengeti.performance;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Stores historical performance data for long-term analysis.
 * This class provides persistent storage of performance metrics,
 * with support for data aggregation, retention policies, and efficient querying.
 */
public class HistoricalDataStore {
    private static final Logger LOGGER = Logger.getLogger(HistoricalDataStore.class.getName());
    
    // Singleton instance
    private static final HistoricalDataStore INSTANCE = new HistoricalDataStore();
    
    // Reference to the performance profiler
    private final PerformanceProfiler profiler = PerformanceProfiler.getInstance();
    
    // Configuration
    private final ProfilingConfiguration config = ProfilingConfiguration.getInstance();
    
    // Scheduled executor for periodic data persistence
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // In-memory cache of historical data
    private final Map<String, SortedMap<Long, Double>> timeSeriesData = new ConcurrentHashMap<>();
    
    // Default storage directory
    private Path storageDirectory = Paths.get("performance-history");
    
    // Data retention periods in milliseconds
    private long rawDataRetentionPeriod = TimeUnit.DAYS.toMillis(7);  // 7 days
    private long hourlyAggregationRetentionPeriod = TimeUnit.DAYS.toMillis(30);  // 30 days
    private long dailyAggregationRetentionPeriod = TimeUnit.DAYS.toMillis(365);  // 365 days
    
    // Flag to indicate if the store is running
    private boolean running = false;
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private HistoricalDataStore() {
        // Initialize with default settings
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create storage directory", e);
        }
        
        // Register as a listener for real-time metrics
        profiler.addListener(this::handleNewMetric);
    }
    
    /**
     * Gets the singleton instance of the historical data store.
     *
     * @return The historical data store instance
     */
    public static HistoricalDataStore getInstance() {
        return INSTANCE;
    }
    
    /**
     * Sets the storage directory for historical data.
     *
     * @param directory The storage directory path
     */
    public void setStorageDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            this.storageDirectory = directory;
            LOGGER.info("Storage directory set to " + directory);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create storage directory", e);
        }
    }
    
    /**
     * Sets the data retention periods.
     *
     * @param rawDataRetentionDays The number of days to retain raw data
     * @param hourlyAggregationRetentionDays The number of days to retain hourly aggregated data
     * @param dailyAggregationRetentionDays The number of days to retain daily aggregated data
     */
    public void setRetentionPeriods(int rawDataRetentionDays, int hourlyAggregationRetentionDays, 
                                   int dailyAggregationRetentionDays) {
        this.rawDataRetentionPeriod = TimeUnit.DAYS.toMillis(rawDataRetentionDays);
        this.hourlyAggregationRetentionPeriod = TimeUnit.DAYS.toMillis(hourlyAggregationRetentionDays);
        this.dailyAggregationRetentionPeriod = TimeUnit.DAYS.toMillis(dailyAggregationRetentionDays);
        
        LOGGER.info("Retention periods set to: raw=" + rawDataRetentionDays + " days, " +
                   "hourly=" + hourlyAggregationRetentionDays + " days, " +
                   "daily=" + dailyAggregationRetentionDays + " days");
    }
    
    /**
     * Starts periodic data persistence and aggregation.
     */
    public synchronized void start() {
        if (running) {
            LOGGER.warning("Historical data store is already running");
            return;
        }
        
        running = true;
        
        // Schedule data persistence
        scheduler.scheduleAtFixedRate(
            this::persistData,
            1,
            15,
            TimeUnit.MINUTES
        );
        
        // Schedule data aggregation
        scheduler.scheduleAtFixedRate(
            this::aggregateData,
            5,
            60,
            TimeUnit.MINUTES
        );
        
        // Schedule data cleanup
        scheduler.scheduleAtFixedRate(
            this::cleanupData,
            10,
            24,
            TimeUnit.HOURS
        );
        
        LOGGER.info("Historical data store started");
    }
    
    /**
     * Stops periodic data persistence and aggregation.
     */
    public synchronized void stop() {
        if (!running) {
            LOGGER.warning("Historical data store is not running");
            return;
        }
        
        running = false;
        
        // Persist data before stopping
        persistData();
        
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Historical data store stopped");
    }
    
    /**
     * Handles a new metric by adding it to the time series data.
     *
     * @param metric The new metric
     */
    private void handleNewMetric(PerformanceMetric metric) {
        if (!config.isEnabled()) {
            return;
        }
        
        String key = getMetricKey(metric);
        SortedMap<Long, Double> points = timeSeriesData.computeIfAbsent(key, k -> new TreeMap<>());
        
        // Add the new point
        points.put(metric.getTimestamp(), metric.getValue());
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
     * Persists time series data to disk.
     */
    public void persistData() {
        if (!config.isEnabled()) {
            return;
        }
        
        try {
            // Create a timestamp for the data files
            String fileTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            
            // Create a directory for this batch of data
            Path batchDir = storageDirectory.resolve("raw-" + fileTimestamp);
            Files.createDirectories(batchDir);
            
            // Write each metric to a separate file
            for (Map.Entry<String, SortedMap<Long, Double>> entry : timeSeriesData.entrySet()) {
                String key = entry.getKey();
                SortedMap<Long, Double> points = entry.getValue();
                
                if (points.isEmpty()) {
                    continue;
                }
                
                // Replace dots with underscores for the filename
                String filename = key.replace('.', '_') + ".csv";
                Path filePath = batchDir.resolve(filename);
                
                try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardOpenOption.CREATE)) {
                    // Write header
                    writer.write("timestamp,datetime,value\n");
                    
                    // Write data points
                    for (Map.Entry<Long, Double> point : points.entrySet()) {
                        long pointTimestamp = point.getKey();
                        double value = point.getValue();
                        
                        String datetime = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(pointTimestamp),
                            ZoneId.systemDefault()
                        ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        
                        writer.write(pointTimestamp + "," + datetime + "," + value + "\n");
                    }
                }
            }
            
            // Create a metadata file
            Path metadataPath = batchDir.resolve("metadata.json");
            JSONObject metadata = new JSONObject();
            metadata.put("timestamp", System.currentTimeMillis());
            metadata.put("metrics", timeSeriesData.size());
            metadata.put("totalPoints", timeSeriesData.values().stream()
                        .mapToInt(Map::size)
                        .sum());
            
            Files.write(metadataPath, metadata.toString().getBytes(), StandardOpenOption.CREATE);
            
            LOGGER.info("Persisted " + timeSeriesData.size() + " metrics to " + batchDir);
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to persist historical data", e);
        }
    }
    
    /**
     * Aggregates time series data into hourly and daily summaries.
     */
    public void aggregateData() {
        if (!config.isEnabled()) {
            return;
        }
        
        try {
            // Get all raw data directories
            List<Path> rawDirs = Files.list(storageDirectory)
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("raw-"))
                .collect(Collectors.toList());
            
            if (rawDirs.isEmpty()) {
                return;
            }
            
            // Create timestamp for aggregated data
            String fileTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            
            // Create directories for aggregated data
            Path hourlyDir = storageDirectory.resolve("hourly-" + fileTimestamp);
            Path dailyDir = storageDirectory.resolve("daily-" + fileTimestamp);
            Files.createDirectories(hourlyDir);
            Files.createDirectories(dailyDir);
            
            // Map to store aggregated data
            Map<String, Map<Long, List<Double>>> hourlyData = new HashMap<>();
            Map<String, Map<Long, List<Double>>> dailyData = new HashMap<>();
            
            // Process each raw data directory
            for (Path rawDir : rawDirs) {
                // Process each CSV file
                Files.list(rawDir)
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .forEach(p -> {
                        try {
                            processRawDataFile(p, hourlyData, dailyData);
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to process raw data file: " + p, e);
                        }
                    });
            }
            
            // Write hourly aggregated data
            writeAggregatedData(hourlyData, hourlyDir, "hourly");
            
            // Write daily aggregated data
            writeAggregatedData(dailyData, dailyDir, "daily");
            
            LOGGER.info("Aggregated data into hourly and daily summaries");
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to aggregate historical data", e);
        }
    }
    
    /**
     * Processes a raw data file and adds its data points to hourly and daily aggregations.
     *
     * @param filePath The path to the raw data file
     * @param hourlyData The map to store hourly aggregated data
     * @param dailyData The map to store daily aggregated data
     * @throws IOException If an I/O error occurs
     */
    private void processRawDataFile(Path filePath, Map<String, Map<Long, List<Double>>> hourlyData,
                                   Map<String, Map<Long, List<Double>>> dailyData) throws IOException {
        // Extract metric key from filename
        String filename = filePath.getFileName().toString();
        String metricKey = filename.substring(0, filename.length() - 4).replace('_', '.');
        
        // Read the file
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            // Skip header
            reader.readLine();
            
            // Process each line
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 3) {
                    continue;
                }
                
                try {
                    long timestamp = Long.parseLong(parts[0]);
                    double value = Double.parseDouble(parts[2]);
                    
                    // Calculate hourly and daily timestamps
                    long hourlyTimestamp = timestamp - (timestamp % TimeUnit.HOURS.toMillis(1));
                    long dailyTimestamp = timestamp - (timestamp % TimeUnit.DAYS.toMillis(1));
                    
                    // Add to hourly data
                    Map<Long, List<Double>> hourlyPoints = hourlyData.computeIfAbsent(metricKey, k -> new HashMap<>());
                    List<Double> hourlyValues = hourlyPoints.computeIfAbsent(hourlyTimestamp, k -> new ArrayList<>());
                    hourlyValues.add(value);
                    
                    // Add to daily data
                    Map<Long, List<Double>> dailyPoints = dailyData.computeIfAbsent(metricKey, k -> new HashMap<>());
                    List<Double> dailyValues = dailyPoints.computeIfAbsent(dailyTimestamp, k -> new ArrayList<>());
                    dailyValues.add(value);
                    
                } catch (NumberFormatException e) {
                    // Skip invalid lines
                }
            }
        }
    }
    
    /**
     * Writes aggregated data to disk.
     *
     * @param aggregatedData The aggregated data
     * @param outputDir The output directory
     * @param aggregationType The type of aggregation (hourly or daily)
     * @throws IOException If an I/O error occurs
     */
    private void writeAggregatedData(Map<String, Map<Long, List<Double>>> aggregatedData,
                                    Path outputDir, String aggregationType) throws IOException {
        // Write each metric to a separate file
        for (Map.Entry<String, Map<Long, List<Double>>> entry : aggregatedData.entrySet()) {
            String key = entry.getKey();
            Map<Long, List<Double>> points = entry.getValue();
            
            if (points.isEmpty()) {
                continue;
            }
            
            // Replace dots with underscores for the filename
            String filename = key.replace('.', '_') + ".csv";
            Path filePath = outputDir.resolve(filename);
            
            try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardOpenOption.CREATE)) {
                // Write header
                writer.write("timestamp,datetime,min,max,avg,count\n");
                
                // Write data points
                for (Map.Entry<Long, List<Double>> point : points.entrySet()) {
                    long pointTimestamp = point.getKey();
                    List<Double> values = point.getValue();
                    
                    if (values.isEmpty()) {
                        continue;
                    }
                    
                    String datetime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(pointTimestamp),
                        ZoneId.systemDefault()
                    ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    
                    double min = Collections.min(values);
                    double max = Collections.max(values);
                    double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    int count = values.size();
                    
                    writer.write(pointTimestamp + "," + datetime + "," +
                                min + "," + max + "," + avg + "," + count + "\n");
                }
            }
        }
        
        // Create a metadata file
        Path metadataPath = outputDir.resolve("metadata.json");
        JSONObject metadata = new JSONObject();
        metadata.put("timestamp", System.currentTimeMillis());
        metadata.put("aggregationType", aggregationType);
        metadata.put("metrics", aggregatedData.size());
        metadata.put("totalPoints", aggregatedData.values().stream()
                    .mapToInt(Map::size)
                    .sum());
        
        Files.write(metadataPath, metadata.toString().getBytes(), StandardOpenOption.CREATE);
    }
    
    /**
     * Cleans up old data based on retention policies.
     */
    public void cleanupData() {
        if (!config.isEnabled()) {
            return;
        }
        
        try {
            long now = System.currentTimeMillis();
            
            // Clean up raw data
            cleanupDataByType(now, "raw-", rawDataRetentionPeriod);
            
            // Clean up hourly data
            cleanupDataByType(now, "hourly-", hourlyAggregationRetentionPeriod);
            
            // Clean up daily data
            cleanupDataByType(now, "daily-", dailyAggregationRetentionPeriod);
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to clean up historical data", e);
        }
    }
    
    /**
     * Cleans up data of a specific type based on retention period.
     *
     * @param now The current timestamp
     * @param prefix The directory prefix
     * @param retentionPeriod The retention period in milliseconds
     * @throws IOException If an I/O error occurs
     */
    private void cleanupDataByType(long now, String prefix, long retentionPeriod) throws IOException {
        // Get all directories of the specified type
        List<Path> dirs = Files.list(storageDirectory)
            .filter(Files::isDirectory)
            .filter(p -> p.getFileName().toString().startsWith(prefix))
            .collect(Collectors.toList());
        
        for (Path dir : dirs) {
            // Check if the directory has a metadata file
            Path metadataPath = dir.resolve("metadata.json");
            if (!Files.exists(metadataPath)) {
                continue;
            }
            
            // Read the metadata
            String metadataJson = new String(Files.readAllBytes(metadataPath));
            JSONObject metadata = new JSONObject(metadataJson);
            
            // Check if the data is older than the retention period
            long timestamp = metadata.optLong("timestamp", 0);
            if (timestamp > 0 && now - timestamp > retentionPeriod) {
                // Delete the directory
                Files.walk(dir)
                    .sorted((a, b) -> -a.compareTo(b))  // Sort in reverse order
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to delete file: " + p, e);
                        }
                    });
                
                LOGGER.info("Deleted old data directory: " + dir);
            }
        }
    }
    
    /**
     * Queries historical data for a specific metric and time range.
     *
     * @param metricKey The metric key
     * @param startTime The start time in milliseconds since epoch
     * @param endTime The end time in milliseconds since epoch
     * @param aggregationType The type of aggregation (raw, hourly, daily)
     * @return A list of data points
     */
    public List<Map<String, Object>> queryData(String metricKey, long startTime, long endTime, 
                                              String aggregationType) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        try {
            // Find the appropriate directories
            List<Path> dirs = Files.list(storageDirectory)
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith(aggregationType + "-"))
                .sorted()
                .collect(Collectors.toList());
            
            // Replace dots with underscores for the filename
            String filename = metricKey.replace('.', '_') + ".csv";
            
            // Process each directory
            for (Path dir : dirs) {
                Path filePath = dir.resolve(filename);
                if (!Files.exists(filePath)) {
                    continue;
                }
                
                // Read the file
                try (BufferedReader reader = Files.newBufferedReader(filePath)) {
                    // Read header
                    String header = reader.readLine();
                    if (header == null) {
                        continue;
                    }
                    
                    String[] headerParts = header.split(",");
                    
                    // Process each line
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split(",");
                        if (parts.length < headerParts.length) {
                            continue;
                        }
                        
                        try {
                            long timestamp = Long.parseLong(parts[0]);
                            
                            // Check if the timestamp is within the requested range
                            if (timestamp >= startTime && timestamp <= endTime) {
                                Map<String, Object> point = new HashMap<>();
                                point.put("timestamp", timestamp);
                                
                                // Add all values from the line
                                for (int i = 1; i < headerParts.length; i++) {
                                    if (i < parts.length) {
                                        try {
                                            // Try to parse as a number
                                            point.put(headerParts[i], Double.parseDouble(parts[i]));
                                        } catch (NumberFormatException e) {
                                            // If not a number, store as string
                                            point.put(headerParts[i], parts[i]);
                                        }
                                    }
                                }
                                
                                result.add(point);
                            }
                        } catch (NumberFormatException e) {
                            // Skip invalid lines
                        }
                    }
                }
            }
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to query historical data", e);
        }
        
        return result;
    }
    
    /**
     * Gets a list of all available metrics.
     *
     * @return A list of metric keys
     */
    public List<String> getAvailableMetrics() {
        List<String> metrics = new ArrayList<>();
        
        try {
            // Find all directories
            List<Path> dirs = Files.list(storageDirectory)
                .filter(Files::isDirectory)
                .collect(Collectors.toList());
            
            // Process each directory
            for (Path dir : dirs) {
                // List all CSV files
                Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .forEach(p -> {
                        String filename = p.getFileName().toString();
                        if (filename.endsWith(".csv")) {
                            String metricKey = filename.substring(0, filename.length() - 4).replace('_', '.');
                            if (!metrics.contains(metricKey)) {
                                metrics.add(metricKey);
                            }
                        }
                    });
            }
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get available metrics", e);
        }
        
        return metrics;
    }
    
    /**
     * Gets the available time range for historical data.
     *
     * @return A map with "minTime" and "maxTime" keys
     */
    public Map<String, Long> getAvailableTimeRange() {
        Map<String, Long> result = new HashMap<>();
        result.put("minTime", Long.MAX_VALUE);
        result.put("maxTime", Long.MIN_VALUE);
        
        try {
            // Find all directories
            List<Path> dirs = Files.list(storageDirectory)
                .filter(Files::isDirectory)
                .collect(Collectors.toList());
            
            // Process each directory
            for (Path dir : dirs) {
                // Check if the directory has a metadata file
                Path metadataPath = dir.resolve("metadata.json");
                if (!Files.exists(metadataPath)) {
                    continue;
                }
                
                // Read the metadata
                String metadataJson = new String(Files.readAllBytes(metadataPath));
                JSONObject metadata = new JSONObject(metadataJson);
                
                // Update time range
                long timestamp = metadata.optLong("timestamp", 0);
                if (timestamp > 0) {
                    result.put("minTime", Math.min(result.get("minTime"), timestamp));
                    result.put("maxTime", Math.max(result.get("maxTime"), timestamp));
                }
            }
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get available time range", e);
        }
        
        // If no data was found, reset to 0
        if (result.get("minTime") == Long.MAX_VALUE) {
            result.put("minTime", 0L);
        }
        if (result.get("maxTime") == Long.MIN_VALUE) {
            result.put("maxTime", 0L);
        }
        
        return result;
    }
}