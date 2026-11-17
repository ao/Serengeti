package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * From review 22g: SELECT ... ORDER BY on a table with indexes (x) against the same rows without them (y), with what
 * OrderedReadProperties leaves out: indexes on 4 columns, index ops from peers with older and newer stamps (a
 * create that loses to a drop, a drop that loses to a create), DROP TABLE from a peer with rows newer than it,
 * a reopen, UPDATE ... SET c = c + n on indexed columns, IN lists of one value, duplicates and NULL, LIMIT 0,
 * _id keys anywhere, and more values (long strings, surrogates, Long.MIN_VALUE and MAX_VALUE, 1e300).
 */
class OrderedReadDiffTest {
    @TempDir
    Path dir;

    static final int CAP = 7;
    static final Object MISSING = new Object();
    static final String FF = String.valueOf((char) 0xFFFF);
    static final String SMILE = new String(Character.toChars(0x1F600));
    static final String HIGH = String.valueOf((char) 0xD83D);
    static final List<String> COLUMNS = List.of("a", "b", "c", "d");
    static final List<Object> STORED = Arrays.asList(MISSING, MISSING, null, true, false, 0L, -0.0, 0.0, 1L, 1.0, 2.5,
            -3L, 9007199254740992L, 9007199254740993L, 9007199254740992.0, Long.MAX_VALUE, Long.MIN_VALUE, 1e300, -1e300,
            "", "a", "a", "ab", "b", FF, SMILE, FF + FF, "a".repeat(80), Map.of("k", 1L),
            List.of(1L), List.of());
    static final List<Object> LITERALS = Arrays.asList(null, true, false, 0L, -0.0, 1L, 1.0, 2.5, -3L,
            9007199254740993L, 9007199254740992.0, Long.MAX_VALUE, Long.MIN_VALUE, 1e300, "", "a", "ab", "b", "z",
            FF, SMILE);
    static final List<String> PATTERNS = List.of("a%", "a", "%", "ab%", "_", "b%", FF + "%", "a\\%", HIGH + "%");
    static final List<List<String>> DEFINITIONS = List.of(List.of("a"), List.of("b"), List.of("d"), List.of("a", "b"),
            List.of("b", "a"), List.of("a", "b", "c"), List.of("a", "b", "c", "d"), List.of("d", "c", "b", "a"),
            List.of("c", "a"), List.of("b", "d"));

    static <T> T pick(Random r, List<T> from) {
        return from.get(r.nextInt(from.size()));
    }

    static String lit(Random r) {
        return Values.format(pick(r, LITERALS));
    }

    static String inList(Random r) {
        List<String> v = new ArrayList<>();
        for (int i = r.nextInt(4); i >= 0; i--) {
            v.add(lit(r));
        }
        if (r.nextInt(4) == 0) {
            v.add(v.get(0));
        }
        return "(" + String.join(", ", v) + ")";
    }

    static String term(Random r) {
        String c = pick(r, COLUMNS);
        return switch (r.nextInt(9)) {
            case 0, 1 -> c + " = " + lit(r);
            case 2 -> c + (r.nextInt(5) == 0 ? " NOT" : "") + " IN " + inList(r);
            case 3, 4 -> c + " " + pick(r, List.of("<", "<=", ">", ">=")) + " " + lit(r);
            case 5 -> c + (r.nextInt(4) == 0 ? " NOT" : "") + " LIKE " + Values.format(pick(r, PATTERNS));
            case 6 -> c + (r.nextBoolean() ? " IS NULL" : " IS NOT NULL");
            case 7 -> "_id " + pick(r, List.of("<", ">=", "=")) + " 'r" + r.nextInt(40) + "'";
            default -> c + " != " + lit(r);
        };
    }

    static String statement(Random r, List<List<String>> defs) {
        List<String> where = new ArrayList<>();
        List<String> order = new ArrayList<>();
        if (r.nextInt(6) > 0 && !defs.isEmpty()) {
            List<String> d = pick(r, defs);
            int fixed = r.nextInt(d.size());
            for (int i = 0; i < fixed; i++) {
                where.add(switch (r.nextInt(4)) {
                    case 0 -> d.get(i) + " IN " + inList(r);
                    case 1 -> d.get(i) + " LIKE " + Values.format(pick(r, List.of("a", "ab", "", FF, "a\\%")));
                    default -> d.get(i) + " = " + lit(r);
                });
            }
            boolean desc = r.nextBoolean();
            int ordered = r.nextInt(5) == 0 ? 0 : 1 + r.nextInt(d.size() - fixed);
            for (int i = fixed; i < fixed + ordered; i++) {
                order.add(d.get(i) + (desc ? " DESC" : r.nextBoolean() ? " ASC" : ""));
            }
            for (int i = r.nextInt(3); i > 0 && fixed > 0; i--) {
                order.add(r.nextInt(order.size() + 1), d.get(r.nextInt(fixed)) + (r.nextBoolean() ? " DESC" : ""));
            }
            if (fixed < d.size() && r.nextInt(3) == 0) {
                String c = d.get(fixed);
                for (int i = 1 + r.nextInt(2); i > 0; i--) {
                    where.add(r.nextInt(5) == 0 ? c + " LIKE " + Values.format(pick(r, PATTERNS))
                            : c + " " + pick(r, List.of("<", "<=", ">", ">=")) + " " + lit(r));
                }
            }
        }
        if (order.isEmpty() || r.nextInt(7) == 0) {
            List<String> cols = new ArrayList<>(COLUMNS);
            Collections.shuffle(cols, r);
            for (String c : cols.subList(0, 1 + r.nextInt(3))) {
                order.add(c + pick(r, List.of("", " ASC", " DESC")));
            }
        }
        for (int i = r.nextInt(3) == 0 ? 1 + r.nextInt(2) : 0; i > 0; i--) {
            where.add(r.nextInt(3) == 0 ? pick(r, COLUMNS) + " != 'nothing'"
                    : r.nextInt(6) == 0 ? "(" + term(r) + " OR " + term(r) + ")" : term(r));
        }
        if (r.nextInt(4) == 0) {
            order.add(r.nextInt(order.size() + 1), "_id" + pick(r, List.of("", " ASC", " DESC")));
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        order.removeIf(k -> !seen.add(k.split(" ")[0]));
        Collections.shuffle(where, r);
        String w = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        String limit = r.nextInt(4) == 0 ? "" : " LIMIT " + pick(r, List.of(0, 1, 2, 3, 7));
        String what = pick(r, List.of("*", "_id", "_id, a, b, c, d"));
        return "SELECT " + what + " FROM @T" + w + " ORDER BY " + String.join(", ", order) + limit;
    }

    static Map<String, Object> row(Random r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String c : COLUMNS) {
            Object v = pick(r, STORED);
            if (v != MISSING) {
                m.put(c, v);
            }
        }
        return m;
    }

    private StorageEngine engine;
    private Query query;
    private static final Map<String, Integer> PLANS = new TreeMap<>();

    private void open() throws IOException {
        engine = StorageEngine.open(dir, FsyncPolicy.OFF);
        query = new Query(new Executor(engine, new Hlc("n1"), CAP));
    }

    private void compare(Random r, String db, List<List<String>> defs, int n, long seed, String when) throws IOException {
        for (int i = 0; i < n; i++) {
            String s = statement(r, defs);
            String why = "seed " + seed + " " + when + ", indexes " + defs + ": " + s;
            String read;
            try {
                Statement.Select q = (Statement.Select) Parser.parse(s.replace("@T", db + ".x"));
                Executor.OrderedPlan p = engine.read(st -> Executor.orderedPlan(st, db, "x", q));
                long keep = (q.limit() != null ? q.limit() : CAP) + 1;
                long budget = p == null ? -1 : engine.read(st -> Executor.orderedBudget(st, q, p, keep));
                read = Executor.readsInIdOrder(q) ? "id order" : p == null ? "sort" : p.exact() ? "exact"
                        : budget < 0 ? "sort, small budget" : "budgeted";
                if (budget >= 0) {
                    int[] m = {0};
                    read += " " + engine.read(st -> st.forEachInOrder(db, "x", p.index(), p.where(), p.keys(),
                            p.idDescending(), budget, (id, row) -> q.where() != null && !Boolean.TRUE.equals(Executor.evaluate(q.where(), id, row))
                                    || ++m[0] < keep));
                }
            } catch (RuntimeException e) {
                read = "error " + e.getClass().getSimpleName();
                if (PLANS.getOrDefault(read, 0) < 3) {
                    System.out.println("R22G " + read + ": " + e.getMessage() + " in " + s);
                }
            }
            PLANS.merge(when.startsWith("after a reopen") ? "reopen: " + read : read, 1, Integer::sum);
            Object want;
            Object got;
            try {
                want = query.run(s.replace("@T", db + ".y")).toJson();
            } catch (RuntimeException e) {
                want = e.toString();
            }
            try {
                got = query.run(s.replace("@T", db + ".x")).toJson();
            } catch (RuntimeException e) {
                got = e.toString();
            }
            assertEquals(want, got, why);
        }
    }

    @Test
    void orderedReadsAnswerAsTheSortWithIndexOpsFromPeersDropsAndUpdates() throws IOException {
        open();
        int runs = Integer.getInteger("ordered.diff.runs", 250);
        Map<String, Integer> statements = new TreeMap<>();
        for (int run = 0; run < runs; run++) {
            long seed = 1000L + run;
            Random r = new Random(seed);
            String db = "o" + run;
            query.run("CREATE DATABASE " + db);
            query.run("CREATE TABLE " + db + ".x");
            query.run("CREATE TABLE " + db + ".y");
            long t = System.currentTimeMillis() + 100_000;
            List<List<String>> all = new ArrayList<>(DEFINITIONS);
            Collections.shuffle(all, r);
            List<List<String>> defs = new ArrayList<>(all.subList(0, 1 + r.nextInt(4)));
            // Index ops as a peer sends them: some created before the rows, some after; one created by a peer
            // with an older stamp than a drop here (it must not exist), one dropped with an older stamp than its
            // create (it must still exist).
            for (List<String> d : defs) {
                engine.merge(List.of(new Op.CreateIndex(db, "x", Op.indexKey(d), new Stamp(t + r.nextInt(3), 0, "p1"))));
            }
            List<String> lost = all.get(all.size() - 1);
            if (!defs.contains(lost)) {
                engine.merge(List.of(new Op.DropIndex(db, "x", Op.indexKey(lost), new Stamp(t + 50, 0, "p2")),
                        new Op.CreateIndex(db, "x", Op.indexKey(lost), new Stamp(t + 40, 0, "p3"))));
            }
            engine.merge(List.of(new Op.DropIndex(db, "x", Op.indexKey(defs.get(0)), new Stamp(t - 5, 0, "p2"))));
            int rows = r.nextInt(r.nextInt(3) == 0 ? 1500 : 60);
            for (String table : List.of("x", "y")) {
                Random data = new Random(seed * 7);
                for (int i = 0; i < rows; i++) {
                    String id = "r" + i;
                    List<Op> ops = new ArrayList<>();
                    ops.add(new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(20), 0, "p1"), Map.of()));
                    if (data.nextInt(3) > 0) {
                        ops.add(data.nextInt(4) == 0
                                ? new Op.Delete(db, table, id, new Stamp(t + data.nextInt(20), 0, "p2"), Map.of())
                                : new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(20), 0, "p2"), Map.of()));
                    }
                    engine.merge(ops);
                }
            }
            compare(r, db, defs, 12, seed, "after load");
            // An increment on a column that may be indexed, where it can be computed on every row.
            String c = pick(r, COLUMNS);
            String inc = "UPDATE " + db + ".@T SET " + c + " = " + c + (r.nextBoolean() ? " + 1" : " - 0.5")
                    + " WHERE " + c + " > -100 AND " + c + " < 100";
            Object wy;
            Object wx;
            try {
                wy = query.run(inc.replace("@T", "y")).toJson();
            } catch (RuntimeException e) {
                wy = e.toString();
            }
            try {
                wx = query.run(inc.replace("@T", "x")).toJson();
            } catch (RuntimeException e) {
                wx = e.toString();
            }
            assertEquals(wy, wx, inc);
            // The table dropped by a peer at a stamp among the rows': the newer rows stay, the indexes go (a drop
            // of the table drops its indexes), then one index is created again.
            if (r.nextInt(3) == 0) {
                for (String table : List.of("x", "y")) {
                    engine.merge(List.of(new Op.DropTable(db, table, new Stamp(t + 10, 0, "p3")),
                            new Op.CreateTable(db, table, new Stamp(t + 11, 0, "p3"))));
                }
                List<String> again = pick(r, defs);
                engine.merge(List.of(new Op.CreateIndex(db, "x", Op.indexKey(again), new Stamp(t + 12, 0, "p3"))));
                compare(r, db, List.of(again), 8, seed, "after a table drop");
            } else {
                compare(r, db, defs, 8, seed, "after the update");
            }
            statements.merge("runs", 1, Integer::sum);
        }
        engine.close();
        // The indexes are rebuilt from the files at start: the same answers after a reopen.
        open();
        for (int run = 0; run < runs; run += 10) {
            Random r = new Random(1000L + run);
            List<List<String>> all = new ArrayList<>(DEFINITIONS);
            compare(r, "o" + run, all, 6, 1000L + run, "after a reopen");
        }
        engine.close();
        System.out.println("R22G ordered diff " + statements + " " + PLANS);
    }
}
