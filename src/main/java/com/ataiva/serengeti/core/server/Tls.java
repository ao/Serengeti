package com.ataiva.serengeti.core.server;

import com.ataiva.serengeti.core.config.ConfigException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.Collections;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/** TLS settings: PKCS#12 key stores, TLS 1.2 and 1.3 only. */
public final class Tls {
    static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private Tls() {}

    /** The server side, from a key store holding this node's key and certificate chain. */
    public static SSLContext serverContext(Path keystore, char[] password) {
        KeyStore ks = load(keystore, password, "tls_keystore");
        try {
            if (Collections.list(ks.aliases()).stream().noneMatch(a -> isKey(ks, a))) {
                throw new ConfigException("tls_keystore " + keystore + " holds no private key");
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (UnrecoverableKeyException e) {
            throw new ConfigException("tls_keystore " + keystore + ": the key password differs from the store password");
        } catch (GeneralSecurityException e) {
            throw new ConfigException("tls_keystore " + keystore + ": " + e.getMessage());
        }
    }

    /** A client that trusts the certificates in {@code store} (or the JDK's defaults when it is null). */
    public static SSLContext clientContext(Path store, char[] password) {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store == null ? null : load(store, password, "tls_truststore"));
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new ConfigException("tls_truststore " + store + ": " + e.getMessage());
        }
    }

    static SSLParameters parameters(SSLContext ctx) {
        SSLParameters p = ctx.getDefaultSSLParameters();
        p.setProtocols(PROTOCOLS);
        return p;
    }

    private static boolean isKey(KeyStore ks, String alias) {
        try {
            return ks.isKeyEntry(alias);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static KeyStore load(Path file, char[] password, String setting) {
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            return ks;
        } catch (NoSuchFileException e) {
            throw new ConfigException(setting + " " + file + " does not exist");
        } catch (IOException e) {
            // PKCS#12 reports a wrong password as an IOException whose cause is UnrecoverableKeyException.
            if (e.getCause() instanceof UnrecoverableKeyException) {
                throw new ConfigException(setting + " " + file + ": wrong password");
            }
            throw new ConfigException(setting + " " + file + " is not a readable PKCS#12 file: " + e.getMessage());
        } catch (GeneralSecurityException e) {
            throw new ConfigException(setting + " " + file + ": " + e.getMessage());
        }
    }
}
