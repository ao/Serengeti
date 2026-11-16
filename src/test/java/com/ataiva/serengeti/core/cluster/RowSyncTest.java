package com.ataiva.serengeti.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.Store;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The row-by-row comparison of a catch-up (protocol 3) and the sizes of its pages. */
class RowSyncTest {
    private static Stamp at(long t, String node) {
        return new Stamp(t, 0, node);
    }

    private static Store table() {
        Store s = new Store();
        s.apply(new Op.CreateDb("d", at(1, "n1")));
        s.apply(new Op.CreateTable("d", "t", at(2, "n1")));
        return s;
    }

    private static Op put(String id, long v, Stamp at) {
        return new Op.Put("d", "t", id, Map.of("v", v), at, Map.of());
    }

    private static Set<Integer> all() {
        Set<Integer> out = new HashSet<>();
        for (int b = 0; b < Store.BUCKETS; b++) {
            out.add(b);
        }
        return out;
    }

    /** What this node pulls from {@code peer}: Cluster.differingIds over both sides' row hashes. */
    private static List<String> toPull(Store peer, Store here) {
        Map<String, Long> theirs = peer.rowHashes("d", "t", all());
        long[] h = theirs.values().stream().mapToLong(Long::longValue).toArray();
        return Cluster.differingIds(new ArrayList<>(theirs.keySet()), h, here.rowHashes("d", "t", all()));
    }

    @Test
    void onlyRowsThatDifferOrThatOnlyThePeerHasArePulled() {
        Store peer = table();
        Store here = table();
        Op same = put("same", 1, at(10, "n1"));
        peer.apply(same);
        here.apply(same);
        peer.apply(put("peerOnly", 2, at(11, "n1")));
        here.apply(put("hereOnly", 3, at(12, "n2")));
        Op old = put("changed", 4, at(13, "n1"));
        here.apply(old);
        peer.apply(old);
        peer.apply(new Op.Put("d", "t", "changed", Map.of("v", 5L), at(14, "n2"), Map.of("n1", at(13, "n1"))));
        // This node is ahead on this one: its hash differs, so it is asked for, and merging it changes nothing.
        Op before = put("ahead", 0, at(9, "n1"));
        peer.apply(before);
        here.apply(before);
        here.apply(new Op.Put("d", "t", "ahead", Map.of("v", 6L), at(15, "n2"), Map.of("n1", at(9, "n1"))));
        assertEquals(List.of("ahead", "changed", "peerOnly"), toPull(peer, here));
        assertEquals(List.of("ahead", "changed", "hereOnly"), toPull(here, peer));
        here.applyAll(peer.rowOps("d", "t", toPull(peer, here)));
        assertEquals(Map.of("v", 5L), here.row("d", "t", "changed"));
        assertEquals(Map.of("v", 6L), here.row("d", "t", "ahead"));
        assertEquals(0, here.conflicts());
        assertEquals(List.of("ahead"), toPull(peer, here), "only the row where this node is ahead still differs");
    }

    @Test
    void conflictingVersionsAndTombstonesShowAsDifferences() {
        Store peer = table();
        Store here = table();
        Op a = put("c", 1, at(10, "n1"));
        peer.apply(a);
        here.apply(a);
        peer.apply(put("c", 2, at(11, "n2"))); // concurrent with a: the peer holds both
        // A delete only one side has, and two tombstones of one row with different stamps.
        Op gone = put("gone", 1, at(12, "n1"));
        peer.apply(gone);
        here.apply(gone);
        peer.apply(new Op.Delete("d", "t", "gone", at(13, "n2"), Map.of("n1", at(12, "n1"))));
        here.apply(new Op.Delete("d", "t", "dead", at(14, "n1"), Map.of()));
        peer.apply(new Op.Delete("d", "t", "dead", at(15, "n2"), Map.of("n1", at(14, "n1"))));
        peer.apply(new Op.Delete("d", "t", "peerTombstone", at(16, "n2"), Map.of()));
        assertEquals(List.of("c", "dead", "gone", "peerTombstone"), toPull(peer, here));
        here.applyAll(peer.rowOps("d", "t", toPull(peer, here)));
        assertEquals(1, here.conflicts(), "both versions of c");
        assertEquals(peer.toOps(), here.toOps(), "after one pull this node holds what the peer has");
        assertEquals(List.of(), toPull(peer, here));
        assertEquals(List.of(), toPull(here, peer));
    }

    @Test
    void aCopyThatLostItsContextIsReplacedByTheFullOne() {
        Store peer = table();
        Store here = table();
        peer.apply(put("x", 1, at(10, "n1")));
        here.apply(new Op.Put("d", "t", "x", Map.of("v", 1L), at(10, "n1"))); // through a 2.0 node
        assertEquals(List.of("x"), toPull(peer, here));
        here.applyAll(peer.rowOps("d", "t", List.of("x")));
        assertEquals(List.of(), toPull(peer, here));
    }

    @Test
    void aPageOfHashesAsksForAboutTheTargetRows() {
        int[] counts = new int[Store.BUCKETS];
        Arrays.fill(counts, 100);
        List<Integer> held = new ArrayList<>();
        for (int b = 0; b < Store.BUCKETS; b++) {
            held.add(b);
        }
        assertEquals(16, Cluster.hashPageBuckets(held, 0, counts, 0, Cluster.HASH_TARGET_IDS, 16), "the first page");
        assertEquals(64, Cluster.hashPageBuckets(held, 16, counts, 100, Cluster.HASH_TARGET_IDS, 64));
        assertEquals(Cluster.BUCKETS_PER_REQUEST, Cluster.hashPageBuckets(held, 80, counts, 100, Cluster.HASH_TARGET_IDS, 256));
        assertEquals(20, Cluster.hashPageBuckets(held, 0, counts, 1_000, Cluster.HASH_TARGET_IDS, 200),
                "the peer's rows per bucket when it has more");
        Arrays.fill(counts, 50_000);
        assertEquals(1, Cluster.hashPageBuckets(held, 0, counts, 0, Cluster.HASH_TARGET_IDS, 200), "never fewer than one");
        assertEquals(4, Cluster.hashPageBuckets(held, 1020, counts, 0, Long.MAX_VALUE, 200), "never past the end");
    }

    @Test
    void aPageOfIdsKeepsToTheRouteLimits() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            ids.add("id" + i);
        }
        assertEquals(Cluster.FIRST_PULL_IDS, Cluster.idPageSize(ids, 0, Cluster.FIRST_PULL_IDS));
        assertEquals(Cluster.IDS_PER_REQUEST, Cluster.idPageSize(ids, 0, 1_000_000));
        assertEquals(1_000, Cluster.idPageSize(ids, 4_000, 1_000_000), "never past the end");
        List<String> long_ = List.of("x".repeat(600_000), "y".repeat(600_000), "z");
        assertEquals(1, Cluster.idPageSize(long_, 0, 2_000), "ids of more than IDS_REQUEST_CHARS split");
        assertEquals(2, Cluster.idPageSize(long_, 1, 2_000));
        // Rows of about 1 KB: 4 MiB is about 4,000, so the page is held at the 2,000 ids the route takes.
        assertEquals(2_000, Cluster.nextPullIds(500, 500, 500L * 1_100));
        assertEquals(2_000, Cluster.nextPullIds(500, 500, 500L * 100), "and never above it");
        assertEquals(409, Cluster.nextPullIds(500, 500, 500L * 10_240), "rows of 10 KB: about 4 MiB");
        assertEquals(1, Cluster.nextPullIds(10, 10, 10L * (8 << 20)), "at least one");
        assertEquals(40, Cluster.nextPullIds(10, 10, 10), "at most four times the last");
    }
}
