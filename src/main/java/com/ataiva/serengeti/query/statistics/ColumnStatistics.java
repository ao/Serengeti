package com.ataiva.serengeti.query.statistics;

import java.util.HashSet;
import java.util.Set;

/**
 * ColumnStatistics stores statistical information about a database column.
 * This information is used by the query optimizer to make cost-based decisions.
 */
public class ColumnStatistics {
    // Number of distinct values in the column
    private long distinctValues;
    
    // Number of null values in the column
    private long nullCount;
    
    // Minimum value (for numeric columns)
    private Double minValue;
    
    // Maximum value (for numeric columns)
    private Double maxValue;
    
    // Average value (for numeric columns)
    private Double avgValue;
    
    // Total number of values analyzed
    private long valueCount;
    
    // Sum of all numeric values
    private double sum;
    
    // Set to track distinct values (up to a limit)
    private Set<Object> distinctValueSet;
    
    // Maximum number of distinct values to track
    private static final int MAX_DISTINCT_VALUES = 1000;
    
    // Flag indicating if we're still tracking all distinct values
    private boolean trackingAllDistinct;
    
    // Average length for string values
    private double avgStringLength;
    
    // Total string length
    private long totalStringLength;
    
    // Number of string values
    private long stringCount;
    
    /**
     * Default constructor
     */
    public ColumnStatistics() {
        this.distinctValues = 0;
        this.nullCount = 0;
        this.minValue = null;
        this.maxValue = null;
        this.avgValue = null;
        this.valueCount = 0;
        this.sum = 0;
        this.distinctValueSet = new HashSet<>();
        this.trackingAllDistinct = true;
        this.avgStringLength = 0;
        this.totalStringLength = 0;
        this.stringCount = 0;
    }
    
    /**
     * Add a value to the statistics
     * @param value Value to add
     */
    public void addValue(Object value) {
        if (value == null) {
            nullCount++;
            return;
        }
        
        valueCount++;
        
        // Track distinct values if we haven't exceeded the limit
        if (trackingAllDistinct) {
            distinctValueSet.add(value);
            if (distinctValueSet.size() > MAX_DISTINCT_VALUES) {
                // Stop tracking individual values if we exceed the limit
                trackingAllDistinct = false;
            } else {
                distinctValues = distinctValueSet.size();
            }
        }
        
        // Handle numeric values
        if (value instanceof Number) {
            double numValue = ((Number) value).doubleValue();
            
            // Update min/max
            if (minValue == null || numValue < minValue) {
                minValue = numValue;
            }
            if (maxValue == null || numValue > maxValue) {
                maxValue = numValue;
            }
            
            // Update sum for average calculation
            sum += numValue;
            avgValue = sum / (valueCount - nullCount);
        }
        
        // Handle string values
        if (value instanceof String) {
            String strValue = (String) value;
            totalStringLength += strValue.length();
            stringCount++;
            avgStringLength = (double) totalStringLength / stringCount;
        }
    }
    
    /**
     * Get the number of distinct values in the column
     * @return Distinct value count
     */
    public long getDistinctValues() {
        return distinctValues;
    }
    
    /**
     * Get the number of null values in the column
     * @return Null count
     */
    public long getNullCount() {
        return nullCount;
    }
    
    /**
     * Get the minimum value (for numeric columns)
     * @return Minimum value or null if not applicable
     */
    public Double getMinValue() {
        return minValue;
    }
    
    /**
     * Get the maximum value (for numeric columns)
     * @return Maximum value or null if not applicable
     */
    public Double getMaxValue() {
        return maxValue;
    }
    
    /**
     * Get the average value (for numeric columns)
     * @return Average value or null if not applicable
     */
    public Double getAvgValue() {
        return avgValue;
    }
    
    /**
     * Get the total number of values analyzed
     * @return Value count
     */
    public long getValueCount() {
        return valueCount;
    }
    
    /**
     * Get the average string length (for string columns)
     * @return Average string length
     */
    public double getAvgStringLength() {
        return avgStringLength;
    }
    
    /**
     * Calculate the selectivity of an equality predicate
     * @return Selectivity factor (between 0 and 1)
     */
    public double calculateEqualitySelectivity() {
        if (valueCount == 0) {
            return 0;
        }
        
        // If we know the exact number of distinct values
        if (trackingAllDistinct && distinctValues > 0) {
            return 1.0 / distinctValues;
        }
        
        // Default estimate if we don't have exact distinct count
        return 0.1; // Assume 10% selectivity as a reasonable default
    }
    
    /**
     * Calculate the selectivity of a range predicate
     * @param lower Lower bound
     * @param upper Upper bound
     * @return Selectivity factor (between 0 and 1)
     */
    public double calculateRangeSelectivity(Double lower, Double upper) {
        if (valueCount == 0 || minValue == null || maxValue == null) {
            return 0.5; // Default if we don't have statistics
        }
        
        // Adjust bounds to the actual data range
        double effectiveLower = (lower != null) ? Math.max(lower, minValue) : minValue;
        double effectiveUpper = (upper != null) ? Math.min(upper, maxValue) : maxValue;
        
        // Calculate the fraction of the data range covered by the query range
        double dataRange = maxValue - minValue;
        if (dataRange <= 0) {
            return (effectiveLower <= maxValue && effectiveUpper >= minValue) ? 1.0 : 0.0;
        }
        
        double queryRange = effectiveUpper - effectiveLower;
        return Math.min(1.0, Math.max(0.0, queryRange / dataRange));
    }
    
    /**
     * Estimate if an index would be beneficial for this column
     * @return True if an index would likely improve query performance
     */
    public boolean shouldCreateIndex() {
        // Index is beneficial if there are many rows but relatively few distinct values
        if (valueCount > 1000 && distinctValues > 0) {
            double distinctRatio = (double) distinctValues / valueCount;
            // Index is most useful when distinctRatio is low but not too low
            return distinctRatio < 0.5 && distinctRatio > 0.001;
        }
        return false;
    }
    
    @Override
    public String toString() {
        return "ColumnStatistics{" +
                "distinctValues=" + distinctValues +
                ", nullCount=" + nullCount +
                ", minValue=" + minValue +
                ", maxValue=" + maxValue +
                ", avgValue=" + avgValue +
                ", valueCount=" + valueCount +
                ", avgStringLength=" + avgStringLength +
                '}';
    }
}