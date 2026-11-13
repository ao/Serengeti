package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.config.ConfigException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsTest {
    private static final char[] PW = TestCerts.PASSWORD.toCharArray();
    private static final String ADMIN_PW = "correct horse battery";

    @TempDir
    static Path dir;

    static Path nodeKs;
    static Path wrongHostKs;
    static HttpApi api;
    static HttpApi wrongHostApi;

    @BeforeAll
    static void start() throws Exception {
        TestCerts.ca(dir, "good");
        TestCerts.ca(dir, "other");
        nodeKs = TestCerts.node(dir, "good", "node1", "dns:localhost,ip:127.0.0.1");
        wrongHostKs = TestCerts.node(dir, "good", "node2", "dns:elsewhere.example");
        Path data = Files.createDirectories(dir.resolve("data"));
        Users users = Users.open(data, 4, System::currentTimeMillis);
        users.setPassword(Users.ADMIN, ADMIN_PW, Users.Role.ADMIN);
        HttpApi.Deps deps = new HttpApi.Deps(users, new AtomicReference<>(), (q, r) -> Map.of("echo", q),
                () -> Map.of("node", "tls"), System::currentTimeMillis);
        api = HttpApi.start("127.0.0.1", 0, Tls.serverContext(nodeKs, PW), deps);
        wrongHostApi = HttpApi.start("127.0.0.1", 0, Tls.serverContext(wrongHostKs, PW), deps);
    }

    @AfterAll
    static void stop() {
        api.close();
        wrongHostApi.close();
    }

    private static HttpClient client(String ca) {
        SSLContext ctx = Tls.clientContext(TestCerts.truststore(dir, ca), PW);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }

    private static HttpResponse<String> get(HttpClient c, String scheme, String host, HttpApi a, String path)
            throws IOException, InterruptedException {
        return c.send(HttpRequest.newBuilder(URI.create(scheme + "://" + host + ":" + a.address().getPort() + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("admin:" + ADMIN_PW).getBytes(StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesHttpsToAClientThatTrustsTheCa() throws Exception {
        HttpClient c = client("good");
        assertEquals(200, get(c, "https", "localhost", api, "/health").statusCode());
        assertEquals(200, get(c, "https", "127.0.0.1", api, "/v1/status").statusCode());
    }

    @Test
    void aCertificateFromAnotherCaIsRefused() {
        assertThrows(SSLHandshakeException.class, () -> get(client("other"), "https", "localhost", api, "/health"));
    }

    @Test
    void aCertificateForAnotherHostNameIsRefused() {
        IOException e = assertThrows(IOException.class,
                () -> get(client("good"), "https", "localhost", wrongHostApi, "/health"));
        assertTrue(e instanceof SSLHandshakeException || e.getCause() instanceof javax.net.ssl.SSLException,
                e.toString());
    }

    @Test
    void aPlaintextClientGetsNoHttpResponse() {
        assertThrows(IOException.class, () -> get(HttpClient.newHttpClient(), "http", "127.0.0.1", api, "/health"));
    }

    @Test
    void onlyTls12And13AreOffered() throws Exception {
        SSLContext ctx = Tls.clientContext(TestCerts.truststore(dir, "good"), PW);
        for (String proto : new String[] {"TLSv1.2", "TLSv1.3"}) {
            try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", api.address().getPort())) {
                SSLParameters p = s.getSSLParameters();
                p.setProtocols(new String[] {proto});
                s.setSSLParameters(p);
                s.startHandshake();
                assertEquals(proto, s.getSession().getProtocol());
            }
        }
        assertEquals(java.util.List.of("TLSv1.3", "TLSv1.2"), java.util.List.of(Tls.PROTOCOLS));
        assertEquals(java.util.List.of("TLSv1.3", "TLSv1.2"),
                java.util.List.of(Tls.parameters(Tls.serverContext(nodeKs, PW)).getProtocols()));
    }

    @Test
    void badKeyStoresAreClearConfigErrors() throws IOException {
        ConfigException wrong = assertThrows(ConfigException.class, () -> Tls.serverContext(nodeKs, "nope".toCharArray()));
        assertTrue(wrong.getMessage().contains("wrong password"), wrong.getMessage());
        ConfigException missing = assertThrows(ConfigException.class,
                () -> Tls.serverContext(dir.resolve("missing.p12"), PW));
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());
        Path junk = dir.resolve("junk.p12");
        Files.writeString(junk, "not a key store");
        assertThrows(ConfigException.class, () -> Tls.serverContext(junk, PW));
        ConfigException noKey = assertThrows(ConfigException.class,
                () -> Tls.serverContext(TestCerts.truststore(dir, "good"), PW));
        assertTrue(noKey.getMessage().contains("no private key"), noKey.getMessage());
    }
}
