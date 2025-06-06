package com.ataiva.serengeti.security.tls;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configuration for TLS/SSL in the Serengeti system.
 * This class handles the setup of TLS for secure communication.
 */
public class TLSConfig {
    
    private static final Logger LOGGER = Logger.getLogger(TLSConfig.class.getName());
    
    private final String keystorePath;
    private final String keystorePassword;
    private final String keyPassword;
    private final String[] protocols;
    private final String[] cipherSuites;
    
    /**
     * Creates a new TLSConfig with default settings.
     */
    public TLSConfig() {
        this("keystore.jks", "password", "password", 
             new String[]{"TLSv1.2", "TLSv1.3"}, 
             null); // null means use default cipher suites
    }
    
    /**
     * Creates a new TLSConfig with custom settings.
     * 
     * @param keystorePath The path to the keystore file
     * @param keystorePassword The password for the keystore
     * @param keyPassword The password for the key
     * @param protocols The TLS protocols to enable
     * @param cipherSuites The cipher suites to enable, or null to use defaults
     */
    public TLSConfig(String keystorePath, String keystorePassword, String keyPassword, 
                    String[] protocols, String[] cipherSuites) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.keyPassword = keyPassword;
        this.protocols = protocols;
        this.cipherSuites = cipherSuites;
    }
    
    /**
     * Configures TLS for an HTTPS server.
     * 
     * @param httpsServer The HTTPS server to configure
     * @throws TLSConfigException If an error occurs during TLS configuration
     */
    public void configureServer(HttpsServer httpsServer) throws TLSConfigException {
        try {
            // Set up the SSL context
            SSLContext sslContext = createSSLContext();
            
            // Create an HTTPS configurator
            HttpsConfigurator configurator = new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    try {
                        // Get the SSL context for this connection
                        SSLContext context = getSSLContext();
                        
                        // Create default SSL parameters
                        SSLParameters sslParams = context.getDefaultSSLParameters();
                        
                        // Set the protocols
                        if (protocols != null && protocols.length > 0) {
                            sslParams.setProtocols(protocols);
                        }
                        
                        // Set the cipher suites
                        if (cipherSuites != null && cipherSuites.length > 0) {
                            sslParams.setCipherSuites(cipherSuites);
                        }
                        
                        // Set client authentication to none
                        sslParams.setNeedClientAuth(false);
                        
                        // Set the SSL parameters
                        params.setSSLParameters(sslParams);
                        
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error configuring HTTPS parameters", e);
                    }
                }
            };
            
            // Set the HTTPS configurator
            httpsServer.setHttpsConfigurator(configurator);
            
            LOGGER.info("TLS configured for HTTPS server with protocols: " + 
                       Arrays.toString(protocols));
            
        } catch (Exception e) {
            throw new TLSConfigException("Error configuring TLS", e);
        }
    }
    
    /**
     * Creates an SSL context with the configured keystore.
     * 
     * @return The SSL context
     * @throws KeyStoreException If an error occurs with the keystore
     * @throws IOException If an I/O error occurs
     * @throws NoSuchAlgorithmException If the algorithm is not available
     * @throws CertificateException If a certificate error occurs
     * @throws UnrecoverableKeyException If the key cannot be recovered
     * @throws KeyManagementException If a key management error occurs
     */
    private SSLContext createSSLContext() throws KeyStoreException, IOException, 
                                               NoSuchAlgorithmException, CertificateException, 
                                               UnrecoverableKeyException, KeyManagementException {
        // Load the keystore
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            keyStore.load(fis, keystorePassword.toCharArray());
        }
        
        // Set up the key manager factory
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPassword.toCharArray());
        
        // Set up the trust manager factory
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        
        // Create the SSL context
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        
        return sslContext;
    }
    
    /**
     * Gets the keystore path.
     * 
     * @return The keystore path
     */
    public String getKeystorePath() {
        return keystorePath;
    }
    
    /**
     * Gets the enabled protocols.
     * 
     * @return The enabled protocols
     */
    public String[] getProtocols() {
        return protocols;
    }
    
    /**
     * Gets the enabled cipher suites.
     * 
     * @return The enabled cipher suites
     */
    public String[] getCipherSuites() {
        return cipherSuites;
    }
    
    /**
     * Exception thrown when an error occurs during TLS configuration.
     */
    public static class TLSConfigException extends Exception {
        
        /**
         * Creates a new TLSConfigException.
         * 
         * @param message The error message
         */
        public TLSConfigException(String message) {
            super(message);
        }
        
        /**
         * Creates a new TLSConfigException.
         * 
         * @param message The error message
         * @param cause The cause of the exception
         */
        public TLSConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}