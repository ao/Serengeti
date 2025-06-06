package com.ataiva.serengeti.security.tls;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import sun.security.x509.*;

/**
 * Utility class for generating keystores with self-signed certificates.
 * This is primarily for development and testing purposes.
 */
public class KeyStoreGenerator {
    
    private static final Logger LOGGER = Logger.getLogger(KeyStoreGenerator.class.getName());
    
    /**
     * Generates a keystore with a self-signed certificate.
     * 
     * @param keystorePath The path where the keystore should be saved
     * @param keystorePassword The password for the keystore
     * @param keyPassword The password for the key
     * @param commonName The common name for the certificate (e.g., "localhost")
     * @param validityDays The number of days the certificate should be valid
     * @return true if the keystore was generated successfully, false otherwise
     */
    public static boolean generateKeyStore(String keystorePath, String keystorePassword, 
                                          String keyPassword, String commonName, int validityDays) {
        try {
            // Create a new keystore
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null, keystorePassword.toCharArray());
            
            // Generate a key pair
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            
            // Generate a self-signed certificate
            X509Certificate certificate = generateCertificate(keyPair, commonName, validityDays);
            
            // Store the key and certificate in the keystore
            Certificate[] chain = {certificate};
            keyStore.setKeyEntry("serengeti", keyPair.getPrivate(), keyPassword.toCharArray(), chain);
            
            // Save the keystore to a file
            File keystoreFile = new File(keystorePath);
            try (FileOutputStream fos = new FileOutputStream(keystoreFile)) {
                keyStore.store(fos, keystorePassword.toCharArray());
            }
            
            LOGGER.info("Generated keystore at " + keystorePath + " with self-signed certificate for " + commonName);
            return true;
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error generating keystore", e);
            return false;
        }
    }
    
    /**
     * Generates a self-signed X.509 certificate.
     * 
     * @param keyPair The key pair to use
     * @param commonName The common name for the certificate
     * @param validityDays The number of days the certificate should be valid
     * @return The generated certificate
     * @throws CertificateException If an error occurs during certificate generation
     * @throws IOException If an I/O error occurs
     * @throws NoSuchAlgorithmException If the algorithm is not available
     * @throws InvalidKeyException If the key is invalid
     * @throws NoSuchProviderException If the provider is not available
     * @throws SignatureException If an error occurs during signing
     */
    private static X509Certificate generateCertificate(KeyPair keyPair, String commonName, int validityDays)
            throws CertificateException, IOException, NoSuchAlgorithmException, 
                   InvalidKeyException, NoSuchProviderException, SignatureException {
        
        // Current time minus 1 day to avoid any potential clock skew issues
        Date from = new Date(System.currentTimeMillis() - 86400000L);
        // Current time plus the validity period
        Date to = new Date(System.currentTimeMillis() + (long) validityDays * 86400000L);
        
        // Serial number for the certificate
        BigInteger serialNumber = new BigInteger(64, new SecureRandom());
        
        // Create the certificate info
        X509CertInfo info = new X509CertInfo();
        
        // Set the validity period
        CertificateValidity interval = new CertificateValidity(from, to);
        info.set(X509CertInfo.VALIDITY, interval);
        
        // Set the serial number
        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(serialNumber));
        
        // Set the subject and issuer (same for self-signed)
        X500Name owner = new X500Name("CN=" + commonName);
        info.set(X509CertInfo.SUBJECT, owner);
        info.set(X509CertInfo.ISSUER, owner);
        
        // Set the public key
        info.set(X509CertInfo.KEY, new CertificateX509Key(keyPair.getPublic()));
        
        // Set the algorithm ID
        AlgorithmId algorithm = new AlgorithmId(AlgorithmId.sha256WithRSAEncryption_oid);
        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algorithm));
        
        // Create the certificate and sign it
        X509CertImpl certificate = new X509CertImpl(info);
        certificate.sign(keyPair.getPrivate(), "SHA256withRSA");
        
        return certificate;
    }
    
    /**
     * Checks if a keystore file exists.
     * 
     * @param keystorePath The path to the keystore file
     * @return true if the keystore file exists, false otherwise
     */
    public static boolean keystoreExists(String keystorePath) {
        File keystoreFile = new File(keystorePath);
        return keystoreFile.exists() && keystoreFile.isFile();
    }
    
    /**
     * Ensures that a keystore exists, generating one if it doesn't.
     * 
     * @param keystorePath The path to the keystore file
     * @param keystorePassword The password for the keystore
     * @param keyPassword The password for the key
     * @param commonName The common name for the certificate
     * @param validityDays The number of days the certificate should be valid
     * @return true if the keystore exists or was generated successfully, false otherwise
     */
    public static boolean ensureKeyStoreExists(String keystorePath, String keystorePassword, 
                                             String keyPassword, String commonName, int validityDays) {
        if (keystoreExists(keystorePath)) {
            LOGGER.info("Keystore already exists at " + keystorePath);
            return true;
        }
        
        return generateKeyStore(keystorePath, keystorePassword, keyPassword, commonName, validityDays);
    }
}