package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
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
 * ORs read through indexes change no answer. Random rows (nulls, missing values, every type, objects and arrays,
 * -0.0 and the neighbours of 2^53, versions from three peers that had not seen each other's), random indexes on
 * one to three columns, and random conditions with OR at the top, inside AND, and inside the AND branches of an
 * OR, their branches mostly terms and ANDs that an index answers. SELECT, COUNT(*), a page in _id order with and
 * without ORDER BY, a keyset page, UPDATE and DELETE give through the indexes what the same table without them
 * gives, before and after writes that move rows in the indexes. Tables of several hundred rows, most values
 * drawn from a few hundred, so that many unions stay within the share of the table they may read.
 */
class OrReadProperties {
    private static Path dir;
    private static StorageEngine engine;
    private static Query query;
    private static final AtomicInteger RUN = new AtomicInteger();
    private static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "MISSING";
        }
    };
    static final List<String> COLUMNS = List.of("a", "b", "c");
    static final List<Object> SPECIAL = Arrays.asList(MISSING, MISSING, null, true, false, 0L, -0.0, 0.0, 1L, 1.0, 2.5,
            -3L, 9007199254740991L, 9007199254740992L, 9007199254740993L, 9007199254740992.0, 9007199254740994.0, "",
            "a", "ab", "\uFFFF", Map.of("k", 1L), List.of(1L), List.of());
    static final List<Object> LITERALS = Arrays.asList(null, true, false, 0L, -0.0, 1L, 1.0, 2.5, -3L,
            9007199254740992L, 9007199254740993L, 9007199254740992.0, "", "a", "ab", "z");
    static final List<List<String>> DEFINITIONS = List.of(List.of("a"), List.of("b"), List.of("c"), List.of("a", "b"),
            List.of("b", "a"), List.of("a", "b", "c"), List.of("c", "a"));

    @BeforeContainer
    static void open() throws IOException {
        dir = Files.createTempDirectory("or-read-properties");
        engine = StorageEngine.open(dir, FsyncPolicy.OFF);
        query = new Query(engine, new Hlc("n1"));
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

    /** A value of the pool most rows draw from: 300 integers and 150 strings. */
    static Object common(Random r) {
        return r.nextInt(3) > 0 ? (Object) (long) r.nextInt(300) : "s" + r.nextInt(150);
    }

    static Object stored(Random r) {
        return r.nextInt(3) == 0 ? pick(r, SPECIAL) : common(r);
    }

    static Object literal(Random r) {
        return r.nextInt(5) < 2 ? pick(r, LITERALS) : common(r);
    }

    /** One term: mostly =, IN, narrow ranges and LIKE prefixes, which indexes answer; sometimes _id, or one they do not. */
    static Expr term(Random r, int rows) {
        String c = pick(r, COLUMNS);
        switch (r.nextInt(20)) {
            case 0, 1, 2, 3, 4, 5, 6:
                return new Expr.Compare(c, Expr.Cmp.EQ, literal(r));
            case 7, 8, 9: {
                List<Object> vs = new ArrayList<>();
                for (int i = 1 + r.nextInt(3); i > 0; i--) {
                    vs.add(literal(r));
                }
                return new Expr.In(c, vs, r.nextInt(10) == 0);
            }
            case 10, 11: {
                // Narrow ranges at the ends of the pool's integers or strings, or any range of a literal.
                Expr.Cmp op = pick(r, List.of(Expr.Cmp.LT, Expr.Cmp.LE, Expr.Cmp.GT, Expr.Cmp.GE));
                boolean low = op == Expr.Cmp.LT || op == Expr.Cmp.LE;
                Object v = switch (r.nextInt(3)) {
                    case 0 -> (long) (low ? r.nextInt(4) : 296 + r.nextInt(4));
                    case 1 -> low ? "s0" + (r.nextBoolean() ? "" : "0") : "s99";
                    default -> literal(r);
                };
                return new Expr.Compare(c, op, v);
            }
            case 12, 13:
                return new Expr.Like(c, pick(r, List.of("s14%", "s1%", "s149", "a%", "s_", "%9", "\uFFFF%", "")),
                        r.nextInt(8) == 0);
            case 14: {
                String id = String.format("r%04d", r.nextInt(Math.max(1, rows + 2)));
                return r.nextBoolean() ? new Expr.Compare("_id", Expr.Cmp.EQ, id)
                        : new Expr.In("_id", List.of(id, String.format("r%04d", r.nextInt(Math.max(1, rows + 2))), 5L), false);
            }
            case 15:
                return new Expr.Compare("_id", pick(r, List.of(Expr.Cmp.GT, Expr.Cmp.LT)), String.format("r%04d", r.nextInt(Math.max(1, rows))));
            case 16:
                return new Expr.IsNull(c, r.nextBoolean());
            case 17:
                return new Expr.Compare(c, Expr.Cmp.NE, literal(r));
            default: {
                // = on the first columns of an index on several columns, then maybe a range on the next.
                List<String> d = pick(r, DEFINITIONS.subList(3, DEFINITIONS.size()));
                List<Expr> terms = new ArrayList<>();
                int k = 1 + r.nextInt(d.size() - 1);
                for (int i = 0; i < k; i++) {
                    terms.add(new Expr.Compare(d.get(i), Expr.Cmp.EQ, literal(r)));
                }
                if (r.nextBoolean()) {
                    terms.add(new Expr.Compare(d.get(k), pick(r, List.of(Expr.Cmp.LT, Expr.Cmp.GE)), common(r)));
                }
                return terms.size() == 1 ? terms.get(0) : new Expr.And(terms);
            }
        }
    }

    /** A branch of an OR: a term, an AND of terms, or (above the deepest level) an AND with an OR among its terms. */
    static Expr branch(Random r, int rows, int depth) {
        int k = r.nextInt(10);
        if (k < 5) {
            return term(r, rows);
        }
        List<Expr> terms = new ArrayList<>();
        terms.add(term(r, rows));
        terms.add(k == 9 && depth < 2 ? or(r, rows, depth + 1) : term(r, rows));
        if (r.nextInt(3) == 0) {
            terms.add(term(r, rows));
        }
        java.util.Collections.shuffle(terms, r);
        return new Expr.And(terms);
    }

    static Expr or(Random r, int rows, int depth) {
        List<Expr> branches = new ArrayList<>();
        for (int i = 2 + (r.nextInt(3) == 0 ? r.nextInt(3) : 0); i > 0; i--) {
            branches.add(r.nextInt(12) == 0 && depth < 2 ? or(r, rows, depth + 1) : branch(r, rows, depth));
        }
        return new Expr.Or(branches);
    }

    /** OR at the top; as a term of an AND; two of them in an AND. */
    static Expr condition(Random r, int rows) {
        int k = r.nextInt(10);
        if (k < 4) {
            return or(r, rows, 0);
        }
        List<Expr> terms = new ArrayList<>();
        terms.add(or(r, rows, 0));
        terms.add(k < 6 ? or(r, rows, 0) : term(r, rows));
        if (k >= 8) {
            terms.add(term(r, rows));
        }
        java.util.Collections.shuffle(terms, r);
        return new Expr.And(terms);
    }

    private static Map<String, Object> row(Random r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String c : COLUMNS) {
            Object v = stored(r);
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
            List<Op> ops = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                String id = String.format("r%04d", i);
                ops.add(new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(5), 0, "p1"), Map.of()));
                if (data.nextInt(3) == 0) {
                    ops.add(data.nextInt(4) == 0
                            ? new Op.Delete(db, table, id, new Stamp(t + data.nextInt(5), 0, "p2"), Map.of())
                            : new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(5), 0, "p2"), Map.of()));
                }
                if (data.nextInt(6) == 0) {
                    ops.add(new Op.Put(db, table, id, row(data), new Stamp(t + data.nextInt(5), 1, "p3"), Map.of()));
                }
            }
            engine.merge(ops);
        }
    }

    private static Result.Rows rows(String q) throws IOException {
        return (Result.Rows) query.run(q);
    }

    private static List<Object> answers(String table, String w, Random r, int n) throws IOException {
        int limit = r.nextInt(6);
        String after = String.format("r%04d", r.nextInt(Math.max(1, n)));
        return List.of(rows("SELECT _id, a, b, c FROM " + table + " WHERE " + w).rows(),
                rows("SELECT COUNT(*) FROM " + table + " WHERE " + w).rows(),
                rows("SELECT _id FROM " + table + " WHERE " + w + " ORDER BY _id LIMIT " + limit).rows(),
                rows("SELECT * FROM " + table + " WHERE " + w + " LIMIT " + limit).rows(),
                rows("SELECT _id, b FROM " + table + " WHERE _id > '" + after + "' AND (" + w + ") ORDER BY _id LIMIT 3").rows());
    }

    /** How {@code w} is read on {@code db}.x, for the statistics. */
    private static String how(String db, String text) {
        Expr w = ((Statement.Select) Parser.parse("SELECT * FROM " + db + ".x WHERE " + text)).where();
        Executor.Plan p = engine.read(s -> Executor.cheapestIndexedTerm(s, db, "x", w));
        return p == null ? "scan" : !p.isUnion() ? "one read" : w instanceof Expr.Or ? "union" : "union in AND";
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    @Property(tries = 250)
    void orsReadThroughIndexesChangeNoAnswer(@ForAll("seeds") long seed) throws IOException {
        Random r = new Random(seed);
        String db = "o" + RUN.incrementAndGet();
        query.run("CREATE DATABASE " + db);
        query.run("CREATE TABLE " + db + ".x");
        query.run("CREATE TABLE " + db + ".y");
        List<List<String>> defs = new ArrayList<>(DEFINITIONS);
        java.util.Collections.shuffle(defs, r);
        defs = defs.subList(0, 1 + r.nextInt(4));
        // Half the indexes are built from the rows, half kept up to date by the writes that follow.
        boolean before = r.nextBoolean();
        if (before) {
            for (List<String> d : defs) {
                query.run("CREATE INDEX ON " + db + ".x (" + String.join(", ", d) + ")");
            }
        }
        long t = System.currentTimeMillis() + 10_000;
        int n = r.nextInt(5) == 0 ? r.nextInt(40) : 300 + r.nextInt(1200);
        load(db, n, seed, t);
        if (!before) {
            for (List<String> d : defs) {
                query.run("CREATE INDEX ON " + db + ".x (" + String.join(", ", d) + ")");
            }
        }
        String x = db + ".x";
        String y = db + ".y";
        for (int round = 0; round < 2; round++) {
            if (round == 1) {
                // Writes that move rows in the indexes: newer versions from p2, then an UPDATE and a DELETE
                // through ORs.
                for (String table : List.of("x", "y")) {
                    Random data = new Random(seed * 31 + 7);
                    List<Op> ops = new ArrayList<>();
                    for (int i = 0; i < n; i += 3) {
                        ops.add(new Op.Put(db, table, String.format("r%04d", i), row(data), new Stamp(t + 100, 0, "p2"), Map.of()));
                    }
                    engine.merge(ops);
                }
                String u = Printer.print(condition(r, n));
                String set = pick(r, COLUMNS) + " = " + Values.format(literal(r));
                Statistics.label("write").collect(how(db, u));
                assertEquals(query.run("UPDATE " + y + " SET " + set + " WHERE " + u).toJson(),
                        query.run("UPDATE " + x + " SET " + set + " WHERE " + u).toJson(), "UPDATE ... WHERE " + u);
                String d = Printer.print(condition(r, n));
                Statistics.label("write").collect(how(db, d));
                assertEquals(query.run("DELETE FROM " + y + " WHERE " + d).toJson(),
                        query.run("DELETE FROM " + x + " WHERE " + d).toJson(), "DELETE ... WHERE " + d);
                assertEquals(rows("SELECT * FROM " + y).rows(), rows("SELECT * FROM " + x).rows());
            }
            for (int i = 0; i < 8; i++) {
                String w = Printer.print(condition(r, n));
                Statistics.label("read").collect(how(db, w));
                long s = r.nextLong();
                assertEquals(answers(y, w, new Random(s), n), answers(x, w, new Random(s), n),
                        "seed " + seed + ", indexes " + defs + ", " + n + " rows: " + w);
            }
        }
        query.run("DROP DATABASE " + db);
        Statistics.label("read").coverage(c -> {
            c.check("union").percentage(pct -> pct > 10);
            c.check("union in AND").percentage(pct -> pct > 5);
            c.check("scan").percentage(pct -> pct > 5);
            c.check("one read").percentage(pct -> pct > 2);
        });
    }
}
