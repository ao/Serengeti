package com.ataiva.serengeti.core.storage;

import com.ataiva.serengeti.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * The in-memory state of all databases, tables and rows.
 *
 * <p>The state is a join-semilattice: each database and table keeps the newest stamp at which it was
 * created and the newest at which it was dropped (it exists while created is newer), and each row keeps
 * its newest write (a value or a tombstone). A drop at stamp D removes every row older than D. So
 * {@link #apply} is idempotent, commutative and associative: replaying the log twice, loading a snapshot
 * and then the log, or merging a peer's state all give the same result regardless of order.
 *
 * <p>Not thread-safe; {@link StorageEngine} guards it with a lock.
 */
public final class Store {
    private static final class TableState {
        Stamp created = Stamp.ZERO;
        Stamp dropped;
        final TreeMap<String, RowState> rows = new TreeMap<>();

        TableState(Stamp dropped) {
            this.dropped = dropped;
        }

        boolean exists() {
            return created.after(dropped);
        }

        void drop(Stamp at) {
            if (at.after(dropped)) {
                dropped = at;
                rows.values().removeIf(r -> !r.stamp.after(at));
            }
        }
    }

    private static final class DbState {
        Stamp created = Stamp.ZERO;
        Stamp dropped = Stamp.ZERO;
        final TreeMap<String, TableState> tables = new TreeMap<>();

        boolean exists() {
            return created.after(dropped);
        }

        TableState table(String name) {
            return tables.computeIfAbsent(name, n -> new TableState(dropped));
        }
    }

    private record RowState(Map<String, Object> value, Stamp stamp) {}

    private final TreeMap<String, DbState> dbs = new TreeMap<>();
    private Stamp maxStamp = Stamp.ZERO;

    public void apply(Op op) {
        maxStamp = Stamp.max(maxStamp, op.stamp());
        DbState d = dbs.computeIfAbsent(op.db(), n -> new DbState());
        if (op instanceof Op.CreateDb o) {
            d.created = Stamp.max(d.created, o.stamp());
        } else if (op instanceof Op.DropDb o) {
            d.dropped = Stamp.max(d.dropped, o.stamp());
            for (TableState t : d.tables.values()) {
                t.drop(o.stamp());
            }
        } else if (op instanceof Op.CreateTable o) {
            TableState t = d.table(o.table());
            t.created = Stamp.max(t.created, o.stamp());
        } else if (op instanceof Op.DropTable o) {
            d.table(o.table()).drop(o.stamp());
        } else if (op instanceof Op.Put o) {
            putRow(d.table(o.table()), o.id(), o.row(), o.stamp());
        } else if (op instanceof Op.Delete o) {
            putRow(d.table(o.table()), o.id(), null, o.stamp());
        }
    }

    public void applyAll(List<Op> ops) {
        for (Op op : ops) {
            apply(op);
        }
    }

    private static void putRow(TableState t, String id, Map<String, Object> value, Stamp at) {
        if (!at.after(t.dropped)) {
            return;
        }
        RowState r = t.rows.get(id);
        if (r == null || at.after(r.stamp)) {
            t.rows.put(id, new RowState(value, at));
        }
    }

    /** True if applying {@code op} would change the state. Does not change anything itself. */
    public boolean isNew(Op op) {
        DbState d = dbs.get(op.db());
        if (op instanceof Op.CreateDb o) {
            return d == null || o.stamp().after(d.created);
        }
        if (op instanceof Op.DropDb o) {
            return d == null || o.stamp().after(d.dropped);
        }
        String table = op instanceof Op.CreateTable o ? o.table()
                : op instanceof Op.DropTable o ? o.table()
                : op instanceof Op.Put o ? o.table()
                : ((Op.Delete) op).table();
        TableState t = d == null ? null : d.tables.get(table);
        Stamp dropped = t != null ? t.dropped : d != null ? d.dropped : Stamp.ZERO;
        if (op instanceof Op.CreateTable o) {
            return t == null || o.stamp().after(t.created);
        }
        if (op instanceof Op.DropTable o) {
            return o.stamp().after(dropped);
        }
        String id = op instanceof Op.Put o ? o.id() : ((Op.Delete) op).id();
        if (!op.stamp().after(dropped)) {
            return false;
        }
        RowState r = t == null ? null : t.rows.get(id);
        return r == null || op.stamp().after(r.stamp);
    }

    /** The newest stamp applied so far; the clock must start after it. */
    public Stamp maxStamp() {
        return maxStamp;
    }

    public boolean dbExists(String db) {
        DbState d = dbs.get(db);
        return d != null && d.exists();
    }

    public boolean tableExists(String db, String table) {
        DbState d = dbs.get(db);
        if (d == null || !d.exists()) {
            return false;
        }
        TableState t = d.tables.get(table);
        return t != null && t.exists();
    }

    public List<String> databases() {
        List<String> out = new ArrayList<>();
        dbs.forEach((name, d) -> {
            if (d.exists()) {
                out.add(name);
            }
        });
        return out;
    }

    public List<String> tables(String db) {
        List<String> out = new ArrayList<>();
        DbState d = dbs.get(db);
        if (d != null && d.exists()) {
            d.tables.forEach((name, t) -> {
                if (t.exists()) {
                    out.add(name);
                }
            });
        }
        return out;
    }

    /** The row with this id, or null if absent, deleted, or its table does not exist. */
    public Map<String, Object> row(String db, String table, String id) {
        if (!tableExists(db, table)) {
            return null;
        }
        RowState r = dbs.get(db).tables.get(table).rows.get(id);
        return r == null ? null : r.value;
    }

    /** Visits the live rows of a table in id order. */
    public void forEachRow(String db, String table, BiConsumer<String, Map<String, Object>> visitor) {
        if (!tableExists(db, table)) {
            return;
        }
        dbs.get(db).tables.get(table).rows.forEach((id, r) -> {
            if (r.value != null) {
                visitor.accept(id, r.value);
            }
        });
    }

    public long rowCount(String db, String table) {
        long[] n = {0};
        forEachRow(db, table, (id, row) -> n[0]++);
        return n[0];
    }

    /** The whole state as ops; applying them to an empty store rebuilds it exactly. */
    public List<Op> toOps() {
        List<Op> out = new ArrayList<>();
        for (String db : dbs.keySet()) {
            appendOps(db, out);
        }
        return out;
    }

    public List<Op> toOps(String db) {
        List<Op> out = new ArrayList<>();
        if (dbs.containsKey(db)) {
            appendOps(db, out);
        }
        return out;
    }

    private void appendOps(String db, List<Op> out) {
        DbState d = dbs.get(db);
        if (d.created.after(Stamp.ZERO)) {
            out.add(new Op.CreateDb(db, d.created));
        }
        if (d.dropped.after(Stamp.ZERO)) {
            out.add(new Op.DropDb(db, d.dropped));
        }
        d.tables.forEach((name, t) -> {
            if (t.created.after(Stamp.ZERO)) {
                out.add(new Op.CreateTable(db, name, t.created));
            }
            if (t.dropped.after(d.dropped)) {
                out.add(new Op.DropTable(db, name, t.dropped));
            }
            t.rows.forEach((id, r) -> out.add(r.value == null
                    ? new Op.Delete(db, name, id, r.stamp)
                    : new Op.Put(db, name, id, r.value, r.stamp)));
        });
    }

    /** All database names known, including dropped ones (their tombstones still replicate). */
    public List<String> allDatabaseNames() {
        return new ArrayList<>(dbs.keySet());
    }

    /** SHA-256 of each database's full state, for comparing replicas. */
    public Map<String, String> digests() {
        Map<String, String> out = new TreeMap<>();
        for (String db : dbs.keySet()) {
            out.put(db, digest(db));
        }
        return out;
    }

    public String digest(String db) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Op op : toOps(db)) {
                md.update(Json.write(Op.toJson(op)).getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
