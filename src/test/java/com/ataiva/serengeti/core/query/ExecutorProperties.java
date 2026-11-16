package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.ScalarOrder;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

/**
 * The executor against models written from docs/QUERY.md: IN and LIKE give the same rows through an index,
 * through _id, and by scan, and they are the rows the rules select; GROUP BY gives what a naive grouping of
 * the rows, in _id order, gives.
 */
class ExecutorProperties {
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

    @BeforeContainer
    static void open() throws IOException {
        dir = Files.createTempDirectory("executor-properties");
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

    private static List<String> ids(String q) throws IOException {
        List<String> out = new ArrayList<>();
        for (List<Object> r : rows(q).rows()) {
            out.add((String) r.get(0));
        }
        return out;
    }

    // IN and LIKE: index, _id and scan.

    static final String TOP = String.valueOf(Character.MAX_VALUE);
    static final String EMOJI = new String(Character.toChars(0x1F600));
    static final List<Object> POOL = Arrays.asList(MISSING, null, true, false, -1L, 0L, 1L, 2L, 1.0, -0.0, 2.5,
            9007199254740993L, 9007199254740992.0, "", "a", "ab", "abc", "b", "B", "a%", "a_b", "a\\b", "%", "_", "'",
            TOP, "a" + TOP, TOP + "a", EMOJI, EMOJI + "a", "ab" + EMOJI);

    @Provide
    Arbitrary<List<Object>> columnValues() {
        return Arbitraries.of(POOL).list().ofMinSize(0).ofMaxSize(40);
    }

    private static Arbitrary<String> patterns() {
        Arbitrary<String> piece = Arbitraries.of("a", "b", "B", "%", "_", "\\%", "\\_", "\\\\", "'", TOP, EMOJI, "c", "i",
                "0", "1");
        return piece.list().ofMinSize(0).ofMaxSize(5).map(l -> String.join("", l));
    }

    private static Arbitrary<Object> literal() {
        return Arbitraries.of(POOL).filter(v -> v != MISSING);
    }

    private static Arbitrary<Expr> matchTerm(String column) {
        Arbitrary<Expr> in = Combinators.combine(literal().list().ofMinSize(1).ofMaxSize(5), Arbitraries.of(true, false))
                .as((vs, neg) -> new Expr.In(column, vs, neg));
        Arbitrary<Expr> like = Combinators.combine(patterns(), Arbitraries.of(true, false))
                .as((p, neg) -> new Expr.Like(column, p, neg));
        return Arbitraries.oneOf(in, like);
    }

    @Provide
    Arbitrary<Expr> conditions() {
        Arbitrary<Expr> term = Arbitraries.oneOf(matchTerm("v"), matchTerm("_id"));
        Arbitrary<Expr> other = Arbitraries.<Expr>of(new Expr.IsNull("v", true), new Expr.Compare("_id", Expr.Cmp.GE, "i1"),
                new Expr.Compare("v", Expr.Cmp.GT, 0L), new Expr.Compare("v", Expr.Cmp.LT, "b"));
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, term),
                net.jqwik.api.Tuple.of(2, Combinators.combine(term, other).as((a, b) -> new Expr.And(List.of(a, b)))),
                net.jqwik.api.Tuple.of(1, Combinators.combine(other, term).as((a, b) -> new Expr.And(List.of(a, b)))),
                net.jqwik.api.Tuple.of(1, Combinators.combine(term, term).as((a, b) -> new Expr.And(List.of(a, b)))),
                net.jqwik.api.Tuple.of(1, Combinators.combine(term, term).as((a, b) -> new Expr.Or(List.of(a, b)))),
                net.jqwik.api.Tuple.of(1, term.map(Expr.Not::new)));
    }

    /** A LIKE match written from the rules, by recursion, independent of LikePattern. */
    static boolean like(String s, int i, String p, int j) {
        if (j == p.length()) {
            return i == s.length();
        }
        char c = p.charAt(j);
        if (c == '%') {
            return like(s, i, p, j + 1) || (i < s.length() && like(s, i + 1, p, j));
        }
        if (c == '_') {
            return i < s.length() && like(s, i + 1, p, j + 1);
        }
        if (c == '\\') {
            return i < s.length() && s.charAt(i) == p.charAt(j + 1) && like(s, i + 1, p, j + 2);
        }
        return i < s.length() && s.charAt(i) == c && like(s, i + 1, p, j + 1);
    }

    static Boolean not(Boolean b) {
        return b == null ? null : !b;
    }

    /** Three-valued, from docs/QUERY.md: IN as an OR of =, LIKE as above. */
    static Boolean model(Expr e, String id, Object v) {
        if (e instanceof Expr.In in) {
            Object x = in.column().equals("_id") ? id : v;
            Boolean any = false;
            for (Object lit : in.values()) {
                Boolean eq = Values.compare(x, Expr.Cmp.EQ, lit);
                if (Boolean.TRUE.equals(eq)) {
                    any = true;
                    break;
                }
                if (eq == null) {
                    any = null;
                }
            }
            return in.negated() ? not(any) : any;
        }
        if (e instanceof Expr.Like l) {
            Object x = l.column().equals("_id") ? id : v;
            Boolean m = x == null ? null : x instanceof String s && like(s, 0, l.pattern().text(), 0);
            return l.negated() ? not(m) : m;
        }
        if (e instanceof Expr.Not n) {
            return not(model(n.inner(), id, v));
        }
        if (e instanceof Expr.And a) {
            Boolean out = true;
            for (Expr t : a.terms()) {
                Boolean b = model(t, id, v);
                if (Boolean.FALSE.equals(b)) {
                    return false;
                }
                if (b == null) {
                    out = null;
                }
            }
            return out;
        }
        if (e instanceof Expr.Or o) {
            Boolean out = false;
            for (Expr t : o.terms()) {
                Boolean b = model(t, id, v);
                if (Boolean.TRUE.equals(b)) {
                    return true;
                }
                if (b == null) {
                    out = null;
                }
            }
            return out;
        }
        if (e instanceof Expr.IsNull n) {
            return n.negated() != (v == null);
        }
        Expr.Compare c = (Expr.Compare) e;
        return Values.compare(c.column().equals("_id") ? id : v, c.op(), c.value());
    }

    @Property(tries = 400)
    void inAndLikeMatchTheRulesThroughAnIndexAndByScan(@ForAll("columnValues") List<Object> values,
            @ForAll("conditionsList") List<Expr> conds) throws IOException {
        String db = "p" + RUN.incrementAndGet();
        query.run("CREATE DATABASE " + db);
        StringBuilder withV = new StringBuilder();
        StringBuilder withoutV = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            String id = String.format("'i%02d'", i);
            Object v = values.get(i);
            if (v == MISSING) {
                withoutV.append(withoutV.length() == 0 ? "" : ", ").append('(').append(id).append(')');
            } else {
                withV.append(withV.length() == 0 ? "" : ", ").append('(').append(id).append(", ")
                        .append(Values.format(v)).append(')');
            }
        }
        for (String t : List.of("x", "y")) {
            query.run("CREATE TABLE " + db + "." + t);
            if (withV.length() > 0) {
                query.run("INSERT INTO " + db + "." + t + " (_id, v) VALUES " + withV);
            }
            if (withoutV.length() > 0) {
                query.run("INSERT INTO " + db + "." + t + " (_id) VALUES " + withoutV);
            }
        }
        query.run("CREATE INDEX ON " + db + ".x (v)");
        for (Expr c : conds) {
            String w = Printer.print(c);
            List<String> want = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                Object v = values.get(i) == MISSING ? null : values.get(i);
                String id = String.format("i%02d", i);
                if (Boolean.TRUE.equals(model(c, id, v))) {
                    want.add(id);
                }
            }
            assertEquals(want, ids("SELECT _id FROM " + db + ".x WHERE " + w), "indexed: " + w);
            assertEquals(want, ids("SELECT _id FROM " + db + ".y WHERE " + w), "scanned: " + w);
            assertEquals(want, ids("SELECT _id FROM " + db + ".x WHERE (" + w + ") OR (" + w + ")"), "OR: " + w);
            assertEquals(List.of(List.<Object>of((long) want.size())),
                    rows("SELECT COUNT(*) FROM " + db + ".x WHERE " + w).rows(), "COUNT(*): " + w);
        }
        query.run("DROP DATABASE " + db);
    }

    @Provide
    Arbitrary<List<Expr>> conditionsList() {
        return conditions().list().ofMinSize(1).ofMaxSize(6);
    }

    // GROUP BY against a naive grouping.

    static final List<Object> GROUP_POOL = Arrays.asList(MISSING, null, true, false, 1L, 1.0, -0.0, 0L, 2.5, "a", "b",
            "", Map.of("k", 1L), Map.of("k", 1.0), List.of(1L, 2L), List.of());
    static final List<Object> NUMBER_POOL = Arrays.asList(MISSING, null, 1L, 2L, -3L, 0L, 2.5, -0.5, 1e300, -0.0,
            Long.MAX_VALUE, Long.MIN_VALUE, 4611686018427387904L, "7", true, Map.of("n", 1L), 0.1, 0.2);

    @Provide
    Arbitrary<List<List<Object>>> groupRows() {
        Arbitrary<List<Object>> row = Combinators.combine(Arbitraries.of(GROUP_POOL), Arbitraries.of(GROUP_POOL),
                Arbitraries.of(NUMBER_POOL)).as((g, h, n) -> Arrays.asList(g, h, n));
        return row.list().ofMinSize(0).ofMaxSize(50);
    }

    @Provide
    Arbitrary<Statement.Select> groupQueries() {
        Arbitrary<List<String>> by = Arbitraries.of(List.<String>of(), List.of("g"), List.of("h"), List.of("g", "h"),
                List.of("h", "g"), List.of("n"));
        Arbitrary<Statement.Item.Aggregate> agg = Arbitraries.oneOf(Arbitraries.just(Statement.Item.Aggregate.COUNT_ALL),
                Combinators.combine(Arbitraries.of(Statement.Fn.class), Arbitraries.of("n", "g", "h", "_id", "nope"))
                        .as(Statement.Item.Aggregate::new));
        Arbitrary<Expr> where = Arbitraries.<Expr>of(new Expr.IsNull("n", true), new Expr.In("g", Arrays.asList(1L, "a", null), false),
                new Expr.Compare("_id", Expr.Cmp.GE, "r10"), new Expr.Like("h", "%", false)).injectNull(0.5);
        Arbitrary<Long> limit = Arbitraries.longs().between(0, 5).injectNull(0.6);
        return Combinators.combine(by, agg.list().ofMinSize(0).ofMaxSize(4), where, Arbitraries.integers().between(0, 63),
                Arbitraries.of(true, false), limit).as((b, aggs, w, pick, desc, l) -> {
                    List<Statement.Item> items = new ArrayList<>();
                    for (int i = 0; i < b.size(); i++) {
                        if ((pick >> i & 1) == 1) {
                            items.add(new Statement.Item.Column(b.get(i)));
                        }
                    }
                    items.addAll(aggs);
                    if (items.isEmpty()) {
                        items.add(Statement.Item.Aggregate.COUNT_ALL);
                    }
                    if ((pick & 4) == 4 && items.size() > 1) {
                        java.util.Collections.reverse(items);
                    }
                    Statement.Projection p = items.equals(List.of(Statement.Item.Aggregate.COUNT_ALL))
                            ? new Statement.Projection.Count()
                            : items.stream().allMatch(i -> i instanceof Statement.Item.Column)
                                    ? new Statement.Projection.Columns(items.stream()
                                            .map(i -> ((Statement.Item.Column) i).name()).toList())
                                    : new Statement.Projection.Items(items);
                    if (b.isEmpty()) {
                        return new Statement.Select("d", "t", p, w, List.of(), null, null);
                    }
                    Statement.OrderBy o = null;
                    if ((pick & 8) == 8) {
                        List<Statement.Item> keys = new ArrayList<>();
                        b.forEach(c -> keys.add(new Statement.Item.Column(c)));
                        items.stream().filter(i -> i instanceof Statement.Item.Aggregate).forEach(keys::add);
                        o = new Statement.OrderBy(keys.get((pick >> 4) % keys.size()), desc);
                    }
                    return new Statement.Select("d", "t", p, w, b, o, l);
                });
    }

    /** Equal as a GROUP BY makes them: by = for scalars, by value for objects and arrays, null with null. */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (ScalarOrder.scalar(a) || ScalarOrder.scalar(b)) {
            return Boolean.TRUE.equals(Values.compare(a, Expr.Cmp.EQ, b));
        }
        return Objects.equals(a, b);
    }

    static Object sorted(Object v) {
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> out = new TreeMap<>();
            m.forEach((k, x) -> out.put((String) k, sorted(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(ExecutorProperties::sorted).toList();
        }
        return v;
    }

    /** The GROUP BY order: the ORDER BY order, objects and arrays by their JSON text with keys sorted. */
    static int groupOrder(Object a, Object b) {
        int c = Values.sortOrder(a, b);
        if (c == 0 && a != null && b != null && !ScalarOrder.scalar(a) && !ScalarOrder.scalar(b)) {
            return Integer.signum(Json.write(sorted(a)).compareTo(Json.write(sorted(b))));
        }
        return c;
    }

    /** One aggregate over the values of a group, in _id order, as docs/QUERY.md says; throws as the executor must. */
    static Object aggregate(Statement.Item.Aggregate a, List<Object> vs) {
        switch (a.fn()) {
            case COUNT:
                return a.column() == null ? (long) vs.size() : vs.stream().filter(Objects::nonNull).count();
            case MIN:
            case MAX: {
                Object best = null;
                for (Object v : vs) {
                    if (v != null && (best == null || (a.fn() == Statement.Fn.MIN ? Values.sortOrder(v, best) < 0
                            : Values.sortOrder(v, best) > 0))) {
                        best = v;
                    }
                }
                return best;
            }
            default: {
                BigInteger ints = BigInteger.ZERO;
                double doubles = -0.0;
                long n = 0;
                boolean anyInt = false;
                boolean anyDouble = false;
                for (Object v : vs) {
                    if (v instanceof Long x) {
                        ints = ints.add(BigInteger.valueOf(x));
                        anyInt = true;
                        n++;
                    } else if (v instanceof Double x) {
                        doubles += x;
                        anyDouble = true;
                        n++;
                    }
                }
                if (n == 0) {
                    return null;
                }
                boolean avg = a.fn() == Statement.Fn.AVG;
                if (!anyDouble && !avg) {
                    if (ints.bitLength() > 63) {
                        throw new QueryException("SUM overflows a 64-bit integer");
                    }
                    return ints.longValue();
                }
                double total = anyInt ? doubles + ints.doubleValue() : doubles;
                if (avg) {
                    total /= n;
                }
                if (!Double.isFinite(total)) {
                    throw new QueryException((avg ? "AVG" : "SUM") + " overflows a double");
                }
                return total;
            }
        }
    }

    record NaiveGroup(List<Object> values, List<Map<String, Object>> rows) {}

    /** The rows a SELECT with GROUP BY or aggregates gives, computed the plain way; or the error it must raise. */
    static List<List<Object>> naive(Statement.Select q, TreeMap<String, Map<String, Object>> table) {
        List<String> by = q.groupBy();
        List<NaiveGroup> groups = new ArrayList<>();
        table.forEach((id, row) -> {
            Map<String, Object> r = new LinkedHashMap<>(row);
            r.put("_id", id);
            if (q.where() != null && !Boolean.TRUE.equals(Executor.evaluate(q.where(), id, row))) {
                return;
            }
            List<Object> vals = new ArrayList<>();
            by.forEach(c -> vals.add(r.get(c)));
            for (NaiveGroup g : groups) {
                boolean match = true;
                for (int i = 0; i < by.size() && match; i++) {
                    match = same(g.values().get(i), vals.get(i));
                }
                if (match) {
                    g.rows().add(r);
                    return;
                }
            }
            List<Map<String, Object>> rs = new ArrayList<>();
            rs.add(r);
            groups.add(new NaiveGroup(vals, rs));
        });
        if (by.isEmpty() && groups.isEmpty()) {
            groups.add(new NaiveGroup(List.of(), new ArrayList<>()));
        }
        groups.sort((a, b) -> {
            for (int i = 0; i < by.size(); i++) {
                int c = groupOrder(a.values().get(i), b.values().get(i));
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        List<Statement.Item> items = Statement.Projection.itemsOf(q.projection());
        List<Statement.Item.Aggregate> aggs = new ArrayList<>();
        items.stream().filter(i -> i instanceof Statement.Item.Aggregate).map(i -> (Statement.Item.Aggregate) i)
                .filter(a -> !aggs.contains(a)).forEach(aggs::add);
        List<Map<Statement.Item, Object>> results = new ArrayList<>();
        for (NaiveGroup g : groups) {
            Map<Statement.Item, Object> res = new java.util.HashMap<>();
            for (Statement.Item.Aggregate a : aggs) {
                List<Object> vs = new ArrayList<>();
                for (Map<String, Object> r : g.rows()) {
                    vs.add(a.column() == null ? Boolean.TRUE : r.get(a.column()));
                }
                res.put(a, aggregate(a, vs));
            }
            for (int i = 0; i < by.size(); i++) {
                res.put(new Statement.Item.Column(by.get(i)), g.values().get(i));
            }
            results.add(res);
        }
        if (q.orderBy() != null) {
            Comparator<Map<Statement.Item, Object>> c = (a, b) -> Values.sortOrder(a.get(q.orderBy().key()),
                    b.get(q.orderBy().key()));
            results.sort(q.orderBy().descending() ? c.reversed() : c);
        }
        if (q.limit() != null && results.size() > q.limit()) {
            results = results.subList(0, q.limit().intValue());
        }
        List<List<Object>> out = new ArrayList<>();
        for (Map<Statement.Item, Object> res : results) {
            List<Object> r = new ArrayList<>();
            items.forEach(i -> r.add(res.get(i)));
            out.add(r);
        }
        return out;
    }

    @Property(tries = 600)
    void groupByGivesWhatANaiveGroupingGives(@ForAll("groupRows") List<List<Object>> data,
            @ForAll("groupQueries") Statement.Select shape) throws IOException {
        String db = "g" + RUN.incrementAndGet();
        query.run("CREATE DATABASE " + db);
        query.run("CREATE TABLE " + db + ".t");
        if (RUN.get() % 2 == 0) {
            query.run("CREATE INDEX ON " + db + ".t (g)");
        }
        TreeMap<String, Map<String, Object>> table = new TreeMap<>();
        List<Op> puts = new ArrayList<>();
        Stamp at = new Stamp(System.currentTimeMillis() + 1000, 0, "p");
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            String[] cols = {"g", "h", "n"};
            for (int c = 0; c < 3; c++) {
                if (data.get(i).get(c) != MISSING) {
                    row.put(cols[c], data.get(i).get(c));
                }
            }
            String id = String.format("r%02d", (i * 37) % 100);
            if (!table.containsKey(id)) {
                table.put(id, row);
                puts.add(new Op.Put(db, "t", id, row, at));
            }
        }
        engine.apply(puts);
        Statement.Select q = new Statement.Select(db, "t", shape.projection(), shape.where(), shape.groupBy(),
                shape.orderBy(), shape.limit());
        String text = Printer.print(q);
        assertEquals(q, Parser.parse(text), text);
        List<List<Object>> want;
        try {
            want = naive(q, table);
        } catch (QueryException e) {
            assertEquals(e.getMessage(), assertThrows(QueryException.class, () -> query.run(text), text).getMessage(),
                    text);
            query.run("DROP DATABASE " + db);
            return;
        }
        Result.Rows got = rows(text);
        assertEquals(Statement.Projection.itemsOf(q.projection()).stream().map(Statement.Item::label).toList(),
                got.columns(), text);
        assertEquals(want, got.rows(), text);
        query.run("DROP DATABASE " + db);
    }
}
