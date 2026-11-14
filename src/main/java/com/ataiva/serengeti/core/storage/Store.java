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
                var it = rows.entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    RowState r = e.getValue();
                    RowState kept = r.newerThan(at);
                    if (kept == r) {
                        continue;
                    }
                    unaccount(e.getKey(), r);
                    if (kept == null) {
                        it.remove();
                    } else {
                        account(e.getKey(), kept);
                        e.setValue(kept);
                    }
                }
            }
        }

        void account(String id, RowState r) {
            buckets[bucket(id)] += r.hash(id);
        }

        void unaccount(String id, RowState r) {
            buckets[bucket(id)] -= r.hash(id);
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

    /**
     * One write to a row: its value (null for a delete), its stamp, and its causal context ({@link Op.RowOp#seen}).
     *
     * <p>Version {@code a} has been seen by version {@code b} when every entry of {@code a}'s clock is at or
     * below {@code b}'s entry for the same key. A version's clock is its context plus its own stamp under its
     * node's id and under {@code "~"}. A version with no context (from 2.0) has only {@code "~"}: such versions
     * are ordered by stamp among themselves, as 2.0 ordered every write, and any later write that saw one
     * covers it.
     */
    public record Version(Map<String, Object> value, Stamp stamp, Map<String, Stamp> seen) {
        public boolean deleted() {
            return value == null;
        }

        /** This version's clock entry for {@code key}, or null. */
        Stamp clock(String key) {
            if (seen == null) {
                return key.equals("~") ? stamp : null;
            }
            if (key.equals("~") || key.equals(stamp.node())) {
                return stamp; // a node's own write is newer than anything it had seen
            }
            return seen.get(key);
        }

        java.util.Set<String> clockKeys() {
            if (seen == null) {
                return java.util.Set.of("~");
            }
            java.util.Set<String> keys = new java.util.TreeSet<>(seen.keySet());
            keys.add("~");
            keys.add(stamp.node());
            return keys;
        }

        /** True if {@code other} had seen this version when it was written (or is it). */
        boolean seenBy(Version other) {
            for (String k : clockKeys()) {
                Stamp mine = clock(k);
                Stamp theirs = other.clock(k);
                if (theirs == null || mine.after(theirs)) {
                    return false;
                }
            }
            return true;
        }

        Op.RowOp toOp(String db, String table, String id) {
            return value == null ? new Op.Delete(db, table, id, stamp, seen) : new Op.Put(db, table, id, value, stamp, seen);
        }

        static Version of(Op.RowOp op) {
            return new Version(op instanceof Op.Put p ? p.row() : null, op.stamp(), op.seen());
        }
    }

    /**
     * A row: the versions that no other version of it has seen, newest first. Normally one; more after
     * writes on nodes that had not seen each other's (a conflict). The newest is the row's value, so the
     * outcome of a conflict is last-writer-wins as in 2.0, and the others stay until a write resolves them.
     */
    private record RowState(List<Version> versions) {
        Version winner() {
            return versions.get(0);
        }

        boolean live() {
            for (Version v : versions) {
                if (v.value != null) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The row's share of its bucket sum: nothing while every version is a delete (so collecting
         * tombstones changes no sum), else the sum over all versions (so a conflicting delete still differs).
         */
        long hash(String id) {
            if (!live()) {
                return 0;
            }
            long h = 0;
            for (Version v : versions) {
                h += rowHash(id, v.stamp);
            }
            return h;
        }

        /** This state with {@code in} merged, or this same object if {@code in} changes nothing. */
        RowState with(Version in) {
            for (Version v : versions) {
                if (v.stamp.equals(in.stamp) || in.seenBy(v)) {
                    return this;
                }
            }
            List<Version> out = new ArrayList<>(versions.size() + 1);
            for (Version v : versions) {
                if (!v.seenBy(in)) {
                    out.add(v);
                }
            }
            out.add(in);
            out.sort((a, b) -> b.stamp.compareTo(a.stamp));
            return new RowState(List.copyOf(out));
        }

        /** The versions stamped after {@code at}: this object if all are, null if none are. */
        RowState newerThan(Stamp at) {
            List<Version> out = new ArrayList<>(versions.size());
            for (Version v : versions) {
                if (v.stamp.after(at)) {
                    out.add(v);
                }
            }
            return out.size() == versions.size() ? this : out.isEmpty() ? null : new RowState(List.copyOf(out));
        }

        /** The context for a new write over this row: the newest stamp per key over all its versions. */
        Map<String, Stamp> context() {
            Map<String, Stamp> out = new TreeMap<>();
            for (Version v : versions) {
                for (String k : v.clockKeys()) {
                    out.merge(k, v.clock(k), Stamp::max);
                }
            }
            return out;
        }
    }

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
        } else if (op instanceof Op.RowOp o) {
            putRow(d.table(o.table()), o.id(), Version.of(o));
        }
    }

    public void applyAll(List<Op> ops) {
        for (Op op : ops) {
            apply(op);
        }
    }

    private static void putRow(TableState t, String id, Version in) {
        if (!in.stamp().after(t.dropped)) {
            return;
        }
        RowState r = t.rows.get(id);
        RowState next = r == null ? new RowState(List.of(in)) : r.with(in);
        if (next != r) {
            if (r != null) {
                t.unaccount(id, r);
            }
            t.account(id, next);
            t.rows.put(id, next);
        }
    }

    /**
     * The batch with a causal context on every row write that has none: the clock of everything this node
     * holds for that row (and of the batch's earlier writes to it), so the write supersedes all of them.
     * Client writes go through here before they are logged; ops from peers keep the context they came with.
     */
    public List<Op> withContext(List<Op> batch) {
        List<Op> out = new ArrayList<>(batch.size());
        Map<String, Version> earlier = new java.util.HashMap<>();
        for (Op op : batch) {
            if (op instanceof Op.RowOp r && r.seen() == null) {
                String key = r.db() + "\u0000" + r.table() + "\u0000" + r.id();
                Version prev = earlier.get(key);
                Map<String, Stamp> ctx;
                if (prev != null) {
                    ctx = new RowState(List.of(prev)).context();
                } else {
                    DbState d = dbs.get(r.db());
                    TableState t = d == null ? null : d.tables.get(r.table());
                    RowState row = t == null ? null : t.rows.get(r.id());
                    ctx = row == null ? new TreeMap<>() : row.context();
                }
                ctx.remove(r.stamp().node()); // implied by the write's own stamp
                ctx.remove("~");
                Op.RowOp filled = r.withSeen(ctx);
                earlier.put(key, Version.of(filled));
                out.add(filled);
            } else {
                out.add(op);
            }
        }
        return out;
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
                for (Version v : r.versions()) {
                    out.add(v.toOp(db, table, id));
                }
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
                    if (!r.live() && r.winner().stamp().wall() < cutoffWallMillis) {
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
                    if (!r.live()) {
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
        return r == null || r.with(Version.of((Op.RowOp) op)) != r;
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
        return r == null ? null : r.winner().value();
    }

    /** Visits the live rows of a table in id order. */
    public void forEachRow(String db, String table, BiConsumer<String, Map<String, Object>> visitor) {
        if (!tableExists(db, table)) {
            return;
        }
        dbs.get(db).tables.get(table).rows.forEach((id, r) -> {
            if (r.winner().value() != null) {
                visitor.accept(id, r.winner().value());
            }
        });
    }

    /**
     * Visits, in id order, the rows of a table that hold more than one version: writes made on nodes that
     * had not seen each other's. The versions come newest first; the first is the row's value.
     */
    public void forEachConflict(String db, String table, BiConsumer<String, List<Version>> visitor) {
        if (!tableExists(db, table)) {
            return;
        }
        dbs.get(db).tables.get(table).rows.forEach((id, r) -> {
            if (r.versions().size() > 1) {
                visitor.accept(id, r.versions());
            }
        });
    }

    /** Number of rows, over every table, that hold more than one version; for metrics. */
    public long conflicts() {
        long n = 0;
        for (DbState d : dbs.values()) {
            for (TableState t : d.tables.values()) {
                for (RowState r : t.rows.values()) {
                    if (r.versions().size() > 1) {
                        n++;
                    }
                }
            }
        }
        return n;
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
            t.rows.forEach((id, r) -> {
                for (Version v : r.versions()) {
                    out.add(v.toOp(db, name, id));
                }
            });
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
                md.update(Json.write(Op.toLegacyJson(op)).getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
