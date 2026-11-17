package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * {@code LIMIT n OFFSET m} gives rows m to m + n of the same statement without them, and without LIMIT the
 * {@link #CAP} rows after m, with truncated true when more follow. Random rows (every type, missing values,
 * versions from three peers that had not seen each other's, deletes), random indexes on one to three columns, and
 * random statements of every form: plain, ORDER BY (read in an index's order, in _id order, or sorted), ORs that
 * may be read as a union, SELECT DISTINCT, GROUP BY with HAVING and ORDER BY, and aggregates without GROUP BY.
 * The full answer comes from an executor whose cap no answer reaches, the pages from one with a cap of 7.
 */
class OffsetProperties {
    private static Path dir;
    private static StorageEngine engine;
    private static Query full;
    private static Query paged;
    private static final AtomicInteger RUN = new AtomicInteger();
    static final int CAP = 7;

    @BeforeContainer
    static void open() throws IOException {
        dir = Files.createTempDirectory("offset-properties");
        engine = StorageEngine.open(dir, FsyncPolicy.OFF);
        full = new Query(new Executor(engine, new Hlc("n1"), Executor.MAX_OFFSET_ROWS));
        paged = new Query(new Executor(engine, new Hlc("n2"), CAP));
    }

    @AfterContainer
    static void close() throws IOException {
        engine.close();
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static <T> T pick(Random r, List<T> from) {
        return OrderedReadProperties.pick(r, from);
    }

    private static String lit(Random r) {
        return Values.format(pick(r, OrderedReadProperties.LITERALS));
    }

    private static String where(Random r) {
        if (r.nextInt(3) == 0) {
            return "";
        }
        List<String> terms = new ArrayList<>();
        for (int i = 1 + r.nextInt(2); i > 0; i--) {
            String c = pick(r, OrderedReadProperties.COLUMNS);
            terms.add(r.nextInt(5) == 0 ? "(" + c + " = " + lit(r) + " OR " + pick(r, OrderedReadProperties.COLUMNS)
                    + " IN (" + lit(r) + ", " + lit(r) + "))" : OrderedReadProperties.term(r));
        }
        return " WHERE " + String.join(" AND ", terms);
    }

    /** The keys of an ORDER BY on {@code keys} (each a column or an aggregate), 1 to 3 of them, any directions. */
    private static String order(Random r, List<String> keys) {
        List<String> k = new ArrayList<>(keys);
        java.util.Collections.shuffle(k, r);
        List<String> out = new ArrayList<>();
        for (String key : k.subList(0, 1 + r.nextInt(Math.min(3, k.size())))) {
            out.add(key + pick(r, List.of("", " ASC", " DESC")));
        }
        return " ORDER BY " + String.join(", ", out);
    }

    /** A statement without LIMIT or OFFSET, and its form. */
    static String[] statement(Random r, List<List<String>> defs) {
        List<String> cols = new ArrayList<>(OrderedReadProperties.COLUMNS);
        java.util.Collections.shuffle(cols, r);
        List<String> some = cols.subList(0, 1 + r.nextInt(2));
        String list = String.join(", ", some);
        String agg = pick(r, List.of("COUNT(*)", "SUM(a)", "MIN(b)", "MAX(c)", "AVG(a)", "COUNT(DISTINCT b)"));
        String having = pick(r, List.of("COUNT(*) > 1", "MIN(b) IS NOT NULL", "COUNT(*) < 3", "SUM(a) >= 1"));
        return switch (r.nextInt(9)) {
            case 0 -> new String[] {"SELECT " + pick(r, List.of("*", "_id", "_id, a, b, c")) + " FROM @T" + where(r), "plain"};
            case 1, 2 -> new String[] {OrderedReadProperties.statement(r, defs).replaceFirst(" LIMIT \\d+$", ""), "ordered"};
            case 3 -> new String[] {"SELECT " + pick(r, List.of("*", "_id, a")) + " FROM @T" + where(r)
                    + pick(r, List.of(" ORDER BY _id", " ORDER BY _id DESC", " ORDER BY a, _id DESC", "")), "id order"};
            case 4 -> new String[] {"SELECT _id, a, b FROM @T WHERE " + cols.get(0) + " = " + lit(r) + " OR "
                    + cols.get(1) + " IN (" + lit(r) + ", " + lit(r) + ")" + (r.nextBoolean() ? "" : order(r, cols)),
                    "or"};
            case 5 -> new String[] {"SELECT DISTINCT " + list + " FROM @T" + where(r)
                    + (r.nextBoolean() ? "" : order(r, some)), "distinct"};
            case 6, 7 -> {
                List<String> keys = new ArrayList<>(some);
                keys.add(agg);
                yield new String[] {"SELECT " + list + ", " + agg + " FROM @T" + where(r) + " GROUP BY " + list
                        + (r.nextBoolean() ? " HAVING " + having : "") + (r.nextInt(3) == 0 ? "" : order(r, keys)),
                        "group by"};
            }
            default -> new String[] {"SELECT " + agg + (r.nextBoolean() && !agg.equals("COUNT(*)") ? ", COUNT(*)" : "")
                    + " FROM @T" + where(r)
                    + (r.nextBoolean() ? " HAVING " + having : ""), "aggregates"};
        };
    }

    private static Map<String, Object> row(Random r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String c : OrderedReadProperties.COLUMNS) {
            Object v = pick(r, OrderedReadProperties.STORED);
            if (v != OrderedReadProperties.MISSING) {
                m.put(c, v);
            }
        }
        return m;
    }

    /** Rows written by three peers that had not seen each other's writes: siblings, and deletes among them. */
    private static void load(String db, int rows, long seed) throws IOException {
        long t = System.currentTimeMillis() + 10_000;
        Random data = new Random(seed);
        for (int i = 0; i < rows; i++) {
            String id = String.format("r%03d", i);
            List<Op> ops = new ArrayList<>();
            ops.add(new Op.Put(db, "x", id, row(data), new Stamp(t + data.nextInt(5), 0, "p1"), Map.of()));
            if (data.nextInt(3) > 0) {
                ops.add(data.nextInt(4) == 0
                        ? new Op.Delete(db, "x", id, new Stamp(t + data.nextInt(5), 0, "p2"), Map.of())
                        : new Op.Put(db, "x", id, row(data), new Stamp(t + data.nextInt(5), 0, "p2"), Map.of()));
            }
            if (data.nextInt(4) == 0) {
                ops.add(new Op.Put(db, "x", id, row(data), new Stamp(t + data.nextInt(5), 1, "p3"), Map.of()));
            }
            engine.merge(ops);
        }
    }

    /**
     * The rows of {@code r} as the columns of {@code columns} hold them. A SELECT * lists the columns its rows
     * have, so a page may list fewer than the full answer; a column it lacks is null in each of its rows.
     */
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

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    @Property(tries = 300)
    void aPageIsTheSliceOfTheFullAnswer(@ForAll("seeds") long seed) throws IOException {
        Random r = new Random(seed);
        String db = "o" + RUN.incrementAndGet();
        full.run("CREATE DATABASE " + db);
        full.run("CREATE TABLE " + db + ".x");
        List<List<String>> defs = new ArrayList<>(OrderedReadProperties.DEFINITIONS);
        java.util.Collections.shuffle(defs, r);
        List<List<String>> indexes = defs.subList(0, r.nextInt(4));
        for (List<String> d : indexes) {
            full.run("CREATE INDEX ON " + db + ".x (" + String.join(", ", d) + ")");
        }
        // The ORDER BY forms are built on these; without indexes they are sorted.
        defs = indexes.isEmpty() ? List.of(List.of("a"), List.of("b", "a")) : indexes;
        // Larger tables give an ordered read that the WHERE does not fully answer a share large enough to try.
        int rows = r.nextInt(r.nextInt(3) == 0 ? 1200 : 40);
        load(db, rows, seed);
        for (int i = 0; i < 10; i++) {
            String[] st = statement(r, defs);
            String s = st[0].replace("@T", db + ".x");
            Result.Rows all = (Result.Rows) full.run(s);
            int size = all.rows().size();
            for (int j = 0; j < 3; j++) {
                long m = r.nextInt(4) == 0 ? 0 : r.nextInt(size + 3);
                Long n = r.nextInt(3) == 0 ? null : Long.valueOf(r.nextInt(CAP + 1));
                String page = s + (n == null ? "" : " LIMIT " + n) + (n != null && m == 0 && r.nextBoolean() ? "" : " OFFSET " + m);
                Result.Rows got = (Result.Rows) paged.run(page);
                long take = n == null ? CAP : n;
                int from = (int) Math.min(m, size);
                List<List<Object>> want = all.rows().subList(from, (int) Math.min(size, from + take));
                String why = "seed " + seed + ", indexes " + indexes + ", " + size + " rows: " + page;
                if (s.startsWith("SELECT * ")) {
                    assertEquals(want, project(got, all.columns()), why);
                    assertEquals(Executor.ID, got.columns().get(0), why);
                    assertTrue(all.columns().containsAll(got.columns()), why);
                } else {
                    assertEquals(all.columns(), got.columns(), why);
                    assertEquals(want, got.rows(), why);
                }
                assertEquals(n == null && size > m + CAP, got.truncated(), why);
                Statistics.label("form").collect(st[1]);
                Statistics.label("page").collect(m >= size && size > 0 ? "past the end" : want.isEmpty() ? "empty"
                        : n == null && size > m + CAP ? "truncated" : "rows");
            }
        }
        full.run("DROP DATABASE " + db);
        Statistics.label("form").coverage(c -> {
            for (String f : List.of("plain", "ordered", "id order", "or", "distinct", "group by", "aggregates")) {
                c.check(f).percentage(p -> p > 3);
            }
        });
        Statistics.label("page").coverage(c -> {
            c.check("truncated").percentage(p -> p > 2);
            c.check("past the end").percentage(p -> p > 2);
            c.check("rows").percentage(p -> p > 20);
        });
    }
}
