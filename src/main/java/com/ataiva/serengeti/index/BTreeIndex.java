package com.ataiva.serengeti.index;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.json.JSONObject;

import com.ataiva.serengeti.helpers.Globals;

/**
 * BTreeIndex implements a B-tree based indexing system for efficient lookups
 * in the Serengeti database system. It maps column values to row IDs for fast
 * query processing.
 */
public class BTreeIndex implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // B-tree order (maximum number of children per node)
    private static final int ORDER = 128;
    
    private String databaseName;
    private String tableName;
    private String columnName;
    private Node root;
    private int size;
    
    /**
     * Node class for the B-tree
     */
    private static class Node implements Serializable {
        private static final long serialVersionUID = 1L;
        
        boolean isLeaf;
        int keyCount;
        Comparable[] keys;
        Object[] values;  // For leaf nodes: Set<String> of row IDs, for internal nodes: Node references
        Node[] children;
        
        // Constructor for creating a new node
        Node(boolean isLeaf) {
            this.isLeaf = isLeaf;
            this.keyCount = 0;
            this.keys = new Comparable[ORDER - 1];
            
            if (isLeaf) {
                this.values = new HashSet[ORDER - 1];
            } else {
                this.children = new Node[ORDER];
            }
        }
    }
    
    /**
     * Creates a new BTreeIndex for the specified database, table, and column
     */
    public BTreeIndex(String databaseName, String tableName, String columnName) {
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.columnName = columnName;
        this.root = new Node(true);
        this.size = 0;
    }
    
    /**
     * Returns the name of the column this index is for
     */
    public String getColumnName() {
        return columnName;
    }
    
    /**
     * Returns the number of entries in the index
     */
    public int size() {
        return size;
    }
    
    /**
     * Inserts a value-rowId pair into the index
     * 
     * @param value The column value
     * @param rowId The row ID
     */
    @SuppressWarnings("unchecked")
    public void insert(Comparable value, String rowId) {
        if (value == null) {
            return;  // Don't index null values
        }
        
        Node node = root;
        
        // Handle root split if needed
        if (node.keyCount == ORDER - 1) {
            Node newRoot = new Node(false);
            newRoot.children[0] = root;
            root = newRoot;
            splitChild(newRoot, 0);
            node = newRoot;
        }
        
        // Find the leaf node where the value should be inserted
        while (!node.isLeaf) {
            int i = node.keyCount - 1;
            while (i >= 0 && value.compareTo(node.keys[i]) < 0) {
                i--;
            }
            i++;
            
            Node child = node.children[i];
            if (child.keyCount == ORDER - 1) {
                splitChild(node, i);
                if (value.compareTo(node.keys[i]) > 0) {
                    i++;
                }
            }
            node = node.children[i];
        }
        
        // Insert into leaf node
        int i = node.keyCount - 1;
        while (i >= 0 && value.compareTo(node.keys[i]) < 0) {
            node.keys[i + 1] = node.keys[i];
            node.values[i + 1] = node.values[i];
            i--;
        }
        
        // Check if key already exists
        if (i >= 0 && value.compareTo(node.keys[i]) == 0) {
            // Key exists, add rowId to the set
            Set<String> rowIds = (Set<String>) node.values[i];
            if (rowIds == null) {
                rowIds = new HashSet<>();
                node.values[i] = rowIds;
            }
            rowIds.add(rowId);
        } else {
            // New key, insert it
            i++;
            node.keys[i] = value;
            Set<String> rowIds = new HashSet<>();
            rowIds.add(rowId);
            node.values[i] = rowIds;
            node.keyCount++;
            size++;
        }
    }
    
    /**
     * Splits a child node during insertion
     */
    @SuppressWarnings("unchecked")
    private void splitChild(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node newChild = new Node(child.isLeaf);
        
        // Move half of the keys and values to the new child
        int midpoint = (ORDER - 1) / 2;
        newChild.keyCount = midpoint;
        
        for (int i = 0; i < midpoint; i++) {
            newChild.keys[i] = child.keys[i + midpoint + 1];
            if (child.isLeaf) {
                newChild.values[i] = child.values[i + midpoint + 1];
            }
        }
        
        // If not leaf, move children too
        if (!child.isLeaf) {
            for (int i = 0; i <= midpoint; i++) {
                newChild.children[i] = child.children[i + midpoint + 1];
            }
        }
        
        child.keyCount = midpoint;
        
        // Insert the new child into the parent
        for (int i = parent.keyCount; i > childIndex; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[childIndex + 1] = newChild;
        
        for (int i = parent.keyCount - 1; i >= childIndex; i--) {
            parent.keys[i + 1] = parent.keys[i];
            if (parent.isLeaf) {
                parent.values[i + 1] = parent.values[i];
            }
        }
        
        parent.keys[childIndex] = child.keys[midpoint];
        if (child.isLeaf) {
            parent.values[childIndex] = child.values[midpoint];
            // Clear the moved key/value from child
            child.keys[midpoint] = null;
            child.values[midpoint] = null;
        }
        
        parent.keyCount++;
    }
    
    /**
     * Removes a value-rowId pair from the index
     * 
     * @param value The column value
     * @param rowId The row ID
     * @return true if the pair was removed, false otherwise
     */
    @SuppressWarnings("unchecked")
    public boolean remove(Comparable value, String rowId) {
        if (value == null) {
            return false;
        }
        
        // Find the leaf node containing the value
        Node node = root;
        int index = -1;
        
        while (node != null) {
            // Find the index of the key in the current node
            index = -1;
            for (int i = 0; i < node.keyCount; i++) {
                if (value.compareTo(node.keys[i]) == 0) {
                    index = i;
                    break;
                }
            }
            
            if (index != -1 && node.isLeaf) {
                // Found the key in a leaf node
                Set<String> rowIds = (Set<String>) node.values[index];
                if (rowIds != null && rowIds.remove(rowId)) {
                    // If the set is now empty, remove the key
                    if (rowIds.isEmpty()) {
                        // Remove the key by shifting all keys after it
                        for (int i = index; i < node.keyCount - 1; i++) {
                            node.keys[i] = node.keys[i + 1];
                            node.values[i] = node.values[i + 1];
                        }
                        node.keys[node.keyCount - 1] = null;
                        node.values[node.keyCount - 1] = null;
                        node.keyCount--;
                        size--;
                    }
                    return true;
                }
                return false;
            }
            
            // If not found or not a leaf, navigate to the appropriate child
            if (node.isLeaf) {
                return false;  // Not found in leaf
            }
            
            // Find the child to navigate to
            int childIndex = 0;
            while (childIndex < node.keyCount && value.compareTo(node.keys[childIndex]) > 0) {
                childIndex++;
            }
            
            node = node.children[childIndex];
        }
        
        return false;
    }
    
    /**
     * Finds all row IDs that match the given value
     * 
     * @param value The column value to search for
     * @return Set of row IDs matching the value, or empty set if none found
     */
    @SuppressWarnings("unchecked")
    public Set<String> find(Comparable value) {
        if (value == null) {
            return Collections.emptySet();
        }
        
        Node node = root;
        
        while (node != null) {
            int i = 0;
            while (i < node.keyCount && value.compareTo(node.keys[i]) > 0) {
                i++;
            }
            
            if (i < node.keyCount && value.compareTo(node.keys[i]) == 0) {
                if (node.isLeaf) {
                    Set<String> result = (Set<String>) node.values[i];
                    return result != null ? result : Collections.emptySet();
                }
            }
            
            if (node.isLeaf) {
                return Collections.emptySet();
            } else {
                node = node.children[i];
            }
        }
        
        return Collections.emptySet();
    }
    
    /**
     * Finds all row IDs with values in the given range (inclusive)
     * 
     * @param fromValue The lower bound (inclusive)
     * @param toValue The upper bound (inclusive)
     * @return Set of row IDs in the range
     */
    @SuppressWarnings("unchecked")
    public Set<String> findRange(Comparable fromValue, Comparable toValue) {
        if (fromValue == null || toValue == null || fromValue.compareTo(toValue) > 0) {
            return Collections.emptySet();
        }
        
        Set<String> result = new HashSet<>();
        findRangeRecursive(root, fromValue, toValue, result);
        return result;
    }
    
    /**
     * Helper method for range queries
     */
    @SuppressWarnings("unchecked")
    private void findRangeRecursive(Node node, Comparable fromValue, Comparable toValue, Set<String> result) {
        if (node == null) {
            return;
        }
        
        int i = 0;
        
        // Find the first key >= fromValue
        while (i < node.keyCount && fromValue.compareTo(node.keys[i]) > 0) {
            i++;
        }
        
        // If this is a leaf node, collect all keys in range
        if (node.isLeaf) {
            while (i < node.keyCount && toValue.compareTo(node.keys[i]) >= 0) {
                Set<String> rowIds = (Set<String>) node.values[i];
                if (rowIds != null) {
                    result.addAll(rowIds);
                }
                i++;
            }
        } else {
            // If this is an internal node, recurse on relevant children
            if (i < node.keyCount && fromValue.compareTo(node.keys[i]) <= 0) {
                findRangeRecursive(node.children[i], fromValue, toValue, result);
            }
            
            while (i < node.keyCount && toValue.compareTo(node.keys[i]) >= 0) {
                Set<String> rowIds = (Set<String>) node.values[i];
                if (rowIds != null) {
                    result.addAll(rowIds);
                }
                
                if (i + 1 < node.keyCount) {
                    findRangeRecursive(node.children[i + 1], fromValue, toValue, result);
                }
                i++;
            }
            
            if (i < node.keyCount + 1) {
                findRangeRecursive(node.children[i], fromValue, toValue, result);
            }
        }
    }
    
    /**
     * Saves the index to disk
     */
    public void saveToDisk() {
        try {
            String indexDir = Globals.data_path + databaseName + "/" + tableName + "/indexes/";
            File dir = new File(indexDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            String indexPath = indexDir + columnName + ".idx";
            FileOutputStream fos = new FileOutputStream(indexPath);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(this);
            oos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Loads an index from disk
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @return The loaded index, or null if it doesn't exist
     */
    public static BTreeIndex loadFromDisk(String databaseName, String tableName, String columnName) {
        try {
            String indexPath = Globals.data_path + databaseName + "/" + tableName + "/indexes/" + columnName + ".idx";
            Path path = Paths.get(indexPath);
            
            if (!Files.exists(path)) {
                return null;
            }
            
            byte[] data = Files.readAllBytes(path);
            Object obj = Globals.convertFromBytes(data);
            
            if (obj instanceof BTreeIndex) {
                return (BTreeIndex) obj;
            }
        } catch (Exception e) {
            System.out.println("Error loading index: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Rebuilds the index from the table data
     * 
     * @param tableData Map of row IDs to JSON data
     */
    @SuppressWarnings("unchecked")
    public void rebuild(Map<String, String> tableData) {
        // Clear the current index
        this.root = new Node(true);
        this.size = 0;
        
        // Rebuild from table data
        for (Map.Entry<String, String> entry : tableData.entrySet()) {
            String rowId = entry.getKey();
            String jsonStr = entry.getValue();
            
            try {
                JSONObject json = new JSONObject(jsonStr);
                if (json.has(columnName)) {
                    Object value = json.get(columnName);
                    if (value != null) {
                        if (value instanceof Number) {
                            insert((Comparable) value, rowId);
                        } else {
                            insert(value.toString(), rowId);
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Error rebuilding index: " + e.getMessage());
            }
        }
    }
}