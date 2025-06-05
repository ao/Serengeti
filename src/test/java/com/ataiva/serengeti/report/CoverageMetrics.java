package com.ataiva.serengeti.report;

/**
 * Class representing code coverage metrics.
 */
public class CoverageMetrics {
    private double lineCoverage;
    private double branchCoverage;
    private double methodCoverage;
    private double classCoverage;
    private boolean initialized;
    
    /**
     * Creates a new coverage metrics object with default values.
     */
    public CoverageMetrics() {
        this.lineCoverage = 0;
        this.branchCoverage = 0;
        this.methodCoverage = 0;
        this.classCoverage = 0;
        this.initialized = false;
    }
    
    /**
     * Sets the line coverage percentage.
     * 
     * @param lineCoverage The line coverage percentage
     */
    public void setLineCoverage(double lineCoverage) {
        this.lineCoverage = lineCoverage;
        this.initialized = true;
    }
    
    /**
     * Sets the branch coverage percentage.
     * 
     * @param branchCoverage The branch coverage percentage
     */
    public void setBranchCoverage(double branchCoverage) {
        this.branchCoverage = branchCoverage;
        this.initialized = true;
    }
    
    /**
     * Sets the method coverage percentage.
     * 
     * @param methodCoverage The method coverage percentage
     */
    public void setMethodCoverage(double methodCoverage) {
        this.methodCoverage = methodCoverage;
        this.initialized = true;
    }
    
    /**
     * Sets the class coverage percentage.
     * 
     * @param classCoverage The class coverage percentage
     */
    public void setClassCoverage(double classCoverage) {
        this.classCoverage = classCoverage;
        this.initialized = true;
    }
    
    /**
     * Gets the line coverage percentage.
     * 
     * @return The line coverage percentage
     */
    public double getLineCoverage() {
        return lineCoverage;
    }
    
    /**
     * Gets the branch coverage percentage.
     * 
     * @return The branch coverage percentage
     */
    public double getBranchCoverage() {
        return branchCoverage;
    }
    
    /**
     * Gets the method coverage percentage.
     * 
     * @return The method coverage percentage
     */
    public double getMethodCoverage() {
        return methodCoverage;
    }
    
    /**
     * Gets the class coverage percentage.
     * 
     * @return The class coverage percentage
     */
    public double getClassCoverage() {
        return classCoverage;
    }
    
    /**
     * Checks if any coverage metrics have been set.
     * 
     * @return True if any coverage metrics have been set, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }
}