package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LIMIT n OFFSET m: the rows the statement gives without them, in its order, less the first m, then at most n,
 * for every form; the cap, truncated and the bound on m + n; and how far each read goes. d.t has indexes on a, b,
 * p and (a, p); d.u holds the same rows and none, so each answer is checked against a scan and a sort.
 */
class OffsetTest {
    @TempDir
    Path dir;

    private StorageEngine engine;
    private Query query;

    /** Rows of {@link #rows()}. */
    static final int N = 4_000;

    @BeforeEach
    void open() throws IOException {
        engine = StorageEngine.open(dir, FsyncPolicy.OFF);
        query = new Query(engine, new Hlc("n1"));
        query.run("CREATE DATABASE d");
        query.run("CREATE TABLE d.t");
        query.run("CREATE TABLE d.u");
        for (String c : List.of("a", "b", "p", "a, p")) {
            query.run("CREATE INDEX ON d.t (" + c + ")");
        }
        rows();
    }

    @AfterEach
    void close() throws IOException {
        engine.close();
    }

    private static String id(int i) {
        return String.format("r%04d", i);
    }

    /** N rows r0000.. with a = i % 40, b = i % 50, c = i % 3, p = i, q = i % 400 and s = "s" + i % 30, in d.t and d.u. */
    private void rows() throws IOException {
        Stamp at = new Stamp(System.currentTimeMillis() + 1000, 0, "p");
        List<Op> ops = new ArrayList<>();
        for (String table : List.of("t", "u")) {
            for (int i = 0; i < N; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("a", (long) (i % 40));
                row.put("b", (long) (i % 50));
                row.put("c", (long) (i % 3));
                row.put("p", (long) i);
                row.put("q", (long) (i % 400));
                row.put("s", "s" + i % 30);
                ops.add(new Op.Put("d", table, id(i), row, at));
            }
        }
        engine.apply(ops);
    }

    private Result.Rows run(Query on, String select) throws IOException {
        return (Result.Rows) on.run(select);
    }

    /** The statement on d.t, checked against the same on d.u. */
    private Result.Rows rows(Query on, String select) throws IOException {
        Result.Rows got = run(on, select.replace("@T", "d.t"));
        assertEquals(run(on, select.replace("@T", "d.u")), got, select);
        return got;
    }

    private Result.Rows rows(String select) throws IOException {
        return rows(query, select);
    }

    private QueryException fails(Query on, String select) {
        return assertThrows(QueryException.class, () -> on.run(select));
    }

    /** The first column of each row. */
    private static List<Object> first(Result.Rows r) {
        List<Object> out = new ArrayList<>();
        r.rows().forEach(row -> out.add(row.get(0)));
        return out;
    }

    private static List<Object> ids(int from, int to) {
        List<Object> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            out.add(id(i));
        }
        return out;
    }

    /** The rows {@code m} to {@code m + n} of {@code all}, as many as there are. */
    private static <T> List<T> slice(List<T> all, long m, long n) {
        int from = (int) Math.min(m, all.size());
        return all.subList(from, (int) Math.min(all.size(), from + n));
    }

    @Test
    void everyFormIsTheSliceOfItsAnswer() throws IOException {
        List<String> forms = List.of(
                "SELECT _id, p FROM @T",
                "SELECT * FROM @T WHERE c = 1",
                "SELECT _id FROM @T WHERE a = 7",
                "SELECT _id FROM @T WHERE p >= 3990",
                "SELECT _id FROM @T WHERE _id > 'r1234' AND b != 3",
                // Sorted (no index on q), _id order, _id descending, and read through an index in order.
                "SELECT _id, q FROM @T ORDER BY q DESC",
                "SELECT _id, q FROM @T WHERE a < 3 ORDER BY q, p DESC",
                "SELECT _id FROM @T ORDER BY _id",
                "SELECT _id FROM @T WHERE a = 3 ORDER BY a, _id",
                "SELECT _id FROM @T ORDER BY _id DESC",
                "SELECT _id, p FROM @T ORDER BY p DESC",
                "SELECT _id, p FROM @T WHERE p < 100 ORDER BY p",
                "SELECT _id, p FROM @T WHERE a = 5 ORDER BY p DESC",
                "SELECT _id, a, p FROM @T WHERE a IN (1, 2) ORDER BY p",
                "SELECT _id, a, p FROM @T WHERE a IN (1, 2) AND s != 's1' ORDER BY a DESC, p DESC",
                // Through the union of the reads of an OR, in _id order, and sorted.
                "SELECT _id FROM @T WHERE a = 7 OR b = 9",
                "SELECT _id, p FROM @T WHERE a = 7 OR b = 9 ORDER BY p DESC",
                // DISTINCT, GROUP BY with HAVING and ORDER BY, and the one row of aggregates without GROUP BY.
                "SELECT DISTINCT s FROM @T",
                "SELECT DISTINCT a, c FROM @T WHERE b < 10 ORDER BY c DESC, a",
                "SELECT a, COUNT(*) FROM @T GROUP BY a",
                "SELECT s, SUM(p) FROM @T WHERE a < 20 GROUP BY s HAVING SUM(p) > 25000 ORDER BY sum(p) DESC",
                "SELECT COUNT(*) FROM @T",
                "SELECT COUNT(*) FROM @T WHERE a = 7",
                "SELECT MIN(p), MAX(p), AVG(q) FROM @T WHERE b = 9",
                "SELECT COUNT(*) FROM @T HAVING COUNT(*) > 5");
        long[][] pages = {{0, 0}, {0, 1}, {0, 5}, {1, 1}, {3, 7}, {7, 3}, {99, 2}, {178, 5}, {179, 5}, {180, 5},
                {200, 10}, {3995, 10}, {3999, 1}, {4000, 1}, {4001, 3}, {12345, 0}, {12345, 10000}};
        for (String form : forms) {
            Result.Rows all = rows(form);
            assertFalse(all.truncated(), form);
            for (long[] page : pages) {
                long m = page[0];
                long n = page[1];
                String paged = form + " LIMIT " + n + " OFFSET " + m;
                Result.Rows got = rows(paged);
                if (!form.startsWith("SELECT *")) {
                    assertEquals(all.columns(), got.columns(), paged);
                }
                assertEquals(slice(all.rows(), m, n), project(got, all.columns()), paged);
                assertFalse(got.truncated(), paged);
                // Without LIMIT: up to the cap of 10,000, which these answers never reach.
                got = rows(form + " OFFSET " + m);
                assertEquals(slice(all.rows(), m, Executor.DEFAULT_MAX_ROWS), project(got, all.columns()), form);
                assertFalse(got.truncated(), form);
            }
            assertEquals(all, rows(form + " OFFSET 0"), "OFFSET 0 changes nothing: " + form);
            assertEquals(rows(form + " LIMIT 4"), rows(form + " LIMIT 4 OFFSET 0"), form);
        }
    }

    /** The rows of {@code r} with the columns {@code columns}, in that order (for SELECT *, whose columns are the page's). */
    private static List<List<Object>> project(Result.Rows r, List<String> columns) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : r.rows()) {
            List<Object> p = new ArrayList<>();
            for (String c : columns) {
                int at = r.columns().indexOf(c);
                p.add(at < 0 ? null : row.get(at));
            }
            out.add(p);
        }
        return out;
    }

    @Test
    void aggregatesWithoutGroupByAreOneRowThatOffsetAndLimitCanDrop() throws IOException {
        for (String form : List.of("SELECT COUNT(*) FROM @T", "SELECT COUNT(*) FROM @T WHERE a = 7",
                "SELECT COUNT(*) FROM @T WHERE a = 1000", "SELECT SUM(p), COUNT(b) FROM @T WHERE b = 9",
                "SELECT MIN(s) FROM @T HAVING COUNT(*) > 1")) {
            Result.Rows one = rows(form);
            assertEquals(1, one.rows().size(), form);
            assertEquals(one, rows(form + " OFFSET 0"));
            assertEquals(one, rows(form + " LIMIT 1"));
            assertEquals(one, rows(form + " LIMIT 10000 OFFSET 0"));
            for (String none : List.of(" OFFSET 1", " LIMIT 0", " LIMIT 0 OFFSET 0", " LIMIT 1 OFFSET 1", " OFFSET 90000")) {
                Result.Rows r = rows(form + none);
                assertEquals(one.columns(), r.columns(), form + none);
                assertEquals(List.of(), r.rows(), form + none);
                assertFalse(r.truncated());
            }
        }
        assertEquals(List.of(List.of(4000L)), rows("SELECT COUNT(*) FROM @T LIMIT 1 OFFSET 0").rows());
        // HAVING that drops the row leaves nothing to page.
        assertEquals(List.of(), rows("SELECT COUNT(*) FROM @T HAVING COUNT(*) = 0 OFFSET 0").rows());
        // As without OFFSET, every aggregate is computed first: a SUM that cannot be given fails the page too.
        query.run("INSERT INTO d.t (_id, a, p) VALUES ('zz', 1, 9223372036854775807)");
        assertEquals("SUM overflows a 64-bit integer", fails(query, "SELECT SUM(p) FROM d.t OFFSET 1").getMessage());
        assertEquals("SUM overflows a 64-bit integer",
                fails(query, "SELECT a, SUM(p) FROM d.t GROUP BY a LIMIT 0").getMessage());
    }

    @Test
    void theCapAppliesToTheRowsReturnedAndTruncatedToTheRowsAfterThem() throws IOException {
        Query five = new Query(new Executor(engine, new Hlc("n2"), 5));
        Result.Rows r = rows(five, "SELECT _id FROM @T OFFSET 3");
        assertEquals(ids(3, 8), first(r));
        assertTrue(r.truncated(), "the cap ended the page");
        r = rows(five, "SELECT _id FROM @T LIMIT 5 OFFSET 3");
        assertEquals(ids(3, 8), first(r));
        assertFalse(r.truncated(), "LIMIT ended it");
        // 10 rows match: OFFSET 5 returns the last 5, and nothing follows them.
        String tail = "SELECT _id FROM @T WHERE p >= 3990";
        for (String order : List.of("", " ORDER BY _id", " ORDER BY p", " ORDER BY q, p", " ORDER BY _id DESC")) {
            List<Object> want = first(rows(query, tail + order));
            assertEquals(10, want.size());
            for (int m = 0; m <= 12; m++) {
                r = rows(five, tail + order + " OFFSET " + m);
                assertEquals(slice(want, m, 5), first(r), order + " OFFSET " + m);
                assertEquals(m < 5, r.truncated(), order + " OFFSET " + m);
                r = rows(five, tail + order + " LIMIT 5 OFFSET " + m);
                assertEquals(slice(want, m, 5), first(r));
                assertFalse(r.truncated());
                r = rows(five, tail + order + " LIMIT 2 OFFSET " + m);
                assertEquals(slice(want, m, 2), first(r));
                assertFalse(r.truncated());
            }
        }
        // Groups and DISTINCT: 40 values of a.
        for (String form : List.of("SELECT a, COUNT(*) FROM @T GROUP BY a", "SELECT DISTINCT a FROM @T",
                "SELECT a, COUNT(*) FROM @T GROUP BY a HAVING COUNT(*) > 1 ORDER BY a DESC")) {
            List<Object> want = first(rows(query, form));
            assertEquals(40, want.size());
            for (int m : new int[] {0, 1, 34, 35, 36, 39, 40, 41}) {
                r = rows(five, form + " OFFSET " + m);
                assertEquals(slice(want, m, 5), first(r), form + " OFFSET " + m);
                assertEquals(m < 35, r.truncated(), form + " OFFSET " + m);
                r = rows(five, form + " LIMIT 3 OFFSET " + m);
                assertEquals(slice(want, m, 3), first(r));
                assertFalse(r.truncated());
            }
        }
        assertEquals("LIMIT 6 is over the maximum of 5", fails(five, "SELECT * FROM d.t LIMIT 6 OFFSET 1").getMessage());
        assertEquals("LIMIT 6 is over the maximum of 5", fails(five, "SELECT COUNT(*) FROM d.t LIMIT 6").getMessage());
        // At the default cap, through each kind of read: the 10,000 rows after the OFFSET, and truncated.
        query.run("CREATE TABLE d.big");
        query.run("CREATE INDEX ON d.big (p)");
        Stamp at = new Stamp(System.currentTimeMillis() + 1000, 0, "p");
        List<Op> ops = new ArrayList<>();
        List<Object> byId = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            String id = String.format("b%05d", i);
            byId.add(id);
            ops.add(new Op.Put("d", "big", id, new LinkedHashMap<>(Map.of("p", (Object) (long) i, "q", (long) (i % 400))), at));
        }
        engine.apply(ops);
        List<Object> byQ = new ArrayList<>(byId);
        byQ.sort(Comparator.comparing((Object id) -> Integer.parseInt(((String) id).substring(1)) % 400)
                .thenComparing(id -> (String) id));
        List<Object> byIdDesc = new ArrayList<>(byId);
        Collections.reverse(byIdDesc);
        Map<String, List<Object>> orders = new LinkedHashMap<>();
        orders.put("", byId);
        orders.put(" ORDER BY _id", byId);
        orders.put(" ORDER BY p", byId);
        orders.put(" ORDER BY p DESC", byIdDesc);
        orders.put(" ORDER BY q, p", byQ);
        orders.put(" ORDER BY _id DESC", byIdDesc);
        for (Map.Entry<String, List<Object>> o : orders.entrySet()) {
            String s = "SELECT _id FROM d.big" + o.getKey();
            List<Object> want = o.getValue();
            r = run(query, s + " OFFSET 9000");
            assertEquals(want.subList(9_000, 19_000), first(r), s);
            assertTrue(r.truncated(), s);
            r = run(query, s + " OFFSET 10000");
            assertEquals(want.subList(10_000, 20_000), first(r), s);
            assertFalse(r.truncated(), s);
            r = run(query, s + " OFFSET 15000");
            assertEquals(want.subList(15_000, 20_000), first(r), s);
            assertFalse(r.truncated(), s);
            r = run(query, s + " LIMIT 10000 OFFSET 9000");
            assertEquals(want.subList(9_000, 19_000), first(r), s);
            assertFalse(r.truncated(), s);
            r = run(query, s + " LIMIT 3 OFFSET 19998");
            assertEquals(want.subList(19_998, 20_000), first(r), s);
        }
    }

    @Test
    void offsetPlusLimitIsAtMost100000() throws IOException {
        assertEquals(100_000, Executor.MAX_OFFSET_ROWS);
        assertEquals(List.of(), rows("SELECT * FROM @T LIMIT 10 OFFSET 99990").rows());
        assertEquals(List.of(), rows("SELECT * FROM @T OFFSET 90000").rows());
        assertEquals(List.of(), rows("SELECT * FROM @T LIMIT 0 OFFSET 100000").rows());
        String keyset = ": an OFFSET page reads every row before it, so read a large table in keyset pages, WHERE _id >"
                + " (the last _id of the page before) ORDER BY _id LIMIT n (docs/QUERY.md, \"Reading a large table\")";
        assertEquals("OFFSET 99991 plus LIMIT 10 is over the maximum of 100000 rows" + keyset,
                fails(query, "SELECT * FROM d.t LIMIT 10 OFFSET 99991").getMessage());
        assertEquals("OFFSET 90001 plus the cap of 10000 is over the maximum of 100000 rows" + keyset,
                fails(query, "SELECT * FROM d.t OFFSET 90001").getMessage());
        for (String q : List.of("SELECT * FROM d.t OFFSET 9223372036854775807",
                "SELECT * FROM d.t LIMIT 10000 OFFSET 9223372036854775807", "SELECT * FROM d.t LIMIT 1 OFFSET 100000",
                "SELECT _id FROM d.t ORDER BY p LIMIT 10 OFFSET 99991", "SELECT _id FROM d.t ORDER BY q OFFSET 95000",
                "SELECT DISTINCT a FROM d.t OFFSET 100000", "SELECT a, COUNT(*) FROM d.t GROUP BY a LIMIT 1 OFFSET 100000",
                "SELECT COUNT(*) FROM d.t OFFSET 99000", "SELECT COUNT(*) FROM d.t WHERE a = 1 LIMIT 2 OFFSET 99999",
                "SELECT * FROM d.t WHERE nothing = 1 LIMIT 10 OFFSET 99991")) {
            assertTrue(fails(query, q).getMessage().contains("is over the maximum of 100000 rows: an OFFSET page"), q);
        }
        // A LIMIT over the cap is named first, and a missing table before either.
        assertEquals("LIMIT 10001 is over the maximum of 10000",
                fails(query, "SELECT * FROM d.t LIMIT 10001 OFFSET 99999").getMessage());
        assertEquals("table 'd.nope' does not exist", fails(query, "SELECT * FROM d.nope OFFSET 99999999").getMessage());
        // The bound is on OFFSET plus LIMIT: with a smaller cap, a larger OFFSET is allowed without LIMIT.
        Query five = new Query(new Executor(engine, new Hlc("n2"), 5));
        assertEquals(List.of(), run(five, "SELECT * FROM d.t OFFSET 99995").rows());
        assertTrue(fails(five, "SELECT * FROM d.t OFFSET 99996").getMessage().startsWith(
                "OFFSET 99996 plus the cap of 5 is over the maximum of 100000 rows"));
    }

    /** What {@link Executor#read} reads for the statement on d.t with the cap {@code cap}. */
    private Executor.Read read(int cap, String select) {
        Executor e = new Executor(engine, new Hlc("n3"), cap);
        Statement.Select q = (Statement.Select) Parser.parse(select.replace("@T", "d.t"));
        return engine.read(s -> e.read(s, q));
    }

    @Test
    void readsInOrderStopAfterOffsetPlusLimit() {
        // With LIMIT, `truncated` is false whatever follows, so a read stops at OFFSET + LIMIT rows (at least 1);
        // without, one more tells whether more follow.
        // In _id order: a scan, an _id range, a range on an index (gathered into _id order), a union of index reads.
        for (String where : List.of("", " WHERE _id > 'r1000'", " WHERE p >= 2000", " WHERE a = 7 OR b = 9",
                " WHERE a = 7 OR b = 9 ORDER BY _id", " WHERE a = 3 ORDER BY a, _id")) {
            // A range and a union gather their ids; a page first streams the table in _id order for a share of
            // that, visiting rows that do not match, then goes on through the gathered read (review 22i).
            boolean gathers = where.contains("p >=") || where.contains(" OR ");
            for (int[] mn : new int[][] {{0, 0}, {0, 1}, {20, 10}, {7, 0}, {50, 3}}) {
                Executor.Read r = read(10_000, "SELECT * FROM @T" + where + " LIMIT " + mn[1] + " OFFSET " + mn[0]);
                int keep = Math.max(1, mn[0] + mn[1]);
                if (gathers) {
                    assertTrue(r.visited() >= keep, where + " " + Arrays.toString(mn));
                } else {
                    assertEquals(keep, r.visited(), where + " " + Arrays.toString(mn));
                }
                assertEquals(keep, r.rows().size());
            }
            Executor.Read r = read(5, "SELECT * FROM @T" + where + " OFFSET 20");
            if (gathers) {
                assertTrue(r.visited() >= 26, where);
            } else {
                assertEquals(26, r.visited(), where);
            }
        }
        // Through an index in its order: exact reads, with and without a column the WHERE fixes, and IN lists merged.
        for (String q : List.of("SELECT * FROM @T ORDER BY p DESC", "SELECT * FROM @T WHERE p > 100 ORDER BY p",
                "SELECT * FROM @T WHERE a = 5 ORDER BY p DESC", "SELECT * FROM @T WHERE a IN (1, 2) ORDER BY p",
                "SELECT * FROM @T WHERE a = 5 ORDER BY a, p, _id")) {
            assertTrue(engine.read(s -> Executor.orderedPlan(s, "d", "t", (Statement.Select) Parser.parse(
                    q.replace("@T", "d.t")))).exact(), q);
            Executor.Read r = read(10_000, q + " LIMIT 10 OFFSET 30");
            assertEquals(40, r.visited(), q);
            assertEquals(40, r.rows().size(), q);
            assertEquals(13, read(5, q + " OFFSET 7").visited(), q);
        }
        // Rows the WHERE leaves out are read too (no index on c or q): the read stops at the 5th match.
        Executor.Read r = read(10_000, "SELECT * FROM @T WHERE c = 0 LIMIT 3 OFFSET 2");
        assertEquals(5, r.rows().size());
        assertEquals(13, r.visited(), "the 5th row with c = 0 is r0012");
        r = read(10_000, "SELECT * FROM @T WHERE q < 2 LIMIT 3 OFFSET 2");
        assertEquals(5, r.rows().size());
        assertEquals(801, r.visited(), "the 5th row with q < 2 is r0800");
    }

    /**
     * An answer's rows hold at most the executor's bound: a page past it ends early, with at least one row, and
     * says truncated, with LIMIT too; keyset pages that go on from the last _id read every row (review 22l).
     */
    @Test
    void anAnswerEndsAtItsBoundOnSizeAndSaysSo() throws IOException {
        Executor small = new Executor(engine, new Hlc("n4"));
        small.maxAnswerBytes(8_000);
        Query q = new Query(small);
        Result.Rows first = run(q, "SELECT * FROM d.t ORDER BY _id LIMIT 1000");
        assertTrue(first.truncated(), "past the bound, with LIMIT");
        assertTrue(first.rows().size() > 1 && first.rows().size() < 1000, "rows: " + first.rows().size());
        List<Object> all = new ArrayList<>();
        Result.Rows page = first;
        for (int pages = 1; ; pages++) {
            page.rows().forEach(r -> all.add(r.get(0)));
            if (!page.truncated() && page.rows().size() < 1000) {
                assertTrue(pages > 2, "pages: " + pages);
                break;
            }
            page = run(q, "SELECT * FROM d.t WHERE _id > '" + all.get(all.size() - 1) + "' ORDER BY _id LIMIT 1000");
        }
        List<Object> want = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            want.add(id(i));
        }
        assertEquals(want, all, "every row once, in order");
        // One row past the bound alone: it is still answered.
        small.maxAnswerBytes(1);
        Result.Rows one = run(q, "SELECT * FROM d.t ORDER BY _id LIMIT 5");
        assertEquals(1, one.rows().size());
        assertTrue(one.truncated());
        // Under the bound nothing changes.
        Result.Rows plain = run(query, "SELECT * FROM d.t ORDER BY _id LIMIT 1000");
        assertEquals(1000, plain.rows().size());
        assertFalse(plain.truncated());
    }

    @Test
    void theSortKeepsTheFirstRowsItNeedsAndReadsEveryMatch() {
        // No index gives q's order: every row is read, and the first m + n by the ORDER BY are kept.
        Executor.Read r = read(10_000, "SELECT * FROM @T ORDER BY q DESC, p LIMIT 10 OFFSET 20");
        assertEquals(N, r.visited());
        assertEquals(30, r.rows().size());
        List<Object> ids = new ArrayList<>();
        r.rows().forEach(m -> ids.add(m.id()));
        // q = 399 for i = 399, 799, ..., 3999 (10 rows), then q = 398 (10 rows), and so on, each by p.
        List<Object> want = new ArrayList<>();
        for (int q = 399; want.size() < 30; q--) {
            for (int i = q; i < N && want.size() < 30; i += 400) {
                want.add(id(i));
            }
        }
        assertEquals(want, ids);
        // Fewer matches than it may keep: all of them.
        assertEquals(N, read(10_000, "SELECT * FROM @T ORDER BY q LIMIT 10000 OFFSET 90000").rows().size());
        assertEquals(N, read(5, "SELECT * FROM @T ORDER BY q OFFSET 3995").rows().size());
        assertEquals(N, read(5, "SELECT * FROM @T ORDER BY q OFFSET 3994").rows().size());
        assertEquals(3_999, read(5, "SELECT * FROM @T ORDER BY q OFFSET 3993").rows().size());
        // An ordered read with a budget (the index does not answer c != 3) stops at OFFSET + LIMIT rows too.
        r = read(10_000, "SELECT * FROM @T WHERE c != 3 ORDER BY p DESC LIMIT 2 OFFSET 1");
        assertEquals(List.of(id(3999), id(3998), id(3997)),
                r.rows().stream().map(Executor.Match::id).map(Object.class::cast).toList());
        assertEquals(3, r.visited());
    }

    @Test
    void topKeepsWhatSortingEverythingWouldKeep() {
        Comparator<Executor.Match> byN = Comparator.comparing((Executor.Match m) -> (Long) m.row().get("n"))
                .thenComparing(Executor.Match::id);
        Random r = new Random(7);
        for (int round = 0; round < 200; round++) {
            int size = r.nextInt(60);
            List<Executor.Match> all = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                // Few values of n, so that many rows tie on it and are told apart by _id.
                all.add(new Executor.Match(String.format("k%03d", r.nextInt(1000)) + "-" + i,
                        Map.of("n", (long) r.nextInt(5))));
            }
            if (round % 3 == 0) {
                all.sort(byN.reversed());
            } else if (round % 3 == 1) {
                all.sort(byN);
            } else {
                Collections.shuffle(all, r);
            }
            List<Executor.Match> sorted = new ArrayList<>(all);
            sorted.sort(byN);
            for (long keep : new long[] {1, 2, Math.max(1, size - 1), Math.max(1, size), size + 1, 1 + r.nextInt(70)}) {
                Executor.Top top = new Executor.Top(byN, keep);
                all.forEach(top::add);
                assertEquals(sorted.subList(0, (int) Math.min(keep, size)), top.sorted(), "size " + size + ", keep " + keep);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new Executor.Top(byN, 0));
    }
}
