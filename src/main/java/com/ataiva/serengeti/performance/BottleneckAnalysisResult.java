package com.ataiva.serengeti.performance;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents the result of analyzing a performance bottleneck.
 * This class encapsulates detailed information about a bottleneck,
 * including insights and recommendations for addressing it.
 */
public class BottleneckAnalysisResult {
    private final Bottleneck bottleneck;
    private final List<String> insights;
    private final Map<String, Object> details;
    private final long timestamp;
    
    /**
     * Creates a new bottleneck analysis result.
     *
     * @param bottleneck The bottleneck that was analyzed
     * @param insights A list of insights about the bottleneck
     * @param details Additional details about the bottleneck
     */
    public BottleneckAnalysisResult(Bottleneck bottleneck, List<String> insights, Map<String, Object> details) {
        this.bottleneck = bottleneck;
        this.insights = Collections.unmodifiableList(insights);
        this.details = Collections.unmodifiableMap(details);
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * Gets the bottleneck that was analyzed.
     *
     * @return The bottleneck
     */
    public Bottleneck getBottleneck() {
        return bottleneck;
    }
    
    /**
     * Gets the insights about the bottleneck.
     *
     * @return An unmodifiable list of insights
     */
    public List<String> getInsights() {
        return insights;
    }
    
    /**
     * Gets the additional details about the bottleneck.
     *
     * @return An unmodifiable map of details
     */
    public Map<String, Object> getDetails() {
        return details;
    }
    
    /**
     * Gets the timestamp when the analysis was performed.
     *
     * @return The timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Analysis of ").append(bottleneck).append(":\n");
        
        if (insights.isEmpty()) {
            sb.append("  No insights available.\n");
        } else {
            sb.append("  Insights:\n");
            for (String insight : insights) {
                sb.append("  - ").append(insight).append("\n");
            }
        }
        
        return sb.toString();
    }
}