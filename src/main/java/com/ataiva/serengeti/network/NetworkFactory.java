package com.ataiva.serengeti.network;

import java.util.logging.Logger;

/**
 * Factory class for creating Network instances.
 * This class provides methods to create different implementations of the Network interface.
 */
public class NetworkFactory {
    private static final Logger LOGGER = Logger.getLogger(NetworkFactory.class.getName());
    
    /**
     * Network implementation types.
     */
    public enum NetworkType {
        MOCK,   // Mock implementation for testing
        REAL    // Real implementation for production
    }
    
    /**
     * Create a Network instance of the specified type.
     * 
     * @param type The type of Network to create
     * @return A Network instance
     */
    public static Network createNetwork(NetworkType type) {
        switch (type) {
            case MOCK:
                LOGGER.info("Creating mock network implementation");
                return new MockNetwork();
            case REAL:
                LOGGER.info("Creating real network implementation");
                return new NetworkImpl();
            default:
                LOGGER.warning("Unknown network type, defaulting to real implementation");
                return new NetworkImpl();
        }
    }
    
    /**
     * Create a Network instance with custom configuration.
     * 
     * @param type The type of Network to create
     * @param communicationPort Port used for HTTP communication
     * @param discoveryPort Port used for node discovery
     * @param heartbeatIntervalMs Interval between heartbeat messages
     * @param nodeTimeoutMs Timeout after which a node is considered failed
     * @param discoveryTimeoutMs Timeout for discovery operations
     * @param maxRetransmissions Maximum number of message retransmissions
     * @return A Network instance
     */
    public static Network createNetwork(
            NetworkType type,
            int communicationPort,
            int discoveryPort,
            int heartbeatIntervalMs,
            int nodeTimeoutMs,
            int discoveryTimeoutMs,
            int maxRetransmissions) {
        
        switch (type) {
            case MOCK:
                LOGGER.info("Creating mock network implementation");
                return new MockNetwork();
            case REAL:
                LOGGER.info("Creating real network implementation with custom configuration");
                return new NetworkImpl(
                        communicationPort,
                        discoveryPort,
                        heartbeatIntervalMs,
                        nodeTimeoutMs,
                        discoveryTimeoutMs,
                        maxRetransmissions
                );
            default:
                LOGGER.warning("Unknown network type, defaulting to real implementation");
                return new NetworkImpl(
                        communicationPort,
                        discoveryPort,
                        heartbeatIntervalMs,
                        nodeTimeoutMs,
                        discoveryTimeoutMs,
                        maxRetransmissions
                );
        }
    }
}