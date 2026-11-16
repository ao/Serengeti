package com.ataiva.serengeti.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.server.InternalRoutes;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peers that ask for row pages and then stop reading, as one cut off by a partition does (no reset arrives,
 * and Linux retransmits for about 15 minutes), must neither close the row routes to the other peers nor
 * hold more heap than the bounds in HttpApi (from review 22d's probes). Each stalled peer connects from a
 * loopback address of its own (127.0.0.2 and up), as peers on other hosts would.
 */
class StalledPeersTest {
    private static final String KEY = "a cluster key of at least 32 characters";
    private static final String PW = "correct horse battery";
    /** HttpApi.HEAVY_UNSENT_PER_PEER and HEAVY_INTERNAL_AT_ONCE. */
    private static final int PER_PEER = 6;
    private static final int BUILT_AT_ONCE = 4;

    @TempDir
    Path dir;

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /** A clustered node (its two peers never answer) holding 4,000 rows of 10 KB in d.t. */
    private Node node(int port) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", dir.resolve("n0").toString());
        env.put("SERENGETI_PORT", Integer.toString(port));
        env.put("SERENGETI_PEERS", "127.0.0.1:" + freePort() + ",127.0.0.1:" + freePort());
        env.put("SERENGETI_CLUSTER_KEY", KEY);
        env.put("SERENGETI_CLUSTER_PLAINTEXT", "allow");
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        Node n = Node.start(Config.load(null, env), Node.QUERIES);
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String auth = "Basic " + Base64.getEncoder().encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8));
        for (String q : List.of("CREATE DATABASE d", "CREATE TABLE d.t")) {
            post(http, port, auth, q);
        }
        // 4,000 rows of 10 KB: a page of 200 buckets is about 8 MB of JSON, more than the socket buffers hold.
        String pad = "x".repeat(10_000);
        for (int from = 0; from < 4_000; from += 40) {
            StringJoiner v = new StringJoiner(", ");
            for (int k = from; k < from + 40; k++) {
                v.add("('r" + k + "', '" + pad + "')");
            }
            post(http, port, auth, "INSERT INTO d.t (_id, pad) VALUES " + v);
        }
        return n;
    }

    /** {@code count} requests for a 200-bucket page from {@code from}, each then not reading its answer. */
    private static List<Socket> stall(String from, int port, int count) throws Exception {
        List<Socket> stalled = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StringJoiner buckets = new StringJoiner(",");
            for (int b = 0; b < 200; b++) {
                buckets.add(Integer.toString((i * 37 + b) % 1024));
            }
            Socket s = new Socket();
            s.setReceiveBufferSize(4096);
            s.bind(new InetSocketAddress(InetAddress.getByName(from), 0));
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            OutputStream out = s.getOutputStream();
            out.write(("GET /internal/v2/rows?db=d&table=t&buckets=" + buckets + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + InternalRoutes.KEY_HEADER + ": " + KEY + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            stalled.add(s);
        }
        return stalled;
    }

    private static void assumeSeveralLoopbackAddresses() {
        try (Socket s = new Socket()) {
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0));
        } catch (Exception e) {
            Assumptions.abort("127.0.0.2 cannot be bound here (Linux has all of 127/8): " + e);
        }
    }

    @Test
    void aPeerThatStopsReadingLeavesTheRowRoutesOpenToTheOthers() throws Exception {
        assumeSeveralLoopbackAddresses();
        int port = freePort();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try (Node n = node(port)) {
            List<Socket> stalled = stall("127.0.0.2", port, 3 * PER_PEER);
            Thread.sleep(3_000);
            List<Integer> statuses = new ArrayList<>();
            try {
                statuses.add(onePage(http, port));
                Thread.sleep(5_000);
                statuses.add(onePage(http, port));
            } finally {
                for (Socket s : stalled) {
                    s.close();
                }
            }
            assertEquals(List.of(200, 200), statuses, "another peer's page requests 3 s and 8 s after one peer"
                    + " stopped reading " + 3 * PER_PEER + " pages");
        }
    }

    @Test
    void stalledSendsHoldAtMostTheirBoundsOfHeap() throws Exception {
        assumeSeveralLoopbackAddresses();
        int port = freePort();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try (Node n = node(port)) {
            int page = pageBytes(http, port);
            long before = usedAfterGc();
            // One peer: at most PER_PEER answers held, however many it asked for.
            List<Socket> stalled = new ArrayList<>(stall("127.0.0.2", port, 24));
            Thread.sleep(3_000);
            long onePeer = usedAfterGc() - before;
            // Two pages more for what the measurement itself catches (buffers, garbage a full GC kept).
            long bound = (long) (PER_PEER + BUILT_AT_ONCE + 2) * page;
            assertTrue(onePeer < bound, "24 stalled requests from one peer hold " + onePeer / (1 << 20)
                    + " MiB; pages of " + page / (1 << 20) + " MiB, bound " + bound / (1 << 20) + " MiB");
            // Several peers: the shared bound, plus what may be built while it is checked.
            for (int k = 3; k <= 6; k++) {
                stalled.addAll(stall("127.0.0." + k, port, 12));
            }
            Thread.sleep(3_000);
            long many = usedAfterGc() - before;
            long cap = Math.max(32L << 20, Runtime.getRuntime().maxMemory() / 8);
            long shared = Math.min(cap + (long) BUILT_AT_ONCE * page, 5L * PER_PEER * page) + (long) BUILT_AT_ONCE * page;
            for (Socket s : stalled) {
                s.close();
            }
            assertTrue(many < shared, "stalled requests from 5 peers hold " + many / (1 << 20) + " MiB; cap "
                    + cap / (1 << 20) + " MiB, bound " + shared / (1 << 20) + " MiB");
        }
    }

    private static long usedAfterGc() throws InterruptedException {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static int pageBytes(HttpClient http, int port) throws Exception {
        StringJoiner buckets = new StringJoiner(",");
        for (int b = 0; b < 200; b++) {
            buckets.add(Integer.toString(b));
        }
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/internal/v2/rows?db=d&table=t&buckets=" + buckets)).header(InternalRoutes.KEY_HEADER, KEY)
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body().length;
    }

    private static int onePage(HttpClient http, int port) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/internal/v2/rows?db=d&table=t&buckets=500")).header(InternalRoutes.KEY_HEADER, KEY)
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static void post(HttpClient http, int port, String auth, String q) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/query"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("query", q, "ack", "local")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
    }
}
