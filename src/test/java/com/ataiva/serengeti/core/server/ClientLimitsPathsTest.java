package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review 22n probes of ClientLimits: leaks on every path, no interrupt while a request runs, TLS, budgets. */
class ClientLimitsPathsTest {
    private static final String PASSWORD = "correct horse battery";
    private static final List<String> BIG = List.of("x".repeat(1 << 20), "y".repeat(1 << 20), "z".repeat(1 << 20),
            "w".repeat(1 << 20));

    @TempDir
    Path dir;

    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    private HttpApi api;
    private ClientLimits limits;
    private SSLContext clientTls;
    private HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile QueryService queries = (q, r) -> Map.of("q", q);
    private final AtomicInteger failures = new AtomicInteger();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    private static ClientLimits.Settings settings(long bodyGrace, long bodyRate, long writeTimeout, long evictAfter,
            long permitWait, long unsentBudget, long bodyBudget) {
        return new ClientLimits.Settings(bodyGrace, bodyRate, writeTimeout, evictAfter, permitWait, unsentBudget,
                bodyBudget, 20);
    }

    private void start(int connections, int workers, ClientLimits.Settings s, SSLContext tls) throws IOException {
        Users users = Users.open(dir, 4, System::currentTimeMillis);
        users.setPassword(Users.ADMIN, PASSWORD, Users.Role.ADMIN);
        limits = new ClientLimits(connections, workers, s, System::nanoTime, true);
        api = HttpApi.start("127.0.0.1", 0, tls, new HttpApi.Deps(users, new AtomicReference<>(),
                (q, r) -> queries.run(q, r), () -> Map.of("node", "test"), System::currentTimeMillis), workers, limits);
    }

    @AfterEach
    void stop() throws IOException {
        for (Socket s : List.copyOf(sockets)) {
            s.close();
        }
        if (api != null) {
            api.close();
        }
    }

    private String base() {
        return (clientTls == null ? "http" : "https") + "://127.0.0.1:" + api.address().getPort();
    }

    private static String basic() {
        return "Basic " + Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> query(String q) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/query"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", basic())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("query", q)))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Socket open(int receiveBuffer) throws IOException {
        Socket s;
        if (clientTls != null) {
            s = clientTls.getSocketFactory().createSocket();
        } else {
            s = new Socket();
        }
        if (receiveBuffer > 0) {
            s.setReceiveBufferSize(receiveBuffer);
        }
        s.connect(new InetSocketAddress("127.0.0.1", api.address().getPort()), 5000);
        if (s instanceof SSLSocket ssl) {
            ssl.startHandshake();
        }
        sockets.add(s);
        return s;
    }

    private Socket plain() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", api.address().getPort()), 5000);
        sockets.add(s);
        return s;
    }

    private static String post(String body, String extra) {
        return "POST /v1/query HTTP/1.1\r\nHost: x\r\nAuthorization: " + basic()
                + "\r\nContent-Type: application/json\r\n" + extra + "Content-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    private Socket stalledReader(String q) throws IOException {
        Socket s = open(4096);
        OutputStream out = s.getOutputStream();
        out.write(post(Json.write(Map.of("query", q)), "").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return s;
    }

    private static void await(String what, long millis, BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + millis * 1_000_000;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(10);
        }
    }

    private static boolean closedByServer(Socket s, long waitMillis) throws IOException {
        long end = System.nanoTime() + waitMillis * 1_000_000;
        s.setSoTimeout(100);
        InputStream in = s.getInputStream();
        byte[] buf = new byte[65536];
        while (System.nanoTime() < end) {
            try {
                if (in.read(buf) < 0) {
                    return true;
                }
            } catch (SocketTimeoutException e) {
                // still open
            } catch (IOException e) {
                return true;
            }
        }
        return false;
    }

    /** A handler that writes and forces a file, as a WAL append does; records any interrupt or failure. */
    private void fileWork(long millis) {
        long end = System.nanoTime() + millis * 1_000_000;
        int i = 0;
        do {
            if (Thread.currentThread().isInterrupted()) {
                failures.incrementAndGet();
                failed.add("interrupted while running");
            }
            try (FileChannel f = FileChannel.open(dir.resolve("f" + Thread.currentThread().getId() + "-" + (i++ % 4)),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                f.write(ByteBuffer.wrap(new byte[512]));
                f.force(false);
            } catch (IOException e) {
                failures.incrementAndGet();
                failed.add(e.toString());
            }
        } while (System.nanoTime() < end);
    }

    private QueryService handlers() {
        return (q, r) -> {
            switch (q) {
                case "big":
                    return Map.of("rows", BIG);
                case "boom":
                    throw new IllegalStateException("boom");
                case "reject":
                    throw new QueryService.Rejected(503, "rejected");
                case "slow":
                    fileWork(150);
                    return Map.of("q", "slow");
                default:
                    return Map.of("q", q);
            }
        };
    }

    /**
     * Every way a request can end: cut in its headers, its body or its write, a client that goes away at each
     * point, 413 and malformed bodies, 500 and 503 from the handler, 503 for no permit, no room for a body or
     * an answer. Afterwards every permit, every byte of both budgets and every thread is back, and no request
     * that ran saw an interrupt.
     */
    @Test
    void noPathLeavesAPermitABudgetOrAThreadBehind() throws Exception {
        start(8, 2, settings(200, 2000, 400, 100, 300, 6L << 20, 256 << 10), null);
        queries = handlers();
        ExecutorService clients = Executors.newFixedThreadPool(10);
        Set<Integer> statuses = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (int c = 0; c < 10; c++) {
            long seed = 2200 + c;
            runs.add(CompletableFuture.runAsync(() -> {
                Random r = new Random(seed);
                for (int i = 0; i < 40; i++) {
                    try {
                        op(r, statuses);
                    } catch (IOException e) {
                        // a cut or a reset: expected
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, clients));
        }
        CompletableFuture.allOf(runs.toArray(new CompletableFuture[0])).get(120, TimeUnit.SECONDS);
        clients.shutdownNow();
        for (Socket s : List.copyOf(sockets)) {
            s.close();
        }
        await("every thread is idle", 10_000, () -> limits.busy() == 0);
        assertEquals(2, limits.freePermits(), "permits");
        assertEquals(0, limits.unsentBytes(), "unsent budget");
        assertEquals(0, limits.bodyBytes(), "body budget");
        assertEquals(0, failures.get(), failed.toString());
        assertTrue(Set.of(200, 400, 413, 500, 503).containsAll(statuses), statuses.toString());
        assertEquals(200, query("SHOW DATABASES").statusCode());
        long[] cuts = limits.cuts();
        assertTrue(cuts[ClientLimits.CUT_BODY] + cuts[ClientLimits.CUT_WRITE] + cuts[ClientLimits.CUT_BUSY]
                + cuts[ClientLimits.CUT_UNSENT] > 0, "no cut happened, so the run proves little");
    }

    private void op(Random r, Set<Integer> statuses) throws IOException, InterruptedException {
        switch (r.nextInt(12)) {
            case 0 -> {
                Socket s = stalledReader("big");
                Thread.sleep(r.nextInt(600));
                s.close();
            }
            case 1 -> {
                Socket s = open(0);
                OutputStream out = s.getOutputStream();
                out.write(("POST /v1/query HTTP/1.1\r\nHost: x\r\nAuthorization: " + basic()
                        + "\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{\"query\": \"")
                        .getBytes(StandardCharsets.US_ASCII));
                long end = System.nanoTime() + r.nextInt(800) * 1_000_000L;
                while (System.nanoTime() < end) {
                    out.write('a');
                    out.flush();
                    Thread.sleep(30);
                }
                s.close();
            }
            case 2 -> {
                Socket s = open(0);
                OutputStream out = s.getOutputStream();
                out.write(("POST /v1/query HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: "
                        + (3 << 20) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(new byte[200 << 10]);
                out.flush();
                Thread.sleep(r.nextInt(300));
                s.close();
            }
            case 3 -> {
                Socket s = open(0);
                s.getOutputStream().write(("POST /v1/query HTTP/1.1\r\nHost: x\r\nAuthorization: " + basic()
                        + "\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                s.setSoTimeout(3000);
                byte[] head = new byte[12];
                int n = s.getInputStream().readNBytes(head, 0, 12);
                if (n == 12) {
                    statuses.add(Integer.parseInt(new String(head, 9, 3, StandardCharsets.US_ASCII)));
                }
                s.close();
            }
            case 4 -> statuses.add(query("boom").statusCode());
            case 5 -> statuses.add(query("reject").statusCode());
            case 6 -> statuses.add(query(r.nextBoolean() ? "slow" : "SHOW").statusCode());
            case 7 -> statuses.add(query("big").statusCode());
            case 8 -> statuses.add(query("e".repeat(300 << 10)).statusCode());
            case 9 -> {
                Socket s = open(0);
                OutputStream out = s.getOutputStream();
                byte[] head = ("GET /health HTTP/1.1\r\nHost: x\r\nX-Slow: " + "a".repeat(40) + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
                int upto = r.nextInt(head.length);
                for (int i = 0; i < upto; i++) {
                    out.write(head[i]);
                    out.flush();
                    Thread.sleep(10);
                }
                s.close();
            }
            case 10 -> open(0).close();
            default -> {
                Socket s = open(0);
                s.getOutputStream().write(post(Json.write(Map.of("query", "slow")), "").getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                Thread.sleep(r.nextInt(200));
                s.close();
            }
        }
    }

    /**
     * Two requests run file writes and forces, as a WAL append does, while new connections keep taking the
     * third thread from clients that are slow in their headers (evictAfter 0). The running requests are never
     * interrupted and their files never closed under them.
     */
    @Test
    void requestsThatRunAreNeverInterruptedWhileClientsAroundThemAreCut() throws Exception {
        start(3, 3, settings(60_000, 1, 60_000, 0, 5_000, 1L << 40, 1L << 40), null);
        AtomicInteger running = new AtomicInteger();
        queries = (q, r) -> {
            if (q.equals("work")) {
                running.incrementAndGet();
                fileWork(2_000);
                return Map.of("q", q);
            }
            return Map.of("q", q);
        };
        List<CompletableFuture<HttpResponse<String>>> work = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            work.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return query("work");
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        await("both run", 5000, () -> running.get() == 2);
        long end = System.nanoTime() + 1_500_000_000L;
        int opened = 0;
        while (System.nanoTime() < end) {
            Socket s = plain();
            s.getOutputStream().write("GET /health HTTP/1.1\r\nHost: x\r\n".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            opened++;
            Thread.sleep(5);
        }
        for (CompletableFuture<HttpResponse<String>> w : work) {
            HttpResponse<String> r = w.get(10, TimeUnit.SECONDS);
            assertEquals(200, r.statusCode(), r.body());
        }
        assertEquals(0, failures.get(), failed.toString());
        assertTrue(limits.cuts()[ClientLimits.CUT_BUSY] > 10, "cuts " + limits.cuts()[ClientLimits.CUT_BUSY]
                + " of " + opened + " connections");
        for (Socket s : List.copyOf(sockets)) {
            s.close();
        }
        await("every thread is idle", 5000, () -> limits.busy() == 0);
        assertEquals(3, limits.freePermits());
    }

    /**
     * Over TLS: a write the client does not take is cut and gives everything back, and a connection stuck half
     * way through its handshake gives its thread to a new one when every thread is busy.
     */
    @Test
    void overTlsAStalledWriteIsCutAndAHalfHandshakeGivesUpItsThread() throws Exception {
        char[] pw = TestCerts.PASSWORD.toCharArray();
        TestCerts.ca(dir, "ca");
        Path ks = TestCerts.node(dir, "ca", "n1", "dns:localhost,ip:127.0.0.1");
        clientTls = Tls.clientContext(TestCerts.truststore(dir, "ca"), pw);
        http = HttpClient.newBuilder().sslContext(clientTls).connectTimeout(Duration.ofSeconds(5)).build();
        start(2, 1, settings(60_000, 1, 500, 200, 2_000, 1L << 40, 1L << 40), Tls.serverContext(ks, pw));
        queries = handlers();
        Socket stalled = stalledReader("big");
        await("the stalled TLS write is cut", 5000, () -> limits.cuts()[ClientLimits.CUT_WRITE] >= 1);
        assertTrue(closedByServer(stalled, 2000));
        await("its thread is free", 3000, () -> limits.busy() == 0);
        assertEquals(1, limits.freePermits());
        assertEquals(0, limits.unsentBytes());
        assertEquals(200, query("SHOW").statusCode());
        // Two connections that send five bytes of a TLS record and stop: both threads wait in the handshake.
        for (int i = 0; i < 2; i++) {
            Socket s = plain();
            s.getOutputStream().write(new byte[] {0x16, 0x03, 0x01, 0x02, 0x00});
            s.getOutputStream().flush();
        }
        await("both threads hold a half handshake", 3000, () -> limits.busy() == 2);
        Thread.sleep(300);
        long t0 = System.nanoTime();
        assertEquals(200, query("SHOW").statusCode());
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L, "took " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        assertTrue(limits.cuts()[ClientLimits.CUT_BUSY] >= 1);
        assertEquals(0, failures.get(), failed.toString());
    }

    /**
     * /health and /ready do no work, but take a permit like any request: while every permit is held by requests
     * that run (long polls of the change feed, majority writes waiting on peers, slow queries), a liveness probe
     * waits for one and gets 503 after permitWait. Expected: they answer at once.
     */
    @Test
    void healthAndReadyAnswerWhileEveryPermitIsHeld() throws Exception {
        start(8, 1, settings(60_000, 1, 60_000, 60_000, 1_500, 1L << 40, 1L << 40), null);
        AtomicInteger running = new AtomicInteger();
        queries = (q, r) -> {
            running.incrementAndGet();
            fileWork(4_000);
            return Map.of("q", q);
        };
        CompletableFuture<HttpResponse<String>> hold = CompletableFuture.supplyAsync(() -> {
            try {
                return query("hold");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        await("the request runs", 5000, () -> running.get() == 1);
        List<String> slow = new ArrayList<>();
        for (String path : List.of("/health", "/ready")) {
            long t0 = System.nanoTime();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (r.statusCode() != 200 || ms > 500) {
                slow.add(path + ": " + r.statusCode() + " after " + ms + " ms " + r.body().trim());
            }
        }
        assertEquals(200, hold.get(10, TimeUnit.SECONDS).statusCode());
        assertEquals(List.of(), slow);
    }

    /**
     * The room taken for an answer before it is built is three times Json.estimate, which counts a string as
     * its length. The answer's own bytes, which are only part of what building it holds, should fit in that.
     */
    @Test
    void theRoomTakenForAnAnswerCoversAtLeastItsOwnBytes() {
        List<String> over = new ArrayList<>();
        for (String c : List.of("a", "é", "密", "\"", "\n", "<", "&", "\u0001")) {
            Map<String, Object> body = Map.of("rows", List.of(List.of(c.repeat(100_000))));
            long reserved = 3 * Json.estimate(body);
            long bytes = (Json.write(body) + "\n").getBytes(StandardCharsets.UTF_8).length;
            if (bytes > reserved) {
                over.add(String.format("U+%04X: %d bytes for %d reserved", (int) c.charAt(0), bytes, reserved));
            }
        }
        assertEquals(List.of(), over);
    }
}
