package com.ataiva.serengeti;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.server.TestCerts;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SerengetiTest {
    @TempDir
    Path dir;

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private Config config(int port, String... kv) {
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", dir.resolve("data").toString());
        env.put("SERENGETI_PORT", Integer.toString(port));
        env.put("SERENGETI_ADMIN_PASSWORD", "correct horse battery");
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return Config.load(null, env);
    }

    @Test
    void healthProbeOverHttp() throws Exception {
        int port = freePort();
        Config c = config(port);
        assertFalse(Serengeti.probe(c), "nothing listening yet");
        try (Node n = Node.start(c, Node.NO_QUERIES)) {
            assertTrue(Serengeti.probe(c));
        }
    }

    @Test
    void healthProbeOverHttpsTrustsTheNodesOwnCertificate() throws Exception {
        TestCerts.ca(dir, "ca");
        // The certificate does not name 127.0.0.1; the probe still connects, trusting only this certificate.
        Path ks = TestCerts.node(dir, "ca", "node", "dns:db.example");
        int port = freePort();
        Config c = config(port, "SERENGETI_TLS_KEYSTORE", ks.toString(), "SERENGETI_TLS_KEYSTORE_PASSWORD",
                TestCerts.PASSWORD);
        try (Node n = Node.start(c, Node.NO_QUERIES)) {
            assertTrue(Serengeti.probe(c));
            Config plain = config(port);
            assertFalse(Serengeti.probe(plain), "a plaintext probe gets no answer from the TLS port");
        }
        TestCerts.ca(dir, "other");
        Path otherKs = TestCerts.node(dir, "other", "impostor", "dns:db.example");
        try (Node n = Node.start(config(port, "SERENGETI_TLS_KEYSTORE", otherKs.toString(),
                "SERENGETI_TLS_KEYSTORE_PASSWORD", TestCerts.PASSWORD), Node.NO_QUERIES)) {
            assertFalse(Serengeti.probe(c), "a node with a different certificate is not trusted");
        }
    }
}
