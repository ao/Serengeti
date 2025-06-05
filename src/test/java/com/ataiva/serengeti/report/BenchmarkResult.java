package com.ataiva.serengeti.report;

/**
 * Class representing a benchmark result.
 */
public class BenchmarkResult {
    private final String name;
    private final String mode;
    private final double score;
    private final double error;
    private final String units;
    
    /**
     * Creates a new benchmark result.
     * 
     * @param name The name of the benchmark
     * @param mode The benchmark mode (e.g., "thrpt", "avgt")
     * @param score The benchmark score
     * @param error The error margin
     * @param units The units of measurement
     */
    public BenchmarkResult(String name, String mode, double score, double error, String units) {
        this.name = name;
        this.mode = mode;
        this.score = score;
        this.error = error;
        this.units = units;
    }
    
    /**
     * Gets the name of the benchmark.
     * 
     * @return The name of the benchmark
     */
    public String getName() {
        return name;
    }
    
    /**
     * Gets the benchmark mode.
     * 
     * @return The benchmark mode
     */
    public String getMode() {
        return mode;
    }
    
    /**
     * Gets the benchmark score.
     * 
     * @return The benchmark score
     */
    public double getScore() {
        return score;
    }
    
    /**
     * Gets the error margin.
     * 
     * @return The error margin
     */
    public double getError() {
        return error;
    }
    
    /**
     * Gets the units of measurement.
     * 
     * @return The units of measurement
     */
    public String getUnits() {
        return units;
    }
}