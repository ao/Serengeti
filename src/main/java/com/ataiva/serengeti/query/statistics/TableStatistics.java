package com.ataiva.serengeti.query.statistics;

/**
 * TableStatistics stores statistical information about a database table.
 * This information is used by the query optimizer to make cost-based decisions.
 */
public class TableStatistics {
    // Number of rows in the table
    private long rowCount;
    
    // Average row size in bytes
    private double averageRowSize;
    
    // Timestamp of when statistics were last updated
    private long lastUpdated;
    
    // Number of pages/blocks used by the table (for storage cost estimation)
    private long pageCount;
    
    /**
     * Default constructor
     */
    public TableStatistics() {
        this.rowCount = 0;
        this.averageRowSize = 0;
        this.lastUpdated = 0;
        this.pageCount = 0;
    }
    
    /**
     * Get the number of rows in the table
     * @return Row count
     */
    public long getRowCount() {
        return rowCount;
    }
    
    /**
     * Set the number of rows in the table
     * @param rowCount Row count
     */
    public void setRowCount(long rowCount) {
        this.rowCount = rowCount;
    }
    
    /**
     * Get the average row size in bytes
     * @return Average row size
     */
    public double getAverageRowSize() {
        return averageRowSize;
    }
    
    /**
     * Set the average row size in bytes
     * @param averageRowSize Average row size
     */
    public void setAverageRowSize(double averageRowSize) {
        this.averageRowSize = averageRowSize;
    }
    
    /**
     * Get the timestamp of when statistics were last updated
     * @return Last updated timestamp
     */
    public long getLastUpdated() {
        return lastUpdated;
    }
    
    /**
     * Set the timestamp of when statistics were last updated
     * @param lastUpdated Last updated timestamp
     */
    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
    
    /**
     * Get the number of pages/blocks used by the table
     * @return Page count
     */
    public long getPageCount() {
        return pageCount;
    }
    
    /**
     * Set the number of pages/blocks used by the table
     * @param pageCount Page count
     */
    public void setPageCount(long pageCount) {
        this.pageCount = pageCount;
    }
    
    /**
     * Check if statistics are stale based on a threshold
     * @param thresholdMillis Threshold in milliseconds
     * @return True if statistics are stale
     */
    public boolean isStale(long thresholdMillis) {
        return System.currentTimeMillis() - lastUpdated > thresholdMillis;
    }
    
    /**
     * Estimate the cost of a full table scan
     * @return Estimated cost (higher values indicate more expensive operations)
     */
    public double estimateFullScanCost() {
        // Simple cost model: proportional to row count
        return rowCount;
    }
    
    /**
     * Estimate the number of pages that need to be read for a given number of rows
     * @param rows Number of rows to read
     * @return Estimated number of pages
     */
    public long estimatePageReads(long rows) {
        if (rowCount <= 0 || pageCount <= 0) {
            return 1; // Default if no statistics available
        }
        
        // Estimate based on the ratio of rows to pages
        double rowsPerPage = (double) rowCount / pageCount;
        return Math.max(1, Math.round(rows / rowsPerPage));
    }
    
    @Override
    public String toString() {
        return "TableStatistics{" +
                "rowCount=" + rowCount +
                ", averageRowSize=" + averageRowSize +
                ", lastUpdated=" + lastUpdated +
                ", pageCount=" + pageCount +
                '}';
    }
}