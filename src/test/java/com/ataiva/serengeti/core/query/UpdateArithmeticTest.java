package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.ChangeFeed;
import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code UPDATE ... SET c = column + number}: the arithmetic, and what the executor does with it. */
class UpdateArithmeticTest {
    private static final long P53 = 1L << 53;

    @TempDir
    Path dir;

    private StorageEngine engine;
    private Query query;

    @BeforeEach
    void open() throws IOException {
        engine = StorageEngine.open(dir, FsyncPolicy.ALWAYS, 64L * 1024 * 1024, 0, 100);
        query = new Query(engine, new Hlc("n1"));
    }

    @AfterEach
    void close() throws IOException {
        engine.close();
    }

    private void reopen(boolean checkpoint) throws IOException {
        if (checkpoint) {
            engine.checkpoint();
        }
        engine.close();
        engine = StorageEngine.open(dir, FsyncPolicy.ALWAYS, 64L * 1024 * 1024, 0, 100);
        query = new Query(engine, new Hlc("n1"));
    }

    private Result run(String q) throws IOException {
        return query.run(q);
    }

    private long affected(String q) throws IOException {
        return ((Result.Write) run(q)).affected();
    }

    private String fails(String q) {
        QueryException e = assertThrows(QueryException.class, () -> run(q));
        assertTrue(!(e instanceof ParseException), e.getMessage());
        return e.getMessage();
    }

    /** The value of {@code column} in row {@code id} of d.t. */
    private Object value(String id, String column) throws IOException {
        List<List<Object>> rows = ((Result.Rows) run("SELECT " + column + " FROM d.t WHERE _id = '" + id + "'")).rows();
        assertEquals(1, rows.size(), id);
        return rows.get(0).get(0);
    }

    private void table() throws IOException {
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
    }

    /** Writes rows with any values (objects, arrays) directly, as a peer's merge would. */
    private void put(String id, Map<String, Object> row) throws IOException {
        engine.apply(List.of(new Op.Put("d", "t", id, row, new Stamp(System.currentTimeMillis(), 0, "n0"))));
    }

    private static Object add(Object a, Object b) {
        return Values.add(a, false, b);
    }

    private static Object sub(Object a, Object b) {
        return Values.add(a, true, b);
    }

    private static String overflow(Object a, boolean minus, Object b) {
        return assertThrows(ArithmeticException.class, () -> Values.add(a, minus, b)).getMessage();
    }

    @Test
    void integersStayIntegersAndAnOverflowIsAnError() {
        assertEquals(5L, add(2L, 3L));
        assertEquals(-1L, sub(2L, 3L));
        assertEquals(Long.MAX_VALUE, add(Long.MAX_VALUE - 1, 1L));
        assertEquals(Long.MIN_VALUE, sub(Long.MIN_VALUE + 1, 1L));
        assertEquals(Long.MIN_VALUE, add(Long.MIN_VALUE + 1, -1L));
        assertEquals(-1L, add(Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(0L, sub(Long.MIN_VALUE, Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, sub(-1L, Long.MIN_VALUE));
        assertEquals(0L, add(0L, 0L));
        for (Object[] c : new Object[][] {{Long.MAX_VALUE, false, 1L}, {Long.MAX_VALUE, true, -1L},
                {Long.MIN_VALUE, true, 1L}, {Long.MIN_VALUE, false, -1L}, {Long.MAX_VALUE, false, Long.MAX_VALUE},
                {Long.MIN_VALUE, false, Long.MIN_VALUE}, {0L, true, Long.MIN_VALUE}, {Long.MAX_VALUE, true, Long.MIN_VALUE}}) {
            assertEquals("overflows a 64-bit integer", overflow(c[0], (Boolean) c[1], c[2]), Arrays.toString(c));
        }
        // Rows built in Java may hold the smaller boxes; they count as integers.
        assertEquals(6L, add(5, 1L));
        assertEquals(6L, add((short) 5, 1L));
        assertEquals(6L, add((byte) 5, 1L));
    }

    @Test
    void aDoubleOnEitherSideMakesADouble() {
        assertEquals(1.5, add(1L, 0.5));
        assertEquals(1.5, add(0.5, 1L));
        assertEquals(6.0, add(5L, 1.0), "a whole double is still a double");
        assertEquals(6.0, add(5.0, 1L));
        assertEquals(0.30000000000000004, add(0.1, 0.2));
        assertEquals(-0.5, sub(0.5, 1L));
        assertEquals(Double.MAX_VALUE, add(Double.MAX_VALUE, 1L), "rounds back to the largest double");
        assertEquals(Double.MAX_VALUE, add(Double.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(1e308 + 7e307, add(1e308, 7e307));
        assertEquals("overflows a double", overflow(1e308, false, 1e308));
        assertEquals("overflows a double", overflow(-1e308, true, 1e308));
        assertEquals("overflows a double", overflow(Double.MAX_VALUE, false, Double.MAX_VALUE));
        assertEquals(4.9e-324, add(0.0, 4.9e-324));
        assertEquals(0x1p63, add(Long.MAX_VALUE, 0.0), "the nearest double to 2^63 - 1 is 2^63; no integer overflow");
        assertEquals(-0x1p63, sub(Long.MIN_VALUE, 0.5), "-2^63 - 0.5 is nearest -2^63");
        // Float, which a row built in Java may hold, counts as a double.
        assertEquals(1.5, add(1.0f, 0.5));
    }

    @Test
    void anIntegerBeyond2To53IsRoundedOnceWithTheDouble() {
        // Exactly 2^53 + 1 + 0.5 = 9007199254740993.5, nearest 9007199254740994. Converting 2^53 + 1 to a double
        // first would give 2^53, and then 2^53 + 0.5 rounds (to even) to 2^53: a number off by two.
        assertEquals(9007199254740994.0, add(P53 + 1, 0.5));
        assertEquals(9007199254740994.0, add(0.5, P53 + 1));
        assertEquals(9007199254740992.0, add(P53 + 1, 0.0), "a tie goes to even");
        assertEquals(9007199254740992.0, sub(P53 + 1, 0.5), "9007199254740992.5 is nearest ...992");
        assertEquals(9007199254740994.0, sub(P53 + 3, 0.5), "9007199254740994.5 is nearest ...994");
        assertEquals(9007199254740994.0, add(P53 + 3, -1.0), "...994 exactly");
        assertEquals(9007199254740996.0, add(P53 + 3, 0.0), "...995 is a tie: even is ...996");
        assertEquals(9007199254740996.0, add(1.0, P53 + 2), "...995 again, from the other side");
        assertEquals(1.0, sub(P53 + 1, 9007199254740992.0), "2^53 + 1 - 2^53 is exactly 1");
        assertEquals(-1.0, sub(9007199254740992.0, P53 + 1));
        assertEquals(-1.0, sub(Long.MAX_VALUE, 0x1p63), "2^63 - 1 - 2^63; converting first would give 0.0");
        assertEquals(2048.0, add(Long.MIN_VALUE, 0x1p63 + 2048.0));
        // The neighbours of 2^53 as integers stay exact.
        assertEquals(P53 + 1, add(P53, 1L));
        assertEquals(P53 - 1, sub(P53, 1L));
        assertEquals(P53 + 2, add(P53 + 1, 1L));
        assertEquals(-P53 - 1, sub(-P53, 1L));
    }

    @Test
    void zeroKeepsTheSignOfDoubleArithmetic() {
        assertEquals(-0.0, add(-0.0, -0.0));
        assertEquals(-0.0, sub(-0.0, 0.0));
        assertEquals(0.0, add(-0.0, 0.0));
        assertEquals(0.0, sub(0.0, 0.0));
        assertEquals(0.0, sub(-0.0, -0.0));
        assertEquals(0.0, add(-0.0, 0L), "-0.0 + 0 is 0.0");
        assertEquals(-0.0, sub(-0.0, 0L), "-0.0 - 0 is -0.0");
        assertEquals(0.0, add(0L, -0.0));
        assertEquals(0.0, sub(0L, 0.0));
        assertEquals(0.0, sub(0L, -0.0));
        assertEquals(0.0, add(5L, -5.0));
        assertEquals(0.0, sub(1L << 60, 0x1p60), "an exact zero beyond 2^53");
        assertEquals(0.0, add(Long.MIN_VALUE, 0x1p63));
        assertEquals(5.0, add(5L, -0.0));
        assertEquals(-5.0, sub(-0.0, 5L));
    }

    @Test
    void aGuardStopsACounterAtZero() throws IOException {
        table();
        run("INSERT INTO d.t (_id, stock) VALUES ('a', 2), ('b', 7)");
        String take = "UPDATE d.t SET stock = stock - 1 WHERE _id = 'a' AND stock > 0";
        assertEquals(1, affected(take));
        assertEquals(1, affected(take));
        assertEquals(0, affected(take));
        assertEquals(0L, value("a", "stock"));
        assertEquals(7L, value("b", "stock"));
        assertEquals(2, affected("UPDATE d.t SET stock = stock + 10"));
        assertEquals(List.of(List.of(10L), List.of(17L)), ((Result.Rows) run("SELECT stock FROM d.t")).rows());
    }

    @Test
    void everyTypeOfTheColumn() throws IOException {
        table();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("i", 5L);
        row.put("f", 2.5);
        row.put("z", -0.0);
        row.put("s", "5");
        row.put("b", true);
        row.put("o", Map.of("x", 1L));
        row.put("l", List.of(1L));
        row.put("nul", null);
        put("r", row);
        String[][] good = {{"i + 1", "6"}, {"i - 1.5", "3.5"}, {"i + 0.0", "5.0"}, {"f + 1", "3.5"}, {"f - 2.5", "0.0"},
                {"z + 0", "0.0"}, {"z - 0", "-0.0"}, {"z - 0.0", "-0.0"}, {"i - -9223372036854775802", "9223372036854775807"}};
        for (String[] g : good) {
            assertEquals(1, affected("UPDATE d.t SET out = " + g[0]));
            Object got = value("r", "out");
            assertEquals(g[1], String.valueOf(got), g[0]);
            assertEquals(g[1].contains(".") ? Double.class : Long.class, got.getClass(), g[0]);
        }
        assertEquals("cannot set out = i - -9223372036854775803 in the row with _id 'r': the result overflows a 64-bit"
                + " integer", fails("UPDATE d.t SET out = i - -9223372036854775803"));
        assertEquals("cannot set out = s + 1 in the row with _id 'r': s is a string", fails("UPDATE d.t SET out = s + 1"));
        assertEquals("cannot set b = b - 1 in the row with _id 'r': b is a boolean", fails("UPDATE d.t SET b = b - 1"));
        assertEquals("cannot set out = o + 1 in the row with _id 'r': o is an object", fails("UPDATE d.t SET out = o + 1"));
        assertEquals("cannot set out = l + 1 in the row with _id 'r': l is an array", fails("UPDATE d.t SET out = l + 1"));
        assertEquals(1, affected("UPDATE d.t SET f2 = 1.7E308"));
        assertEquals("cannot set out = f2 + 1.0E308 in the row with _id 'r': the result overflows a double",
                fails("UPDATE d.t SET out = f2 + 1e308"));
        assertEquals("cannot set out = f2 - -1.0E308 in the row with _id 'r': the result overflows a double",
                fails("UPDATE d.t SET out = f2 - -1e308"));
        assertEquals(9223372036854775807L, value("r", "out"), "the failed statements left out as it was");
        // Null and missing give null, as in SQL, and SET writes it.
        assertEquals(1, affected("UPDATE d.t SET out = nul + 1, nul = nul - 1, gone = gone + 1"));
        assertEquals(Arrays.asList(null, null, null), ((Result.Rows) run("SELECT out, nul, gone FROM d.t")).rows().get(0));
        assertEquals(List.of(List.of(1L)), ((Result.Rows) run("SELECT COUNT(*) FROM d.t WHERE gone IS NULL")).rows());
        assertTrue(((Result.Rows) run("SELECT * FROM d.t")).columns().contains("gone"), "the null is stored");
    }

    @Test
    void everyRightSideReadsTheRowAsItWasBefore() throws IOException {
        table();
        run("INSERT INTO d.t (_id, a, b, price) VALUES ('x', 1, 10, 4.5)");
        assertEquals(1, affected("UPDATE d.t SET a = b + 1, b = a + 1, total = price + 5"));
        assertEquals(List.of(List.of(11L, 2L, 9.5)), ((Result.Rows) run("SELECT a, b, total FROM d.t")).rows());
        assertEquals(1, affected("UPDATE d.t SET a = a + 1, c = a + 100"));
        assertEquals(List.of(List.of(12L, 111L)), ((Result.Rows) run("SELECT a, c FROM d.t")).rows());
    }

    @Test
    void aStatementIsAllOrNothing() throws IOException {
        table();
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            rows.append(i == 0 ? "" : ", ").append("('r").append(String.format("%04d", i)).append("', ")
                    .append(i == 500 ? "'five hundred'" : i == 700 ? "9223372036854775807" : Integer.toString(i))
                    .append(")");
        }
        run("INSERT INTO d.t (_id, n) VALUES " + rows);
        String start = engine.changes().now();
        String before = String.valueOf(((Result.Rows) run("SELECT * FROM d.t")).rows());
        // Rows are read in _id order, so the first that cannot be computed is named.
        assertEquals("cannot set n = n + 1 in the row with _id 'r0500': n is a string", fails("UPDATE d.t SET n = n + 1"));
        assertEquals("cannot set n = n + 1 in the row with _id 'r0700': the result overflows a 64-bit integer",
                fails("UPDATE d.t SET n = n + 1 WHERE _id > 'r0500'"));
        assertEquals("cannot set m = n - 1.5 in the row with _id 'r0500': n is a string",
                fails("UPDATE d.t SET m = n - 1.5, n = n + 1 WHERE _id >= 'r0500'"));
        assertEquals(before, String.valueOf(((Result.Rows) run("SELECT * FROM d.t")).rows()), "nothing was written");
        assertEquals(List.of(), feed(start), "nothing reached the change feed");
        reopen(false);
        assertEquals(before, String.valueOf(((Result.Rows) run("SELECT * FROM d.t")).rows()), "nor the log");
        // Without the rows that cannot be computed, the statement runs.
        assertEquals(998, affected("UPDATE d.t SET n = n + 1 WHERE _id != 'r0500' AND _id != 'r0700'"));
        assertEquals(List.of(List.of(1000L * 999 / 2 - 500 - 700 + 998)),
                ((Result.Rows) run("SELECT SUM(n) FROM d.t WHERE _id != 'r0500' AND _id != 'r0700'")).rows());
    }

    private List<String> feed(String after) throws IOException {
        try {
            List<String> out = new ArrayList<>();
            for (ChangeFeed.Change c : engine.changes().read(after, "d", null, 100, 0).changes()) {
                out.add(c.kind() + " " + c.id() + " " + c.row());
            }
            return out;
        } catch (ChangeFeed.Gone | InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theChangeFeedListsTheNewRow() throws IOException {
        table();
        run("INSERT INTO d.t (_id, n, s) VALUES ('a', 1, 'x'), ('b', 2, 'y')");
        String start = engine.changes().now();
        assertEquals(1, affected("UPDATE d.t SET n = n + 41 WHERE _id = 'b'"));
        assertEquals(List.of("row b {n=43, s=y}"), feed(start));
    }

    @Test
    void anIndexOnTheColumnFollowsTheNewValue() throws IOException {
        table();
        run("INSERT INTO d.t (_id, qty, kind) VALUES ('a', 5, 1), ('b', 5, 2), ('c', 6, 1)");
        run("CREATE INDEX ON d.t (qty)");
        run("CREATE INDEX ON d.t (kind, qty)");
        assertEquals(2, affected("UPDATE d.t SET qty = qty - 1 WHERE qty = 5"));
        assertEquals(List.of(), ids("qty = 5"));
        assertEquals(List.of("a", "b"), ids("qty = 4"));
        assertEquals(List.of("a"), ids("kind = 1 AND qty = 4"));
        assertEquals(List.of("c"), ids("kind = 1 AND qty > 4"));
        assertEquals(1, affected("UPDATE d.t SET qty = qty + 0.5 WHERE kind = 1 AND qty = 6"));
        assertEquals(List.of("c"), ids("qty = 6.5"));
        assertEquals(List.of("a", "b", "c"), ids("qty >= 4"));
        // The index and a scan agree.
        for (String w : List.of("qty = 4", "qty > 4", "qty <= 6.5", "kind = 1 AND qty >= 4")) {
            assertEquals(ids("(" + w + ") OR (" + w + ")"), ids(w), w);
        }
    }

    private List<Object> ids(String where) throws IOException {
        List<Object> out = new ArrayList<>();
        for (List<Object> r : ((Result.Rows) run("SELECT _id FROM d.t WHERE " + where)).rows()) {
            out.add(r.get(0));
        }
        return out;
    }

    @Test
    void aWhereOnTheChangedColumnChangesEachRowOnce() throws IOException {
        table();
        run("CREATE TABLE d.u");
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            rows.append(i == 1 ? "" : ", ").append("('r").append(String.format("%03d", i)).append("', ").append(i).append(")");
        }
        run("INSERT INTO d.t (_id, a) VALUES " + rows);
        run("INSERT INTO d.u (_id, a) VALUES " + rows);
        run("CREATE INDEX ON d.t (a)");
        // Through the index on d.t, by scan on d.u: every row moves up past rows still to be read.
        for (String t : List.of("d.t", "d.u")) {
            assertEquals(300, affected("UPDATE " + t + " SET a = a + 1 WHERE a > 0"), t);
            assertEquals(List.of(List.of(300L, 2L, 301L, 300L * 301 / 2 + 300)),
                    ((Result.Rows) run("SELECT COUNT(*), MIN(a), MAX(a), SUM(a) FROM " + t)).rows(), t);
            assertEquals(150, affected("UPDATE " + t + " SET a = a + 1000 WHERE a >= 152"), t);
            assertEquals(List.of(List.of(150L)),
                    ((Result.Rows) run("SELECT COUNT(*) FROM " + t + " WHERE a >= 1152")).rows(), t);
            assertEquals(List.of(List.of(150L)),
                    ((Result.Rows) run("SELECT COUNT(*) FROM " + t + " WHERE a < 152")).rows(), t);
        }
    }

    @Test
    void numbersKeepTheirTypeThroughTheLogAndTheSnapshot() throws IOException {
        table();
        run("INSERT INTO d.t (_id, big, z, z2, low, d, m) VALUES ('a', 9007199254740992, -0.0, -0.0, "
                + "-9223372036854775807, 0.5, 9007199254740993)");
        assertEquals(1, affected("UPDATE d.t SET big = big + 1, z = z - 0.0, z2 = z2 + 0, low = low - 1, d = d + 1,"
                + " m = m + 0.5"));
        assertEquals("cannot set top = big + 9223372036854775806 in the row with _id 'a': the result overflows a"
                + " 64-bit integer", fails("UPDATE d.t SET top = big + 9223372036854775806"));
        assertEquals(1, affected("UPDATE d.t SET top = low + 9223372036854775807"));
        // Each value, and its JSON text (as the log, the snapshot and the HTTP answers write it).
        Map<String, Object> want = new LinkedHashMap<>();
        want.put("big", P53 + 1);
        want.put("z", -0.0);
        want.put("z2", 0.0);
        want.put("low", Long.MIN_VALUE);
        want.put("d", 1.5);
        want.put("m", 9007199254740994.0);
        want.put("top", -1L);
        List<String> json = List.of("9007199254740993", "-0.0", "0.0", "-9223372036854775808", "1.5",
                "9.007199254740994E15", "-1");
        for (String stage : List.of("as written", "from the log", "from the snapshot", "from the snapshot again")) {
            int i = 0;
            for (Map.Entry<String, Object> e : want.entrySet()) {
                Object got = value("a", e.getKey());
                assertEquals(e.getValue(), got, stage + ": " + e.getKey());
                assertEquals(e.getValue().getClass(), got.getClass(), stage + ": " + e.getKey());
                assertEquals(json.get(i++), com.ataiva.serengeti.core.json.Json.write(got), stage + ": " + e.getKey());
            }
            reopen(!stage.equals("as written"));
        }
    }

    @Test
    void concurrentIncrementsOnOneNodeLoseNothing() throws Exception {
        table();
        run("INSERT INTO d.t (_id, n, stock) VALUES ('c', 0, 5000)");
        int threads = 16;
        int each = 500;
        AtomicLong taken = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    for (int k = 0; k < each; k++) {
                        assertEquals(1, affected("UPDATE d.t SET n = n + 1 WHERE _id = 'c'"));
                        taken.addAndGet(affected("UPDATE d.t SET stock = stock - 1 WHERE _id = 'c' AND stock > 0"));
                    }
                    return null;
                }));
            }
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(8000L, value("c", "n"));
        assertEquals(0L, value("c", "stock"));
        assertEquals(5000, taken.get(), "each of the 5,000 in stock was taken once");
        reopen(false);
        assertEquals(8000L, value("c", "n"));
    }

    @Test
    void theMessageShortensALongId() throws IOException {
        table();
        String id = "x".repeat(100);
        run("INSERT INTO d.t (_id, n) VALUES ('" + id + "', 'one')");
        assertEquals("cannot set n = n + 1 in the row with _id '" + "x".repeat(32) + "...': n is a string",
                fails("UPDATE d.t SET n = n + 1"));
    }
}
