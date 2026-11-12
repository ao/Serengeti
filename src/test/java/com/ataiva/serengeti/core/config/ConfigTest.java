package com.ataiva.serengeti.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
    private static final String KEY = "k".repeat(Config.MIN_CLUSTER_KEY);

    @TempDir
    Path dir;

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private Path file(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void defaultsAreLoopbackSingleNodeAndFsyncAlways() {
        Config c = Config.load(null, Map.of());
        assertEquals("127.0.0.1", c.bind);
        assertEquals(1985, c.port);
        assertEquals(Path.of("data"), c.dataDir);
        assertEquals(FsyncPolicy.ALWAYS, c.fsync);
        assertFalse(c.clustered());
        assertFalse(c.tls());
        assertEquals(1, c.clusterSize);
        assertEquals(1, c.quorum());
        assertNull(c.adminPassword);
        assertNull(c.clusterKey);
    }

    @Test
    void environmentOverridesFileAndFileOverridesDefaults() throws IOException {
        Path f = file("s.properties", "port = 2000\nbind=10.0.0.5\nfsync=off\n");
        Config fromFile = Config.load(f.toString(), Map.of());
        assertEquals(2000, fromFile.port);
        assertEquals("10.0.0.5", fromFile.bind);
        assertEquals(FsyncPolicy.OFF, fromFile.fsync);

        Config both = Config.load(f.toString(), env("SERENGETI_PORT", "3000"));
        assertEquals(3000, both.port);
        assertEquals("10.0.0.5", both.bind);
    }

    @Test
    void configPathComesFromTheEnvironmentWhenNoArgument() throws IOException {
        Path f = file("s.properties", "port=2001\n");
        assertEquals(2001, Config.load(null, env("SERENGETI_CONFIG", f.toString())).port);
    }

    @Test
    void argumentPathWinsOverEnvironmentPath() throws IOException {
        Path a = file("a.properties", "port=2001\n");
        Path b = file("b.properties", "port=2002\n");
        assertEquals(2001, Config.load(a.toString(), env("SERENGETI_CONFIG", b.toString())).port);
    }

    @Test
    void unknownKeysAndMissingFileAreRefused() throws IOException {
        Path f = file("s.properties", "prot=2000\n");
        ConfigException e = assertThrows(ConfigException.class, () -> Config.load(f.toString(), Map.of()));
        assertTrue(e.getMessage().contains("unknown setting 'prot'"), e.getMessage());
        assertThrows(ConfigException.class, () -> Config.load(dir.resolve("nope").toString(), Map.of()));
    }

    @Test
    void secretsAreRefusedInThePropertiesFile() throws IOException {
        for (String line : List.of("admin_password=abcdefghijklmn", "cluster_key=" + KEY,
                "tls_keystore_password=x")) {
            Path f = file("s.properties", line + "\n");
            ConfigException e = assertThrows(ConfigException.class, () -> Config.load(f.toString(), Map.of()));
            assertTrue(e.getMessage().contains("_file"), e.getMessage());
            assertFalse(e.getMessage().contains(line.substring(line.indexOf('=') + 1)),
                    "the error must not echo the secret");
        }
    }

    @Test
    void secretsComeFromFilesWithTrailingNewlineRemoved() throws IOException {
        Path pw = file("pw", "correct horse battery\n");
        Config c = Config.load(null, env("SERENGETI_ADMIN_PASSWORD_FILE", pw.toString()));
        assertEquals("correct horse battery", c.adminPassword);
        Path f = file("s.properties", "admin_password_file=" + pw + "\n");
        assertEquals("correct horse battery", Config.load(f.toString(), Map.of()).adminPassword);
    }

    @Test
    void secretEnvironmentVariableWinsOverItsFile() throws IOException {
        Path pw = file("pw", "from the file 123");
        Config c = Config.load(null, env("SERENGETI_ADMIN_PASSWORD_FILE", pw.toString(),
                "SERENGETI_ADMIN_PASSWORD", "from the environment"));
        assertEquals("from the environment", c.adminPassword);
    }

    @Test
    void shortAdminPasswordIsRefused() {
        assertThrows(ConfigException.class, () -> Config.load(null, env("SERENGETI_ADMIN_PASSWORD", "short")));
    }

    @Test
    void numbersAreValidated() {
        for (String port : List.of("0", "65536", "abc", "")) {
            assertThrows(ConfigException.class, () -> Config.load(null, env("SERENGETI_PORT", port)), port);
        }
        assertThrows(ConfigException.class, () -> Config.load(null, env("SERENGETI_FSYNC", "sometimes")));
        assertThrows(ConfigException.class, () -> Config.load(null, env("SERENGETI_BIND", "  ")));
    }

    @Test
    void peersNeedAClusterKeyOfAtLeast32Characters() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> Config.load(null, env("SERENGETI_PEERS", "a:1985", "SERENGETI_CLUSTER_PLAINTEXT", "allow")));
        assertTrue(e.getMessage().contains("CLUSTER_KEY"), e.getMessage());
        assertThrows(ConfigException.class, () -> Config.load(null, env("SERENGETI_PEERS", "a:1985",
                "SERENGETI_CLUSTER_PLAINTEXT", "allow", "SERENGETI_CLUSTER_KEY", "k".repeat(31))));
    }

    @Test
    void peersWithoutTlsNeedTheExplicitPlaintextOptIn() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> Config.load(null, env("SERENGETI_PEERS", "a:1985", "SERENGETI_CLUSTER_KEY", KEY)));
        assertTrue(e.getMessage().contains("SERENGETI_CLUSTER_PLAINTEXT=allow"), e.getMessage());
        assertThrows(ConfigException.class, () -> Config.load(null, env("SERENGETI_PEERS", "a:1985",
                "SERENGETI_CLUSTER_KEY", KEY, "SERENGETI_CLUSTER_PLAINTEXT", "yes")));

        Config c = Config.load(null, env("SERENGETI_PEERS", "a:1985, b:1985,a:1985", "SERENGETI_CLUSTER_KEY", KEY,
                "SERENGETI_CLUSTER_PLAINTEXT", "allow"));
        assertTrue(c.clustered());
        assertEquals(List.of("a:1985", "b:1985"), c.peers);
        assertEquals(3, c.clusterSize);
        assertEquals(2, c.quorum());
    }

    @Test
    void malformedPeersAreRefused() {
        for (String p : List.of("a", "a:", ":1985", "a:0", "a:x", "a b:1985", "a/b:1985")) {
            assertThrows(ConfigException.class, () -> Config.load(null, env("SERENGETI_PEERS", p,
                    "SERENGETI_CLUSTER_KEY", KEY, "SERENGETI_CLUSTER_PLAINTEXT", "allow")), p);
        }
        Config v6 = Config.load(null, env("SERENGETI_PEERS", "[fd00::2]:1985", "SERENGETI_CLUSTER_KEY", KEY,
                "SERENGETI_CLUSTER_PLAINTEXT", "allow"));
        assertEquals(List.of("[fd00::2]:1985"), v6.peers);
    }

    @Test
    void clusterSizeRules() {
        assertThrows(ConfigException.class, () -> Config.load(null, env("SERENGETI_CLUSTER_SIZE", "3")));
        assertEquals(1, Config.load(null, env("SERENGETI_CLUSTER_SIZE", "1")).clusterSize);
        Map<String, String> base = env("SERENGETI_PEERS", "a:1,b:1", "SERENGETI_CLUSTER_KEY", KEY,
                "SERENGETI_CLUSTER_PLAINTEXT", "allow");
        base.put("SERENGETI_CLUSTER_SIZE", "2");
        assertThrows(ConfigException.class, () -> Config.load(null, base));
        base.put("SERENGETI_CLUSTER_SIZE", "8");
        assertThrows(ConfigException.class, () -> Config.load(null, base));
        base.put("SERENGETI_CLUSTER_SIZE", "5");
        assertEquals(3, Config.load(null, base).quorum());
    }

    @Test
    void tlsNeedsAKeystorePassword() throws IOException {
        Path ks = file("node.p12", "not read here");
        assertThrows(ConfigException.class, () -> Config.load(null, env("SERENGETI_TLS_KEYSTORE", ks.toString())));
        Config c = Config.load(null, env("SERENGETI_TLS_KEYSTORE", ks.toString(),
                "SERENGETI_TLS_KEYSTORE_PASSWORD", "changeit"));
        assertTrue(c.tls());
        assertEquals("https", c.scheme());
    }

    @Test
    void discoveryNeedsTlsAndClusterSizeAndANarrowRange() throws IOException {
        Path ks = file("node.p12", "x");
        Map<String, String> base = env("SERENGETI_DISCOVERY_CIDR", "10.1.2.0/24", "SERENGETI_CLUSTER_KEY", KEY,
                "SERENGETI_CLUSTER_PLAINTEXT", "allow");
        assertThrows(ConfigException.class, () -> Config.load(null, base), "no TLS");
        base.put("SERENGETI_TLS_KEYSTORE", ks.toString());
        base.put("SERENGETI_TLS_KEYSTORE_PASSWORD", "changeit");
        ConfigException e = assertThrows(ConfigException.class, () -> Config.load(null, base));
        assertTrue(e.getMessage().contains("cluster_size"), e.getMessage());
        base.put("SERENGETI_CLUSTER_SIZE", "3");
        Config c = Config.load(null, base);
        assertTrue(c.clustered());
        assertEquals(2, c.quorum());
        base.put("SERENGETI_DISCOVERY_CIDR", "10.1.0.0/23");
        assertThrows(ConfigException.class, () -> Config.load(null, base));
    }

    @Test
    void cidrParsing() {
        assertEquals(256, Cidr.parse("192.168.7.0/24").addresses().size());
        assertEquals("192.168.7.255", Cidr.parse("192.168.7.0/24").addresses().get(255).getHostAddress());
        assertEquals(4, Cidr.parse("10.0.0.4/30").addresses().size());
        assertEquals(1, Cidr.parse("10.0.0.4/32").addresses().size());
        assertEquals(256, Cidr.parse("fd00::/120").addresses().size());
        for (String bad : List.of("10.0.0.0/16", "10.0.0.0/23", "fd00::/119", "10.0.0.1/24", "10.0.0.0",
                "10.0.0.0/", "/24", "10.0.0.0/33", "10.0.0.0/x", "example.com/24", "10.0.0.0/-1")) {
            assertThrows(ConfigException.class, () -> Cidr.parse(bad), bad);
        }
    }
}
