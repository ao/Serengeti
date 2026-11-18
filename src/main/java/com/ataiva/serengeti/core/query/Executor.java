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
     *     {@code _id}, a LIMIT over the cap, an OFFSET that reaches past {@link #MAX_OFFSET_ROWS}); nothing has
     *     been written in that case
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

    /**
     * INSERT, tuple by tuple in order. With ON CONFLICT, a tuple whose {@code _id} is a live row (one SELECT
     * reads: not a tombstone, and the current version when the row is in conflict) is skipped (DO NOTHING) or
     * has the assignments applied to that row (DO UPDATE), reading it as UPDATE does; its other values are not
     * written. Without it, such a tuple fails the statement. All tuples are planned before any is written.
     */
    private StorageEngine.Planned<Result> insert(Store store, Statement.Insert i) {
        requireTable(store, i.db(), i.table());
        Statement.OnConflict onConflict = i.onConflict();
        if (onConflict != null) {
            for (Statement.Assignment a : onConflict.assignments()) {
                if (a.column().equals(ID)) {
                    throw new QueryException("_id cannot be changed");
                }
            }
        }
        Stamp at = stamp(store);
        int idIndex = i.columns().indexOf(ID);
        List<Op> ops = new ArrayList<>(i.rows().size());
        List<String> ids = new ArrayList<>(i.rows().size());
        Set<String> seen = new HashSet<>();
        long updated = 0;
        long skipped = 0;
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
            boolean twice = !seen.add(id);
            if (twice && onConflict != null) {
                throw new QueryException("_id '" + Lexer.shorten(id) + "' is listed twice: an INSERT ... ON CONFLICT"
                        + " takes each _id once");
            }
            Map<String, Object> existing = store.row(i.db(), i.table(), id);
            if (twice || (existing != null && onConflict == null)) {
                throw new QueryException("a row with _id '" + Lexer.shorten(id) + "' already exists in '"
                        + i.db() + "." + i.table() + "'");
            }
            if (existing != null) {
                if (onConflict.doNothing()) {
                    skipped++;
                    continue;
                }
                row = new LinkedHashMap<>(existing);
                for (Statement.Assignment a : onConflict.assignments()) {
                    row.put(a.column(), a.value() instanceof Statement.Arithmetic x ? compute(id, existing, a, x)
                            : a.value());
                }
                updated++;
            }
            ops.add(new Op.Put(i.db(), i.table(), id, row, at));
            ids.add(id);
        }
        Result.Write.Upsert upsert = onConflict == null ? null
                : new Result.Write.Upsert(ops.size() - updated, updated, skipped);
        return new StorageEngine.Planned<>(ops, new Result.Write(ops.size(), ids, upsert));
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
        // Every op is planned before any is applied, so a row is changed once even when the WHERE reads the
        // column it sets, and a row that cannot be computed fails the statement with nothing written.
        scan(store, u.db(), u.table(), u.where(), (id, row) -> {
            if (matches(u.where(), id, row)) {
                Map<String, Object> changed = new LinkedHashMap<>(row);
                for (Statement.Assignment a : u.assignments()) {
                    changed.put(a.column(), a.value() instanceof Statement.Arithmetic x ? compute(id, row, a, x)
                            : a.value());
                }
                ops.add(new Op.Put(u.db(), u.table(), id, changed, at));
            }
        });
        return new StorageEngine.Planned<>(ops, new Result.Write(ops.size()));
    }

    /**
     * The value of {@code SET c = column + number} for one row, from the row as it was before the statement:
     * null when the column is missing or null, as in SQL.
     */
    private static Object compute(String id, Map<String, Object> row, Statement.Assignment a, Statement.Arithmetic x) {
        Object v = row.get(x.column());
        if (v == null) {
            return null;
        }
        if (!Values.isNumber(v)) {
            throw cannotSet(id, a, x.column() + " is " + Values.kind(v));
        }
        try {
            return Values.add(v, x.minus(), x.number());
        } catch (ArithmeticException e) {
            throw cannotSet(id, a, "the result " + e.getMessage());
        }
    }

    private static QueryException cannotSet(String id, Statement.Assignment a, String why) {
        return new QueryException("cannot set " + Printer.assignment(a) + " in the row with _id '" + Lexer.shorten(id)
                + "': " + why);
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
     * value, is an IN list or a LIKE with a literal prefix, is an OR whose every branch an index answers, or
     * when terms of a top-level AND fix the first columns of an index on several columns (see
     * {@link #cheapestIndexedTerm}); else every row. Visitors still check the whole condition, so the choice
     * changes only speed.
     */
    private static void scan(Store store, String db, String table, Expr where,
            java.util.function.BiConsumer<String, Map<String, Object>> visitor) {
        List<Plan> plans = candidates(store, db, table, where);
        scan(store, db, table, plans, cheapest(store, db, table, plans, List.of()), visitor);
    }

    /** As above, with the candidate reads and the cheapest of them ({@code first}, null for none) already found. */
    private static void scan(Store store, String db, String table, List<Plan> plans, Plan first,
            java.util.function.BiConsumer<String, Map<String, Object>> visitor) {
        scanWhile(store, db, table, plans, first, (id, row) -> {
            visitor.accept(id, row);
            return true;
        });
    }

    /**
     * As {@link #scan}, stopping at the first row for which {@code visitor} returns false: a page. A read that
     * gathers its ids before it visits a row (see {@link #gathers}) costs its whole size even when the page
     * fills after a few rows, which a read that streams in _id order would not (review 22i: `a < 31 OR b < 31
     * LIMIT 10` took 4 ms where the scan takes 0.1). So such a page first streams: through the cheapest read
     * that does not gather, or the table, for a share of what the gathered read would gather. If the page is
     * not full by then, the gathered read goes on after the last _id streamed, so no row is read twice.
     */
    private static void scanWhile(Store store, String db, String table, Expr where,
            java.util.function.BiPredicate<String, Map<String, Object>> visitor) {
        List<Plan> plans = candidates(store, db, table, where);
        Choice first = cheapestChoice(store, db, table, plans, List.of());
        // A read that would gather more than the store gathers is refused anyway: no streaming first (review 22j).
        if (first != null && gathers(first.plan()) && first.rows() <= Store.MATERIALIZE_LIMIT) {
            List<Plan> streaming = new ArrayList<>();
            for (Plan p : plans) {
                if (!gathers(p)) {
                    streaming.add(p);
                }
            }
            Choice s = streaming.isEmpty() ? null : cheapestChoice(store, db, table, streaming, List.of());
            // A row streamed by the table or an _id range costs about half what gathering an id does; one
            // streamed through an index, about four times as much.
            long budget = s == null || ID.equals(s.plan().column()) ? first.rows() / STREAM_SHARE_OF_SCAN
                    : first.rows() / STREAM_SHARE_OF_INDEX_READ;
            if (budget > 0) {
                String[] last = {null};
                long[] n = {0};
                boolean[] full = {false};
                java.util.function.BiPredicate<String, Map<String, Object>> counted = (id, row) -> {
                    last[0] = id;
                    if (!visitor.test(id, row)) {
                        full[0] = true;
                        return false;
                    }
                    return ++n[0] < budget;
                };
                boolean read = true;
                if (s == null) {
                    store.forEachRowWhile(db, table, counted);
                } else {
                    read = store.forEachIndexedWhile(db, table, s.plan().column(), s.plan().lookup(), counted);
                }
                if (read) {
                    // Full, or the streamed read ran out before its budget: it visited every row that may match.
                    if (full[0] || n[0] < budget) {
                        return;
                    }
                    String after = last[0];
                    // Each read, and the scan if every read refuses, starts after it (review 22j).
                    scanWhile(store, db, table, plans, first.plan(), after, visitor);
                    return;
                }
            }
        }
        scanWhile(store, db, table, plans, first == null ? null : first.plan(), visitor);
    }

    /** A page streams for at most 1 in so many of the ids a gathered read would gather, through the table or _id. */
    static final int STREAM_SHARE_OF_SCAN = 2;

    /** As {@link #STREAM_SHARE_OF_SCAN}, through an index read that streams. */
    static final int STREAM_SHARE_OF_INDEX_READ = 16;

    /**
     * True if the store gathers the ids of {@code p}'s read into one set before it visits a row: a union, a
     * range or prefix on a column, or a read of an index on several columns that leaves columns open. Reads of
     * _id, of one value and of an IN list on one column, and of whole tuples stream.
     */
    static boolean gathers(Plan p) {
        if (p.isUnion()) {
            return true;
        }
        if (ID.equals(p.column())) {
            return false;
        }
        Store.Lookup l = p.lookup();
        if (l instanceof Store.Lookup.Compare c) {
            return !c.op().equals("=");
        }
        if (l instanceof Store.Lookup.Prefix) {
            return true;
        }
        if (l instanceof Store.Lookup.Composite c) {
            return !c.range().isEmpty() || c.equal().size() < p.column().split(",").length;
        }
        return false;
    }

    /** As above, with the candidate reads and the cheapest of them ({@code first}, null for none) already found. */
    private static void scanWhile(Store store, String db, String table, List<Plan> plans, Plan first,
            java.util.function.BiPredicate<String, Map<String, Object>> visitor) {
        scanWhile(store, db, table, plans, first, null, visitor);
    }

    /** As above, visiting only rows whose _id comes after {@code after} (all when it is null). */
    private static void scanWhile(Store store, String db, String table, List<Plan> plans, Plan first, String after,
            java.util.function.BiPredicate<String, Map<String, Object>> visitor) {
        List<Plan> tried = new ArrayList<>();
        // A read that would gather too many ids visits nothing and says so: the next cheapest is tried (review 22f).
        for (Plan via = first; via != null; via = cheapest(store, db, table, plans, tried)) {
            if (via.isUnion() ? store.forEachUnionWhile(db, table, via.reads(), after, visitor)
                    : store.forEachIndexedWhile(db, table, via.column(), via.lookup(), after, visitor)) {
                return;
            }
            tried.add(via);
        }
        store.forEachRowWhile(db, table, after, visitor);
    }

    /**
     * An index read for a condition: what {@code lookup} finds in the index {@code column} (a column, or for
     * an index on several columns their names joined by commas, see {@link Op#indexKey}); or, for an OR, the
     * union of the reads in {@code union} (column and lookup null), each a read of one index.
     */
    record Plan(String column, Store.Lookup lookup, List<Plan> union) {
        Plan(String column, Store.Lookup lookup) {
            this(java.util.Objects.requireNonNull(column), java.util.Objects.requireNonNull(lookup), List.of());
        }

        /** The read of every row one of {@code reads} finds; none of them may be a union. */
        static Plan union(List<Plan> reads) {
            return new Plan(null, null, List.copyOf(reads));
        }

        boolean isUnion() {
            return column == null;
        }

        /**
         * How many columns of the index the read names: 1, or for an index on several columns those it fixes; 0
         * for a union, so that a read of one index is taken over a union that visits as many rows.
         */
        int named() {
            return isUnion() ? 0 : lookup instanceof Store.Lookup.Composite c ? c.named() : 1;
        }

        /** The reads of a union, as the store takes them. */
        List<Store.IndexRead> reads() {
            List<Store.IndexRead> out = new ArrayList<>(union.size());
            for (Plan p : union) {
                out.add(new Store.IndexRead(p.column, p.lookup));
            }
            return out;
        }

        /** How many values the read looks up: those of an IN list, the tuples of an index on several columns, or 1. */
        long values() {
            if (lookup instanceof Store.Lookup.AnyOf a) {
                return a.values().size();
            }
            long n = 1;
            if (lookup instanceof Store.Lookup.Composite c) {
                for (List<Object> e : c.equal()) {
                    n *= e.size();
                }
            }
            return n;
        }
    }

    /** The most tuples of values one read of an index on several columns looks up: the IN lists' product. */
    static final int MAX_LOOKUP_TUPLES = 1_000;

    /** The most reads one OR is read through (those of the ORs in its branches included); with more, it is a scan. */
    static final int MAX_UNION_READS = 32;

    /** The most values ({@link Plan#values}) the reads of one OR look up in all; with more, it is a scan. */
    static final int MAX_UNION_VALUES = 4_000;

    /**
     * An OR is read through indexes only if its reads visit at most this share (1 in so many) of the table's rows:
     * a row read through an index costs about 10 times one read by a scan (docs/QUERY.md, "OR through indexes"),
     * so a larger union would be slower than the scan.
     */
    static final int UNION_SHARE = 16;

    /** The most ids of an _id range or prefix that a branch of a union may read (counted when it is planned). */
    static final long UNION_ID_COUNT = 1_024;

    /**
     * The index read for {@code where} that visits the fewest rows, and of equal ones the one that names the most
     * columns, then the first: a term of {@code where} (itself, or one of a top-level AND) on an indexed column
     * or _id, an OR of such reads ({@link #union}), or for an index on several columns the terms of the AND that
     * fix its first columns (see {@link #composite}). Null when there is none. Every estimate is exact but those
     * of _id ranges, which are the table's rows, and of unions, which count a row each read finds, so fewer
     * columns named can mean fewer rows (review 22f).
     */
    static Plan cheapestIndexedTerm(Store store, String db, String table, Expr where) {
        return cheapest(store, db, table, candidates(store, db, table, where), List.of());
    }

    /**
     * The reads of an index that could answer {@code where}: one per term (for an OR, the union of its branches'
     * reads, see {@link #union}), then one per index on several columns.
     */
    static List<Plan> candidates(Store store, String db, String table, Expr where) {
        List<Expr> terms = terms(where);
        List<Plan> plans = new ArrayList<>();
        // Several range terms on _id (`_id >= 'a' AND _id < 'b'`) are one read between their bounds, first, so
        // that it wins a tie with a read of one bound to the end of the table (docs3).
        List<Store.Lookup> idRanges = new ArrayList<>();
        for (Expr t : terms) {
            if (t instanceof Expr.Compare c && ID.equals(c.column()) && c.op() != Expr.Cmp.NE && c.op() != Expr.Cmp.EQ) {
                idRanges.add(new Store.Lookup.Compare(c.op().symbol(), c.value()));
            } else if (t instanceof Expr.Like l && ID.equals(l.column()) && !l.negated() && !l.pattern().exact()
                    && !l.pattern().prefix().isEmpty()) {
                idRanges.add(new Store.Lookup.Prefix(l.pattern().prefix()));
            }
        }
        if (idRanges.size() > 1) {
            plans.add(new Plan(ID, new Store.Lookup.Composite(List.of(), idRanges)));
        }
        for (Expr t : terms) {
            Plan p = t instanceof Expr.Or o ? union(store, db, table, o) : plan(t);
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
        Choice c = cheapestChoice(store, db, table, plans, skip);
        return c == null ? null : c.plan();
    }

    /**
     * A read {@link #cheapest} chose, and its estimate ({@link #estimate}): exact, but the table's rows for an _id
     * range or prefix, and for a union the sum of its reads'.
     */
    record Choice(Plan plan, long rows) {}

    /** As {@link #cheapest}, with the estimate of the read it chose; null when there is none. */
    static Choice cheapestChoice(Store store, String db, String table, List<Plan> plans, java.util.Collection<Plan> skip) {
        return cheapestChoice(store, db, table, plans, skip, Long.MAX_VALUE);
    }

    /** As above, of the reads of at most {@code atMost} rows. */
    private static Choice cheapestChoice(Store store, String db, String table, List<Plan> plans,
            java.util.Collection<Plan> skip, long atMost) {
        Plan best = null;
        long bestCount = atMost;
        for (Plan p : plans) {
            if (skip.contains(p)) {
                continue;
            }
            // An _id range or prefix is counted only up to the caller's cap, not the best so far: counted against the
            // best, the choice depended on the order of the terms, and pages that stopped after a few rows counted
            // thousands of ids first (review 22h). Without a cap its estimate is the table's rows, as before.
            long n;
            if (ID.equals(p.column()) && atMost != Long.MAX_VALUE) {
                // A branch of a union: its _id range is counted up to UNION_ID_COUNT ids, and one past that is too
                // large to be a branch (review 22i: counting it up to the share made a rejected OR cost more than
                // the page its scan reads).
                long cap = Math.min(atMost, UNION_ID_COUNT);
                n = estimate(store, db, table, p, cap);
                if (n > cap) {
                    n = Long.MAX_VALUE;
                }
            } else {
                n = estimate(store, db, table, p, ID.equals(p.column()) ? atMost : bestCount);
            }
            if (n >= 0 && n <= bestCount && (best == null || n < bestCount || p.named() > best.named())) {
                best = p;
                bestCount = n;
            }
        }
        return best == null ? null : new Choice(best, bestCount);
    }

    /**
     * How many rows {@code p} visits at most, as {@link Store#indexEstimate}; for a union the sum of its reads'
     * (rows two of them find are counted twice), or -1 if one of them has no index. Once the count passes
     * {@code atMost} it may stop there. A union's reads are counted as {@link #union} counted them, up to
     * 1/{@link #UNION_SHARE} of the table's rows, which their sum does not pass: an _id range or prefix among
     * them is then counted, not taken as the table's rows.
     */
    static long estimate(Store store, String db, String table, Plan p, long atMost) {
        if (!p.isUnion()) {
            return store.indexEstimate(db, table, p.column(), p.lookup(), atMost);
        }
        long cap = Math.min(atMost, store.visibleRows(db, table) / UNION_SHARE);
        long n = 0;
        for (Plan r : p.union()) {
            long m = store.indexEstimate(db, table, r.column(), r.lookup(), cap - n);
            if (m < 0) {
                return -1;
            }
            n += m;
            if (n > cap) {
                // Only past atMost: union() made the plan with a sum within the share, in this same read.
                return Math.max(n, atMost == Long.MAX_VALUE ? atMost : atMost + 1);
            }
        }
        return n;
    }

    /**
     * The read of {@code or} as the union of its branches' reads, or null if it has none. Each branch (one term,
     * or an AND of terms) is planned as a whole WHERE is: its cheapest read ({@link #candidates}, {@link
     * #cheapest}), which may be the union of an OR among its terms, whose reads it then takes. A branch that no
     * index answers leaves the OR to a scan, and so do reads that would visit more than 1/{@link #UNION_SHARE} of
     * the table's rows in all (their estimates' sum), more than {@link #MAX_UNION_READS} reads, and more than
     * {@link #MAX_UNION_VALUES} values. The rows the union visits are a superset of those the OR matches; callers
     * check the whole condition on each.
     */
    private static Plan union(Store store, String db, String table, Expr.Or or) {
        if (or.terms().size() > MAX_UNION_READS) {
            return null;
        }
        long share = store.visibleRows(db, table) / UNION_SHARE;
        long rows = 0;
        List<Plan> reads = new ArrayList<>();
        long values = 0;
        for (Expr branch : or.terms()) {
            // Each estimate stops once the sum would pass the share, so a large branch costs little to rule out;
            // an _id range or prefix is counted up to it (Store#indexEstimate).
            Choice c = cheapestChoice(store, db, table, candidates(store, db, table, branch), List.of(), share - rows);
            if (c == null) {
                return null;
            }
            rows += c.rows();
            Plan p = c.plan();
            for (Plan r : p.isUnion() ? p.union() : List.of(p)) {
                reads.add(r);
                values += r.values();
            }
            if (reads.size() > MAX_UNION_READS || values > MAX_UNION_VALUES) {
                return null;
            }
        }
        return Plan.union(reads);
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
        Expr t = equalTerm(column, terms);
        return t == null ? null : equalValues(t);
    }

    /** The term of {@code terms} that {@link #equalValues(String, List)} takes its values from, or null. */
    private static Expr equalTerm(String column, List<Expr> terms) {
        Expr best = null;
        int size = 0;
        for (Expr t : terms) {
            List<Object> values = equalValues(t);
            if (values != null && column.equals(columnOf(t)) && (best == null || values.size() < size)) {
                best = t;
                size = values.size();
            }
        }
        return best;
    }

    /** The values an =, IN or wildcard-free LIKE term says its column equals; null for any other term. */
    private static List<Object> equalValues(Expr t) {
        if (t instanceof Expr.Compare c && c.op() == Expr.Cmp.EQ) {
            return java.util.Collections.singletonList(c.value());
        }
        if (t instanceof Expr.In in && !in.negated()) {
            return in.values();
        }
        if (t instanceof Expr.Like l && !l.negated() && l.pattern().exact()) {
            return List.of(l.pattern().prefix());
        }
        return null;
    }

    private static String columnOf(Expr t) {
        return t instanceof Expr.Compare c ? c.column() : t instanceof Expr.In in ? in.column()
                : t instanceof Expr.Like l ? l.column() : null;
    }

    private static List<Expr> terms(Expr where) {
        return where instanceof Expr.And a ? a.terms() : where == null ? List.of() : List.of(where);
    }

    /**
     * A read of an index in the order of an ORDER BY: the index (by the {@code column} field of its ops), what it
     * reads (values of its first columns, then comparisons on the next), the ORDER BY keys as columns of it, the
     * direction of _id among rows equal on them, and whether it reads exactly the rows the WHERE matches without
     * grouping many keys: every term of the WHERE is one the read answers, so that each row it visits is one of
     * the answer, and the keys reach the index's last column, so that no group holds more keys than streams.
     */
    record OrderedPlan(String index, Store.Lookup.Composite where, List<Store.SortKey> keys, boolean idDescending,
            boolean exact) {}

    /**
     * An exact ordered read is always taken. Any other reads at most this share (1 in so many) of the rows the
     * read without order would visit: the rows its index read finds, or the table's. If its page is not full by
     * then, the rows are read and sorted as without it.
     */
    static final int ORDERED_SHARE = 96;

    /**
     * The ORDER BY keys of {@code q} that can decide anything: those before the first _id key, less those on a
     * column that a term of the WHERE's top-level AND fixes to one value (every row that matches holds that
     * value, equal as ORDER BY compares). Empty when there are none; then the rows come in _id order,
     * descending if the first _id key says so ({@link #idDescending}).
     */
    private static List<Statement.OrderBy> deciding(Statement.Select q) {
        List<Expr> terms = terms(q.where());
        List<Statement.OrderBy> out = new ArrayList<>();
        for (Statement.OrderBy o : q.orderBy()) {
            if (ID.equals(o.column())) {
                break;
            }
            List<Object> values = o.column() == null ? null : equalValues(o.column(), terms);
            if (values == null || values.size() != 1) {
                out.add(o);
            }
        }
        return out;
    }

    /** True if the first _id key of {@code q}'s ORDER BY is descending. */
    private static boolean idDescending(Statement.Select q) {
        for (Statement.OrderBy o : q.orderBy()) {
            if (ID.equals(o.column())) {
                return o.descending();
            }
        }
        return false;
    }

    /**
     * The ordered read of an index for a SELECT without groups or aggregates, or null: of the indexes on whose
     * columns its deciding ORDER BY keys lie (see {@link #ordered}), an exact read first, then the one that names
     * the most columns of the WHERE, then the first.
     */
    static OrderedPlan orderedPlan(Store store, String db, String table, Statement.Select q) {
        List<Statement.OrderBy> keys = deciding(q);
        if (keys.isEmpty()) {
            return null;
        }
        List<Expr> terms = terms(q.where());
        boolean idDescending = idDescending(q);
        List<List<String>> indexes = new ArrayList<>();
        for (String key : store.indexes(db, table)) {
            if (key.indexOf(',') < 0) {
                indexes.add(List.of(key));
            }
        }
        indexes.addAll(store.compositeIndexes(db, table));
        OrderedPlan best = null;
        for (List<String> columns : indexes) {
            OrderedPlan p = ordered(columns, terms, keys, idDescending);
            if (p != null && (best == null || p.exact() && !best.exact()
                    || p.exact() == best.exact() && p.where().named() > best.where().named())) {
                best = p;
            }
        }
        return best;
    }

    /**
     * The read of the index on {@code columns} in the order of {@code keys}, or null if it has none. Its first
     * columns are fixed as {@link #composite} fixes them (=, IN, or LIKE without wildcards, while the IN lists'
     * product stays within {@link #MAX_LOOKUP_TUPLES}), and comparisons on the next column narrow it. Each key
     * must be a column of the index; those past the fixed ones must be the next columns, in order, all in one
     * direction. Keys on fixed columns may come anywhere and in either direction: within one tuple of fixed
     * values they do not change, and the read merges the tuples by them.
     */
    static OrderedPlan ordered(List<String> columns, List<Expr> terms, List<Statement.OrderBy> keys, boolean idDescending) {
        List<List<Object>> equal = new ArrayList<>();
        Set<Expr> answered = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        long tuples = 1;
        int fixed = 0;
        for (; fixed < columns.size(); fixed++) {
            Expr t = equalTerm(columns.get(fixed), terms);
            if (t == null || tuples * equalValues(t).size() > MAX_LOOKUP_TUPLES) {
                break;
            }
            tuples *= equalValues(t).size();
            equal.add(equalValues(t));
            answered.add(t);
        }
        List<Store.SortKey> sort = new ArrayList<>();
        int next = fixed;
        Boolean descending = null;
        for (Statement.OrderBy o : keys) {
            int at = columns.indexOf(o.column());
            if (at < 0) {
                return null;
            }
            if (at >= fixed) {
                if (at != next++ || descending != null && descending != o.descending()) {
                    return null;
                }
                descending = o.descending();
            }
            sort.add(new Store.SortKey(at, o.descending()));
        }
        List<Store.Lookup> range = new ArrayList<>();
        if (fixed < columns.size()) {
            String c = columns.get(fixed);
            for (Expr t : terms) {
                if (t instanceof Expr.Compare x && x.column().equals(c) && x.op() != Expr.Cmp.NE && x.op() != Expr.Cmp.EQ) {
                    range.add(new Store.Lookup.Compare(x.op().symbol(), x.value()));
                    answered.add(t);
                } else if (t instanceof Expr.Like l && l.column().equals(c) && !l.negated() && !l.pattern().exact()
                        && !l.pattern().prefix().isEmpty()) {
                    // The pattern may say more than its prefix, so the term is still checked: not answered.
                    range.add(new Store.Lookup.Prefix(l.pattern().prefix()));
                }
            }
        }
        // Keys that stop before the index's last column (or lie only on fixed columns) tie on every key the
        // ORDER BY leaves out: the read forms such a group key by key before it visits a row, so it is given a
        // budget like a read that is not exact (review 22g: one large group was 2.5 times slower than the sort).
        boolean exact = next == columns.size();
        for (Expr t : terms) {
            exact &= answered.contains(t);
        }
        return new OrderedPlan(Op.indexKey(columns), new Store.Lookup.Composite(equal, range), sort, idDescending, exact);
    }

    /**
     * The steps {@code op} may take to find {@code keep} rows of {@code q} (see {@link Store#forEachInOrder}): no
     * limit when it is exact; else {@link #ORDERED_SHARE} of the rows the read without order would visit, or -1
     * (do not try) when that is fewer than {@code keep}.
     */
    static long orderedBudget(Store store, Statement.Select q, OrderedPlan op, long keep) {
        return orderedBudget(store, q, op, keep,
                cheapestChoice(store, q.db(), q.table(), candidates(store, q.db(), q.table(), q.where()), List.of()));
    }

    /**
     * As above, with the read {@link #scan} would take without order already chosen ({@code unordered}, null for
     * none): the budget is a share of the rows that read visits, its estimate or the table's rows. An {@code _id}
     * range or prefix, whose estimate is the whole table, is counted only as far as decides whether the read is
     * tried (review 22g: a budget from the table made a narrow range slow; review 22h: counting further made a
     * wide one slow): a range of fewer than {@code keep} shares is too small, and past that the table's rows
     * stand. The caller passes the same choice to the scan it falls back on, so no estimate is made twice.
     */
    /** The share of an index read's rows an ordered read may take when the read without order is that index read. */
    static final int ORDERED_SHARE_OF_INDEX_READ = 7;

    /** As {@link #ORDERED_SHARE_OF_INDEX_READ}, when that index read streams rather than gathers (see gathers). */
    static final int ORDERED_SHARE_OF_STREAMED_READ = 16;

    /**
     * The most ids of an _id range an ordered read's budget counts: past it, the table's rows stand (review 22i:
     * counting a page's 96 shares cost twice reading a long page in order).
     */
    static final long ID_COUNT_LIMIT = 16_384;

    static long orderedBudget(Store store, Statement.Select q, OrderedPlan op, long keep, Choice unordered) {
        if (op.exact()) {
            return Long.MAX_VALUE;
        }
        long rows = store.visibleRows(q.db(), q.table());
        long share = ORDERED_SHARE;
        if (unordered != null) {
            // A union's estimate already counts its _id ranges (estimate); column is null for it.
            if (ID.equals(unordered.plan().column())) {
                long enough = Math.min(keep * ORDERED_SHARE, ID_COUNT_LIMIT);
                long counted = store.indexEstimate(q.db(), q.table(), ID, unordered.plan().lookup(), Math.min(rows, enough));
                rows = counted > enough ? rows : Math.min(rows, counted);
            } else {
                // The read without order goes through an index, at 8 to 10 times a scan's cost a row, and sorts:
                // a share of the scan's size gave up where a read in order was cheaper (review 22i).
                rows = Math.min(rows, unordered.rows());
                // Each step of a read merging many streams costs about log2 of their number in heap work (review
                // 22i: 1,000 IN streams took 2.5 times the sort with the share alone).
                long streams = 1;
                for (List<Object> values : op.where().equal()) {
                    streams = Math.min(streams * Math.max(1, values.size()), MAX_LOOKUP_TUPLES);
                }
                // A read that streams (=, IN) costs about a third of one that gathers each row (review 22j).
                int perRow = gathers(unordered.plan()) ? ORDERED_SHARE_OF_INDEX_READ : ORDERED_SHARE_OF_STREAMED_READ;
                share = perRow * Math.max(1, 64 - Long.numberOfLeadingZeros(streams - 1));
            }
        }
        long budget = rows / share;
        return keep <= budget ? budget : -1;
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

    record Match(String id, Map<String, Object> row) {}

    /**
     * True when a SELECT without aggregates or groups answers in _id order, so it reads rows in that order and
     * stops after its page instead of sorting: without ORDER BY, or with _id ascending as the first key that
     * can decide anything (_id is unique, so the keys after it never do, and a key on a column the WHERE fixes
     * to one value never does; see {@link #deciding}).
     */
    static boolean readsInIdOrder(Statement.Select q) {
        return deciding(q).isEmpty() && !idDescending(q);
    }

    /**
     * The most rows OFFSET and LIMIT may reach: OFFSET plus LIMIT, or plus the cap without LIMIT. An OFFSET
     * page reads every row before it, so past this a statement is refused and pointed to keyset pages.
     */
    public static final int MAX_OFFSET_ROWS = 100_000;

    /** The rows a SELECT may return: its LIMIT, or the cap. */
    private long cap(Statement.Select q) {
        return q.limit() != null ? q.limit() : maxRows;
    }

    /** The rows a SELECT leaves out before those it returns: its OFFSET, or 0. */
    private static long skip(Statement.Select q) {
        return q.offset() != null ? q.offset() : 0;
    }

    /**
     * The rows of {@code all} (an answer in its order, or its first rows) that OFFSET {@code skip} and at most
     * {@code cap} rows leave.
     */
    private static <T> List<T> page(List<T> all, long skip, long cap) {
        int from = (int) Math.min(skip, all.size());
        return all.subList(from, (int) Math.min(all.size(), from + cap));
    }

    private Result select(Store store, Statement.Select q) {
        requireTable(store, q.db(), q.table());
        if (q.limit() != null && q.limit() > maxRows) {
            throw new QueryException("LIMIT " + q.limit() + " is over the maximum of " + maxRows);
        }
        long skip = skip(q);
        long cap = cap(q);
        if (q.offset() != null && skip > MAX_OFFSET_ROWS - cap) {
            throw new QueryException("OFFSET " + skip + " plus " + (q.limit() != null ? "LIMIT " + q.limit()
                    : "the cap of " + maxRows) + " is over the maximum of " + MAX_OFFSET_ROWS + " rows: an OFFSET page"
                    + " reads every row before it, so read a large table in keyset pages, WHERE _id > (the last _id of"
                    + " the page before) ORDER BY _id LIMIT n (docs/QUERY.md, \"Reading a large table\")");
        }
        if (q.projection() instanceof Statement.Projection.Count && q.groupBy().isEmpty() && q.having() == null) {
            long[] n = {0};
            if (q.where() == null) {
                // Kept with every change, so counting a table does not visit its rows.
                n[0] = store.visibleRows(q.db(), q.table());
            } else {
                scan(store, q.db(), q.table(), q.where(), (id, row) -> {
                    if (matches(q.where(), id, row)) {
                        n[0]++;
                    }
                });
            }
            return new Result.Rows(List.of("count"), page(List.of(List.<Object>of(n[0])), skip, cap), false);
        }
        if (Statement.Projection.aggregates(q.projection()) || !q.groups().isEmpty()) {
            return grouped(store, q);
        }
        List<Match> found = read(store, q).rows();
        boolean truncated = q.limit() == null && found.size() > skip + cap;
        List<Match> page = page(found, skip, cap);

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

    /** What {@link #read} found: rows of the answer in its order, and how many rows it visited to find them. */
    record Read(List<Match> rows, long visited) {}

    /**
     * The rows of {@code q}'s answer up to its page's end (OFFSET, then LIMIT or the cap), and without LIMIT one
     * more, which is enough to know whether more follow; see {@link #read(Store, Statement.Select, long)}. With
     * LIMIT, `truncated` is always false, so the read does not look for that row: when exactly LIMIT rows
     * matched, a read in index order spent its whole share looking for it, then sorted (docs3: 30 ms against
     * 0.55 on 200,000 rows).
     */
    Read read(Store store, Statement.Select q) {
        return read(store, q, Math.max(1, skip(q) + cap(q) + (q.limit() == null ? 1 : 0)));
    }

    /**
     * The first {@code keep} rows of the answer of {@code q} (a SELECT without groups or aggregates), in its
     * order, or all of them if it has fewer. A read in the answer's order (by _id, or through an index in order)
     * stops once it has {@code keep}. Otherwise every match is read and the first {@code keep} by the ORDER BY
     * are kept as it goes ({@link Top}), then sorted.
     */
    static Read read(Store store, Statement.Select q, long keep) {
        List<Match> found = new ArrayList<>();
        long[] visited = {0};
        java.util.function.BiPredicate<String, Map<String, Object>> collect = (id, row) -> {
            visited[0]++;
            if (matches(q.where(), id, row)) {
                found.add(new Match(id, row));
            }
            return found.size() < keep;
        };
        if (readsInIdOrder(q)) {
            scanWhile(store, q.db(), q.table(), q.where(), collect);
            return new Read(found, visited[0]);
        }
        OrderedPlan op = orderedPlan(store, q.db(), q.table(), q);
        List<Plan> plans = null;
        Choice unordered = null;
        if (op != null && !op.exact()) {
            plans = candidates(store, q.db(), q.table(), q.where());
            unordered = cheapestChoice(store, q.db(), q.table(), plans, List.of());
        }
        long budget = op == null ? -1 : orderedBudget(store, q, op, keep, unordered);
        if (budget >= 0 && store.forEachInOrder(q.db(), q.table(), op.index(), op.where(), op.keys(), op.idDescending(),
                budget, collect) == Store.Ordered.DONE) {
            return new Read(found, visited[0]);
        }
        Top top = new Top(order(q), keep);
        java.util.function.BiConsumer<String, Map<String, Object>> all = (id, row) -> {
            visited[0]++;
            if (matches(q.where(), id, row)) {
                top.add(new Match(id, row));
            }
        };
        if (plans != null) {
            scan(store, q.db(), q.table(), plans, unordered == null ? null : unordered.plan(), all);
        } else {
            scan(store, q.db(), q.table(), q.where(), all);
        }
        return new Read(top.sorted(), visited[0]);
    }

    /** The order of {@code q}'s ORDER BY (which has a key), rows equal on every key by _id. */
    private static Comparator<Match> order(Statement.Select q) {
        Comparator<Match> byKeys = null;
        for (Statement.OrderBy o : q.orderBy()) {
            String col = o.column();
            Comparator<Match> byValue = (a, b) -> Values.sortOrder(get(a.id, a.row, col), get(b.id, b.row, col));
            if (o.descending()) {
                byValue = byValue.reversed();
            }
            byKeys = byKeys == null ? byValue : byKeys.thenComparing(byValue);
        }
        return byKeys.thenComparing(Match::id);
    }

    /**
     * The first {@code keep} rows by {@code order} of those added, which come in any order. A sort holds at most
     * {@link #BUFFER} times {@code keep} rows, not every match, and gives the first {@code keep} that sorting
     * them all would: {@code order} ends with _id, so no two rows tie.
     */
    static final class Top {
        /**
         * How many times {@code keep} rows are held before they are cut: rows in the reverse of the order are all
         * kept, and each cut costs about as many comparisons as it holds, so the fewer cuts the closer to one sort.
         */
        static final int BUFFER = 4;
        private final Comparator<Match> order;
        private final int keep;
        private final ArrayList<Match> rows = new ArrayList<>();
        /** Once the rows were cut to {@code keep}, the last of them: a row not before it is not among the first. */
        private Match bound;

        Top(Comparator<Match> order, long keep) {
            if (keep < 1 || keep > Integer.MAX_VALUE / BUFFER) {
                throw new IllegalArgumentException("keep " + keep);
            }
            this.order = order;
            this.keep = (int) keep;
        }

        /**
         * Rows before the bound are kept until there are BUFFER times {@code keep}, then sorted and cut to the first
         * {@code keep}. The sort (TimSort) finds runs, so rows that come in the order, or in its reverse (newest
         * first, read in _id order), cost O(1) each; a heap cost about 2 log2(keep) comparisons a row there and
         * was 6 times slower than the sort it replaced (review 22j).
         */
        void add(Match m) {
            if (bound != null && order.compare(m, bound) >= 0) {
                return;
            }
            rows.add(m);
            if (rows.size() >= BUFFER * keep) {
                rows.sort(order);
                rows.subList(keep, rows.size()).clear();
                bound = rows.get(keep - 1);
            }
        }

        /** The rows kept, in {@code order}. */
        List<Match> sorted() {
            rows.sort(order);
            if (rows.size() > keep) {
                rows.subList(keep, rows.size()).clear();
            }
            return rows;
        }
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
     * ORDER BY, OFFSET and LIMIT apply to the rest.
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
        long skip = skip(q);
        long cap = cap(q);
        boolean truncated = q.limit() == null && list.size() > skip + cap;
        List<Group> page = page(list, skip, cap);
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
