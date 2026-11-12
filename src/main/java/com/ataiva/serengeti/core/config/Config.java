package com.ataiva.serengeti.core.config;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Node settings. Sources, lowest precedence first: built-in defaults, a properties file (path from
 * {@code --config} or {@code SERENGETI_CONFIG}), then {@code SERENGETI_*} environment variables.
 * Secrets are read from files ({@code *_file} keys, or {@code SERENGETI_*_FILE}) or from the matching
 * environment variable, never from the properties file itself.
 */
public final class Config {
    public static final int MIN_CLUSTER_KEY = 32;
    public static final int MIN_ADMIN_PASSWORD = 12;

    private static final Set<String> KEYS = Set.of("bind", "port", "data_dir", "fsync", "peers", "advertise",
            "discovery_cidr", "cluster_size", "cluster_key_file", "cluster_plaintext", "admin_password_file",
            "tls_keystore", "tls_keystore_password_file", "tls_truststore", "tls_truststore_password_file",
            "test_fsync_delay_ms");

    public final String bind;
    public final int port;
    public final Path dataDir;
    public final FsyncPolicy fsync;
    public final List<String> peers;
    public final String advertise;
    public final String discoveryCidr;
    public final int clusterSize;
    public final String clusterKey;
    public final boolean clusterPlaintext;
    public final String adminPassword;
    public final Path tlsKeystore;
    public final char[] tlsKeystorePassword;
    public final Path tlsTruststore;
    public final char[] tlsTruststorePassword;
    public final long testFsyncDelayMs;

    private final boolean explicitSize;

    private Config(Map<String, String> v, Map<String, String> secrets) {
        explicitSize = v.containsKey("cluster_size");
        bind = v.getOrDefault("bind", "127.0.0.1").trim();
        port = parseInt(v.getOrDefault("port", "1985"), "port", 1, 65535);
        dataDir = Path.of(v.getOrDefault("data_dir", "data"));
        try {
            fsync = FsyncPolicy.parse(v.getOrDefault("fsync", "always"));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(e.getMessage());
        }
        peers = parsePeers(v.getOrDefault("peers", ""));
        advertise = blankToNull(v.get("advertise"));
        discoveryCidr = blankToNull(v.get("discovery_cidr"));
        clusterKey = secrets.get("cluster_key");
        clusterPlaintext = "allow".equals(v.getOrDefault("cluster_plaintext", "").trim());
        adminPassword = secrets.get("admin_password");
        tlsKeystore = pathOrNull(v.get("tls_keystore"));
        tlsKeystorePassword = toChars(secrets.get("tls_keystore_password"));
        tlsTruststore = pathOrNull(v.get("tls_truststore"));
        tlsTruststorePassword = toChars(secrets.get("tls_truststore_password"));
        testFsyncDelayMs = parseInt(v.getOrDefault("test_fsync_delay_ms", "0"), "test_fsync_delay_ms", 0, 60_000);
        String size = v.get("cluster_size");
        clusterSize = size == null ? peers.size() + 1 : parseInt(size, "cluster_size", 1, 7);
        validate();
    }

    public boolean tls() {
        return tlsKeystore != null;
    }

    public boolean clustered() {
        return !peers.isEmpty() || discoveryCidr != null;
    }

    public String scheme() {
        return tls() ? "https" : "http";
    }

    /** Writes needed to acknowledge a change: a majority of the cluster, counting this node. */
    public int quorum() {
        return clusterSize / 2 + 1;
    }

    private void validate() {
        if (bind.isEmpty()) {
            throw new ConfigException("bind must not be empty");
        }
        if (tls() && tlsKeystorePassword == null) {
            throw new ConfigException("tls_keystore is set but no keystore password "
                    + "(SERENGETI_TLS_KEYSTORE_PASSWORD or tls_keystore_password_file)");
        }
        if (tlsTruststore != null && tlsTruststorePassword == null) {
            throw new ConfigException("tls_truststore is set but no truststore password");
        }
        if (adminPassword != null && adminPassword.length() < MIN_ADMIN_PASSWORD) {
            throw new ConfigException("the admin password must be at least " + MIN_ADMIN_PASSWORD + " characters");
        }
        if (!clustered()) {
            if (explicitSize && clusterSize != 1) {
                throw new ConfigException("cluster_size is set but no peers or discovery_cidr");
            }
            return;
        }
        if (clusterKey == null || clusterKey.length() < MIN_CLUSTER_KEY) {
            throw new ConfigException("a cluster needs SERENGETI_CLUSTER_KEY (or cluster_key_file) of at least "
                    + MIN_CLUSTER_KEY + " characters");
        }
        if (!tls() && !clusterPlaintext) {
            throw new ConfigException("peers are configured without TLS. Node-to-node traffic, including the "
                    + "cluster key, would be sent in clear. Configure tls_keystore, or set "
                    + "SERENGETI_CLUSTER_PLAINTEXT=allow to accept that on a private network.");
        }
        if (discoveryCidr != null) {
            if (!tls()) {
                throw new ConfigException("discovery_cidr needs TLS: discovered peers are only trusted once "
                        + "their certificate is verified");
            }
            Cidr.parse(discoveryCidr);
            if (!explicitSize) {
                throw new ConfigException("discovery_cidr needs cluster_size, so that a majority is defined");
            }
        }
        if (clusterSize < peers.size() + 1) {
            throw new ConfigException("cluster_size " + clusterSize + " is smaller than this node plus "
                    + peers.size() + " peers");
        }
        if (clusterSize > 7) {
            throw new ConfigException("clusters of more than 7 nodes are not supported");
        }
    }

    /** Builds the configuration from the process environment and arguments. */
    public static Config load(String configPath, Map<String, String> env) {
        Map<String, String> values = new java.util.HashMap<>();
        Map<String, String> secrets = new java.util.HashMap<>();
        String path = configPath != null ? configPath : env.get("SERENGETI_CONFIG");
        if (path != null) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new ConfigException("cannot read config file " + path + ": " + e.getMessage());
            }
            for (String k : p.stringPropertyNames()) {
                String key = k.trim().toLowerCase(Locale.ROOT);
                if (key.equals("cluster_key") || key.equals("admin_password") || key.endsWith("_password")) {
                    throw new ConfigException("config file: put secrets in a file and use " + key + "_file");
                }
                if (!KEYS.contains(key)) {
                    throw new ConfigException("config file: unknown setting '" + k + "'");
                }
                values.put(key, p.getProperty(k));
            }
        }
        for (String key : KEYS) {
            String e = env.get("SERENGETI_" + key.toUpperCase(Locale.ROOT));
            if (e != null) {
                values.put(key, e);
            }
        }
        for (String secret : List.of("cluster_key", "admin_password", "tls_keystore_password",
                "tls_truststore_password")) {
            String direct = env.get("SERENGETI_" + secret.toUpperCase(Locale.ROOT));
            String file = values.get(secret + "_file");
            if (direct != null && !direct.isEmpty()) {
                secrets.put(secret, direct);
            } else if (file != null && !file.isBlank()) {
                secrets.put(secret, readSecret(Path.of(file.trim()), secret));
            }
        }
        return new Config(values, secrets);
    }

    private static String readSecret(Path p, String name) {
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            int end = s.length();
            while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
                end--;
            }
            return s.substring(0, end);
        } catch (IOException e) {
            throw new ConfigException("cannot read " + name + " file " + p + ": " + e.getMessage());
        }
    }

    private static List<String> parsePeers(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int colon = p.lastIndexOf(':');
            if (colon <= 0 || colon == p.length() - 1) {
                throw new ConfigException("peer '" + p + "' must be host:port");
            }
            parseInt(p.substring(colon + 1), "peer port", 1, 65535);
            if (!p.substring(0, colon).matches("[A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:]+\\]")) {
                throw new ConfigException("peer '" + p + "' has an invalid host");
            }
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static int parseInt(String s, String name, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min || v > max) {
                throw new ConfigException(name + " must be between " + min + " and " + max);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ConfigException(name + " must be a number, not '" + s + "'");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Path pathOrNull(String s) {
        return s == null || s.isBlank() ? null : Path.of(s.trim());
    }

    private static char[] toChars(String s) {
        return s == null ? null : s.toCharArray();
    }
}
