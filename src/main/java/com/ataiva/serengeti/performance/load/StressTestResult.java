package com.ataiva.serengeti.performance.load;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Class representing the result of a stress test.
 * This class stores information about the system limits identified during a stress test,
 * including the maximum sustainable request rate and the limiting factor.
 */
public class StressTestResult {
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int systemLimit;
    private String limitingFactor;
    private double limitingThreshold;
    private final Map<Integer, Double> stepResults = new HashMap<>();
    
    /**
     * Gets the start time of the stress test.
     *
     * @return The start time
     */
    public LocalDateTime getStartTime() {
        return startTime;
    }
    
    /**
     * Sets the start time of the stress test.
     *
     * @param startTime The start time
     */
    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }
    
    /**
     * Gets the end time of the stress test.
     *
     * @return The end time
     */
    public LocalDateTime getEndTime() {
        return endTime;
    }
    
    /**
     * Sets the end time of the stress test.
     *
     * @param endTime The end time
     */
    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }
    
    /**
     * Gets the system limit identified by the stress test.
     * This is the maximum sustainable request rate.
     *
     * @return The system limit in requests per second
     */
    public int getSystemLimit() {
        return systemLimit;
    }
    
    /**
     * Sets the system limit identified by the stress test.
     *
     * @param systemLimit The system limit in requests per second
     */
    public void setSystemLimit(int systemLimit) {
        this.systemLimit = systemLimit;
    }
    
    /**
     * Gets the limiting factor that determined the system limit.
     * This is the name of the metric or resource that became a bottleneck.
     *
     * @return The limiting factor
     */
    public String getLimitingFactor() {
        return limitingFactor;
    }
    
    /**
     * Sets the limiting factor that determined the system limit.
     *
     * @param limitingFactor The limiting factor
     */
    public void setLimitingFactor(String limitingFactor) {
        this.limitingFactor = limitingFactor;
    }
    
    /**
     * Gets the threshold value for the limiting factor.
     * This is the value at which the system was considered to be at its limit.
     *
     * @return The limiting threshold
     */
    public double getLimitingThreshold() {
        return limitingThreshold;
    }
    
    /**
     * Sets the threshold value for the limiting factor.
     *
     * @param limitingThreshold The limiting threshold
     */
    public void setLimitingThreshold(double limitingThreshold) {
        this.limitingThreshold = limitingThreshold;
    }
    
    /**
     * Gets the results for each step of the stress test.
     * The key is the request rate, and the value is the measured metric value.
     *
     * @return The step results
     */
    public Map<Integer, Double> getStepResults() {
        return new HashMap<>(stepResults);
    }
    
    /**
     * Adds a result for a specific step of the stress test.
     *
     * @param requestRate The request rate for the step
     * @param metricValue The measured metric value
     */
    public void addStepResult(int requestRate, double metricValue) {
        stepResults.put(requestRate, metricValue);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Stress Test Result:\n");
        sb.append("  Start Time: ").append(startTime).append("\n");
        sb.append("  End Time: ").append(endTime).append("\n");
        sb.append("  System Limit: ").append(systemLimit).append(" requests/sec\n");
        sb.append("  Limiting Factor: ").append(limitingFactor).append("\n");
        sb.append("  Limiting Threshold: ").append(limitingThreshold).append("\n");
        sb.append("  Step Results:\n");
        
        for (Map.Entry<Integer, Double> entry : stepResults.entrySet()) {
            sb.append("    ").append(entry.getKey()).append(" req/s: ")
              .append(entry.getValue()).append("\n");
        }
        
        return sb.toString();
    }
}