package com.ataiva.serengeti.storage.lsm;

import com.ataiva.serengeti.performance.PerformanceProfiler;
import java.util.BitSet;
import java.util.List;
import java.util.logging.Logger;

/**
 * BloomFilterOptimizer implements bloom filter optimizations for LSM trees
 * to reduce disk I/O by quickly determining if a key might exist in an SSTable.
 * This significantly improves read performance by avoiding unnecessary disk reads.
 */
public class BloomFilterOptimizer {
    private static final Logger LOGGER = Logger.getLogger(BloomFilterOptimizer.class.getName());
    private static final int DEFAULT_BITS_PER_ELEMENT = 10;
    private static final int DEFAULT_HASH_FUNCTIONS = 7;
    
    private final PerformanceProfiler profiler;
    private int bitsPerElement;
    private int numHashFunctions;
    
    /**
     * Creates a new BloomFilterOptimizer with default settings
     */
    public BloomFilterOptimizer() {
        this(DEFAULT_BITS_PER_ELEMENT, DEFAULT_HASH_FUNCTIONS);
    }
    
    /**
     * Creates a new BloomFilterOptimizer with custom settings
     * 
     * @param bitsPerElement Number of bits to use per element
     * @param numHashFunctions Number of hash functions to use
     */
    public BloomFilterOptimizer(int bitsPerElement, int numHashFunctions) {
        this.profiler = PerformanceProfiler.getInstance();
        this.bitsPerElement = bitsPerElement;
        this.numHashFunctions = numHashFunctions;
    }
    
    /**
     * Creates a bloom filter for a list of keys
     * 
     * @param keys List of keys to add to the bloom filter
     * @return BitSet representing the bloom filter
     */
    public BitSet createFilter(List<String> keys) {
        String timerId = profiler.startTimer("storage", "bloom_filter_creation");
        
        try {
            int size = keys.size() * bitsPerElement;
            BitSet bloomFilter = new BitSet(size);
            
            for (String key : keys) {
                for (int i = 0; i < numHashFunctions; i++) {
                    int hash = computeHash(key, i, size);
                    bloomFilter.set(hash);
                }
            }
            
            LOGGER.fine("Created bloom filter with " + size + " bits for " + keys.size() + " keys");
            return bloomFilter;
        } finally {
            profiler.stopTimer(timerId, "storage.bloom_filter.creation_time");
        }
    }
    
    /**
     * Checks if a key might be present in the bloom filter
     * 
     * @param key Key to check
     * @param bloomFilter Bloom filter to check against
     * @return true if the key might be present, false if it's definitely not present
     */
    public boolean mightContain(String key, BitSet bloomFilter) {
        String timerId = profiler.startTimer("storage", "bloom_filter_check");
        
        try {
            int size = bloomFilter.size();
            
            for (int i = 0; i < numHashFunctions; i++) {
                int hash = computeHash(key, i, size);
                if (!bloomFilter.get(hash)) {
                    return false;
                }
            }
            
            return true;
        } finally {
            profiler.stopTimer(timerId, "storage.bloom_filter.check_time");
        }
    }
    
    /**
     * Computes a hash value for a key using a specific seed
     * 
     * @param key Key to hash
     * @param seed Seed for the hash function
     * @param size Size of the bloom filter
     * @return Hash value
     */
    private int computeHash(String key, int seed, int size) {
        // MurmurHash implementation
        int h = seed ^ key.length();
        
        for (int i = 0; i < key.length(); i++) {
            h = 31 * h + key.charAt(i);
        }
        
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        
        return Math.abs(h % size);
    }
    
    /**
     * Estimates the false positive probability for the current settings
     * 
     * @param numElements Expected number of elements
     * @return Estimated false positive probability
     */
    public double estimateFalsePositiveProbability(int numElements) {
        // p = (1 - e^(-k*n/m))^k
        // where k is the number of hash functions, n is the number of elements, and m is the size of the filter
        double m = numElements * bitsPerElement;
        double k = numHashFunctions;
        double n = numElements;
        
        return Math.pow(1 - Math.exp(-k * n / m), k);
    }
    
    /**
     * Optimizes the bloom filter parameters for a given false positive probability
     * 
     * @param expectedElements Expected number of elements
     * @param targetFalsePositiveRate Target false positive rate (0.0 to 1.0)
     */
    public void optimizeParameters(int expectedElements, double targetFalsePositiveRate) {
        // Calculate optimal bits per element: m/n = -ln(p) / (ln(2)^2)
        this.bitsPerElement = (int) Math.ceil(-Math.log(targetFalsePositiveRate) / (Math.log(2) * Math.log(2)));
        
        // Calculate optimal number of hash functions: k = (m/n) * ln(2)
        this.numHashFunctions = (int) Math.round(bitsPerElement * Math.log(2));
        
        LOGGER.info("Optimized bloom filter parameters: " + bitsPerElement + 
                   " bits per element, " + numHashFunctions + " hash functions");
    }
    
    /**
     * Gets the current bits per element setting
     * 
     * @return Bits per element
     */
    public int getBitsPerElement() {
        return bitsPerElement;
    }
    
    /**
     * Gets the current number of hash functions
     * 
     * @return Number of hash functions
     */
    public int getNumHashFunctions() {
        return numHashFunctions;
    }
}