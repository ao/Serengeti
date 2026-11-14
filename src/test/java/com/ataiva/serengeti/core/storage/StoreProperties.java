package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** The properties replication relies on: order does not matter, and repeats change nothing. */
class StoreProperties {
    @Property(tries = 500)
    void anyOrderGivesTheSameState(@ForAll("ops") List<Op> ops, @ForAll long seed) {
        Store inOrder = new Store();
        inOrder.applyAll(ops);
        List<Op> shuffled = new ArrayList<>(ops);
        Collections.shuffle(shuffled, new Random(seed));
        Store other = new Store();
        other.applyAll(shuffled);
        assertEquals(digests(inOrder), digests(other));
        assertEquals(inOrder.toOps(), other.toOps());
    }

    /** Root hashes without the entries that only placeholders produce (empty tables, schema hash 0). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> roots(Store s) {
        Map<String, Object> out = new java.util.TreeMap<>();
        s.rootHashes().forEach((db, m) -> {
            Map<String, Object> tables = new java.util.TreeMap<>((Map<String, Object>) m.get("tables"));
            tables.values().removeIf(v -> ((Long) v) == 0L);
            long schema = (Long) m.get("schema");
            if (schema != 0 || !tables.isEmpty()) {
                out.put(db, Map.of("schema", schema, "tables", tables));
            }
        });
        return out;
    }

    static List<Op> withoutTombstones(List<Op> ops) {
        List<Op> out = new ArrayList<>(ops);
        out.removeIf(o -> o instanceof Op.Delete);
        return out;
    }

    @Property(tries = 500)
    void bucketSumsEqualAFreshRebuild(@ForAll("ops") List<Op> ops) {
        Store s = new Store();
        s.applyAll(ops);
        Store rebuilt = new Store();
        rebuilt.applyAll(s.toOps());
        assertEquals(roots(rebuilt), roots(s));
        for (String db : s.allDatabaseNames()) {
            for (String t : List.of("t", "u")) {
                assertEquals(java.util.Arrays.toString(rebuilt.bucketSums(db, t)),
                        java.util.Arrays.toString(s.bucketSums(db, t)), db + "." + t);
            }
        }
    }

    @Property(tries = 500)
    void anyOrderGivesTheSameRootHashes(@ForAll("ops") List<Op> ops, @ForAll long seed) {
        Store a = new Store();
        a.applyAll(ops);
        List<Op> shuffled = new ArrayList<>(ops);
        Collections.shuffle(shuffled, new Random(seed));
        Store b = new Store();
        b.applyAll(shuffled);
        assertEquals(roots(a), roots(b));
    }

    static List<Op> otherNodes(List<Op> ops) {
        List<Op> out = new ArrayList<>();
        for (Op o : ops) {
            Stamp s = new Stamp(o.stamp().wall(), o.stamp().counter(), "r" + o.stamp().node());
            out.add(o instanceof Op.CreateDb x ? new Op.CreateDb(x.db(), s)
                    : o instanceof Op.DropDb x ? new Op.DropDb(x.db(), s)
                    : o instanceof Op.CreateTable x ? new Op.CreateTable(x.db(), x.table(), s)
                    : o instanceof Op.DropTable x ? new Op.DropTable(x.db(), x.table(), s)
                    : o instanceof Op.Put x ? new Op.Put(x.db(), x.table(), x.id(), x.row(), s)
                    : new Op.Delete(o.db(), ((Op.Delete) o).table(), ((Op.Delete) o).id(), s));
        }
        return out;
    }

    /** One round of the cluster's sync, both ways: schema ops, then only the buckets whose sums differ. */
    static void syncRound(Store a, Store b) {
        java.util.Set<String> dbs = new java.util.TreeSet<>(a.allDatabaseNames());
        dbs.addAll(b.allDatabaseNames());
        for (String db : dbs) {
            if (a.schemaHash(db) != b.schemaHash(db)) {
                List<Op> fromA = a.schemaOps(db);
                List<Op> fromB = b.schemaOps(db);
                a.applyAll(fromB);
                b.applyAll(fromA);
            }
            for (String t : List.of("t", "u")) {
                long[] sa = a.bucketSums(db, t);
                long[] sb = b.bucketSums(db, t);
                java.util.Set<Integer> differ = new java.util.HashSet<>();
                for (int i = 0; i < Store.BUCKETS; i++) {
                    if (sa[i] != sb[i]) {
                        differ.add(i);
                    }
                }
                if (!differ.isEmpty()) {
                    List<Op> fromA = a.bucketOps(db, t, differ);
                    List<Op> fromB = b.bucketOps(db, t, differ);
                    a.applyAll(fromB);
                    b.applyAll(fromA);
                }
            }
        }
    }

    @Property(tries = 1000)
    void exchangingOnlyDifferingBucketsConverges(@ForAll("ops") List<Op> left, @ForAll("ops") List<Op> right) {
        // A stamp names its node, so two replicas never make different ops with the same stamp. The two
        // lists are generated independently, so give the right one its own node names.
        right = otherNodes(right);
        Store a = new Store();
        a.applyAll(left);
        Store b = new Store();
        b.applyAll(right);
        syncRound(a, b);
        syncRound(a, b);
        // Same visible state: the same schema, the same live rows. A tombstone for a row the other side
        // never had is not sent (it changes nothing there); it is sent once that row shows up.
        assertEquals(withoutTombstones(a.toOps()), withoutTombstones(b.toOps()));
        assertEquals(roots(a), roots(b));
        Store union = new Store();
        union.applyAll(left);
        union.applyAll(right);
        assertEquals(withoutTombstones(union.toOps()), withoutTombstones(a.toOps()), "converges to the merge of both");
    }

    @Property(tries = 300)
    void collectingTombstonesChangesNoRootHashAndNoLiveRow(@ForAll("ops") List<Op> ops) {
        Store s = new Store();
        s.applyAll(ops);
        Map<String, Object> before = roots(s);
        List<Op> live = withoutTombstones(s.toOps());
        int removed = s.collectTombstones(Long.MAX_VALUE);
        assertEquals(before, roots(s));
        assertEquals(live, s.toOps());
        assertEquals(0, s.tombstones());
        assertEquals(removed >= 0, true);
    }

    /** merge() logs only ops for which isNew is true, so isNew must be exactly "applying changes the state". */
    @Property(tries = 500)
    void isNewIsTrueExactlyWhenApplyingChangesTheState(@ForAll("ops") List<Op> state, @ForAll("ops") List<Op> more) {
        Store s = new Store();
        s.applyAll(state);
        for (Op op : more) {
            List<Op> before = s.toOps();
            boolean predicted = s.isNew(op);
            s.apply(op);
            assertEquals(!before.equals(s.toOps()), predicted, () -> "op " + op + " on " + before);
        }
    }

    @Property(tries = 300)
    void mergingOnlyNewOpsGivesTheSameStateAsApplyingAll(@ForAll("ops") List<Op> a, @ForAll("ops") List<Op> b) {
        Store all = new Store();
        all.applyAll(a);
        all.applyAll(b);
        Store filtered = new Store();
        filtered.applyAll(a);
        for (Op op : b) {
            if (filtered.isNew(op)) {
                filtered.apply(op);
            }
        }
        assertEquals(all.toOps(), filtered.toOps());
    }

    @Property(tries = 300)
    void applyingTwiceChangesNothing(@ForAll("ops") List<Op> ops) {
        Store once = new Store();
        once.applyAll(ops);
        Store twice = new Store();
        twice.applyAll(ops);
        twice.applyAll(ops);
        assertEquals(once.toOps(), twice.toOps());
    }

    @Property(tries = 300)
    void mergingStatesIsTheSameAsApplyingAllOps(@ForAll("ops") List<Op> ops) {
        List<Op> a = ops.subList(0, ops.size() / 2);
        List<Op> b = ops.subList(ops.size() / 2, ops.size());
        Store sa = new Store();
        sa.applyAll(a);
        Store sb = new Store();
        sb.applyAll(b);
        Store merged = new Store();
        merged.applyAll(sa.toOps());
        merged.applyAll(sb.toOps());
        Store all = new Store();
        all.applyAll(a);
        all.applyAll(b);
        assertEquals(all.toOps(), merged.toOps());
    }

    private static Map<String, String> digests(Store s) {
        Map<String, String> d = new java.util.TreeMap<>(s.digests());
        String empty = new Store().digest("none");
        d.values().removeIf(empty::equals);
        return d;
    }

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<String> db = Arbitraries.of("a", "b");
        Arbitrary<String> table = Arbitraries.of("t", "u");
        Arbitrary<String> id = Arbitraries.of("1", "2", "3");
        Arbitrary<Stamp> stamp = Combinators.combine(Arbitraries.longs().between(1, 30),
                Arbitraries.integers().between(0, 2), Arbitraries.of("n1", "n2", "n3")).as(Stamp::new);
        Arbitrary<Map<String, Object>> row = Arbitraries.longs().between(0, 5).map(v -> Map.of("v", (Object) v));
        Arbitrary<Op> op = Arbitraries.oneOf(
                Combinators.combine(db, stamp).as(Op.CreateDb::new),
                Combinators.combine(db, stamp).as(Op.DropDb::new),
                Combinators.combine(db, table, stamp).as(Op.CreateTable::new),
                Combinators.combine(db, table, stamp).as(Op.DropTable::new),
                Combinators.combine(db, table, id, row, stamp).as(Op.Put::new),
                Combinators.combine(db, table, id, row, stamp).as(Op.Put::new),
                Combinators.combine(db, table, id, stamp).as(Op.Delete::new));
        return op.list().ofMaxSize(25).map(StoreProperties::uniqueStamps);
    }

    /** Real stamps are unique (the counter and node id make them so); give each generated op its own. */
    private static List<Op> uniqueStamps(List<Op> ops) {
        List<Op> out = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Op o = ops.get(i);
            Stamp s = new Stamp(o.stamp().wall(), i, o.stamp().node());
            if (o instanceof Op.CreateDb c) {
                out.add(new Op.CreateDb(c.db(), s));
            } else if (o instanceof Op.DropDb c) {
                out.add(new Op.DropDb(c.db(), s));
            } else if (o instanceof Op.CreateTable c) {
                out.add(new Op.CreateTable(c.db(), c.table(), s));
            } else if (o instanceof Op.DropTable c) {
                out.add(new Op.DropTable(c.db(), c.table(), s));
            } else if (o instanceof Op.Put c) {
                out.add(new Op.Put(c.db(), c.table(), c.id(), c.row(), s));
            } else if (o instanceof Op.Delete c) {
                out.add(new Op.Delete(c.db(), c.table(), c.id(), s));
            }
        }
        return out;
    }
}
