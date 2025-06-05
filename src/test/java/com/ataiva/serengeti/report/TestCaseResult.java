package com.ataiva.serengeti.report;

/**
 * Class representing the result of a single test case.
 */
public class TestCaseResult {
    private final String name;
    private final String className;
    private final double time;
    private final TestStatus status;
    private final String message;
    private final String type;
    private final String stackTrace;
    
    /**
     * Creates a new test case result.
     * 
     * @param name The name of the test case
     * @param className The class name of the test case
     * @param time The execution time of the test case in seconds
     * @param status The status of the test case
     * @param message The message associated with the test case (e.g., error message)
     * @param type The type of failure (if any)
     * @param stackTrace The stack trace (if any)
     */
    public TestCaseResult(String name, String className, double time, TestStatus status, 
                          String message, String type, String stackTrace) {
        this.name = name;
        this.className = className;
        this.time = time;
        this.status = status;
        this.message = message;
        this.type = type;
        this.stackTrace = stackTrace;
    }
    
    /**
     * Gets the name of the test case.
     * 
     * @return The name of the test case
     */
    public String getName() {
        return name;
    }
    
    /**
     * Gets the class name of the test case.
     * 
     * @return The class name of the test case
     */
    public String getClassName() {
        return className;
    }
    
    /**
     * Gets the execution time of the test case in seconds.
     * 
     * @return The execution time of the test case
     */
    public double getTime() {
        return time;
    }
    
    /**
     * Gets the status of the test case.
     * 
     * @return The status of the test case
     */
    public TestStatus getStatus() {
        return status;
    }
    
    /**
     * Gets the message associated with the test case.
     * 
     * @return The message associated with the test case
     */
    public String getMessage() {
        return message;
    }
    
    /**
     * Gets the type of failure (if any).
     * 
     * @return The type of failure
     */
    public String getType() {
        return type;
    }
    
    /**
     * Gets the stack trace (if any).
     * 
     * @return The stack trace
     */
    public String getStackTrace() {
        return stackTrace;
    }
}