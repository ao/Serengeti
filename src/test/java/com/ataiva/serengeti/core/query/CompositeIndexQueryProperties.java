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
 * Indexes on several columns change no answer: random rows (nulls, missing values, every type), random
 * indexes on one to three of their columns, and random conditions, mostly ANDs that fix a prefix of an index;
 * SELECT, COUNT(*), a page in _id order, GROUP BY, UPDATE and DELETE give through the indexes what a table
 * without them gives, and so does (w) OR (w), which uses no index.
 */
class CompositeIndexQueryProperties {
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
    /** Stored values: few enough that prefixes repeat, with every type, equal numbers of both kinds, and strings around a prefix. */
    static final List<Object> STORED = Arrays.asList(MISSING, MISSING, null, true, false, 1L, 1L, 1.0, 2L, 2.5, -0.0, 0L,
            9007199254740993L, 9007199254740992.0, "a", "a", "ab", "b", "", "a" + Character.MAX_VALUE,
            Map.of("k", 1L), List.of(1L));
    static final List<Object> LITERALS = Arrays.asList(null, true, false, 1L, 1.0, 2L, 2.5, 0L, -0.0, 3L,
            9007199254740993L, 9007199254740992.0, "a", "ab", "b", "", "z");
    static final List<String> PATTERNS = List.of("a%", "a", "%", "ab%", "_", "a_", "b%", "", "\\%%", "a" + Character.MAX_VALUE + "%");
    static final List<List<String>> DEFINITIONS = List.of(List.of("a", "b"), List.of("b", "a"), List.of("a", "b", "c"),
            List.of("c", "a"), List.of("c", "b", "a"), List.of("a"), List.of("b"));

    @BeforeContainer
    static void open() throws IOException {
        dir = Files.createTempDirectory("composite-index-properties");
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

    private static Result.Rows rows(String q) throws IOException {
        return (Result.Rows) query.run(q);
    }

    private static long affected(String q) throws IOException {
        return ((Result.Write) query.run(q)).affected();
    }

    static <T> T pick(Random r, List<T> from) {
        return from.get(r.nextInt(from.size()));
    }

    /** One term on a column: mostly =, IN and ranges, which indexes answer; sometimes one they do not. */
    static Expr term(Random r) {
        String c = pick(r, COLUMNS);
        switch (r.nextInt(12)) {
            case 0, 1, 2, 3:
                return new Expr.Compare(c, Expr.Cmp.EQ, pick(r, LITERALS));
            case 4, 5: {
                List<Object> vs = new ArrayList<>();
                for (int i = 1 + r.nextInt(4); i > 0; i--) {
                    vs.add(pick(r, LITERALS));
                }
                return new Expr.In(c, vs, r.nextInt(6) == 0);
            }
            case 6, 7, 8:
                return new Expr.Compare(c, pick(r, List.of(Expr.Cmp.LT, Expr.Cmp.LE, Expr.Cmp.GT, Expr.Cmp.GE)),
                        pick(r, LITERALS));
            case 9:
                return new Expr.Like(c, pick(r, PATTERNS), r.nextInt(5) == 0);
            case 10:
                return new Expr.IsNull(c, r.nextBoolean());
            default:
                return new Expr.Compare(c, Expr.Cmp.NE, pick(r, LITERALS));
        }
    }

    static Expr condition(Random r) {
        int k = r.nextInt(10);
        if (k == 0) {
            return term(r);
        }
        List<Expr> terms = new ArrayList<>();
        for (int i = 2 + r.nextInt(3); i > 0; i--) {
            terms.add(term(r));
        }
        Expr and = new Expr.And(terms);
        if (k == 1) {
            return new Expr.Or(List.of(and, term(r)));
        }
        if (k == 2) {
            return new Expr.Not(and);
        }
        return and;
    }

    @Provide
    Arbitrary<List<List<Object>>> data() {
        Arbitrary<List<Object>> row = Arbitraries.of(STORED).list().ofSize(3);
        return row.list().ofMinSize(0).ofMaxSize(60);
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    private static void load(String db, String table, List<List<Object>> data) throws IOException {
        List<Op> puts = new ArrayList<>();
        Stamp at = new Stamp(System.currentTimeMillis() + 1000, 0, "p");
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < COLUMNS.size(); c++) {
                if (data.get(i).get(c) != MISSING) {
                    row.put(COLUMNS.get(c), data.get(i).get(c));
                }
            }
            puts.add(new Op.Put(db, table, String.format("r%02d", i), row, at));
        }
        engine.apply(puts);
    }

    private static List<Object> answers(String table, String w) throws IOException {
        return List.of(rows("SELECT _id, a, b, c FROM " + table + " WHERE " + w).rows(),
                rows("SELECT COUNT(*) FROM " + table + " WHERE " + w).rows(),
                rows("SELECT _id FROM " + table + " WHERE " + w + " ORDER BY _id LIMIT 2").rows(),
                rows("SELECT a, COUNT(*), MAX(c) FROM " + table + " WHERE " + w + " GROUP BY a").rows());
    }

    @Property(tries = 500)
    void indexesOnSeveralColumnsChangeNoAnswer(@ForAll("data") List<List<Object>> data, @ForAll("seeds") long seed)
            throws IOException {
        Random r = new Random(seed);
        String db = "c" + RUN.incrementAndGet();
        query.run("CREATE DATABASE " + db);
        query.run("CREATE TABLE " + db + ".x");
        query.run("CREATE TABLE " + db + ".y");
        load(db, "x", data);
        load(db, "y", data);
        List<List<String>> defs = new ArrayList<>(DEFINITIONS);
        java.util.Collections.shuffle(defs, r);
        defs = defs.subList(0, 1 + r.nextInt(3));
        // Half the indexes are built from the rows, half kept up to date by the writes that follow.
        boolean before = r.nextBoolean();
        if (before) {
            for (List<String> d : defs) {
                query.run("CREATE INDEX ON " + db + ".x (" + String.join(", ", d) + ")");
            }
        }
        String x = db + ".x";
        String y = db + ".y";
        for (int round = 0; round < 2; round++) {
            if (round == 1) {
                // Writes through the indexes, which must move rows in them as on the table without.
                Expr w = condition(r);
                String text = Printer.print(w);
                Object v = pick(r, LITERALS);
                String set = pick(r, COLUMNS) + " = " + Values.format(v);
                assertEquals(affected("UPDATE " + y + " SET " + set + " WHERE " + text),
                        affected("UPDATE " + x + " SET " + set + " WHERE " + text), "UPDATE ... WHERE " + text);
                Expr d = condition(r);
                assertEquals(affected("DELETE FROM " + y + " WHERE " + Printer.print(d)),
                        affected("DELETE FROM " + x + " WHERE " + Printer.print(d)), "DELETE WHERE " + Printer.print(d));
                assertEquals(rows("SELECT * FROM " + y).rows(), rows("SELECT * FROM " + x).rows());
                if (!before) {
                    for (List<String> def : defs) {
                        query.run("CREATE INDEX ON " + x + " (" + String.join(", ", def) + ")");
                    }
                }
            }
            for (int i = 0; i < 8; i++) {
                Expr w = condition(r);
                String text = Printer.print(w);
                Executor.Plan p = engine.read(s -> Executor.cheapestIndexedTerm(s, db, "x", w));
                Statistics.label("plan").collect(p == null ? "scan"
                        : p.lookup() instanceof Store.Lookup.Composite ? "several columns, " + p.named() + " named" : "one column");
                List<Object> want = answers(y, text);
                assertEquals(want, answers(x, text), "through the indexes " + defs + ": " + text);
                assertEquals(want, answers(x, "(" + text + ") OR (" + text + ")"), "OR: " + text);
            }
        }
        query.run("DROP DATABASE " + db);
        Statistics.label("plan").coverage(c -> {
            c.check("several columns, 2 named").percentage(pct -> pct > 5);
            c.check("several columns, 1 named").percentage(pct -> pct > 1);
        });
    }
}
