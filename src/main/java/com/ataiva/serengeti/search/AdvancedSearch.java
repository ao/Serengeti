package com.ataiva.serengeti.search;

import com.ataiva.serengeti.storage.Storage;
import org.json.JSONObject;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides advanced search capabilities for the Serengeti database system.
 * Supports range queries, full-text search, regex matching, and fuzzy matching.
 */
public class AdvancedSearch {

    private static final Logger LOGGER = Logger.getLogger(AdvancedSearch.class.getName());
    
    private final Storage storage;
    
    /**
     * Creates a new AdvancedSearch instance.
     * 
     * @param storage The storage implementation to use
     */
    public AdvancedSearch(Storage storage) {
        this.storage = storage;
    }
    
    /**
     * Performs a range query on a numeric field.
     * 
     * @param database The database name
     * @param table The table name
     * @param field The field to query
     * @param min The minimum value (inclusive)
     * @param max The maximum value (inclusive)
     * @return A list of matching records
     */
    public List<JSONObject> rangeQuery(String database, String table, String field, double min, double max) {
        LOGGER.info("Performing range query on " + database + "." + table + "." + field + " between " + min + " and " + max);
        
        try {
            // Get all records from the table
            List<String> records = storage.select(database, table, "*", null, null);
            
            // Filter records that match the range
            return records.stream()
                .map(JSONObject::new)
                .filter(record -> {
                    if (!record.has(field)) {
                        return false;
                    }
                    
                    try {
                        double value = Double.parseDouble(record.get(field).toString());
                        return value >= min && value <= max;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error performing range query", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Performs a full-text search on a text field.
     * 
     * @param database The database name
     * @param table The table name
     * @param field The field to search
     * @param query The search query
     * @return A list of matching records, sorted by relevance
     */
    public List<JSONObject> fullTextSearch(String database, String table, String field, String query) {
        LOGGER.info("Performing full-text search on " + database + "." + table + "." + field + " for '" + query + "'");
        
        try {
            // Get all records from the table
            List<String> records = storage.select(database, table, "*", null, null);
            
            // Tokenize the query
            Set<String> queryTokens = tokenize(query.toLowerCase());
            
            // Calculate relevance scores for each record
            Map<JSONObject, Double> scores = new HashMap<>();
            
            for (String record : records) {
                JSONObject json = new JSONObject(record);
                
                if (!json.has(field)) {
                    continue;
                }
                
                String text = json.getString(field).toLowerCase();
                Set<String> textTokens = tokenize(text);
                
                // Calculate TF-IDF score
                double score = calculateRelevance(queryTokens, textTokens);
                
                if (score > 0) {
                    scores.put(json, score);
                }
            }
            
            // Sort records by relevance score (descending)
            return scores.entrySet().stream()
                .sorted(Map.Entry.<JSONObject, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error performing full-text search", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Performs a regex match on a text field.
     * 
     * @param database The database name
     * @param table The table name
     * @param field The field to match
     * @param regex The regular expression to match
     * @return A list of matching records
     */
    public List<JSONObject> regexMatch(String database, String table, String field, String regex) {
        LOGGER.info("Performing regex match on " + database + "." + table + "." + field + " with pattern '" + regex + "'");
        
        try {
            // Get all records from the table
            List<String> records = storage.select(database, table, "*", null, null);
            
            // Compile the regex pattern
            Pattern pattern = Pattern.compile(regex);
            
            // Filter records that match the regex
            return records.stream()
                .map(JSONObject::new)
                .filter(record -> {
                    if (!record.has(field)) {
                        return false;
                    }
                    
                    String text = record.getString(field);
                    Matcher matcher = pattern.matcher(text);
                    return matcher.find();
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error performing regex match", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Performs a fuzzy match on a text field using Levenshtein distance.
     * 
     * @param database The database name
     * @param table The table name
     * @param field The field to match
     * @param query The query string
     * @param maxDistance The maximum Levenshtein distance allowed
     * @return A list of matching records, sorted by distance (ascending)
     */
    public List<JSONObject> fuzzyMatch(String database, String table, String field, String query, int maxDistance) {
        LOGGER.info("Performing fuzzy match on " + database + "." + table + "." + field + " for '" + query + 
                "' with max distance " + maxDistance);
        
        try {
            // Get all records from the table
            List<String> records = storage.select(database, table, "*", null, null);
            
            // Calculate Levenshtein distance for each record
            Map<JSONObject, Integer> distances = new HashMap<>();
            
            for (String record : records) {
                JSONObject json = new JSONObject(record);
                
                if (!json.has(field)) {
                    continue;
                }
                
                String text = json.getString(field);
                int distance = levenshteinDistance(query.toLowerCase(), text.toLowerCase());
                
                if (distance <= maxDistance) {
                    distances.put(json, distance);
                }
            }
            
            // Sort records by distance (ascending)
            return distances.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error performing fuzzy match", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Tokenizes a string into a set of words.
     * 
     * @param text The text to tokenize
     * @return A set of tokens
     */
    private Set<String> tokenize(String text) {
        // Split on non-alphanumeric characters
        String[] tokens = text.split("[^a-zA-Z0-9]+");
        
        // Filter out empty tokens and convert to set
        return Arrays.stream(tokens)
            .filter(token -> !token.isEmpty())
            .collect(Collectors.toSet());
    }
    
    /**
     * Calculates the relevance score between a query and a text.
     * 
     * @param queryTokens The tokens in the query
     * @param textTokens The tokens in the text
     * @return The relevance score
     */
    private double calculateRelevance(Set<String> queryTokens, Set<String> textTokens) {
        // Calculate the number of matching tokens
        Set<String> intersection = new HashSet<>(queryTokens);
        intersection.retainAll(textTokens);
        
        if (intersection.isEmpty()) {
            return 0.0;
        }
        
        // Calculate TF (term frequency)
        double tf = (double) intersection.size() / textTokens.size();
        
        // Calculate IDF (inverse document frequency)
        // In a real implementation, this would consider the frequency of terms across all documents
        double idf = Math.log((double) 1 / (1 + intersection.size()));
        
        // Calculate TF-IDF score
        return tf * idf;
    }
    
    /**
     * Calculates the Levenshtein distance between two strings.
     * 
     * @param s1 The first string
     * @param s2 The second string
     * @return The Levenshtein distance
     */
    private int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();
        
        // Create a matrix to store the distances
        int[][] dp = new int[m + 1][n + 1];
        
        // Initialize the first row and column
        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;
        }
        
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;
        }
        
        // Fill the matrix
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i][j - 1], dp[i - 1][j]));
                }
            }
        }
        
        return dp[m][n];
    }
}