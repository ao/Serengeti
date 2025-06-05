package com.ataiva.serengeti.search;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.json.JSONObject;

import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.schema.TableStorageObject;

/**
 * FullTextSearch provides full-text search capabilities for the Serengeti database system.
 * It implements tokenization, indexing, and searching for text fields.
 */
public class FullTextSearch implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // Map of database.table.column -> inverted index
    // The inverted index maps tokens to document IDs and their relevance scores
    private Map<String, Map<String, Map<String, Double>>> invertedIndices;
    
    // Stop words to exclude from indexing
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", 
        "into", "is", "it", "no", "not", "of", "on", "or", "such", "that", "the", 
        "their", "then", "there", "these", "they", "this", "to", "was", "will", "with"
    ));
    
    // Pattern for tokenizing text
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    
    /**
     * Creates a new FullTextSearch instance
     */
    public FullTextSearch() {
        this.invertedIndices = new ConcurrentHashMap<>();
        loadIndices();
    }
    
    /**
     * Creates a full-text index for a specific column in a table
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @param tableData The table data to index
     * @return true if the index was created, false otherwise
     */
    public boolean createIndex(String databaseName, String tableName, String columnName, Map<String, String> tableData) {
        String indexKey = getIndexKey(databaseName, tableName, columnName);
        
        // Check if index already exists
        if (invertedIndices.containsKey(indexKey)) {
            return false;
        }
        
        // Create the inverted index
        Map<String, Map<String, Double>> invertedIndex = new HashMap<>();
        
        // Build the index from table data
        for (Map.Entry<String, String> entry : tableData.entrySet()) {
            String rowId = entry.getKey();
            String jsonStr = entry.getValue();
            
            try {
                JSONObject json = new JSONObject(jsonStr);
                if (json.has(columnName)) {
                    Object value = json.get(columnName);
                    if (value != null && value instanceof String) {
                        indexDocument(invertedIndex, rowId, (String) value);
                    }
                }
            } catch (Exception e) {
                System.out.println("Error building full-text index: " + e.getMessage());
            }
        }
        
        // Save the index
        invertedIndices.put(indexKey, invertedIndex);
        saveIndices();
        
        System.out.println("Created full-text index on " + databaseName + "." + tableName + "." + columnName);
        return true;
    }
    
    /**
     * Drops a full-text index for a specific column in a table
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @return true if the index was dropped, false otherwise
     */
    public boolean dropIndex(String databaseName, String tableName, String columnName) {
        String indexKey = getIndexKey(databaseName, tableName, columnName);
        
        // Check if index exists
        if (!invertedIndices.containsKey(indexKey)) {
            return false;
        }
        
        // Remove the index
        invertedIndices.remove(indexKey);
        
        // Delete the index file
        try {
            String indexPath = Globals.data_path + databaseName + "/" + tableName + "/fulltext/" + columnName + ".ftidx";
            Path path = Paths.get(indexPath);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            System.out.println("Error deleting full-text index file: " + e.getMessage());
        }
        
        saveIndices();
        System.out.println("Dropped full-text index on " + databaseName + "." + tableName + "." + columnName);
        return true;
    }
    
    /**
     * Updates a full-text index when a row is inserted
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param rowId The row ID
     * @param json The JSON data
     */
    public void handleInsert(String databaseName, String tableName, String rowId, JSONObject json) {
        // Get all full-text indices for this table
        List<String> indexedColumns = getIndexedColumns(databaseName, tableName);
        
        // Update each index
        for (String columnName : indexedColumns) {
            if (json.has(columnName)) {
                Object value = json.get(columnName);
                if (value != null && value instanceof String) {
                    String indexKey = getIndexKey(databaseName, tableName, columnName);
                    Map<String, Map<String, Double>> invertedIndex = invertedIndices.get(indexKey);
                    
                    if (invertedIndex != null) {
                        indexDocument(invertedIndex, rowId, (String) value);
                    }
                }
            }
        }
        
        // Save indices
        saveIndices();
    }
    
    /**
     * Updates full-text indices when a row is updated
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param rowId The row ID
     * @param oldJson The old JSON data
     * @param newJson The new JSON data
     */
    public void handleUpdate(String databaseName, String tableName, String rowId, JSONObject oldJson, JSONObject newJson) {
        // Get all full-text indices for this table
        List<String> indexedColumns = getIndexedColumns(databaseName, tableName);
        
        // Update each index
        for (String columnName : indexedColumns) {
            String indexKey = getIndexKey(databaseName, tableName, columnName);
            Map<String, Map<String, Double>> invertedIndex = invertedIndices.get(indexKey);
            
            if (invertedIndex != null) {
                // Remove old value from index
                if (oldJson.has(columnName)) {
                    Object oldValue = oldJson.get(columnName);
                    if (oldValue != null && oldValue instanceof String) {
                        removeDocumentFromIndex(invertedIndex, rowId, (String) oldValue);
                    }
                }
                
                // Add new value to index
                if (newJson.has(columnName)) {
                    Object newValue = newJson.get(columnName);
                    if (newValue != null && newValue instanceof String) {
                        indexDocument(invertedIndex, rowId, (String) newValue);
                    }
                }
            }
        }
        
        // Save indices
        saveIndices();
    }
    
    /**
     * Updates full-text indices when a row is deleted
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param rowId The row ID
     * @param json The JSON data
     */
    public void handleDelete(String databaseName, String tableName, String rowId, JSONObject json) {
        // Get all full-text indices for this table
        List<String> indexedColumns = getIndexedColumns(databaseName, tableName);
        
        // Update each index
        for (String columnName : indexedColumns) {
            if (json.has(columnName)) {
                Object value = json.get(columnName);
                if (value != null && value instanceof String) {
                    String indexKey = getIndexKey(databaseName, tableName, columnName);
                    Map<String, Map<String, Double>> invertedIndex = invertedIndices.get(indexKey);
                    
                    if (invertedIndex != null) {
                        removeDocumentFromIndex(invertedIndex, rowId, (String) value);
                    }
                }
            }
        }
        
        // Save indices
        saveIndices();
    }
    
    /**
     * Searches for documents matching a query in a full-text index
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @param query The search query
     * @return Map of row IDs to relevance scores, sorted by relevance
     */
    public Map<String, Double> search(String databaseName, String tableName, String columnName, String query) {
        String indexKey = getIndexKey(databaseName, tableName, columnName);
        
        // Check if an index exists for this column
        if (!invertedIndices.containsKey(indexKey)) {
            return Collections.emptyMap();
        }
        
        Map<String, Map<String, Double>> invertedIndex = invertedIndices.get(indexKey);
        
        // Tokenize the query
        List<String> queryTokens = tokenize(query);
        
        // Calculate TF-IDF scores for each document
        Map<String, Double> scores = new HashMap<>();
        int totalDocuments = getTotalDocuments(invertedIndex);
        
        for (String token : queryTokens) {
            if (invertedIndex.containsKey(token)) {
                Map<String, Double> docScores = invertedIndex.get(token);
                int documentFrequency = docScores.size();
                double idf = Math.log((double) totalDocuments / documentFrequency);
                
                for (Map.Entry<String, Double> entry : docScores.entrySet()) {
                    String docId = entry.getKey();
                    double tf = entry.getValue();
                    double tfidf = tf * idf;
                    
                    scores.put(docId, scores.getOrDefault(docId, 0.0) + tfidf);
                }
            }
        }
        
        // Sort by relevance score (descending)
        Map<String, Double> sortedScores = new LinkedHashMap<>();
        scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .forEachOrdered(e -> sortedScores.put(e.getKey(), e.getValue()));
        
        return sortedScores;
    }
    
    /**
     * Checks if a full-text index exists for a specific column
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @return true if an index exists, false otherwise
     */
    public boolean hasIndex(String databaseName, String tableName, String columnName) {
        String indexKey = getIndexKey(databaseName, tableName, columnName);
        return invertedIndices.containsKey(indexKey);
    }
    
    /**
     * Gets all indexed columns for a specific table
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @return List of column names that are indexed
     */
    public List<String> getIndexedColumns(String databaseName, String tableName) {
        List<String> result = new ArrayList<>();
        String prefix = databaseName + "." + tableName + ".";
        
        for (String key : invertedIndices.keySet()) {
            if (key.startsWith(prefix)) {
                String columnName = key.substring(prefix.length());
                result.add(columnName);
            }
        }
        
        return result;
    }
    
    /**
     * Gets all full-text indices in the system
     * 
     * @return List of all indices
     */
    public List<Map<String, String>> getAllIndices() {
        List<Map<String, String>> result = new ArrayList<>();
        
        for (String key : invertedIndices.keySet()) {
            String[] parts = key.split("\\.");
            if (parts.length == 3) {
                Map<String, String> indexInfo = new HashMap<>();
                indexInfo.put("database", parts[0]);
                indexInfo.put("table", parts[1]);
                indexInfo.put("column", parts[2]);
                indexInfo.put("size", String.valueOf(invertedIndices.get(key).size()));
                result.add(indexInfo);
            }
        }
        
        return result;
    }
    
    /**
     * Rebuilds all full-text indices for a table
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param tableData The table data
     */
    public void rebuildTableIndices(String databaseName, String tableName, Map<String, String> tableData) {
        List<String> indexedColumns = getIndexedColumns(databaseName, tableName);
        
        for (String columnName : indexedColumns) {
            String indexKey = getIndexKey(databaseName, tableName, columnName);
            
            // Clear the existing index
            invertedIndices.remove(indexKey);
            
            // Recreate the index
            createIndex(databaseName, tableName, columnName, tableData);
        }
    }
    
    /**
     * Tokenizes a text string into a list of tokens
     * 
     * @param text The text to tokenize
     * @return List of tokens
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        
        return tokens;
    }
    
    /**
     * Indexes a document in the inverted index
     * 
     * @param invertedIndex The inverted index
     * @param docId The document ID
     * @param text The text to index
     */
    private void indexDocument(Map<String, Map<String, Double>> invertedIndex, String docId, String text) {
        List<String> tokens = tokenize(text);
        
        // Count token frequencies
        Map<String, Integer> tokenFrequencies = new HashMap<>();
        for (String token : tokens) {
            tokenFrequencies.put(token, tokenFrequencies.getOrDefault(token, 0) + 1);
        }
        
        // Calculate term frequencies (TF)
        int totalTokens = tokens.size();
        for (Map.Entry<String, Integer> entry : tokenFrequencies.entrySet()) {
            String token = entry.getKey();
            int frequency = entry.getValue();
            double tf = (double) frequency / totalTokens;
            
            // Add to inverted index
            Map<String, Double> docScores = invertedIndex.computeIfAbsent(token, k -> new HashMap<>());
            docScores.put(docId, tf);
        }
    }
    
    /**
     * Removes a document from the inverted index
     * 
     * @param invertedIndex The inverted index
     * @param docId The document ID
     * @param text The text to remove
     */
    private void removeDocumentFromIndex(Map<String, Map<String, Double>> invertedIndex, String docId, String text) {
        List<String> tokens = tokenize(text);
        
        // Remove document from each token's posting list
        for (String token : tokens) {
            if (invertedIndex.containsKey(token)) {
                Map<String, Double> docScores = invertedIndex.get(token);
                docScores.remove(docId);
                
                // Remove token if no documents left
                if (docScores.isEmpty()) {
                    invertedIndex.remove(token);
                }
            }
        }
    }
    
    /**
     * Gets the total number of documents in the index
     * 
     * @param invertedIndex The inverted index
     * @return The total number of unique documents
     */
    private int getTotalDocuments(Map<String, Map<String, Double>> invertedIndex) {
        Set<String> uniqueDocIds = new HashSet<>();
        
        for (Map<String, Double> docScores : invertedIndex.values()) {
            uniqueDocIds.addAll(docScores.keySet());
        }
        
        return uniqueDocIds.size();
    }
    
    /**
     * Gets the key used to identify an index
     * 
     * @param databaseName The database name
     * @param tableName The table name
     * @param columnName The column name
     * @return The index key
     */
    private String getIndexKey(String databaseName, String tableName, String columnName) {
        return databaseName + "." + tableName + "." + columnName;
    }
    
    /**
     * Loads all indices from disk
     */
    private void loadIndices() {
        try {
            String metadataPath = Globals.data_path + "fulltext_metadata.json";
            Path path = Paths.get(metadataPath);
            
            if (!Files.exists(path)) {
                return;
            }
            
            FileInputStream fis = new FileInputStream(metadataPath);
            ObjectInputStream ois = new ObjectInputStream(fis);
            
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Map<String, Double>>> loadedIndices = 
                (Map<String, Map<String, Map<String, Double>>>) ois.readObject();
            
            invertedIndices.putAll(loadedIndices);
            ois.close();
            
            System.out.println("Loaded " + invertedIndices.size() + " full-text indices");
        } catch (Exception e) {
            System.out.println("Error loading full-text indices: " + e.getMessage());
        }
    }
    
    /**
     * Saves all indices to disk
     */
    private void saveIndices() {
        try {
            String metadataPath = Globals.data_path + "fulltext_metadata.json";
            File file = new File(metadataPath);
            
            // Create parent directories if they don't exist
            file.getParentFile().mkdirs();
            
            FileOutputStream fos = new FileOutputStream(metadataPath);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(invertedIndices);
            oos.close();
        } catch (Exception e) {
            System.out.println("Error saving full-text indices: " + e.getMessage());
        }
    }
}