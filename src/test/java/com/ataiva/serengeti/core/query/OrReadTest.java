package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Store;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An OR whose every branch an index or _id answers is read as the union of the branches' reads: each case
 * answers as the same table without indexes does (d.u holds the rows of d.t and no index), and the read it takes
 * is the one docs/QUERY.md describes.
 */
class OrReadTest {
    @TempDir
    Path dir;

    private StorageEngine engine;
    private Query query;

    /** Rows of {@link #rows()}: a union may visit 1/16 of them, 250. */
    static final int N = 4_000;

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

    /**
     * N rows r0000.. with a = i % 40 (100 rows a value), b = i % 50 (80), c = i % 3, p = i, q = i % 400 (10) and
     * s = "s" + i % 30.
     */
    private void rows() throws IOException {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (int i = 0; i < N; i++) {
            rows.put(id(i), row("a", (long) (i % 40), "b", (long) (i % 50), "c", (long) (i % 3), "p", (long) i,
                    "q", (long) (i % 400), "s", "s" + i % 30));
        }
        put(rows);
    }

    private static String id(int i) {
        return String.format("r%04d", i);
    }

    /** The ids of {@link #rows()} for which {@code p} holds, in order. */
    private static List<Object> where(IntPredicate p) {
        return IntStream.range(0, N).filter(p).mapToObj(OrReadTest::id).map(Object.class::cast).toList();
    }

    /** The _id column of the statement on d.t, checked against the same statement on d.u. */
    private List<Object> ids(String select) throws IOException {
        Result.Rows got = (Result.Rows) query.run(select.replace("@T", "d.t"));
        Result.Rows want = (Result.Rows) query.run(select.replace("@T", "d.u"));
        assertEquals(want, got, select);
        List<Object> out = new ArrayList<>();
        for (List<Object> r : got.rows()) {
            out.add(r.get(got.columns().indexOf("_id")));
        }
        return out;
    }

    private long affected(String statement) throws IOException {
        return ((Result.Write) query.run(statement)).affected();
    }

    private Executor.Plan plan(String where) {
        Statement.Select q = (Statement.Select) Parser.parse("SELECT * FROM d.t WHERE " + where);
        return engine.read(s -> Executor.cheapestIndexedTerm(s, "d", "t", q.where()));
    }

    /** The indexes of the reads of the union the WHERE is read through; null if it is not read through a union. */
    private List<String> union(String where) {
        Executor.Plan p = plan(where);
        if (p == null || !p.isUnion()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Executor.Plan r : p.union()) {
            out.add(r.column());
        }
        return out;
    }

    private static Executor.Plan read(String column, Store.Lookup l) {
        return new Executor.Plan(column, l);
    }

    /** {@link Store#forEachUnionWhile} on d.t, inside a read. */
    private boolean unionRead(List<Store.IndexRead> reads, BiPredicate<String, Map<String, Object>> visitor) {
        return engine.read(s -> s.forEachUnionWhile("d", "t", reads, visitor));
    }

    @Test
    void anOrOfIndexedTermsIsReadAsTheUnionInIdOrderEachRowOnce() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (a)");
        query.run("CREATE INDEX ON d.t (b)");
        query.run("CREATE INDEX ON d.t (q)");
        assertEquals(Executor.Plan.union(List.of(read("a", new Store.Lookup.Compare("=", 3L)),
                read("b", new Store.Lookup.Compare("=", 13L)))), plan("a = 3 OR b = 13"));
        List<Object> want = where(i -> i % 40 == 3 || i % 50 == 13);
        assertEquals(100 + 80 - 20, want.size(), "r0163 and one in every 200 after it are found by both reads");
        assertEquals(want, ids("SELECT _id FROM @T WHERE a = 3 OR b = 13"));
        assertEquals(List.of("r0003", "r0013", "r0043"), ids("SELECT _id FROM @T WHERE a = 3 OR b = 13 LIMIT 3"));
        assertEquals(List.of("r0163", "r0203"),
                ids("SELECT _id FROM @T WHERE _id > 'r0150' AND (a = 3 OR b = 13) ORDER BY _id LIMIT 2"));
        assertEquals(List.of(List.of(160L)), ((Result.Rows) query.run("SELECT COUNT(*) FROM d.t WHERE a = 3 OR b = 13")).rows());
        // The same read twice, an IN list and a value in it, equal numbers of two kinds: each row once.
        assertEquals(ids("SELECT _id FROM @T WHERE a = 3"), ids("SELECT _id FROM @T WHERE a = 3 OR a = 3"));
        assertEquals(List.of("a", "a"), union("a = 3 OR a = 3"));
        assertEquals(ids("SELECT _id FROM @T WHERE b IN (3, 4)"), ids("SELECT _id FROM @T WHERE b IN (3, 4) OR b = 4.0"));
        assertEquals(List.of("b", "b"), union("b IN (3, 4) OR b = 4.0"));
        // Three branches, three kinds of lookup.
        assertEquals(where(i -> i % 40 == 1 || i % 400 == 5 || i % 400 == 6 || i % 400 >= 399),
                ids("SELECT * FROM @T WHERE a = 1 OR q IN (5, 6) OR q >= 399"));
        assertEquals(List.of("a", "q", "q"), union("a = 1 OR q IN (5, 6) OR q >= 399"));
    }

    @Test
    void aBranchThatNoIndexAnswersLeavesTheOrToAScan() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (a)");
        for (String w : List.of("a = 1 OR b = 2", "a = 1 OR a IS NULL", "a = 1 OR a != 2", "a = 1 OR NOT a = 2",
                "a = 1 OR a NOT IN (2)", "a = 1 OR s LIKE '%1'", "a = 1 OR s NOT LIKE 's1%'", "a = 1 OR (b = 2 AND c = 1)")) {
            assertNull(plan(w), w);
            ids("SELECT _id FROM @T WHERE " + w);
        }
        // An AND branch needs one term an index answers, as a WHERE does.
        assertEquals(List.of("a", "a"), union("a = 1 OR (b = 2 AND a > 38)"));
        assertEquals(where(i -> i % 40 == 1 || i % 50 == 2 && i % 40 > 38),
                ids("SELECT _id FROM @T WHERE a = 1 OR (b = 2 AND a > 38)"));
    }

    @Test
    void aUnionReadsAtMostOneSixteenthOfTheTable() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (a)");
        query.run("CREATE INDEX ON d.t (b)");
        query.run("CREATE INDEX ON d.t (q)");
        // 100 + 80 + 70 = 250 rows, N / 16: read through the indexes; one more value, 260: scanned.
        assertEquals(List.of("a", "b", "q"), union("a = 1 OR b = 1 OR q IN (1, 2, 3, 4, 5, 6, 7)"));
        assertNull(plan("a = 1 OR b = 1 OR q IN (1, 2, 3, 4, 5, 6, 7, 8)"));
        ids("SELECT _id FROM @T WHERE a = 1 OR b = 1 OR q IN (1, 2, 3, 4, 5, 6, 7, 8)");
        assertNull(plan("a >= 0 OR q = 1"), "the whole table");
        // A range or prefix of _id is counted up to the share: 9 and 100 ids here, 3,000 there.
        assertEquals(List.of("_id", "a"), union("_id > 'r3990' OR a = 1"));
        assertEquals(where(i -> i > 3990 || i % 40 == 1), ids("SELECT _id FROM @T WHERE _id > 'r3990' OR a = 1"));
        assertEquals(List.of("_id", "a"), union("_id LIKE 'r39%' OR a = 1"));
        assertEquals(where(i -> i >= 3900 || i % 40 == 1), ids("SELECT _id FROM @T WHERE _id LIKE 'r39%' OR a = 1"));
        assertEquals(109L, (long) engine.read(s -> Executor.estimate(s, "d", "t", plan("_id > 'r3990' OR a = 1"),
                Long.MAX_VALUE)), "the count, not the table's rows");
        assertNull(plan("_id >= 'r1000' OR a = 1"));
        assertEquals(List.of("_id", "a"), union("_id IN ('r3990', 'x') OR a = 1"));
        assertEquals(where(i -> i == 3990 || i % 40 == 1), ids("SELECT _id FROM @T WHERE _id IN ('r3990', 'x') OR a = 1"));
        // The share is of the rows as they are now.
        assertEquals(List.of("a", "b"), union("a = 1 OR b = 1"));
        for (String t : List.of("d.t", "d.u")) {
            assertEquals(N / 2, affected("DELETE FROM " + t + " WHERE a >= 20"));
        }
        assertNull(plan("a = 1 OR b = 1"), "100 + 40 rows of 2,000");
        assertEquals(List.of("a", "q"), union("a = 1 OR q = 1"));
        ids("SELECT _id FROM @T WHERE a = 1 OR q = 1");
    }

    @Test
    void anOrInsideAnAndIsOneMoreCandidateEstimatedAsTheSumOfItsReads() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (a)");
        query.run("CREATE INDEX ON d.t (b)");
        query.run("CREATE INDEX ON d.t (c)");
        query.run("CREATE INDEX ON d.t (q)");
        // c = 1 finds 1,333 rows; a = 3 OR b = 13 at most 100 + 80.
        assertEquals(List.of("a", "b"), union("c = 1 AND (a = 3 OR b = 13)"));
        assertEquals(180L, (long) engine.read(s -> Executor.estimate(s, "d", "t", plan("c = 1 AND (a = 3 OR b = 13)"), Long.MAX_VALUE)));
        assertEquals(where(i -> i % 3 == 1 && (i % 40 == 3 || i % 50 == 13)),
                ids("SELECT _id, a, b, c FROM @T WHERE c = 1 AND (a = 3 OR b = 13)"));
        // a = 3 alone (100) and q = 1 (10) find fewer.
        assertEquals(read("a", new Store.Lookup.Compare("=", 3L)), plan("a = 3 AND (a = 3 OR b = 13)"));
        assertEquals(read("q", new Store.Lookup.Compare("=", 1L)), plan("(a = 1 OR b = 2) AND q = 1"));
        // A union over the share is no candidate: c is read.
        assertEquals("c", plan("c = 1 AND (a IN (1, 2, 3) OR b = 2)").column());
        // Of two ORs, the smaller union.
        assertEquals(List.of("b", "b"), union("(a = 1 OR a = 2) AND (b = 1 OR b = 2)"));
        ids("SELECT _id FROM @T WHERE (a = 1 OR a = 2) AND (b = 1 OR b = 2)");
        // Of a union and one read that visit as many rows, the one read.
        assertEquals(read("a", new Store.Lookup.Compare("=", 3L)), plan("(a = 4 OR q = 100000) AND a = 3"));
        // The cheapest union also bounds the share an ordered read may take (1/7 of what that index read would
        // visit, 1/96 of the table's rows for a scan), and is the read the query falls back on when the ordered
        // read runs out of it.
        query.run("CREATE INDEX ON d.t (p)");
        for (String w : List.of("(a = 1 OR a = 2) AND s != 'x'", "(s = 's1' OR s = 's2') AND s != 'x'")) {
            Statement.Select q = (Statement.Select) Parser.parse("SELECT * FROM d.t WHERE " + w + " ORDER BY p LIMIT 1");
            Executor.OrderedPlan op = engine.read(s -> Executor.orderedPlan(s, "d", "t", q));
            assertFalse(op.exact());
            assertEquals(w.startsWith("(a") ? 200L / Executor.ORDERED_SHARE_OF_INDEX_READ : N / Executor.ORDERED_SHARE,
                    (long) engine.read(s -> Executor.orderedBudget(s, q, op, 2)), w);
            ids("SELECT _id, p FROM @T WHERE " + w + " ORDER BY p LIMIT 1");
            ids("SELECT _id, p FROM @T WHERE " + w + " ORDER BY p DESC LIMIT 3");
        }
        assertEquals(List.of("r0001"), ids("SELECT _id, p FROM @T WHERE (a = 1 OR a = 2) AND s != 'x' ORDER BY p LIMIT 1"));
    }

    @Test
    void branchesAreAndsThatAnIndexOnSeveralColumnsAnswers() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (c, p)");
        String w = "(c = 1 AND p > 3900) OR (c = 2 AND p < 100)";
        assertEquals(Executor.Plan.union(List.of(read("c,p", new Store.Lookup.Composite(List.of(List.of(1L)),
                List.of(new Store.Lookup.Compare(">", 3900L)))), read("c,p", new Store.Lookup.Composite(
                        List.of(List.of(2L)), List.of(new Store.Lookup.Compare("<", 100L)))))), plan(w));
        assertEquals(where(i -> i % 3 == 1 && i > 3900 || i % 3 == 2 && i < 100), ids("SELECT _id FROM @T WHERE " + w));
        ids("SELECT _id, c, p FROM @T WHERE (c = 1 AND p > 3900 AND a = 1) OR (c IN (0, 2) AND p < 10) ORDER BY _id LIMIT 4");
        // Each branch's cheapest read: _id, a column, or the index on several columns.
        query.run("CREATE INDEX ON d.t (a)");
        String three = "(_id = 'r0010' AND c = 1) OR (c = 2 AND a = 5) OR (c = 1 AND p = 7)";
        assertEquals(List.of("_id", "a", "c,p"), union(three));
        assertEquals(where(i -> i == 10 || i % 3 == 2 && i % 40 == 5 || i == 7), ids("SELECT * FROM @T WHERE " + three));
    }

    @Test
    void orsInsideAnAndBranchAndOrsOfOrsGiveTheirReadsToOneUnion() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (a)");
        query.run("CREATE INDEX ON d.t (b)");
        query.run("CREATE INDEX ON d.t (q)");
        query.run("CREATE INDEX ON d.t (s)");
        String nested = "(c = 1 AND (a = 1 OR b = 1)) OR q = 6";
        assertEquals(List.of("a", "b", "q"), union(nested));
        assertEquals(where(i -> i % 3 == 1 && (i % 40 == 1 || i % 50 == 1) || i % 400 == 6),
                ids("SELECT * FROM @T WHERE " + nested));
        assertEquals(List.of("a", "b", "q"), union("a = 1 OR (b = 2 OR q = 3)"));
        ids("SELECT _id FROM @T WHERE a = 1 OR (b = 2 OR q = 3)");
        // A nested OR with a branch no index answers: the AND branch takes its other term.
        assertEquals(List.of("s", "q"), union("(s = 's1' AND (a = 1 OR p > 3)) OR q = 6"));
        ids("SELECT _id FROM @T WHERE (s = 's1' AND (a = 1 OR p > 3)) OR q = 6");
        // ... and with no other term, the whole OR scans.
        assertNull(plan("(a = 1 OR p > 3) OR q = 6"));
        ids("SELECT _id FROM @T WHERE (a = 1 OR p > 3) OR q = 6");
    }

    @Test
    void idBranchesAndMixedTypes() throws IOException {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (int i = 0; i < 400; i++) {
            rows.put(String.format("0%03d", i), row("v", "f" + i));
        }
        rows.put("a1", row("v", 1L));
        rows.put("a2", row("v", 1.0));
        rows.put("a3", row("v", -0.0));
        rows.put("a4", row("v", 0L));
        rows.put("b1", row("v", 9007199254740992L));
        rows.put("b2", row("v", 9007199254740993L));
        rows.put("b3", row("v", 9007199254740992.0));
        rows.put("c1", row("v", "1"));
        rows.put("c2", row("v", true));
        rows.put("c3", row("v", null));
        rows.put("c4", row());
        rows.put("c5", row("v", List.of(1L)));
        rows.put("c6", row("v", Map.of("k", 1L)));
        put(rows);
        query.run("CREATE INDEX ON d.t (v)");
        assertEquals(List.of("v", "v", "v"), union("v = 1 OR v = 0 OR v = '1'"));
        assertEquals(List.of("a1", "a2", "a3", "a4", "c1"), ids("SELECT _id FROM @T WHERE v = 1 OR v = 0 OR v = '1'"));
        assertEquals(List.of("b1", "b2", "b3"), ids("SELECT _id FROM @T WHERE v = 9007199254740993 OR v = 9007199254740992.0"));
        assertEquals(List.of("_id", "v", "v"), union("_id = 'c4' OR v = -0.0 OR v = TRUE"));
        assertEquals(List.of("a3", "a4", "c2", "c4"), ids("SELECT _id FROM @T WHERE _id = 'c4' OR v = -0.0 OR v = TRUE"));
        assertEquals(List.of("a1", "b1", "b2", "b3", "c1", "c2", "c3", "c5", "c6"),
                ids("SELECT _id FROM @T WHERE _id IN ('a1', 'zz', 'c3') OR _id LIKE 'b%' OR _id > 'c' AND v IS NOT NULL"
                        + " OR v = '1'"));
        assertEquals(List.of("_id", "v", "v"), union("_id = 'c3' OR v = NULL OR v IN (NULL)"));
        assertEquals(List.of("c3"), ids("SELECT _id FROM @T WHERE _id = 'c3' OR v = NULL OR v IN (NULL)"));
        assertEquals(List.of(), ids("SELECT _id FROM @T WHERE _id = 5 OR v = 'none'"));
        assertEquals(List.of("v", "v"), union("v LIKE 'f39%' OR v LIKE 'f1'"));
        assertEquals(List.of("0001", "0039", "0390", "0391", "0392", "0393", "0394", "0395", "0396", "0397", "0398", "0399"),
                ids("SELECT _id FROM @T WHERE v LIKE 'f39%' OR v LIKE 'f1'"));
    }

    @Test
    void writesReadThroughTheUnionAndMoveRowsInIt() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (a)");
        query.run("CREATE INDEX ON d.t (b)");
        query.run("CREATE INDEX ON d.t (q)");
        for (String table : List.of("d.t", "d.u")) {
            // b is a column of the WHERE and of SET: each row is changed once.
            assertEquals(160, affected("UPDATE " + table + " SET b = 13, a = 4 WHERE a = 3 OR b = 13"));
        }
        long deleted = affected("DELETE FROM d.t WHERE a = 1 OR (b = 5 AND a > 30)");
        assertEquals(affected("DELETE FROM d.u WHERE a = 1 OR (b = 5 AND a > 30)"), deleted);
        assertEquals(List.of("a", "b"), union("a = 1 OR (b = 5 AND a > 30)"));
        assertEquals(ids("SELECT * FROM @T").size(), N - deleted);
        assertEquals(160, ids("SELECT _id FROM @T WHERE a = 4 AND b = 13").size());
        assertEquals(List.of(), ids("SELECT _id FROM @T WHERE a = 3 OR a = 1"));
        // Conflicting versions from two peers: the winner moves between the branches' keys.
        long t = System.currentTimeMillis() + 5_000;
        for (String table : List.of("t", "u")) {
            engine.merge(List.of(new Op.Put("d", table, "r0004", row("a", 9L, "b", 9L), new Stamp(t, 0, "p1"), Map.of()),
                    new Op.Put("d", table, "r0004", row("a", 3L), new Stamp(t + 1, 0, "p2"), Map.of()),
                    new Op.Delete("d", table, "r0014", new Stamp(t, 0, "p2"), Map.of())));
        }
        assertEquals(List.of("a", "b"), union("a = 3 OR b = 9"));
        assertEquals("r0004", ids("SELECT _id FROM @T WHERE a = 3 OR b = 9").get(0));
        assertEquals(List.of("q", "b"), union("q = 14 OR b = 14"));
        assertFalse(ids("SELECT _id FROM @T WHERE q = 14 OR b = 14").contains("r0014"));
    }

    @Test
    void keysetPagesThroughAUnionGiveEveryRowOnce() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (a)");
        query.run("CREATE INDEX ON d.t (b)");
        List<Object> all = ids("SELECT _id FROM @T WHERE a = 3 OR b = 13");
        List<Object> paged = new ArrayList<>();
        String after = "";
        while (true) {
            List<Object> page = ids("SELECT _id FROM @T WHERE (a = 3 OR b = 13) AND _id > '" + after + "' ORDER BY _id LIMIT 7");
            paged.addAll(page);
            if (page.size() < 7) {
                break;
            }
            after = (String) page.get(page.size() - 1);
        }
        assertEquals(all, paged);
        assertEquals(List.of("a", "b"), union("(a = 3 OR b = 13) AND _id > 'r1000'"),
                "the union (at most 180 rows) is cheaper than the _id range (2,999 rows)");
    }

    @Test
    void theUnionStopsWhenTheVisitorSays() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (a)");
        query.run("CREATE INDEX ON d.t (b)");
        List<Store.IndexRead> reads = List.of(new Store.IndexRead("a", new Store.Lookup.Compare("=", 3L)),
                new Store.IndexRead("b", new Store.Lookup.Compare(">=", 48L)), new Store.IndexRead("_id",
                        new Store.Lookup.Prefix("r1")));
        List<String> seen = new ArrayList<>();
        assertTrue(unionRead(reads, (id, row) -> seen.add(id) && seen.size() < 4));
        assertEquals(List.of("r0003", "r0043", "r0048", "r0049"), seen);
        List<String> every = new ArrayList<>();
        assertTrue(unionRead(reads, (id, row) -> every.add(id)));
        assertEquals(where(i -> i % 40 == 3 || i % 50 >= 48 || i >= 1000 && i < 2000), new ArrayList<Object>(every));
        // A read of an index that does not exist: nothing visited.
        List<Store.IndexRead> missing = List.of(reads.get(0), new Store.IndexRead("c", new Store.Lookup.Compare("=", 1L)));
        assertFalse(unionRead(missing, (id, row) -> {
            throw new AssertionError("visited " + id);
        }));
    }

    @Test
    void thereAreAtMost32ReadsAnd4000ValuesInAUnion() throws IOException {
        rows();
        query.run("CREATE INDEX ON d.t (p)");
        StringBuilder w = new StringBuilder("p = 0");
        for (int i = 1; i < 32; i++) {
            w.append(" OR p = ").append(i);
        }
        assertEquals(32, union(w.toString()).size());
        assertEquals(where(i -> i < 32), ids("SELECT _id FROM @T WHERE " + w));
        assertNull(plan(w + " OR p = 32"), "33 branches");
        assertEquals(where(i -> i < 33), ids("SELECT _id FROM @T WHERE " + w + " OR p = 32"));
        // The reads of nested ORs count.
        assertNull(plan("(" + w + ") OR p = 32"));
        assertNull(plan("(s = 's1' AND (" + w + ")) OR p = 32"));
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            list.append(i == 0 ? "" : ", ").append(i < 10 ? 3990 + i : 10_000 + i);
        }
        String in = "p IN (" + list + ")";
        String four = in + " OR " + in + " OR " + in + " OR " + in;
        assertEquals(4, union(four).size(), "4,000 values");
        assertNull(plan(four + " OR p = -1"), "4,001 values");
        assertEquals(where(i -> i >= 3990), ids("SELECT _id FROM @T WHERE " + four + " OR p = -1"));
    }

    @Test
    void aUnionThatWouldGatherTooManyIdsVisitsNothing() throws IOException {
        int n = 120_000;
        Stamp at = new Stamp(System.currentTimeMillis() + 1000, 0, "p");
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ops.add(new Op.Put("d", "t", String.format("r%06d", i), row("b", (long) i, "c", (long) i), at));
        }
        engine.apply(ops);
        query.run("CREATE INDEX ON d.t (b)");
        query.run("CREATE INDEX ON d.t (c)");
        // b < 60,000 and c >= 60,000 are ranges of 60,000 rows each: each could be gathered, both not.
        List<Store.IndexRead> both = List.of(new Store.IndexRead("b", new Store.Lookup.Compare("<", 60_000L)),
                new Store.IndexRead("c", new Store.Lookup.Compare(">=", 60_000L)));
        assertFalse(unionRead(both, (id, row) -> {
            throw new AssertionError("visited " + id);
        }));
        // 100,000 ids in all are gathered.
        List<Store.IndexRead> limit = List.of(new Store.IndexRead("b", new Store.Lookup.Compare("<", 50_000L)),
                new Store.IndexRead("c", new Store.Lookup.Compare(">=", 70_000L)),
                new Store.IndexRead("_id", new Store.Lookup.Compare(">=", "r119990")),
                new Store.IndexRead("b", new Store.Lookup.Compare("=", 60_000L)));
        long[] visited = {0};
        assertTrue(unionRead(limit, (id, row) -> ++visited[0] > 0));
        assertEquals(100_001, visited[0]);
        // One range larger than 100,000 on its own.
        assertFalse(unionRead(List.of(new Store.IndexRead("b",
                new Store.Lookup.Compare("<", 110_000L))), (id, row) -> true));
        // The planner does not get there with fewer than 1,600,000 rows: such unions are over 1/16 of the table.
        assertNull(plan("b < 60000 OR c >= 60000"));
        assertEquals(List.of(List.of((long) n)), ((Result.Rows) query.run("SELECT COUNT(*) FROM d.t WHERE b < 60000 OR c >= 60000")).rows());
    }
}
