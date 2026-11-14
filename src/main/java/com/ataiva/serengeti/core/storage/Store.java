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
    /**
     * Rows of a table are spread over this many buckets by a hash of their id. Each bucket keeps the sum
     * (mod 2^64) of {@link #rowHash} over its live rows, updated as rows change, so replicas compare a
     * table in O(1) and find the differing rows by bucket without hashing the whole table.
     */
    public static final int BUCKETS = 1024;

    private static final class TableState {
        Stamp created = Stamp.ZERO;
        Stamp dropped;
        final TreeMap<String, RowState> rows = new TreeMap<>();
        final long[] buckets = new long[BUCKETS];

        TableState(Stamp dropped) {
            this.dropped = dropped;
        }

        boolean exists() {
            return created.after(dropped);
        }

        void drop(Stamp at) {
            if (at.after(dropped)) {
                dropped = at;
                rows.entrySet().removeIf(e -> {
                    if (e.getValue().stamp.after(at)) {
                        return false;
                    }
                    unaccount(e.getKey(), e.getValue());
                    return true;
                });
            }
        }

        void account(String id, RowState r) {
            if (r.value != null) {
                buckets[bucket(id)] += rowHash(id, r.stamp);
            }
        }

        void unaccount(String id, RowState r) {
            if (r.value != null) {
                buckets[bucket(id)] -= rowHash(id, r.stamp);
            }
        }

        long root() {
            long sum = 0;
            for (long b : buckets) {
                sum = sum * 31 + b;
            }
            return sum;
        }
    }

    /** The bucket of a row id. Stable across nodes and versions (String.hashCode is specified). */
    public static int bucket(String id) {
        int h = id.hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return Math.floorMod(h, BUCKETS);
    }

    /** 64 bits of SHA-256 over the id and the stamp. A row's version is identified by its stamp. */
    static long rowHash(String id, Stamp at) {
        MessageDigest md = SHA256.get();
        md.reset();
        md.update(id.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(at.toString().getBytes(StandardCharsets.UTF_8));
        byte[] d = md.digest();
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (d[i] & 0xff);
        }
        return v;
    }

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

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
            RowState next = new RowState(value, at);
            if (r != null) {
                t.unaccount(id, r);
            }
            t.account(id, next);
            t.rows.put(id, next);
        }
    }

    // ---- replica comparison ----

    /** Order-independent hash of a database's schema: its own and its tables' create and drop stamps. */
    public long schemaHash(String db) {
        // Hashes exactly what schemaOps would send, so an entry that apply() created as a placeholder
        // (a rejected stale write, say) on one replica and not on another does not count as a difference.
        long h = 0;
        for (Op op : schemaOps(db)) {
            String key = op instanceof Op.CreateTable o ? "ct\u0000" + o.table()
                    : op instanceof Op.DropTable o ? "dt\u0000" + o.table()
                    : op instanceof Op.CreateDb ? "cd" : "dd";
            h += rowHash(key, op.stamp());
        }
        return h;
    }

    /** For each known database (dropped ones too): its schema hash and one root hash per table. */
    public Map<String, Map<String, Object>> rootHashes() {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (Map.Entry<String, DbState> e : dbs.entrySet()) {
            Map<String, Object> tables = new TreeMap<>();
            for (Map.Entry<String, TableState> t : e.getValue().tables.entrySet()) {
                tables.put(t.getKey(), t.getValue().root());
            }
            Map<String, Object> m = new TreeMap<>();
            m.put("schema", schemaHash(e.getKey()));
            m.put("tables", tables);
            out.put(e.getKey(), m);
        }
        return out;
    }

    /** The bucket sums of a table, or all zeros if it is unknown. A copy. */
    public long[] bucketSums(String db, String table) {
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        return t == null ? new long[BUCKETS] : t.buckets.clone();
    }

    /** The database's create and drop ops, and its tables' (no rows). */
    public List<Op> schemaOps(String db) {
        List<Op> out = new ArrayList<>();
        DbState d = dbs.get(db);
        if (d == null) {
            return out;
        }
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
        });
        return out;
    }

    /** The rows (values and tombstones) of the given buckets of a table, as ops. */
    public List<Op> bucketOps(String db, String table, java.util.Set<Integer> wanted) {
        List<Op> out = new ArrayList<>();
        DbState d = dbs.get(db);
        TableState t = d == null ? null : d.tables.get(table);
        if (t == null) {
            return out;
        }
        t.rows.forEach((id, r) -> {
            if (wanted.contains(bucket(id))) {
                out.add(r.value == null ? new Op.Delete(db, table, id, r.stamp) : new Op.Put(db, table, id, r.value, r.stamp));
            }
        });
        return out;
    }

    /**
     * Forgets row tombstones written before {@code cutoffWallMillis}. A delete must have reached every
     * replica before its tombstone goes, or an older copy of the row could come back from a replica that
     * missed it; {@code Cluster} refuses to sync with a node that was away longer than the cutoff. Live
     * rows and the digests are not affected: tombstones are not counted in the bucket sums.
     *
     * @return the number of tombstones removed
     */
    public int collectTombstones(long cutoffWallMillis) {
        int n = 0;
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                var it = t.rows.values().iterator();
                while (it.hasNext()) {
                    RowState r = it.next();
                    if (r.value == null && r.stamp.wall() < cutoffWallMillis) {
                        it.remove();
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Number of row tombstones held, for metrics. */
    public long tombstones() {
        long n = 0;
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                for (RowState r : t.rows.values()) {
                    if (r.value == null) {
                        n++;
                    }
                }
            }
        }
        return n;
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
