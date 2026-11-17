package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Store;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SELECT with ORDER BY and LIMIT read in index order: each case answers as the same table without the index
 * does (d.u holds the rows of d.t and no index), and the read it takes is the one docs/QUERY.md describes.
 */
class OrderedReadTest {
    @TempDir
    Path dir;

    private StorageEngine engine;
    private Query query;

    @BeforeEach
    void open() throws IOException {
        engine = StorageEngine.open(dir, FsyncPolicy.OFF);
        query = new Query(engine, new Hlc("n1"));
        query.run("CREATE DATABASE d");
        query.run("CREATE TABLE d.t");
        query.run("CREATE TABLE d.u");
    }

    @AfterEach
    void close() throws IOException {
        engine.close();
    }

    /** Puts the rows into d.t and d.u. */
    private void put(Map<String, Map<String, Object>> rows) throws IOException {
        Stamp at = new Stamp(System.currentTimeMillis() + 1000, 0, "p");
        List<Op> ops = new ArrayList<>();
        for (String table : List.of("t", "u")) {
            rows.forEach((id, row) -> ops.add(new Op.Put("d", table, id, row, at)));
        }
        engine.apply(ops);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** The _id column of the statement on d.t, checked against the same statement on d.u. */
    private List<Object> ids(Query q, String select) throws IOException {
        Result.Rows got = (Result.Rows) q.run(String.format(select, "d.t"));
        Result.Rows want = (Result.Rows) q.run(String.format(select, "d.u"));
        assertEquals(want, got, select);
        List<Object> out = new ArrayList<>();
        for (List<Object> r : got.rows()) {
            out.add(r.get(got.columns().indexOf("_id")));
        }
        return out;
    }

    private List<Object> ids(String select) throws IOException {
        return ids(query, select);
    }

    private Executor.OrderedPlan plan(String select) {
        Statement.Select q = (Statement.Select) Parser.parse(String.format(select, "d.t"));
        return engine.read(s -> Executor.orderedPlan(s, "d", "t", q));
    }

    private void values() throws IOException {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        rows.put("m1", row("k", 1L));
        rows.put("n1", row("a", null));
        rows.put("f1", row("a", false));
        rows.put("t1", row("a", true));
        rows.put("z2", row("a", 0L));
        rows.put("z1", row("a", -0.0));
        rows.put("o2", row("a", 1.0));
        rows.put("o1", row("a", 1L));
        rows.put("h3", row("a", 9007199254740992.0));
        rows.put("h1", row("a", 9007199254740992L));
        rows.put("h2", row("a", 9007199254740993L));
        rows.put("h4", row("a", 9007199254740994.0));
        rows.put("s1", row("a", ""));
        rows.put("s2", row("a", "a"));
        rows.put("b2", row("a", List.of(1L)));
        rows.put("b1", row("a", Map.of("k", 1L)));
        put(rows);
    }

    @Test
    void anIndexOnOneColumnPlacesEveryValueInBothDirections() throws IOException {
        values();
        query.run("CREATE INDEX ON d.t (a)");
        // Null and missing first, false, true, numbers (exactly: 2^53 as a long and as a double are equal, 2^53 + 1
        // is above both), strings, then objects and arrays; equal values by _id.
        List<Object> up = List.of("m1", "n1", "f1", "t1", "z1", "z2", "o1", "o2", "h1", "h3", "h2", "h4", "s1", "s2",
                "b1", "b2");
        assertEquals(up, ids("SELECT _id, a FROM %s ORDER BY a LIMIT 100"));
        assertEquals(up, ids("SELECT * FROM %s ORDER BY a ASC, _id"));
        // DESC reverses the values; rows with equal ones stay in _id order, unless _id DESC says otherwise.
        assertEquals(List.of("b1", "b2", "s2", "s1", "h4", "h2", "h1", "h3", "o1", "o2", "z1", "z2", "t1", "f1", "m1",
                "n1"), ids("SELECT _id FROM %s ORDER BY a DESC LIMIT 100"));
        assertEquals(List.of("b2", "b1", "s2", "s1", "h4", "h2", "h3", "h1", "o2", "o1", "z2", "z1", "t1", "f1", "n1",
                "m1"), ids("SELECT _id FROM %s ORDER BY a DESC, _id DESC LIMIT 100"));
        assertEquals(List.of("b1", "b2", "s2"), ids("SELECT _id FROM %s ORDER BY a DESC LIMIT 3"));
        assertEquals(List.of("m1"), ids("SELECT _id FROM %s ORDER BY a LIMIT 1"));
        assertEquals(List.of(), ids("SELECT _id FROM %s ORDER BY a LIMIT 0"));
        for (String q : List.of("SELECT _id FROM %s ORDER BY a DESC LIMIT 3", "SELECT * FROM %s ORDER BY a, _id DESC")) {
            Executor.OrderedPlan p = plan(q);
            assertNotNull(p, q);
            assertTrue(p.exact(), q);
            assertEquals("a", p.index());
        }
        // A range on the column narrows the read to its type; IN lists read each value's rows, merged.
        assertEquals(List.of("h4", "h2", "h1", "h3", "o1", "o2"), ids("SELECT _id FROM %s WHERE a >= 1 ORDER BY a DESC LIMIT 10"));
        assertEquals(List.of("z1", "z2", "o1"), ids("SELECT _id FROM %s WHERE a > -1 AND a <= 1 ORDER BY a LIMIT 3"));
        assertEquals(List.of("s2", "s1"), ids("SELECT _id FROM %s WHERE a < 'b' ORDER BY a DESC"));
        assertEquals(List.of("s2"), ids("SELECT _id FROM %s WHERE a LIKE 'a%%' ORDER BY a DESC"));
        assertEquals(List.of(), ids("SELECT _id FROM %s WHERE a > 1 AND a < 'z' ORDER BY a"));
        assertEquals(List.of("s1", "o1", "o2", "t1"), ids("SELECT _id FROM %s WHERE a IN (TRUE, 1, '', 'zz') ORDER BY a DESC"));
        assertTrue(plan("SELECT _id FROM %s WHERE a IN (TRUE, 1, '') ORDER BY a DESC").exact());
        assertTrue(plan("SELECT _id FROM %s WHERE a >= 1 ORDER BY a DESC LIMIT 10").exact());
        assertFalse(plan("SELECT _id FROM %s WHERE a LIKE 'a%%' ORDER BY a DESC").exact(), "a pattern may say more");
        // Other terms are checked on each row read.
        assertEquals(List.of("s2", "h4", "h2"), ids("SELECT _id FROM %s WHERE _id != 'b1' AND _id != 'b2' AND _id != 's1' "
                + "ORDER BY a DESC LIMIT 3"));
        assertEquals(List.of("n1"), ids("SELECT _id FROM %s WHERE a IS NULL AND _id > 'm1' ORDER BY a"));
    }

    @Test
    void aKeyOnAColumnTheWhereFixesDecidesNothing() throws IOException {
        values();
        query.run("CREATE INDEX ON d.t (k, a)");
        for (String q : List.of("SELECT * FROM d.t WHERE a = 1 ORDER BY a DESC LIMIT 1",
                "SELECT * FROM d.t WHERE a = 1 ORDER BY a, _id", "SELECT * FROM d.t WHERE a = 'a' AND k = 1 ORDER BY k, a")) {
            assertTrue(Executor.readsInIdOrder((Statement.Select) Parser.parse(q)), q);
        }
        assertFalse(Executor.readsInIdOrder((Statement.Select) Parser.parse("SELECT * FROM d.t WHERE a = 1 ORDER BY a, _id DESC")));
        assertFalse(Executor.readsInIdOrder((Statement.Select) Parser.parse("SELECT * FROM d.t WHERE a IN (1, 2) ORDER BY a")));
        assertEquals(List.of("o1", "o2"), ids("SELECT _id FROM %s WHERE a = 1 ORDER BY a DESC"));
        assertEquals(List.of("o2", "o1"), ids("SELECT _id FROM %s WHERE a = 1 ORDER BY a DESC, _id DESC"));
    }

    private void orders() throws IOException {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        String[] customers = {"c1", "c2", "c3"};
        for (int i = 0; i < 30; i++) {
            String c = customers[i % 3];
            rows.put(String.format("o%02d", i), row("customer", c, "placed", String.format("2026-%02d", 1 + (i * 7) % 12),
                    "total", (long) (i % 5)));
        }
        rows.put("x1", row("placed", "2026-05")); // no customer
        rows.put("x2", row("customer", "c1")); // no date
        rows.put("x3", row("customer", "c1", "placed", List.of("2026-01"))); // an array
        put(rows);
    }

    @Test
    void anIndexOnTwoColumnsReadsTheRowsOfOneValueInTheOrderOfTheNext() throws IOException {
        orders();
        query.run("CREATE INDEX ON d.t (customer, placed)");
        String newest = "SELECT _id, placed FROM %s WHERE customer = 'c1' ORDER BY placed DESC LIMIT 4";
        assertEquals(List.of("x3", "o03", "o15", "o27"), ids(newest), "the array sorts last, so first descending");
        assertTrue(plan(newest).exact());
        assertEquals(List.of("x2", "o00", "o12", "o24"), ids("SELECT _id FROM %s WHERE customer = 'c1' ORDER BY placed LIMIT 4"),
                "no date sorts first");
        // A range on the ordered column narrows the read.
        String range = "SELECT _id FROM %s WHERE customer = 'c1' AND placed >= '2026-03' AND placed < '2026-09' "
                + "ORDER BY placed LIMIT 3";
        assertEquals(List.of("o09", "o21", "o06"), ids(range));
        assertTrue(plan(range).exact());
        assertEquals(1, plan(range).where().equal().size());
        assertEquals(2, plan(range).where().range().size());
        // Both columns, nothing fixed: rows without a customer first.
        assertEquals(List.of("x1", "x2", "o00", "o12"), ids("SELECT _id FROM %s ORDER BY customer, placed LIMIT 4"));
        assertEquals(List.of("o05", "o17", "o29", "o08"), ids("SELECT _id FROM %s ORDER BY customer DESC, placed DESC LIMIT 4"));
        // IN lists: each customer's rows in order, merged; a key on the customer may come before or after.
        assertEquals(List.of("x3", "o10", "o22", "o03"), ids("SELECT _id FROM %s WHERE customer IN ('c2', 'c1') "
                + "ORDER BY placed DESC LIMIT 4"));
        assertEquals(List.of("o10", "o22", "o01", "o13"), ids("SELECT _id FROM %s WHERE customer IN ('c2', 'c1') "
                + "ORDER BY customer DESC, placed DESC LIMIT 4"));
        assertEquals(List.of("x3", "o10", "o22", "o03"), ids("SELECT _id FROM %s WHERE customer IN ('c2', 'c1') "
                + "ORDER BY placed DESC, customer LIMIT 4"));
        // Out of the index's order, or a column it does not hold: sorted, the same answer.
        for (String q : List.of("SELECT _id FROM %s ORDER BY customer, placed DESC LIMIT 5",
                "SELECT _id FROM %s ORDER BY placed DESC LIMIT 5", "SELECT _id FROM %s WHERE customer = 'c1' ORDER BY total LIMIT 5",
                "SELECT _id FROM %s ORDER BY _id DESC LIMIT 5")) {
            assertNull(plan(q), q);
            ids(q);
        }
        // Only the first of the index's columns: rows equal on it come in _id order, not by placed.
        assertEquals(List.of("o00", "o03", "o06", "o09", "o12"), ids("SELECT _id FROM %s WHERE customer >= 'c1' "
                + "ORDER BY customer LIMIT 5"));
        assertEquals(List.of("o02", "o05", "o08"), ids("SELECT _id FROM %s ORDER BY customer DESC LIMIT 3"));
        assertNotNull(plan("SELECT _id FROM %s ORDER BY customer DESC LIMIT 3"));
    }

    @Test
    void truncatedIsWhatTheSortSays() throws IOException {
        orders();
        query.run("CREATE INDEX ON d.t (customer, placed)");
        Query capped = new Query(new Executor(engine, new Hlc("n2"), 3));
        Result.Rows r = (Result.Rows) capped.run("SELECT _id FROM d.t WHERE customer = 'c1' ORDER BY placed DESC");
        assertEquals(List.of(List.of("x3"), List.of("o03"), List.of("o15")), r.rows());
        assertTrue(r.truncated());
        assertEquals(r, capped.run("SELECT _id FROM d.u WHERE customer = 'c1' ORDER BY placed DESC"));
        Result.Rows few = (Result.Rows) capped.run("SELECT _id FROM d.t WHERE customer = 'c3' AND placed > '2026-10' ORDER BY placed");
        assertFalse(few.truncated());
        assertEquals(few, capped.run("SELECT _id FROM d.u WHERE customer = 'c3' AND placed > '2026-10' ORDER BY placed"));
        Result.Rows limited = (Result.Rows) capped.run("SELECT _id FROM d.t WHERE customer = 'c1' ORDER BY placed DESC LIMIT 3");
        assertFalse(limited.truncated(), "with a LIMIT, never truncated");
        assertEquals(r.rows(), limited.rows());
    }

    @Test
    void aConditionTheReadDoesNotAnswerHasAShareOfTheRowsToFillThePage() throws IOException {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (int i = 0; i < 6400; i++) {
            // The three rare rows are the oldest, so a read from the newest finds them last.
            rows.put(String.format("e%04d", i), row("at", (long) i, "kind", i < 3 ? "rare" : "common"));
        }
        put(rows);
        query.run("CREATE INDEX ON d.t (at)");
        String rare = "SELECT _id FROM %s WHERE kind = 'rare' ORDER BY at DESC LIMIT 10";
        Statement.Select q = (Statement.Select) Parser.parse(String.format(rare, "d.t"));
        Executor.OrderedPlan p = plan(rare);
        assertFalse(p.exact());
        assertEquals(100L, (long) engine.read(s -> Executor.orderedBudget(s, q, p, 11)), "1/64 of the 6,400 rows a scan reads");
        AtomicInteger visited = new AtomicInteger();
        assertEquals(Store.Ordered.OVER_BUDGET, engine.read(s -> s.forEachInOrder("d", "t", p.index(), p.where(), p.keys(),
                false, 100, (id, row) -> visited.incrementAndGet() > 0)));
        assertEquals(99, visited.get(), "it stopped after its share (one step opened the read)");
        assertEquals(List.of("e0002", "e0001", "e0000"), ids(rare), "then the rows are read and sorted");
        // Common rows fill the page within the share.
        assertEquals(List.of("e6399", "e6398"), ids("SELECT _id FROM %s WHERE kind = 'common' ORDER BY at DESC LIMIT 2"));
        // With an index that finds the three rows, the other read is so small that the ordered one is not tried.
        query.run("CREATE INDEX ON d.t (kind)");
        assertEquals(-1L, (long) engine.read(s -> Executor.orderedBudget(s, q, p, 11)));
        assertEquals(List.of("e0002", "e0001", "e0000"), ids(rare));
        assertEquals(List.of("e6399", "e6398"), ids("SELECT _id FROM %s WHERE kind = 'common' ORDER BY at DESC LIMIT 2"));
    }

    @Test
    void theReadStopsWhenItHasThePageAndRefusesKeysOutOfOrder() throws IOException {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (int i = 0; i < 1000; i++) {
            rows.put(String.format("e%04d", i), row("at", (long) (i % 100), "n", (long) i));
        }
        put(rows);
        query.run("CREATE INDEX ON d.t (at)");
        Store.Lookup.Composite all = new Store.Lookup.Composite(List.of(), List.of());
        List<String> seen = new ArrayList<>();
        assertEquals(Store.Ordered.DONE, engine.read(s -> s.forEachInOrder("d", "t", "at", all,
                List.of(new Store.SortKey(0, true)), false, Long.MAX_VALUE, (id, row) -> seen.add(id) && seen.size() < 11)));
        assertEquals(List.of("e0099", "e0199", "e0299", "e0399", "e0499", "e0599", "e0699", "e0799", "e0899", "e0999",
                "e0098"), seen);
        assertEquals(Store.Ordered.NO_INDEX, engine.read(s -> s.forEachInOrder("d", "t", "n", all,
                List.of(new Store.SortKey(0, true)), false, 10, (id, row) -> true)));
        query.run("CREATE INDEX ON d.t (at, n)");
        assertThrows(IllegalArgumentException.class, () -> engine.read(s -> s.forEachInOrder("d", "t", "at,n", all,
                List.of(new Store.SortKey(1, false)), false, 10, (id, row) -> true)));
        assertThrows(IllegalArgumentException.class, () -> engine.read(s -> s.forEachInOrder("d", "t", "at,n", all,
                List.of(new Store.SortKey(0, false), new Store.SortKey(1, true)), false, 10, (id, row) -> true)));
    }

    @Test
    void anIndexHoldsEveryRowSoItsWeightCountsThoseWithoutAValueToo() throws IOException {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (int i = 0; i < 1000; i++) {
            rows.put(String.format("e%04d", i), i % 4 == 0 ? row("at", (long) i) : i % 4 == 1 ? row("at", Map.of())
                    : row("other", 1L));
        }
        put(rows);
        long before = engine.read(Store::weight);
        query.run("CREATE INDEX ON d.t (at)");
        // 1,000 entries of 40 bytes, and a key of 104 for each of the 250 distinct numbers.
        assertEquals(1000 * 40 + 250 * 104, engine.read(Store::weight) - before);
        query.run("CREATE INDEX ON d.t (at, other)");
        long tuple = 104 + 16 + 8; // a key of an index on two columns: its tree entry and set, and its array
        // 250 keys of the numbers, one for the objects, (OTHER, null), and one for the rest, (null, 1).
        assertEquals(2 * 1000 * 40 + 250 * 104 + (250 + 1 + 1) * tuple, engine.read(Store::weight) - before);
    }

    /**
     * Writers on other threads (local writes, and versions from three peers that had not seen each other's)
     * while readers compare the ordered read with the sort in one read of the engine: the two see one state, the
     * engine's lock holding writers off, and agree on it.
     */
    @Test
    void readsAgreeWithTheSortWhileOthersWrite() throws Exception {
        query.run("CREATE INDEX ON d.t (customer, placed)");
        query.run("CREATE INDEX ON d.t (placed)");
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicInteger compared = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            int writer = w;
            threads.add(new Thread(() -> {
                Random r = new Random(writer);
                long t = System.currentTimeMillis();
                try {
                    while (!stop.get()) {
                        String id = "r" + r.nextInt(300);
                        Map<String, Object> v = row("customer", "c" + r.nextInt(3), "placed", r.nextInt(5) == 0 ? null
                                : (Object) String.format("2026-%02d", 1 + r.nextInt(12)));
                        if (writer == 0) {
                            String peer = "p" + (1 + r.nextInt(3));
                            engine.merge(List.of(r.nextInt(5) == 0
                                    ? new Op.Delete("d", "t", id, new Stamp(t + r.nextInt(2000), 0, peer), Map.of())
                                    : new Op.Put("d", "t", id, v, new Stamp(t + r.nextInt(2000), 0, peer), Map.of())));
                        } else if (r.nextInt(4) == 0) {
                            query.run("DELETE FROM d.t WHERE _id = '" + id + "'");
                        } else {
                            query.run("UPDATE d.t SET placed = '2026-" + String.format("%02d", 1 + r.nextInt(12))
                                    + "' WHERE customer = 'c" + r.nextInt(3) + "' AND placed < '2026-0" + (1 + r.nextInt(9)) + "'");
                            query.run("DELETE FROM d.t WHERE _id = 'n" + r.nextInt(50) + "'");
                            try {
                                query.run("INSERT INTO d.t (_id, customer, placed) VALUES ('n" + r.nextInt(50) + "', 'c1', '2026-06')");
                            } catch (QueryException taken) {
                                assertTrue(taken.getMessage().contains("already exists"), taken.getMessage());
                            }
                        }
                    }
                } catch (Throwable e) {
                    failed.compareAndSet(null, e);
                }
            }));
        }
        List<String[]> pairs = List.of(
                new String[] {"SELECT _id, placed FROM d.t WHERE customer = 'c1' ORDER BY placed DESC LIMIT 7",
                    "SELECT _id, placed FROM d.t WHERE customer = 'c1' ORDER BY zz, placed DESC LIMIT 7"},
                new String[] {"SELECT * FROM d.t ORDER BY placed DESC LIMIT 9", "SELECT * FROM d.t ORDER BY zz, placed DESC LIMIT 9"},
                new String[] {"SELECT _id FROM d.t WHERE customer IN ('c0', 'c2') AND placed >= '2026-04' ORDER BY placed LIMIT 5",
                    "SELECT _id FROM d.t WHERE customer IN ('c0', 'c2') AND placed >= '2026-04' ORDER BY zz, placed LIMIT 5"},
                new String[] {"SELECT _id FROM d.t ORDER BY customer DESC, placed DESC", "SELECT _id FROM d.t ORDER BY zz, customer DESC, placed DESC"});
        for (int rd = 0; rd < 2; rd++) {
            threads.add(new Thread(() -> {
                try {
                    for (int i = 0; i < 400 && failed.get() == null; i++) {
                        String[] pair = pairs.get(i % pairs.size());
                        List<Result> both = engine.read(s -> {
                            try {
                                return List.of(query.run(pair[0]), query.run(pair[1]));
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                        assertEquals(both.get(1), both.get(0), pair[0]);
                        compared.incrementAndGet();
                    }
                } catch (Throwable e) {
                    failed.compareAndSet(null, e);
                }
            }));
        }
        List<Thread> started = new ArrayList<>(threads);
        started.forEach(Thread::start);
        for (Thread th : started.subList(2, started.size())) {
            th.join();
        }
        stop.set(true);
        for (Thread th : started.subList(0, 2)) {
            th.join();
        }
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
        assertEquals(800, compared.get());
        assertTrue(plan("SELECT * FROM %s ORDER BY placed DESC LIMIT 9").exact());
    }
}
