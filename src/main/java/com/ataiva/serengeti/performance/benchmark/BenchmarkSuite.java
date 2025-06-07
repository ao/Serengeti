package com.ataiva.serengeti.performance.benchmark;

import java.util.Map;

/**
 * Interface for benchmark suites in the Serengeti performance testing framework.
 * A benchmark suite contains a collection of related benchmarks that test
 * a specific component or functionality of the system.
 */
public interface BenchmarkSuite {
    
    /**
     * Gets the name of the benchmark suite.
     *
     * @return The benchmark suite name
     */
    String getName();
    
    /**
     * Gets a description of the benchmark suite.
     *
     * @return The benchmark suite description
     */
    String getDescription();
    
    /**
     * Configures the benchmark suite with the provided settings.
     *
     * @param config A map of configuration parameters
     */
    void configure(Map<String, Object> config);
    
    /**
     * Runs the benchmark suite and returns the results.
     *
     * @return The benchmark results
     * @throws Exception if an error occurs during benchmark execution
     */
    BenchmarkResult run() throws Exception;
    
    /**
     * Gets the list of parameter sets for parameterized benchmarks.
     * Each parameter set represents a different configuration to test.
     *
     * @return A map of parameter sets, where the key is the parameter set name
     *         and the value is a map of parameter names to values
     */
    Map<String, Map<String, Object>> getParameterSets();
    
    /**
     * Sets the parameter sets for parameterized benchmarks.
     *
     * @param parameterSets A map of parameter sets
     */
    void setParameterSets(Map<String, Map<String, Object>> parameterSets);
}