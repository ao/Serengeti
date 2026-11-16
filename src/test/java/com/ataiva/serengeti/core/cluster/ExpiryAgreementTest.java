package com.ataiva.serengeti.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.server.InternalRoutes;
import com.ataiva.serengeti.core.storage.Store;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Nodes that do not count expired tombstones at the same cutoff compare live rows only, so an idle cluster
 * pulls nothing: two nodes with different tombstone_ttl_hours, the seconds around a 10-minute boundary, and
 * a 2.1 peer. From review 22e's probes (findings 2 and 5).
 */
class ExpiryAgreementTest {
    private static final String KEY = "a cluster key of at least 32 characters";
    private static final String PW = "correct horse battery";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @TempDir
    Path dir;

    private final int[] ports = new int[3];
    private final Node[] nodes = new Node[3];
    private final List<SyncProxy> proxies = new ArrayList<>();

    @BeforeEach
    void ports() throws IOException {
        for (int i = 0; i < 3; i++) {
            try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                ports[i] = s.getLocalPort();
            }
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
        proxies.forEach(SyncProxy::close);
    }

    private List<String> direct(int i) {
        List<String> peers = new ArrayList<>();
        for (int j = 0; j < 3; j++) {
            if (j != i) {
                peers.add("127.0.0.1:" + ports[j]);
            }
        }
        return peers;
    }

    private void start(int i, int ttlHours, List<String> peers) throws IOException {
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", dir.resolve("n" + i).toString());
        env.put("SERENGETI_PORT", Integer.toString(ports[i]));
        env.put("SERENGETI_PEERS", String.join(",", peers));
        env.put("SERENGETI_CLUSTER_KEY", KEY);
        env.put("SERENGETI_CLUSTER_PLAINTEXT", "allow");
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        env.put("SERENGETI_TOMBSTONE_TTL_HOURS", Integer.toString(ttlHours));
        nodes[i] = Node.start(Config.load(null, env), Node.QUERIES);
    }

    private static void eventually(String what, BooleanSupplier ok) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (ok.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private void writable() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            int n = i;
            eventually("node " + n + " accepts writes", () -> nodes[n].cluster().caughtUp()
                    && nodes[n].cluster().reachable() >= nodes[n].cluster().quorum());
        }
    }

    private void merge(int i, List<Map<String, Object>> ops) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports[i] + "/internal/apply"))
                .header(InternalRoutes.KEY_HEADER, KEY).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("ops", ops)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
    }

    private static Map<String, Object> op(String kind, String id, String at) {
        Map<String, Object> m = new HashMap<>();
        m.put("op", kind);
        m.put("db", "d");
        if (!kind.equals("create_db")) {
            m.put("table", "t");
        }
        if (id != null) {
            m.put("id", id);
        }
        if (kind.equals("put")) {
            m.put("row", Map.of("n", 1L));
        }
        m.put("at", at);
        return m;
    }

    /** {@code n} rows put at {@code w} and deleted just after; with the schema at {@code w - 10}. */
    private static List<Map<String, Object>> deleted(long w, int n) {
        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(op("create_db", null, (w - 10) + ".0.z1"));
        ops.add(op("create_table", null, (w - 10) + ".1.z1"));
        for (int k = 0; k < n; k++) {
            ops.add(op("put", "r" + k, w + "." + (2 * k) + ".z1"));
            ops.add(op("delete", "r" + k, w + "." + (2 * k + 1) + ".z1"));
        }
        return ops;
    }

    /** Pages of whole buckets, hashes and ids that nodes {@code which} pulled in {@code millis}, as text, and their sum. */
    private Object[] pagesDuring(long millis, int... which) throws InterruptedException {
        long[][] before = new long[3][];
        for (int i : which) {
            before[i] = nodes[i].cluster().pagesForTest();
        }
        Thread.sleep(millis);
        StringBuilder out = new StringBuilder();
        long sum = 0;
        for (int i : which) {
            long[] after = nodes[i].cluster().pagesForTest();
            out.append("node ").append(i).append(": +").append(after[0] - before[i][0]).append('/')
                    .append(after[1] - before[i][1]).append('/').append(after[2] - before[i][2]).append("; ");
            for (int k = 0; k < 3; k++) {
                sum += after[k] - before[i][k];
            }
        }
        return new Object[] {out.toString(), sum};
    }

    /**
     * Review 22e, finding 2: tombstone_ttl_hours 2 on nodes 0 and 1, 1 on node 2, as during a rolling change of
     * it. 500 rows deleted 90 minutes ago are expired on node 2, not on 0 and 1. Before, node 2 pulled them as
     * whole buckets every round and dropped them, and 0 and 1 fetched its hashes every round (140 pages in
     * 10 s). Now the pair compares live rows only.
     */
    @Test
    void nodesWithDifferentTtlsDoNotPullTheSameTombstonesEveryRound() throws Exception {
        quietClusterPulls(2, 2, 1);
    }

    /** The control: the same with one TTL on every node. */
    @Test
    void withOneTtlAQuietClusterPullsNothing() throws Exception {
        quietClusterPulls(2, 2, 2);
    }

    private void quietClusterPulls(int ttl0, int ttl1, int ttl2) throws Exception {
        int[] ttl = {ttl0, ttl1, ttl2};
        for (int i = 0; i < 3; i++) {
            start(i, ttl[i], direct(i));
        }
        writable();
        List<Map<String, Object>> ops = deleted(System.currentTimeMillis() - 90 * 60_000L, 500);
        for (int i = 0; i < 3; i++) {
            merge(i, ops);
        }
        Thread.sleep(8_000); // node 2 collects its expired tombstones within 5 s
        Object[] p = pagesDuring(10_000, 0, 1, 2);
        assertEquals(0L, p[1], "pages (bucket/hash/id) a quiet cluster pulled in 10 s: " + p[0]);
    }

    /**
     * Review 22e, finding 5: 2,000 tombstones that expire at the next boundary, held by all three nodes, whose
     * 5 s expiry checks are out of phase (node 0 started 2.5 s before 1 and 2). Before, from the boundary
     * until every node had crossed it, the ones that had pulled the step's tombstones back from the others
     * (40 and 42 pages, then 25 and 21, in two runs). Waits for a real boundary: up to 10 minutes, so it runs
     * only with -Dserengeti.slow=true.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "serengeti.slow", matches = "true")
    void aBoundaryDoesNotMakeTheNodesPullThatStepsTombstones() throws Exception {
        long step = Store.EXPIRY_STEP_MILLIS;
        long now = System.currentTimeMillis();
        long boundary = (Math.floorDiv(now, step) + 1) * step;
        if (boundary - now < 90_000) {
            boundary += step;
        }
        start(0, 1, direct(0));
        Thread.sleep(2_500);
        start(1, 1, direct(1));
        start(2, 1, direct(2));
        writable();
        List<Map<String, Object>> ops = deleted(boundary - 3_600_000L - 30_000, 2_000); // expired from the boundary on
        for (int i = 0; i < 3; i++) {
            merge(i, ops);
        }
        long wait = boundary - 1_000 - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        Object[] p = pagesDuring(21_000, 0, 1, 2);
        System.out.println("ExpiryAgreementTest: 1 s before the boundary to 20 s after, bucket/hash/id pages: " + p[0]);
        assertEquals(0L, p[1], "pages pulled around the boundary: " + p[0]);
    }

    /**
     * From review 22e: node 2 looks like 2.1 to nodes 0 and 1 (404 to /internal/v3, roots without "full") and
     * holds none of the 2,000 recent tombstones they hold, as a 2.1 node that dropped them on its own schedule.
     * Nodes 0 and 1 must not pull from it every round.
     */
    @Test
    void aPeerThatLooksLike21AndLacksTombstonesIsNotPulledEveryRound() throws Exception {
        SyncProxy a = new SyncProxy(ports[2]);
        SyncProxy b = new SyncProxy(ports[2]);
        proxies.add(a);
        proxies.add(b);
        a.like21 = true;
        b.like21 = true;
        start(0, 168, List.of("127.0.0.1:" + ports[1], "127.0.0.1:" + a.port()));
        start(1, 168, List.of("127.0.0.1:" + ports[0], "127.0.0.1:" + b.port()));
        start(2, 168, direct(2));
        writable();
        List<Map<String, Object>> ops = deleted(System.currentTimeMillis() - 60_000, 2_000);
        merge(0, ops);
        merge(1, ops);
        merge(2, ops.subList(0, 2)); // the schema only
        Thread.sleep(8_000);
        Object[] p = pagesDuring(10_000, 0, 1);
        assertEquals(0L, p[1], "pages nodes 0 and 1 pulled in 10 s: " + p[0]);
    }
}
