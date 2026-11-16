package com.ataiva.serengeti.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.storage.Store;
import java.io.IOException;
import java.net.InetAddress;
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
import java.util.StringJoiner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The size of what a catching-up node asks for at once (review 22d of bc4d8ef): pages of rows by id and of
 * whole buckets stay near PULL_TARGET_BYTES whatever the rows' size, and a majority read's whole-database
 * pulls from 2.0 peers end with the read. Node 2 reaches its peers through {@link SyncProxy}, which records
 * the largest answer per route.
 */
class SyncPagesTest {
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

    private SyncProxy proxy(int target) throws IOException {
        SyncProxy p = new SyncProxy(target);
        proxies.add(p);
        return p;
    }

    private void start(int i, List<String> peers) throws IOException {
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", dir.resolve("n" + i).toString());
        env.put("SERENGETI_PORT", Integer.toString(ports[i]));
        env.put("SERENGETI_PEERS", String.join(",", peers));
        env.put("SERENGETI_CLUSTER_KEY", KEY);
        env.put("SERENGETI_CLUSTER_PLAINTEXT", "allow");
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        nodes[i] = Node.start(Config.load(null, env), Node.QUERIES);
    }

    private void startDirect(int i) throws IOException {
        List<String> peers = new ArrayList<>();
        for (int j = 0; j < 3; j++) {
            if (j != i) {
                peers.add("127.0.0.1:" + ports[j]);
            }
        }
        start(i, peers);
    }

    /** Node 2 again, reaching nodes 0 and 1 through proxies. */
    private SyncProxy[] restartBehindProxies() throws IOException {
        SyncProxy a = proxy(ports[0]);
        SyncProxy b = proxy(ports[1]);
        start(2, List.of("127.0.0.1:" + a.port(), "127.0.0.1:" + b.port()));
        return new SyncProxy[] {a, b};
    }

    private void stop(int i) throws IOException {
        nodes[i].close();
        nodes[i] = null;
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

    private void ok(int i, String q) throws Exception {
        HttpResponse<String> r = query(i, q, "local");
        assertEquals(200, r.statusCode(), (q.length() > 120 ? q.substring(0, 120) : q) + ": " + r.body());
    }

    @SuppressWarnings("unchecked")
    private long count(int i, String table) throws Exception {
        HttpResponse<String> r = query(i, "SELECT COUNT(*) FROM " + table, "local");
        if (r.statusCode() != 200) {
            return -1;
        }
        return ((Number) ((List<Object>) ((List<Object>) Json.parseObject(r.body()).get("rows")).get(0)).get(0)).longValue();
    }

    private static void eventually(String what, long millis, java.util.concurrent.Callable<Boolean> ok) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (ok.call()) {
                    return;
                }
            } catch (Exception | AssertionError e) {
                // not yet
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private void waitWritable(int i) throws Exception {
        eventually("node " + i + " accepts writes", 20_000, () -> nodes[i].cluster().caughtUp()
                && nodes[i].cluster().reachable() >= nodes[i].cluster().quorum());
    }

    /** The rows {@code ids} of {@code table}, {@code pad} bytes each, as INSERTs of at most 900 KB. */
    private void insert(int node, String table, List<String> ids, int pad) throws Exception {
        String p = "y".repeat(pad);
        int per = Math.max(1, Math.min(500, 900_000 / (pad + 40)));
        for (int k = 0; k < ids.size(); k += per) {
            StringJoiner v = new StringJoiner(", ");
            for (String id : ids.subList(k, Math.min(ids.size(), k + per))) {
                v.add("('" + id + "', '" + p + "')");
            }
            ok(node, "INSERT INTO " + table + " (_id, pad) VALUES " + v);
        }
    }

    private static List<String> ids(String prefix, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(prefix + i);
        }
        return out;
    }

    /** {@code n} ids in each of the buckets {@code from} to {@code to} (exclusive). */
    private static List<String> idsInBuckets(String prefix, int from, int to, int n) {
        Map<Integer, Integer> have = new HashMap<>();
        List<String> out = new ArrayList<>();
        for (int i = 0; out.size() < (to - from) * n; i++) {
            String id = prefix + i;
            int b = Store.bucket(id);
            if (b >= from && b < to && have.merge(b, 1, Integer::sum) <= n) {
                out.add(id);
            }
        }
        return out;
    }

    @Test
    void pagesOfWholeBucketsFollowThePeersRowsAndBytes() {
        long[] rows = new long[Store.BUCKETS];
        long[] bytes = new long[Store.BUCKETS];
        java.util.Arrays.fill(rows, 40);
        java.util.Arrays.fill(bytes, 800_000);
        List<Integer> all = new ArrayList<>();
        for (int b = 0; b < 16; b++) {
            all.add(b);
        }
        List<List<Integer>> pages = Cluster.bucketPages(all, rows, bytes);
        assertEquals(List.of(0, 1, 2, 3, 4), pages.get(0), "5 x 800 KB fit in 4 MiB, 6 do not");
        assertEquals(16, pages.stream().mapToInt(List::size).sum());
        bytes[0] = 10L << 20;
        assertEquals(List.of(0), Cluster.bucketPages(all, rows, bytes).get(0), "a bucket alone over the target is its own page");
        java.util.Arrays.fill(rows, 1);
        java.util.Arrays.fill(bytes, 100);
        List<Integer> many = new ArrayList<>();
        for (int b = 0; b < Store.BUCKETS; b++) {
            many.add(b);
        }
        assertEquals(Cluster.BUCKETS_PER_REQUEST, Cluster.bucketPages(many, rows, bytes).get(0).size());
        java.util.Arrays.fill(rows, 1_000);
        assertEquals(2, Cluster.bucketPages(many, rows, bytes).get(0).size(), "about 2,000 rows a page");
        assertEquals(List.of(), Cluster.bucketPages(List.of(), rows, bytes));
    }

    @Test
    void pagesOfIdsFollowTheRowSizes() {
        List<String> ids = ids("r", 3_000);
        long[] w = new long[ids.size()];
        java.util.Arrays.fill(w, 100_000);
        assertEquals(41, Cluster.idPageSizeByBytes(ids, w, 0), "4 MiB of 100 KB rows");
        java.util.Arrays.fill(w, 10);
        assertEquals(Cluster.IDS_PER_REQUEST, Cluster.idPageSizeByBytes(ids, w, 0));
        assertEquals(1_000, Cluster.idPageSizeByBytes(ids, w, 2_000), "never past the end");
        w[5] = 50L << 20;
        assertEquals(1, Cluster.idPageSizeByBytes(ids, w, 5), "a row over the target goes alone");
        assertEquals(5, Cluster.idPageSizeByBytes(ids, w, 0), "and is not added to a page of others");
    }

    /** Node 2 holds 3 small rows in most buckets (so they are compared by hashes), then misses 300 of 100 KB. */
    private SyncProxy[] missLargeRows(boolean sizes) throws Exception {
        for (int i = 0; i < 3; i++) {
            startDirect(i);
        }
        waitWritable(0);
        ok(0, "CREATE DATABASE d");
        ok(0, "CREATE TABLE d.t");
        insert(0, "d.t", ids("s", 3_072), 10);
        eventually("node 2 has the small rows", 20_000, () -> count(2, "d.t") == 3_072);
        stop(2);
        insert(0, "d.t", ids("big", 300), 100_000);
        SyncProxy[] p = restartBehindProxies();
        for (SyncProxy x : p) {
            x.noSizes = !sizes;
        }
        eventually("node 2 has every row", 30_000, () -> count(2, "d.t") == 3_372);
        assertTrue(p[0].count("/internal/v3/hashes") + p[1].count("/internal/v3/hashes") > 0, "never compared by hashes");
        return p;
    }

    private static long largest(SyncProxy[] p, String path) {
        return Math.max(p[0].largest(path), p[1].largest(path));
    }

    private static String mib(long n) {
        return String.format("%.1f MiB", n / 1048576.0);
    }

    /** The sizes measured, in the test's output (target/surefire-reports), for reports. */
    private static void report(String what, long bytes) {
        System.out.println("SyncPagesTest: " + what + " " + mib(bytes));
    }

    /**
     * Review 22d, RS1: each page of hashes started its pages of ids again at 500 ids, whatever their size, so
     * with rows of 100 KB a page of ids came to about 20 MB (it measured 18 MiB). The peer now sends each
     * row's size with its hash, and pages of ids are filled to about PULL_TARGET_BYTES.
     */
    @Test
    void idPagesStayNearTheByteTargetForLargeRows() throws Exception {
        SyncProxy[] p = missLargeRows(true);
        long largest = largest(p, "/internal/v3/rows");
        report("largest page of rows by id, with row sizes:", largest);
        assertTrue(largest <= 2 * Cluster.PULL_TARGET_BYTES, "the largest page of rows by id was " + mib(largest));
    }

    /** From a peer that does not send row sizes, the size of the last page of ids carries on to the next page of hashes. */
    @Test
    void idPagesStayNearTheByteTargetWithoutRowSizes() throws Exception {
        SyncProxy[] p = missLargeRows(false);
        long largest = largest(p, "/internal/v3/rows");
        report("largest page of rows by id, without row sizes:", largest);
        assertTrue(largest <= 2 * Cluster.PULL_TARGET_BYTES, "the largest page of rows by id was " + mib(largest));
    }

    /**
     * Review 22d: buckets this node holds no row of were pulled whole with a first page of 16 buckets sized
     * blind, so a node without the rows of a big table asked for 16 buckets of them at once (16 x 10,000
     * rows in a table of 10 million). Here the first 16 buckets hold 40 rows of 20 KB each (12.8 MB), and one
     * bucket alone 60 rows of 100 KB (6 MB, more than one page). The peer's rows and bytes per bucket now size
     * those pulls, and a bucket bigger than a page is pulled by id.
     */
    @Test
    void aNodeWithNoRowsOfABigTablePullsItInBoundedPages() throws Exception {
        for (int i = 0; i < 3; i++) {
            startDirect(i);
        }
        waitWritable(0);
        ok(0, "CREATE DATABASE d");
        ok(0, "CREATE TABLE d.t");
        eventually("node 2 has d.t", 20_000, () -> count(2, "d.t") == 0);
        stop(2);
        insert(0, "d.t", idsInBuckets("m", 0, 16, 40), 20_000);
        insert(0, "d.t", idsInBuckets("b", 20, 21, 60), 100_000);
        SyncProxy[] p = restartBehindProxies();
        eventually("node 2 has every row", 30_000, () -> count(2, "d.t") == 16 * 40 + 60);
        long wholeBuckets = largest(p, "/internal/v2/rows");
        long byId = largest(p, "/internal/v3/rows");
        report("largest page of whole buckets of an empty table:", wholeBuckets);
        report("largest page of rows by id of an empty table:", byId);
        assertTrue(wholeBuckets <= 2 * Cluster.PULL_TARGET_BYTES, "the largest page of whole buckets was " + mib(wholeBuckets));
        assertTrue(byId > 0, "the bucket bigger than a page was not pulled by id");
        assertTrue(byId <= 2 * Cluster.PULL_TARGET_BYTES, "the largest page of rows by id was " + mib(byId));
    }

    /**
     * Review 22d: a majority read's whole-database pulls from 2.0 peers did not check that the read was over,
     * so they ran on after it had answered. Node 2 missed rows in 6 databases; both peers answer as 2.0, each
     * whole database in 10 s. The read gives up after 5 s with 4 of its 12 pulls running; the 8 waiting for a
     * turn must not start.
     */
    @Test
    void aMajorityReadsWholeDatabasePullsEndWithTheRead() throws Exception {
        for (int i = 0; i < 3; i++) {
            startDirect(i);
        }
        waitWritable(0);
        for (int d = 0; d < 6; d++) {
            ok(0, "CREATE DATABASE d" + d);
            ok(0, "CREATE TABLE d" + d + ".t");
        }
        eventually("node 2 has d5.t", 20_000, () -> count(2, "d5.t") == 0);
        stop(2);
        for (int d = 0; d < 6; d++) {
            ok(0, "INSERT INTO d" + d + ".t (_id) VALUES ('x'), ('y')");
        }
        SyncProxy a = proxy(ports[0]);
        SyncProxy b = proxy(ports[1]);
        for (SyncProxy x : List.of(a, b)) {
            x.legacy = true;
            x.stateDelayMillis = 10_000;
        }
        start(2, List.of("127.0.0.1:" + a.port(), "127.0.0.1:" + b.port()));
        eventually("node 2 syncs with its peers as 2.0 nodes", 20_000, () -> a.count("/internal/digests") > 0);
        HttpResponse<String> r = query(2, "SELECT COUNT(*) FROM d0.t", "majority");
        long atAnswer = nodes[2].cluster().readStatePullsForTest();
        assertTrue(atAnswer > 0 && atAnswer <= Cluster.MAX_READ_PULLS, "pulls started by the read: " + atAnswer
                + " (answer " + r.statusCode() + ")");
        Thread.sleep(12_000); // the running pulls end, and the waiting ones get their turn
        assertEquals(atAnswer, nodes[2].cluster().readStatePullsForTest(), "pulls the read started after it answered "
                + r.statusCode());
    }
}
