package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Replicas that write locally (with causal contexts, as client writes are) and sync by buckets, checked
 * against the true causal history: after they sync, every replica keeps exactly the writes that no other
 * write to the same row had seen, and nothing else.
 */
class ConflictProperties {
    static final List<String> NODES = List.of("n1", "n2", "n3");
    static final List<String> IDS = List.of("1", "2");

    /** write(node, id, value or -1 for delete) or sync(node, node). */
    record Step(boolean sync, int a, int b, String id, long value) {}

    /** One replica, plus what the test knows that the store must not: the true causal past of its rows. */
    static final class Replica {
        final String node;
        final Store store = new Store();
        /** Per row: every write (by stamp) this replica knows of, directly or through a write it has seen. */
        final Map<String, Set<Stamp>> known = new HashMap<>();

        Replica(String node) {
            this.node = node;
            store.apply(new Op.CreateDb("a", new Stamp(1, 0, "init")));
            store.apply(new Op.CreateTable("a", "t", new Stamp(2, 0, "init")));
        }
    }

    static final class World {
        final List<Replica> replicas = new ArrayList<>();
        /** The true causal past of every write: the writes to its row its node knew of when writing it. */
        final Map<Stamp, Set<Stamp>> past = new HashMap<>();
        final Map<Stamp, String> rowOf = new HashMap<>();
        final Map<Stamp, Boolean> deleteOf = new HashMap<>();
        long time = 10;

        World() {
            for (String n : NODES) {
                replicas.add(new Replica(n));
            }
        }

        void write(int r, String id, long value) {
            Replica rep = replicas.get(r);
            Stamp at = new Stamp(++time, 0, rep.node); // one global clock, so causality implies stamp order
            Op.RowOp op = value < 0 ? new Op.Delete("a", "t", id, at) : new Op.Put("a", "t", id, Map.of("v", value), at);
            Op filled = rep.store.withContext(List.of(op)).get(0);
            rep.store.apply(filled);
            Set<Stamp> k = rep.known.computeIfAbsent(id, x -> new HashSet<>());
            past.put(at, new HashSet<>(k));
            rowOf.put(at, id);
            deleteOf.put(at, value < 0);
            k.add(at);
        }

        void sync(int a, int b) {
            Replica x = replicas.get(a);
            Replica y = replicas.get(b);
            // The cluster's bucket sync: each side sends the versions in the buckets whose sums differ; the
            // receiver learns those writes and everything they had seen.
            long[] sx = x.store.bucketSums("a", "t");
            long[] sy = y.store.bucketSums("a", "t");
            Set<Integer> differ = new HashSet<>();
            for (int i = 0; i < Store.BUCKETS; i++) {
                if (sx[i] != sy[i]) {
                    differ.add(i);
                }
            }
            List<Op> fromX = x.store.bucketOps("a", "t", differ);
            List<Op> fromY = y.store.bucketOps("a", "t", differ);
            x.store.applyAll(fromY);
            y.store.applyAll(fromX);
            learn(y, fromX);
            learn(x, fromY);
        }

        void learn(Replica r, List<Op> ops) {
            for (Op op : ops) {
                if (op instanceof Op.RowOp row && past.containsKey(row.stamp())) {
                    Set<Stamp> k = r.known.computeIfAbsent(row.id(), x -> new HashSet<>());
                    k.add(row.stamp());
                    k.addAll(past.get(row.stamp()));
                }
            }
        }

        /** The writes to {@code id} that no other write to it had seen. */
        Set<Stamp> maximal(String id) {
            Set<Stamp> all = new TreeSet<>();
            rowOf.forEach((s, row) -> {
                if (row.equals(id)) {
                    all.add(s);
                }
            });
            Set<Stamp> out = new TreeSet<>(all);
            for (Stamp s : all) {
                out.removeAll(transitivePast(s));
            }
            return out;
        }

        Set<Stamp> transitivePast(Stamp s) {
            Set<Stamp> out = new HashSet<>();
            List<Stamp> todo = new ArrayList<>(past.get(s));
            while (!todo.isEmpty()) {
                Stamp p = todo.remove(todo.size() - 1);
                if (out.add(p)) {
                    todo.addAll(past.get(p));
                }
            }
            return out;
        }
    }

    static Map<String, List<Stamp>> conflicts(Store s) {
        Map<String, List<Stamp>> out = new TreeMap<>();
        s.forEachConflict("a", "t", (id, vs) -> out.put(id, vs.stream().map(Store.Version::stamp).toList()));
        return out;
    }

    static Map<String, Set<Stamp>> versions(Store s) {
        Map<String, Set<Stamp>> out = new TreeMap<>();
        for (String id : IDS) {
            Set<Stamp> v = new TreeSet<>();
            for (Op op : s.toOps("a")) {
                if (op instanceof Op.RowOp r && r.id().equals(id)) {
                    v.add(r.stamp());
                }
            }
            out.put(id, v);
        }
        return out;
    }

    static World run(List<Step> steps) {
        World w = new World();
        for (Step st : steps) {
            if (st.sync()) {
                if (st.a() != st.b()) {
                    w.sync(st.a(), st.b());
                }
            } else {
                w.write(st.a(), st.id(), st.value());
            }
        }
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < NODES.size(); i++) {
                for (int j = i + 1; j < NODES.size(); j++) {
                    w.sync(i, j);
                }
            }
        }
        return w;
    }

    @Property(tries = 1000)
    void afterSyncEveryReplicaKeepsExactlyTheWritesNoOtherWriteHadSeen(@ForAll("histories") List<Step> steps) {
        World w = run(steps);
        for (String id : IDS) {
            Set<Stamp> truth = w.maximal(id);
            boolean live = truth.stream().anyMatch(s -> !w.deleteOf.get(s));
            for (Replica r : w.replicas) {
                Set<Stamp> held = versions(r.store).get(id);
                if (live) {
                    assertEquals(truth, held, () -> r.node + " row " + id + " after " + steps);
                } else {
                    // Only deletes are left: nothing is visible. Tombstones need not have spread (rows that are
                    // only deletes count for nothing in the bucket sums), so a replica may hold an older one.
                    assertEquals(null, r.store.row("a", "t", id), r.node + " row " + id);
                    for (Stamp s : held) {
                        assertEquals(true, w.deleteOf.get(s), () -> r.node + " holds a live version " + s + " of " + id);
                    }
                }
            }
        }
    }

    @Property(tries = 1000)
    void everyReplicaShowsTheSameRowsAndTheSameConflicts(@ForAll("histories") List<Step> steps) {
        World w = run(steps);
        Store first = w.replicas.get(0).store;
        for (Replica r : w.replicas) {
            assertEquals(StoreProperties.withoutTombstones(first.toOps()), StoreProperties.withoutTombstones(r.store.toOps()));
            assertEquals(StoreProperties.roots(first), StoreProperties.roots(r.store));
            for (String id : IDS) {
                assertEquals(first.row("a", "t", id), r.store.row("a", "t", id));
            }
            assertEquals(conflicts(first), conflicts(r.store), "SHOW CONFLICTS agrees on " + r.node);
            assertEquals(first.conflicts(), r.store.conflicts());
        }
    }

    @Property(tries = 500)
    void theRowsValueIsTheNewestOfItsVersions(@ForAll("histories") List<Step> steps) {
        World w = run(steps);
        for (Replica r : w.replicas) {
            for (String id : IDS) {
                Set<Stamp> held = versions(r.store).get(id);
                if (held.isEmpty()) {
                    continue;
                }
                Stamp newest = ((TreeSet<Stamp>) held).last();
                Object expected = null;
                for (Op op : r.store.toOps("a")) {
                    if (op instanceof Op.Put p && p.stamp().equals(newest)) {
                        expected = p.row();
                    }
                }
                assertEquals(expected, r.store.row("a", "t", id));
            }
        }
    }

    @Property(tries = 500)
    void opsWithContextsMergeTheSameInAnyOrder(@ForAll("histories") List<Step> steps, @ForAll long seed) {
        World w = new World();
        List<Op> all = new ArrayList<>();
        for (Step st : steps) {
            if (st.sync()) {
                if (st.a() != st.b()) {
                    w.sync(st.a(), st.b());
                }
            } else {
                Replica rep = w.replicas.get(st.a());
                List<Op> before = rep.store.toOps("a");
                w.write(st.a(), st.id(), st.value());
                for (Op op : rep.store.toOps("a")) {
                    if (!before.contains(op)) {
                        all.add(op);
                    }
                }
            }
        }
        Store inOrder = new Store();
        inOrder.applyAll(all);
        List<Op> shuffled = new ArrayList<>(all);
        Collections.shuffle(shuffled, new Random(seed));
        Store other = new Store();
        other.applyAll(shuffled);
        assertEquals(inOrder.toOps(), other.toOps());
        assertEquals(StoreProperties.roots(inOrder), StoreProperties.roots(other));
        for (Op op : shuffled) {
            assertEquals(false, other.isNew(op), "every op is already merged");
        }
    }

    @Property(tries = 300)
    void aSnapshotOfOpsRebuildsTheSameVersions(@ForAll("histories") List<Step> steps) {
        World w = run(steps);
        for (Replica r : w.replicas) {
            Store rebuilt = new Store();
            for (Object json : Op.toJson(r.store.toOps())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) com.ataiva.serengeti.core.json.Json.parse(
                        com.ataiva.serengeti.core.json.Json.write(json));
                rebuilt.apply(Op.fromJson(m));
            }
            assertEquals(r.store.toOps(), rebuilt.toOps());
        }
    }

    /** Merging stays order-independent and isNew exact for any contexts, floors and missing contexts included. */
    @Property(tries = 1000)
    void arbitraryContextsMergeTheSameInAnyOrder(@ForAll("contextOps") List<Op> ops, @ForAll long seed) {
        Store a = new Store();
        a.applyAll(ops);
        List<Op> shuffled = new ArrayList<>(ops);
        Collections.shuffle(shuffled, new Random(seed));
        Store b = new Store();
        for (Op op : shuffled) {
            List<Op> before = b.toOps();
            boolean predicted = b.isNew(op);
            b.apply(op);
            assertEquals(!before.equals(b.toOps()), predicted, () -> "isNew for " + op);
        }
        assertEquals(a.toOps(), b.toOps());
        assertEquals(StoreProperties.roots(a), StoreProperties.roots(b));
        Store twice = new Store();
        twice.applyAll(ops);
        twice.applyAll(shuffled);
        assertEquals(a.toOps(), twice.toOps());
    }

    @Provide
    Arbitrary<List<Op>> contextOps() {
        List<String> nodes = List.of("n1", "n2", "n3", "n4");
        Arbitrary<Stamp> stamp = Combinators.combine(Arbitraries.longs().between(1, 40), Arbitraries.of(nodes))
                .as((w, n) -> new Stamp(w, 0, n));
        Arbitrary<Map<String, Stamp>> seen = Arbitraries.maps(Arbitraries.of("n1", "n2", "n3", "n4", "*"),
                Arbitraries.longs().between(1, 40)).ofMaxSize(4).map(m -> {
                    Map<String, Stamp> out = new TreeMap<>();
                    m.forEach((k, w) -> out.put(k, new Stamp(w, 0, k.equals("*") ? "n1" : k)));
                    return out;
                }).injectNull(0.2);
        Arbitrary<Op> op = Combinators.combine(Arbitraries.of(IDS), Arbitraries.longs().between(-1, 3), stamp, seen)
                .as((id, v, s, ctx) -> v < 0 ? (Op) new Op.Delete("a", "t", id, s, ctx)
                        : (Op) new Op.Put("a", "t", id, Map.of("v", v), s, ctx));
        return op.list().ofMaxSize(20).map(list -> {
            // A stamp names one write: give each op its own counter so no two differ under one stamp.
            List<Op> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Op.RowOp r = (Op.RowOp) list.get(i);
                Stamp s = new Stamp(r.stamp().wall(), i + 1, r.stamp().node());
                out.add(r instanceof Op.Put p ? new Op.Put("a", "t", p.id(), p.row(), s, p.seen())
                        : new Op.Delete("a", "t", r.id(), s, r.seen()));
            }
            return out;
        });
    }

    @Provide
    Arbitrary<List<Step>> histories() {
        Arbitrary<Integer> node = Arbitraries.integers().between(0, NODES.size() - 1);
        Arbitrary<Step> write = Combinators.combine(node, Arbitraries.of(IDS), Arbitraries.longs().between(-1, 3))
                .as((n, id, v) -> new Step(false, n, n, id, v));
        Arbitrary<Step> sync = Combinators.combine(node, node).as((a, b) -> new Step(true, a, b, "", 0));
        return Arbitraries.frequencyOf(net.jqwik.api.Tuple.of(3, write), net.jqwik.api.Tuple.of(1, sync))
                .list().ofMaxSize(30);
    }
}
