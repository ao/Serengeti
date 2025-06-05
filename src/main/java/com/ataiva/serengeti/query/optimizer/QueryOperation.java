package com.ataiva.serengeti.query.optimizer;

import java.util.Arrays;

/**
 * QueryOperation represents a single operation in a query execution plan.
 * Examples include table scans, index lookups, filters, joins, etc.
 */
public class QueryOperation {
    // Operation type
    private QueryOperationType operationType;
    
    // Target table for the operation
    private String targetTable;
    
    // Index column (for index operations)
    private String indexColumn;
    
    // Filter column (for filter operations)
    private String filterColumn;
    
    // Filter value (for filter operations)
    private String filterValue;
    
    // Filter operator (for filter operations)
    private String filterOperator;
    
    // Join column (for join operations)
    private String joinColumn;
    
    // Projection columns (for projection operations)
    private String[] projectColumns;
    
    // Estimated cost of this operation
    private double estimatedCost;
    
    // Estimated number of rows produced by this operation
    private long estimatedRows;
    
    /**
     * Constructor with operation type
     * @param operationType Operation type
     */
    public QueryOperation(QueryOperationType operationType) {
        this.operationType = operationType;
        this.targetTable = "";
        this.indexColumn = "";
        this.filterColumn = "";
        this.filterValue = "";
        this.filterOperator = "=";
        this.joinColumn = "";
        this.projectColumns = new String[0];
        this.estimatedCost = 0;
        this.estimatedRows = 0;
    }
    
    /**
     * Get the operation type
     * @return Operation type
     */
    public QueryOperationType getOperationType() {
        return operationType;
    }
    
    /**
     * Set the operation type
     * @param operationType Operation type
     */
    public void setOperationType(QueryOperationType operationType) {
        this.operationType = operationType;
    }
    
    /**
     * Get the target table
     * @return Target table
     */
    public String getTargetTable() {
        return targetTable;
    }
    
    /**
     * Set the target table
     * @param targetTable Target table
     */
    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }
    
    /**
     * Get the index column
     * @return Index column
     */
    public String getIndexColumn() {
        return indexColumn;
    }
    
    /**
     * Set the index column
     * @param indexColumn Index column
     */
    public void setIndexColumn(String indexColumn) {
        this.indexColumn = indexColumn;
    }
    
    /**
     * Get the filter column
     * @return Filter column
     */
    public String getFilterColumn() {
        return filterColumn;
    }
    
    /**
     * Set the filter column
     * @param filterColumn Filter column
     */
    public void setFilterColumn(String filterColumn) {
        this.filterColumn = filterColumn;
    }
    
    /**
     * Get the filter value
     * @return Filter value
     */
    public String getFilterValue() {
        return filterValue;
    }
    
    /**
     * Set the filter value
     * @param filterValue Filter value
     */
    public void setFilterValue(String filterValue) {
        this.filterValue = filterValue;
    }
    
    /**
     * Get the filter operator
     * @return Filter operator
     */
    public String getFilterOperator() {
        return filterOperator;
    }
    
    /**
     * Set the filter operator
     * @param filterOperator Filter operator
     */
    public void setFilterOperator(String filterOperator) {
        this.filterOperator = filterOperator;
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
     * Get the projection columns
     * @return Projection columns
     */
    public String[] getProjectColumns() {
        return projectColumns;
    }
    
    /**
     * Set the projection columns
     * @param projectColumns Projection columns
     */
    public void setProjectColumns(String[] projectColumns) {
        this.projectColumns = projectColumns;
    }
    
    /**
     * Get the estimated cost
     * @return Estimated cost
     */
    public double getEstimatedCost() {
        return estimatedCost;
    }
    
    /**
     * Set the estimated cost
     * @param estimatedCost Estimated cost
     */
    public void setEstimatedCost(double estimatedCost) {
        this.estimatedCost = estimatedCost;
    }
    
    /**
     * Get the estimated rows
     * @return Estimated rows
     */
    public long getEstimatedRows() {
        return estimatedRows;
    }
    
    /**
     * Set the estimated rows
     * @param estimatedRows Estimated rows
     */
    public void setEstimatedRows(long estimatedRows) {
        this.estimatedRows = estimatedRows;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(operationType);
        
        switch (operationType) {
            case TABLE_SCAN:
                sb.append(" on table ").append(targetTable);
                break;
            case INDEX_SCAN:
                sb.append(" on table ").append(targetTable)
                  .append(" using index on ").append(indexColumn)
                  .append(" with ").append(filterOperator).append(" ").append(filterValue);
                break;
            case RANGE_SCAN:
                sb.append(" on table ").append(targetTable)
                  .append(" using index on ").append(indexColumn)
                  .append(" with ").append(filterOperator).append(" ").append(filterValue);
                break;
            case FILTER:
                sb.append(" where ").append(filterColumn)
                  .append(" ").append(filterOperator).append(" ").append(filterValue);
                break;
            case NESTED_LOOP_JOIN:
                sb.append(" with table ").append(targetTable)
                  .append(" on column ").append(joinColumn);
                break;
            case HASH_JOIN_PROBE:
                sb.append(" on column ").append(joinColumn);
                break;
            case BUILD_HASH_TABLE:
                sb.append(" on column ").append(joinColumn);
                break;
            case PROJECT:
                sb.append(" columns ").append(Arrays.toString(projectColumns));
                break;
            default:
                break;
        }
        
        if (estimatedCost > 0 || estimatedRows > 0) {
            sb.append(" (cost: ").append(estimatedCost)
              .append(", rows: ").append(estimatedRows).append(")");
        }
        
        return sb.toString();
    }
}