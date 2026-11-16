package com.ataiva.serengeti.core.query;

import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Store;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs parsed statements against a {@link StorageEngine}. Each write is planned and applied inside one
 * {@link StorageEngine#update} call, so its checks (the table exists, the {@code _id} is free) and its
 * ops happen under the same lock. All ops of one statement share one stamp from the clock.
 *
 * <p>Thread-safe: the engine does the locking.
 */
public final class Executor {
    /** The most rows one SELECT returns, unless the executor is built with another cap. */
    public static final int DEFAULT_MAX_ROWS = 10_000;
    /** The most groups a GROUP BY may hold while it runs, unless the executor is built with another cap. */
    public static final int DEFAULT_MAX_GROUPS = 100_000;
    /** The column that holds each row's key. */
    public static final String ID = "_id";

    private final StorageEngine engine;
    private final Hlc clock;
    private final int maxRows;
    private final int maxGroups;

    public Executor(StorageEngine engine, Hlc clock) {
        this(engine, clock, DEFAULT_MAX_ROWS);
    }

    public Executor(StorageEngine engine, Hlc clock, int maxRows) {
        this(engine, clock, maxRows, DEFAULT_MAX_GROUPS);
    }

    public Executor(StorageEngine engine, Hlc clock, int maxRows, int maxGroups) {
        if (maxRows < 1 || maxGroups < 1) {
            throw new IllegalArgumentException("maxRows and maxGroups must be at least 1");
        }
        this.engine = engine;
        this.clock = clock;
        this.maxRows = maxRows;
        this.maxGroups = maxGroups;
        clock.observe(engine.read(Store::maxStamp));
    }

    public int maxRows() {
        return maxRows;
    }

    /**
     * Runs one statement.
     *
     * @throws QueryException if the statement cannot run (a missing database or table, a duplicate
     *     {@code _id}, a LIMIT over the cap); nothing has been written in that case
     * @throws IOException if the write-ahead log could not be written
     */
    public Result execute(Statement s) throws IOException {
        if (s instanceof Statement.Select q) {
            return engine.read(store -> select(store, q));
        }
        if (s instanceof Statement.ShowDatabases) {
            return engine.read(store -> names("database", store.databases()));
        }
        if (s instanceof Statement.ShowTables t) {
            return engine.read(store -> {
                requireDb(store, t.db());
                return names("table", store.tables(t.db()));
            });
        }
        if (s instanceof Statement.ShowConflicts c) {
            return engine.read(store -> conflicts(store, c));
        }
        if (s instanceof Statement.CreateDatabase c) {
            return engine.update(store -> {
                if (store.dbExists(c.db())) {
                    if (c.ifNotExists()) {
                        return none();
                    }
                    throw new QueryException("database '" + c.db() + "' already exists");
                }
                return one(new Op.CreateDb(c.db(), stamp(store)));
            });
        }
        if (s instanceof Statement.DropDatabase d) {
            return engine.update(store -> {
                if (!store.dbExists(d.db())) {
                    if (d.ifExists()) {
                        return none();
                    }
                    throw noDb(d.db());
                }
                return one(new Op.DropDb(d.db(), stamp(store)));
            });
        }
        if (s instanceof Statement.CreateTable c) {
            return engine.update(store -> {
                requireDb(store, c.db());
                if (store.tableExists(c.db(), c.table())) {
                    if (c.ifNotExists()) {
                        return none();
                    }
                    throw new QueryException("table '" + c.db() + "." + c.table() + "' already exists");
                }
                return one(new Op.CreateTable(c.db(), c.table(), stamp(store)));
            });
        }
        if (s instanceof Statement.CreateIndex c) {
            return engine.update(store -> {
                requireTable(store, c.db(), c.table());
                if (c.column().equals(ID)) {
                    throw new QueryException("_id needs no index: lookups by _id never scan");
                }
                if (store.indexExists(c.db(), c.table(), c.column())) {
                    if (c.ifNotExists()) {
                        return none();
                    }
                    throw new QueryException("'" + c.db() + "." + c.table() + "' already has an index on " + c.column());
                }
                return one(new Op.CreateIndex(c.db(), c.table(), c.column(), stamp(store)));
            });
        }
        if (s instanceof Statement.DropIndex d) {
            return engine.update(store -> {
                // As with DROP TABLE IF EXISTS: no table means no index, so nothing to do.
                if (d.ifExists() && !store.tableExists(d.db(), d.table())) {
                    return none();
                }
                requireTable(store, d.db(), d.table());
                if (!store.indexExists(d.db(), d.table(), d.column())) {
                    if (d.ifExists()) {
                        return none();
                    }
                    throw new QueryException("'" + d.db() + "." + d.table() + "' has no index on " + d.column());
                }
                return one(new Op.DropIndex(d.db(), d.table(), d.column(), stamp(store)));
            });
        }
        if (s instanceof Statement.ShowIndexes i) {
            return engine.read(store -> {
                requireTable(store, i.db(), i.table());
                return names("column", store.indexes(i.db(), i.table()));
            });
        }
        if (s instanceof Statement.DropTable d) {
            return engine.update(store -> {
                if (!store.tableExists(d.db(), d.table())) {
                    if (d.ifExists()) {
                        return none();
                    }
                    requireDb(store, d.db());
                    throw noTable(d.db(), d.table());
                }
                return one(new Op.DropTable(d.db(), d.table(), stamp(store)));
            });
        }
        if (s instanceof Statement.Insert i) {
            return engine.update(store -> insert(store, i));
        }
        if (s instanceof Statement.Update u) {
            return engine.update(store -> update(store, u));
        }
        if (s instanceof Statement.Delete d) {
            return engine.update(store -> delete(store, d));
        }
        throw new IllegalArgumentException("unknown statement " + s);
    }

    private StorageEngine.Planned<Result> insert(Store store, Statement.Insert i) {
        requireTable(store, i.db(), i.table());
        Stamp at = stamp(store);
        int idIndex = i.columns().indexOf(ID);
        List<Op> ops = new ArrayList<>(i.rows().size());
        List<String> ids = new ArrayList<>(i.rows().size());
        Set<String> seen = new HashSet<>();
        for (List<Object> values : i.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            String id = null;
            for (int c = 0; c < values.size(); c++) {
                if (c == idIndex) {
                    if (!(values.get(c) instanceof String str) || str.isEmpty()) {
                        throw new QueryException("_id must be a non-empty string");
                    }
                    id = str;
                } else {
                    row.put(i.columns().get(c), values.get(c));
                }
            }
            if (id == null) {
                id = UUID.randomUUID().toString();
            }
            if (!seen.add(id) || store.row(i.db(), i.table(), id) != null) {
                throw new QueryException("a row with _id '" + Lexer.shorten(id) + "' already exists in '"
                        + i.db() + "." + i.table() + "'");
            }
            ops.add(new Op.Put(i.db(), i.table(), id, row, at));
            ids.add(id);
        }
        return new StorageEngine.Planned<>(ops, new Result.Write(ops.size(), ids));
    }

    private StorageEngine.Planned<Result> update(Store store, Statement.Update u) {
        requireTable(store, u.db(), u.table());
        for (Statement.Assignment a : u.assignments()) {
            if (a.column().equals(ID)) {
                throw new QueryException("_id cannot be changed");
            }
        }
        List<Op> ops = new ArrayList<>();
        Stamp at = stamp(store);
        scan(store, u.db(), u.table(), u.where(), (id, row) -> {
            if (matches(u.where(), id, row)) {
                Map<String, Object> changed = new LinkedHashMap<>(row);
                for (Statement.Assignment a : u.assignments()) {
                    changed.put(a.column(), a.value());
                }
                ops.add(new Op.Put(u.db(), u.table(), id, changed, at));
            }
        });
        return new StorageEngine.Planned<>(ops, new Result.Write(ops.size()));
    }

    /** Columns of SHOW CONFLICTS: one result row per version, the row's value (the newest) first. */
    static final List<String> CONFLICT_COLUMNS = List.of(ID, "current", "deleted", "node", "at", "row");

    private Result conflicts(Store store, Statement.ShowConflicts c) {
        requireTable(store, c.db(), c.table());
        List<List<Object>> rows = new ArrayList<>();
        boolean[] truncated = {false};
        store.forEachConflict(c.db(), c.table(), (id, versions) -> {
            if (rows.size() + versions.size() > maxRows) {
                truncated[0] = true;
                return;
            }
            for (int i = 0; i < versions.size(); i++) {
                Store.Version v = versions.get(i);
                List<Object> r = new ArrayList<>(CONFLICT_COLUMNS.size());
                r.add(id);
                r.add(i == 0);
                r.add(v.deleted());
                r.add(v.stamp().node());
                r.add(v.stamp().wall());
                r.add(v.value());
                rows.add(r);
            }
        });
        return new Result.Rows(CONFLICT_COLUMNS, rows, truncated[0]);
    }

    private StorageEngine.Planned<Result> delete(Store store, Statement.Delete d) {
        requireTable(store, d.db(), d.table());
        List<Op> ops = new ArrayList<>();
        Stamp at = stamp(store);
        scan(store, d.db(), d.table(), d.where(), (id, row) -> {
            if (matches(d.where(), id, row)) {
                ops.add(new Op.Delete(d.db(), d.table(), id, at));
            }
        });
        return new StorageEngine.Planned<>(ops, new Result.Write(ops.size()));
    }

    /**
     * Visits, in _id order, the live rows that may match {@code where}: through an index when one term of
     * it (the whole condition, or one term of a top-level AND) compares an indexed column or _id with a
     * value, is an IN list or a LIKE with a literal prefix, else every row. Visitors still check the whole
     * condition, so the choice changes only speed.
     */
    private static void scan(Store store, String db, String table, Expr where,
            java.util.function.BiConsumer<String, Map<String, Object>> visitor) {
        Plan via = cheapestIndexedTerm(store, db, table, where);
        if (via == null || !store.forEachIndexed(db, table, via.column(), via.lookup(), visitor)) {
            store.forEachRow(db, table, visitor);
        }
    }

    /** As {@link #scan}, stopping at the first row for which {@code visitor} returns false. */
    private static void scanWhile(Store store, String db, String table, Expr where,
            java.util.function.BiPredicate<String, Map<String, Object>> visitor) {
        Plan via = cheapestIndexedTerm(store, db, table, where);
        if (via == null || !store.forEachIndexedWhile(db, table, via.column(), via.lookup(), visitor)) {
            store.forEachRowWhile(db, table, visitor);
        }
    }

    /** An index read for one term of a condition: what {@code lookup} finds in {@code column}. */
    record Plan(String column, Store.Lookup lookup) {}

    /** The term of {@code where} (itself, or one of a top-level AND) whose index visits the fewest rows. */
    static Plan cheapestIndexedTerm(Store store, String db, String table, Expr where) {
        List<Expr> terms = where instanceof Expr.And a ? a.terms() : where == null ? List.of() : List.of(where);
        Plan best = null;
        long bestCount = Long.MAX_VALUE;
        for (Expr t : terms) {
            Plan p = plan(t);
            if (p != null) {
                long n = store.indexEstimate(db, table, p.column(), p.lookup());
                if (n >= 0 && n < bestCount) {
                    best = p;
                    bestCount = n;
                }
            }
        }
        return best;
    }

    /** The index read that finds every row for which {@code t} is true, or null if there is none. */
    private static Plan plan(Expr t) {
        if (t instanceof Expr.Compare c && c.op() != Expr.Cmp.NE) {
            return new Plan(c.column(), new Store.Lookup.Compare(c.op().symbol(), c.value()));
        }
        if (t instanceof Expr.In in && !in.negated()) {
            return new Plan(in.column(), new Store.Lookup.AnyOf(in.values()));
        }
        if (t instanceof Expr.Like l && !l.negated()) {
            LikePattern p = l.pattern();
            if (p.exact()) {
                return new Plan(l.column(), new Store.Lookup.Compare("=", p.prefix()));
            }
            if (!p.prefix().isEmpty()) {
                return new Plan(l.column(), new Store.Lookup.Prefix(p.prefix()));
            }
        }
        return null;
    }

    private record Match(String id, Map<String, Object> row) {}

    private Result select(Store store, Statement.Select q) {
        requireTable(store, q.db(), q.table());
        if (q.projection() instanceof Statement.Projection.Count && q.groupBy().isEmpty()) {
            long[] n = {0};
            scan(store, q.db(), q.table(), q.where(), (id, row) -> {
                if (matches(q.where(), id, row)) {
                    n[0]++;
                }
            });
            return new Result.Rows(List.of("count"), List.of(List.<Object>of(n[0])), false);
        }
        if (q.limit() != null && q.limit() > maxRows) {
            throw new QueryException("LIMIT " + q.limit() + " is over the maximum of " + maxRows);
        }
        if (Statement.Projection.aggregates(q.projection()) || !q.groupBy().isEmpty()) {
            return grouped(store, q);
        }
        long cap = q.limit() != null ? q.limit() : maxRows;
        List<Match> found = new ArrayList<>();
        boolean idOrder = q.orderBy() == null || (ID.equals(q.orderBy().column()) && !q.orderBy().descending());
        if (idOrder) {
            // Rows come in _id order, so the first matches are the answer, and one past the cap is enough to
            // know about truncation: stop there (a keyset page reads only its rows).
            long keep = cap + 1;
            scanWhile(store, q.db(), q.table(), q.where(), (id, row) -> {
                if (matches(q.where(), id, row)) {
                    found.add(new Match(id, row));
                }
                return found.size() < keep;
            });
        } else {
            scan(store, q.db(), q.table(), q.where(), (id, row) -> {
                if (matches(q.where(), id, row)) {
                    found.add(new Match(id, row));
                }
            });
        }
        if (!idOrder) {
            String col = q.orderBy().column();
            Comparator<Match> byValue = (a, b) -> Values.sortOrder(get(a.id, a.row, col), get(b.id, b.row, col));
            if (q.orderBy().descending()) {
                byValue = byValue.reversed();
            }
            found.sort(byValue.thenComparing(Match::id));
        }
        boolean truncated = q.limit() == null && found.size() > cap;
        List<Match> page = found.size() > cap ? found.subList(0, (int) cap) : found;

        List<String> columns;
        if (q.projection() instanceof Statement.Projection.Columns c) {
            columns = c.names();
        } else {
            Set<String> all = new LinkedHashSet<>();
            all.add(ID);
            for (Match m : page) {
                all.addAll(m.row.keySet());
            }
            columns = new ArrayList<>(all);
        }
        List<List<Object>> rows = new ArrayList<>(page.size());
        for (Match m : page) {
            List<Object> r = new ArrayList<>(columns.size());
            for (String c : columns) {
                r.add(get(m.id, m.row, c));
            }
            rows.add(r);
        }
        return new Result.Rows(columns, rows, truncated);
    }

    /** The rows of one group: its key and first-seen values for each GROUP BY column, and its aggregates. */
    private static final class Group {
        final Object[] keys;
        final Object[] values;
        final Acc[] accs;
        final Object[] results;

        Group(Object[] keys, Object[] values, List<Statement.Item.Aggregate> aggs) {
            this.keys = keys;
            this.values = values;
            this.accs = new Acc[aggs.size()];
            for (int i = 0; i < accs.length; i++) {
                accs[i] = Acc.of(aggs.get(i));
            }
            this.results = new Object[accs.length];
        }
    }

    private static final List<Object> NO_GROUP_COLUMNS = List.of();

    /**
     * SELECT with aggregates or GROUP BY: one row per distinct combination of the GROUP BY values (null and
     * missing are one value), or a single row without GROUP BY even over no rows. Groups are kept while the
     * matching rows are read, and the statement fails once there would be more than {@code maxGroups}.
     */
    private Result grouped(Store store, Statement.Select q) {
        List<Statement.Item> items = Statement.Projection.itemsOf(q.projection());
        List<String> by = q.groupBy();
        int k = by.size();
        List<Statement.Item.Aggregate> aggs = new ArrayList<>();
        for (Statement.Item i : items) {
            if (i instanceof Statement.Item.Aggregate a && !aggs.contains(a)) {
                aggs.add(a);
            }
        }
        Map<Object, Group> groups = new java.util.HashMap<>();
        scan(store, q.db(), q.table(), q.where(), (id, row) -> {
            if (!matches(q.where(), id, row)) {
                return;
            }
            Object key;
            if (k == 1) {
                key = Values.key(get(id, row, by.get(0)));
            } else if (k == 0) {
                key = NO_GROUP_COLUMNS;
            } else {
                Object[] ks = new Object[k];
                for (int i = 0; i < k; i++) {
                    ks[i] = Values.key(get(id, row, by.get(i)));
                }
                key = java.util.Arrays.asList(ks);
            }
            Group g = groups.get(key);
            if (g == null) {
                if (groups.size() >= maxGroups) {
                    throw new QueryException(String.format(java.util.Locale.ROOT,
                            "GROUP BY would make more than %,d groups", maxGroups));
                }
                Object[] values = new Object[k];
                for (int i = 0; i < k; i++) {
                    values[i] = get(id, row, by.get(i));
                }
                Object[] keys = k == 1 ? new Object[] {key} : ((List<?>) key).toArray();
                g = new Group(keys, values, aggs);
                groups.put(key, g);
            }
            for (int i = 0; i < aggs.size(); i++) {
                String c = aggs.get(i).column();
                g.accs[i].add(c == null ? Boolean.TRUE : get(id, row, c));
            }
        });
        if (k == 0 && groups.isEmpty()) {
            groups.put(NO_GROUP_COLUMNS, new Group(new Object[0], new Object[0], aggs));
        }
        List<Group> list = new ArrayList<>(groups.values());
        list.sort((a, b) -> {
            for (int i = 0; i < k; i++) {
                int c = Values.keyOrder(a.keys[i], b.keys[i]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        // In GROUP BY order, so that of two groups that cannot be summed, the same one is reported every time.
        for (Group g : list) {
            for (int i = 0; i < aggs.size(); i++) {
                g.results[i] = g.accs[i].result();
            }
        }
        if (q.orderBy() != null) {
            String col = q.orderBy().column();
            int at = col != null ? by.indexOf(col) : aggs.indexOf((Statement.Item.Aggregate) q.orderBy().key());
            Comparator<Group> byValue = col != null
                    ? (a, b) -> Values.sortOrder(a.values[at], b.values[at])
                    : (a, b) -> Values.sortOrder(a.results[at], b.results[at]);
            // The sort is stable: groups with equal values stay in GROUP BY order, in both directions.
            list.sort(q.orderBy().descending() ? byValue.reversed() : byValue);
        }
        long cap = q.limit() != null ? q.limit() : maxRows;
        boolean truncated = q.limit() == null && list.size() > cap;
        List<Group> page = list.size() > cap ? list.subList(0, (int) cap) : list;
        List<String> columns = new ArrayList<>(items.size());
        for (Statement.Item i : items) {
            columns.add(i.label());
        }
        List<List<Object>> rows = new ArrayList<>(page.size());
        for (Group g : page) {
            List<Object> r = new ArrayList<>(items.size());
            for (Statement.Item i : items) {
                r.add(i instanceof Statement.Item.Column c ? g.values[by.indexOf(c.name())] : g.results[aggs.indexOf(i)]);
            }
            rows.add(r);
        }
        return new Result.Rows(columns, rows, truncated);
    }

    /**
     * One aggregate over the rows of a group, fed each row's value of its column (TRUE for COUNT(*)). SUM and
     * AVG take numbers only: integers are added exactly, doubles in the order the rows come (_id order).
     */
    private abstract static class Acc {
        abstract void add(Object v);

        abstract Object result();

        static Acc of(Statement.Item.Aggregate a) {
            return switch (a.fn()) {
                case COUNT -> new Count();
                case SUM -> new Sum(false);
                case AVG -> new Sum(true);
                case MIN -> new Extreme(false);
                case MAX -> new Extreme(true);
            };
        }
    }

    /** COUNT(*) (fed TRUE for every row) and COUNT(column): the values that are not null. */
    private static final class Count extends Acc {
        long n;

        @Override
        void add(Object v) {
            if (v != null) {
                n++;
            }
        }

        @Override
        Object result() {
            return n;
        }
    }

    private static final class Sum extends Acc {
        final boolean average;
        long numbers;
        boolean anyInteger;
        boolean anyDouble;
        long integers;
        java.math.BigInteger bigIntegers; // once the integers no longer fit in a long
        double doubles = -0.0; // the identity of double addition, so a sum of -0.0 stays -0.0

        Sum(boolean average) {
            this.average = average;
        }

        @Override
        void add(Object v) {
            if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
                long x = ((Number) v).longValue();
                numbers++;
                anyInteger = true;
                if (bigIntegers != null) {
                    bigIntegers = bigIntegers.add(java.math.BigInteger.valueOf(x));
                } else {
                    try {
                        integers = Math.addExact(integers, x);
                    } catch (ArithmeticException overflow) {
                        bigIntegers = java.math.BigInteger.valueOf(integers).add(java.math.BigInteger.valueOf(x));
                    }
                }
            } else if (v instanceof Double || v instanceof Float) {
                numbers++;
                anyDouble = true;
                doubles += ((Number) v).doubleValue();
            }
        }

        @Override
        Object result() {
            if (numbers == 0) {
                return null;
            }
            if (!anyDouble && !average) {
                if (bigIntegers != null) {
                    if (bigIntegers.bitLength() > 63) {
                        throw new QueryException("SUM overflows a 64-bit integer");
                    }
                    return bigIntegers.longValue();
                }
                return integers;
            }
            double total = doubles;
            if (anyInteger) {
                total += bigIntegers != null ? bigIntegers.doubleValue() : (double) integers;
            }
            if (average) {
                total /= numbers;
            }
            if (!Double.isFinite(total)) {
                throw new QueryException((average ? "AVG" : "SUM") + " overflows a double");
            }
            return total;
        }
    }

    /** MIN and MAX in the ORDER BY order, over the values that are not null; of equal ones, the first. */
    private static final class Extreme extends Acc {
        final boolean max;
        Object best;

        Extreme(boolean max) {
            this.max = max;
        }

        @Override
        void add(Object v) {
            if (v != null) {
                if (best == null) {
                    best = v;
                } else {
                    int c = Values.sortOrder(v, best);
                    if (max ? c > 0 : c < 0) {
                        best = v;
                    }
                }
            }
        }

        @Override
        Object result() {
            return best;
        }
    }

    private static Object get(String id, Map<String, Object> row, String column) {
        return column.equals(ID) ? id : row.get(column);
    }

    /** True only if the condition is true; false and unknown both exclude the row. */
    private static boolean matches(Expr where, String id, Map<String, Object> row) {
        return where == null || Boolean.TRUE.equals(evaluate(where, id, row));
    }

    /** Three-valued: TRUE, FALSE, or null for unknown. */
    static Boolean evaluate(Expr e, String id, Map<String, Object> row) {
        if (e instanceof Expr.Compare c) {
            return Values.compare(get(id, row, c.column()), c.op(), c.value());
        }
        if (e instanceof Expr.IsNull n) {
            boolean isNull = get(id, row, n.column()) == null;
            return n.negated() != isNull;
        }
        if (e instanceof Expr.Not n) {
            Boolean v = evaluate(n.inner(), id, row);
            return v == null ? null : !v;
        }
        if (e instanceof Expr.And a) {
            boolean unknown = false;
            for (Expr t : a.terms()) {
                Boolean v = evaluate(t, id, row);
                if (Boolean.FALSE.equals(v)) {
                    return false;
                }
                unknown |= v == null;
            }
            return unknown ? null : Boolean.TRUE;
        }
        if (e instanceof Expr.Or o) {
            boolean unknown = false;
            for (Expr t : o.terms()) {
                Boolean v = evaluate(t, id, row);
                if (Boolean.TRUE.equals(v)) {
                    return true;
                }
                unknown |= v == null;
            }
            return unknown ? null : Boolean.FALSE;
        }
        if (e instanceof Expr.In in) {
            Boolean v = in.holds(get(id, row, in.column()));
            return v == null || !in.negated() ? v : Boolean.valueOf(!v);
        }
        if (e instanceof Expr.Like l) {
            Boolean v = l.holds(get(id, row, l.column()));
            return v == null || !l.negated() ? v : Boolean.valueOf(!v);
        }
        throw new IllegalArgumentException("unknown expression " + e);
    }

    private Stamp stamp(Store store) {
        // Ops from peers or an older run may carry newer stamps than this clock has seen.
        clock.observe(store.maxStamp());
        return clock.now();
    }

    private static StorageEngine.Planned<Result> none() {
        return new StorageEngine.Planned<>(List.of(), new Result.Write(0));
    }

    private static StorageEngine.Planned<Result> one(Op op) {
        return new StorageEngine.Planned<>(List.of(op), new Result.Write(1));
    }

    private static Result.Rows names(String column, List<String> names) {
        List<List<Object>> rows = new ArrayList<>(names.size());
        for (String n : names) {
            rows.add(List.of(n));
        }
        return new Result.Rows(List.of(column), rows, false);
    }

    private static void requireDb(Store store, String db) {
        if (!store.dbExists(db)) {
            throw noDb(db);
        }
    }

    private static void requireTable(Store store, String db, String table) {
        requireDb(store, db);
        if (!store.tableExists(db, table)) {
            throw noTable(db, table);
        }
    }

    private static QueryException noDb(String db) {
        return new QueryException("database '" + db + "' does not exist");
    }

    private static QueryException noTable(String db, String table) {
        return new QueryException("table '" + db + "." + table + "' does not exist");
    }
}
