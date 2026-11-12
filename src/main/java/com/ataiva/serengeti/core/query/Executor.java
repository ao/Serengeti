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
    /** The column that holds each row's key. */
    public static final String ID = "_id";

    private final StorageEngine engine;
    private final Hlc clock;
    private final int maxRows;

    public Executor(StorageEngine engine, Hlc clock) {
        this(engine, clock, DEFAULT_MAX_ROWS);
    }

    public Executor(StorageEngine engine, Hlc clock, int maxRows) {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be at least 1");
        }
        this.engine = engine;
        this.clock = clock;
        this.maxRows = maxRows;
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
        store.forEachRow(u.db(), u.table(), (id, row) -> {
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

    private StorageEngine.Planned<Result> delete(Store store, Statement.Delete d) {
        requireTable(store, d.db(), d.table());
        List<Op> ops = new ArrayList<>();
        Stamp at = stamp(store);
        store.forEachRow(d.db(), d.table(), (id, row) -> {
            if (matches(d.where(), id, row)) {
                ops.add(new Op.Delete(d.db(), d.table(), id, at));
            }
        });
        return new StorageEngine.Planned<>(ops, new Result.Write(ops.size()));
    }

    private record Match(String id, Map<String, Object> row) {}

    private Result select(Store store, Statement.Select q) {
        requireTable(store, q.db(), q.table());
        if (q.projection() instanceof Statement.Projection.Count) {
            long[] n = {0};
            store.forEachRow(q.db(), q.table(), (id, row) -> {
                if (matches(q.where(), id, row)) {
                    n[0]++;
                }
            });
            return new Result.Rows(List.of("count"), List.of(List.<Object>of(n[0])), false);
        }
        if (q.limit() != null && q.limit() > maxRows) {
            throw new QueryException("LIMIT " + q.limit() + " is over the maximum of " + maxRows);
        }
        long cap = q.limit() != null ? q.limit() : maxRows;
        // Without ORDER BY rows come in _id order, so one past the cap is enough to know about truncation.
        long keep = q.orderBy() == null ? cap + 1 : Long.MAX_VALUE;
        List<Match> found = new ArrayList<>();
        store.forEachRow(q.db(), q.table(), (id, row) -> {
            if (found.size() < keep && matches(q.where(), id, row)) {
                found.add(new Match(id, row));
            }
        });
        if (q.orderBy() != null) {
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
