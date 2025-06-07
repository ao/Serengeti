package com.ataiva.serengeti.query.parser.ast;

/**
 * Represents a WHERE clause in a SQL query.
 */
public class WhereNode extends Node {
    private final String column;
    private final String operator;
    private final String value;
    
    /**
     * Constructor
     * @param column Column name
     * @param operator Operator (=, >, <, etc.)
     * @param value Value to compare with
     */
    public WhereNode(String column, String operator, String value) {
        this.column = column;
        this.operator = operator;
        this.value = value;
    }
    
    /**
     * Get the column name
     * @return Column name
     */
    public String getColumn() {
        return column;
    }
    
    /**
     * Get the operator
     * @return Operator
     */
    public String getOperator() {
        return operator;
    }
    
    /**
     * Get the value
     * @return Value
     */
    public String getValue() {
        return value;
    }
    
    /**
     * Check if this is an equality condition
     * @return True if the operator is =, false otherwise
     */
    public boolean isEquality() {
        return "=".equals(operator);
    }
    
    /**
     * Check if this is a range condition
     * @return True if the operator is >, <, >=, or <=, false otherwise
     */
    public boolean isRange() {
        return ">".equals(operator) || "<".equals(operator) || 
               ">=".equals(operator) || "<=".equals(operator);
    }
    
    /**
     * Check if this is a LIKE condition
     * @return True if the operator is LIKE, false otherwise
     */
    public boolean isLike() {
        return "LIKE".equalsIgnoreCase(operator);
    }
    
    /**
     * Check if this is an IN condition
     * @return True if the operator is IN, false otherwise
     */
    public boolean isIn() {
        return "IN".equalsIgnoreCase(operator);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("WHERE ");
        sb.append(column);
        sb.append(" ");
        sb.append(operator);
        sb.append(" ");
        
        // Add quotes for string values in the output
        if (value.matches("^[0-9]+$") || value.matches("^[0-9]*\\.[0-9]+$")) {
            sb.append(value);
        } else {
            sb.append("'").append(value).append("'");
        }
        
        return sb.toString();
    }
}