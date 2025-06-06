package com.ataiva.serengeti.server;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.network.Network;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A robust implementation of the Server class for production use.
 * This class extends the base Server class and adds additional functionality
 * such as connection pooling, request throttling, and improved error handling.
 */
public class ServerImpl extends Server {
    
    private static final Logger LOGGER = Logger.getLogger(ServerImpl.class.getName());
    
    private HttpServer httpServer;
    private ExecutorService threadPool;
    private final int port;
    private final int backlog;
    private final int threadPoolSize;
    private final long shutdownTimeout;
    private final TimeUnit shutdownTimeoutUnit;
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters;
    private final ConcurrentHashMap<String, Integer> activeConnections;
    private final ConcurrentHashMap<String, Long> lastRequestTimes;
    private boolean isRunning;
    
    /**
     * Creates a new ServerImpl with default settings.
     */
    public ServerImpl() {
        this(Globals.port_default, 100, 50, 10, TimeUnit.SECONDS);
    }
    
    /**
     * Creates a new ServerImpl with custom settings.
     * 
     * @param port The port to listen on
     * @param backlog The maximum number of queued incoming connections
     * @param threadPoolSize The number of threads in the thread pool
     * @param shutdownTimeout The timeout for graceful shutdown
     * @param shutdownTimeoutUnit The time unit for the shutdown timeout
     */
    public ServerImpl(int port, int backlog, int threadPoolSize, long shutdownTimeout, TimeUnit shutdownTimeoutUnit) {
        this.port = port;
        this.backlog = backlog;
        this.threadPoolSize = threadPoolSize;
        this.shutdownTimeout = shutdownTimeout;
        this.shutdownTimeoutUnit = shutdownTimeoutUnit;
        this.rateLimiters = new ConcurrentHashMap<>();
        this.activeConnections = new ConcurrentHashMap<>();
        this.lastRequestTimes = new ConcurrentHashMap<>();
        this.isRunning = false;
    }
    
    /**
     * Initializes the server.
     */
    @Override
    public void init() {
        super.init();
        
        try {
            // Create a thread pool with a fixed number of threads
            threadPool = Executors.newFixedThreadPool(threadPoolSize);
            
            LOGGER.info("ServerImpl initialized with thread pool size: " + threadPoolSize);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error initializing ServerImpl", e);
            throw new RuntimeException("Error initializing ServerImpl", e);
        }
    }
    
    /**
     * Starts the server and begins accepting connections.
     */
    @Override
    public void serve() {
        if (isRunning) {
            LOGGER.warning("Server is already running");
            return;
        }
        
        try {
            // Create the HTTP server
            httpServer = HttpServer.create(new InetSocketAddress(port), backlog);
            
            // Set up request handlers
            setupRequestHandlers();
            
            // Set the executor
            httpServer.setExecutor(threadPool);
            
            // Start the server
            httpServer.start();
            isRunning = true;
            
            LOGGER.info("ServerImpl started on port " + port);
            
            // Log server information
            try {
                System.out.printf("\nHTTP server started at http://%s:%d/%n",
                        Globals.getHost4Address(), port);
                System.out.printf("Dashboard available at http://%s:%d/dashboard%n",
                        Globals.getHost4Address(), port);
                System.out.printf("\nNode is 'online' and ready to contribute (took %dms to startup)%n",
                        System.currentTimeMillis() - Serengeti.startTime);
            } catch (SocketException se) {
                LOGGER.log(Level.WARNING, "Could not get host address", se);
                System.out.println("Could not start HTTP server started, IP lookup failed");
            }
        } catch (BindException be) {
            LOGGER.log(Level.WARNING, "Port " + port + " is already in use", be);
            System.out.println("Warning: Port " + port + " is already in use. This node will operate in passive mode.");
            // Don't exit, just continue without starting the HTTP server
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error starting server", e);
            System.out.println("Error starting server: " + e.getMessage());
        }
    }
    
    /**
     * Sets up the request handlers for the HTTP server.
     */
    private void setupRequestHandlers() {
        // Add the standard handlers from the parent class
        httpServer.createContext("/", new RateLimitedHandler(new RootHandler()));
        httpServer.createContext("/dashboard", new RateLimitedHandler(new DashboardHandler()));
        httpServer.createContext("/interactive", new RateLimitedHandler(new InteractiveHandler()));
        httpServer.createContext("/meta", new RateLimitedHandler(new MetaHandler()));
        httpServer.createContext("/post", new RateLimitedHandler(new GenericPostHandler()));
        
        // Add additional handlers
        httpServer.createContext("/health", new HealthCheckHandler());
        httpServer.createContext("/metrics", new MetricsHandler());
        httpServer.createContext("/admin", new AdminHandler());
    }
    
    /**
     * Stops the server and releases all resources.
     */
    public void shutdown() {
        if (!isRunning) {
            LOGGER.warning("Server is not running");
            return;
        }
        
        try {
            // Stop accepting new connections
            httpServer.stop(0);
            
            // Shutdown the thread pool gracefully
            threadPool.shutdown();
            if (!threadPool.awaitTermination(shutdownTimeout, shutdownTimeoutUnit)) {
                // Force shutdown if graceful shutdown fails
                threadPool.shutdownNow();
                if (!threadPool.awaitTermination(shutdownTimeout, shutdownTimeoutUnit)) {
                    LOGGER.severe("Thread pool did not terminate");
                }
            }
            
            isRunning = false;
            LOGGER.info("ServerImpl shutdown complete");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error shutting down server", e);
        }
    }
    
    /**
     * Checks if the server is running.
     * 
     * @return true if the server is running, false otherwise
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Gets the number of active connections.
     * 
     * @return The number of active connections
     */
    public int getActiveConnectionCount() {
        int total = 0;
        for (int count : activeConnections.values()) {
            total += count;
        }
        return total;
    }
    
    /**
     * Gets the number of requests processed by the server.
     * 
     * @return The number of requests processed
     */
    public int getRequestCount() {
        return lastRequestTimes.size();
    }
    
    /**
     * A handler for health check requests.
     */
    private class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JSONObject healthData = new JSONObject();
            healthData.put("status", "UP");
            healthData.put("timestamp", System.currentTimeMillis());
            healthData.put("version", "1.0.0");
            
            // Add system health metrics
            JSONObject metrics = new JSONObject();
            metrics.put("activeConnections", getActiveConnectionCount());
            metrics.put("threadPoolSize", threadPoolSize);
            metrics.put("freeMemory", Runtime.getRuntime().freeMemory());
            metrics.put("totalMemory", Runtime.getRuntime().totalMemory());
            metrics.put("maxMemory", Runtime.getRuntime().maxMemory());
            
            healthData.put("metrics", metrics);
            
            String response = healthData.toString();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }
    
    /**
     * A handler for metrics requests.
     */
    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JSONObject metricsData = new JSONObject();
            
            // System metrics
            JSONObject systemMetrics = new JSONObject();
            systemMetrics.put("cpuCores", Runtime.getRuntime().availableProcessors());
            systemMetrics.put("freeMemory", Runtime.getRuntime().freeMemory());
            systemMetrics.put("totalMemory", Runtime.getRuntime().totalMemory());
            systemMetrics.put("maxMemory", Runtime.getRuntime().maxMemory());
            
            // JVM metrics
            JSONObject jvmMetrics = new JSONObject();
            jvmMetrics.put("threadCount", Thread.activeCount());
            jvmMetrics.put("uptime", System.currentTimeMillis() - Serengeti.startTime);
            
            // Server metrics
            JSONObject serverMetrics = new JSONObject();
            serverMetrics.put("activeConnections", getActiveConnectionCount());
            serverMetrics.put("requestCount", getRequestCount());
            
            // Network metrics
            JSONObject networkMetrics = new JSONObject();
            networkMetrics.put("discoveryLatency", Network.latency);
            networkMetrics.put("availableNodes", Serengeti.network.availableNodes.size());
            
            metricsData.put("system", systemMetrics);
            metricsData.put("jvm", jvmMetrics);
            metricsData.put("server", serverMetrics);
            metricsData.put("network", networkMetrics);
            
            String response = metricsData.toString();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }
    
    /**
     * A handler for admin requests.
     */
    private class AdminHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Check for admin authorization
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader == null || !isValidAdminAuth(authHeader)) {
                exchange.sendResponseHeaders(401, 0);
                exchange.close();
                return;
            }
            
            String path = exchange.getRequestURI().getPath();
            String response;
            
            if (path.endsWith("/shutdown")) {
                response = "Shutting down server...";
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                
                // Schedule shutdown after response is sent
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(1000);
                        shutdown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } else if (path.endsWith("/restart")) {
                response = "Restarting server...";
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                
                // Schedule restart after response is sent
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(1000);
                        shutdown();
                        Thread.sleep(1000);
                        init();
                        serve();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } else {
                JSONObject adminData = new JSONObject();
                adminData.put("status", "ok");
                adminData.put("commands", new String[]{"shutdown", "restart"});
                
                response = adminData.toString();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }
        
        private boolean isValidAdminAuth(String authHeader) {
            // In a real implementation, this would validate against stored credentials
            // For now, we'll use a simple check
            return authHeader.startsWith("Bearer ") && 
                   authHeader.substring(7).equals("admin-token");
        }
    }
    
    /**
     * A wrapper handler that adds rate limiting to any HttpHandler.
     */
    private class RateLimitedHandler implements HttpHandler {
        private final HttpHandler delegate;
        
        public RateLimitedHandler(HttpHandler delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String clientIP = exchange.getRemoteAddress().getAddress().getHostAddress();
            
            // Get or create rate limiter for this client
            RateLimiter rateLimiter = rateLimiters.computeIfAbsent(clientIP, 
                    ip -> new RateLimiter(10, TimeUnit.SECONDS));
            
            // Check if rate limit is exceeded
            if (!rateLimiter.tryAcquire()) {
                String response = "Rate limit exceeded. Please try again later.";
                exchange.sendResponseHeaders(429, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            
            // Track active connection
            activeConnections.compute(clientIP, (k, v) -> (v == null) ? 1 : v + 1);
            lastRequestTimes.put(clientIP, System.currentTimeMillis());
            
            try {
                // Delegate to the actual handler
                delegate.handle(exchange);
            } finally {
                // Decrement active connection count
                activeConnections.compute(clientIP, (k, v) -> (v == null || v <= 1) ? null : v - 1);
            }
        }
    }
    
    /**
     * A simple rate limiter that limits the number of requests per time period.
     */
    private static class RateLimiter {
        private final long ratePerPeriod;
        private final long periodInNanos;
        private final AtomicLong nextFreeTicketNanos;
        
        public RateLimiter(long ratePerPeriod, TimeUnit timeUnit) {
            this.ratePerPeriod = ratePerPeriod;
            this.periodInNanos = timeUnit.toNanos(1);
            this.nextFreeTicketNanos = new AtomicLong(System.nanoTime());
        }
        
        public boolean tryAcquire() {
            long now = System.nanoTime();
            long next = nextFreeTicketNanos.get();
            
            // If we're within the rate limit, allow the request
            if (now >= next) {
                // Try to update the next free ticket time
                long newNext = now + (periodInNanos / ratePerPeriod);
                if (nextFreeTicketNanos.compareAndSet(next, newNext)) {
                    return true;
                }
            }
            
            return false;
        }
    }
}