package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.query.Executor;
import com.ataiva.serengeti.core.query.Parser;
import com.ataiva.serengeti.core.query.Printer;
import com.ataiva.serengeti.core.query.Query;
import com.ataiva.serengeti.core.query.Statement;
import com.ataiva.serengeti.core.server.Backup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * From review 22l: object and array values in INSERT tuples and SET (c280acd). Random nested values, written by
 * statements, read back from the parser, the answer, the log, the snapshot, a backup restored, the ops a peer is
 * sent (as JSON) and the change feed; the depth bound; and how they group, count and sort, with and without an
 * index on the column.
 */
class ValueLiteralsRoundTripTest {
    @TempDir
    Path dir;

    private static final String[] BITS = {"'", "''", "a", "é", "😀", "\u0000", "\n", "\r", "<", "\\", "\"",
        "{", "}", ":", "[", "]", ",", " ", "--", "￿", " ", "x", "NULL", "TRUE"};

    private static String string(Random r) {
        StringBuilder b = new StringBuilder();
        int n = r.nextInt(5);
        for (int i = 0; i < n; i++) {
            b.append(BITS[r.nextInt(BITS.length)]);
        }
        return b.toString();
    }

    private static Object scalar(Random r) {
        switch (r.nextInt(5)) {
            case 0:
                return null;
            case 1:
                return r.nextBoolean();
            case 2: {
                long[] l = {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, (1L << 53) + 1, -(1L << 53) - 1, r.nextLong(), r.nextInt(100)};
                return l[r.nextInt(l.length)];
            }
            case 3: {
                double x = Double.longBitsToDouble(r.nextLong());
                double[] d = {0.0, -0.0, 1.0, -1.5, 0.1, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE,
                    -Double.MAX_VALUE, 1e21, 1e-7, 1e20, 123456789012345680.0, r.nextDouble() * 1e10, Double.isFinite(x) ? x : 2.5};
                return d[r.nextInt(d.length)];
            }
            default:
                return string(r);
        }
    }

    static Object value(Random r, int depth, int max) {
        int k = depth >= max ? 0 : r.nextInt(4);
        if (k == 0 || k == 1) {
            return scalar(r);
        }
        if (k == 2) {
            List<Object> a = new ArrayList<>();
            int n = r.nextInt(4);
            for (int i = 0; i < n; i++) {
                a.add(value(r, depth + 1, max));
            }
            return a;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        int n = r.nextInt(4);
        for (int i = 0; i < n; i++) {
            m.put(string(r), value(r, depth + 1, max));
        }
        return m;
    }

    /** A value with exactly {@code depth} objects and arrays nested, alternating. */
    static Object deep(int depth) {
        Object v = "leaf";
        for (int i = 0; i < depth; i++) {
            if (i % 2 == 0) {
                v = new ArrayList<>(List.of(v));
            } else {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("k" + i, v);
                v = m;
            }
        }
        return v;
    }

    private static String text(Object v) {
        return Printer.value(v);
    }

    @Test
    void valuesRoundTripEverywhere() throws Exception {
        Random r = new Random(22);
        int n = 1500;
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            values.add(value(r, 0, i % 50 == 0 ? 64 : 6));
        }
        values.set(1, deep(64));
        values.set(2, deep(63));
        List<String> wrong = new ArrayList<>();
        // The parser: Printer.value reads back as the same value, keys in order, types kept.
        for (int i = 0; i < n; i++) {
            Object v = values.get(i);
            Statement s = Parser.parse("INSERT INTO d.t (_id, v) VALUES ('r', " + text(v) + ")");
            Object back = ((Statement.Insert) s).rows().get(0).get(1);
            if (!Json.write(v).equals(Json.write(back))) {
                wrong.add("parse " + i + ": " + Json.write(v) + " came back " + Json.write(back));
            }
            Statement again = Parser.parse(Printer.print(s));
            if (!Json.write(((Statement.Insert) again).rows().get(0).get(1)).equals(Json.write(v))) {
                wrong.add("Printer.print " + i);
            }
        }
        // Statements write them: INSERT, SET, and ON CONFLICT DO UPDATE SET.
        Path d1 = dir.resolve("n1");
        Map<String, List<Object>> expected = new java.util.TreeMap<>();
        StorageEngine e = StorageEngine.open(d1, FsyncPolicy.OFF, 64L << 20, 0, 100_000);
        Query q = new Query(new Executor(e, new Hlc("n1"), 10_000));
        q.run("CREATE DATABASE d");
        q.run("CREATE TABLE d.t");
        q.run("CREATE INDEX ON d.t (v)");
        for (int i = 0; i < n; i++) {
            String id = String.format("r%05d", i);
            Object v = values.get(i);
            q.run("INSERT INTO d.t (_id, v) VALUES ('" + id + "', " + text(v) + ")");
            expected.put(id, new ArrayList<>(Arrays.asList(id, v, null, null)));
        }
        for (int i = 0; i < n; i += 3) {
            String id = String.format("r%05d", i);
            Object w = values.get((i * 7 + 1) % n);
            q.run("UPDATE d.t SET w = " + text(w) + " WHERE _id = '" + id + "'");
            expected.get(id).set(2, w);
        }
        for (int i = 1; i < n; i += 5) {
            String id = String.format("r%05d", i);
            Object u = values.get((i * 11 + 2) % n);
            q.run("INSERT INTO d.t (_id, v) VALUES ('" + id + "', 0) ON CONFLICT DO UPDATE SET u = " + text(u));
            expected.get(id).set(3, u);
        }
        // One SET value in many rows.
        Object shared = values.get(1);
        q.run("UPDATE d.t SET s = " + text(shared) + " WHERE _id >= 'r01000' AND _id < 'r01100'");
        String want = Json.write(new ArrayList<>(expected.values()));
        check(wrong, "the answer", q, want, shared);

        // The change feed holds the rows as written.
        ChangeFeed feed = e.changes();
        Map<String, String> feedRows = feedRows(feed);
        compareFeed(wrong, "the change feed", feedRows, expected, shared);

        // The log, read again.
        e.close();
        e = StorageEngine.open(d1, FsyncPolicy.OFF, 64L << 20, 0, 100_000);
        q = new Query(new Executor(e, new Hlc("n1"), 10_000));
        check(wrong, "the log after a restart", q, want, shared);
        compareFeed(wrong, "the change feed after a restart", feedRows(e.changes()), expected, shared);
        // The snapshot.
        e.checkpoint();
        e.close();
        e = StorageEngine.open(d1, FsyncPolicy.OFF, 64L << 20, 0, 100_000);
        q = new Query(new Executor(e, new Hlc("n1"), 10_000));
        check(wrong, "the snapshot after a restart", q, want, shared);
        // A backup, restored.
        Path b = dir.resolve("backup.jsonl");
        e.writeBackup(b);
        Path d2 = dir.resolve("n2");
        Files.createDirectories(d2);
        Backup.restore(b, d2);
        // What a peer is sent: the ops, as JSON, read and merged on another node.
        String sent = Json.write(Map.of("ops", Op.toJson(e.snapshotOps())));
        e.close();
        try (StorageEngine e2 = StorageEngine.open(d2, FsyncPolicy.OFF)) {
            check(wrong, "a backup restored", new Query(new Executor(e2, new Hlc("n2"), 10_000)), want, shared);
        }
        Path d3 = dir.resolve("n3");
        try (StorageEngine e3 = StorageEngine.open(d3, FsyncPolicy.OFF)) {
            e3.merge(Op.listFromJson(Json.parseObject(sent).get("ops")));
            check(wrong, "the ops a peer is sent, merged", new Query(new Executor(e3, new Hlc("n3"), 10_000)), want, shared);
        }
        System.out.println("values: " + n + " values, " + wrong.size() + " wrong");
        assertEquals(List.of(), wrong, String.join("\n", wrong.subList(0, Math.min(20, wrong.size()))));
    }

    private static void check(List<String> wrong, String where, Query q, String want, Object shared) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) q.run("SELECT _id, v, w, u FROM d.t ORDER BY _id").toJson().get("rows");
        String got = Json.write(rows);
        if (!got.equals(want)) {
            List<?> w = (List<?>) Json.parse(want);
            for (int i = 0; i < Math.min(w.size(), rows.size()); i++) {
                if (!Json.write(w.get(i)).equals(Json.write(rows.get(i)))) {
                    wrong.add(where + ": " + Json.write(w.get(i)) + " came back " + Json.write(rows.get(i)));
                    break;
                }
            }
            if (w.size() != rows.size()) {
                wrong.add(where + ": " + rows.size() + " rows, " + w.size() + " expected");
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> s = (List<Object>) q.run("SELECT s FROM d.t WHERE s IS NOT NULL").toJson().get("rows");
        for (Object row : s) {
            if (!Json.write(((List<?>) row).get(0)).equals(Json.write(shared))) {
                wrong.add(where + ": the shared SET value changed: " + Json.write(row));
                break;
            }
        }
        if (s.size() != 100) {
            wrong.add(where + ": " + s.size() + " rows with the shared value");
        }
    }

    private static Map<String, String> feedRows(ChangeFeed feed) throws Exception {
        Map<String, String> last = new LinkedHashMap<>();
        String cursor = feed.epoch() + ":0";
        for (;;) {
            ChangeFeed.Page p;
            try {
                p = feed.read(cursor, "d", "t", 1000, 0);
            } catch (ChangeFeed.Gone g) {
                last.put("GONE", g.getMessage());
                return last;
            }
            if (p.changes().isEmpty()) {
                return last;
            }
            for (ChangeFeed.Change c : p.changes()) {
                if (c.id() != null) {
                    last.put(c.id(), c.row() == null ? null : Json.write(c.row()));
                }
            }
            cursor = p.cursor();
        }
    }

    private static void compareFeed(List<String> wrong, String where, Map<String, String> feed,
            Map<String, List<Object>> expected, Object shared) {
        if (feed.containsKey("GONE")) {
            System.out.println("" + where + ": " + feed.get("GONE"));
            return;
        }
        int compared = 0;
        for (Map.Entry<String, List<Object>> x : expected.entrySet()) {
            String got = feed.get(x.getKey());
            if (got == null) {
                continue;
            }
            Map<?, ?> row = (Map<?, ?>) Json.parse(got);
            List<Object> e = x.getValue();
            compared++;
            for (int c = 1; c <= 3; c++) {
                String col = new String[] {"_id", "v", "w", "u"}[c];
                if (e.get(c) != null || row.containsKey(col)) {
                    if (!Json.write(e.get(c)).equals(Json.write(row.get(col)))) {
                        wrong.add(where + ": " + x.getKey() + "." + col + " " + Json.write(e.get(c)) + " came back "
                                + Json.write(row.get(col)));
                    }
                }
            }
        }
        System.out.println("" + where + ": " + compared + " rows compared");
    }

    @Test
    void theDepthBound() throws Exception {
        List<String> wrong = new ArrayList<>();
        try (StorageEngine e = StorageEngine.open(dir.resolve("deep"), FsyncPolicy.OFF)) {
            Query q = new Query(new Executor(e, new Hlc("n1"), 10_000));
            q.run("CREATE DATABASE d");
            q.run("CREATE TABLE d.t");
            String[] ok = {"INSERT INTO d.t (_id, v) VALUES ('a', %s)", "UPDATE d.t SET w = %s WHERE _id = 'a'",
                "INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO UPDATE SET u = %s"};
            for (String f : ok) {
                for (int depth : new int[] {64, 65, 200}) {
                    String s = String.format(f, text64(depth));
                    String outcome;
                    try {
                        q.run(s);
                        outcome = "written";
                    } catch (RuntimeException x) {
                        outcome = x.getClass().getSimpleName() + ": " + x.getMessage();
                    }
                    boolean shouldWrite = depth <= 64;
                    if (shouldWrite != outcome.equals("written")) {
                        wrong.add(depth + " deep, " + f + ": " + outcome);
                    }
                }
            }
            // 64 deep inside the ops a peer is sent, and a log line: readable.
            String sent = Json.write(Map.of("ops", Op.toJson(e.snapshotOps())));
            Op.listFromJson(Json.parseObject(sent).get("ops"));
        }
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    @Test
    void valuesNoReaderCouldTakeAreRefused() throws Exception {
        List<String> wrong = new ArrayList<>();
        try (StorageEngine e = StorageEngine.open(dir.resolve("refused"), FsyncPolicy.OFF)) {
            Query q = new Query(new Executor(e, new Hlc("n1"), 10_000));
            q.run("CREATE DATABASE d");
            q.run("CREATE TABLE d.t");
            q.run("INSERT INTO d.t (_id, v) VALUES ('a', 1)");
            for (String s : List.of(
                    "INSERT INTO d.t (_id, v) VALUES ('b', {'k': 1, 'k': 2})",
                    "INSERT INTO d.t (_id, v) VALUES ('b', {'k': {'x': 1, 'x': [2]}})",
                    "INSERT INTO d.t (_id, v) VALUES ('b', {1: 2})",
                    "INSERT INTO d.t (_id, v) VALUES ('b', {k: 2})",
                    "INSERT INTO d.t (_id, v) VALUES ('b', {NULL: 2})",
                    "INSERT INTO d.t (_id, v) VALUES ({'a': 1}, 1)",
                    "INSERT INTO d.t (_id, v) VALUES (['a'], 1)",
                    "INSERT INTO d.t (v) VALUES ({'_id': 'x'}) ON CONFLICT DO UPDATE SET _id = ['y']",
                    "UPDATE d.t SET _id = {'a': 1} WHERE _id = 'a'",
                    "SELECT * FROM d.t WHERE v = {'d': 6}",
                    "SELECT * FROM d.t WHERE v IN ([1], 2)",
                    "INSERT INTO d.t (_id, v) VALUES ('b', [1,])",
                    "INSERT INTO d.t (_id, v) VALUES ('b', {'a' 1})",
                    "UPDATE d.t SET v = v + {'a': 1}")) {
                try {
                    q.run(s);
                    wrong.add("taken: " + s);
                } catch (RuntimeException x) {
                    System.out.println("refused: " + s + " -> " + x.getMessage());
                }
            }
            // Keys differing only in case, the empty key and _id inside an object are values like any other.
            q.run("INSERT INTO d.t (_id, v) VALUES ('c', {'k': 1, 'K': 2, '': 3, '_id': 'x'})");
            String got = Json.write(q.run("SELECT v FROM d.t WHERE _id = 'c'").toJson().get("rows"));
            if (!got.equals("[[{\"k\":1,\"K\":2,\"\":3,\"_id\":\"x\"}]]")) {
                wrong.add("keys: " + got);
            }
        }
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    /** {@code depth} arrays and objects nested, as query text (not through Printer, which refuses past 64). */
    private static String text64(int depth) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            b.append(i % 2 == 0 ? "[" : "{'k': ");
        }
        b.append("1");
        for (int i = depth - 1; i >= 0; i--) {
            b.append(i % 2 == 0 ? "]" : "}");
        }
        return b.toString();
    }

    @Test
    void groupsCountsAndOrderAsQueryMdSays() throws Exception {
        try (StorageEngine e = StorageEngine.open(dir.resolve("g"), FsyncPolicy.OFF)) {
            Query q = new Query(new Executor(e, new Hlc("n1"), 10_000));
            q.run("CREATE DATABASE d");
            q.run("CREATE TABLE d.g");
            q.run("CREATE TABLE d.plain");
            q.run("CREATE INDEX ON d.g (v)");
            String values = "('a', {'x': 1, 'y': 2}), ('b', {'y': 2, 'x': 1}), ('c', [1]), ('d', [1.0]), ('e', [0.0]),"
                    + " ('f', [-0.0]), ('g', [0]), ('h', 'str'), ('i', 5), ('j', NULL), ('k', TRUE), ('l', {}), ('m', []),"
                    + " ('n', {'x': 1.0, 'y': 2}), ('o', 5.0), ('p', -0.0), ('q', 0)";
            q.run("INSERT INTO d.g (_id, v) VALUES " + values);
            q.run("INSERT INTO d.plain (_id, v) VALUES " + values);
            for (String s : List.of("SELECT v, COUNT(*) FROM d.g GROUP BY v", "SELECT COUNT(DISTINCT v) FROM d.g",
                    "SELECT DISTINCT v FROM d.g", "SELECT MIN(v), MAX(v) FROM d.g", "SELECT _id FROM d.g ORDER BY v",
                    "SELECT _id FROM d.g ORDER BY v DESC")) {
                System.out.println("semantics: " + s + " -> " + Json.write(q.run(s).toJson().get("rows")));
            }
            // An index on v, against the same rows without one.
            Random r = new Random(5);
            StringBuilder ins = new StringBuilder();
            for (int i = 0; i < 3000; i++) {
                ins.append(i % 500 == 0 ? "" : ", ");
                ins.append("('z").append(String.format("%05d", (i * 7919) % 3000)).append("', ").append(text(value(r, 0, 2)))
                        .append(")");
                if (i % 500 == 499) {
                    q.run("INSERT INTO d.g (_id, v) VALUES " + ins);
                    q.run("INSERT INTO d.plain (_id, v) VALUES " + ins);
                    ins.setLength(0);
                }
            }
            List<String> wrong = new ArrayList<>();
            int compared = 0;
            for (String order : List.of("v", "v DESC", "v, _id DESC", "v DESC, _id DESC")) {
                for (String where : List.of("", " WHERE v IS NOT NULL", " WHERE _id > 'z01000'", " WHERE v != 'zz'")) {
                    for (String limit : List.of(" LIMIT 1", " LIMIT 17", " LIMIT 500", " LIMIT 3500", " LIMIT 20 OFFSET 2990", "")) {
                        String a = "SELECT _id FROM d.g" + where + " ORDER BY " + order + limit;
                        String b = "SELECT _id FROM d.plain" + where + " ORDER BY " + order + limit;
                        compared++;
                        String ga = Json.write(q.run(a).toJson().get("rows"));
                        String gb = Json.write(q.run(b).toJson().get("rows"));
                        if (!ga.equals(gb)) {
                            wrong.add(a + ": differs from the table without the index");
                        }
                    }
                }
            }
            for (String s : List.of("SELECT v, COUNT(*) FROM %s GROUP BY v", "SELECT COUNT(DISTINCT v) FROM %s",
                    "SELECT MIN(v), MAX(v) FROM %s", "SELECT DISTINCT v FROM %s ORDER BY v DESC LIMIT 50")) {
                compared++;
                if (!Json.write(q.run(String.format(s, "d.g")).toJson()).equals(Json.write(q.run(String.format(s, "d.plain")).toJson()))) {
                    wrong.add(s + ": differs with the index");
                }
            }
            System.out.println("index vs no index: " + compared + " compared, " + wrong.size() + " wrong");
            assertEquals(List.of(), wrong, String.join("\n", wrong));
        }
    }
}
