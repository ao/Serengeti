package com.ataiva.serengeti.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.Store;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Catching up by rows that differ (protocol 3) against catching up by whole buckets (protocol 2): on random
 * histories of two replicas (schema changes, conflicting writes, deletes, copies that lost their context),
 * each pull leaves the receiver in exactly the state the bucket pull leaves it in, moves no more rows, and
 * the replicas end equal.
 */
class RowSyncProperties {
    /**
     * Four ids in one bucket, four in another, and two more: buckets that hold several rows, of which only
     * some differ, as in a real table. With ids spread over 1,024 buckets a bucket would hold one row and
     * differ exactly when that row does.
     */
    static final List<String> IDS = collidingIds();

    private static List<String> collidingIds() {
        List<String> out = new ArrayList<>();
        for (String prefix : List.of("k", "q")) {
            int bucket = Store.bucket(prefix + "0");
            int want = out.size() + 4;
            for (int i = 0; out.size() < want; i++) {
                if (Store.bucket(prefix + i) == bucket) {
                    out.add(prefix + i);
                }
            }
        }
        out.add("1");
        out.add("2");
        return List.copyOf(out);
    }

    interface Pull {
        /** The ops {@code into} gets from {@code from} for the differing buckets of a table. */
        List<Op> ops(Store from, Store into, String db, String table, Set<Integer> differ);
    }

    /** What /internal/v2/rows sends: the schema, and every version in the buckets. */
    static List<Op> byBuckets(Store from, Store into, String db, String table, Set<Integer> differ) {
        List<Op> ops = new ArrayList<>(from.schemaOps(db));
        ops.addAll(from.bucketOps(db, table, differ));
        return ops;
    }

    /** Cluster.pullTable: buckets held nowhere here go whole; the others by row hashes and /internal/v3/rows. */
    static List<Op> byRows(Store from, Store into, String db, String table, Set<Integer> differ) {
        int[] counts = into.bucketRowCounts(db, table);
        Set<Integer> empty = new HashSet<>();
        Set<Integer> held = new HashSet<>();
        for (int b : differ) {
            (counts[b] == 0 ? empty : held).add(b);
        }
        List<Op> ops = new ArrayList<>(from.bucketOps(db, table, empty));
        Map<String, Long> theirs = from.rowHashes(db, table, held);
        long[] h = theirs.values().stream().mapToLong(Long::longValue).toArray();
        List<String> want = Cluster.differingIds(new ArrayList<>(theirs.keySet()), h, into.rowHashes(db, table, held));
        ops.addAll(from.schemaOps(db));
        ops.addAll(from.rowOps(db, table, want));
        return ops;
    }

    /** One sync of {@code into} from {@code from} by the live sums, as with a 2.1 peer. */
    static int sync(Store from, Store into, Pull pull) {
        return sync(from, into, pull, false);
    }

    /**
     * One sync of {@code into} from {@code from}, as Cluster.syncByBuckets: the schema, then each table whose
     * root differs, by the full sums (tombstones counted, between protocol 3 nodes) or the live ones. Ops go
     * in as a merge takes them (Store.withoutExpiredDeletes). Returns the row ops sent.
     */
    @SuppressWarnings("unchecked")
    static int sync(Store from, Store into, Pull pull, boolean full) {
        String key = full ? "full" : "tables";
        int rows = 0;
        for (Map.Entry<String, Map<String, Object>> e : from.rootHashes().entrySet()) {
            String db = e.getKey();
            if (from.schemaHash(db) != into.schemaHash(db)) {
                into.applyAll(from.schemaOps(db));
            }
            Map<String, Object> mine = (Map<String, Object>) into.rootHashes().getOrDefault(db, Map.of(key, Map.of()))
                    .get(key);
            for (Map.Entry<String, Object> t : ((Map<String, Object>) e.getValue().get(key)).entrySet()) {
                Object myRoot = mine.get(t.getKey());
                if (t.getValue().equals(myRoot == null ? 0L : myRoot)) {
                    continue;
                }
                long[] theirs = full ? from.fullBucketSums(db, t.getKey()) : from.bucketSums(db, t.getKey());
                long[] local = full ? into.fullBucketSums(db, t.getKey()) : into.bucketSums(db, t.getKey());
                Set<Integer> differ = new HashSet<>();
                for (int i = 0; i < Store.BUCKETS; i++) {
                    if (theirs[i] != local[i]) {
                        differ.add(i);
                    }
                }
                List<Op> ops = pull.ops(from, into, db, t.getKey(), differ);
                for (Op op : ops) {
                    if (op instanceof Op.RowOp) {
                        rows++;
                    }
                }
                into.applyAll(into.withoutExpiredDeletes(ops));
            }
        }
        return rows;
    }

    /**
     * Three replicas with their own histories, deletes and conflicting deletes among them, synced by the full
     * sums until nothing differs: each ends holding exactly the merge of all three, tombstones included.
     * Before the full sums, a replica holding an older tombstone kept it next to a peer's newer one (both
     * counted nothing), and a concurrent write could then show through on it (TombstoneBlindTest).
     */
    @Property(tries = 1000)
    void threeReplicasSyncedByTheFullSumsEndAsTheMergeOfAll(@ForAll("histories") List<List<Op>> h,
            @ForAll("histories") List<List<Op>> more, @ForAll long seed) {
        List<Op> third = new ArrayList<>(h.get(0));
        for (Op o : more.get(2)) {
            third.add(renamed(o, "t"));
        }
        List<List<Op>> histories = List.of(concat(h.get(0), h.get(1)), concat(h.get(0), h.get(2)), third);
        List<Store> stores = new ArrayList<>();
        Store union = new Store();
        for (List<Op> ops : histories) {
            stores.add(of(ops));
            union.applyAll(ops);
        }
        java.util.Random random = new java.util.Random(seed);
        int rounds = 0;
        while (!converged(stores)) {
            assertTrue(++rounds <= 6, "converges within six rounds");
            // In a random order each round, as syncs run on their own.
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    if (i != j) {
                        pairs.add(new int[] {i, j});
                    }
                }
            }
            java.util.Collections.shuffle(pairs, random);
            for (int[] p : pairs) {
                sync(stores.get(p[0]), stores.get(p[1]), RowSyncProperties::byRows, true);
            }
        }
        for (Store s : stores) {
            assertEquals(union.toOps(), s.toOps());
        }
    }

    /**
     * Replicas that agree, then take the same expiry: some collect the expired tombstones and some do not (a
     * node may not collect while a peer is unknown). They still compare equal, and after newer writes on any
     * of them they converge again to the same live rows, with no table left differing between any two, so
     * nothing is pulled back and forth. (Writes older than the expiry do not arrive after it: a node away
     * that long is stale and does not sync.)
     */
    @Property(tries = 500)
    void collectedAndUncollectedReplicasAgreeOnceExpired(@ForAll("histories") List<List<Op>> h,
            @ForAll("histories") List<List<Op>> later, @ForAll("cutoffs") long expiry, @ForAll boolean[] collects) {
        List<Store> stores = new ArrayList<>();
        Store union = new Store();
        for (int i = 0; i < 3; i++) {
            List<Op> ops = i == 0 ? concat(h.get(0), h.get(1)) : i == 1 ? concat(h.get(0), h.get(2)) : h.get(0);
            stores.add(of(ops));
            union.applyAll(ops);
        }
        syncUntilConverged(stores);
        for (int i = 0; i < 3; i++) {
            stores.get(i).setExpiry(expiry);
            if (collects.length > i && collects[i]) {
                stores.get(i).collectTombstones(expiry);
            }
        }
        assertTrue(converged(stores), "collected or not, they compare equal");
        for (int i = 0; i < 3; i++) {
            for (Op o : later.get(i)) {
                Op newer = renamed(o, "w" + i, 100); // after the expiry
                stores.get(i).apply(newer);
                union.apply(newer);
            }
        }
        syncUntilConverged(stores);
        for (Store s : stores) {
            assertEquals(live(union), live(s));
        }
    }

    static void syncUntilConverged(List<Store> stores) {
        int rounds = 0;
        while (!converged(stores)) {
            assertTrue(++rounds <= 6, "converges within six rounds");
            for (int i = 0; i < stores.size(); i++) {
                for (int j = 0; j < stores.size(); j++) {
                    if (i != j) {
                        sync(stores.get(i), stores.get(j), RowSyncProperties::byRows, true);
                    }
                }
            }
        }
    }

    @Provide
    Arbitrary<Long> cutoffs() {
        return Arbitraries.longs().between(0, 42);
    }

    /** No table's full root differs between any two of the stores. */
    @SuppressWarnings("unchecked")
    static boolean converged(List<Store> stores) {
        for (Store a : stores) {
            for (Store b : stores) {
                Map<String, Map<String, Object>> ra = a.rootHashes();
                Map<String, Map<String, Object>> rb = b.rootHashes();
                Set<String> dbs = new java.util.TreeSet<>(ra.keySet());
                dbs.addAll(rb.keySet());
                for (String db : dbs) {
                    if (a.schemaHash(db) != b.schemaHash(db)) {
                        return false;
                    }
                    Map<String, Object> fa = (Map<String, Object>) ra.getOrDefault(db, Map.of("full", Map.of())).get("full");
                    Map<String, Object> fb = (Map<String, Object>) rb.getOrDefault(db, Map.of("full", Map.of())).get("full");
                    Set<String> tables = new java.util.TreeSet<>(fa.keySet());
                    tables.addAll(fb.keySet());
                    for (String t : tables) {
                        if (!fa.getOrDefault(t, 0L).equals(fb.getOrDefault(t, 0L))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    static List<Op> concat(List<Op> a, List<Op> b) {
        List<Op> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** The op with its stamp moved to another node and counter range, so that no two replicas share stamps. */
    static Op renamed(Op o, String prefix) {
        return renamed(o, prefix, 0);
    }

    /** As above, and {@code later} milliseconds later, its context too. */
    static Op renamed(Op o, String prefix, long later) {
        Stamp s = new Stamp(o.stamp().wall() + later, o.stamp().counter() + 5_000, prefix + o.stamp().node());
        Map<String, Stamp> seen = o instanceof Op.RowOp r ? shifted(r.seen(), later) : null;
        return o instanceof Op.CreateDb c ? new Op.CreateDb(c.db(), s)
                : o instanceof Op.DropDb c ? new Op.DropDb(c.db(), s)
                : o instanceof Op.CreateTable c ? new Op.CreateTable(c.db(), c.table(), s)
                : o instanceof Op.DropTable c ? new Op.DropTable(c.db(), c.table(), s)
                : o instanceof Op.Put c ? new Op.Put(c.db(), c.table(), c.id(), c.row(), s, seen)
                : new Op.Delete(o.db(), ((Op.Delete) o).table(), ((Op.Delete) o).id(), s, seen);
    }

    private static Map<String, Stamp> shifted(Map<String, Stamp> seen, long later) {
        if (seen == null || later == 0) {
            return seen;
        }
        Map<String, Stamp> out = new TreeMap<>();
        seen.forEach((k, v) -> out.put(k, new Stamp(v.wall() + later, v.counter(), v.node())));
        return out;
    }

    static Store of(List<Op> ops) {
        Store s = new Store();
        s.applyAll(ops);
        return s;
    }

    @org.junit.jupiter.api.Test
    void theIdsShareBuckets() {
        assertEquals(10, IDS.size());
        assertEquals(Store.bucket(IDS.get(0)), Store.bucket(IDS.get(3)));
        assertEquals(Store.bucket(IDS.get(4)), Store.bucket(IDS.get(7)));
    }

    @Property(tries = 1000)
    void pullingRowsThatDifferEndsLikePullingWholeBuckets(@ForAll("histories") List<List<Op>> h) {
        List<Op> left = new ArrayList<>(h.get(0));
        left.addAll(h.get(1));
        List<Op> right = new ArrayList<>(h.get(0));
        right.addAll(h.get(2));
        Store a2 = of(left);
        Store b2 = of(right);
        Store a3 = of(left);
        Store b3 = of(right);
        for (int round = 0; round < 3; round++) {
            int bucketRows = sync(b2, a2, RowSyncProperties::byBuckets);
            int idRows = sync(b3, a3, RowSyncProperties::byRows);
            assertEquals(a2.toOps(), a3.toOps(), "a after pulling from b, round " + round);
            assertTrue(idRows <= bucketRows, idRows + " rows by id, " + bucketRows + " by buckets");
            bucketRows = sync(a2, b2, RowSyncProperties::byBuckets);
            idRows = sync(a3, b3, RowSyncProperties::byRows);
            assertEquals(b2.toOps(), b3.toOps(), "b after pulling from a, round " + round);
            assertTrue(idRows <= bucketRows, idRows + " rows by id, " + bucketRows + " by buckets");
        }
        assertEquals(roots(a3), roots(b3));
        assertEquals(live(a3), live(b3));
        Store union = of(left);
        union.applyAll(right);
        assertEquals(live(union), live(a3), "converges to the merge of both");
    }

    @Property(tries = 300)
    void aReplicaThatMissedOneWriteGetsThatRowAlone(@ForAll("histories") List<List<Op>> h, @ForAll("ids") String id) {
        Store a = of(h.get(1));
        Store b = of(a.toOps());
        assertEquals(0, sync(b, a, RowSyncProperties::byRows), "equal replicas move nothing");
        b.apply(new Op.Put("a", "t", id, Map.of("v", 9L), new Stamp(50, 9_999, "n9"), Map.of()));
        assertEquals(1, sync(b, a, RowSyncProperties::byRows));
        assertEquals(b.toOps(), a.toOps());
    }

    @Provide
    Arbitrary<String> ids() {
        return Arbitraries.of("9", "new", "x1"); // ids the histories never write
    }

    /** Root hashes without the entries that only placeholders produce (empty tables, schema hash 0). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> roots(Store s) {
        Map<String, Object> out = new TreeMap<>();
        s.rootHashes().forEach((db, m) -> {
            Map<String, Object> tables = new TreeMap<>((Map<String, Object>) m.get("tables"));
            tables.values().removeIf(v -> ((Long) v) == 0L);
            long schema = (Long) m.get("schema");
            if (schema != 0 || !tables.isEmpty()) {
                out.put(db, Map.of("schema", schema, "tables", tables));
            }
        });
        return out;
    }

    /** The state without tombstones: a delete of a row the other side never had need not travel. */
    static List<Op> live(Store s) {
        List<Op> out = new ArrayList<>(s.toOps());
        out.removeIf(o -> o instanceof Op.Delete);
        return out;
    }

    /**
     * Three lists of ops: a shared past, then what only the left and only the right replica got. The right
     * one also gets copies of some of the left's row writes without their context, as a 2.0 node passes on.
     */
    @Provide
    Arbitrary<List<List<Op>>> histories() {
        Arbitrary<String> db = Arbitraries.of("a", "b");
        Arbitrary<String> table = Arbitraries.of("t", "u");
        Arbitrary<String> id = Arbitraries.of(IDS);
        Arbitrary<Stamp> stamp = Combinators.combine(Arbitraries.longs().between(1, 40),
                Arbitraries.of("n1", "n2", "n3", "n4")).as((w, n) -> new Stamp(w, 0, n));
        Arbitrary<Map<String, Stamp>> seen = Arbitraries.maps(Arbitraries.of("n1", "n2", "n3", "n4", "*"),
                Arbitraries.longs().between(1, 40)).ofMaxSize(3).map(m -> {
                    Map<String, Stamp> out = new TreeMap<>();
                    m.forEach((k, w) -> out.put(k, new Stamp(w, 0, k.equals("*") ? "n1" : k)));
                    return out;
                }).injectNull(0.3);
        Arbitrary<Map<String, Object>> row = Arbitraries.longs().between(0, 5).map(v -> Map.of("v", (Object) v));
        Arbitrary<Op> schema = Arbitraries.oneOf(
                Combinators.combine(db, stamp).as(Op.CreateDb::new),
                Combinators.combine(db, stamp).as(Op.DropDb::new),
                Combinators.combine(db, table, stamp).as(Op.CreateTable::new),
                Combinators.combine(db, table, stamp).as(Op.DropTable::new));
        Arbitrary<Op> put = Combinators.combine(db, table, id, row, stamp, seen).as(Op.Put::new);
        Arbitrary<Op> delete = Combinators.combine(db, table, id, stamp, seen).as(Op.Delete::new);
        Arbitrary<Op> op = Arbitraries.frequencyOf(net.jqwik.api.Tuple.of(1, schema), net.jqwik.api.Tuple.of(4, put),
                net.jqwik.api.Tuple.of(1, delete));
        Arbitrary<List<Op>> list = op.list().ofMaxSize(20);
        return Combinators.combine(list, list, list, Arbitraries.integers().between(0, 3)).as((shared, l, r, copies) -> {
            List<Op> s = unique(shared, 0);
            List<Op> left = unique(l, 1_000);
            List<Op> right = new ArrayList<>(unique(r, 2_000));
            int n = 0;
            for (Op o : left) {
                if (o instanceof Op.Put p && p.seen() != null && n++ < copies) {
                    right.add(new Op.Put(p.db(), p.table(), p.id(), p.row(), p.stamp()));
                }
            }
            return List.of(s, left, right);
        });
    }

    /** Real stamps are unique (the counter and node id make them so): give each op its own counter. */
    private static List<Op> unique(List<Op> ops, int base) {
        List<Op> out = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Op o = ops.get(i);
            Stamp s = new Stamp(o.stamp().wall(), base + i, o.stamp().node());
            out.add(o instanceof Op.CreateDb c ? new Op.CreateDb(c.db(), s)
                    : o instanceof Op.DropDb c ? new Op.DropDb(c.db(), s)
                    : o instanceof Op.CreateTable c ? new Op.CreateTable(c.db(), c.table(), s)
                    : o instanceof Op.DropTable c ? new Op.DropTable(c.db(), c.table(), s)
                    : o instanceof Op.Put c ? new Op.Put(c.db(), c.table(), c.id(), c.row(), s, c.seen())
                    : new Op.Delete(o.db(), ((Op.Delete) o).table(), ((Op.Delete) o).id(), s, ((Op.Delete) o).seen()));
        }
        return out;
    }
}
