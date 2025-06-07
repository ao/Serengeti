package com.ataiva.serengeti.query.optimizer;

import java.util.ArrayList;
import java.util.List;

/**
 * QueryPlan represents a complete execution plan for a query.
 * It contains a sequence of operations to be performed and metadata about the plan.
 */
public class QueryPlan {
    // Plan type (e.g., FULL_TABLE_SCAN, INDEX_SCAN, etc.)
    private QueryPlanType planType;
    
    // Database name
    private String database;
    
    // Table name(s)
    private String table;
    
    // Columns to select
    private String selectColumns;
    
    // Column in WHERE clause
    private String whereColumn;
    
    // Value in WHERE clause
    private String whereValue;
    
    // Operator in WHERE clause (=, >, <, etc.)
    private String whereOperator;
    
    // Join column (if applicable)
    private String joinColumn;
    
    // Estimated number of rows in the result
    private long estimatedRows;
    
    // Estimated cost of the plan
    private double estimatedCost;
    
    // Sequence of operations to execute
    private List<QueryOperation> operations;
    
    // Explanation of the plan for debugging/logging
    private String explanation;
    
    // Estimated memory usage in bytes
    private long estimatedMemoryUsage;
    
    /**
     * Default constructor
     */
    public QueryPlan() {
        this.planType = QueryPlanType.UNKNOWN;
        this.database = "";
        this.table = "";
        this.selectColumns = "*";
        this.whereColumn = "";
        this.whereValue = "";
        this.whereOperator = "=";
        this.joinColumn = "";
        this.estimatedRows = 0;
        this.estimatedCost = 0;
        this.operations = new ArrayList<>();
        this.explanation = "";
        this.estimatedMemoryUsage = 0;
    }
    
    /**
     * Get the plan type
     * @return Plan type
     */
    public QueryPlanType getPlanType() {
        return planType;
    }
    
    /**
     * Set the plan type
     * @param planType Plan type
     */
    public void setPlanType(QueryPlanType planType) {
        this.planType = planType;
    }
    
    /**
     * Get the database name
     * @return Database name
     */
    public String getDatabase() {
        return database;
    }
    
    /**
     * Set the database name
     * @param database Database name
     */
    public void setDatabase(String database) {
        this.database = database;
    }
    
    /**
     * Get the table name(s)
     * @return Table name(s)
     */
    public String getTable() {
        return table;
    }
    
    /**
     * Set the table name(s)
     * @param table Table name(s)
     */
    public void setTable(String table) {
        this.table = table;
    }
    
    /**
     * Get the columns to select
     * @return Select columns
     */
    public String getSelectColumns() {
        return selectColumns;
    }
    
    /**
     * Set the columns to select
     * @param selectColumns Select columns
     */
    public void setSelectColumns(String selectColumns) {
        this.selectColumns = selectColumns;
    }
    
    /**
     * Get the column in WHERE clause
     * @return Where column
     */
    public String getWhereColumn() {
        return whereColumn;
    }
    
    /**
     * Set the column in WHERE clause
     * @param whereColumn Where column
     */
    public void setWhereColumn(String whereColumn) {
        this.whereColumn = whereColumn;
    }
    
    /**
     * Get the value in WHERE clause
     * @return Where value
     */
    public String getWhereValue() {
        return whereValue;
    }
    
    /**
     * Set the value in WHERE clause
     * @param whereValue Where value
     */
    public void setWhereValue(String whereValue) {
        this.whereValue = whereValue;
    }
    
    /**
     * Get the operator in WHERE clause
     * @return Where operator
     */
    public String getWhereOperator() {
        return whereOperator;
    }
    
    /**
     * Set the operator in WHERE clause
     * @param whereOperator Where operator
     */
    public void setWhereOperator(String whereOperator) {
        this.whereOperator = whereOperator;
    }
    
    /**
     * Get the join column
     * @return Join column
     */
    public String getJoinColumn() {
        return joinColumn;
    }
    
    /**
     * Set the join column
     * @param joinColumn Join column
     */
    public void setJoinColumn(String joinColumn) {
        this.joinColumn = joinColumn;
    }
    
    /**
     * Get the estimated number of rows in the result
     * @return Estimated rows
     */
    public long getEstimatedRows() {
        return estimatedRows;
    }
    
    /**
     * Set the estimated number of rows in the result
     * @param estimatedRows Estimated rows
     */
    public void setEstimatedRows(long estimatedRows) {
        this.estimatedRows = estimatedRows;
    }
    
    /**
     * Get the estimated cost of the plan
     * @return Estimated cost
     */
    public double getEstimatedCost() {
        return estimatedCost;
    }
    
    /**
     * Set the estimated cost of the plan
     * @param estimatedCost Estimated cost
     */
    public void setEstimatedCost(double estimatedCost) {
        this.estimatedCost = estimatedCost;
    }
    
    /**
     * Get the sequence of operations to execute
     * @return List of operations
     */
    public List<QueryOperation> getOperations() {
        return operations;
    }
    
    /**
     * Set the sequence of operations to execute
     * @param operations List of operations
     */
    public void setOperations(List<QueryOperation> operations) {
        this.operations = operations;
    }
    
    /**
     * Add an operation to the plan
     * @param operation Operation to add
     */
    public void addOperation(QueryOperation operation) {
        this.operations.add(operation);
    }
    
    /**
     * Get the explanation of the plan
     * @return Explanation
     */
    public String getExplanation() {
        return explanation;
    }
    
    /**
     * Set the explanation of the plan
     * @param explanation Explanation
     */
    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }
    
    /**
     * Get the estimated memory usage in bytes
     * @return Estimated memory usage
     */
    public long getEstimatedMemoryUsage() {
        return estimatedMemoryUsage;
    }
    
    /**
     * Set the estimated memory usage in bytes
     * @param estimatedMemoryUsage Estimated memory usage
     */
    public void setEstimatedMemoryUsage(long estimatedMemoryUsage) {
        this.estimatedMemoryUsage = estimatedMemoryUsage;
    }
    
    /**
     * Generate a human-readable explanation of the plan
     * @return Plan explanation
     */
    public String generateExplanation() {
        StringBuilder sb = new StringBuilder();
        sb.append("Query Plan: ").append(planType).append("\n");
        sb.append("Estimated cost: ").append(estimatedCost).append("\n");
        sb.append("Estimated rows: ").append(estimatedRows).append("\n");
        sb.append("Operations:\n");
        
        for (int i = 0; i < operations.size(); i++) {
            QueryOperation op = operations.get(i);
            sb.append("  ").append(i + 1).append(". ").append(op.toString()).append("\n");
        }
        
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return "QueryPlan{" +
                "planType=" + planType +
                ", database='" + database + '\'' +
                ", table='" + table + '\'' +
                ", estimatedRows=" + estimatedRows +
                ", estimatedCost=" + estimatedCost +
                ", operations=" + operations.size() +
                '}';
    }
}