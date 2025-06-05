package com.ataiva.serengeti.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Class representing the result of a test suite.
 */
public class TestSuiteResult {
    private final String name;
    private final int tests;
    private final int failures;
    private final int errors;
    private final int skipped;
    private final double time;
    private final List<TestCaseResult> testCases;
    
    /**
     * Creates a new test suite result.
     * 
     * @param name The name of the test suite
     * @param tests The total number of tests in the suite
     * @param failures The number of failed tests
     * @param errors The number of tests with errors
     * @param skipped The number of skipped tests
     * @param time The execution time of the test suite in seconds
     */
    public TestSuiteResult(String name, int tests, int failures, int errors, int skipped, double time) {
        this.name = name;
        this.tests = tests;
        this.failures = failures;
        this.errors = errors;
        this.skipped = skipped;
        this.time = time;
        this.testCases = new ArrayList<>();
    }
    
    /**
     * Adds a test case result to this test suite.
     * 
     * @param testCase The test case result to add
     */
    public void addTestCase(TestCaseResult testCase) {
        testCases.add(testCase);
    }
    
    /**
     * Gets the name of the test suite.
     * 
     * @return The name of the test suite
     */
    public String getName() {
        return name;
    }
    
    /**
     * Gets the total number of tests in the suite.
     * 
     * @return The total number of tests
     */
    public int getTests() {
        return tests;
    }
    
    /**
     * Gets the number of failed tests.
     * 
     * @return The number of failed tests
     */
    public int getFailures() {
        return failures;
    }
    
    /**
     * Gets the number of tests with errors.
     * 
     * @return The number of tests with errors
     */
    public int getErrors() {
        return errors;
    }
    
    /**
     * Gets the number of skipped tests.
     * 
     * @return The number of skipped tests
     */
    public int getSkipped() {
        return skipped;
    }
    
    /**
     * Gets the execution time of the test suite in seconds.
     * 
     * @return The execution time of the test suite
     */
    public double getTime() {
        return time;
    }
    
    /**
     * Gets the list of test case results in this suite.
     * 
     * @return The list of test case results
     */
    public List<TestCaseResult> getTestCases() {
        return testCases;
    }
}