package com.ataiva.serengeti.performance.load;

import com.ataiva.serengeti.performance.PerformanceMetric;
import com.ataiva.serengeti.performance.PerformanceProfiler;
import com.ataiva.serengeti.performance.benchmark.BenchmarkMetric;
import com.ataiva.serengeti.performance.benchmark.BenchmarkResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Framework for load testing and stress testing the Serengeti system.
 * This class provides capabilities for simulating realistic workloads,
 * generating load at various levels, and identifying system limits.
 */
public class LoadTestingFramework {
    private static final Logger LOGGER = Logger.getLogger(LoadTestingFramework.class.getName());
    
    // Singleton instance
    private static final LoadTestingFramework INSTANCE = new LoadTestingFramework();
    
    // Reference to the performance profiler
    private final PerformanceProfiler profiler;
    
    // Thread pools for load generation
    private final ExecutorService workerPool;
    private final ScheduledExecutorService schedulerPool;
    
    // Load test configuration
    private final Map<String, Object> config;
    
    // Registry of workload patterns
    private final Map<String, WorkloadPattern> workloadPatterns;
    
    // Registry of load generators
    private final Map<String, LoadGenerator> loadGenerators;
    
    // Active load tests
    private final Map<String, LoadTest> activeTests;
    
    // Flag to indicate if the framework is running
    private final AtomicBoolean running;
    
    /**
     * Private constructor to enforce singleton pattern.
     */
    private LoadTestingFramework() {
        this.profiler = PerformanceProfiler.getInstance();
        
        // Create thread pools
        int processors = Runtime.getRuntime().availableProcessors();
        this.workerPool = Executors.newFixedThreadPool(processors * 2);
        this.schedulerPool = Executors.newScheduledThreadPool(2);
        
        // Initialize collections
        this.config = new ConcurrentHashMap<>();
        this.workloadPatterns = new ConcurrentHashMap<>();
        this.loadGenerators = new ConcurrentHashMap<>();
        this.activeTests = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);
        
        // Set default configuration
        config.put("outputDirectory", "load-test-results");
        config.put("defaultDuration", 60); // seconds
        config.put("warmupTime", 10); // seconds
        config.put("cooldownTime", 5); // seconds
        config.put("reportInterval", 5); // seconds
        
        // Register built-in workload patterns
        registerBuiltInWorkloadPatterns();
        
        LOGGER.info("Load Testing Framework initialized");
    }
    
    /**
     * Gets the singleton instance of the load testing framework.
     *
     * @return The load testing framework instance
     */
    public static LoadTestingFramework getInstance() {
        return INSTANCE;
    }
    
    /**
     * Sets a configuration parameter.
     *
     * @param key The configuration key
     * @param value The configuration value
     */
    public void setConfig(String key, Object value) {
        config.put(key, value);
    }
    
    /**
     * Gets a configuration parameter.
     *
     * @param key The configuration key
     * @return The configuration value, or null if not set
     */
    public Object getConfig(String key) {
        return config.get(key);
    }
    
    /**
     * Gets a configuration parameter with a default value.
     *
     * @param key The configuration key
     * @param defaultValue The default value to return if the key is not set
     * @return The configuration value, or the default value if not set
     */
    public Object getConfig(String key, Object defaultValue) {
        return config.getOrDefault(key, defaultValue);
    }
    
    /**
     * Registers a workload pattern.
     *
     * @param name The name of the workload pattern
     * @param pattern The workload pattern
     */
    public void registerWorkloadPattern(String name, WorkloadPattern pattern) {
        workloadPatterns.put(name, pattern);
        LOGGER.info("Registered workload pattern: " + name);
    }
    
    /**
     * Gets a registered workload pattern.
     *
     * @param name The name of the workload pattern
     * @return The workload pattern, or null if not found
     */
    public WorkloadPattern getWorkloadPattern(String name) {
        return workloadPatterns.get(name);
    }
    
    /**
     * Registers a load generator.
     *
     * @param name The name of the load generator
     * @param generator The load generator
     */
    public void registerLoadGenerator(String name, LoadGenerator generator) {
        loadGenerators.put(name, generator);
        LOGGER.info("Registered load generator: " + name);
    }
    
    /**
     * Gets a registered load generator.
     *
     * @param name The name of the load generator
     * @return The load generator, or null if not found
     */
    public LoadGenerator getLoadGenerator(String name) {
        return loadGenerators.get(name);
    }
    
    /**
     * Starts a load test.
     *
     * @param name The name of the load test
     * @param generatorName The name of the load generator to use
     * @param patternName The name of the workload pattern to use
     * @param duration The duration of the test in seconds
     * @param params Additional parameters for the test
     * @return A future that completes when the test is finished
     */
    public CompletableFuture<BenchmarkResult> startLoadTest(String name, String generatorName, 
                                                          String patternName, int duration, 
                                                          Map<String, Object> params) {
        if (activeTests.containsKey(name)) {
            throw new IllegalArgumentException("Load test already running: " + name);
        }
        
        LoadGenerator generator = loadGenerators.get(generatorName);
        if (generator == null) {
            throw new IllegalArgumentException("Load generator not found: " + generatorName);
        }
        
        WorkloadPattern pattern = workloadPatterns.get(patternName);
        if (pattern == null) {
            throw new IllegalArgumentException("Workload pattern not found: " + patternName);
        }
        
        // Create a new load test
        LoadTest test = new LoadTest(name, generator, pattern, duration, params);
        activeTests.put(name, test);
        
        // Start the test
        return test.start().thenApply(result -> {
            activeTests.remove(name);
            return result;
        });
    }
    
    /**
     * Stops a running load test.
     *
     * @param name The name of the load test
     * @return true if the test was stopped, false if it wasn't running
     */
    public boolean stopLoadTest(String name) {
        LoadTest test = activeTests.get(name);
        if (test == null) {
            return false;
        }
        
        test.stop();
        return true;
    }
    
    /**
     * Gets the status of a load test.
     *
     * @param name The name of the load test
     * @return The status of the load test, or null if it's not running
     */
    public LoadTestStatus getLoadTestStatus(String name) {
        LoadTest test = activeTests.get(name);
        return test != null ? test.getStatus() : null;
    }
    
    /**
     * Runs a stress test to identify system limits.
     *
     * @param generatorName The name of the load generator to use
     * @param initialRate The initial request rate
     * @param maxRate The maximum request rate
     * @param stepSize The step size for increasing the rate
     * @param stepDuration The duration of each step in seconds
     * @param targetMetric The name of the metric to monitor
     * @param threshold The threshold for the target metric
     * @return The stress test result
     */
    public StressTestResult runStressTest(String generatorName, int initialRate, int maxRate,
                                         int stepSize, int stepDuration, String targetMetric,
                                         double threshold) {
        LOGGER.info("Starting stress test with generator: " + generatorName);
        
        LoadGenerator generator = loadGenerators.get(generatorName);
        if (generator == null) {
            throw new IllegalArgumentException("Load generator not found: " + generatorName);
        }
        
        // Create a stress test result
        StressTestResult result = new StressTestResult();
        result.setStartTime(LocalDateTime.now());
        
        // Run the stress test
        int currentRate = initialRate;
        boolean thresholdExceeded = false;
        
        while (currentRate <= maxRate && !thresholdExceeded) {
            LOGGER.info("Running stress test at rate: " + currentRate + " requests/sec");
            
            // Create a constant workload pattern at the current rate
            WorkloadPattern pattern = new ConstantWorkloadPattern(currentRate);
            
            // Run a load test at this rate
            Map<String, Object> params = new HashMap<>();
            params.put("stressTest", true);
            
            try {
                BenchmarkResult stepResult = startLoadTest("stress-" + currentRate, generatorName,
                                                         "constant", stepDuration, params).get();
                
                // Extract the target metric value
                double metricValue = extractMetricValue(stepResult, targetMetric);
                
                // Add the result to the stress test result
                result.addStepResult(currentRate, metricValue);
                
                // Check if the threshold has been exceeded
                if (metricValue > threshold) {
                    thresholdExceeded = true;
                    result.setLimitingFactor(targetMetric);
                    result.setLimitingThreshold(threshold);
                    result.setSystemLimit(currentRate);
                }
                
                // Increase the rate for the next step
                currentRate += stepSize;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error during stress test at rate: " + currentRate, e);
                thresholdExceeded = true;
                result.setLimitingFactor("error");
                result.setSystemLimit(currentRate);
            }
        }
        
        // If we reached the maximum rate without exceeding the threshold, set that as the system limit
        if (!thresholdExceeded) {
            result.setSystemLimit(maxRate);
            result.setLimitingFactor("maxRateReached");
        }
        
        result.setEndTime(LocalDateTime.now());
        
        LOGGER.info("Stress test completed. System limit: " + result.getSystemLimit() + 
                   " requests/sec, limiting factor: " + result.getLimitingFactor());
        
        return result;
    }
    
    /**
     * Extracts a metric value from a benchmark result.
     *
     * @param result The benchmark result
     * @param metricName The name of the metric
     * @return The metric value, or 0 if not found
     */
    private double extractMetricValue(BenchmarkResult result, String metricName) {
        for (BenchmarkMetric metric : result.getMetrics()) {
            if (metric.getMetricName().equals(metricName)) {
                return metric.getValue();
            }
        }
        return 0;
    }
    
    /**
     * Registers built-in workload patterns.
     */
    private void registerBuiltInWorkloadPatterns() {
        // Constant workload pattern
        registerWorkloadPattern("constant", new ConstantWorkloadPattern(100));
        
        // Ramp-up workload pattern
        registerWorkloadPattern("ramp-up", new RampWorkloadPattern(10, 1000, 60));
        
        // Sine wave workload pattern
        registerWorkloadPattern("sine", new SineWorkloadPattern(100, 50, 60));
        
        // Step workload pattern
        registerWorkloadPattern("step", new StepWorkloadPattern(new int[]{10, 50, 100, 200, 100, 50, 10}, 10));
    }
    
    /**
     * Shuts down the load testing framework.
     */
    public void shutdown() {
        LOGGER.info("Shutting down Load Testing Framework");
        
        // Stop all active tests
        for (String testName : new ArrayList<>(activeTests.keySet())) {
            stopLoadTest(testName);
        }
        
        // Shutdown thread pools
        workerPool.shutdown();
        schedulerPool.shutdown();
        
        try {
            if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
            if (!schedulerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                schedulerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            schedulerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Inner class representing a load test.
     */
    private class LoadTest {
        private final String name;
        private final LoadGenerator generator;
        private final WorkloadPattern pattern;
        private final int duration;
        private final Map<String, Object> params;
        private final CompletableFuture<BenchmarkResult> future;
        private final AtomicBoolean running;
        private final AtomicLong startTime;
        private final AtomicLong endTime;
        private final AtomicInteger completedRequests;
        private final AtomicInteger failedRequests;
        private final List<Consumer<LoadTestStatus>> statusListeners;
        
        /**
         * Creates a new load test.
         *
         * @param name The name of the load test
         * @param generator The load generator to use
         * @param pattern The workload pattern to use
         * @param duration The duration of the test in seconds
         * @param params Additional parameters for the test
         */
        public LoadTest(String name, LoadGenerator generator, WorkloadPattern pattern, 
                       int duration, Map<String, Object> params) {
            this.name = name;
            this.generator = generator;
            this.pattern = pattern;
            this.duration = duration;
            this.params = params != null ? new HashMap<>(params) : new HashMap<>();
            this.future = new CompletableFuture<>();
            this.running = new AtomicBoolean(false);
            this.startTime = new AtomicLong(0);
            this.endTime = new AtomicLong(0);
            this.completedRequests = new AtomicInteger(0);
            this.failedRequests = new AtomicInteger(0);
            this.statusListeners = new ArrayList<>();
        }
        
        /**
         * Starts the load test.
         *
         * @return A future that completes when the test is finished
         */
        public CompletableFuture<BenchmarkResult> start() {
            if (running.getAndSet(true)) {
                future.completeExceptionally(new IllegalStateException("Load test already running"));
                return future;
            }
            
            LOGGER.info("Starting load test: " + name);
            
            // Record the start time
            startTime.set(System.currentTimeMillis());
            
            // Create a benchmark result
            BenchmarkResult result = new BenchmarkResult("load-test-" + name);
            
            // Add test parameters to the result
            result.setExecutionParam("generator", generator.getClass().getSimpleName());
            result.setExecutionParam("pattern", pattern.getClass().getSimpleName());
            result.setExecutionParam("duration", duration);
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                result.setExecutionParam(entry.getKey(), entry.getValue());
            }
            
            // Get configuration parameters
            int warmupTime = (int) getConfig("warmupTime", 10);
            int cooldownTime = (int) getConfig("cooldownTime", 5);
            int reportInterval = (int) getConfig("reportInterval", 5);
            
            // Schedule the end of the test
            schedulerPool.schedule(() -> {
                try {
                    // Allow time for in-flight requests to complete
                    Thread.sleep(cooldownTime * 1000);
                    
                    // Record the end time
                    endTime.set(System.currentTimeMillis());
                    
                    // Stop the test
                    stop();
                    
                    // Add final metrics to the result
                    addFinalMetrics(result);
                    
                    // Complete the future with the result
                    future.complete(result);
                    
                    LOGGER.info("Load test completed: " + name);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    LOGGER.log(Level.SEVERE, "Error completing load test: " + name, e);
                }
            }, duration, TimeUnit.SECONDS);
            
            // Schedule periodic status reporting
            schedulerPool.scheduleAtFixedRate(() -> {
                if (running.get()) {
                    LoadTestStatus status = getStatus();
                    notifyStatusListeners(status);
                }
            }, reportInterval, reportInterval, TimeUnit.SECONDS);
            
            // Start generating load after the warmup period
            schedulerPool.schedule(() -> {
                try {
                    generateLoad();
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    LOGGER.log(Level.SEVERE, "Error generating load: " + name, e);
                }
            }, warmupTime, TimeUnit.SECONDS);
            
            return future;
        }
        
        /**
         * Stops the load test.
         */
        public void stop() {
            if (running.getAndSet(false)) {
                LOGGER.info("Stopping load test: " + name);
                
                // If the end time hasn't been set, set it now
                if (endTime.get() == 0) {
                    endTime.set(System.currentTimeMillis());
                }
                
                // Notify status listeners that the test has stopped
                notifyStatusListeners(getStatus());
            }
        }
        
        /**
         * Gets the current status of the load test.
         *
         * @return The load test status
         */
        public LoadTestStatus getStatus() {
            LoadTestStatus status = new LoadTestStatus();
            status.setName(name);
            status.setRunning(running.get());
            status.setStartTime(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(startTime.get()), 
                java.time.ZoneId.systemDefault()));
            
            long end = endTime.get();
            if (end > 0) {
                status.setEndTime(LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(end), 
                    java.time.ZoneId.systemDefault()));
            }
            
            status.setCompletedRequests(completedRequests.get());
            status.setFailedRequests(failedRequests.get());
            
            long elapsedMs = System.currentTimeMillis() - startTime.get();
            double elapsedSec = elapsedMs / 1000.0;
            if (elapsedSec > 0) {
                status.setRequestRate(completedRequests.get() / elapsedSec);
                status.setErrorRate(failedRequests.get() / elapsedSec);
            }
            
            return status;
        }
        
        /**
         * Adds a status listener.
         *
         * @param listener The status listener
         */
        public void addStatusListener(Consumer<LoadTestStatus> listener) {
            statusListeners.add(listener);
        }
        
        /**
         * Notifies all status listeners.
         *
         * @param status The current status
         */
        private void notifyStatusListeners(LoadTestStatus status) {
            for (Consumer<LoadTestStatus> listener : statusListeners) {
                try {
                    listener.accept(status);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error notifying status listener", e);
                }
            }
        }
        
        /**
         * Generates load according to the workload pattern.
         */
        private void generateLoad() {
            LOGGER.info("Generating load for test: " + name);
            
            long testStartTime = System.currentTimeMillis();
            long testEndTime = testStartTime + (duration * 1000);
            
            while (running.get() && System.currentTimeMillis() < testEndTime) {
                // Calculate the elapsed time in seconds
                long elapsedMs = System.currentTimeMillis() - testStartTime;
                double elapsedSec = elapsedMs / 1000.0;
                
                // Get the request rate for this point in time
                int requestRate = pattern.getRequestRate(elapsedSec);
                
                // Generate the specified number of requests
                for (int i = 0; i < requestRate; i++) {
                    if (!running.get()) {
                        break;
                    }
                    
                    // Submit a request to the worker pool
                    workerPool.submit(() -> {
                        try {
                            // Generate and execute the request
                            boolean success = generator.generateRequest(params);
                            
                            // Update counters
                            if (success) {
                                completedRequests.incrementAndGet();
                            } else {
                                failedRequests.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failedRequests.incrementAndGet();
                            LOGGER.log(Level.WARNING, "Error generating request", e);
                        }
                    });
                }
                
                // Sleep for a short time
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        /**
         * Adds final metrics to the benchmark result.
         *
         * @param result The benchmark result
         */
        private void addFinalMetrics(BenchmarkResult result) {
            // Calculate test duration
            long testDurationMs = endTime.get() - startTime.get();
            double testDurationSec = testDurationMs / 1000.0;
            
            // Add metrics
            result.addMetric(new BenchmarkMetric.Builder()
                .setBenchmarkName("LoadTest")
                .setMetricName("duration")
                .setValue(testDurationSec)
                .setUnit("sec")
                .setType(BenchmarkMetric.MetricType.CUSTOM)
                .build());
            
            result.addMetric(new BenchmarkMetric.Builder()
                .setBenchmarkName("LoadTest")
                .setMetricName("completedRequests")
                .setValue(completedRequests.get())
                .setUnit("requests")
                .setType(BenchmarkMetric.MetricType.CUSTOM)
                .build());
            
            result.addMetric(new BenchmarkMetric.Builder()
                .setBenchmarkName("LoadTest")
                .setMetricName("failedRequests")
                .setValue(failedRequests.get())
                .setUnit("requests")
                .setType(BenchmarkMetric.MetricType.CUSTOM)
                .build());
            
            if (testDurationSec > 0) {
                double throughput = completedRequests.get() / testDurationSec;
                double errorRate = failedRequests.get() / testDurationSec;
                
                result.addMetric(new BenchmarkMetric.Builder()
                    .setBenchmarkName("LoadTest")
                    .setMetricName("throughput")
                    .setValue(throughput)
                    .setUnit("requests/sec")
                    .setType(BenchmarkMetric.MetricType.THROUGHPUT)
                    .build());
                
                result.addMetric(new BenchmarkMetric.Builder()
                    .setBenchmarkName("LoadTest")
                    .setMetricName("errorRate")
                    .setValue(errorRate)
                    .setUnit("errors/sec")
                    .setType(BenchmarkMetric.MetricType.ERROR_RATE)
                    .build());
            }
        }
    }
    
    /**
     * Interface for workload patterns.
     */
    public interface WorkloadPattern {
        /**
         * Gets the request rate for a specific point in time.
         *
         * @param elapsedSeconds The elapsed time in seconds
         * @return The request rate in requests per second
         */
        int getRequestRate(double elapsedSeconds);
    }
    
    /**
     * Constant workload pattern that maintains a fixed request rate.
     */
    public static class ConstantWorkloadPattern implements WorkloadPattern {
        private final int requestRate;
        
        /**
         * Creates a new constant workload pattern.
         *
         * @param requestRate The request rate in requests per second
         */
        public ConstantWorkloadPattern(int requestRate) {
            this.requestRate = requestRate;
        }
        
        @Override
        public int getRequestRate(double elapsedSeconds) {
            return requestRate;
        }
    }
    
    /**
     * Ramp workload pattern that gradually increases the request rate.
     */
    public static class RampWorkloadPattern implements WorkloadPattern {
        private final int startRate;
        private final int endRate;
        private final double duration;
        
        /**
         * Creates a new ramp workload pattern.
         *
         * @param startRate The starting request rate in requests per second
         * @param endRate The ending request rate in requests per second
         * @param duration The duration of the ramp in seconds
         */
        public RampWorkloadPattern(int startRate, int endRate, double duration) {
            this.startRate = startRate;
            this.endRate = endRate;
            this.duration = duration;
        }
        
        @Override
        public int getRequestRate(double elapsedSeconds) {
            if (elapsedSeconds >= duration) {
                return endRate;
            }
            
            double progress = elapsedSeconds / duration;
            return startRate + (int) (progress * (endRate - startRate));
        }
    }
    
    /**
     * Sine wave workload pattern that oscillates the request rate.
     */
    public static class SineWorkloadPattern implements WorkloadPattern {
        private final int baseRate;
        private final int amplitude;
        private final double period;
        
        /**
         * Creates a new sine wave workload pattern.
         *
         * @param baseRate The base request rate in requests per second
         * @param amplitude The amplitude of the sine wave
         * @param period The period of the sine wave in seconds
         */
        public SineWorkloadPattern(int baseRate, int amplitude, double period) {
            this.baseRate = baseRate;
            this.amplitude = amplitude;
            this.period = period;
        }
        
        @Override
        public int getRequestRate(double elapsedSeconds) {
            double radians = (elapsedSeconds / period) * 2 * Math.PI;
            double sinValue = Math.sin(radians);
            return baseRate + (int) (amplitude * sinValue);
        }
    }
    
    /**
     * Step workload pattern that changes the request rate in discrete steps.
     */
    public static class StepWorkloadPattern implements WorkloadPattern {
        private final int[] rates;
        private final int stepDuration;
        
        /**
         * Creates a new step workload pattern.
         *
         * @param rates The request rates for each step in requests per second
         * @param stepDuration The duration of each step in seconds
         */
        public StepWorkloadPattern(int[] rates, int stepDuration) {
            this.rates = rates;
            this.stepDuration = stepDuration;
        }
        
        @Override
        public int getRequestRate(double elapsedSeconds) {
            int step = (int) (elapsedSeconds / stepDuration);
            if (step >= rates.length) {
                return rates[rates.length - 1];
            }
            return rates[step];
        }
    }
    
    /**
     * Interface for load generators.
     */
    public interface LoadGenerator {
        /**
         * Generates and executes a request.
         *
         * @param params Parameters for the request
         * @return true if the request was successful, false otherwise
         * @throws Exception if an error occurs
         */
        boolean generateRequest(Map<String, Object> params) throws Exception;
    }
    
    /**
     * Class representing the status of a load test.
     */
    public static class LoadTestStatus {
        private String name;
        private boolean running;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private int completedRequests;
        private int failedRequests;
        private double requestRate;
        private double errorRate;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public boolean isRunning() {
            return running;
        }
        
        public void setRunning(boolean running) {
            this.running = running;
        }
        
        public LocalDateTime getStartTime() {
            return startTime;
        }
        
        public void setStartTime(LocalDateTime startTime) {
            this.startTime = startTime;
        }
        
        public LocalDateTime getEndTime() {
            return endTime;
        }
        
        public void setEndTime(LocalDateTime endTime) {
            this.endTime = endTime;
        }
        
        public int getCompletedRequests() {
            return completedRequests;
        }
        
        public void setCompletedRequests(int completedRequests) {
            this.completedRequests = completedRequests;
        }
        
        public int getFailedRequests() {
            return failedRequests;
        }
        
        public void setFailedRequests(int failedRequests) {
            this.failedRequests = failedRequests;
        }
        
        public double getRequestRate() {
            return requestRate;
        }
        
        public void setRequestRate(double requestRate) {
            this.requestRate = requestRate;
        }
        
        public double getErrorRate() {
            return errorRate;
        }
        
        public void setErrorRate(double errorRate) {
            this.errorRate = errorRate;
        }
    }
    
    /**
     * Class representing the result of a stress test.
     */
    public static class StressTestResult {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private int systemLimit;
        private String limitingFactor;
        private double limitingThreshold;
        private final Map<Integer, Double> stepResults = new HashMap<>();
        
        public LocalDateTime getStartTime() {
            return startTime