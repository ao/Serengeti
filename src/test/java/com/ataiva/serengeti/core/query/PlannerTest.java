package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Store;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which index read a query takes, and what an index keeps alive (review 22f). */
class PlannerTest {
    @TempDir
    Path tmp;

    private static Expr eq(String c, Object v) {
        return new Expr.Compare(c, Expr.Cmp.EQ, v);
    }

    private static Expr ge(String c, Object v) {
        return new Expr.Compare(c, Expr.Cmp.GE, v);
    }

    private static StorageEngine rows(Path dir, int n, java.util.function.IntFunction<Map<String, Object>> row)
            throws Exception {
        StorageEngine e = StorageEngine.open(dir, FsyncPolicy.OFF);
        Query q = new Query(e, new Hlc("n1"));
        q.run("CREATE DATABASE d");
        q.run("CREATE TABLE d.t");
        Stamp at = new Stamp(System.currentTimeMillis(), 0, "p");
        List<Op> puts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            puts.add(new Op.Put("d", "t", String.format("r%06d", i), row.apply(i), at));
        }
        e.apply(puts);
        return e;
    }

    @Test
    void theReadWithTheFewestRowsWinsWhateverColumnsItNames() throws Exception {
        try (StorageEngine e = rows(tmp.resolve("d"), 2_000, i -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("a", 1L);
            r.put("b", (long) i);
            r.put("c", (long) i);
            return r;
        })) {
            Query q = new Query(e, new Hlc("n1"));
            q.run("CREATE INDEX ON d.t (c)");
            q.run("CREATE INDEX ON d.t (a, b)");
            Expr byId = new Expr.And(List.of(eq("_id", "r000005"), eq("a", 1L), ge("b", 0L)));
            Expr byC = new Expr.And(List.of(eq("c", 7L), eq("a", 1L), ge("b", 0L)));
            Expr both = new Expr.And(List.of(eq("a", 1L), ge("b", 1_990L)));
            assertEquals("_id", e.read(s -> Executor.cheapestIndexedTerm(s, "d", "t", byId)).column());
            assertEquals("c", e.read(s -> Executor.cheapestIndexedTerm(s, "d", "t", byC)).column());
            assertEquals("a,b", e.read(s -> Executor.cheapestIndexedTerm(s, "d", "t", both)).column(),
                    "10 rows through (a, b), 2,000 through nothing else");
            assertEquals(List.of(List.of("r000007")),
                    ((Result.Rows) q.run("SELECT _id FROM d.t WHERE c = 7 AND a = 1 AND b >= 0")).rows());
        }
    }

    @Test
    void aReadTooLargeToGatherGivesWayToTheNextCheapest() throws Exception {
        int n = 120_000;
        try (StorageEngine e = rows(tmp.resolve("d"), n, i -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("a", i % 20 == 0 ? 0L : 1L);
            r.put("b", (long) i);
            return r;
        })) {
            Query q = new Query(e, new Hlc("n1"));
            q.run("CREATE INDEX ON d.t (a)");
            q.run("CREATE INDEX ON d.t (b)");
            // b >= 10,000 is 110,000 rows, over what a range may gather; a = 1 is 114,000 rows, which stream.
            Expr w = new Expr.And(List.of(ge("b", 10_000L), eq("a", 1L)));
            List<Executor.Plan> plans = e.read(s -> Executor.candidates(s, "d", "t", w));
            Executor.Plan first = e.read(s -> Executor.cheapest(s, "d", "t", plans, List.of()));
            assertEquals("b", first.column());
            long large = e.read(s -> s.indexEstimate("d", "t", "b", first.lookup()));
            assertTrue(large > Store.MATERIALIZE_LIMIT, "b >= 10,000: " + large);
            boolean gathered = e.read(s -> s.forEachIndexed("d", "t", "b", first.lookup(), (id, r) -> {}));
            assertFalse(gathered, "too large");
            Executor.Plan next = e.read(s -> Executor.cheapest(s, "d", "t", plans, List.of(first)));
            assertNotNull(next);
            assertEquals("a", next.column());
            assertNull(e.read(s -> Executor.cheapest(s, "d", "t", plans, List.of(first, next))));
            assertEquals(List.of(List.of(104_500L)),
                    ((Result.Rows) q.run("SELECT COUNT(*) FROM d.t WHERE b >= 10000 AND a = 1")).rows());
        }
    }

    @Test
    void anEstimateStopsCountingOncePastTheBestSoFar() throws Exception {
        try (StorageEngine e = rows(tmp.resolve("d"), 5_000, i -> Map.of("b", (long) i))) {
            new Query(e, new Hlc("n1")).run("CREATE INDEX ON d.t (b)");
            Store.Lookup all = new Store.Lookup.Compare(">=", 0L);
            long full = e.read(s -> s.indexEstimate("d", "t", "b", all));
            assertEquals(5_000L, full);
            long capped = e.read(s -> s.indexEstimate("d", "t", "b", all, 10));
            assertTrue(capped > 10 && capped < 5_000, "stopped at " + capped);
            long atCap = e.read(s -> s.indexEstimate("d", "t", "b", all, 5_000));
            assertEquals(5_000L, atCap, "exact up to the cap");
        }
    }

    private static Statement.Select select(String text) {
        return (Statement.Select) Parser.parse(text);
    }

    /**
     * An ordered read that a narrow _id range limits is given a share of that range, not of the table, so it is not
     * tried when the range is too small to fill the page within it (review 22g: it was 25 to 130 times slower).
     */
    @Test
    void anIdRangeGivesAnOrderedReadAShareOfTheRangeNotOfTheTable() throws Exception {
        try (StorageEngine e = rows(tmp.resolve("d"), 20_000, i -> Map.of("at", (long) (i * 7 % 20_000)))) {
            Query q = new Query(e, new Hlc("n1"));
            q.run("CREATE INDEX ON d.t (at)");
            Statement.Select narrow = select("SELECT _id FROM d.t WHERE _id >= 'r019900' ORDER BY at DESC LIMIT 10");
            Executor.OrderedPlan p = e.read(s -> Executor.orderedPlan(s, "d", "t", narrow));
            assertFalse(p.exact());
            long none = e.read(s -> Executor.orderedBudget(s, narrow, p, 11));
            assertEquals(-1L, none, "100 rows: a share of them is fewer than the page");
            Statement.Select wide = select("SELECT _id FROM d.t WHERE _id >= 'r005000' ORDER BY at DESC LIMIT 10");
            long some = e.read(s -> Executor.orderedBudget(s, wide, p, 11));
            assertEquals(20_000L / Executor.ORDERED_SHARE, some, "past 11 shares, the table's rows (review 22h)");
            for (int from : new int[] {19_900, 5_000}) {
                // at = i * 7 mod 20,000 is a different value for each i.
                List<Integer> ids = new ArrayList<>();
                for (int i = from; i < 20_000; i++) {
                    ids.add(i);
                }
                ids.sort((x, y) -> Integer.compare(y * 7 % 20_000, x * 7 % 20_000));
                List<List<Object>> want = new ArrayList<>();
                for (int i : ids.subList(0, 10)) {
                    want.add(List.of(String.format("r%06d", i)));
                }
                assertEquals(want, ((Result.Rows) q.run(String.format(
                        "SELECT _id FROM d.t WHERE _id >= 'r%06d' ORDER BY at DESC LIMIT 10", from))).rows());
            }
        }
    }

    /**
     * ORDER BY keys that stop before an index's last column tie on the rest, and the read forms each such group
     * key by key: it is given a budget, so one large group makes it give way to the sort (review 22g).
     */
    @Test
    void anOrderedReadThatGroupsKeysHasABudget() throws Exception {
        try (StorageEngine e = rows(tmp.resolve("d"), 20_000, i -> Map.of("a", "k", "b", (long) i))) {
            Query q = new Query(e, new Hlc("n1"));
            q.run("CREATE INDEX ON d.t (a, b)");
            Statement.Select byA = select("SELECT _id FROM d.t ORDER BY a LIMIT 10");
            Executor.OrderedPlan p = e.read(s -> Executor.orderedPlan(s, "d", "t", byA));
            assertFalse(p.exact(), "the keys stop before b");
            long budget = e.read(s -> Executor.orderedBudget(s, byA, p, 11));
            assertEquals(20_000L / Executor.ORDERED_SHARE, budget);
            Store.Ordered how = e.read(s -> s.forEachInOrder("d", "t", p.index(), p.where(), p.keys(), p.idDescending(),
                    budget, (id, row) -> true));
            assertEquals(Store.Ordered.OVER_BUDGET, how, "one group of 20,000 keys");
            assertEquals(List.of(List.of("r000000"), List.of("r000001")),
                    ((Result.Rows) q.run("SELECT _id FROM d.t ORDER BY a LIMIT 2")).rows());
            Statement.Select byAB = select("SELECT _id FROM d.t ORDER BY a, b DESC LIMIT 10");
            assertNull(e.read(s -> Executor.orderedPlan(s, "d", "t", byAB)), "b DESC after a ASC: not one direction");
            Statement.Select both = select("SELECT _id FROM d.t ORDER BY a, b LIMIT 10");
            assertTrue(e.read(s -> Executor.orderedPlan(s, "d", "t", both)).exact(), "the keys reach b");
        }
    }

    /**
     * The read chosen does not depend on the order of the terms: an _id range keeps the table's rows as its
     * estimate whichever term comes first, so an indexed term is read (review 22h).
     */
    @Test
    void theReadChosenDoesNotDependOnTheOrderOfTheTerms() throws Exception {
        try (StorageEngine e = rows(tmp.resolve("d"), 5_000, i -> Map.of("kind", "k" + i % 10, "half", "h" + i % 2))) {
            Query q = new Query(e, new Hlc("n1"));
            q.run("CREATE INDEX ON d.t (kind)");
            q.run("CREATE INDEX ON d.t (half)");
            for (String[] pair : new String[][] {{"kind = 'k3'", "_id >= 'r004900'"}, {"half = 'h1'", "_id > 'r000100'"},
                    {"kind = 'k3'", "_id LIKE 'r0%'"}}) {
                Expr ab = ((Statement.Select) Parser.parse("SELECT * FROM d.t WHERE " + pair[0] + " AND " + pair[1])).where();
                Expr ba = ((Statement.Select) Parser.parse("SELECT * FROM d.t WHERE " + pair[1] + " AND " + pair[0])).where();
                Executor.Plan one = e.read(s -> Executor.cheapestIndexedTerm(s, "d", "t", ab));
                Executor.Plan other = e.read(s -> Executor.cheapestIndexedTerm(s, "d", "t", ba));
                assertEquals(one, other, String.join(" AND ", pair));
                assertTrue(!"_id".equals(one.column()), "the indexed term: " + one);
            }
        }
    }

    /**
     * A page whose read would gather first streams in _id order for a share of the gathered size, then goes on
     * through the gathered read after the last _id it streamed: the same rows as the scan, whether the page
     * fills while streaming, after the hand-over, or the matches lie only past the streamed share (review 22i).
     */
    @Test
    void aPageThatWouldGatherStreamsFirstAndHandsOverWithoutLosingARow() throws Exception {
        int n = 20_000;
        try (StorageEngine e = rows(tmp.resolve("d"), n, i -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("a", (long) (i % 1000));
            r.put("b", (long) (i % 997));
            // Only the last 500 rows have a late value.
            r.put("late", (long) (i >= n - 500 ? i % 50 : 1000 + i));
            r.put("kind", (long) (i % 10));
            return r;
        })) {
            Query q = new Query(e, new Hlc("n1"));
            for (String c : List.of("a", "b", "late", "kind")) {
                q.run("CREATE INDEX ON d.t (" + c + ")");
            }
            assertTrue(Executor.gathers(e.read(s -> Executor.cheapestIndexedTerm(s, "d", "t",
                    ((Statement.Select) Parser.parse("SELECT * FROM d.t WHERE a < 20 OR b < 20")).where()))));
            for (String w : List.of("a < 20 OR b < 20", "late < 5", "late < 5 OR a = 999", "kind = 3 AND (a < 20 OR b < 20)",
                    "(a < 20 OR b < 20) AND _id > 'r010000'", "a < 62")) {
                for (String page : List.of(" LIMIT 1", " LIMIT 10", " LIMIT 400", " ORDER BY _id LIMIT 2000", "")) {
                    String indexed = "SELECT _id FROM d.t WHERE " + w + page;
                    String scanned = "SELECT _id FROM d.t WHERE NOT (NOT (" + w + "))" + page;
                    assertEquals(((Result.Rows) q.run(scanned)).rows(), ((Result.Rows) q.run(indexed)).rows(), indexed);
                }
            }
        }
    }

    @Test
    void whichReadsGather() {
        assertTrue(Executor.gathers(new Executor.Plan("a", new Store.Lookup.Compare(">", 1L))));
        assertTrue(Executor.gathers(new Executor.Plan("a", new Store.Lookup.Prefix("x"))));
        assertFalse(Executor.gathers(new Executor.Plan("a", new Store.Lookup.Compare("=", 1L))));
        assertFalse(Executor.gathers(new Executor.Plan("a", new Store.Lookup.AnyOf(List.of(1L, 2L)))));
        assertFalse(Executor.gathers(new Executor.Plan("_id", new Store.Lookup.Compare(">", "x"))));
        assertTrue(Executor.gathers(new Executor.Plan("a,b", new Store.Lookup.Composite(List.of(List.of(1L)), List.of()))));
        assertFalse(Executor.gathers(new Executor.Plan("a,b",
                new Store.Lookup.Composite(List.of(List.of(1L), List.of(2L)), List.of()))));
        assertTrue(Executor.gathers(new Executor.Plan("a,b", new Store.Lookup.Composite(List.of(List.of(1L)),
                List.of(new Store.Lookup.Compare(">", 1L))))));
        assertTrue(Executor.gathers(Executor.Plan.union(List.of(new Executor.Plan("a", new Store.Lookup.Compare("=", 1L))))));
    }

    private static boolean collected(WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        return ref.get() == null;
    }

    /**
     * A key of an index is the value of the row that put it there. When that row changes or goes while another
     * row keeps an equal value, the key is put again with the other row's, so the gone row's value can be freed.
     */
    @Test
    void anIndexKeyDoesNotKeepTheValueOfARowThatIsGone() throws Exception {
        for (String def : List.of("a", "a,b")) {
            Store s = new Store();
            long t = System.currentTimeMillis() - 3_600_000;
            s.apply(new Op.CreateDb("d", new Stamp(t, 0, "n1")));
            s.apply(new Op.CreateTable("d", "t", new Stamp(t, 1, "n1")));
            s.apply(new Op.CreateIndex("d", "t", def, new Stamp(t, 2, "n1")));
            String big = "v".repeat(10_000);
            List<WeakReference<String>> gone = new ArrayList<>();
            for (String id : List.of("x", "y", "z")) {
                Map<String, Object> row = new LinkedHashMap<>();
                String own = new String(big.toCharArray()); // its own copy, as parsing a request makes
                row.put("a", own);
                row.put("b", 1L);
                if (!id.equals("z")) {
                    gone.add(new WeakReference<>(own));
                }
                s.applyAll(s.withContext(List.of(new Op.Put("d", "t", id, row, new Stamp(t + 10, 0, id)))));
            }
            // x is deleted and collected; y takes another value.
            s.applyAll(s.withContext(List.of(new Op.Delete("d", "t", "x", new Stamp(t + 20, 0, "n1")))));
            s.collectTombstones(Long.MAX_VALUE);
            s.applyAll(s.withContext(List.of(new Op.Put("d", "t", "y", Map.of("a", "other", "b", 1L),
                    new Stamp(t + 30, 0, "n1")))));
            assertTrue(collected(gone.get(0)), def + ": the deleted row's value is freed");
            assertTrue(collected(gone.get(1)), def + ": the updated row's old value is freed");
            assertEquals(2L, s.rowCount("d", "t"));
            Store.Lookup l = def.equals("a") ? new Store.Lookup.Compare("=", big)
                    : new Store.Lookup.Composite(List.of(List.of(big), List.of(1L)), List.of());
            List<String> found = new ArrayList<>();
            assertTrue(s.forEachIndexed("d", "t", def, l, (id, r) -> found.add(id)));
            assertEquals(List.of("z"), found, def);
        }
    }
}
