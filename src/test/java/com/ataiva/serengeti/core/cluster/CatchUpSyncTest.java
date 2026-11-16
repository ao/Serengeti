package com.ataiva.serengeti.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.server.InternalRoutes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Catching up in bounded pages, from review 22d's probes: a sync whose pages stopped (a newer background sync
 * with the peer started, or its majority read is over) must not count as a completed sync, a majority read
 * must not take one for its answer, a read's pulls must stop with the read, and whole-database pulls from 2.0
 * peers go through the same limits. Each test failed on an earlier engine/2.2 commit (d4d0a60 or 74e2f41).
 *
 * <p>Node 2 reaches its peers through small proxies in this test that forward every request and can slow or
 * refuse the row routes, the way a loaded peer does. Nodes 0 and 1 reach node 2 directly.
 */
class CatchUpSyncTest {
    private static final String KEY = "a cluster key of at least 32 characters";
    private static final String PW = "correct horse battery";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @TempDir
    Path dir;

    private final int[] ports = new int[3];
    private final Node[] nodes = new Node[3];
    private final List<Proxy> proxies = new ArrayList<>();

    @BeforeEach
    void ports() throws IOException {
        for (int i = 0; i < 3; i++) {
            ports[i] = freePort();
        }
    }

    @AfterEach
    void stopAll() throws IOException {
        for (int i = 0; i < 3; i++) {
            if (nodes[i] != null) {
                nodes[i].close();
                nodes[i] = null;
            }
        }
        proxies.forEach(Proxy::close);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private Config config(int i, List<String> peers) {
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", dir.resolve("n" + i).toString());
        env.put("SERENGETI_PORT", Integer.toString(ports[i]));
        env.put("SERENGETI_PEERS", String.join(",", peers));
        env.put("SERENGETI_CLUSTER_KEY", KEY);
        env.put("SERENGETI_CLUSTER_PLAINTEXT", "allow");
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        return Config.load(null, env);
    }

    private void startDirect(int i) throws IOException {
        List<String> peers = new ArrayList<>();
        for (int j = 0; j < 3; j++) {
            if (j != i) {
                peers.add("127.0.0.1:" + ports[j]);
            }
        }
        nodes[i] = Node.start(config(i, peers), Node.QUERIES);
    }

    private HttpResponse<String> query(int i, String q, String consistency) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[i] + "/v1/query"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("query", q, "consistency", consistency))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private long count(int i) throws Exception {
        HttpResponse<String> r = query(i, "SELECT COUNT(*) FROM d.t", "local");
        assertEquals(200, r.statusCode(), r.body());
        return ((Number) ((List<Object>) ((List<Object>) Json.parseObject(r.body()).get("rows")).get(0)).get(0)).longValue();
    }

    private static void eventually(String what, java.util.function.BooleanSupplier ok) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (ok.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    /** Three nodes with d.t on every one; then node 2 stops and misses {@code rows} rows. */
    private void clusterWithNode2Behind(int rows) throws Exception {
        for (int i = 0; i < 3; i++) {
            startDirect(i);
        }
        eventually("node 0 accepts writes", () -> nodes[0].cluster().caughtUp()
                && nodes[0].cluster().reachable() >= nodes[0].cluster().quorum());
        assertEquals(200, query(0, "CREATE DATABASE d", "local").statusCode());
        assertEquals(200, query(0, "CREATE TABLE d.t", "local").statusCode());
        eventually("node 2 has d.t", () -> {
            try {
                return count(2) == 0;
            } catch (Exception | AssertionError e) {
                return false;
            }
        });
        nodes[2].close();
        nodes[2] = null;
        for (int from = 0; from < rows; from += 500) {
            StringBuilder v = new StringBuilder();
            for (int k = from; k < Math.min(rows, from + 500); k++) {
                v.append(k == from ? "" : ", ").append("('r").append(k).append("', ").append(k).append(')');
            }
            HttpResponse<String> r = query(0, "INSERT INTO d.t (_id, n) VALUES " + v, "local");
            assertEquals(200, r.statusCode(), r.body());
        }
        assertEquals(rows, count(1), "node 1 has every row");
    }

    /**
     * Node 1 answers, but is busy: it refuses row pages with 503 (what HttpApi.HEAVY_INTERNAL answers while two
     * other pages are being built) and is slow to give bucket sums. Node 0 is down. So no peer can give node 2
     * the rows it missed, and a majority read on node 2 must be refused. It is answered 200 instead, with node
     * 2's old count, whenever node 2's background sync with node 1 fails and the next sync round starts a new
     * one while the read's own sync waits for the bucket sums: the read's sync then sees the generation move,
     * pulls nothing, and completes as a sync.
     */
    @Test
    void aMajorityReadIsRefusedWhenNoPeerGaveTheRows() throws Exception {
        clusterWithNode2Behind(300);
        nodes[0].close();
        nodes[0] = null;
        Proxy busy = proxy(ports[1]);
        busy.bucketsDelayMillis = 1_500;
        busy.rowsStatus = 503;
        nodes[2] = Node.start(config(2, List.of("127.0.0.1:" + freePort(), "127.0.0.1:" + busy.port())), Node.QUERIES);
        Thread.sleep(2_000);
        for (int k = 0; k < 10; k++) {
            HttpResponse<String> r = query(2, "SELECT COUNT(*) FROM d.t", "majority");
            if (r.statusCode() == 200) {
                fail("majority read " + k + " answered 200 " + r.body().trim() + " though node 2 has " + count(2)
                        + " of the 300 rows a majority acknowledged, and no peer gave it any");
            }
            assertEquals(503, r.statusCode(), r.body());
        }
    }

    /**
     * The fast form of the next test, for the unit suite (about 15 s). Node 2 missed 6,000 rows and its peers
     * answer row pages in 1 s. Once its first pages are in flight, the test moves each peer's generation on, as
     * syncRound does when it starts a newer sync after the 35 s wait (the field is private; reflection stands in
     * for those 35 s). The first syncs must then fail, not count as complete: node 2 may report caught_up only
     * once it has every row.
     */
    @Test
    void aSupersededSyncDoesNotMarkTheNodeCaughtUp() throws Exception {
        int rows = 6_000;
        clusterWithNode2Behind(rows);
        Proxy a = proxy(ports[0]);
        Proxy b = proxy(ports[1]);
        a.rowsDelayMillis = 1_000;
        b.rowsDelayMillis = 1_000;
        long started = System.currentTimeMillis();
        nodes[2] = Node.start(config(2, List.of("127.0.0.1:" + a.port(), "127.0.0.1:" + b.port())), Node.QUERIES);
        eventually("node 2 asks both peers for row pages", () -> a.rowsRequests.get() > 0 && b.rowsRequests.get() > 0);
        java.lang.reflect.Field peersField = Cluster.class.getDeclaredField("peers");
        peersField.setAccessible(true);
        for (Object p : (Iterable<?>) peersField.get(nodes[2].cluster())) {
            java.lang.reflect.Field g = p.getClass().getDeclaredField("generation");
            g.setAccessible(true);
            g.setLong(p, g.getLong(p) + 1);
        }
        long deadline = started + 25_000;
        while (System.currentTimeMillis() < deadline) {
            if (nodes[2].cluster().caughtUp()) {
                long have = count(2);
                assertEquals(rows, have, "node 2 reports caught_up " + (System.currentTimeMillis() - started) / 1000
                        + " s after its start with " + have + " of the " + rows + " rows");
                return;
            }
            Thread.sleep(100);
        }
        fail("node 2 did not catch up within 25 s");
    }

    /**
     * Node 2 missed 6,000 rows; both peers answer row pages slowly (7 s each), so its first sync with each takes
     * longer than the 35 s the sync round waits. Then the next round starts a second sync, the first one's pages
     * stop at the generation check, and the first sync completes as if whole: lastSynced is set, and node 2
     * reports caught_up and accepts writes while it still lacks rows the second sync has not pulled yet.
     * About 45 s: run with -Dserengeti.slow=true.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "serengeti.slow", matches = "true")
    void aNodeIsNotCaughtUpWhileRowsItMissedAreStillMissing() throws Exception {
        int rows = 6_000;
        clusterWithNode2Behind(rows);
        Proxy a = proxy(ports[0]);
        Proxy b = proxy(ports[1]);
        a.rowsDelayMillis = 7_000;
        b.rowsDelayMillis = 7_000;
        long started = System.currentTimeMillis();
        nodes[2] = Node.start(config(2, List.of("127.0.0.1:" + a.port(), "127.0.0.1:" + b.port())), Node.QUERIES);
        long deadline = started + 150_000;
        while (System.currentTimeMillis() < deadline) {
            if (nodes[2].cluster().caughtUp()) {
                long have = count(2);
                assertEquals(rows, have, "node 2 reports caught_up " + (System.currentTimeMillis() - started) / 1000
                        + " s after its start with " + have + " of the " + rows + " rows; status "
                        + Json.write(nodes[2].cluster().status()));
                return;
            }
            Thread.sleep(100);
        }
        fail("node 2 never caught up");
    }

    private Proxy proxy(int target) throws IOException {
        Proxy p = new Proxy(target);
        proxies.add(p);
        return p;
    }

    /** Forwards node-to-node requests to 127.0.0.1:target; can slow bucket sums and slow or refuse row pages. */
    static final class Proxy implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        private final int target;
        volatile long rowsDelayMillis;
        volatile long bucketsDelayMillis;
        /** 0: forward row pages; otherwise answer them with this status. */
        volatile int rowsStatus;
        /** Row pages asked for through this proxy. */
        final java.util.concurrent.atomic.AtomicInteger rowsRequests = new java.util.concurrent.atomic.AtomicInteger();
        /** Answer as a 2.0 node: 404 to the /internal/v2 and /internal/v3 routes. */
        volatile boolean legacy;
        volatile long stateDelayMillis;
        /** Whole-database pulls (/internal/state) running now, and the most at once, over every proxy. */
        static final java.util.concurrent.atomic.AtomicInteger STATE_NOW = new java.util.concurrent.atomic.AtomicInteger();
        static final java.util.concurrent.atomic.AtomicInteger STATE_MAX = new java.util.concurrent.atomic.AtomicInteger();

        Proxy(int target) throws IOException {
            this.target = target;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(pool);
            server.createContext("/", this::handle);
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        private void handle(HttpExchange ex) throws IOException {
            try {
                forward(ex);
            } finally {
                ex.close();
            }
        }

        private void forward(HttpExchange ex) {
            try {
                String path = ex.getRequestURI().getRawPath();
                String q = ex.getRequestURI().getRawQuery();
                byte[] body = ex.getRequestBody().readAllBytes();
                if (legacy && (path.startsWith("/internal/v2/") || path.startsWith("/internal/v3/"))) {
                    byte[] b = "{\"error\":\"no such route\"}".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(404, b.length);
                    ex.getResponseBody().write(b);
                    return;
                }
                boolean state = path.equals("/internal/state");
                if (state) {
                    STATE_MAX.accumulateAndGet(STATE_NOW.incrementAndGet(), Math::max);
                }
                try {
                    if (state) {
                        Thread.sleep(stateDelayMillis);
                    }
                    forwardOrRefuse(ex, path, q, body);
                } finally {
                    if (state) {
                        STATE_NOW.decrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                try {
                    ex.sendResponseHeaders(502, -1);
                } catch (IOException ignored) {
                    // the exchange is gone
                }
            }
        }

        private void forwardOrRefuse(HttpExchange ex, String path, String q, byte[] body)
                throws IOException, InterruptedException {
            {
                if (path.equals("/internal/v2/buckets")) {
                    Thread.sleep(bucketsDelayMillis);
                }
                if (path.equals("/internal/v2/rows")) {
                    rowsRequests.incrementAndGet();
                    Thread.sleep(rowsDelayMillis);
                    if (rowsStatus != 0) {
                        byte[] b = "{\"error\":\"busy sending rows to other nodes; try again\"}".getBytes(StandardCharsets.UTF_8);
                        ex.getResponseHeaders().set("Content-Type", "application/json");
                        ex.sendResponseHeaders(rowsStatus, b.length);
                        ex.getResponseBody().write(b);
                        return;
                    }
                }
                HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target + path
                        + (q == null ? "" : "?" + q))).timeout(Duration.ofSeconds(60));
                String key = ex.getRequestHeaders().getFirst(InternalRoutes.KEY_HEADER);
                if (key != null) {
                    r.header(InternalRoutes.KEY_HEADER, key);
                }
                String type = ex.getRequestHeaders().getFirst("Content-Type");
                if (type != null) {
                    r.header("Content-Type", type);
                }
                r.method(ex.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
                HttpResponse<byte[]> res = http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
                res.headers().firstValue("Content-Type").ifPresent(v -> ex.getResponseHeaders().set("Content-Type", v));
                byte[] b = res.body();
                ex.sendResponseHeaders(res.statusCode(), b.length == 0 ? -1 : b.length);
                if (b.length > 0) {
                    ex.getResponseBody().write(b);
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    /**
     * The commit says a node runs at most two row pulls at once, over all peers and tables. A peer on 2.0 (a
     * rolling upgrade from 2.0.0, which tests/upgrade covers) is synced by whole databases, and
     * syncWholeDatabases still asks for every differing database at once, from every such peer, outside the
     * limiter: each answer is a whole database, held as text and as ops. Here node 2 missed rows in 6
     * databases and both its peers answer as 2.0 nodes.
     */
    @Test
    void wholeDatabasePullsFromA20PeerAlsoRunTwoAtATime() throws Exception {
        Proxy.STATE_NOW.set(0);
        Proxy.STATE_MAX.set(0);
        for (int i = 0; i < 3; i++) {
            startDirect(i);
        }
        eventually("node 0 accepts writes", () -> nodes[0].cluster().caughtUp()
                && nodes[0].cluster().reachable() >= nodes[0].cluster().quorum());
        for (int d = 0; d < 6; d++) {
            assertEquals(200, query(0, "CREATE DATABASE d" + d, "local").statusCode());
            assertEquals(200, query(0, "CREATE TABLE d" + d + ".t", "local").statusCode());
        }
        eventually("node 2 has d5.t", () -> {
            try {
                return query(2, "SELECT COUNT(*) FROM d5.t", "local").statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });
        nodes[2].close();
        nodes[2] = null;
        for (int d = 0; d < 6; d++) {
            assertEquals(200, query(0, "INSERT INTO d" + d + ".t (_id) VALUES ('x'), ('y')", "local").statusCode());
        }
        Proxy a = proxy(ports[0]);
        Proxy b = proxy(ports[1]);
        for (Proxy p : List.of(a, b)) {
            p.legacy = true;
            p.stateDelayMillis = 1_500;
        }
        nodes[2] = Node.start(config(2, List.of("127.0.0.1:" + a.port(), "127.0.0.1:" + b.port())), Node.QUERIES);
        eventually("node 2 has the rows of d0 to d5", () -> {
            try {
                for (int d = 0; d < 6; d++) {
                    if (!query(2, "SELECT COUNT(*) FROM d" + d + ".t", "local").body().contains("[[2]]")) {
                        return false;
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(Proxy.STATE_MAX.get() <= Cluster.MAX_PULLS, "node 2 ran " + Proxy.STATE_MAX.get()
                + " whole-database pulls at once; the limit is " + Cluster.MAX_PULLS);
    }

    /**
     * New on 75aa8dd: a read's sync never stops for a newer one, and nothing stops it when the read gives up
     * (syncForRead waits 5 s, then answers 503, and leaves its syncs running). Each majority read made while
     * a node catches up starts a sync with every peer that pulls every bucket that differed when it began,
     * whether or not the background sync has pulled them since. Ten such reads (clients retry 503s) keep the
     * node and its peers pulling and building the same pages long after the node has every row; a steady
     * node pulls none.
     */
    @Test
    void majorityReadsThatGaveUpStopPullingOnceTheNodeHasCaughtUp() throws Exception {
        int rows = 6_000;
        clusterWithNode2Behind(rows);
        Proxy a = proxy(ports[0]);
        Proxy b = proxy(ports[1]);
        a.rowsDelayMillis = 1_000;
        b.rowsDelayMillis = 1_000;
        nodes[2] = Node.start(config(2, List.of("127.0.0.1:" + a.port(), "127.0.0.1:" + b.port())), Node.QUERIES);
        List<java.util.concurrent.CompletableFuture<Integer>> reads = new ArrayList<>();
        for (int k = 0; k < 10; k++) {
            reads.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return query(2, "SELECT COUNT(*) FROM d.t", "majority").statusCode();
                } catch (Exception e) {
                    return -1;
                }
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (var r : reads) {
            statuses.add(r.get(60, java.util.concurrent.TimeUnit.SECONDS));
        }
        long deadline = System.currentTimeMillis() + 20_000;
        while (count(2) != rows || !nodes[2].cluster().caughtUp()) {
            assertTrue(System.currentTimeMillis() < deadline, "node 2 did not catch up");
            Thread.sleep(200);
        }
        Thread.sleep(1_000);
        int from = a.rowsRequests.get() + b.rowsRequests.get();
        Thread.sleep(5_000);
        int during = a.rowsRequests.get() + b.rowsRequests.get() - from;
        assertTrue(during <= 2, "node 2 asked for " + during + " row pages in the 5 s after it had every row and"
                + " was caught up (reads answered " + statuses + ")");
    }

    @Test
    void theProxyForwardsFaithfully() throws Exception {
        // A control: through plain proxies node 2 catches up and a majority read on it sees every row.
        clusterWithNode2Behind(300);
        Proxy a = proxy(ports[0]);
        Proxy b = proxy(ports[1]);
        nodes[2] = Node.start(config(2, List.of("127.0.0.1:" + a.port(), "127.0.0.1:" + b.port())), Node.QUERIES);
        HttpResponse<String> r = query(2, "SELECT COUNT(*) FROM d.t", "majority");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("[[300]]"), r.body());
        eventually("node 2 caught up", () -> nodes[2].cluster().caughtUp());
    }
}
