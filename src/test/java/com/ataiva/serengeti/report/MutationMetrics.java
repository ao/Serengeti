package com.ataiva.serengeti.report;

/**
 * Class representing mutation testing metrics.
 */
public class MutationMetrics {
    private double mutationScore;
    private int totalMutations;
    private int killedMutations;
    private int survivedMutations;
    private boolean initialized;
    
    /**
     * Creates a new mutation metrics object with default values.
     */
    public MutationMetrics() {
        this.mutationScore = 0;
        this.totalMutations = 0;
        this.killedMutations = 0;
        this.survivedMutations = 0;
        this.initialized = false;
    }
    
    /**
     * Sets the mutation score percentage.
     * 
     * @param mutationScore The mutation score percentage
     */
    public void setMutationScore(double mutationScore) {
        this.mutationScore = mutationScore;
        this.initialized = true;
    }
    
    /**
     * Sets the total number of mutations.
     * 
     * @param totalMutations The total number of mutations
     */
    public void setTotalMutations(int totalMutations) {
        this.totalMutations = totalMutations;
        this.initialized = true;
    }
    
    /**
     * Sets the number of killed mutations.
     * 
     * @param killedMutations The number of killed mutations
     */
    public void setKilledMutations(int killedMutations) {
        this.killedMutations = killedMutations;
        this.initialized = true;
    }
    
    /**
     * Sets the number of survived mutations.
     * 
     * @param survivedMutations The number of survived mutations
     */
    public void setSurvivedMutations(int survivedMutations) {
        this.survivedMutations = survivedMutations;
        this.initialized = true;
    }
    
    /**
     * Gets the mutation score percentage.
     * 
     * @return The mutation score percentage
     */
    public double getMutationScore() {
        return mutationScore;
    }
    
    /**
     * Gets the total number of mutations.
     * 
     * @return The total number of mutations
     */
    public int getTotalMutations() {
        return totalMutations;
    }
    
    /**
     * Gets the number of killed mutations.
     * 
     * @return The number of killed mutations
     */
    public int getKilledMutations() {
        return killedMutations;
    }
    
    /**
     * Gets the number of survived mutations.
     * 
     * @return The number of survived mutations
     */
    public int getSurvivedMutations() {
        return survivedMutations;
    }
    
    /**
     * Checks if any mutation metrics have been set.
     * 
     * @return True if any mutation metrics have been set, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }
}