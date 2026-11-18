package com.ataiva.serengeti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.query.Parser;
import com.ataiva.serengeti.core.server.HttpApi;
import com.ataiva.serengeti.core.server.TestCerts;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code serengeti import} and {@code serengeti export} against a node in this process. */
class ImportExportTest {
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

    private Map<String, String> nodeEnv(int port, String... kv) {
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", dir.resolve("data").toString());
        env.put("SERENGETI_PORT", Integer.toString(port));
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return env;
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /** A node with database d and tables d.t and d.copy. */
    private void start() throws Exception {
        port = freePort();
        node = Node.start(Config.load(null, nodeEnv(port)), Node.QUERIES);
        q("CREATE DATABASE d");
        q("CREATE TABLE d.t");
        q("CREATE TABLE d.copy");
    }

    private Map<?, ?> q(String statement) throws Exception {
        return q(port, statement);
    }

    private static Map<?, ?> q(int port, String statement) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/query"))
                .header("Authorization", auth()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("query", statement)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), statement + ": " + r.body());
        return (Map<?, ?>) Json.parse(r.body());
    }

    private List<?> rows(String select) throws Exception {
        return (List<?>) q(select).get("rows");
    }

    private long count(String table) throws Exception {
        return (Long) ((List<?>) rows("SELECT COUNT(*) FROM " + table).get(0)).get(0);
    }

    private static String auth() {
        return "Basic " + Base64.getEncoder().encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8));
    }

    private Run imp(String input, String... args) {
        return imp(port, input.getBytes(StandardCharsets.UTF_8), args);
    }

    private static Run imp(int port, byte[] input, String... args) {
        List<String> a = new ArrayList<>(List.of("--url", "http://127.0.0.1:" + port));
        a.addAll(List.of(args));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Import.run(a.toArray(String[]::new), Map.of("SERENGETI_PASSWORD", PW),
                new ByteArrayInputStream(input), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), null);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Run exp(String... args) {
        return exp(port, args);
    }

    private static Run exp(int port, String... args) {
        List<String> a = new ArrayList<>(List.of("--url", "http://127.0.0.1:" + port));
        a.addAll(List.of(args));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Export.run(a.toArray(String[]::new), Map.of("SERENGETI_PASSWORD", PW), out,
                new PrintStream(err, true, StandardCharsets.UTF_8), null);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static List<Object> parseLines(String ndjson) {
        List<Object> rows = new ArrayList<>();
        ndjson.lines().forEach(l -> rows.add(Json.parse(l)));
        return rows;
    }

    /** The same object without its null values at the top. */
    private static Map<String, Object> withoutNulls(Object row) {
        Map<String, Object> m = new LinkedHashMap<>();
        ((Map<?, ?>) row).forEach((k, v) -> {
            if (v != null) {
                m.put((String) k, v);
            }
        });
        return m;
    }

    private static final String TRICKY = String.join("\n",
            "{\"_id\":\"a\",\"i\":9007199254740993,\"neg\":-0.0,\"zero\":0.0,\"max\":9223372036854775807,"
                    + "\"min\":-9223372036854775808,\"d\":0.1,\"tiny\":4.9E-324,\"big\":1.7976931348623157E308,"
                    + "\"t\":true,\"f\":false,\"s\":\"it's \\\"quoted\\\" <b>&amp;</b>\\n\\ttab \\\\ \\u0001\","
                    + "\"u\":\"é ü 中文 😀 \\ud834\\udd1e\",\"empty\":\"\","
                    + "\"nested\":{\"k\":[1,{\"x\":null,\"it's\":\"y\"}],\"\":{},\"deeper\":{\"a\":{\"b\":[[[]]]}}},"
                    + "\"arr\":[[],[1,\"two\",3.5,false,null,-0.0]]}",
            "{\"_id\":\"b😀\",\"n\":null,\"x\":1.0}",
            "",
            "{\"_id\":\"c'quote\",\"whole\":100.0,\"e\":1e300,\"Mixed_Case\":\"folded\"}",
            "   ",
            "{\"_id\":\"d\\u2028\\\"\",\"e\":-1.5E-10}");

    @Test
    void aRoundTripKeepsEveryValueAndItsType() throws Exception {
        start();
        Run in = imp(TRICKY, "d.t");
        assertEquals(0, in.status(), in.err());
        assertEquals("read 4 rows: 4 inserted, 0 updated, 0 skipped", in.out().replaceAll(" \\(.*\\)\n$", ""));
        // The node holds the numbers as they were written: an integer past 2^53, and -0.0 as a double.
        List<?> a = (List<?>) rows("SELECT i, neg, max, min FROM d.t WHERE _id = 'a'").get(0);
        assertEquals(List.of(9007199254740993L, -0.0, Long.MAX_VALUE, Long.MIN_VALUE), a);
        assertEquals(1L, count("d.t WHERE mixed_case = 'folded'"), "names fold to lowercase, as in INSERT");

        Run out = exp("d.t");
        assertEquals(0, out.status(), out.err());
        assertTrue(out.err().startsWith("serengeti export: 4 rows ("), out.err());
        List<Object> expected = new ArrayList<>();
        for (String line : TRICKY.lines().filter(l -> !l.isBlank()).toList()) {
            Map<String, Object> m = withoutNulls(Json.parse(line.replace("Mixed_Case", "mixed_case")));
            expected.add(m);
        }
        expected.sort((x, y) -> ((String) ((Map<?, ?>) x).get("_id")).compareTo((String) ((Map<?, ?>) y).get("_id")));
        List<Object> got = parseLines(out.out());
        assertEquals(expected, got, out.out());
        // Equal as maps, and -0.0 and 1.0 are doubles: Double.equals tells -0.0 from 0.0 and Long from Double.
        assertEquals(-0.0, ((Map<?, ?>) got.get(0)).get("neg"));
        assertEquals(1.0, ((Map<?, ?>) got.get(1)).get("x"));

        Run back = imp(out.out(), "d.copy");
        assertEquals(0, back.status(), back.err());
        Run again = exp("d.copy");
        assertEquals(out.out(), again.out(), "export, import into an empty table, export: the same text");

        // CSV: what the type rule keeps; objects and arrays come back as their JSON text.
        Run csv = exp("--csv", "d.t");
        assertEquals(0, csv.status(), csv.err());
        q("CREATE TABLE d.fromcsv");
        Run csvIn = imp(csv.out(), "--csv", "--csv-types", "d.fromcsv");
        assertEquals(0, csvIn.status(), csvIn.err());
        List<Object> fromCsv = parseLines(exp("d.fromcsv").out());
        List<Object> scalars = new ArrayList<>();
        for (Object row : expected) {
            Map<String, Object> m = new LinkedHashMap<>();
            ((Map<?, ?>) row).forEach((k, v) -> m.put((String) k, v instanceof Map || v instanceof List ? Json.write(v) : v));
            scalars.add(m);
        }
        assertEquals(scalars.size(), fromCsv.size());
        for (int i = 0; i < scalars.size(); i++) {
            // The CSV's columns are the union, in the order first seen: compare as maps.
            assertEquals(scalars.get(i), fromCsv.get(i), "row " + i);
        }
    }

    @Test
    void csvIsWrittenAsTheRfcSaysWithEveryStringQuoted() throws Exception {
        start();
        Run in = imp("""
                {"_id":"a","name":"Smith, J","qty":3,"price":2.5,"ok":true}
                {"_id":"b","name":"say \\"hi\\"\\nthere","note":"x"}
                {"_id":"c","qty":-0.0,"tags":[1,"a"],"ok":null}
                """, "d.t");
        assertEquals(0, in.status(), in.err());
        Run csv = exp("--csv", "d.t");
        assertEquals("_id,name,qty,price,ok,note,tags\r\n"
                + "\"a\",\"Smith, J\",3,2.5,true,,\r\n"
                + "\"b\",\"say \"\"hi\"\"\nthere\",,,,\"x\",\r\n"
                + "\"c\",,-0.0,,,,\"[1,\"\"a\"\"]\"\r\n", csv.out());
        Path file = dir.resolve("t.csv");
        assertEquals(0, exp("--csv", "-o", file.toString(), "d.t").status());
        assertEquals(csv.out(), Files.readString(file));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        try (var s = Files.list(dir)) {
            assertEquals(List.of(), s.filter(p -> p.getFileName().toString().contains(".tmp")).toList(), "no spool left");
        }
    }

    @Test
    void csvQuotingAndTheTypeRule() throws Exception {
        start();
        String text = "_id,Name,qty,flag,note,code\r\n"
                + "1,\"Smith, J\",3,TRUE,\"say \"\"hi\"\"\",007\r\n"
                + "2,\"two\r\nlines\",\"3\",false,\"\",1e2\n"
                + "3,plain,,true,,-0.0\n"
                + "\n"
                + "4, padded ,-9223372036854775808,yes,x,9007199254740993";
        Run strings = imp(text, "--csv", "d.t");
        assertEquals(0, strings.status(), strings.err());
        assertTrue(strings.out().startsWith("read 4 rows: 4 inserted"), strings.out());
        List<?> got = rows("SELECT _id, name, qty, flag, note, code FROM d.t");
        assertEquals(List.of(
                List.of("1", "Smith, J", "3", "TRUE", "say \"hi\"", "007"),
                List.of("2", "two\r\nlines", "3", "false", "", "1e2"),
                java.util.Arrays.asList("3", "plain", null, "true", null, "-0.0"),
                List.of("4", " padded ", "-9223372036854775808", "yes", "x", "9007199254740993")), got);
        assertEquals(List.of("_id", "name", "flag", "code"), q("SELECT * FROM d.t WHERE _id = '3'").get("columns"),
                "an empty unquoted field leaves the column out");

        Run typed = imp(text, "--csv", "--csv-types", "d.copy");
        assertEquals(0, typed.status(), typed.err());
        List<?> t = rows("SELECT _id, name, qty, flag, note, code FROM d.copy");
        assertEquals(List.of(
                List.of("1", "Smith, J", 3L, true, "say \"hi\"", "007"),
                List.of("2", "two\r\nlines", "3", false, "", 100.0),
                java.util.Arrays.asList("3", "plain", null, true, null, -0.0),
                List.of("4", " padded ", Long.MIN_VALUE, "yes", "x", 9007199254740993L)), t,
                "a quoted field stays a string, _id is always one, 007 is not a JSON number");
        assertTrue(Double.doubleToRawLongBits((Double) ((List<?>) t.get(2)).get(5)) < 0);

        // A header only, and nothing at all, are no rows.
        assertEquals("read 0 rows: 0 inserted, 0 updated, 0 skipped", imp("_id,a\n", "--csv", "d.t").out()
                .replaceAll(" \\(.*\\)\n$", ""));
        assertEquals(0, imp("", "--csv", "d.t").status());
    }

    /** Runs an import that must stop at {@code line}, with the message holding {@code says}. */
    private Run refused(String input, long line, String says, String... args) {
        return refused(input.getBytes(StandardCharsets.UTF_8), line, says, args);
    }

    private Run refused(byte[] input, long line, String says, String... args) {
        Run r = imp(port, input, args);
        assertEquals(1, r.status(), r.err());
        assertTrue(r.err().startsWith("serengeti import: line " + line + ": "), r.err());
        assertTrue(r.err().contains(says), says + " in " + r.err());
        assertTrue(r.err().contains("the lines before line " + line + " are written"), r.err());
        return r;
    }

    @Test
    void anInputLineThatCannotBeWrittenIsRefusedWithItsNumber() throws Exception {
        start();
        String ok = "{\"_id\":\"ok1\"}\n\n";
        refused(ok + "not json\n{\"_id\":\"never\"}", 3, "not JSON", "--on-conflict", "skip", "d.t");
        refused(ok + "[1, 2]", 3, "not a JSON object", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"x\": 1}", 3, "the row has no _id (--generate-ids", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": 5}", 3, "_id must be a non-empty string, not a number", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"\"}", 3, "not an empty string", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": null}", 3, "not null", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"a\", \"first name\": 1}", 3, "'first name' is not a column name", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"a\", \"order\": 1}", 3, "'order' is not a column name", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"a\", \"\u212a\": 1}", 3, "is not a column name", "--on-conflict", "skip", "d.t"); // KELVIN SIGN folds to k
        refused(ok + "{\"_id\": \"a\", \"" + "c".repeat(64) + "\": 1}", 3, "is not a column name", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"a\", \"Qty\": 1, \"qty\": 2}", 3, "'Qty' and 'qty' are one column", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"a\", \"n\": 9223372036854775808}", 3, "integer outside the 64-bit range", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"a\", \"n\": -9223372036854775809}", 3, "integer outside the 64-bit range", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"a\", \"n\": 1e400}", 3, "number out of range", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"a\", \"n\": NaN}", 3, "not JSON", "--on-conflict", "skip", "d.t");
        refused(ok + "{\"_id\": \"a\", \"n\": \"\\ud800\"}", 3, "unpaired surrogate", "--on-conflict", "skip", "d.t");
        String deep = "[".repeat(Parser.MAX_VALUE_DEPTH + 1) + "]".repeat(Parser.MAX_VALUE_DEPTH + 1);
        refused(ok + "{\"_id\": \"a\", \"n\": " + deep + "}", 3, "the value of n nests objects and arrays more than 64 deep", "--on-conflict", "skip", "d.t");
        byte[] notUtf8 = (ok + "{\"_id\": \"a\", \"s\": \"").getBytes(StandardCharsets.UTF_8);
        notUtf8 = java.util.Arrays.copyOf(notUtf8, notUtf8.length + 3);
        notUtf8[notUtf8.length - 3] = (byte) 0xC3; // a lead byte without its continuation
        notUtf8[notUtf8.length - 2] = '"';
        notUtf8[notUtf8.length - 1] = '}';
        refused(notUtf8, 3, "not UTF-8", "--on-conflict", "skip", "d.t");
        // CSV
        refused("_id,a\n1,x\n2,\"two\nlines\"\n3,x,extra\n", 5, "3 fields, where the header has 2", "--csv", "--on-conflict", "skip", "d.copy");
        refused("_id,a\n1,x\n2,\"never closed\n3,x\n", 3, "a quoted field that starts here is not closed", "--csv", "--on-conflict", "skip", "d.copy");
        refused("_id,a\n1,x\n2,ab\"c\n", 3, "a quote inside an unquoted field", "--csv", "--on-conflict", "skip", "d.copy");
        refused("_id,a\n1,x\n2,\"ab\"c\n", 3, "text after the closing quote", "--csv", "--on-conflict", "skip", "d.copy");
        refused("_id,a b\n1,x\n", 1, "in the header, 'a b' is not a column name", "--csv", "--on-conflict", "skip", "d.copy");
        refused("_id,A,a\n1,x,y\n", 1, "in the header, a is listed twice", "--csv", "--on-conflict", "skip", "d.copy");
        refused("_id,n\n1,1\n2,NaN\n", 3, "n: 'NaN' is not a number a JSON value can hold", "--csv", "--csv-types", "--on-conflict", "skip", "d.copy");
        refused("_id,n\n1,1\n2,-inf\n", 3, "is not a number", "--csv", "--csv-types", "--on-conflict", "skip", "d.copy");
        refused("_id,n\n1,1\n2,99999999999999999999\n", 3, "an integer outside the 64-bit range", "--csv", "--csv-types", "--on-conflict", "skip", "d.copy");
        refused("_id,n\n1,1\n2,1e999\n", 3, "a number beyond the range of a double", "--csv", "--csv-types", "--on-conflict", "skip", "d.copy");
        refused("_id,n\n1,1\n,2\n", 3, "the row has no _id", "--csv", "--on-conflict", "skip", "d.copy");
        // Every line before a refused one was written (in d.copy, rows 1 and the two-line 2), nothing after it.
        assertEquals(List.of(List.of("ok1")), rows("SELECT _id FROM d.t"));
        assertEquals(List.of(List.of("1"), List.of("2")), rows("SELECT _id FROM d.copy"));
    }

    @Test
    void theNodesRefusalStopsAtItsLineAndASkippingRerunFinishes() throws Exception {
        start();
        q("INSERT INTO d.t (_id, n) VALUES ('e', 0)");
        StringBuilder in = new StringBuilder();
        for (char c = 'a'; c <= 'j'; c++) {
            in.append("{\"_id\":\"").append(c).append("\",\"n\":1}\n");
        }
        Run r = refused(in.toString(), 5, "400: a row with _id 'e' already exists in 'd.t'", "d.t");
        assertTrue(r.out().startsWith("read 10 rows: 4 inserted, 0 updated, 0 skipped"), r.out());
        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c"), List.of("d"), List.of("e")), rows("SELECT _id FROM d.t"));
        Run rerun = imp(in.toString(), "--on-conflict", "skip", "d.t");
        assertEquals(0, rerun.status(), rerun.err());
        assertTrue(rerun.out().startsWith("read 10 rows: 5 inserted, 0 updated, 5 skipped"), rerun.out());
        assertEquals(10L, count("d.t"));
        assertEquals(List.of(List.of(0L)), rows("SELECT n FROM d.t WHERE _id = 'e'"));

        // A role that may not write: the first line, and its message.
        HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/users/reader"))
                .header("Authorization", auth()).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"password\": \"reader password 1\", \"role\": \"read\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Import.run(new String[] {"--url", "http://127.0.0.1:" + port, "--user", "reader", "d.t"},
                Map.of("SERENGETI_PASSWORD", "reader password 1"),
                new ByteArrayInputStream("{\"_id\":\"x\"}".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(new ByteArrayOutputStream(), true), new PrintStream(err, true), null);
        assertEquals(1, status);
        assertTrue(err.toString().contains("line 1: 403"), err.toString());
    }

    @Test
    void conflictsAreSkippedOrUpdateTheColumnsTheRowHas() throws Exception {
        start();
        q("INSERT INTO d.t (_id, x, keep) VALUES ('a', 1, TRUE), ('b', 2, TRUE)");
        String in = """
                {"_id":"a","x":10,"y":{"new":[1]}}
                {"_id":"b"}
                {"_id":"c","x":3}
                """;
        Run skip = imp(in, "--on-conflict", "skip", "d.t");
        assertEquals(0, skip.status(), skip.err());
        assertTrue(skip.out().startsWith("read 3 rows: 1 inserted, 0 updated, 2 skipped"), skip.out());
        assertEquals(List.of(1L), rows("SELECT x FROM d.t WHERE _id = 'a'").get(0));
        q("DELETE FROM d.t WHERE _id = 'c'");
        Run update = imp(in, "--on-conflict", "update", "d.t");
        assertEquals(0, update.status(), update.err());
        assertTrue(update.out().startsWith("read 3 rows: 1 inserted, 1 updated, 1 skipped"), update.out());
        assertEquals(List.of(List.of("a", 10L, true, Map.of("new", List.of(1L))), java.util.Arrays.asList("b", 2L, true, null),
                java.util.Arrays.asList("c", 3L, null, null)), rows("SELECT _id, x, keep, y FROM d.t"),
                "update sets the columns the line has and keeps the others; a line of only _id changes nothing");

        // One _id twice in the input: the later line comes later.
        Run twice = imp("{\"_id\":\"z\",\"n\":1}\n{\"_id\":\"z\",\"n\":2}\n", "--on-conflict", "update", "d.copy");
        assertTrue(twice.out().startsWith("read 2 rows: 1 inserted, 1 updated, 0 skipped"), twice.out());
        assertEquals(List.of(List.of(2L)), rows("SELECT n FROM d.copy WHERE _id = 'z'"));
        Run twiceSkip = imp("{\"_id\":\"y\",\"n\":1}\n{\"_id\":\"y\",\"n\":2}\n", "--on-conflict", "skip", "d.copy");
        assertTrue(twiceSkip.out().startsWith("read 2 rows: 1 inserted, 0 updated, 1 skipped"), twiceSkip.out());
        assertEquals(List.of(List.of(1L)), rows("SELECT n FROM d.copy WHERE _id = 'y'"));
        Run twiceFail = refused("{\"_id\":\"w\",\"n\":1}\n{\"_id\":\"w\",\"n\":2}\n", 2, "already exists", "d.copy");
        assertTrue(twiceFail.out().startsWith("read 2 rows: 1 inserted"), twiceFail.out());
    }

    @Test
    void generatedIdsAreRandomAndOnlyForRowsWithoutOne() throws Exception {
        start();
        Run r = imp("{\"n\":1}\n{\"_id\":\"mine\",\"n\":2}\n{}\n", "--generate-ids", "d.t");
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().startsWith("read 3 rows: 3 inserted"), r.out());
        List<?> ids = rows("SELECT _id FROM d.t");
        assertEquals(3, ids.size());
        assertTrue(ids.contains(List.of("mine")));
        for (Object id : ids) {
            String s = (String) ((List<?>) id).get(0);
            assertTrue(s.equals("mine") || s.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}"), s);
        }
    }

    @Test
    void emptyInputImportsNothing() throws Exception {
        start();
        for (String in : List.of("", "\n\n  \n", "\r\n")) {
            Run r = imp(in, "d.t");
            assertEquals(0, r.status(), r.err());
            assertTrue(r.out().startsWith("read 0 rows: 0 inserted, 0 updated, 0 skipped"), r.out());
        }
        Run e = exp("d.t");
        assertEquals(0, e.status(), e.err());
        assertEquals("", e.out());
        assertEquals("_id\r\n", exp("--csv", "d.t").out());
        assertEquals(0, imp("\uFEFF{\"_id\":\"bom\"}\n", "d.t").status(), "a byte order mark is skipped");
    }

    @Test
    void theArgumentsAndTheTableAreCheckedBeforeAnyRow() throws Exception {
        start();
        assertEquals(2, imp("", "--bogus", "d.t").status());
        assertEquals(2, imp("").status());
        assertEquals(2, imp("", "d.t", "f1", "f2").status());
        assertEquals(2, imp("", "nodot").status());
        assertEquals(2, imp("", "d.select").status());
        assertEquals(2, imp("", "--csv-types", "d.t").status());
        assertEquals(2, imp("", "--on-conflict", "replace", "d.t").status());
        assertEquals(2, imp("", "--batch", "0", "d.t").status());
        assertEquals(2, imp("", "--retries", "x", "d.t").status());
        assertEquals(2, imp("", "--url").status());
        Run missing = imp("", "d.t", dir.resolve("nope.ndjson").toString());
        assertEquals(2, missing.status());
        assertTrue(missing.err().contains("cannot read"), missing.err());
        Run noTable = imp("{\"_id\":\"a\"}", "d.nope");
        assertEquals(1, noTable.status());
        assertTrue(noTable.err().contains("table 'd.nope' does not exist (--create makes it)"), noTable.err());
        Run create = imp("{\"_id\":\"a\"}", "--create", "made.here");
        assertEquals(0, create.status(), create.err());
        assertEquals(List.of(List.of("a")), rows("SELECT _id FROM made.here"));
        assertTrue(imp("", "--help").out().startsWith("usage: serengeti import"));
        assertTrue(exp("--help").out().startsWith("usage: serengeti export"));

        Map<String, String> wrong = Map.of("SERENGETI_PASSWORD", "not the password");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, Import.run(new String[] {"--url", "http://127.0.0.1:" + port, "d.t"}, wrong,
                new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true), null));
        assertTrue(err.toString().contains("401"), err.toString());
        assertEquals(2, Export.run(new String[] {"--url", "http://127.0.0.1:" + port, "d.t"}, wrong,
                new ByteArrayOutputStream(), new PrintStream(new ByteArrayOutputStream()), null));
        Run nobody = imp(freePort(), new byte[0], "d.t");
        assertEquals(2, nobody.status());
        assertTrue(nobody.err().contains("cannot reach"), nobody.err());
        assertEquals(2, exp(freePort(), "d.t").status());
        // The file is read from the path given, and - is standard input.
        Path f = dir.resolve("rows.ndjson");
        Files.writeString(f, "{\"_id\":\"from-file\"}\n");
        assertEquals(0, imp("", "d.t", f.toString()).status());
        assertEquals(0, imp("{\"_id\":\"from-stdin\"}", "d.t", "-").status());
        assertEquals(1L, count("d.t WHERE _id = 'from-file'"));
        assertEquals(1L, count("d.t WHERE _id = 'from-stdin'"));
    }

    /**
     * An HTTP server in front of the node that passes each request on, or answers it itself as {@code plan}
     * says for the n-th statement (from 0).
     */
    final class Proxy implements AutoCloseable {
        enum Act { PASS, BUSY, LIMITED, DROP, PASS_AND_DROP }

        final HttpServer server;
        final List<String> statements = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger n = new AtomicInteger();
        volatile BiFunction<Integer, String, Act> plan = (i, s) -> Act.PASS;
        volatile Consumer<String> after = s -> { };

        Proxy() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            server.createContext("/", this::handle);
            server.start();
            closing.add(this);
        }

        int port() {
            return server.getAddress().getPort();
        }

        private void handle(HttpExchange ex) throws IOException {
            byte[] body = ex.getRequestBody().readAllBytes();
            String statement = null;
            Act act = Act.PASS;
            if (ex.getRequestURI().getPath().equals("/v1/query")) {
                statement = (String) Json.parseObject(new String(body, StandardCharsets.UTF_8)).get("query");
                statements.add(statement);
                sizes.add(body.length);
                act = plan.apply(n.getAndIncrement(), statement);
            }
            switch (act) {
                case BUSY -> answer(ex, 503, "{\"error\":\"this node is stopping\"}");
                case LIMITED -> {
                    ex.getResponseHeaders().set("Retry-After", "1");
                    answer(ex, 429, "{\"error\":\"too many failed attempts; try again later\"}");
                }
                case DROP -> ex.close();
                default -> {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ImportExportTest.this.port
                            + ex.getRequestURI())).header("Authorization", ex.getRequestHeaders().getFirst("Authorization"));
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
                        answer(ex, r.statusCode(), r.body());
                    }
                }
            }
            if (statement != null) {
                after.accept(statement);
            }
        }

        private void answer(HttpExchange ex, int code, String text) throws IOException {
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        }

        long inserts() {
            return statements.stream().filter(s -> s.startsWith("INSERT")).count();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void batchesFillUpToTheBodyLimitAndNoFurther() throws Exception {
        start();
        Proxy p = new Proxy();
        // Rows of about 100 kB, of characters that take 1, 2, 4 and 6 bytes in the request ("<" is \u003c).
        StringBuilder in = new StringBuilder();
        String unit = "a<é😀\"\n";
        int rows = 60;
        for (int i = 0; i < rows; i++) {
            in.append(Json.write(Map.of("_id", "r" + i, "s", unit.repeat(5_000 + i), "n", i))).append('\n');
        }
        Run r = imp(p.port(), in.toString().getBytes(StandardCharsets.UTF_8), "d.t");
        assertEquals(0, r.status(), r.err());
        assertEquals(rows, count("d.t"));
        List<Integer> inserts = new ArrayList<>();
        for (int i = 0; i < p.statements.size(); i++) {
            if (p.statements.get(i).startsWith("INSERT")) {
                inserts.add(p.sizes.get(i));
            }
        }
        assertTrue(inserts.size() > 2, inserts.toString());
        for (int size : inserts.subList(0, inserts.size() - 1)) {
            assertTrue(size <= HttpApi.MAX_BODY && size > HttpApi.MAX_BODY - 120_000, "a full batch: " + inserts);
        }
        assertTrue(inserts.get(inserts.size() - 1) <= HttpApi.MAX_BODY);
        assertEquals(List.of(List.of(unit.repeat(5_007))), rows("SELECT s FROM d.t WHERE _id = 'r7'"));

        // --batch counts rows, and a line too large for any request is refused with the lines before it written.
        q("CREATE TABLE d.small");
        p.statements.clear();
        StringBuilder small = new StringBuilder();
        for (int i = 0; i < 2500; i++) {
            small.append("{\"_id\":\"s").append(i).append("\"}\n");
        }
        assertEquals(0, imp(p.port(), small.toString().getBytes(StandardCharsets.UTF_8), "--batch", "1000", "d.small").status());
        assertEquals(3, p.inserts());
        Run big = refused("{\"_id\":\"fits\"}\n" + Json.write(Map.of("_id", "huge", "s", "x".repeat(HttpApi.MAX_BODY))) + "\n",
                2, "a request may have at most 1048576", "d.copy");
        assertTrue(big.out().startsWith("read 1 row: 1 inserted"), big.out());
        // A row just under the limit goes alone; in update mode it must fit with its SET as well.
        String justUnder = Json.write(Map.of("_id", "j", "s", "x".repeat(HttpApi.MAX_BODY - 100)));
        assertEquals(0, imp(justUnder, "d.copy").status());
        refused(justUnder.replace("\"j\"", "\"k\""), 1, "as an INSERT ... ON CONFLICT DO UPDATE", "--on-conflict", "update", "d.copy");
    }

    @Test
    void aBusyNodeOrALostConnectionIsRetriedAndThenGivenUp() throws Exception {
        start();
        Proxy p = new Proxy();
        String in = "{\"_id\":\"a\"}\n{\"_id\":\"b\"}\n{\"_id\":\"c\"}\n";
        // 503, then a connection closed with no answer, then through.
        int[] seen = {0};
        p.plan = (i, s) -> !s.startsWith("INSERT") ? Proxy.Act.PASS
                : switch (seen[0]++) {
                    case 0 -> Proxy.Act.BUSY;
                    case 1 -> Proxy.Act.DROP;
                    default -> Proxy.Act.PASS;
                };
        Run r = imp(p.port(), in.getBytes(StandardCharsets.UTF_8), "d.t");
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().startsWith("read 3 rows: 3 inserted"), r.out());
        assertTrue(r.err().contains("503: this node is stopping; sending it again in 0.5 s (retry 1 of 8)"), r.err());
        assertTrue(r.err().contains("sending it again in 1 s (retry 2 of 8)"), r.err());
        assertEquals(3, p.inserts());

        // The write is made but its answer lost: with skip the retry finds the rows there.
        q("CREATE TABLE d.lost");
        seen[0] = 0;
        p.plan = (i, s) -> s.startsWith("INSERT") && seen[0]++ == 0 ? Proxy.Act.PASS_AND_DROP : Proxy.Act.PASS;
        Run lost = imp(p.port(), in.getBytes(StandardCharsets.UTF_8), "--on-conflict", "skip", "d.lost");
        assertEquals(0, lost.status(), lost.err());
        assertTrue(lost.out().startsWith("read 3 rows: 0 inserted, 0 updated, 3 skipped"), lost.out());
        assertEquals(3, count("d.lost"));
        // Made ids are sent with DO NOTHING, so a retry does not insert a row twice, and they count as inserted.
        seen[0] = 0;
        Run gen = imp(p.port(), "{\"n\":1}\n{\"n\":2}\n".getBytes(StandardCharsets.UTF_8), "--generate-ids", "d.lost");
        assertEquals(0, gen.status(), gen.err());
        assertTrue(gen.out().startsWith("read 2 rows: 2 inserted, 0 updated, 0 skipped"), gen.out());
        assertEquals(5, count("d.lost"));
        // Without skip the retry is refused, and the message says why that may be.
        q("CREATE TABLE d.lost2");
        seen[0] = 0;
        Run fail = imp(p.port(), in.getBytes(StandardCharsets.UTF_8), "d.lost2");
        assertEquals(1, fail.status());
        assertTrue(fail.err().contains("line 1: 400: a row with _id 'a' already exists"), fail.err());
        assertTrue(fail.err().contains("it was sent again after \"cannot reach"), fail.err());
        assertTrue(fail.err().contains("so the first attempt may have written it"), fail.err());

        // A 429 waits as long as Retry-After says.
        seen[0] = 0;
        p.plan = (i, s) -> s.startsWith("INSERT") && seen[0]++ == 0 ? Proxy.Act.LIMITED : Proxy.Act.PASS;
        long t0 = System.nanoTime();
        Run limited = imp(p.port(), "{\"_id\":\"l\"}".getBytes(StandardCharsets.UTF_8), "d.copy");
        assertEquals(0, limited.status(), limited.err());
        assertTrue(System.nanoTime() - t0 >= 1_000_000_000L, "waited a second");
        assertTrue(limited.err().contains("429: too many failed attempts; try again later; sending it again in 1 s"),
                limited.err());

        // Busy throughout: exit 3, the line it stopped at, and the lines before it written.
        p.plan = (i, s) -> s.contains("never") ? Proxy.Act.BUSY : Proxy.Act.PASS;
        Run gaveUp = imp(p.port(), "{\"_id\":\"x1\"}\n{\"_id\":\"never\",\"n\":1}\n".getBytes(StandardCharsets.UTF_8),
                "--retries", "2", "--batch", "1", "d.copy");
        assertEquals(3, gaveUp.status(), gaveUp.err());
        assertTrue(gaveUp.err().contains("line 2: http://127.0.0.1:" + p.port() + " stayed unavailable: 503: this node is"
                + " stopping (sent 3 times)"), gaveUp.err());
        assertTrue(gaveUp.err().contains("the lines before line 2 are written; a rerun with --on-conflict skip"), gaveUp.err());
        assertTrue(gaveUp.out().startsWith("read 2 rows: 1 inserted"), gaveUp.out());
        assertEquals(1L, count("d.copy WHERE _id = 'x1'"));
        // And for export.
        p.plan = (i, s) -> Proxy.Act.BUSY;
        Run exportGaveUp = exp(p.port(), "--retries", "1", "d.copy");
        assertEquals(3, exportGaveUp.status(), exportGaveUp.err());
        assertTrue(exportGaveUp.err().contains("stayed unavailable"), exportGaveUp.err());
    }

    @Test
    void exportReadsKeysetPagesAndARowWrittenMeanwhileIsInIfItComesLater() throws Exception {
        start();
        StringBuilder in = new StringBuilder();
        for (int i = 10; i < 60; i++) {
            in.append("{\"_id\":\"k").append(i).append("\",\"n\":").append(i).append("}\n");
        }
        assertEquals(0, imp(in.toString(), "d.t").status());
        Proxy p = new Proxy();
        int[] pages = {0};
        p.after = s -> {
            if (s.startsWith("SELECT") && ++pages[0] == 2) {
                try {
                    // Written between the second and third page: after the place reached, and before it.
                    q("INSERT INTO d.t (_id, n) VALUES ('k99', 99), ('k00', 0)");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Run r = exp(p.port(), "--page", "7", "d.t");
        assertEquals(0, r.status(), r.err());
        List<Object> got = parseLines(r.out());
        assertEquals(51, got.size());
        assertEquals("k10", ((Map<?, ?>) got.get(0)).get("_id"));
        assertEquals("k99", ((Map<?, ?>) got.get(50)).get("_id"));
        assertFalse(r.out().contains("k00"), "written behind the place reached: not in this export");
        List<String> selects = p.statements.stream().filter(s -> s.startsWith("SELECT")).toList();
        assertEquals(8, selects.size(), selects.toString());
        assertEquals("SELECT * FROM d.t ORDER BY _id LIMIT 7", selects.get(0));
        assertEquals("SELECT * FROM d.t WHERE _id > 'k16' ORDER BY _id LIMIT 7", selects.get(1));
        for (String s : selects) {
            assertFalse(s.contains("OFFSET"), s);
        }

        // --where is read as a condition and sent inside the page's.
        p.statements.clear();
        Run some = exp(p.port(), "--page", "4", "--where", "n >= 20 and N < 30 OR _id = 'k55'", "d.t");
        assertEquals(0, some.status(), some.err());
        assertEquals(11, parseLines(some.out()).size());
        assertEquals("SELECT * FROM d.t WHERE _id > 'k23' AND (((n >= 20 AND n < 30) OR _id = 'k55')) ORDER BY _id LIMIT 4",
                p.statements.get(1));
        for (String bad : List.of("n = 1) OR (n = 2", "n = 1 LIMIT 3", "n = 1 ORDER BY n", "n = 1; DROP TABLE d.t", "", "n =")) {
            Run b = exp("--where", bad, "d.t");
            assertEquals(2, b.status(), bad);
            assertTrue(b.err().startsWith("serengeti export: --where"), b.err());
        }
        assertTrue(exp("--where", "n = = 1", "d.t").err().endsWith("at position 4\n"));
        assertEquals(1L, count("d.t WHERE _id = 'k10'"), "the table is still there");

        // A file: private, renamed once complete; nothing kept when the export fails.
        Path f = dir.resolve("out.ndjson");
        Run toFile = exp("-o", f.toString(), "d.t");
        assertEquals(0, toFile.status(), toFile.err());
        assertTrue(toFile.err().startsWith("serengeti export: 52 rows in " + f), toFile.err());
        assertEquals(r.out().lines().count() + 1, Files.readAllLines(f).size());
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        Path g = dir.resolve("none.ndjson");
        Run missing = exp("-o", g.toString(), "d.nope");
        assertEquals(1, missing.status());
        assertTrue(missing.err().contains("table 'd.nope' does not exist"), missing.err());
        assertTrue(missing.err().contains("nothing was kept"), missing.err());
        assertFalse(Files.exists(g));
        assertFalse(Files.exists(dir.resolve("none.ndjson.tmp")));
        Run csvMissing = exp("--csv", "-o", g.toString(), "d.nope");
        assertEquals(1, csvMissing.status());
        try (var s = Files.list(dir)) {
            assertEquals(List.of(), s.filter(x -> x.getFileName().toString().startsWith("none")).toList());
        }
    }

    @Test
    void aValueNestedSixtyFourDeepIsReadBackAfterARestart() throws Exception {
        start();
        String deep = "{\"a\":".repeat(Parser.MAX_VALUE_DEPTH - 1) + "[\"bottom\"]" + "}".repeat(Parser.MAX_VALUE_DEPTH - 1);
        String line = "{\"_id\":\"deep\",\"v\":" + deep + "}";
        assertEquals(0, imp(line, "d.t").status());
        String before = exp("d.t").out();
        assertEquals(line + "\n", before);
        node.close();
        node = Node.start(Config.load(null, nodeEnv(port)), Node.QUERIES);
        assertEquals(before, exp("d.t").out(), "replayed from the log after a restart");
    }

    @Test
    void tlsWithTheClustersCa() throws Exception {
        TestCerts.ca(dir, "ca");
        Path ks = TestCerts.node(dir, "ca", "node", "dns:localhost,ip:127.0.0.1");
        port = freePort();
        node = Node.start(Config.load(null, nodeEnv(port, "SERENGETI_TLS_KEYSTORE", ks.toString(),
                "SERENGETI_TLS_KEYSTORE_PASSWORD", TestCerts.PASSWORD)), Node.QUERIES);
        String url = "https://127.0.0.1:" + port;
        String ca = dir.resolve("ca-ca.pem").toString();
        Map<String, String> pw = Map.of("SERENGETI_PASSWORD", PW);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Import.run(new String[] {"--url", url, "--ca", ca, "--create", "tls.t"}, pw,
                new ByteArrayInputStream("{\"_id\":\"a\",\"v\":[1]}\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true), new PrintStream(err, true), null);
        assertEquals(0, status, err.toString());
        ByteArrayOutputStream rows = new ByteArrayOutputStream();
        status = Export.run(new String[] {"--url", url, "--ca", ca, "tls.t"}, pw, rows, new PrintStream(err, true), null);
        assertEquals(0, status, err.toString());
        assertEquals("{\"_id\":\"a\",\"v\":[1]}\n", rows.toString(StandardCharsets.UTF_8));
        // Without the CA the node's certificate is not trusted.
        assertEquals(2, Export.run(new String[] {"--url", url, "tls.t"}, pw, new ByteArrayOutputStream(),
                new PrintStream(new ByteArrayOutputStream()), null));
    }

    @Test
    void aHundredThousandRows() throws Exception {
        start();
        int n = 100_000;
        StringBuilder in = new StringBuilder(n * 90);
        for (int i = 0; i < n; i++) {
            in.append(String.format(java.util.Locale.ROOT,
                    "{\"_id\":\"r%06d\",\"n\":%d,\"x\":%s,\"s\":\"row %d, é\",\"ok\":%b}%n", i, i, i / 7.0, i, i % 2 == 0));
        }
        long t0 = System.nanoTime();
        Run r = imp(in.toString(), "d.t");
        long importNanos = System.nanoTime() - t0;
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().startsWith("read 100000 rows: 100000 inserted"), r.out());
        t0 = System.nanoTime();
        Run e = exp("d.t");
        long exportNanos = System.nanoTime() - t0;
        assertEquals(0, e.status(), e.err());
        assertEquals(in.toString(), e.out(), "the same rows, in _id order");
        t0 = System.nanoTime();
        Run c = exp("--csv", "d.t");
        long csvNanos = System.nanoTime() - t0;
        assertEquals(n + 1, c.out().lines().count());
        System.out.printf(java.util.Locale.ROOT, "io2 timing: %d rows (%.1f MB of NDJSON): import %.2f s, export %.2f s,"
                + " export --csv %.2f s%n", n, in.length() / 1e6, importNanos / 1e9, exportNanos / 1e9, csvNanos / 1e9);
    }
}
