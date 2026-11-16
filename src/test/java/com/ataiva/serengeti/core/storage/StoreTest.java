package com.ataiva.serengeti.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoreTest {
    static Stamp at(long t) {
        return new Stamp(t, 0, "n1");
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static Stamp at(long t, String node) {
        return new Stamp(t, 0, node);
    }

    static Store table() {
        Store s = new Store();
        s.apply(new Op.CreateDb("shop", at(1)));
        s.apply(new Op.CreateTable("shop", "items", at(2)));
        return s;
    }

    static List<Stamp> versionStamps(Store s, String id) {
        List<Stamp> out = new java.util.ArrayList<>();
        s.forEachConflict("shop", "items", (rid, vs) -> {
            if (rid.equals(id)) {
                vs.forEach(v -> out.add(v.stamp()));
            }
        });
        return out;
    }

    @Test
    void opsWithoutAContextAreOrderedByStampAsIn20() {
        Store s = table();
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(5, "n2")));
        s.apply(new Op.Put("shop", "items", "a", row("v", 2), at(4, "n3")));
        s.apply(new Op.Delete("shop", "items", "a", at(3, "n1")));
        assertEquals(row("v", 1), s.row("shop", "items", "a"));
        assertEquals(0, s.conflicts());
    }

    @Test
    void writesThatHadNotSeenEachOtherAreBothKept() {
        Store s = table();
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"), Map.of()));
        s.apply(new Op.Put("shop", "items", "a", row("v", 2), at(11, "n2"), Map.of()));
        assertEquals(row("v", 2), s.row("shop", "items", "a"), "the newer one is the value");
        assertEquals(List.of(at(11, "n2"), at(10, "n1")), versionStamps(s, "a"));
        assertEquals(1, s.conflicts());
        // A write that had seen n1's version only replaces that one: n2's is still concurrent with it.
        s.apply(new Op.Put("shop", "items", "a", row("v", 3), at(12, "n3"), Map.of("n1", at(10, "n1"))));
        assertEquals(List.of(at(12, "n3"), at(11, "n2")), versionStamps(s, "a"));
        // A write here, with the context of everything held, supersedes all of them.
        List<Op> local = s.withContext(List.of(new Op.Put("shop", "items", "a", row("v", 4), at(13, "n1"))));
        s.applyAll(local);
        assertEquals(row("v", 4), s.row("shop", "items", "a"));
        assertEquals(0, s.conflicts());
        Map<String, Stamp> ctx = ((Op.Put) local.get(0)).seen();
        assertEquals(Map.of("n2", at(11, "n2"), "n3", at(12, "n3")), ctx, "own node and ~ are implied by the stamp");
    }

    @Test
    void aDeleteConcurrentWithAnUpdateIsKeptAsAConflict() {
        Store s = table();
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"), Map.of()));
        Map<String, Stamp> sawPut = Map.of("n1", at(10, "n1"));
        s.apply(new Op.Delete("shop", "items", "a", at(12, "n2"), sawPut));
        s.apply(new Op.Put("shop", "items", "a", row("v", 2), at(11, "n3"), sawPut));
        assertNull(s.row("shop", "items", "a"), "the newer version is the delete");
        assertEquals(0, s.rowCount("shop", "items"));
        assertEquals(List.of(at(12, "n2"), at(11, "n3")), versionStamps(s, "a"));
        assertEquals(0, s.collectTombstones(Long.MAX_VALUE), "a delete that is part of a conflict is not collected");
        // Inserting the id again (here, with the context) resolves it.
        s.applyAll(s.withContext(List.of(new Op.Put("shop", "items", "a", row("v", 3), at(13, "n1")))));
        assertEquals(row("v", 3), s.row("shop", "items", "a"));
        assertEquals(0, s.conflicts());
    }

    @Test
    void aLaterWriteCoversEarlierOpsWithoutAContext() {
        Store s = table();
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n2")));
        s.applyAll(s.withContext(List.of(new Op.Put("shop", "items", "a", row("v", 2), at(11, "n1")))));
        // A 2.0 node's copy of an older write arriving late changes nothing.
        s.apply(new Op.Put("shop", "items", "a", row("v", 0), at(9, "n3")));
        assertEquals(row("v", 2), s.row("shop", "items", "a"));
        assertEquals(0, s.conflicts());
    }

    @Test
    void aContextNeverOutgrowsWhatReadersAccept() {
        Store s = table();
        Op last = null;
        for (int i = 0; i < Op.MAX_SEEN + 10; i++) {
            // Each write is made on a different node that had seen everything before it.
            last = s.withContext(List.of(new Op.Put("shop", "items", "a", row("v", (long) i), at(10 + i, "w" + i)))).get(0);
            s.apply(last);
            assertTrue(((Op.Put) last).seen().size() <= Op.MAX_SEEN, "context of write " + i);
            assertEquals(last, Op.fromJson(Op.toJson(last)), "a reader accepts it");
        }
        assertEquals(row("v", (long) Op.MAX_SEEN + 9), s.row("shop", "items", "a"));
        assertEquals(0, s.conflicts(), "each write saw the one before it");
        assertEquals(Op.MAX_SEEN, ((Op.Put) last).seen().size());
        assertTrue(((Op.Put) last).seen().containsKey("w" + (Op.MAX_SEEN + 8)), "the newest writers are kept");
    }

    @Test
    void aCopyThatLostItsContextIsReplacedByTheFullOne() {
        Store full = table();
        Op.Put v = (Op.Put) full.withContext(List.of(new Op.Put("shop", "items", "a", row("v", 1L), at(10, "n2")))).get(0);
        full.apply(v);
        Store relayed = table();
        relayed.apply(new Op.Put("shop", "items", "a", row("v", 1L), at(10, "n2"))); // through a 2.0 node: no context
        assertFalse(java.util.Arrays.equals(full.bucketSums("shop", "items"), relayed.bucketSums("shop", "items")),
                "the two copies hash differently, so sync fetches the full one");
        assertTrue(relayed.isNew(v));
        relayed.apply(v);
        assertEquals(full.toOps(), relayed.toOps());
        assertTrue(java.util.Arrays.equals(full.bucketSums("shop", "items"), relayed.bucketSums("shop", "items")));
        // A later write that had seen it is then no conflict on either.
        Op later = relayed.withContext(List.of(new Op.Put("shop", "items", "a", row("v", 2L), at(11, "n1")))).get(0);
        full.apply(later);
        relayed.apply(later);
        assertEquals(0, full.conflicts());
        assertEquals(0, relayed.conflicts());
        // And the stripped copy arriving again changes nothing.
        assertFalse(relayed.isNew(new Op.Put("shop", "items", "a", row("v", 1L), at(10, "n2"))));
    }

    @Test
    void theDigestForTwoZeroPeersHashesWhatA20NodeHolds() {
        // A 2.1 node with a conflict, and a 2.0 node that holds only the newer version, without contexts.
        Store mine = table();
        mine.apply(new Op.Put("shop", "items", "a", row("v", 1L), at(10, "n1"), Map.of()));
        mine.apply(new Op.Put("shop", "items", "a", row("v", 2L), at(11, "n2"), Map.of()));
        mine.applyAll(mine.withContext(List.of(new Op.Put("shop", "items", "b", row("v", 3L), at(12, "n1")))));
        mine.apply(new Op.CreateIndex("shop", "items", "v", at(13)));
        assertEquals(1, mine.conflicts());
        Store theirs = table();
        theirs.apply(new Op.Put("shop", "items", "a", row("v", 2L), at(11, "n2")));
        theirs.apply(new Op.Put("shop", "items", "b", row("v", 3L), at(12, "n1")));
        assertEquals(theirs.digest("shop"), mine.digest("shop"));
        assertEquals(theirs.toOps("shop"), mine.legacyOps("shop"));
    }

    @Test
    void theSameWriteArrivingTwiceIsOneVersion() {
        Store s = table();
        Op.Put p = new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"), Map.of());
        s.apply(p);
        assertFalse(s.isNew(p));
        assertFalse(s.isNew(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"))), "same stamp, no context");
        s.apply(new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1")));
        assertEquals(0, s.conflicts());
    }

    @Test
    void contextsSurviveJsonAndBadOnesAreRefused() {
        Op.Put p = new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"), Map.of("n2", at(7, "n2"), "~", at(6, "n3")));
        assertEquals(p, Op.fromJson(Op.toJson(p)));
        assertFalse(Op.toLegacyJson(p).containsKey("seen"));
        Op.Put legacy = new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"));
        assertFalse(Op.toJson(legacy).containsKey("seen"));
        Map<String, Object> bad = new java.util.LinkedHashMap<>(Op.toJson(p));
        bad.put("seen", Map.of("n2", "7.0.n9"));
        assertTrue(assertThrowsIae(bad).contains("another node"));
        bad.put("seen", "nope");
        assertTrue(assertThrowsIae(bad).contains("'seen'"));
        Map<String, Object> many = new java.util.TreeMap<>();
        for (int i = 0; i <= Op.MAX_SEEN; i++) {
            many.put("n" + i, "1.0.n" + i);
        }
        bad.put("seen", many);
        assertTrue(assertThrowsIae(bad).contains("at most"));
    }

    @Test
    void opsOfAnUnknownKindAreSkippedOnlyIfTheKindIsAPlainName() {
        Map<String, Object> newer = new java.util.LinkedHashMap<>();
        newer.put("op", "create_view");
        newer.put("db", "shop");
        newer.put("table", "items");
        newer.put("at", "5.0.n2");
        Op.Put p = new Op.Put("shop", "items", "a", row("v", 1), at(10, "n1"));
        assertEquals(List.of(p), Op.listFromJson(List.of(newer, Op.toJson(p))));
        newer.put("op", "Drop Everything");
        assertTrue(assertThrowsIae(newer).contains("unknown op"));
    }

    private static String assertThrowsIae(Map<String, Object> json) {
        return org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Op.fromJson(json))
                .getMessage();
    }

    @Test
    void createAndRead() {
        Store s = new Store();
        s.apply(new Op.CreateDb("shop", at(1)));
        s.apply(new Op.CreateTable("shop", "items", at(2)));
        s.apply(new Op.Put("shop", "items", "a", row("name", "Lamp"), at(3)));
        assertEquals(List.of("shop"), s.databases());
        assertEquals(List.of("items"), s.tables("shop"));
        assertEquals(row("name", "Lamp"), s.row("shop", "items", "a"));
        assertEquals(1, s.rowCount("shop", "items"));
    }

    @Test
    void newerWriteWinsWhicheverArrivesFirst() {
        Store a = new Store();
        Store b = new Store();
        List<Op> base = List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2)));
        Op older = new Op.Put("d", "t", "x", row("v", 1L), at(5));
        Op newer = new Op.Put("d", "t", "x", row("v", 2L), at(6));
        a.applyAll(base);
        b.applyAll(base);
        a.apply(older);
        a.apply(newer);
        b.apply(newer);
        b.apply(older);
        assertEquals(row("v", 2L), a.row("d", "t", "x"));
        assertEquals(a.digests(), b.digests());
    }

    @Test
    void tombstoneHidesOlderPut() {
        Store s = new Store();
        s.applyAll(List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2))));
        s.apply(new Op.Delete("d", "t", "x", at(7)));
        s.apply(new Op.Put("d", "t", "x", row("v", 1L), at(6)));
        assertNull(s.row("d", "t", "x"));
        s.apply(new Op.Put("d", "t", "x", row("v", 3L), at(8)));
        assertEquals(row("v", 3L), s.row("d", "t", "x"));
    }

    @Test
    void dropRemovesOlderRowsAndRecreateStartsEmpty() {
        Store s = new Store();
        s.applyAll(List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2)),
                new Op.Put("d", "t", "x", row("v", 1L), at(3))));
        s.apply(new Op.DropTable("d", "t", at(4)));
        assertFalse(s.tableExists("d", "t"));
        s.apply(new Op.CreateTable("d", "t", at(5)));
        assertTrue(s.tableExists("d", "t"));
        assertEquals(0, s.rowCount("d", "t"));
        // a write made before the drop, arriving late, stays dropped
        s.apply(new Op.Put("d", "t", "y", row("v", 2L), at(3)));
        assertEquals(0, s.rowCount("d", "t"));
    }

    @Test
    void dropDatabaseDropsItsTablesEvenIfTheyArriveLater() {
        Store s = new Store();
        s.apply(new Op.CreateDb("d", at(1)));
        s.apply(new Op.DropDb("d", at(10)));
        s.apply(new Op.CreateTable("d", "t", at(5)));
        s.apply(new Op.Put("d", "t", "x", row("v", 1L), at(6)));
        assertFalse(s.dbExists("d"));
        s.apply(new Op.CreateDb("d", at(11)));
        assertTrue(s.dbExists("d"));
        assertFalse(s.tableExists("d", "t"));
        assertEquals(List.of(), s.tables("d"));
    }

    @Test
    void rowsForATableNotYetCreatedAppearWhenItIs() {
        Store s = new Store();
        s.apply(new Op.Put("d", "t", "x", row("v", 1L), at(3)));
        assertNull(s.row("d", "t", "x"));
        s.apply(new Op.CreateTable("d", "t", at(2)));
        s.apply(new Op.CreateDb("d", at(1)));
        assertEquals(row("v", 1L), s.row("d", "t", "x"));
    }

    @Test
    void toOpsRebuildsTheSameState() {
        Store s = new Store();
        s.applyAll(List.of(new Op.CreateDb("d", at(1)), new Op.CreateTable("d", "t", at(2)),
                new Op.Put("d", "t", "x", row("v", 1L), at(3)), new Op.Delete("d", "t", "y", at(4)),
                new Op.DropTable("d", "u", at(5)), new Op.CreateDb("e", at(6)), new Op.DropDb("e", at(7))));
        Store copy = new Store();
        copy.applyAll(s.toOps());
        assertEquals(s.digests(), copy.digests());
        assertEquals(s.toOps(), copy.toOps());
        assertEquals(s.maxStamp(), copy.maxStamp());
    }

    /** A store with a live row, a conflict, a tombstone and a 2.0 copy, spread over several buckets. */
    static Store rowsForHashes() {
        Store s = table();
        for (int i = 0; i < 40; i++) {
            s.apply(new Op.Put("shop", "items", "r" + i, row("v", i), at(10 + i, "n1"), Map.of()));
        }
        s.apply(new Op.Put("shop", "items", "c", row("v", 1), at(60, "n1"), Map.of()));
        s.apply(new Op.Put("shop", "items", "c", row("v", 2), at(61, "n2"), Map.of()));
        s.apply(new Op.Delete("shop", "items", "r3", at(70, "n1"), Map.of("n1", at(13, "n1"))));
        s.apply(new Op.Put("shop", "items", "old", row("v", 0), at(5, "n3")));
        return s;
    }

    static java.util.Set<Integer> allBuckets() {
        java.util.Set<Integer> all = new java.util.HashSet<>();
        for (int b = 0; b < Store.BUCKETS; b++) {
            all.add(b);
        }
        return all;
    }

    @Test
    void rowHashesOfLiveRowsAddUpToTheBucketSums() {
        Store s = rowsForHashes();
        Map<String, Long> h = s.rowHashes("shop", "items", allBuckets());
        assertEquals(42, h.size(), "every row, the tombstone included");
        assertEquals(new java.util.ArrayList<>(new java.util.TreeSet<>(h.keySet())), new java.util.ArrayList<>(h.keySet()),
                "in id order");
        long[] sums = new long[Store.BUCKETS];
        h.forEach((id, v) -> {
            if (s.row("shop", "items", id) != null) {
                sums[Store.bucket(id)] += v;
            }
        });
        assertEquals(java.util.Arrays.toString(s.bucketSums("shop", "items")), java.util.Arrays.toString(sums));
        assertTrue(h.get("r3") != 0, "a tombstone has a hash of its own here, unlike in the bucket sums");
    }

    @Test
    void rowHashesDifferExactlyWhenTheVersionsDo() {
        Store a = rowsForHashes();
        Store b = rowsForHashes();
        assertEquals(a.rowHashes("shop", "items", allBuckets()), b.rowHashes("shop", "items", allBuckets()));
        // Another tombstone stamp, one version of a conflict missing, a copy without its context.
        b.apply(new Op.Delete("shop", "items", "r3", at(71, "n2"), Map.of("n1", at(70, "n1"))));
        Store c = table();
        c.apply(new Op.Put("shop", "items", "c", row("v", 1), at(60, "n1"), Map.of()));
        c.apply(new Op.Put("shop", "items", "r5", row("v", 5), at(15, "n1")));
        Map<String, Long> ha = a.rowHashes("shop", "items", allBuckets());
        Map<String, Long> hb = b.rowHashes("shop", "items", allBuckets());
        Map<String, Long> hc = c.rowHashes("shop", "items", allBuckets());
        assertFalse(ha.get("r3").equals(hb.get("r3")));
        assertFalse(ha.get("c").equals(hc.get("c")));
        assertFalse(ha.get("r5").equals(hc.get("r5")));
        assertEquals(ha.get("r4"), hb.get("r4"));
    }

    @Test
    void rowHashesKeepToTheBucketsAskedFor() {
        Store s = rowsForHashes();
        int bucket = Store.bucket("r7");
        Map<String, Long> h = s.rowHashes("shop", "items", java.util.Set.of(bucket));
        assertTrue(h.containsKey("r7"));
        h.keySet().forEach(id -> assertEquals(bucket, Store.bucket(id), id));
        assertTrue(s.rowHashes("shop", "nope", allBuckets()).isEmpty());
        assertTrue(s.rowHashes("nope", "items", allBuckets()).isEmpty());
        int[] counts = s.bucketRowCounts("shop", "items");
        assertEquals(42, java.util.Arrays.stream(counts).sum(), "tombstones count as rows held");
        assertEquals(Store.BUCKETS, s.bucketRowCounts("nope", "items").length);
    }

    @Test
    void rowOpsAreEveryVersionOfTheRowsAskedFor() {
        Store s = rowsForHashes();
        List<Op> ops = s.rowOps("shop", "items", List.of("c", "r3", "missing", "c"));
        assertEquals(3, ops.size(), ops.toString());
        assertEquals(List.of(new Op.Put("shop", "items", "c", row("v", 2), at(61, "n2"), Map.of()),
                new Op.Put("shop", "items", "c", row("v", 1), at(60, "n1"), Map.of()),
                new Op.Delete("shop", "items", "r3", at(70, "n1"), Map.of("n1", at(13, "n1")))), ops);
        assertTrue(s.rowOps("shop", "nope", List.of("c")).isEmpty());
        Store copy = table();
        copy.applyAll(ops);
        assertEquals(s.rowHashes("shop", "items", java.util.Set.of(Store.bucket("c"))).get("c"),
                copy.rowHashes("shop", "items", java.util.Set.of(Store.bucket("c"))).get("c"));
    }

    // ---- full bucket sums and the expiry of tombstones ----

    static Map<String, Stamp> saw(Stamp... stamps) {
        Map<String, Stamp> m = new java.util.TreeMap<>();
        for (Stamp s : stamps) {
            m.put(s.node(), s);
        }
        return m;
    }

    static String sums(long[] a) {
        return java.util.Arrays.toString(a);
    }

    @Test
    void theFullSumsCountDeletesAndTheLiveSumsDoNot() {
        Store older = table();
        Store newer = table();
        for (Store s : List.of(older, newer)) {
            s.apply(new Op.Put("shop", "items", "x", row("v", 1), at(10, "n1"), Map.of()));
            s.apply(new Op.Delete("shop", "items", "x", at(20, "n2"), saw(at(10, "n1"))));
        }
        newer.apply(new Op.Delete("shop", "items", "x", at(30, "n3"), saw(at(10, "n1"), at(20, "n2"))));
        assertEquals(sums(older.bucketSums("shop", "items")), sums(newer.bucketSums("shop", "items")),
                "what 2.1 compares: no difference");
        assertFalse(sums(older.fullBucketSums("shop", "items")).equals(sums(newer.fullBucketSums("shop", "items"))));
        Map<String, Object> o = older.rootHashes().get("shop");
        Map<String, Object> n = newer.rootHashes().get("shop");
        assertEquals(o.get("tables"), n.get("tables"));
        assertFalse(o.get("full").equals(n.get("full")));
        // Merging the newer delete makes them equal; an empty table has all-zero sums.
        older.applyAll(newer.rowOps("shop", "items", List.of("x")));
        assertEquals(sums(newer.fullBucketSums("shop", "items")), sums(older.fullBucketSums("shop", "items")));
        assertEquals(sums(new long[Store.BUCKETS]), sums(older.fullBucketSums("shop", "none")));
    }

    @Test
    void theFullSumsFollowEveryChangeAndEqualARecount() {
        Store s = rowsForHashes();
        s.apply(new Op.Put("shop", "items", "r7", row("v", 70), at(80, "n2"), saw(at(17, "n1"))));
        s.apply(new Op.Delete("shop", "items", "r8", at(81, "n2"), saw(at(18, "n1"))));
        s.apply(new Op.Put("shop", "items", "r8", row("v", 80), at(82, "n3"), saw(at(18, "n1")))); // a conflict
        long[] sums = new long[Store.BUCKETS];
        s.rowHashes("shop", "items", allBuckets()).forEach((id, h) -> sums[Store.bucket(id)] += h);
        assertEquals(sums(sums), sums(s.fullBucketSums("shop", "items")));
        Store rebuilt = new Store();
        rebuilt.applyAll(s.toOps());
        assertEquals(sums(rebuilt.fullBucketSums("shop", "items")), sums(s.fullBucketSums("shop", "items")));
        // Dropping the table removes every row older than the drop, and their share of the sums.
        s.apply(new Op.DropTable("shop", "items", at(100, "n1")));
        assertEquals(sums(new long[Store.BUCKETS]), sums(s.fullBucketSums("shop", "items")));
    }

    @Test
    void expiredTombstonesAreLeftOutOfTheFullSumsAndTheHashesAndCollectingThemChangesNoSum() {
        Store kept = rowsForHashes();
        Store collected = rowsForHashes();
        long[] before = kept.fullBucketSums("shop", "items");
        // r3's only version is a delete at 70: expired once the expiry passes it.
        kept.setExpiry(71);
        collected.setExpiry(71);
        assertEquals(sums(kept.fullBucketSums("shop", "items")), sums(collected.fullBucketSums("shop", "items")));
        assertFalse(sums(before).equals(sums(kept.fullBucketSums("shop", "items"))));
        assertFalse(kept.rowHashes("shop", "items", allBuckets()).containsKey("r3"));
        assertEquals(1, collected.collectTombstones(71));
        assertEquals(sums(kept.fullBucketSums("shop", "items")), sums(collected.fullBucketSums("shop", "items")),
                "a node that collected them and one that may not yet compare equal");
        assertEquals(kept.rowHashes("shop", "items", allBuckets()), collected.rowHashes("shop", "items", allBuckets()));
        assertEquals(1, kept.tombstones(), "held, not collected");
        // A conflict that holds a value is never expired, however old its delete.
        assertTrue(kept.rowHashes("shop", "items", allBuckets()).containsKey("c"));
        kept.setExpiry(Long.MAX_VALUE);
        assertTrue(kept.rowHashes("shop", "items", allBuckets()).containsKey("c"));
        kept.setExpiry(Long.MIN_VALUE);
        assertEquals(sums(before), sums(kept.fullBucketSums("shop", "items")), "and back");
    }

    @Test
    void anExpiredDeleteForARowNotHeldIsNotMerged() {
        Store s = rowsForHashes();
        s.setExpiry(100);
        Op expiredForAbsent = new Op.Delete("shop", "items", "absent", at(99, "n2"), Map.of());
        Op olderForAbsent = new Op.Delete("shop", "items", "absent", at(90, "n3"), Map.of());
        // For a row held here it is kept: it covers the older version.
        Op expiredForLive = new Op.Delete("shop", "items", "r1", at(99, "n2"), saw(at(11, "n1")));
        // One that is not expired is kept for an absent row too, and so is a put, however old.
        Op recent = new Op.Delete("shop", "items", "absent2", at(100, "n2"), Map.of());
        Op oldPut = new Op.Put("shop", "items", "absent3", row("v", 1), at(5, "n2"), Map.of());
        // A conflict whose newest version is an old delete: the delete comes first, and must not be lost.
        Op conflictDelete = new Op.Delete("shop", "items", "absent4", at(98, "n2"), Map.of());
        Op conflictPut = new Op.Put("shop", "items", "absent4", row("v", 4), at(97, "n3"), Map.of());
        List<Op> batch = List.of(expiredForAbsent, olderForAbsent, expiredForLive, recent, oldPut, conflictDelete, conflictPut);
        assertEquals(List.of(expiredForLive, recent, oldPut, conflictDelete, conflictPut), s.withoutExpiredDeletes(batch));
        s.applyAll(s.withoutExpiredDeletes(batch));
        assertNull(s.row("shop", "items", "r1"));
        assertTrue(s.rowOps("shop", "items", List.of("absent")).isEmpty());
        assertEquals(2, s.rowOps("shop", "items", List.of("absent4")).size());
        assertEquals(batch, new Store().withoutExpiredDeletes(batch), "with no expiry set, nothing is left out");
    }

    @Test
    void everyNodeTakesTheSameExpiryWithinATenMinuteStep() {
        long ttl = 168 * 3_600_000L;
        long boundary = 1_794_844_200_000L; // a multiple of 10 minutes
        assertEquals(0, boundary % Store.EXPIRY_STEP_MILLIS);
        assertEquals(boundary - ttl, Store.expiryAt(boundary, ttl));
        assertEquals(boundary - ttl, Store.expiryAt(boundary + Store.EXPIRY_STEP_MILLIS - 1, ttl));
        assertEquals(boundary - ttl - Store.EXPIRY_STEP_MILLIS, Store.expiryAt(boundary - 1, ttl));
        // Two stores that collect at different moments of the same step drop the same tombstones.
        long cutoff = Store.expiryAt(boundary + 3_000, ttl);
        Store a = table();
        Store b = table();
        for (Store s : List.of(a, b)) {
            for (long w : new long[] {cutoff - 2, cutoff - 1, cutoff, cutoff + 1, cutoff + 90_000}) {
                s.apply(new Op.Put("shop", "items", "r" + w, row("v", 1), at(w - 10, "n1"), Map.of()));
                s.apply(new Op.Delete("shop", "items", "r" + w, at(w, "n1"), saw(at(w - 10, "n1"))));
            }
        }
        assertEquals(2, a.collectTombstones(Store.expiryAt(boundary + 1_000, ttl)));
        assertEquals(2, b.collectTombstones(Store.expiryAt(boundary + 590_000, ttl)));
        assertEquals(a.toOps(), b.toOps());
    }
}
