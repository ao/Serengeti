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
 * the rows, in _id order, gives, and HAVING what filtering those groups one by one gives; SELECT DISTINCT
 * gives the first row of each set of equal values, as its GROUP BY form does; ORDER BY on several keys gives
 * a stable sort of the rows in _id order.
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

    static final List<Expr> WHERES = List.of(new Expr.IsNull("n", true), new Expr.In("g", Arrays.asList(1L, "a", null), false),
            new Expr.Compare("_id", Expr.Cmp.GE, "r10"), new Expr.Like("h", "%", false));
    /** Values HAVING compares with: counts, the values of GROUP_POOL and NUMBER_POOL, and null. */
    static final List<Object> HAVING_POOL = Arrays.asList(null, true, false, 0L, 1L, 2L, 3L, 2.5, -0.0, 1.0, "a", "b", "",
            "7", Long.MAX_VALUE);

    static <T> T pick(java.util.Random r, List<T> from) {
        return from.get(r.nextInt(from.size()));
    }

    static Statement.Item.Aggregate randomAggregate(java.util.Random r) {
        int k = r.nextInt(8);
        if (k == 0) {
            return Statement.Item.Aggregate.COUNT_ALL;
        }
        String column = pick(r, List.of("n", "g", "h", "_id", "nope"));
        return k == 1 ? new Statement.Item.Aggregate(Statement.Fn.COUNT, column, true)
                : new Statement.Item.Aggregate(pick(r, List.of(Statement.Fn.values())), column);
    }

    static Statement.Projection projectionOf(List<Statement.Item> items) {
        if (items.equals(List.of(Statement.Item.Aggregate.COUNT_ALL))) {
            return new Statement.Projection.Count();
        }
        if (items.stream().allMatch(i -> i instanceof Statement.Item.Column)) {
            return new Statement.Projection.Columns(items.stream().map(i -> ((Statement.Item.Column) i).name()).toList());
        }
        return new Statement.Projection.Items(items);
    }

    /** A HAVING condition whose terms test the items of {@code lefts} (by label) with values of {@code pool}. */
    static Expr randomHaving(java.util.Random r, List<Statement.Item> lefts, List<Object> pool, int depth) {
        int k = depth >= 2 ? 0 : r.nextInt(5);
        if (k == 2) {
            return new Expr.Not(randomHaving(r, lefts, pool, depth + 1));
        }
        if (k == 3 || k == 4) {
            List<Expr> terms = List.of(randomHaving(r, lefts, pool, depth + 1), randomHaving(r, lefts, pool, depth + 1));
            return k == 3 ? new Expr.And(terms) : new Expr.Or(terms);
        }
        String left = pick(r, lefts).label();
        switch (r.nextInt(5)) {
            case 0:
                return new Expr.IsNull(left, r.nextBoolean());
            case 1: {
                List<Object> vs = new ArrayList<>();
                for (int i = 1 + r.nextInt(3); i > 0; i--) {
                    vs.add(pick(r, pool));
                }
                return new Expr.In(left, vs, r.nextBoolean());
            }
            case 2:
                return new Expr.Like(left, pick(r, List.of("%", "a%", "_", "")), r.nextBoolean());
            default:
                return new Expr.Compare(left, pick(r, List.of(Expr.Cmp.values())), pick(r, pool));
        }
    }

    /** Up to {@code max} distinct keys of {@code candidates}, in random order and directions. */
    static List<Statement.OrderBy> randomOrder(java.util.Random r, List<Statement.Item> candidates, int max) {
        List<Statement.Item> keys = new ArrayList<>(new java.util.LinkedHashSet<>(candidates));
        java.util.Collections.shuffle(keys, r);
        List<Statement.OrderBy> out = new ArrayList<>();
        for (int n = r.nextInt(Math.min(max, keys.size()) + 1), i = 0; i < n; i++) {
            out.add(new Statement.OrderBy(keys.get(i), r.nextBoolean()));
        }
        return out;
    }

    static Long randomLimit(java.util.Random r) {
        return r.nextInt(5) < 3 ? null : Long.valueOf(r.nextInt(6));
    }

    /** SELECTs with aggregates or GROUP BY, HAVING sometimes (with aggregates the list lacks), and 0 to 3 keys. */
    @Provide
    Arbitrary<Statement.Select> groupQueries() {
        return Arbitraries.randomValue(r -> {
            List<String> b = pick(r, List.of(List.<String>of(), List.of("g"), List.of("h"), List.of("g", "h"),
                    List.of("h", "g"), List.of("n")));
            List<Statement.Item> items = new ArrayList<>();
            for (String c : b) {
                if (r.nextBoolean()) {
                    items.add(new Statement.Item.Column(c));
                }
            }
            for (int i = r.nextInt(5); i > 0; i--) {
                items.add(randomAggregate(r));
            }
            if (items.isEmpty()) {
                items.add(Statement.Item.Aggregate.COUNT_ALL);
            }
            if (r.nextInt(3) == 0) {
                java.util.Collections.shuffle(items, r);
            }
            Statement.Projection p = projectionOf(items);
            Expr w = r.nextBoolean() ? null : pick(r, WHERES);
            Expr having = null;
            if (r.nextInt(3) > 0 && (!b.isEmpty() || Statement.Projection.aggregates(p))) {
                List<Statement.Item> lefts = new ArrayList<>();
                b.forEach(c -> lefts.add(new Statement.Item.Column(c)));
                items.stream().filter(i -> i instanceof Statement.Item.Aggregate).forEach(lefts::add);
                lefts.add(randomAggregate(r));
                having = randomHaving(r, lefts, HAVING_POOL, 0);
            }
            if (b.isEmpty()) {
                return new Statement.Select("d", "t", p, false, w, List.of(), having, List.of(), null);
            }
            List<Statement.Item> keys = new ArrayList<>();
            b.forEach(c -> keys.add(new Statement.Item.Column(c)));
            items.stream().filter(i -> i instanceof Statement.Item.Aggregate).forEach(keys::add);
            return new Statement.Select("d", "t", p, false, w, b, having, randomOrder(r, keys, 3), randomLimit(r));
        });
    }

    /** SELECT DISTINCT of 1 to 3 columns, with ORDER BY on some of them. */
    @Provide
    Arbitrary<Statement.Select> distinctQueries() {
        return Arbitraries.randomValue(r -> {
            List<String> cols = new ArrayList<>(List.of("g", "h", "n", "_id", "nope"));
            java.util.Collections.shuffle(cols, r);
            cols = cols.subList(0, 1 + r.nextInt(3));
            List<Statement.Item> keys = cols.stream().<Statement.Item>map(Statement.Item.Column::new).toList();
            return new Statement.Select("d", "t", new Statement.Projection.Columns(cols), true,
                    r.nextBoolean() ? null : pick(r, WHERES), List.of(), null, randomOrder(r, keys, 3), randomLimit(r));
        });
    }

    /** A plain SELECT of every column with 1 to 4 ORDER BY keys, _id and a column no row has among them. */
    @Provide
    Arbitrary<Statement.Select> orderQueries() {
        return Arbitraries.randomValue(r -> {
            List<Statement.Item> keys = List.of(new Statement.Item.Column("g"), new Statement.Item.Column("h"),
                    new Statement.Item.Column("n"), new Statement.Item.Column("_id"), new Statement.Item.Column("nope"));
            List<Statement.OrderBy> order = List.of();
            while (order.isEmpty()) {
                order = randomOrder(r, keys, 4);
            }
            return new Statement.Select("d", "t", new Statement.Projection.Columns(List.of("_id", "g", "h", "n")), false,
                    r.nextBoolean() ? null : pick(r, WHERES), List.of(), null, order, randomLimit(r));
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
        if (a.distinct()) {
            List<Object> seen = new ArrayList<>();
            for (Object v : vs) {
                if (v != null && seen.stream().noneMatch(s -> same(s, v))) {
                    seen.add(v);
                }
            }
            return (long) seen.size();
        }
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

    /** Three-valued, from docs/QUERY.md, over a row of named values (a group's labels in HAVING). */
    static Boolean modelRow(Expr e, Map<String, Object> row) {
        if (e instanceof Expr.Not n) {
            return not(modelRow(n.inner(), row));
        }
        if (e instanceof Expr.And a) {
            Boolean out = true;
            for (Expr t : a.terms()) {
                Boolean b = modelRow(t, row);
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
                Boolean b = modelRow(t, row);
                if (Boolean.TRUE.equals(b)) {
                    return true;
                }
                if (b == null) {
                    out = null;
                }
            }
            return out;
        }
        String[] column = new String[1];
        Expr.forEachColumn(e, c -> column[0] = c);
        if (!row.containsKey(column[0])) {
            throw new AssertionError("the model has no value for " + column[0]);
        }
        Object v = row.get(column[0]);
        // The leaf models of model() take the value as v; "-" is a column name that is never _id.
        return model(rename(e), "-", v);
    }

    /** A leaf with its column renamed, so that model() reads v and not the id. */
    private static Expr rename(Expr e) {
        if (e instanceof Expr.Compare c) {
            return new Expr.Compare("v", c.op(), c.value());
        }
        if (e instanceof Expr.In in) {
            return new Expr.In("v", in.values(), in.negated());
        }
        if (e instanceof Expr.Like l) {
            return new Expr.Like("v", l.pattern(), l.negated());
        }
        Expr.IsNull n = (Expr.IsNull) e;
        return new Expr.IsNull("v", n.negated());
    }

    /** A stable insertion sort, written out so as not to lean on the library sort the executor uses. */
    static <T> List<T> stableSort(List<T> in, Comparator<T> c) {
        List<T> out = new ArrayList<>();
        for (T x : in) {
            int at = out.size();
            while (at > 0 && c.compare(out.get(at - 1), x) > 0) {
                at--;
            }
            out.add(at, x);
        }
        return out;
    }

    /** The ORDER BY keys of {@code q} over rows of items, each key in its direction, in the order written. */
    static Comparator<Map<Statement.Item, Object>> byKeys(Statement.Select q) {
        return (a, b) -> {
            for (Statement.OrderBy o : q.orderBy()) {
                int c = Values.sortOrder(a.get(o.key()), b.get(o.key()));
                if (c != 0) {
                    return o.descending() ? -c : c;
                }
            }
            return 0;
        };
    }

    /**
     * The rows a SELECT with GROUP BY, DISTINCT or aggregates gives, computed the plain way; or the error it must
     * raise. HAVING filters the full grouped result, row by row, before ORDER BY and LIMIT.
     */
    static List<List<Object>> naive(Statement.Select q, TreeMap<String, Map<String, Object>> table) {
        List<String> by = q.distinct() ? ((Statement.Projection.Columns) q.projection()).names() : q.groupBy();
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
        List<Statement.Item> all = new ArrayList<>(items);
        all.addAll(Statement.Select.havingItems(q.having()));
        all.stream().filter(i -> i instanceof Statement.Item.Aggregate).map(i -> (Statement.Item.Aggregate) i)
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
        if (q.having() != null) {
            List<Map<Statement.Item, Object>> kept = new ArrayList<>();
            for (Map<Statement.Item, Object> res : results) {
                Map<String, Object> row = new java.util.HashMap<>();
                res.forEach((item, v) -> row.put(item.label(), v));
                if (Boolean.TRUE.equals(modelRow(q.having(), row))) {
                    kept.add(res);
                }
            }
            results = kept;
        }
        results = stableSort(results, byKeys(q));
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

    /** A new database with the table t holding {@code data} (columns g, h, n) in _id order; its rows by _id. */
    private static TreeMap<String, Map<String, Object>> load(String db, List<List<Object>> data) throws IOException {
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
        return table;
    }

    private static Statement.Select in(String db, Statement.Select s) {
        return new Statement.Select(db, "t", s.projection(), s.distinct(), s.where(), s.groupBy(), s.having(), s.orderBy(),
                s.limit());
    }

    /** Runs {@code q} and checks it against {@code want}, or against the error computing {@code want} raised. */
    private static void answersAsModelled(Statement.Select q, java.util.function.Supplier<List<List<Object>>> want)
            throws IOException {
        String text = Printer.print(q);
        assertEquals(q, Parser.parse(text), text);
        List<List<Object>> rows;
        try {
            rows = want.get();
        } catch (QueryException e) {
            assertEquals(e.getMessage(), assertThrows(QueryException.class, () -> query.run(text), text).getMessage(),
                    text);
            return;
        }
        Result.Rows got = rows(text);
        assertEquals(Statement.Projection.itemsOf(q.projection()).stream().map(Statement.Item::label).toList(),
                got.columns(), text);
        assertEquals(rows, got.rows(), text);
    }

    @Property(tries = 600)
    void groupByAndHavingGiveWhatANaiveGroupingGives(@ForAll("groupRows") List<List<Object>> data,
            @ForAll("groupQueries") Statement.Select shape) throws IOException {
        String db = "g" + RUN.incrementAndGet();
        TreeMap<String, Map<String, Object>> table = load(db, data);
        Statement.Select q = in(db, shape);
        answersAsModelled(q, () -> naive(q, table));
        query.run("DROP DATABASE " + db);
    }

    @Property(tries = 400)
    void distinctGivesWhatANaiveDedupGivesAndWhatItsGroupByFormGives(@ForAll("groupRows") List<List<Object>> data,
            @ForAll("distinctQueries") Statement.Select shape) throws IOException {
        String db = "s" + RUN.incrementAndGet();
        TreeMap<String, Map<String, Object>> table = load(db, data);
        Statement.Select q = in(db, shape);
        List<String> cols = ((Statement.Projection.Columns) q.projection()).names();
        // Written out: the first row of each set of equal values, in _id order, then the GROUP BY order.
        List<List<Object>> firsts = new ArrayList<>();
        table.forEach((id, row) -> {
            if (q.where() != null && !Boolean.TRUE.equals(Executor.evaluate(q.where(), id, row))) {
                return;
            }
            List<Object> vals = new ArrayList<>();
            cols.forEach(c -> vals.add(c.equals("_id") ? id : row.get(c)));
            boolean seen = firsts.stream().anyMatch(f -> {
                for (int i = 0; i < cols.size(); i++) {
                    if (!same(f.get(i), vals.get(i))) {
                        return false;
                    }
                }
                return true;
            });
            if (!seen) {
                firsts.add(vals);
            }
        });
        List<List<Object>> distinct = stableSort(firsts, (a, b) -> {
            for (int i = 0; i < cols.size(); i++) {
                int c = groupOrder(a.get(i), b.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        List<Map<Statement.Item, Object>> keyed = new ArrayList<>();
        for (List<Object> r : distinct) {
            Map<Statement.Item, Object> m = new java.util.HashMap<>();
            for (int i = 0; i < cols.size(); i++) {
                m.put(new Statement.Item.Column(cols.get(i)), r.get(i));
            }
            keyed.add(m);
        }
        keyed = stableSort(keyed, byKeys(q));
        List<List<Object>> want = new ArrayList<>();
        for (Map<Statement.Item, Object> m : keyed) {
            if (q.limit() == null || want.size() < q.limit()) {
                want.add(cols.stream().map(c -> m.get(new Statement.Item.Column(c))).toList());
            }
        }
        answersAsModelled(q, () -> want);
        Statement.Select grouped = new Statement.Select(db, "t", q.projection(), false, q.where(), cols, null, q.orderBy(),
                q.limit());
        assertEquals(rows(Printer.print(grouped)).rows(), rows(Printer.print(q)).rows(), Printer.print(q));
        query.run("DROP DATABASE " + db);
    }

    @Property(tries = 400)
    void orderByKeysGiveANaiveStableSortOfTheIdOrder(@ForAll("groupRows") List<List<Object>> data,
            @ForAll("orderQueries") Statement.Select shape) throws IOException {
        String db = "o" + RUN.incrementAndGet();
        TreeMap<String, Map<String, Object>> table = load(db, data);
        Statement.Select q = in(db, shape);
        List<Map<Statement.Item, Object>> matched = new ArrayList<>();
        table.forEach((id, row) -> {
            if (q.where() == null || Boolean.TRUE.equals(Executor.evaluate(q.where(), id, row))) {
                Map<Statement.Item, Object> m = new java.util.HashMap<>();
                for (String c : List.of("g", "h", "n", "nope")) {
                    m.put(new Statement.Item.Column(c), row.get(c));
                }
                m.put(new Statement.Item.Column("_id"), id);
                matched.add(m);
            }
        });
        List<List<Object>> want = new ArrayList<>();
        for (Map<Statement.Item, Object> m : stableSort(matched, byKeys(q))) {
            if (q.limit() == null || want.size() < q.limit()) {
                want.add(Arrays.asList(m.get(new Statement.Item.Column("_id")), m.get(new Statement.Item.Column("g")),
                        m.get(new Statement.Item.Column("h")), m.get(new Statement.Item.Column("n"))));
            }
        }
        answersAsModelled(q, () -> want);
        query.run("DROP DATABASE " + db);
    }
}
