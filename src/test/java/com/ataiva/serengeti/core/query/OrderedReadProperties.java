package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Store;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.statistics.Statistics;

/**
 * SELECT with ORDER BY and a LIMIT (or the cap, here 5) read in index order gives the answer of the sort, row
 * for row. Random rows (nulls, missing values, every type, objects and arrays, -0.0 and the neighbours of 2^53,
 * versions from three peers that had not seen each other's), random indexes on one to three columns, and random
 * statements: mostly ORDER BY keys on an index's columns past those the WHERE fixes, with other terms, ranges,
 * IN lists, _id as a last key and keys in mixed directions among them. A table with the indexes returns the
 * same rows in the same order, and the same truncated, as the same table without them, before and after writes
 * that move rows in the indexes.
 */
class OrderedReadProperties {
    private static Path dir;
    private static StorageEngine engine;
    private static Query query;
    private static final AtomicInteger RUN = new AtomicInteger();
    static final int CAP = 5;
    private static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "MISSING";
        }
    };
    static final List<String> COLUMNS = List.of("a", "b", "c");
    static final List<Object> STORED = Arrays.asList(MISSING, MISSING, null, true, false, 0L, -0.0, 0.0, 1L, 1.0, 2.5,
            -3L, 9007199254740991L, 9007199254740992L, 9007199254740993L, 9007199254740992.0, 9007199254740994.0, "",
            "a", "a", "ab", "b", "\uFFFF", Map.of("k", 1L), List.of(1L), List.of());
    static final List<Object> LITERALS = Arrays.asList(null, true, false, 0L, -0.0, 1L, 1.0, 2.5, -3L,
            9007199254740992L, 9007199254740993L, 9007199254740992.0, "", "a", "ab", "b", "z");
    static final List<String> PATTERNS = List.of("a%", "a", "%", "ab%", "_", "b%", "\uFFFF%");
    static final List<List<String>> DEFINITIONS = List.of(List.of("a"), List.of("b"), List.of("a", "b"),
            List.of("b", "a"), List.of("a", "b", "c"), List.of("c", "a"), List.of("c", "b", "a"));

    @BeforeContainer
    static void open() throws IOException {
        dir = Files.createTempDirectory("ordered-read-properties");
        engine = StorageEngine.open(dir, FsyncPolicy.OFF);
        query = new Query(new Executor(engine, new Hlc("n1"), CAP));
    }

    @AfterContainer
    static void close() throws IOException {
        engine.close();
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    static <T> T pick(Random r, List<T> from) {
        return from.get(r.nextInt(from.size()));
    }

    private static String lit(Random r) {
        return Values.format(pick(r, LITERALS));
    }

    /** A term on any column, of any kind the language has. */
    static String term(Random r) {
        String c = pick(r, COLUMNS);
        return switch (r.nextInt(8)) {
            case 0, 1 -> c + " = " + lit(r);
            case 2 -> c + " IN (" + lit(r) + ", " + lit(r) + ")";
            case 3, 4 -> c + " " + pick(r, List.of("<", "<=", ">", ">=")) + " " + lit(r);
            case 5 -> c + (r.nextInt(4) == 0 ? " NOT" : "") + " LIKE " + Values.format(pick(r, PATTERNS));
            case 6 -> c + (r.nextBoolean() ? " IS NULL" : " IS NOT NULL");
            default -> c + " != " + lit(r);
        };
    }

    /**
     * A SELECT with ORDER BY: most often on the columns of one of {@code defs} past those the WHERE fixes (with =
     * or an IN list), in one direction, keys on the fixed columns mixed in, maybe a range on the first ordered
     * column, maybe other terms and a last key on _id; else on any columns in any directions.
     */
    static String statement(Random r, List<List<String>> defs) {
        List<String> where = new ArrayList<>();
        List<String> order = new ArrayList<>();
        if (r.nextInt(5) > 0) {
            List<String> d = pick(r, defs);
            int fixed = r.nextInt(d.size());
            for (int i = 0; i < fixed; i++) {
                where.add(r.nextInt(3) > 0 ? d.get(i) + " = " + lit(r)
                        : d.get(i) + " IN (" + lit(r) + ", " + lit(r) + (r.nextBoolean() ? ", " + lit(r) : "") + ")");
            }
            boolean desc = r.nextBoolean();
            int ordered = 1 + r.nextInt(d.size() - fixed);
            for (int i = fixed; i < fixed + ordered; i++) {
                order.add(d.get(i) + (desc ? " DESC" : r.nextBoolean() ? " ASC" : ""));
            }
            if (fixed > 0 && r.nextInt(3) == 0) {
                order.add(r.nextInt(order.size() + 1), d.get(r.nextInt(fixed)) + (r.nextBoolean() ? " DESC" : ""));
            }
            if (r.nextInt(3) == 0) {
                String c = d.get(fixed);
                for (int i = 1 + r.nextInt(2); i > 0; i--) {
                    where.add(r.nextInt(5) == 0 ? c + " LIKE " + Values.format(pick(r, PATTERNS))
                            : c + " " + pick(r, List.of("<", "<=", ">", ">=")) + " " + lit(r));
                }
            }
            if (r.nextInt(8) == 0) {
                // Out of the index's order: still the sort's answer.
                java.util.Collections.swap(order, 0, order.size() - 1);
            }
        } else {
            List<String> cols = new ArrayList<>(COLUMNS);
            java.util.Collections.shuffle(cols, r);
            for (String c : cols.subList(0, 1 + r.nextInt(3))) {
                order.add(c + pick(r, List.of("", " ASC", " DESC")));
            }
        }
        for (int i = r.nextInt(4) == 0 ? 1 + r.nextInt(2) : 0; i > 0; i--) {
            // Sometimes a term most rows pass (no index answers !=), so that a read of a share can fill its page.
            where.add(r.nextInt(3) == 0 ? pick(r, COLUMNS) + " != 'nothing'"
                    : r.nextInt(6) == 0 ? "(" + term(r) + " OR " + term(r) + ")" : term(r));
        }
        if (r.nextInt(4) == 0) {
            order.add("_id" + pick(r, List.of("", " ASC", " DESC")));
        }
        java.util.Collections.shuffle(where, r);
        String w = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        String limit = r.nextInt(4) == 0 ? "" : " LIMIT " + pick(r, List.of(0, 1, 2, 3, 5));
        String what = pick(r, List.of("*", "_id", "_id, a, b, c"));
        return "SELECT " + what + " FROM @T" + w + " ORDER BY " + String.join(", ", order) + limit;
    }

    private static Map<String, Object> row(Random r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String c : COLUMNS) {
            Object v = pick(r, STORED);
            if (v != MISSING) {
                m.put(c, v);
            }
        }
        return m;
    }

    /** Rows written by three peers that had not seen each other's writes: siblings, and deletes among them. */
    private static void load(String db, int rows, long seed, long t) throws IOException {
        for (String table : List.of("x", "y")) {
            Random data = new Random(seed);
            for (int i = 0; i < rows; i++) {
                String id = String.format("r%02d", i);
                List<Op> ops = new ArrayList<>();
                ops.add(new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(5), 0, "p1"), Map.of()));
                if (data.nextInt(3) > 0) {
                    ops.add(data.nextInt(4) == 0
                            ? new Op.Delete(db, table, id, new Stamp(t + data.nextInt(5), 0, "p2"), Map.of())
                            : new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(5), 0, "p2"), Map.of()));
                }
                if (data.nextInt(4) == 0) {
                    ops.add(new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(5), 1, "p3"), Map.of()));
                }
                engine.merge(ops);
            }
        }
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    @Property(tries = 400)
    void readingInIndexOrderGivesTheSortsAnswer(@ForAll("seeds") long seed) throws IOException {
        Random r = new Random(seed);
        String db = "o" + RUN.incrementAndGet();
        query.run("CREATE DATABASE " + db);
        query.run("CREATE TABLE " + db + ".x");
        query.run("CREATE TABLE " + db + ".y");
        List<List<String>> defs = new ArrayList<>(DEFINITIONS);
        java.util.Collections.shuffle(defs, r);
        defs = defs.subList(0, 1 + r.nextInt(3));
        boolean before = r.nextBoolean();
        if (before) {
            for (List<String> d : defs) {
                query.run("CREATE INDEX ON " + db + ".x (" + String.join(", ", d) + ")");
            }
        }
        long t = System.currentTimeMillis() + 10_000;
        // Larger tables give a read that the WHERE does not fully answer a share large enough to try.
        int rows = r.nextInt(r.nextInt(3) == 0 ? 1200 : 40);
        load(db, rows, seed, t);
        if (!before) {
            for (List<String> d : defs) {
                query.run("CREATE INDEX ON " + db + ".x (" + String.join(", ", d) + ")");
            }
        }
        for (int round = 0; round < 2; round++) {
            if (round == 1) {
                // Writes that move rows in the indexes: a newer version from p2 on some rows, an UPDATE and a DELETE.
                for (String table : List.of("x", "y")) {
                    Random data = new Random(seed * 31 + 7);
                    for (int i = 0; i < rows; i += 3) {
                        engine.merge(List.of(new Op.Put(db, table, String.format("r%02d", i), row(data),
                                new Stamp(t + 100, 0, "p2"), Map.of())));
                    }
                }
                String set = pick(r, COLUMNS) + " = " + lit(r);
                String w = term(r);
                assertEquals(query.run("UPDATE " + db + ".y SET " + set + " WHERE " + w).toJson(),
                        query.run("UPDATE " + db + ".x SET " + set + " WHERE " + w).toJson());
                String d = term(r);
                assertEquals(query.run("DELETE FROM " + db + ".y WHERE " + d).toJson(),
                        query.run("DELETE FROM " + db + ".x WHERE " + d).toJson());
            }
            for (int i = 0; i < 10; i++) {
                String s = statement(r, defs);
                Statement.Select q = (Statement.Select) Parser.parse(s.replace("@T", db + ".x"));
                Executor.OrderedPlan p = engine.read(st -> Executor.orderedPlan(st, db, "x", q));
                long keep = (q.limit() != null ? q.limit() : CAP) + 1;
                long budget = p == null || p.exact() ? -1 : engine.read(st -> Executor.orderedBudget(st, q, p, keep));
                String read = Executor.readsInIdOrder(q) ? "_id order" : p == null ? "sort"
                        : p.exact() ? "index order, exact" : budget < 0 ? "sort, budget too small" : "index order, budgeted";
                if (budget >= 0) {
                    // Whether the page filled within the budget, or the rows were read again and sorted.
                    int[] n = {0};
                    Store.Ordered how = engine.read(st -> st.forEachInOrder(db, "x", p.index(), p.where(), p.keys(),
                            p.idDescending(), budget, (id, row) -> !Boolean.TRUE.equals(Executor.evaluate(q.where(), id, row))
                                    || ++n[0] < keep));
                    read += how == Store.Ordered.DONE ? ", done" : ", over";
                }
                Statistics.label("read").collect(read);
                Result.Rows want = (Result.Rows) query.run(s.replace("@T", db + ".y"));
                Result.Rows got = (Result.Rows) query.run(s.replace("@T", db + ".x"));
                String why = "seed " + seed + ", indexes " + defs + ": " + s;
                assertEquals(want.columns(), got.columns(), why);
                assertEquals(want.rows(), got.rows(), why);
                assertEquals(want.truncated(), got.truncated(), why);
            }
        }
        query.run("DROP DATABASE " + db);
        Statistics.label("read").coverage(c -> {
            c.check("index order, exact").percentage(pct -> pct > 15);
            c.check("index order, budgeted, done").percentage(pct -> pct > 0.1);
            c.check("index order, budgeted, over").percentage(pct -> pct > 0.5);
            c.check("sort").percentage(pct -> pct > 5);
        });
    }
}
