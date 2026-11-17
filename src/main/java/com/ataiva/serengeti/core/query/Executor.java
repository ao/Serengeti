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
    /** The most groups a GROUP BY (or rows a SELECT DISTINCT) may hold while it runs, unless built with another cap. */
    public static final int DEFAULT_MAX_GROUPS = 100_000;
    /** The most values the COUNT(DISTINCT) of one statement may hold in all, unless built with another cap. */
    public static final int DEFAULT_MAX_DISTINCT = 100_000;
    /** The column that holds each row's key. */
    public static final String ID = "_id";

    private final StorageEngine engine;
    private final Hlc clock;
    private final int maxRows;
    private final int maxGroups;
    private final int maxDistinct;

    public Executor(StorageEngine engine, Hlc clock) {
        this(engine, clock, DEFAULT_MAX_ROWS);
    }

    public Executor(StorageEngine engine, Hlc clock, int maxRows) {
        this(engine, clock, maxRows, DEFAULT_MAX_GROUPS);
    }

    public Executor(StorageEngine engine, Hlc clock, int maxRows, int maxGroups) {
        this(engine, clock, maxRows, maxGroups, DEFAULT_MAX_DISTINCT);
    }

    public Executor(StorageEngine engine, Hlc clock, int maxRows, int maxGroups, int maxDistinct) {
        if (maxRows < 1 || maxGroups < 1 || maxDistinct < 1) {
            throw new IllegalArgumentException("maxRows, maxGroups and maxDistinct must be at least 1");
        }
        this.engine = engine;
        this.clock = clock;
        this.maxRows = maxRows;
        this.maxGroups = maxGroups;
        this.maxDistinct = maxDistinct;
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
            return databases(db -> true);
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
                if (c.columns().contains(ID)) {
                    throw new QueryException(c.columns().size() == 1 ? "_id needs no index: lookups by _id never scan"
                            : "_id cannot be a column of an index: lookups by _id never scan");
                }
                String key = Op.indexKey(c.columns());
                if (store.indexExists(c.db(), c.table(), key)) {
                    if (c.ifNotExists()) {
                        return none();
                    }
                    throw new QueryException("'" + c.db() + "." + c.table() + "' already has an index on "
                            + String.join(", ", c.columns()));
                }
                return one(new Op.CreateIndex(c.db(), c.table(), key, stamp(store)));
            });
        }
        if (s instanceof Statement.DropIndex d) {
            return engine.update(store -> {
                // As with DROP TABLE IF EXISTS: no table means no index, so nothing to do.
                if (d.ifExists() && !store.tableExists(d.db(), d.table())) {
                    return none();
                }
                requireTable(store, d.db(), d.table());
                String key = Op.indexKey(d.columns());
                if (!store.indexExists(d.db(), d.table(), key)) {
                    if (d.ifExists()) {
                        return none();
                    }
                    throw new QueryException("'" + d.db() + "." + d.table() + "' has no index on "
                            + String.join(", ", d.columns()));
                }
                return one(new Op.DropIndex(d.db(), d.table(), key, stamp(store)));
            });
        }
        if (s instanceof Statement.ShowIndexes i) {
            return engine.read(store -> {
                requireTable(store, i.db(), i.table());
                List<String> shown = new ArrayList<>();
                for (String key : store.indexes(i.db(), i.table())) {
                    shown.add(String.join(", ", Op.indexColumns(key)));
                }
                return names("column", shown);
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

    /** SHOW DATABASES, listing only the databases {@code shown} accepts. */
    public Result databases(java.util.function.Predicate<String> shown) {
        return engine.read(store -> names("database", store.databases().stream().filter(shown).toList()));
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
     * value, is an IN list or a LIKE with a literal prefix, or when terms of a top-level AND fix the first
     * columns of an index on several columns (see {@link #cheapestIndexedTerm}); else every row. Visitors
     * still check the whole condition, so the choice changes only speed.
     */
    private static void scan(Store store, String db, String table, Expr where,
            java.util.function.BiConsumer<String, Map<String, Object>> visitor) {
        List<Plan> plans = candidates(store, db, table, where);
        List<Plan> tried = new ArrayList<>();
        // A read that would gather too many ids visits nothing and says so: the next cheapest is tried (review 22f).
        for (Plan via; (via = cheapest(store, db, table, plans, tried)) != null; tried.add(via)) {
            if (store.forEachIndexed(db, table, via.column(), via.lookup(), visitor)) {
                return;
            }
        }
        store.forEachRow(db, table, visitor);
    }

    /** As {@link #scan}, stopping at the first row for which {@code visitor} returns false. */
    private static void scanWhile(Store store, String db, String table, Expr where,
            java.util.function.BiPredicate<String, Map<String, Object>> visitor) {
        List<Plan> plans = candidates(store, db, table, where);
        List<Plan> tried = new ArrayList<>();
        for (Plan via; (via = cheapest(store, db, table, plans, tried)) != null; tried.add(via)) {
            if (store.forEachIndexedWhile(db, table, via.column(), via.lookup(), visitor)) {
                return;
            }
        }
        store.forEachRowWhile(db, table, visitor);
    }

    /**
     * An index read for a condition: what {@code lookup} finds in the index {@code column} (a column, or for
     * an index on several columns their names joined by commas, see {@link Op#indexKey}).
     */
    record Plan(String column, Store.Lookup lookup) {
        /** How many columns of the index the read names: 1, or for an index on several columns those it fixes. */
        int named() {
            return lookup instanceof Store.Lookup.Composite c ? c.named() : 1;
        }
    }

    /** The most tuples of values one read of an index on several columns looks up: the IN lists' product. */
    static final int MAX_LOOKUP_TUPLES = 1_000;

    /**
     * The index read for {@code where} that visits the fewest rows, and of equal ones the one that names the most
     * columns, then the first: a term of {@code where} (itself, or one of a top-level AND) on an indexed column
     * or _id, or for an index on several columns the terms of the AND that fix its first columns (see
     * {@link #composite}). Null when there is none. Every estimate is exact but those of _id ranges, which are
     * the table's rows, so fewer columns named can mean fewer rows (review 22f).
     */
    static Plan cheapestIndexedTerm(Store store, String db, String table, Expr where) {
        return cheapest(store, db, table, candidates(store, db, table, where), List.of());
    }

    /** The reads of an index that could answer {@code where}: one per term, then one per index on several columns. */
    static List<Plan> candidates(Store store, String db, String table, Expr where) {
        List<Expr> terms = where instanceof Expr.And a ? a.terms() : where == null ? List.of() : List.of(where);
        List<Plan> plans = new ArrayList<>();
        for (Expr t : terms) {
            Plan p = plan(t);
            if (p != null) {
                plans.add(p);
            }
        }
        if (!terms.isEmpty()) {
            for (List<String> columns : store.compositeIndexes(db, table)) {
                Plan p = composite(columns, terms);
                if (p != null) {
                    plans.add(p);
                }
            }
        }
        return plans;
    }

    /**
     * Of {@code plans} not in {@code skip}, the one that visits the fewest rows, as {@link #cheapestIndexedTerm}.
     * Each estimate stops counting once it passes the best so far, so a large range costs little to rule out.
     */
    static Plan cheapest(Store store, String db, String table, List<Plan> plans, java.util.Collection<Plan> skip) {
        Plan best = null;
        long bestCount = Long.MAX_VALUE;
        for (Plan p : plans) {
            if (skip.contains(p)) {
                continue;
            }
            long n = store.indexEstimate(db, table, p.column(), p.lookup(), bestCount);
            if (n >= 0 && (best == null || n < bestCount || (n == bestCount && p.named() > best.named()))) {
                best = p;
                bestCount = n;
            }
        }
        return best;
    }

    /**
     * The read of the index on {@code columns} for the terms of a top-level AND: for each of its columns in
     * turn, the values of an {@code =}, IN or wildcard-free LIKE term on it (the term with the fewest), while
     * the product of the lists stays within {@link #MAX_LOOKUP_TUPLES}; then every range term ({@code <},
     * {@code <=}, {@code >}, {@code >=}, a LIKE with a literal prefix) on the next column. Null if the terms
     * fix no column.
     */
    static Plan composite(List<String> columns, List<Expr> terms) {
        List<List<Object>> equal = new ArrayList<>();
        long tuples = 1;
        int i = 0;
        for (; i < columns.size(); i++) {
            List<Object> values = equalValues(columns.get(i), terms);
            if (values == null || tuples * values.size() > MAX_LOOKUP_TUPLES) {
                break;
            }
            tuples *= values.size();
            equal.add(values);
        }
        List<Store.Lookup> range = new ArrayList<>();
        if (i < columns.size()) {
            String c = columns.get(i);
            for (Expr t : terms) {
                if (t instanceof Expr.Compare x && x.column().equals(c) && x.op() != Expr.Cmp.NE && x.op() != Expr.Cmp.EQ) {
                    range.add(new Store.Lookup.Compare(x.op().symbol(), x.value()));
                } else if (t instanceof Expr.Like l && l.column().equals(c) && !l.negated() && !l.pattern().exact()
                        && !l.pattern().prefix().isEmpty()) {
                    range.add(new Store.Lookup.Prefix(l.pattern().prefix()));
                }
            }
        }
        if (equal.isEmpty() && range.isEmpty()) {
            return null;
        }
        return new Plan(Op.indexKey(columns), new Store.Lookup.Composite(equal, range));
    }

    /** The values one of {@code terms} says {@code column} equals (one of them, for IN), the shortest list; null if none. */
    private static List<Object> equalValues(String column, List<Expr> terms) {
        List<Object> best = null;
        for (Expr t : terms) {
            List<Object> values = null;
            if (t instanceof Expr.Compare c && c.column().equals(column) && c.op() == Expr.Cmp.EQ) {
                values = java.util.Collections.singletonList(c.value());
            } else if (t instanceof Expr.In in && in.column().equals(column) && !in.negated()) {
                values = in.values();
            } else if (t instanceof Expr.Like l && l.column().equals(column) && !l.negated() && l.pattern().exact()) {
                values = List.of(l.pattern().prefix());
            }
            if (values != null && (best == null || values.size() < best.size())) {
                best = values;
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

    /**
     * True when a SELECT without aggregates or groups answers in _id order, so it reads rows in that order and
     * stops after its page instead of sorting: without ORDER BY, or with _id ascending as the first key (_id
     * is unique, so the keys after it never decide anything).
     */
    static boolean readsInIdOrder(Statement.Select q) {
        if (q.orderBy().isEmpty()) {
            return true;
        }
        Statement.OrderBy first = q.orderBy().get(0);
        return ID.equals(first.column()) && !first.descending();
    }

    private Result select(Store store, Statement.Select q) {
        requireTable(store, q.db(), q.table());
        if (q.projection() instanceof Statement.Projection.Count && q.groupBy().isEmpty() && q.having() == null) {
            if (q.where() == null) {
                // Kept with every change, so counting a table does not visit its rows.
                return new Result.Rows(List.of("count"), List.of(List.<Object>of(store.visibleRows(q.db(), q.table()))),
                        false);
            }
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
        if (Statement.Projection.aggregates(q.projection()) || !q.groups().isEmpty()) {
            return grouped(store, q);
        }
        long cap = q.limit() != null ? q.limit() : maxRows;
        List<Match> found = new ArrayList<>();
        boolean idOrder = readsInIdOrder(q);
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
            Comparator<Match> byKeys = null;
            for (Statement.OrderBy o : q.orderBy()) {
                String col = o.column();
                Comparator<Match> byValue = (a, b) -> Values.sortOrder(get(a.id, a.row, col), get(b.id, b.row, col));
                if (o.descending()) {
                    byValue = byValue.reversed();
                }
                byKeys = byKeys == null ? byValue : byKeys.thenComparing(byValue);
            }
            found.sort(byKeys.thenComparing(Match::id));
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

        Group(Object[] keys, Object[] values, List<Statement.Item.Aggregate> aggs, Budget budget) {
            this.keys = keys;
            this.values = values;
            this.accs = new Acc[aggs.size()];
            for (int i = 0; i < accs.length; i++) {
                accs[i] = Acc.of(aggs.get(i), budget);
            }
            this.results = new Object[accs.length];
        }
    }

    /** How many more values the COUNT(DISTINCT) aggregates of one statement may hold, in all groups together. */
    private static final class Budget {
        final int max;
        int left;

        Budget(int max) {
            this.max = max;
            this.left = max;
        }

        void take() {
            if (left == 0) {
                throw new QueryException(String.format(java.util.Locale.ROOT,
                        "COUNT(DISTINCT) would hold more than %,d values", max));
            }
            left--;
        }
    }

    private static final List<Object> NO_GROUP_COLUMNS = List.of();

    /**
     * SELECT with aggregates, GROUP BY or DISTINCT: one row per distinct combination of the GROUP BY values
     * (null and missing are one value), or a single row without GROUP BY even over no rows. Groups are kept
     * while the matching rows are read, and the statement fails once there would be more than {@code maxGroups}.
     * Every aggregate, of the list and of HAVING, is computed for every group; then HAVING drops groups, and
     * ORDER BY and LIMIT apply to the rest.
     */
    private Result grouped(Store store, Statement.Select q) {
        List<Statement.Item> items = Statement.Projection.itemsOf(q.projection());
        List<String> by = q.groups();
        int k = by.size();
        List<Statement.Item.Aggregate> aggs = new ArrayList<>();
        List<Statement.Item> tested = Statement.Select.havingItems(q.having());
        for (List<Statement.Item> from : List.of(items, tested)) {
            for (Statement.Item i : from) {
                if (i instanceof Statement.Item.Aggregate a && !aggs.contains(a)) {
                    aggs.add(a);
                }
            }
        }
        Budget budget = new Budget(maxDistinct);
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
                    throw new QueryException(String.format(java.util.Locale.ROOT, q.distinct()
                            ? "SELECT DISTINCT would hold more than %,d rows"
                            : "GROUP BY would make more than %,d groups", maxGroups));
                }
                Object[] values = new Object[k];
                for (int i = 0; i < k; i++) {
                    values[i] = get(id, row, by.get(i));
                }
                Object[] keys = k == 1 ? new Object[] {key} : ((List<?>) key).toArray();
                g = new Group(keys, values, aggs, budget);
                groups.put(key, g);
            }
            for (int i = 0; i < aggs.size(); i++) {
                String c = aggs.get(i).column();
                g.accs[i].add(c == null ? Boolean.TRUE : get(id, row, c));
            }
        });
        if (k == 0 && groups.isEmpty()) {
            groups.put(NO_GROUP_COLUMNS, new Group(new Object[0], new Object[0], aggs, budget));
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
        if (q.having() != null) {
            // HAVING reads a group as a row whose columns are the labels of its GROUP BY values and aggregates.
            int idAt = by.indexOf(ID);
            List<Group> kept = new ArrayList<>();
            for (Group g : list) {
                Map<String, Object> row = new java.util.HashMap<>();
                for (int i = 0; i < k; i++) {
                    row.put(by.get(i), g.values[i]);
                }
                for (int i = 0; i < aggs.size(); i++) {
                    row.put(aggs.get(i).label(), g.results[i]);
                }
                if (matches(q.having(), idAt < 0 ? null : (String) g.values[idAt], row)) {
                    kept.add(g);
                }
            }
            list = kept;
        }
        if (!q.orderBy().isEmpty()) {
            Comparator<Group> byKeys = null;
            for (Statement.OrderBy o : q.orderBy()) {
                String col = o.column();
                int at = col != null ? by.indexOf(col) : aggs.indexOf((Statement.Item.Aggregate) o.key());
                Comparator<Group> byValue = col != null
                        ? (a, b) -> Values.sortOrder(a.values[at], b.values[at])
                        : (a, b) -> Values.sortOrder(a.results[at], b.results[at]);
                if (o.descending()) {
                    byValue = byValue.reversed();
                }
                byKeys = byKeys == null ? byValue : byKeys.thenComparing(byValue);
            }
            // The sort is stable: groups equal on every key stay in GROUP BY order, in both directions.
            list.sort(byKeys);
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

        static Acc of(Statement.Item.Aggregate a, Budget budget) {
            if (a.distinct()) {
                return new CountDistinct(budget);
            }
            return switch (a.fn()) {
                case COUNT -> new Count();
                case SUM -> new Sum(false);
                case AVG -> new Sum(true);
                case MIN -> new Extreme(false);
                case MAX -> new Extreme(true);
            };
        }
    }

    /** COUNT(DISTINCT column): the values that are not null, each once, equal as GROUP BY makes them. */
    private static final class CountDistinct extends Acc {
        final Set<Object> seen = new HashSet<>();
        final Budget budget;

        CountDistinct(Budget budget) {
            this.budget = budget;
        }

        @Override
        void add(Object v) {
            if (v != null) {
                Object key = Values.key(v);
                if (!seen.contains(key)) {
                    budget.take();
                    seen.add(key);
                }
            }
        }

        @Override
        Object result() {
            return (long) seen.size();
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
