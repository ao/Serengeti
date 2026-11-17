package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Store;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What CompositeIndexQueryProperties does not reach (from review 22f). Four columns (indexes on up to four),
 * conflicting versions from two peers (concurrent puts and deletes, so rows hold siblings and the winner
 * moves), surrogate pairs and lone surrogates around LIKE prefixes, and IN lists long enough that their
 * product passes the 1,000-tuple cap. A table with indexes must answer as the same table without.
 */
class CompositeIndexDiffTest {
    @TempDir
    Path tmp;

    static final List<String> COLUMNS = List.of("a", "b", "c", "d");
    static final Object MISSING = new Object();
    static final List<Object> STORED = Arrays.asList(MISSING, null, true, false, 1L, 1.0, 2L, 2.5, -0.0, 0L,
            9007199254740993L, 9007199254740992.0, -3L, "a", "ab", "", "\uD83D\uDE00", "\uD83D", "a\uD83D\uDE00",
            "\uFFFF", "a\uFFFF", "\uD83D\uDE01x", Map.of("k", 1L), List.of(1L));
    static final List<Object> LITERALS = Arrays.asList(null, true, false, 1L, 1.0, 2L, 2.5, 0L, -0.0, -3L, 3L,
            9007199254740993L, 9007199254740992.0, "a", "ab", "", "\uD83D\uDE00", "\uD83D", "\uFFFF", "z");
    static final List<String> PATTERNS = List.of("a%", "\uD83D%", "\uD83D\uDE00%", "\uFFFF%", "a\uD83D%", "%", "_%",
            "a", "\uD83D", "", "\\%%");
    static final List<List<String>> DEFS = List.of(List.of("a", "b"), List.of("b", "a"), List.of("a", "b", "c", "d"),
            List.of("d", "c", "b", "a"), List.of("c", "a", "d"), List.of("b", "d"), List.of("a"), List.of("c"));

    static <T> T pick(Random r, List<T> from) {
        return from.get(r.nextInt(from.size()));
    }

    static Expr term(Random r) {
        String c = pick(r, COLUMNS);
        switch (r.nextInt(12)) {
            case 0, 1, 2, 3:
                return new Expr.Compare(c, Expr.Cmp.EQ, pick(r, LITERALS));
            case 4, 5: {
                List<Object> vs = new ArrayList<>();
                int n = r.nextInt(4) == 0 ? 35 + r.nextInt(10) : 1 + r.nextInt(4);
                for (int i = 0; i < n; i++) {
                    vs.add(i < LITERALS.size() && n > 20 ? LITERALS.get(i) : n > 20 ? (long) (100 + i) : pick(r, LITERALS));
                }
                return new Expr.In(c, vs, r.nextInt(8) == 0);
            }
            case 6, 7, 8:
                return new Expr.Compare(c, pick(r, List.of(Expr.Cmp.LT, Expr.Cmp.LE, Expr.Cmp.GT, Expr.Cmp.GE)),
                        pick(r, LITERALS));
            case 9, 10:
                return new Expr.Like(c, pick(r, PATTERNS), r.nextInt(6) == 0);
            default:
                return new Expr.IsNull(c, r.nextBoolean());
        }
    }

    static Expr condition(Random r) {
        if (r.nextInt(8) == 0) {
            return term(r);
        }
        List<Expr> terms = new ArrayList<>();
        for (int i = 2 + r.nextInt(4); i > 0; i--) {
            terms.add(term(r));
        }
        return new Expr.And(terms);
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

    private static List<Object> answers(Query q, String table, String w) throws Exception {
        return List.of(((Result.Rows) q.run("SELECT _id, a, b, c, d FROM " + table + " WHERE " + w)).rows(),
                ((Result.Rows) q.run("SELECT COUNT(*) FROM " + table + " WHERE " + w)).rows(),
                ((Result.Rows) q.run("SELECT _id FROM " + table + " WHERE " + w + " ORDER BY _id LIMIT 3")).rows());
    }

    @Test
    void indexesOnUpToFourColumnsWithConflictsChangeNoAnswer() throws Exception {
        TreeMap<String, Integer> plans = new TreeMap<>();
        try (StorageEngine e = StorageEngine.open(tmp.resolve("d"), FsyncPolicy.OFF)) {
            Query q = new Query(e, new Hlc("n1"));
            for (int run = 0; run < 400; run++) {
                Random r = new Random(7_000 + run);
                String db = "z" + run;
                q.run("CREATE DATABASE " + db);
                q.run("CREATE TABLE " + db + ".x");
                q.run("CREATE TABLE " + db + ".y");
                List<List<String>> defs = new ArrayList<>(DEFS);
                java.util.Collections.shuffle(defs, r);
                defs = defs.subList(0, 1 + r.nextInt(4));
                boolean before = r.nextBoolean();
                if (before) {
                    for (List<String> d : defs) {
                        q.run("CREATE INDEX ON " + db + ".x (" + String.join(", ", d) + ")");
                    }
                }
                long t = System.currentTimeMillis() + 10_000;
                int rows = r.nextInt(50);
                for (String table : List.of("x", "y")) {
                    Random data = new Random(run);
                    for (int i = 0; i < rows; i++) {
                        String id = String.format("r%02d", i);
                        // Two peers that had not seen each other's writes: siblings, the newer stamp wins.
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
                        e.merge(ops);
                    }
                }
                if (!before) {
                    for (List<String> d : defs) {
                        q.run("CREATE INDEX ON " + db + ".x (" + String.join(", ", d) + ")");
                    }
                }
                for (int i = 0; i < 12; i++) {
                    Expr w = condition(r);
                    String text = Printer.print(w);
                    Executor.Plan p = e.read(s -> Executor.cheapestIndexedTerm(s, db, "x", w));
                    plans.merge(p == null ? "scan" : p.lookup() instanceof Store.Lookup.Composite c
                            ? "composite " + c.named() + " named" + (c.equal().isEmpty() ? "" : ", " + c.equal().size() + " fixed")
                            : "one column", 1, Integer::sum);
                    assertEquals(answers(q, db + ".y", text), answers(q, db + ".x", text),
                            "seed " + run + ", indexes " + defs + ": " + text);
                }
                // A write that moves winners: a newer put from p2 on half the rows, through both tables.
                for (String table : List.of("x", "y")) {
                    Random data = new Random(run * 31L + 5);
                    for (int i = 0; i < rows; i += 2) {
                        e.merge(List.of(new Op.Put(db, table, String.format("r%02d", i), row(data),
                                new Stamp(t + 100, 0, "p2"), Map.of())));
                    }
                }
                for (int i = 0; i < 6; i++) {
                    Expr w = condition(r);
                    String text = Printer.print(w);
                    assertEquals(answers(q, db + ".y", text), answers(q, db + ".x", text),
                            "after moves, seed " + run + ", indexes " + defs + ": " + text);
                }
                q.run("DROP DATABASE " + db);
            }
        }
        System.out.println("composite diff plans: " + plans);
    }
}
