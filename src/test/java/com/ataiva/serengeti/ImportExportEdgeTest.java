package com.ataiva.serengeti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.query.Printer;
import com.ataiva.serengeti.core.server.HttpApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** From review 22l: serengeti import and export against a node in this process: lost answers, signals, page sizes, exit codes. */
class ImportExportEdgeTest {
    private static final String PW = "correct horse battery";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    private Node node;
    private int port;
    private final List<AutoCloseable> closing = new ArrayList<>();

    record Run(int status, String out, String err) {}

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable c : closing) {
            c.close();
        }
        if (node != null) {
            node.close();
        }
    }

    private void start() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", dir.resolve("data").toString());
        env.put("SERENGETI_PORT", Integer.toString(port));
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        node = Node.start(Config.load(null, env), Node.QUERIES);
        q("CREATE DATABASE d");
    }

    private HttpResponse<String> send(String statement) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/query"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("admin:" + PW)
                        .getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("query", statement)))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<?, ?> q(String statement) throws Exception {
        HttpResponse<String> r = send(statement);
        assertEquals(200, r.statusCode(), Printer.class.getSimpleName() + " " + statement.substring(0,
                Math.min(200, statement.length())) + ": " + r.body());
        return (Map<?, ?>) Json.parse(r.body());
    }

    private List<?> rows(String select) throws Exception {
        return (List<?>) q(select).get("rows");
    }

    private Run imp(int to, String input, String... args) {
        return imp(to, input.getBytes(StandardCharsets.UTF_8), args);
    }

    private Run imp(int to, byte[] input, String... args) {
        List<String> a = new ArrayList<>(List.of("--url", "http://127.0.0.1:" + to));
        a.addAll(List.of(args));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Import.run(a.toArray(String[]::new), Map.of("SERENGETI_PASSWORD", PW),
                new ByteArrayInputStream(input), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), null);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Run exp(int to, String... args) {
        List<String> a = new ArrayList<>(List.of("--url", "http://127.0.0.1:" + to));
        a.addAll(List.of(args));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Export.run(a.toArray(String[]::new), Map.of("SERENGETI_PASSWORD", PW), out,
                new PrintStream(err, true, StandardCharsets.UTF_8), null);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static List<String> ids(String ndjson) {
        List<String> ids = new ArrayList<>();
        ndjson.lines().forEach(l -> ids.add((String) ((Map<?, ?>) Json.parse(l)).get("_id")));
        return ids;
    }

    private List<String> selectIds(String table, String where) throws Exception {
        List<String> ids = new ArrayList<>();
        for (Object r : rows("SELECT _id FROM " + table + (where == null ? "" : " WHERE " + where) + " ORDER BY _id")) {
            ids.add((String) ((List<?>) r).get(0));
        }
        return ids;
    }

    /** In front of the node: passes each request on, answers 503, drops the answer, or waits first, as told. */
    final class Proxy implements AutoCloseable {
        enum Act { PASS, BUSY, BAD, PASS_AND_DROP }

        final HttpServer server;
        final List<String> statements = Collections.synchronizedList(new ArrayList<>());
        volatile BiFunction<Integer, String, Act> plan = (i, s) -> Act.PASS;
        volatile ToLongFunction<String> delay = s -> 0;
        final AtomicInteger n = new AtomicInteger();
        final List<Long> answers = Collections.synchronizedList(new ArrayList<>());

        Proxy() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            server.createContext("/", this::handle);
            server.start();
            closing.add(this);
        }

        int port() {
            return server.getAddress().getPort();
        }

        long count(String part) {
            synchronized (statements) {
                return statements.stream().filter(s -> s.contains(part)).count();
            }
        }

        private void handle(HttpExchange ex) throws IOException {
            byte[] body = ex.getRequestBody().readAllBytes();
            Act act = Act.PASS;
            if (ex.getRequestURI().getPath().equals("/v1/query")) {
                String statement = (String) Json.parseObject(new String(body, StandardCharsets.UTF_8)).get("query");
                statements.add(statement);
                act = plan.apply(n.getAndIncrement(), statement);
                long ms = delay.applyAsLong(statement);
                if (ms > 0) {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }
            }
            switch (act) {
                case BUSY -> answer(ex, 503, "{\"error\":\"this node is stopping\"}");
                case BAD -> answer(ex, 400, "{\"error\":\"refused by the probe\"}");
                default -> {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ex.getRequestURI()))
                            .header("Authorization", ex.getRequestHeaders().getFirst("Authorization"));
                    if (body.length > 0) {
                        b.header("Content-Type", "application/json").method(ex.getRequestMethod(),
                                HttpRequest.BodyPublishers.ofByteArray(body));
                    } else {
                        b.method(ex.getRequestMethod(), HttpRequest.BodyPublishers.noBody());
                    }
                    HttpResponse<String> r;
                    try {
                        r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    if (act == Act.PASS_AND_DROP) {
                        ex.close();
                    } else {
                        answers.add((long) r.body().getBytes(StandardCharsets.UTF_8).length);
                        answer(ex, r.statusCode(), r.body());
                    }
                }
            }
        }

        private void answer(HttpExchange ex, int code, String text) throws IOException {
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // ---- 1. an update run that stops part way, then the rerun the message asks for ----

    @Test
    void anUpdateRunStoppedPartWayAndRerunAsAdvisedKeepsEveryUpdate() throws Exception {
        start();
        List<String> wrong = new ArrayList<>();
        List<Object> clean = List.of(List.of("x1", 1L), List.of("x2", 1L), List.of("x3", 1L), List.of("x4", 1L));
        String good = "{\"_id\":\"x1\",\"v\":1}\n{\"_id\":\"x2\",\"v\":1}\n{\"_id\":\"x3\",\"v\":1}\n{\"_id\":\"x4\",\"v\":1}\n";

        // (a) a line import refuses itself
        q("CREATE TABLE d.a");
        q("INSERT INTO d.a (_id, v) VALUES ('x1', 0), ('x2', 0), ('x3', 0), ('x4', 0)");
        String bad = good.replace("{\"_id\":\"x2\",\"v\":1}", "not json");
        Run r = imp(port, bad, "--on-conflict", "update", "d.a");
        System.out.println("update-a first run: status " + r.status() + "\n" + r.err() + r.out());
        Run rerun = imp(port, good, "--on-conflict", "update", "d.a");
        System.out.println("update-a rerun as advised: status " + rerun.status() + " " + rerun.out());
        List<?> got = rows("SELECT _id, v FROM d.a");
        if (!got.equals(clean)) {
            wrong.add("(a) a refused line, then the advised rerun: " + got + " where a clean run gives " + clean
                    + "; the message said: " + r.err().replace('\n', '|'));
        }

        // (b) the node stays unavailable part way (--retries 0)
        q("CREATE TABLE d.b");
        q("INSERT INTO d.b (_id, v) VALUES ('x1', 0), ('x2', 0), ('x3', 0), ('x4', 0)");
        Proxy p = new Proxy();
        AtomicInteger writes = new AtomicInteger();
        p.plan = (i, s) -> s.startsWith("INSERT") && writes.getAndIncrement() >= 2 ? Proxy.Act.BUSY : Proxy.Act.PASS;
        Run u = imp(p.port(), good, "--on-conflict", "update", "--batch", "1", "--retries", "0", "d.b");
        System.out.println("update-b first run: status " + u.status() + "\n" + u.err() + u.out());
        Run rerunB = imp(port, good, "--on-conflict", "update", "d.b");
        System.out.println("update-b rerun as advised: status " + rerunB.status() + " " + rerunB.out());
        List<?> gotB = rows("SELECT _id, v FROM d.b");
        if (!gotB.equals(clean)) {
            wrong.add("(b) the node unavailable, then the advised rerun: " + gotB + " where a clean run gives " + clean
                    + "; the message said: " + u.err().replace('\n', '|'));
        }
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    // ---- 2. a lost answer at each statement, in every mode ----

    @Test
    void aLostAnswerAtAnyStatementLeavesWhatACleanRunLeaves() throws Exception {
        start();
        Proxy p = new Proxy();
        List<String> wrong = new ArrayList<>();
        int cases = 0;
        int table = 0;
        for (String mode : List.of("skip", "update", "fail")) {
            for (boolean gen : List.of(false, true)) {
                StringBuilder in = new StringBuilder();
                List<String> lines = new ArrayList<>(mode.equals("fail")
                        ? List.of("n1", "n2", "-", "n3", "n4", "-", "n5")
                        : List.of("e1", "n1", "e2", "-", "n2", "e3", "-", "n3", "e1"));
                for (String id : lines) {
                    if (id.equals("-")) {
                        if (gen) {
                            in.append("{\"v\":1,\"w\":\"new\"}\n");
                        }
                    } else {
                        in.append("{\"_id\":\"").append(id).append("\",\"v\":1,\"w\":\"new\"}\n");
                    }
                }
                String[] flags = gen ? new String[] {"--generate-ids"} : new String[0];
                // The clean run, and how many writes it sends.
                String cleanTable = "d.c" + (table++);
                q("CREATE TABLE " + cleanTable);
                seed(cleanTable, mode);
                p.statements.clear();
                p.plan = (i, s) -> Proxy.Act.PASS;
                Run clean = imp(p.port(), in.toString(), concat(flags, "--on-conflict", mode, "--batch", "2", cleanTable));
                assertEquals(0, clean.status(), clean.err());
                long sent = p.count("INSERT");
                List<?> want = rowsWithoutGeneratedIds(cleanTable);
                long wantGenerated = generated(cleanTable);
                for (int drop = 0; drop < sent; drop++) {
                    String t = "d.c" + (table++);
                    q("CREATE TABLE " + t);
                    seed(t, mode);
                    AtomicInteger k = new AtomicInteger();
                    int at = drop;
                    p.plan = (i, s) -> s.startsWith("INSERT") && k.getAndIncrement() == at ? Proxy.Act.PASS_AND_DROP
                            : Proxy.Act.PASS;
                    Run r = imp(p.port(), in.toString(), concat(flags, "--on-conflict", mode, "--batch", "2", t));
                    cases++;
                    List<?> got = rowsWithoutGeneratedIds(t);
                    long gotGenerated = generated(t);
                    String what = mode + (gen ? " --generate-ids" : "") + ", answer of write " + drop + " lost: ";
                    if (mode.equals("fail")) {
                        // Refused after the retry (documented); nothing doubled, nothing changed.
                        for (Object row : got) {
                            if (!want.contains(row)) {
                                wrong.add(what + "row " + row + " is not one a clean run writes");
                            }
                        }
                        if (gotGenerated > wantGenerated) {
                            wrong.add(what + gotGenerated + " generated rows, where a clean run has " + wantGenerated);
                        }
                    } else {
                        if (r.status() != 0) {
                            wrong.add(what + "status " + r.status() + ": " + r.err());
                        }
                        if (!got.equals(want) || gotGenerated != wantGenerated) {
                            wrong.add(what + got + " + " + gotGenerated + " generated, where a clean run gives " + want
                                    + " + " + wantGenerated);
                        }
                    }
                }
            }
        }
        System.out.println("lost answers: " + cases + " runs, " + wrong.size() + " wrong");
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    private void seed(String table, String mode) throws Exception {
        if (!mode.equals("fail")) {
            q("INSERT INTO " + table + " (_id, v, w, keep) VALUES ('e1', 0, 'old', TRUE), ('e2', 0, 'old', TRUE),"
                    + " ('e3', 0, 'old', TRUE)");
        }
    }

    private List<?> rowsWithoutGeneratedIds(String table) throws Exception {
        List<Object> out = new ArrayList<>();
        for (Object r : rows("SELECT _id, v, w, keep FROM " + table + " ORDER BY _id")) {
            String id = (String) ((List<?>) r).get(0);
            if (id.length() < 8) {
                out.add(r);
            }
        }
        return out;
    }

    private long generated(String table) throws Exception {
        long n = 0;
        for (Object r : rows("SELECT _id, v FROM " + table)) {
            if (((String) ((List<?>) r).get(0)).length() >= 8) {
                n++;
            }
        }
        return n;
    }

    private static String[] concat(String[] a, String... b) {
        List<String> l = new ArrayList<>(List.of(a));
        l.addAll(List.of(b));
        return l.toArray(String[]::new);
    }

    // ---- 3. an export while the table is written ----

    @Test
    void anExportDuringWritesHasEveryStableRowOnceAndInOrder() throws Exception {
        start();
        q("CREATE TABLE d.w");
        int rows = 3000;
        for (int from = 0; from < rows; from += 500) {
            StringBuilder s = new StringBuilder("INSERT INTO d.w (_id, n, v) VALUES ");
            for (int i = from; i < from + 500; i++) {
                s.append(i > from ? ", " : "").append("('k").append(String.format("%05d", 2 * i)).append("', ").append(i)
                        .append(", 0)");
            }
            q(s.toString());
        }
        List<String> failures = new ArrayList<>();
        for (String format : List.of("ndjson", "csv")) {
            AtomicBoolean stop = new AtomicBoolean();
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            Thread writer = new Thread(() -> {
                Random r = new Random(format.length());
                int made = 0;
                try {
                    while (!stop.get()) {
                        int i = r.nextInt(rows);
                        String id = "'k" + String.format("%05d", 2 * i) + "'";
                        switch (r.nextInt(5)) {
                            case 0 -> send("INSERT INTO d.w (_id, n) VALUES ('k" + String.format("%05d", 2 * r.nextInt(rows) + 1)
                                    + "x" + (made++) + "', -1)");
                            case 1 -> {
                                if (i % 3 == 0) {
                                    send("DELETE FROM d.w WHERE _id = " + id);
                                }
                            }
                            case 2 -> {
                                if (i % 3 == 0) {
                                    send("INSERT INTO d.w (_id, n, v) VALUES (" + id + ", " + i + ", 0) ON CONFLICT DO NOTHING");
                                }
                            }
                            default -> send("UPDATE d.w SET v = v + 1 WHERE _id = " + id);
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            writer.start();
            Run e = format.equals("csv") ? exp(port, "--csv", "--page", "7", "d.w") : exp(port, "--page", "7", "d.w");
            stop.set(true);
            writer.join();
            assertEquals(List.of(), errors);
            assertEquals(0, e.status(), e.err());
            List<String> got = new ArrayList<>();
            if (format.equals("csv")) {
                List<String> lines = e.out().lines().toList();
                for (String l : lines.subList(1, lines.size())) {
                    String f = l.substring(0, l.indexOf(','));
                    got.add(f.substring(1, f.length() - 1));
                }
            } else {
                got = ids(e.out());
            }
            for (int i = 1; i < got.size(); i++) {
                if (got.get(i - 1).compareTo(got.get(i)) >= 0) {
                    failures.add(format + ": out of order or twice at " + i + ": " + got.get(i - 1) + ", " + got.get(i));
                }
            }
            Set<String> seen = new HashSet<>(got);
            for (int i = 0; i < rows; i++) {
                if (i % 3 != 0 && !seen.contains("k" + String.format("%05d", 2 * i))) {
                    failures.add(format + ": a row there all along is missing: k" + String.format("%05d", 2 * i));
                }
            }
            System.out.println("export during writes (" + format + "): " + got.size() + " rows");
        }
        assertEquals(List.of(), failures, String.join("\n", failures));
    }

    // ---- 4. --where with _id terms, against the scan ----

    @Test
    void exportWhereWithIdTermsAnswersAsTheScan() throws Exception {
        start();
        q("CREATE TABLE d.r");
        List<String> extra = List.of("a", "a￿", "a￿b", "￿", "￿￿", "b'c", "😀x", "r",
                "r0", "r01", "r0100x", "r￿", "R0100", "r0100\u0000");
        StringBuilder s = new StringBuilder("INSERT INTO d.r (_id, n) VALUES ");
        for (int i = 0; i < 3000; i++) {
            s.append(i > 0 ? ", " : "").append("('r").append(String.format("%04d", i)).append("', ").append(i).append(")");
        }
        for (int i = 0; i < extra.size(); i++) {
            s.append(", (").append(Printer.value(extra.get(i))).append(", ").append(-i).append(")");
        }
        q(s.toString());
        q("DELETE FROM d.r WHERE n >= 1500 AND n < 1520");
        List<String> conditions = List.of(
                "_id >= 'r0100' AND _id < 'r0200'",
                "_id < 'r0200' AND _id >= 'r0100'",
                "_id > 'r0100' AND _id <= 'r0200' AND n > 150",
                "_id LIKE 'r01%' AND _id > 'r0150'",
                "_id LIKE 'r0%' AND _id LIKE 'r01%'",
                "_id LIKE 'r01%' AND _id LIKE 'r02%'",
                "_id LIKE 'a%' AND _id >= 'a'",
                "_id LIKE 'a￿%' AND _id < 'b'",
                "_id LIKE '￿%' AND _id > 'a'",
                "_id >= 'r0100' AND _id <= 'r0100'",
                "_id > 'r0100' AND _id <= 'r0100'",
                "_id > 'r2999' AND _id < 'r0001'",
                "_id > 'r1490' AND _id < 'r1530'",
                "_id < 'r0050' OR _id > 'r2950'",
                "(_id >= 'r1000' AND _id < 'r1010') OR (_id >= 'r2000' AND _id < 'r2010')",
                "(_id >= 'r1000' AND _id < 'r1010' AND n > 1004) OR (_id LIKE 'r2%' AND _id < 'r2003')",
                "_id > 5 AND _id < 'r0100'",
                "_id >= 'r0100' AND _id < NULL",
                "NOT _id < 'r1000' AND _id < 'r1100'",
                "_id >= 'r0' AND _id < 'r1' AND _id != 'r0005'",
                "_id >= 'r0' AND _id <= 'r0' AND _id LIKE 'r%'",
                "_id >= 'r0100' AND _id < 'r0200' AND _id IN ('r0150', 'r0250', 'a')",
                "_id >= 'r0100' AND _id < 'r0200' AND _id = 'r0150'",
                "_id > 'R' AND _id < 'S'",
                "_id >= 'r0100' AND _id < 'r0100\u0001'");
        List<String> wrong = new ArrayList<>();
        int compared = 0;
        for (String c : conditions) {
            List<String> want = selectIds("d.r", "NOT (NOT (" + c + "))");
            List<String> direct = selectIds("d.r", c);
            if (!direct.equals(want)) {
                wrong.add("SELECT WHERE " + c + ": " + direct.size() + " ids against the scan's " + want.size());
            }
            for (String page : List.of("1", "3", "1000")) {
                Run e = exp(port, "--page", page, "--where", c, "d.r");
                compared++;
                if (e.status() != 0) {
                    wrong.add("export --page " + page + " --where " + c + ": status " + e.status() + " " + e.err());
                    continue;
                }
                List<String> got = ids(e.out());
                if (!got.equals(want)) {
                    wrong.add("export --page " + page + " --where " + c + ": " + got.size() + " ids against the scan's "
                            + want.size());
                }
            }
        }
        System.out.println("export --where: " + compared + " exports compared, " + wrong.size() + " wrong");
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    // ---- 5. exotic ids, and an id near the body limit ----

    @Test
    void exoticIdsComeOutOnceEach() throws Exception {
        start();
        q("CREATE TABLE d.x");
        List<String> odd = List.of("'", "''", "a'b", "\n", "\r\n", "\r", "\u0000", "\u0001", "--", "-- x", "/*", ";", "\\",
                "\"", "<>&", " ", " ", "￿", "￿￿", "😀", "😀😁", "é",
                "é", " ", "  ", "\t", "x".repeat(5000), "<".repeat(5000), "a,b", "\"a\",\"b\"", "﻿", "﻿a");
        for (int i = 0; i < odd.size(); i++) {
            q("INSERT INTO d.x (_id, n) VALUES (" + Printer.value(odd.get(i)) + ", " + i + ")");
        }
        List<String> want = selectIds("d.x", null);
        List<String> wrong = new ArrayList<>();
        for (String page : List.of("1", "2", "1000")) {
            Run e = exp(port, "--page", page, "d.x");
            if (e.status() != 0 || !ids(e.out()).equals(want)) {
                wrong.add("export --page " + page + ": status " + e.status() + ", " + e.err());
            }
        }
        // NDJSON and CSV round trips.
        Run nd = exp(port, "d.x");
        q("CREATE TABLE d.x2");
        Run back = imp(port, nd.out(), "d.x2");
        if (back.status() != 0 || !selectIds("d.x2", null).equals(want)) {
            wrong.add("NDJSON round trip: " + back.err());
        }
        Run csv = exp(port, "--csv", "d.x");
        q("CREATE TABLE d.x3");
        Run backCsv = imp(port, csv.out(), "--csv", "--csv-types", "d.x3");
        if (backCsv.status() != 0 || !selectIds("d.x3", null).equals(want)) {
            wrong.add("CSV round trip: " + backCsv.err());
        } else if (!rows("SELECT _id, n FROM d.x3 ORDER BY _id").equals(rows("SELECT _id, n FROM d.x ORDER BY _id"))) {
            wrong.add("CSV round trip changed values");
        }
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    @Test
    void aTableWithAnIdNearTheBodyLimitExports() throws Exception {
        start();
        q("CREATE TABLE d.y");
        StringBuilder s = new StringBuilder("INSERT INTO d.y (_id) VALUES ");
        for (int i = 0; i < 99; i++) {
            s.append(i > 0 ? ", " : "").append("('a").append(String.format("%03d", i)).append("')");
        }
        q(s.toString());
        String head = "INSERT INTO d.y (_id) VALUES ('')";
        int n = HttpApi.MAX_BODY - Remote.body(head).length;
        String id = "x".repeat(n);
        q("INSERT INTO d.y (_id) VALUES ('" + id + "')"); // exactly the body limit: accepted
        q("INSERT INTO d.y (_id) VALUES ('y')");
        Run e = exp(port, "d.y");
        System.out.println("long id (" + n + " characters): export status " + e.status() + ", " + e.err());
        assertEquals(0, e.status(), "every row the node took should export: " + e.err());
        assertEquals(101, e.out().lines().count());
    }

    // ---- 6. a killed export ----

    @Test
    void aKilledExportLeavesNothingOnDisk() throws Exception {
        start();
        q("CREATE TABLE d.k");
        StringBuilder s = new StringBuilder("INSERT INTO d.k (_id, secret) VALUES ");
        for (int i = 0; i < 3000; i++) {
            s.append(i > 0 ? ", " : "").append("('k").append(String.format("%04d", i)).append("', 'card-").append(i).append("-").append("x".repeat(100)).append("')");
        }
        q(s.toString());
        Proxy p = new Proxy();
        p.delay = st -> st.contains("_id > ") ? 3000 : 0;
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        Path outDir = Files.createDirectories(dir.resolve("out"));
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        List<String> left = new ArrayList<>();
        for (List<String> args : List.of(List.of("--csv"), List.of("--csv", "-o", outDir.resolve("k.csv").toString()),
                List.of("-o", outDir.resolve("k.ndjson").toString()))) {
            long before = p.count("_id > ");
            List<String> cmd = new ArrayList<>(List.of(javaBin, "-Djava.io.tmpdir=" + tmp, "-cp",
                    System.getProperty("java.class.path"), "com.ataiva.serengeti.Serengeti", "export", "--page", "1000",
                    "--url", "http://127.0.0.1:" + p.port()));
            cmd.addAll(args);
            cmd.add("d.k");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("SERENGETI_PASSWORD", PW);
            pb.redirectOutput(dir.resolve("stdout.txt").toFile());
            pb.redirectError(dir.resolve("stderr.txt").toFile());
            Process proc = pb.start();
            long deadline = System.currentTimeMillis() + 60_000;
            while (p.count("_id > ") == before && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(200);
            proc.destroy(); // SIGTERM, as Ctrl-C or a stopped container would
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS));
            for (Path d : List.of(tmp, outDir)) {
                try (var files = Files.list(d)) {
                    for (Path f : files.toList()) {
                        String mode = java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f));
                        left.add(args + " (exit " + proc.exitValue() + "): " + f.getFileName() + " " + Files.size(f)
                                + " bytes, " + mode + (Files.readString(f).contains("card-0") ? ", holds rows" : ""));
                        Files.delete(f);
                    }
                }
            }
        }
        System.out.println("killed export left: " + left);
        assertEquals(List.of(), left, String.join("\n", left));
    }

    // ---- 6b. pages of rows that grow, and the image's 96 MB heap ----

    @Test
    void pagesStayNearFourMegabytesWhenRowsGrow() throws Exception {
        start();
        q("CREATE TABLE d.g");
        StringBuilder s = new StringBuilder("INSERT INTO d.g (_id, s) VALUES ");
        for (int i = 0; i < 500; i++) {
            s.append(i > 0 ? ", " : "").append("('k").append(String.format("%04d", i)).append("', 'small')");
        }
        q(s.toString());
        String big = "x".repeat(100_000);
        for (int i = 500; i < 1500; i += 8) {
            StringBuilder b = new StringBuilder("INSERT INTO d.g (_id, s) VALUES ");
            for (int j = i; j < i + 8 && j < 1500; j++) {
                b.append(j > i ? ", " : "").append("('k").append(String.format("%04d", j)).append("', '").append(big).append("')");
            }
            q(b.toString());
        }
        Proxy p = new Proxy();
        Run e = exp(p.port(), "-o", dir.resolve("g.ndjson").toString(), "d.g");
        assertEquals(0, e.status(), e.err());
        long max = 0;
        synchronized (p.answers) {
            for (long a : p.answers) {
                max = Math.max(max, a);
            }
        }
        System.out.println("growing rows: answers " + p.answers + "; the largest " + max + " bytes");
        List<String> wrong = new ArrayList<>();
        if (max > 2 * Export.PAGE_BYTES) {
            wrong.add("a page of " + max + " bytes of answer (IMPORT.md: later pages are sized to answers of about 4 MB)");
        }
        // The same export as the image runs it: java -Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1.
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-Xmx96m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-cp",
                System.getProperty("java.class.path"), "com.ataiva.serengeti.Serengeti", "export", "--url",
                "http://127.0.0.1:" + port, "-o", dir.resolve("g2.ndjson").toString(), "d.g");
        pb.environment().put("SERENGETI_PASSWORD", PW);
        pb.redirectErrorStream(true);
        pb.redirectOutput(dir.resolve("g2.txt").toFile());
        Process proc = pb.start();
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS));
        String said = Files.readString(dir.resolve("g2.txt"));
        System.out.println("growing rows, -Xmx96m: exit " + proc.exitValue() + ": "
                + said.substring(0, Math.min(600, said.length())).replace('\n', '|'));
        if (proc.exitValue() != 0) {
            wrong.add("with the image's heap: exit " + proc.exitValue() + ": " + said.lines().findFirst().orElse(""));
        }
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    // ---- 7. CSV against RFC 4180 and the stated rule ----

    @Test
    void csvEdgeCases() throws Exception {
        start();
        Map<String, List<Object>> cases = new LinkedHashMap<>();
        // input -> the rows SELECT _id, a, b gives, ordered by _id
        cases.put("_id,a,b\r\n1,x,y\r\n2,\"p\r\nq\",\"\"\"\"\r\n", List.of(List.of("1", "x", "y"), List.of("2", "p\r\nq", "\"")));
        cases.put("_id,a,b\n1,,\"\"\n", List.of(java.util.Arrays.asList("1", null, "")));
        cases.put("_id,a,b\r1,x,y\r2,u,v", List.of(List.of("1", "x", "y"), List.of("2", "u", "v")));
        cases.put("﻿_id,a,b\n1, x , y\n", List.of(List.of("1", " x ", " y")));
        cases.put("_id,a,b\n\n1,x,y\n\n\n", List.of(List.of("1", "x", "y")));
        cases.put("_id,a,b\n", List.of());
        cases.put("_id,a,b\n1,\"a\nb\rc\",\n", List.of(java.util.Arrays.asList("1", "a\nb\rc", null)));
        List<String> wrong = new ArrayList<>();
        int t = 0;
        for (Map.Entry<String, List<Object>> c : cases.entrySet()) {
            String table = "d.csv" + (t++);
            q("CREATE TABLE " + table);
            Run r = imp(port, c.getKey(), "--csv", table);
            List<?> got = r.status() == 0 ? rows("SELECT _id, a, b FROM " + table + " ORDER BY _id") : null;
            if (r.status() != 0 || !c.getValue().equals(got)) {
                wrong.add(Json.write(c.getKey()) + ": status " + r.status() + " " + r.err() + " got " + got);
            }
        }
        // The type rule with --csv-types.
        q("CREATE TABLE d.types");
        Run r = imp(port, "_id,v\n1,-0\n2,1E5\n3,007\n4,TRUE\n5,fAlSe\n6,\"1\"\n7,1e-400\n8,-1e-400\n9,+1\n10,1.\n11,.5\n"
                + "12, 1\n13,0x10\n14,9223372036854775807\n15,-9223372036854775808\n16,1.0E21\n", "--csv", "--csv-types", "d.types");
        assertEquals(0, r.status(), r.err());
        Map<String, Object> got = new LinkedHashMap<>();
        for (Object row : rows("SELECT _id, v FROM d.types")) {
            got.put((String) ((List<?>) row).get(0), ((List<?>) row).get(1));
        }
        System.out.println("csv types: " + Json.write(got));
        Map<String, Object> want = new LinkedHashMap<>();
        want.put("1", 0L);
        want.put("2", 100000.0);
        want.put("3", "007");
        want.put("4", true);
        want.put("5", false);
        want.put("6", "1");
        want.put("9", "+1");
        want.put("10", "1.");
        want.put("11", ".5");
        want.put("12", " 1");
        want.put("13", "0x10");
        want.put("14", Long.MAX_VALUE);
        want.put("15", Long.MIN_VALUE);
        want.put("16", 1.0E21);
        for (Map.Entry<String, Object> w : want.entrySet()) {
            Object g = got.get(w.getKey());
            if (!w.getValue().equals(g)) {
                wrong.add("--csv-types " + w.getKey() + ": " + g + " (" + (g == null ? "-" : g.getClass().getSimpleName())
                        + ") where " + w.getValue() + " was expected");
            }
        }
        System.out.println("csv: 1e-400 became " + got.get("7") + ", -1e-400 became " + got.get("8"));
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    // ---- 8. exit codes, and where the password may show ----

    @Test
    void exitCodesAndThePassword() throws Exception {
        start();
        q("CREATE TABLE d.e");
        List<String> wrong = new ArrayList<>();
        Path sub = Files.createDirectories(dir.resolve("adir"));
        Run r = imp(port, "", "d.e", sub.toString());
        System.out.println("import of a directory: " + r.status() + " " + r.err());
        if (r.status() != 2) {
            wrong.add("import of a directory as FILE: status " + r.status() + " (IMPORT.md: 2 when the file cannot be read): "
                    + r.err());
        }
        // A URL with a password in it: the password is printed with the node's address.
        List<String> a = List.of("--url", "http://admin:" + "s3cret-in-url" + "@127.0.0.1:" + port, "d.nope");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int st = Import.run(a.toArray(String[]::new), Map.of("SERENGETI_PASSWORD", PW), new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true), new PrintStream(err, true, StandardCharsets.UTF_8), null);
        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        int st2 = Export.run(a.toArray(String[]::new), Map.of("SERENGETI_PASSWORD", PW), new ByteArrayOutputStream(),
                new PrintStream(err2, true, StandardCharsets.UTF_8), null);
        String all = err.toString(StandardCharsets.UTF_8) + err2.toString(StandardCharsets.UTF_8);
        System.out.println("url with a password: import " + st + ", export " + st2 + ": " + all);
        if (all.contains(PW)) {
            wrong.add("the account's password is printed: " + all);
        }
        if (all.contains("s3cret-in-url")) {
            wrong.add("a password in --url is printed: " + all.replace('\n', '|'));
        }
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }
}
