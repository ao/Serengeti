package com.ataiva.serengeti.network;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.storage.Storage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of the Network component for the Serengeti distributed database system.
 * This class handles node discovery, message passing, and failure detection.
 */
public class NetworkImpl extends Network {
    private static final Logger LOGGER = Logger.getLogger(NetworkImpl.class.getName());
    
    // Thread pool for network operations
    private final ExecutorService networkExecutor;
    
    // Node registry
    private final ConcurrentHashMap<String, JSONObject> nodeRegistry;
    
    // Heartbeat scheduler
    private final ScheduledExecutorService heartbeatScheduler;
    
    // Network configuration
    private final int discoveryPort;
    private final int communicationPort;
    private final int heartbeatIntervalMs;
    private final int nodeTimeoutMs;
    private final int discoveryTimeoutMs;
    private final int maxRetransmissions;
    
    // Network state
    private boolean initialized = false;
    private boolean discoveryRunning = false;
    
    /**
     * Constructor with default configuration.
     */
    public NetworkImpl() {
        this(
            Globals.port_default,      // communicationPort
            1986,                      // discoveryPort
            5000,                      // heartbeatIntervalMs
            15000,                     // nodeTimeoutMs
            3000,                      // discoveryTimeoutMs
            3                          // maxRetransmissions
        );
    }
    
    /**
     * Constructor with custom configuration.
     * 
     * @param communicationPort Port used for HTTP communication
     * @param discoveryPort Port used for node discovery
     * @param heartbeatIntervalMs Interval between heartbeat messages
     * @param nodeTimeoutMs Timeout after which a node is considered failed
     * @param discoveryTimeoutMs Timeout for discovery operations
     * @param maxRetransmissions Maximum number of message retransmissions
     */
    public NetworkImpl(
            int communicationPort,
            int discoveryPort,
            int heartbeatIntervalMs,
            int nodeTimeoutMs,
            int discoveryTimeoutMs,
            int maxRetransmissions) {
        
        // Initialize thread pools
        this.networkExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "network-worker");
            t.setDaemon(true);
            return t;
        });
        
        this.heartbeatScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "heartbeat-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // Initialize data structures
        this.nodeRegistry = new ConcurrentHashMap<>();
        this.availableNodes = Collections.synchronizedMap(new HashMap<>());
        
        // Set configuration
        this.communicationPort = communicationPort;
        this.discoveryPort = discoveryPort;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.nodeTimeoutMs = nodeTimeoutMs;
        this.discoveryTimeoutMs = discoveryTimeoutMs;
        this.maxRetransmissions = maxRetransmissions;
        
        LOGGER.info("NetworkImpl initialized with configuration: " +
                "communicationPort=" + communicationPort +
                ", discoveryPort=" + discoveryPort +
                ", heartbeatIntervalMs=" + heartbeatIntervalMs +
                ", nodeTimeoutMs=" + nodeTimeoutMs);
    }
    
    /**
     * Initialize the network component.
     */
    @Override
    public void init() {
        if (initialized) {
            LOGGER.warning("Network already initialized");
            return;
        }
        
        LOGGER.info("Initializing network component");
        
        try {
            // Get local IP address
            myIP = Globals.getHost4Address();
            myINA = InetAddress.getByName(myIP);
            
            if (myIP == null || myIP.equals("127.0.0.1")) {
                LOGGER.warning("This node is not connected to a network. It will therefore only function locally.");
            } else {
                LOGGER.info("Local IP address: " + myIP);
            }
            
            // Start node discovery
            startDiscovery();
            
            // Start heartbeat scheduler
            startHeartbeatScheduler();
            
            initialized = true;
            LOGGER.info("Network component initialized successfully");
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize network component", e);
        }
    }
    
    /**
     * Start the node discovery process.
     */
    private void startDiscovery() {
        if (discoveryRunning) {
            LOGGER.warning("Discovery already running");
            return;
        }
        
        LOGGER.info("Starting node discovery");
        
        discoveryRunning = true;
        
        // Start discovery thread
        networkExecutor.submit(() -> {
            try {
                while (discoveryRunning) {
                    try {
                        // Scan network for nodes
                        scanNetworkForNodes();
                        
                        // Sleep before next scan
                        Thread.sleep(pingInterval);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOGGER.log(Level.WARNING, "Discovery thread interrupted", e);
                        break;
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error during network discovery", e);
                    }
                }
            } finally {
                discoveryRunning = false;
                LOGGER.info("Node discovery stopped");
            }
        });
    }
    
    /**
     * Start the heartbeat scheduler.
     */
    private void startHeartbeatScheduler() {
        LOGGER.info("Starting heartbeat scheduler");
        
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeats();
                checkNodeTimeouts();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in heartbeat processing", e);
            }
        }, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Send heartbeats to all known nodes.
     */
    private void sendHeartbeats() {
        if (availableNodes.isEmpty()) {
            return;
        }
        
        LOGGER.fine("Sending heartbeats to " + availableNodes.size() + " nodes");
        
        JSONObject heartbeatMessage = new JSONObject();
        heartbeatMessage.put("type", "Heartbeat");
        heartbeatMessage.put("sender_id", Serengeti.server.server_constants.id);
        heartbeatMessage.put("sender_ip", myIP);
        heartbeatMessage.put("timestamp", System.currentTimeMillis());
        
        String heartbeatJson = heartbeatMessage.toString();
        
        for (Map.Entry<String, JSONObject> entry : availableNodes.entrySet()) {
            String nodeId = entry.getKey();
            JSONObject nodeInfo = entry.getValue();
            
            // Skip self
            if (nodeId.equals(Serengeti.server.server_constants.id)) {
                continue;
            }
            
            // Send heartbeat asynchronously
            networkExecutor.submit(() -> {
                try {
                    String ip = nodeInfo.getString("ip");
                    communicateQueryLogSingleNode(nodeId, ip, heartbeatJson);
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Failed to send heartbeat to node " + nodeId, e);
                }
            });
        }
    }
    
    /**
     * Check for node timeouts.
     */
    private void checkNodeTimeouts() {
        if (availableNodes.isEmpty()) {
            return;
        }
        
        LOGGER.fine("Checking node timeouts");
        
        long currentTime = System.currentTimeMillis();
        List<String> nodesToRemove = new ArrayList<>();
        
        for (Map.Entry<String, JSONObject> entry : availableNodes.entrySet()) {
            String nodeId = entry.getKey();
            JSONObject nodeInfo = entry.getValue();
            
            // Skip self
            if (nodeId.equals(Serengeti.server.server_constants.id)) {
                continue;
            }
            
            try {
                long lastChecked = nodeInfo.getLong("last_checked");
                if (currentTime - lastChecked > nodeTimeoutMs) {
                    LOGGER.info("Node timeout detected for node " + nodeId);
                    nodesToRemove.add(nodeId);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error checking node timeout for node " + nodeId, e);
            }
        }
        
        // Remove timed out nodes
        for (String nodeId : nodesToRemove) {
            JSONObject nodeInfo = availableNodes.get(nodeId);
            availableNodes.remove(nodeId);
            
            // Notify storage reshuffle about lost node
            if (nodeInfo != null) {
                Serengeti.storageReshuffle.queueLostNode(nodeInfo);
            }
        }
        
        if (!nodesToRemove.isEmpty()) {
            LOGGER.info("Removed " + nodesToRemove.size() + " timed out nodes");
        }
    }
    
    /**
     * Scan the network for nodes.
     */
    @Override
    public void findNodes() {
        if (!initialized) {
            LOGGER.warning("Network not initialized");
            return;
        }
        
        // This method is called from the original Network class
        // We've already started discovery in init(), so just log
        LOGGER.fine("findNodes() called, discovery already running");
    }
    
    /**
     * Scan the network for nodes.
     */
    private void scanNetworkForNodes() {
        if (myINA == null) {
            LOGGER.warning("Local IP address not initialized");
            return;
        }
        
        LOGGER.fine("Scanning network for nodes");
        
        final byte[] ip = myINA.getAddress();
        final long currentTime = System.currentTimeMillis();
        
        // Create thread pool for parallel scanning
        ExecutorService scanExecutor = Executors.newFixedThreadPool(50);
        List<Future<?>> futures = new ArrayList<>();
        
        String[] ipParts = myIP.split("\\.");
        final String baseIP = ipParts[0] + "." + ipParts[1] + "." + ipParts[2] + ".";
        
        // Start latency measurement
        latencyRun = true;
        latency = 0;
        
        // Scan all IPs in the subnet
        for (int i = 1; i <= 254; i++) {
            final int j = i;
            
            futures.add(scanExecutor.submit(() -> {
                try {
                    String targetIp = baseIP + j;
                    
                    // Skip self
                    if (targetIp.equals(myIP)) {
                        return;
                    }
                    
                    long startTime = System.currentTimeMillis();
                    
                    // Try to connect to the node
                    HttpURLConnection connection = getURLConnection(targetIp);
                    int status = connection.getResponseCode();
                    
                    if (status == 200) {
                        // Read response
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                            StringBuilder content = new StringBuilder();
                            String inputLine;
                            while ((inputLine = in.readLine()) != null) {
                                content.append(inputLine);
                            }
                            
                            // Parse response
                            JSONObject jsonObj = new JSONObject(content.toString());
                            JSONObject nodeJSON = jsonObj.getJSONObject("this");
                            nodeJSON.put("last_checked", currentTime);
                            
                            String nodeId = nodeJSON.getString("id");
                            
                            // Update node registry
                            if (availableNodes.containsKey(nodeId)) {
                                availableNodes.replace(nodeId, nodeJSON);
                            } else {
                                availableNodes.put(nodeId, nodeJSON);
                                LOGGER.info("Discovered new node: " + nodeId + " at " + targetIp);
                            }
                        }
                    }
                    
                    // Update latency measurement
                    if (latencyRun) {
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        if (elapsedTime > latency) {
                            latency = elapsedTime;
                        }
                    }
                    
                    connection.disconnect();
                    
                } catch (SocketTimeoutException | ConnectException | NoRouteToHostException e) {
                    // Ignore common connection issues
                } catch (IOException e) {
                    // Ignore I/O exceptions
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error scanning node", e);
                }
            }));
        }
        
        // Wait for all scans to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error waiting for scan completion", e);
            }
        }
        
        // Shutdown scan executor
        scanExecutor.shutdown();
        try {
            if (!scanExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                scanExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scanExecutor.shutdownNow();
        }
        
        // End latency measurement
        latencyRun = false;
        
        // Check if we found any nodes
        if (!availableNodes.isEmpty()) {
            // Remove nodes that weren't seen in this scan
            List<String> nodesToRemove = new ArrayList<>();
            
            for (Map.Entry<String, JSONObject> entry : availableNodes.entrySet()) {
                JSONObject nodeInfo = entry.getValue();
                long lastChecked = nodeInfo.getLong("last_checked");
                
                if (lastChecked < currentTime) {
                    nodesToRemove.add(entry.getKey());
                }
            }
            
            // Remove nodes that weren't seen
            for (String nodeId : nodesToRemove) {
                JSONObject nodeInfo = availableNodes.remove(nodeId);
                if (nodeInfo != null) {
                    Serengeti.storageReshuffle.queueLostNode(nodeInfo);
                }
            }
            
            if (!nodesToRemove.isEmpty()) {
                LOGGER.info("Removed " + nodesToRemove.size() + " nodes that weren't seen in scan");
            }
            
            // Request network metadata
            requestNetworkMetas();
        } else if (!online) {
            // No nodes found, but we're not online yet
            online = true;
            hasPerformedNetworkSync = true;
            LOGGER.info("No other nodes found on the network, waiting..");
            Serengeti.server.serve();
        }
    }
    
    /**
     * Request network metadata from other nodes.
     */
    @Override
    public void requestNetworkMetas() {
        if (!hasPerformedNetworkSync) {
            hasPerformedNetworkSync = true;
            
            LOGGER.info("Checking to see if local data is stale..");
            
            networkExecutor.submit(() -> {
                int changesFound = 0;
                
                for (Map.Entry<String, JSONObject> entry : availableNodes.entrySet()) {
                    String nodeId = entry.getKey();
                    JSONObject nodeInfo = entry.getValue();
                    
                    // Skip self
                    if (nodeId.equals(Serengeti.server.server_constants.id)) {
                        continue;
                    }
                    
                    try {
                        String ip = nodeInfo.getString("ip");
                        
                        // Request metadata from node
                        URL url = new URL(String.format("http://%s:%d/meta", ip, Globals.port_default));
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setDoOutput(true);
                        connection.setConnectTimeout(networkTimeout);
                        connection.setReadTimeout(networkTimeout);
                        connection.setRequestProperty("Content-Type", "application/json");
                        
                        if (connection.getResponseCode() == 200) {
                            // Read response
                            StringBuilder content = new StringBuilder();
                            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                                String inputLine;
                                while ((inputLine = in.readLine()) != null) {
                                    content.append(inputLine);
                                }
                            }
                            
                            // Parse metadata
                            JSONObject jsonMeta = new JSONObject(content.toString()).getJSONObject("meta");
                            
                            // Process databases and tables
                            Iterator<String> keys = jsonMeta.keys();
                            while (keys.hasNext()) {
                                String db = keys.next();
                                if (jsonMeta.get(db) instanceof JSONArray) {
                                    JSONArray jsonArr = jsonMeta.getJSONArray(db);
                                    
                                    // Create database if it doesn't exist
                                    if (!Serengeti.storage.databaseExists(db)) {
                                        LOGGER.info("Creating missing database '" + db + "'");
                                        Serengeti.storage.createDatabase(db, true);
                                        changesFound++;
                                    }
                                    
                                    // Process tables
                                    for (int i = 0; i < jsonArr.length(); i++) {
                                        String table = jsonArr.getString(i);
                                        
                                        // Create table if it doesn't exist
                                        if (!Serengeti.storage.tableExists(db, table)) {
                                            LOGGER.info("Creating missing table '" + table + "' for database '" + db + "'");
                                            Serengeti.storage.createTable(db, table, true);
                                            changesFound++;
                                            
                                            // Request table replica
                                            JSONObject request = new JSONObject();
                                            request.put("type", "SendTableReplicaToNode");
                                            request.put("db", db);
                                            request.put("table", table);
                                            request.put("node_id", Serengeti.server.server_constants.id);
                                            request.put("node_ip", myIP);
                                            
                                            String replicaResponse = communicateQueryLogSingleNode(
                                                    nodeId,
                                                    ip,
                                                    request.toString()
                                            );
                                            
                                            // Process replica response
                                            try {
                                                JSONObject jsonRowsReplica = new JSONObject(replicaResponse);
                                                Iterator<String> rowKeys = jsonRowsReplica.keys();
                                                
                                                while (rowKeys.hasNext()) {
                                                    String rowId = rowKeys.next();
                                                    JSONObject rowJson = new JSONObject(jsonRowsReplica.getString(rowId));
                                                    
                                                    Storage.tableReplicaObjects
                                                            .get(db + "#" + table)
                                                            .insertOrReplace(rowId, rowJson);
                                                }
                                            } catch (Exception e) {
                                                LOGGER.log(Level.WARNING, "Error processing table replica", e);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error requesting metadata from node " + nodeId, e);
                    }
                }
                
                // Mark system as online
                online = true;
                LOGGER.info("Startup completed with " + changesFound + " changes found");
                
                // Start server
                Serengeti.server.serve();
            });
        }
    }
    
    /**
     * Get a URL connection to a node.
     * 
     * @param ip The IP address of the node
     * @return The URL connection
     * @throws Exception If an error occurs
     */
    @Override
    public HttpURLConnection getURLConnection(String ip) throws Exception {
        URL url = new URL("http://" + ip + ":" + Globals.port_default);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setDoOutput(true);
        connection.setConnectTimeout(networkTimeout);
        connection.setReadTimeout(networkTimeout);
        connection.setRequestProperty("Content-Type", "application/json");
        return connection;
    }
    
    /**
     * Check if a node is online.
     * 
     * @param ip The IP address of the node
     * @return True if the node is online, false otherwise
     */
    @Override
    public boolean nodeIsOnline(String ip) {
        try {
            HttpURLConnection connection = getURLConnection(ip);
            int status = connection.getResponseCode();
            return status == 200;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Communicate with all nodes.
     * 
     * @param jsonString The JSON string to send
     * @return The responses from all nodes
     */
    @Override
    public JSONArray communicateQueryLogAllNodes(String jsonString) {
        JSONArray responses = new JSONArray();
        
        if (availableNodes.isEmpty()) {
            return responses;
        }
        
        // Create a copy to avoid concurrent modification
        Map<String, JSONObject> nodesToContact = new HashMap<>(availableNodes);
        
        // Create a list of futures for parallel execution
        List<Future<String>> futures = new ArrayList<>();
        
        // Submit tasks for each node
        for (Map.Entry<String, JSONObject> entry : nodesToContact.entrySet()) {
            String nodeId = entry.getKey();
            JSONObject nodeInfo = entry.getValue();
            
            futures.add(networkExecutor.submit(() -> {
                try {
                    String ip = nodeInfo.getString("ip");
                    return communicateQueryLogSingleNode(nodeId, ip, jsonString);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error communicating with node " + nodeId, e);
                    return "";
                }
            }));
        }
        
        // Collect responses
        for (Future<String> future : futures) {
            try {
                String response = future.get(networkTimeout, TimeUnit.MILLISECONDS);
                if (response != null && !response.isEmpty()) {
                    responses.put(response);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error collecting node response", e);
            }
        }
        
        return responses;
    }
    
    /**
     * Communicate with a single node.
     * 
     * @param id The ID of the node
     * @param ip The IP address of the node
     * @param jsonString The JSON string to send
     * @return The response from the node
     */
    @Override
    public String communicateQueryLogSingleNode(String id, String ip, String jsonString) {
        // Check for empty ID or IP
        if (id.isEmpty() || ip.isEmpty()) {
            return "";
        }
        
        StringBuilder response = new StringBuilder();
        
        try {
            // Create URL and connection
            URL url = new URL(String.format("http://%s:%d/post", ip, Globals.port_default));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(networkTimeout);
            connection.setReadTimeout(networkTimeout);
            
            // Send request
            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonString.getBytes(StandardCharsets.UTF_8));
            }
            
            // Read response
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
            
            return response.toString();
            
        } catch (SocketException e) {
            // Socket exception, node might be down
            LOGGER.log(Level.FINE, "Socket exception communicating with node " + id, e);
            return "";
        } catch (IOException e) {
            // I/O exception
            LOGGER.log(Level.WARNING, "I/O exception communicating with node " + id + ": " + e.getMessage(), e);
            return "";
        } catch (Exception e) {
            // Other exception
            LOGGER.log(Level.SEVERE, "Error communicating with node " + id, e);
            return "";
        }
    }
    
    /**
     * Get self as a node.
     * 
     * @return The self node
     */
    @Override
    public JSONObject getSelfNode() {
        JSONObject self = new JSONObject();
        self.put("id", Serengeti.server.server_constants.id);
        self.put("ip", myIP);
        return self;
    }
    
    /**
     * Get primary and secondary nodes.
     * 
     * @return The primary and secondary nodes
     */
    @Override
    public JSONObject getPrimarySecondary() {
        JSONObject result = new JSONObject();
        
        JSONArray nodes = getRandomAvailableNodes(2);
        
        if (nodes == null || nodes.length() < 2) {
            // Not enough nodes, use self as primary
            JSONObject self = getSelfNode();
            result.put("primary", self);
            
            // Empty secondary
            JSONObject emptySec = new JSONObject();
            emptySec.put("id", "");
            emptySec.put("ip", "");
            result.put("secondary", emptySec);
        } else {
            // Use random nodes
            result.put("primary", nodes.get(0));
            result.put("secondary", nodes.get(1));
        }
        
        return result;
    }
    
    /**
     * Get random available nodes.
     * 
     * @param amount The number of nodes to get
     * @return The random nodes
     */
    @Override
    public JSONArray getRandomAvailableNodes(int amount) {
        // Create a copy to avoid concurrent modification
        Map<String, JSONObject> nodes = new HashMap<>(availableNodes);
        
        if (nodes.size() >= 2) {
            JSONArray result = new JSONArray();
            List<JSONObject> nodeList = new ArrayList<>(nodes.values());
            
            // Shuffle the list
            Collections.shuffle(nodeList);
            
            // Add nodes to result
            for (int i = 0; i < Math.min(amount, nodeList.size()); i++) {
                result.put(nodeList.get(i));
            }
            
            return result;
        }
        
        return null;
    }
    
    /**
     * Get a random available node.
     * 
     * @return A random node
     */
    @Override
    public JSONObject getRandomAvailableNode() {
        // Create a copy to avoid concurrent modification
        Map<String, JSONObject> nodes = new HashMap<>(availableNodes);
        
        if (nodes.isEmpty()) {
            return null;
        }
        
        // Remove self from the list
        nodes.entrySet().removeIf(entry -> entry.getValue().getString("ip").equals(myIP));
        
        if (nodes.isEmpty()) {
            return null;
        }
        
        // Convert to list and shuffle
        List<JSONObject> nodeList = new ArrayList<>(nodes.values());
        Collections.shuffle(nodeList);
        
        // Return first node
        return nodeList.get(0);
    }
    
    /**
     * Get the IP address for a node ID.
     * 
     * @param uuid The node ID
     * @return The IP address
     */
    @Override
    public String getIPFromUUID(String uuid) {
        try {
            JSONObject nodeInfo = availableNodes.get(uuid);
            if (nodeInfo != null) {
                return nodeInfo.getString("ip");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error getting IP for node " + uuid, e);
        }
        
        LOGGER.warning("Network is not ready to handle this request..");
        return "";
    }
    
    /**
     * Scan the network for IP addresses only.
     */
    @Override
    public void getNetworkIPsOnly() {
        try {
            byte[] ip = InetAddress.getLocalHost().getAddress();
            
            for (int i = 1; i <= 254; i++) {
                final int j = i;
                
                networkExecutor.submit(() -> {
                    try {
                        ip[3] = (byte) j;
                        InetAddress address = InetAddress.getByAddress(ip);
                        String output = address.toString().substring(1);
                        
                        if (address.isReachable(5000)) {
                            LOGGER.info(output + " is on the network");
                        } else {
                            LOGGER.fine("Not Reachable: " + output);
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error checking IP " + Arrays.toString(ip), e);
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error scanning network IPs", e);
        }
    }
    
    /**
     * Shutdown the network component.
     */
    public void shutdown() {
        LOGGER.info("Shutting down network component");
        
        // Stop discovery
        discoveryRunning = false;
        
        // Shutdown thread pools
        heartbeatScheduler.shutdown();
        networkExecutor.shutdown();
        
        try {
            // Wait for thread pools to terminate
            if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatScheduler.shutdownNow();
            }
            
            if (!networkExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                networkExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.SEVERE, "Error shutting down network component", e);
        }
        
        LOGGER.info("Network component shutdown complete");
    }
    
    /**
     * Check if the network is initialized
     * @return true if the network is initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }
}