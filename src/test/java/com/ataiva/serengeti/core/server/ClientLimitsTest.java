package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The limits on slow clients (ClientLimits), through the real HTTP API on short limits. */
class ClientLimitsTest {
    private static final String PASSWORD = "correct horse battery";
    /** About 4 MB of JSON: more than the sockets of a client that does not read take. */
    private static final List<String> BIG = List.of("x".repeat(1 << 20), "y".repeat(1 << 20), "z".repeat(1 << 20),
            "w".repeat(1 << 20));

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Socket> sockets = new ArrayList<>();
    private HttpApi api;
    private ClientLimits limits;
    private volatile QueryService queries = (q, r) -> Map.of("q", q);

    private static ClientLimits.Settings settings(long bodyGrace, long bodyRate, long writeTimeout, long evictAfter,
            long permitWait, long unsentBudget, long bodyBudget) {
        return new ClientLimits.Settings(bodyGrace, bodyRate, writeTimeout, evictAfter, permitWait, unsentBudget,
                bodyBudget, 20);
    }

    /** Long limits, so a test shortens only the one it is about. */
    private static ClientLimits.Settings relaxed() {
        return settings(60_000, 1, 60_000, 60_000, 10_000, 1L << 40, 1L << 40);
    }

    private void start(int connections, int workers, ClientLimits.Settings s) throws IOException {
        Users users = Users.open(dir, 4, System::currentTimeMillis);
        users.setPassword(Users.ADMIN, PASSWORD, Users.Role.ADMIN);
        limits = new ClientLimits(connections, workers, s, System::nanoTime, true);
        api = HttpApi.start("127.0.0.1", 0, null, new HttpApi.Deps(users, new AtomicReference<>(),
                (q, r) -> queries.run(q, r), () -> Map.of("node", "test"), System::currentTimeMillis), workers, limits);
    }

    @AfterEach
    void stop() throws IOException {
        for (Socket s : sockets) {
            s.close();
        }
        if (api != null) {
            api.close();
        }
    }

    private int port() {
        return api.address().getPort();
    }

    private static String basic() {
        return "Basic " + Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> query(String q) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/v1/query"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", basic())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"" + q + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> health() throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/health"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Socket open(int receiveBuffer) throws IOException {
        Socket s = new Socket();
        if (receiveBuffer > 0) {
            s.setReceiveBufferSize(receiveBuffer);
        }
        s.connect(new InetSocketAddress("127.0.0.1", port()), 5000);
        sockets.add(s);
        return s;
    }

    /** Starts a query for the big answer on a socket that never reads it. */
    private Socket stalledReader() throws IOException {
        Socket s = open(4096);
        byte[] body = "{\"query\": \"big\"}".getBytes(StandardCharsets.UTF_8);
        String head = "POST /v1/query HTTP/1.1\r\nHost: x\r\nAuthorization: " + basic()
                + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        return s;
    }

    /** Sends {@code bytes} one at a time, {@code gapMillis} apart, on a thread; stops at the first failure. */
    private Thread trickle(Socket s, byte[] bytes, long gapMillis) {
        Thread t = new Thread(() -> {
            try {
                OutputStream out = s.getOutputStream();
                for (byte b : bytes) {
                    out.write(b);
                    out.flush();
                    Thread.sleep(gapMillis);
                }
            } catch (IOException | InterruptedException e) {
                // cut
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** True once the server has closed the socket (end of stream or a reset), reading at most a little. */
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

    private static void await(String what, long millis, BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + millis * 1_000_000;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(10);
        }
    }

    @Test
    void aBodySentTooSlowlyIsCutAndHoldsNoPermitMeanwhile() throws Exception {
        start(8, 1, settings(300, 1000, 60_000, 60_000, 2_000, 1L << 40, 1L << 40));
        Socket slow = open(0);
        slow.getOutputStream().write(("POST /v1/query HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n"
                + "Content-Length: 1000\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        trickle(slow, new byte[1000], 100);
        Thread.sleep(150);
        // The only permit is free while the body comes in: another request runs at once.
        assertEquals(200, query("SHOW DATABASES").statusCode());
        assertTrue(closedByServer(slow, 3000), "the slow body's connection was not closed");
        assertTrue(limits.cuts()[ClientLimits.CUT_BODY] >= 1);
        await("the thread is free", 2000, () -> limits.busy() == 0);
    }

    @Test
    void aBodyThatKeepsTheRateIsRead() throws Exception {
        // 1,000 bytes a second after 300 ms of grace; this body comes at about 5,000 a second for 0.6 s.
        start(8, 1, settings(300, 1000, 60_000, 60_000, 2_000, 1L << 40, 1L << 40));
        String body = "{\"query\": \"" + "a".repeat(3000) + "\"}";
        Socket s = open(0);
        OutputStream out = s.getOutputStream();
        out.write(("POST /v1/query HTTP/1.1\r\nHost: x\r\nAuthorization: " + basic()
                + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        byte[] b = body.getBytes(StandardCharsets.US_ASCII);
        for (int off = 0; off < b.length; off += 100) {
            out.write(b, off, Math.min(100, b.length - off));
            out.flush();
            Thread.sleep(20);
        }
        s.setSoTimeout(5000);
        byte[] head = new byte[12];
        int n = s.getInputStream().readNBytes(head, 0, head.length);
        assertEquals("HTTP/1.1 200", new String(head, 0, n, StandardCharsets.US_ASCII));
        assertEquals(0, limits.cuts()[ClientLimits.CUT_BODY]);
    }

    @Test
    void aWriteTheClientDoesNotTakeIsCutAndTheAnswerGaveBackItsPermitFirst() throws Exception {
        start(8, 1, settings(60_000, 1, 1500, 60_000, 2_000, 1L << 40, 1L << 40));
        queries = (q, r) -> q.equals("big") ? Map.of("rows", BIG) : Map.of("q", q);
        stalledReader();
        // Once built (its room taken and the permit given back), the stuck write holds its thread, not the permit.
        await("the answer's write is stuck", 3000, () -> limits.unsentBytes() > 0 && limits.freePermits() == 1);
        assertEquals(200, query("SHOW DATABASES").statusCode());
        assertEquals(0, limits.cuts()[ClientLimits.CUT_WRITE]);
        await("the stuck write is cut", 3000, () -> limits.cuts()[ClientLimits.CUT_WRITE] >= 1);
        await("its thread is free", 3000, () -> limits.busy() == 0);
    }

    @Test
    void permitsBoundTheRequestsThatRunAtOnce() throws Exception {
        start(16, 2, settings(60_000, 1, 60_000, 60_000, 300, 1L << 40, 1L << 40));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        queries = (q, r) -> {
            int now = running.incrementAndGet();
            most.accumulateAndGet(now, Math::max);
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return Map.of("q", q);
        };
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return query("SHOW DATABASES");
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        // Two run; the other three wait 300 ms for a permit and get 503 with Retry-After.
        await("three are refused", 5000, () -> calls.stream().filter(CompletableFuture::isDone).count() == 3);
        for (CompletableFuture<HttpResponse<String>> c : calls) {
            if (c.isDone()) {
                assertEquals(503, c.join().statusCode(), c.join().body());
                assertEquals("1", c.join().headers().firstValue("Retry-After").orElse(""));
            }
        }
        assertEquals(2, most.get());
        assertEquals(0, limits.freePermits());
        go.countDown();
        for (CompletableFuture<HttpResponse<String>> c : calls) {
            int status = c.get(5, TimeUnit.SECONDS).statusCode();
            assertTrue(status == 200 || status == 503, "status " + status);
        }
        assertEquals(2, calls.stream().filter(c -> c.join().statusCode() == 200).count());
        await("the permits are back", 2000, () -> limits.freePermits() == 2);
    }

    @Test
    void whenEveryThreadIsBusyANewConnectionTakesTheThreadOfTheSlowestClient() throws Exception {
        start(2, 1, settings(60_000, 1, 60_000, 200, 2_000, 1L << 40, 1L << 40));
        byte[] request = ("GET /health HTTP/1.1\r\nHost: x\r\nX-Slow: " + "a".repeat(200) + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        Socket a = open(0);
        trickle(a, request, 50);
        Socket b = open(0);
        trickle(b, request, 50);
        await("both threads hold a slow client", 3000, () -> limits.busy() == 2);
        Thread.sleep(300);
        long t0 = System.nanoTime();
        assertEquals(200, health().statusCode());
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "took " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        assertTrue(limits.cuts()[ClientLimits.CUT_BUSY] >= 1);
        assertTrue(closedByServer(a, 50) || closedByServer(b, 50), "neither slow client was cut");
    }

    @Test
    void aNewConnectionDoesNotTakeTheThreadOfAClientThatIsNotSlowYet() throws Exception {
        start(1, 1, settings(60_000, 1, 60_000, 60_000, 2_000, 1L << 40, 1L << 40));
        Socket a = open(0);
        trickle(a, ("GET /health HTTP/1.1\r\nHost: x\r\nX-Slow: " + "a".repeat(200) + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII), 50);
        await("the thread holds the slow client", 3000, () -> limits.busy() == 1);
        // evictAfter is a minute: the new request waits for the thread instead.
        CompletableFuture<HttpResponse<String>> h = CompletableFuture.supplyAsync(() -> {
            try {
                return health();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(500);
        assertFalse(h.isDone());
        assertEquals(0, limits.cuts()[ClientLimits.CUT_BUSY]);
        a.close(); // the slow client goes; its thread takes the queued connection
        assertEquals(200, h.get(5, TimeUnit.SECONDS).statusCode());
    }

    @Test
    void stalledWritesOverTheUnsentBudgetAreCutOldestFirst() throws Exception {
        start(8, 2, settings(60_000, 1, 60_000, 100, 2_000, 5L << 20, 1L << 40));
        queries = (q, r) -> q.equals("big") ? Map.of("rows", BIG) : Map.of("q", q);
        stalledReader();
        await("the first answer is stuck", 3000, () -> limits.unsentBytes() > 0);
        Thread.sleep(300);
        assertEquals(0, limits.cuts()[ClientLimits.CUT_UNSENT]);
        stalledReader(); // 2 x 4 MB is over 5 MB
        await("the older stuck write is cut", 3000, () -> limits.cuts()[ClientLimits.CUT_UNSENT] == 1);
        Thread.sleep(300);
        assertEquals(1, limits.cuts()[ClientLimits.CUT_UNSENT], "only the older one");
        assertTrue(limits.unsentBytes() <= 5L << 20);
    }

    @Test
    void aLargeAnswerWaitsForRoomAndGets503WhenNoneComes() throws Exception {
        // No write is stalled long enough to cut, so the second large answer can only wait; one permit in all.
        start(8, 1, settings(60_000, 1, 60_000, 60_000, 1000, 5L << 20, 1L << 40));
        AtomicInteger built = new AtomicInteger();
        queries = (q, r) -> {
            if (q.equals("big")) {
                built.incrementAndGet();
                return Map.of("rows", BIG);
            }
            return Map.of("q", q);
        };
        stalledReader();
        await("the first answer is stuck", 3000, () -> limits.unsentBytes() > 0 && limits.freePermits() == 1);
        CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(() -> {
            try {
                return query("big");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        await("the second waits for room", 3000, () -> built.get() == 2);
        // It waits without the only permit: a small request runs meanwhile.
        long t0 = System.nanoTime();
        assertEquals(200, query("SHOW DATABASES").statusCode(), "a small answer needs no room");
        assertTrue(System.nanoTime() - t0 < 500_000_000L, "took " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        assertFalse(second.isDone());
        HttpResponse<String> r = second.get(5, TimeUnit.SECONDS);
        assertEquals(503, r.statusCode());
        assertTrue(r.body().contains("too many large answers"), r.body());
        assertEquals("1", r.headers().firstValue("Retry-After").orElse(""));
        assertEquals(0, limits.cuts()[ClientLimits.CUT_UNSENT]);
        assertEquals(1, limits.freePermits());
    }

    @Test
    void aCutLeavesNoInterruptBehindForTheNextRequestOnItsThread() throws Exception {
        start(1, 1, settings(60_000, 1, 300, 60_000, 2_000, 1L << 40, 1L << 40));
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicLong written = new AtomicLong();
        queries = (q, r) -> {
            if (q.equals("big")) {
                return Map.of("rows", BIG);
            }
            interrupted.set(Thread.currentThread().isInterrupted());
            try (FileChannel f = FileChannel.open(dir.resolve("f"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                written.set(f.write(ByteBuffer.wrap(new byte[100])));
                f.force(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return Map.of("q", q);
        };
        stalledReader();
        await("the stuck write is cut", 3000, () -> limits.cuts()[ClientLimits.CUT_WRITE] == 1);
        await("the thread is free", 3000, () -> limits.busy() == 0);
        assertEquals(200, query("write").statusCode());
        assertFalse(interrupted.get());
        assertEquals(100, written.get());
    }

    @Test
    void largeBodiesPastTheBudgetGet503() throws Exception {
        // 64 KiB of each body is free; past it, 600 KiB in all.
        start(8, 2, settings(60_000, 1, 60_000, 60_000, 2_000, 1L << 40, 600 << 10));
        Socket a = open(0);
        OutputStream out = a.getOutputStream();
        out.write(("POST /v1/query HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: "
                + (900 << 10) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[500 << 10]); // and then nothing: its buffer holds 448 KiB of the budget
        out.flush();
        await("the first body holds its room", 3000, () -> limits.bodyBytes() >= 448 << 10);
        String big = "a".repeat(300 << 10);
        HttpResponse<String> r = query(big);
        assertEquals(503, r.statusCode(), r.body());
        assertTrue(r.body().contains("busy reading"), r.body());
        assertEquals(200, query("SHOW DATABASES").statusCode(), "a small body needs no room");
        a.close();
        await("the room is given back", 3000, () -> limits.bodyBytes() == 0);
        assertEquals(200, query(big).statusCode());
    }
}
