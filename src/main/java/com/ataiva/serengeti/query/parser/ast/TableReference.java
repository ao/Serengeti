package com.ataiva.serengeti.query.parser.ast;

/**
 * Represents a reference to a table in a SQL query.
 */
public class TableReference {
    private final String database;
    private final String table;
    private final String alias;
    
    /**
     * Constructor with database and table
     * @param database Database name (can be null for default database)
     * @param table Table name
     */
    public TableReference(String database, String table) {
        this(database, table, null);
    }
    
    /**
     * Constructor with database, table, and alias
     * @param database Database name (can be null for default database)
     * @param table Table name
     * @param alias Table alias (can be null if no alias)
     */
    public TableReference(String database, String table, String alias) {
        this.database = database;
        this.table = table;
        this.alias = alias;
    }
    
    /**
     * Get the database name
     * @return Database name or null if not specified
     */
    public String getDatabase() {
        return database;
    }
    
    /**
     * Get the table name
     * @return Table name
     */
    public String getTable() {
        return table;
    }
    
    /**
     * Get the table alias
     * @return Table alias or null if not specified
     */
    public String getAlias() {
        return alias;
    }
    
    /**
     * Check if this table reference has an alias
     * @return True if it has an alias, false otherwise
     */
    public boolean hasAlias() {
        return alias != null && !alias.isEmpty();
    }
    
    /**
     * Get the effective name to use for this table in the query
     * @return Alias if present, otherwise table name
     */
    public String getEffectiveName() {
        return hasAlias() ? alias : table;
    }
    
    /**
     * Get the fully qualified name (database.table)
     * @return Fully qualified name or just table name if database is null
     */
    public String getFullyQualifiedName() {
        return database != null ? database + "." + table : table;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (database != null) {
            sb.append(database).append(".");
        }
        sb.append(table);
        if (hasAlias()) {
            sb.append(" AS ").append(alias);
        }
        return sb.toString();
    }
}