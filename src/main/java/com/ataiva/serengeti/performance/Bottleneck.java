package com.ataiva.serengeti.performance;

/**
 * Represents a performance bottleneck detected in the system.
 * This class encapsulates information about a specific performance issue,
 * including its location, description, and severity.
 */
public class Bottleneck {
    private final String component;
    private final String operation;
    private final String title;
    private final String description;
    private final double severity;
    private final BottleneckType type;
    
    /**
     * Enum representing the type of bottleneck.
     */
    public enum BottleneckType {
        LATENCY,
        CPU,
        MEMORY,
        IO,
        ERROR
    }
    
    /**
     * Creates a new bottleneck.
     *
     * @param component The component where the bottleneck was detected
     * @param operation The operation where the bottleneck was detected
     * @param title A short title describing the bottleneck
     * @param description A detailed description of the bottleneck
     * @param severity The severity of the bottleneck (0.0-1.0)
     * @param type The type of bottleneck
     */
    public Bottleneck(String component, String operation, String title, String description, 
                     double severity, BottleneckType type) {
        this.component = component;
        this.operation = operation;
        this.title = title;
        this.description = description;
        this.severity = severity;
        this.type = type;
    }
    
    /**
     * Gets the component where the bottleneck was detected.
     *
     * @return The component name
     */
    public String getComponent() {
        return component;
    }
    
    /**
     * Gets the operation where the bottleneck was detected.
     *
     * @return The operation name
     */
    public String getOperation() {
        return operation;
    }
    
    /**
     * Gets the title of the bottleneck.
     *
     * @return The title
     */
    public String getTitle() {
        return title;
    }
    
    /**
     * Gets the description of the bottleneck.
     *
     * @return The description
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Gets the severity of the bottleneck.
     *
     * @return The severity (0.0-1.0)
     */
    public double getSeverity() {
        return severity;
    }
    
    /**
     * Gets the type of bottleneck.
     *
     * @return The bottleneck type
     */
    public BottleneckType getType() {
        return type;
    }
    
    @Override
    public String toString() {
        return String.format("[%s] %s.%s: %s (Severity: %.2f)", 
                           type, component, operation, title, severity);
    }
}