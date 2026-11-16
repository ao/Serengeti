package com.ataiva.serengeti.core.cluster;

import com.ataiva.serengeti.core.config.Cidr;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.server.InternalRoutes;
import com.ataiva.serengeti.core.server.QueryService;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Store;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;

/**
 * Full replication across a fixed set of nodes.
 *
 * <p>A client write is committed on the node that received it, then sent to every peer; the client gets
 * its answer once a majority of the cluster (this node included) has the write in its log. A write is
 * refused up front when fewer than a majority are reachable. Every second each node compares per-database
 * digests with each peer and pulls the state of any database that differs; since the state merges like a
 * CRDT ({@link Store}), this brings a restarted or partitioned node up to date. A starting node accepts no
 * writes until it has synced with a majority.
 *
 * <p>Concurrent writes to one row on different nodes resolve last-writer-wins by hybrid logical clock.
 * This is not linearizable: see docs/CONSISTENCY.md.
 */
public final class Cluster implements StorageEngine.WriteHooks, InternalRoutes, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Cluster.class.getName());
    /** A peer counts as reachable if it answered within this long. */
    static final long REACHABLE_MILLIS = 3_000;
    static final long SYNC_INTERVAL_MILLIS = 1_000;
    static final long WRITE_TIMEOUT_MILLIS = 5_000;
    static final long DISCOVERY_INTERVAL_MILLIS = 10_000;
    /** How often a peer found running 2.0 is asked again whether it has the bucket routes (it may have been upgraded). */
    static final long PROBE_MILLIS = 30_000;

    private static final class Peer {
        final String address;
        volatile long lastContact = Long.MIN_VALUE / 2;
        /** When a sync with this peer last completed without error. */
        volatile long lastSynced = Long.MIN_VALUE / 2;
        /** The sync with this peer that is still running, if any. */
        volatile CompletableFuture<Boolean> inFlight;
        volatile String node;
        volatile String lastError;
        /** The peer runs 2.0 and has only the whole-database routes. */
        volatile boolean protocol1;
        /** When protocol1 was last found true; the peer is asked for the bucket routes again after PROBE_MILLIS. */
        volatile long protocol1Since;
        /** A sync by buckets with this peer has completed since this node started: it runs 2.1 or later. */
        volatile boolean v2Synced;
        /** This node is stale and has pushed the writes it made while away to this peer. */
        volatile boolean isolatedPushed;
        /** Counts the syncs started with this peer; a sync's pulls stop once a newer one has started. */
        volatile long generation;
        /** A hello sent while a long sync runs, so the peer does not look unreachable meanwhile. */
        volatile CompletableFuture<Void> probe;

        Peer(String address) {
            this.address = address;
        }
    }

    private final String nodeId;
    private final int size;
    private final int quorum;
    private final StorageEngine engine;
    private final byte[] keyDigest;
    private final PeerClient client;
    private final LongSupplier clock;
    private final ExecutorService io;
    private final ScheduledExecutorService timer;
    private final List<Peer> peers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> ignored = ConcurrentHashMap.newKeySet();
    private final Cidr discovery;
    private final int port;
    private volatile boolean caughtUp;
    private volatile boolean closed;
    /** Away from a majority for longer than the tombstone TTL; refuses to sync until emptied. */
    private volatile boolean stale;
    private final long tombstoneTtl;
    private final boolean localAckByDefault;
    private final java.nio.file.Path lastSyncFile;
    private volatile long lastMajoritySync;
    private volatile long lastSyncWritten;

    /** File in the data directory: when this node last synced with a majority, in Unix milliseconds. */
    public static final String LAST_SYNC_FILE = "last-sync";

    /**
     * Acknowledgement for the write being made on this thread: a majority of nodes (the default), or this
     * node only. Set by the query layer for one statement.
     */
    public enum Ack {
        MAJORITY, LOCAL
    }

    private static final ThreadLocal<Ack> ACK = new ThreadLocal<>();

    /** Runs {@code body} with this acknowledgement for any write it makes, then restores the previous one. */
    public static <T, E extends Exception> T withAck(Ack ack, java.util.concurrent.Callable<T> body) throws Exception {
        Ack before = ACK.get();
        ACK.set(ack);
        try {
            return body.call();
        } finally {
            if (before == null) {
                ACK.remove();
            } else {
                ACK.set(before);
            }
        }
    }

    private boolean localAck() {
        Ack a = ACK.get();
        return a == null ? localAckByDefault : a == Ack.LOCAL;
    }

    public Cluster(Config config, String nodeId, StorageEngine engine, SSLContext clientTls) {
        this(config, nodeId, engine, clientTls, System::currentTimeMillis);
    }

    Cluster(Config config, String nodeId, StorageEngine engine, SSLContext clientTls, LongSupplier clock) {
        this.tombstoneTtl = config.tombstoneTtlMillis;
        this.localAckByDefault = config.localWriteAck;
        this.lastSyncFile = config.dataDir.resolve(LAST_SYNC_FILE);
        long last = readLastSync(lastSyncFile);
        if (last > 0 && clock.getAsLong() - last > tombstoneTtl) {
            // It starts, but stale: no sync and no writes, so its old rows cannot bring back deleted ones. It
            // still hands the changes it made after `last` to its peers (pushIsolatedWrites).
            this.stale = true;
            LOG.severe("this node last synced with a majority of its cluster at " + java.time.Instant.ofEpochMilli(last)
                    + ", longer ago than tombstone_ttl_hours (" + tombstoneTtl / 3_600_000 + "). Rows deleted since then"
                    + " could come back through it, so it does not sync or take writes. It hands the changes it made"
                    + " since then to its peers; then stop it, empty its data directory except users.json and node-id,"
                    + " and start it again to resync.");
        }
        this.lastMajoritySync = last > 0 ? last : clock.getAsLong();
        this.nodeId = nodeId;
        this.size = config.clusterSize;
        this.quorum = config.quorum();
        this.engine = engine;
        this.keyDigest = sha256(config.clusterKey);
        this.clock = clock;
        this.port = config.port;
        this.io = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cluster-io");
            t.setDaemon(true);
            return t;
        });
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-sync");
            t.setDaemon(true);
            return t;
        });
        this.client = new PeerClient(config.scheme(), clientTls, config.clusterKey, io);
        for (String p : config.peers) {
            peers.add(new Peer(p));
        }
        this.discovery = config.discoveryCidr == null ? null : Cidr.parse(config.discoveryCidr);
    }

    private static long readLastSync(java.nio.file.Path f) {
        try {
            if (!java.nio.file.Files.exists(f)) {
                return 0;
            }
            return Long.parseLong(java.nio.file.Files.readString(f).trim());
        } catch (IOException | NumberFormatException e) {
            LOG.warning("cannot read " + f + "; treating this node as freshly synced: " + e.getMessage());
            return 0;
        }
    }

    /** Records a sync with a majority; writes the file at most once a minute. */
    private void majoritySynced() {
        long now = clock.getAsLong();
        lastMajoritySync = now;
        if (now - lastSyncWritten >= 60_000) {
            try {
                java.nio.file.Path tmp = lastSyncFile.resolveSibling(LAST_SYNC_FILE + ".tmp");
                java.nio.file.Files.writeString(tmp, Long.toString(now) + "\n");
                java.nio.file.Files.move(tmp, lastSyncFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                lastSyncWritten = now;
            } catch (IOException e) {
                LOG.warning("cannot write " + lastSyncFile + ": " + e.getMessage());
            }
        }
    }

    /** True if writes on this node are acknowledged locally unless a request asks for a majority. */
    public boolean localAckByDefault() {
        return localAckByDefault;
    }

    /** True if this node has been away from a majority for longer than the tombstone TTL. */
    public boolean stale() {
        return stale;
    }

    /** Starts syncing (and discovery, if configured). Call once the HTTP API serves {@code /internal/}. */
    public void start() {
        if (discovery != null) {
            timer.scheduleWithFixedDelay(this::discover, 0, DISCOVERY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        }
        timer.scheduleWithFixedDelay(this::syncRound, 0, SYNC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    public int size() {
        return size;
    }

    public int quorum() {
        return quorum;
    }

    /** True once this node has synced with a majority since it started. */
    public boolean caughtUp() {
        return caughtUp;
    }

    /** Nodes reachable now, this one included. */
    public int reachable() {
        long now = clock.getAsLong();
        int n = 1;
        for (Peer p : peers) {
            if (now - p.lastContact < REACHABLE_MILLIS) {
                n++;
            }
        }
        return n;
    }

    /** For GET /v1/status. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", size);
        m.put("quorum", quorum);
        m.put("reachable", reachable());
        m.put("caught_up", caughtUp);
        m.put("stale", stale);
        m.put("sync_ops_pulled", pulledOps.get());
        m.put("write_ack", localAckByDefault ? "local" : "majority");
        List<Object> ps = new ArrayList<>();
        long now = clock.getAsLong();
        for (Peer p : peers) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("address", p.address);
            pm.put("node", p.node);
            pm.put("reachable", now - p.lastContact < REACHABLE_MILLIS);
            ps.add(pm);
        }
        m.put("peers", ps);
        return m;
    }

    // ---- writes ----

    @Override
    public void before() {
        if (closed) {
            throw new UnavailableException("this node is stopping");
        }
        if (stale) {
            throw new UnavailableException("this node has been away from its cluster for longer than "
                    + "tombstone_ttl_hours and must be emptied and resynced");
        }
        if (localAck()) {
            // Accepted on this node alone; sync merges it into the others when they are reachable.
            return;
        }
        if (!caughtUp) {
            throw new UnavailableException("this node is still catching up with the cluster");
        }
        int r = reachable();
        if (r < quorum) {
            throw new UnavailableException(r + " of " + size + " nodes are reachable; writes need " + quorum
                    + " (or send \"ack\": \"local\" to accept it on this node and merge later)");
        }
    }

    /**
     * Sends a committed batch to a peer. A 2.0 peer gets it without the index ops, which it cannot read;
     * indexes are derived from the rows, so it still holds the same data, and it learns the indexes from
     * the schema sync once it runs 2.2.
     */
    private CompletableFuture<?> send(Peer p, List<Op> ops) {
        List<Op> batch = p.protocol1 ? Op.legacyOnly(ops) : ops;
        try {
            return batch.isEmpty() ? CompletableFuture.completedFuture(null) : client.apply(p.address, batch);
        } catch (RuntimeException e) {
            // The node is stopping and its I/O pool refuses work: this peer did not get it, like one that is
            // down. The write is in this node's log; the answer is the usual 503 for a majority write.
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void after(List<Op> committed) {
        if (quorum == 1 && peers.isEmpty()) {
            return;
        }
        if (localAck()) {
            // Try to pass it on now, but do not wait: the client has its answer once this node has it.
            for (Peer p : List.copyOf(peers)) {
                send(p, committed).whenComplete((v, err) -> {
                    if (err == null) {
                        p.lastContact = clock.getAsLong();
                    }
                });
            }
            return;
        }
        AtomicInteger acks = new AtomicInteger(1);
        AtomicInteger finished = new AtomicInteger();
        List<Peer> targets = List.copyOf(peers);
        // Completes when a majority has the write, or when every peer has answered either way.
        CompletableFuture<Void> settled = new CompletableFuture<>();
        if (acks.get() >= quorum) {
            settled.complete(null);
        }
        for (Peer p : targets) {
            send(p, committed).whenComplete((v, err) -> {
                if (err == null) {
                    p.lastContact = clock.getAsLong();
                    if (acks.incrementAndGet() >= quorum) {
                        settled.complete(null);
                    }
                } else {
                    p.lastError = PeerClient.describe(err);
                }
                if (finished.incrementAndGet() == targets.size()) {
                    settled.complete(null);
                }
            });
        }
        try {
            settled.get(WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            // counted below
        }
        int got = acks.get();
        if (got < quorum) {
            throw new UnavailableException("the write reached " + got + " of " + size + " nodes; " + quorum
                    + " are needed. It is stored on this node and may still reach the others.");
        }
    }

    // ---- reads ----

    /**
     * Brings this node up to date with enough peers that, with this node, they form a majority. Every write
     * acknowledged before this call is then visible locally.
     */
    public void syncForRead() {
        if (closed) {
            throw new UnavailableException("this node is stopping");
        }
        int needed = quorum - 1;
        if (needed <= 0) {
            return;
        }
        List<CompletableFuture<Boolean>> runs = new ArrayList<>();
        for (Peer p : peers) {
            try {
                runs.add(syncWith(p, true));
            } catch (RuntimeException e) {
                runs.add(CompletableFuture.completedFuture(false)); // stopping: counted as not synced
            }
        }
        long deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MILLIS;
        int ok = 0;
        for (CompletableFuture<Boolean> f : runs) {
            long left = deadline - System.currentTimeMillis();
            try {
                if (left > 0 && f.get(left, TimeUnit.MILLISECONDS)) {
                    ok++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // counted as not synced
            }
            if (ok >= needed) {
                return;
            }
        }
        throw new UnavailableException("a majority read needs " + quorum + " of " + size + " nodes; "
                + (ok + 1) + " answered");
    }

    // ---- sync ----

    /**
     * Starts a sync with every peer whose previous one has finished, and never waits for them: each peer
     * syncs on its own, so one that does not answer (a partition drops its packets, and each call runs
     * until its timeout) cannot hold up the others. Waiting here once let a single cut-off peer keep the
     * reachable ones from being contacted for longer than REACHABLE_MILLIS, so a node with a majority
     * reachable refused writes. A majority counts as synced while enough peers have completed a sync
     * within REACHABLE_MILLIS.
     */
    private void syncRound() {
        if (closed) {
            return;
        }
        try {
            for (Peer p : peers) {
                CompletableFuture<Boolean> f = p.inFlight;
                if (f == null || f.isDone()) {
                    p.generation++;
                    p.inFlight = syncWith(p, false).completeOnTimeout(false,
                            PeerClient.STATE_TIMEOUT.toMillis() + 5_000, TimeUnit.MILLISECONDS);
                } else if (clock.getAsLong() - p.lastContact > SYNC_INTERVAL_MILLIS
                        && (p.probe == null || p.probe.isDone())) {
                    // A sync that pulls many pages, or waits for its turn to pull, must not make a peer that
                    // answers look unreachable: writes need a majority reachable.
                    p.probe = client.hello(p.address).thenAccept(h -> p.lastContact = clock.getAsLong())
                            .exceptionally(err -> null);
                }
            }
            long now = clock.getAsLong();
            int ok = 0;
            for (Peer p : peers) {
                if (now - p.lastSynced < REACHABLE_MILLIS) {
                    ok++;
                }
            }
            if (ok + 1 >= quorum) {
                majoritySynced();
                if (!caughtUp) {
                    caughtUp = true;
                    LOG.info("caught up with " + ok + " of " + peers.size() + " peers; accepting writes");
                }
            } else if (!stale && clock.getAsLong() - lastMajoritySync > tombstoneTtl) {
                stale = true;
                LOG.severe("no sync with a majority for longer than tombstone_ttl_hours; this node now refuses "
                        + "writes and syncing. Stop it, empty its data directory except users.json and node-id, "
                        + "and start it again.");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "sync round failed", e);
        }
    }

    /**
     * True when tombstones may be collected: every peer has completed a sync by buckets since this node
     * started, so each runs 2.1 or later and has the guard that keeps a node away longer than the TTL from
     * rejoining. A 2.0 node has no such guard, and a peer not heard from since this node started may be one,
     * or a node restored from an old backup: until they show up, tombstones stay.
     */
    public boolean mayCollectTombstones() {
        if (stale) {
            return false;
        }
        for (Peer p : peers) {
            if (!p.v2Synced || p.protocol1) {
                return false;
            }
        }
        return true;
    }

    /** Most ops in one push from a stale node, to keep each request well under the internal body limit. */
    static final int PUSH_BATCH = 2_000;

    /**
     * A stale node does not sync (its old rows could bring back deleted ones), but the writes it made after it
     * last synced with a majority are newer than any tombstone the cluster can have collected, so they are
     * safe to hand on: it pushes them to each peer once, so writes it acknowledged while cut off are not lost
     * when it is emptied and resynced.
     */
    private CompletableFuture<Boolean> pushIsolatedWrites(Peer p) {
        if (p.isolatedPushed) {
            return CompletableFuture.completedFuture(false);
        }
        long since = lastMajoritySync;
        List<Op> ops = engine.read(s -> s.opsSince(since));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < ops.size(); i += PUSH_BATCH) {
            List<Op> batch = ops.subList(i, Math.min(ops.size(), i + PUSH_BATCH));
            chain = chain.thenCompose(v -> send(p, batch).thenAccept(x -> { }));
        }
        return chain.handle((v, err) -> {
            if (err == null) {
                p.isolatedPushed = true;
                LOG.warning("this node is out of date; it handed the " + ops.size() + " changes stamped after "
                        + java.time.Instant.ofEpochMilli(since) + " to " + p.address + ". Empty it and start it again.");
            }
            return false;
        });
    }

    /** Pulls every database whose digest differs from the peer's. Completes with true if it worked. */
    private CompletableFuture<Boolean> syncWith(Peer p, boolean forRead) {
        if (stale) {
            return pushIsolatedWrites(p);
        }
        // A peer found running 2.0 is asked for the bucket routes again now and then: it may have been upgraded.
        boolean byBuckets = !p.protocol1 || clock.getAsLong() - p.protocol1Since > PROBE_MILLIS;
        CompletableFuture<Integer> run = !byBuckets ? syncWholeDatabases(p) : syncByBuckets(p, forRead).handle((n, err) -> {
            if (err != null && unwrap(err) instanceof PeerClient.NotSupported) {
                if (!p.protocol1) {
                    LOG.info("peer " + p.address + " runs 2.0; syncing with it by whole databases");
                }
                p.protocol1 = true;
                p.protocol1Since = clock.getAsLong();
                return null;
            }
            if (err != null) {
                throw new java.util.concurrent.CompletionException(unwrap(err));
            }
            if (p.protocol1) {
                LOG.info("peer " + p.address + " now has the bucket routes; syncing with it by buckets");
                p.protocol1 = false;
            }
            p.v2Synced = true;
            return n;
        }).thenCompose(n -> n == null ? syncWholeDatabases(p) : CompletableFuture.completedFuture(n));
        return run.thenApply(merged -> {
            if (merged > 0) {
                LOG.fine("merged " + merged + " ops from " + p.address);
            }
            p.lastContact = clock.getAsLong();
            p.lastSynced = p.lastContact;
            if (p.lastError != null) {
                LOG.info("peer " + p.address + " is reachable again");
                p.lastError = null;
            }
            return true;
        }).exceptionally(err -> {
            if (unwrap(err) instanceof Superseded) {
                return false; // the newer sync goes on; nothing went wrong with the peer
            }
            String msg = PeerClient.describe(err);
            if (!msg.equals(p.lastError)) {
                LOG.warning("peer " + p.address + ": " + msg);
            }
            p.lastError = msg;
            return false;
        });
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException
                || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** Max buckets asked for in one request, to keep URLs short. */
    static final int BUCKETS_PER_REQUEST = 200;

    /**
     * Row pulls running at once on this node, over every peer and table. A pull holds a page of rows twice
     * (the response's text and the ops parsed from it); before this limit, a node that had missed writes
     * asked each peer for every differing bucket at once, and with a few hundred MB of data that took more
     * heap than the node had, on it and on the peers building the answers.
     */
    static final int MAX_PULLS = 2;
    /** A page aims at about this many ops, and at about {@link #PULL_TARGET_BYTES} of them. */
    static final int PULL_TARGET_OPS = 2_000;
    static final long PULL_TARGET_BYTES = 4L << 20;
    /** The first page of a table asks for this many buckets; the next follow what the last one held. */
    static final int FIRST_PULL_BUCKETS = 16;
    /** A page asks for at most this many times the buckets of the one before. */
    static final int PULL_GROWTH = 4;

    /**
     * Row pulls of the syncs that majority reads run, at once, apart from the background ones: a read waits
     * for its syncs (5 s at most), and queued behind a long catch-up it got 503 instead (seen in the soak).
     */
    static final int MAX_READ_PULLS = 4;

    private final AsyncLimiter pullLimiter = new AsyncLimiter(MAX_PULLS, PeerClient.STATE_TIMEOUT.toMillis() + 5_000);
    private final AsyncLimiter readPullLimiter = new AsyncLimiter(MAX_READ_PULLS, PeerClient.STATE_TIMEOUT.toMillis() + 5_000);

    /** A background sync whose pages stopped because a newer sync with the peer started. It counts as failed. */
    static final class Superseded extends RuntimeException {
        Superseded() {
            super("a newer sync with the peer took over", null, false, false);
        }
    }

    /**
     * Pulls the rows of the differing buckets from {@code from} on, one page at a time and through
     * {@code limiter}, sizing each page from the last: a table of large rows gets pages of few buckets. A
     * background sync ({@code generation} 0 or more) stops with {@link Superseded} once a newer one with the
     * peer has started, which pulls what is still missing; it must not count as a completed sync. A read's
     * sync ({@code generation} -1) always runs to the end or fails.
     */
    private CompletableFuture<Integer> pullBuckets(Peer p, AsyncLimiter limiter, long generation, String db,
            String table, List<Integer> differ, int from, int buckets, int merged) {
        if (from >= differ.size()) {
            return CompletableFuture.completedFuture(merged);
        }
        if (closed) {
            return CompletableFuture.failedFuture(new UnavailableException("this node is stopping"));
        }
        if (generation >= 0 && p.generation != generation) {
            return CompletableFuture.failedFuture(new Superseded());
        }
        List<Integer> page = List.copyOf(differ.subList(from, Math.min(differ.size(), from + buckets)));
        return limiter.run(() -> client.rows(p.address, db, table, page)).thenCompose(ops -> {
            p.lastContact = clock.getAsLong();
            int n = mergeOrThrow(ops);
            int next = nextPullBuckets(page.size(), ops.size(), estimateBytes(ops));
            return pullBuckets(p, limiter, generation, db, table, differ, from + page.size(), next, merged + n);
        });
    }

    /** The buckets of the next page, from what the last one asked for and held. */
    static int nextPullBuckets(int asked, int ops, long bytes) {
        double perBucketOps = Math.max(1.0, (double) ops / asked);
        double perBucketBytes = Math.max(1.0, (double) bytes / asked);
        double fit = Math.min(PULL_TARGET_OPS / perBucketOps, PULL_TARGET_BYTES / perBucketBytes);
        long next = (long) Math.floor(fit);
        return (int) Math.max(1, Math.min(Math.min(BUCKETS_PER_REQUEST, (long) asked * PULL_GROWTH), next));
    }

    /** A rough size of pulled ops in memory: their ids and string values, and a fixed cost per op and value. */
    static long estimateBytes(List<Op> ops) {
        long n = 0;
        for (Op op : ops) {
            n += 96;
            if (op instanceof Op.Put p) {
                n += 2L * p.id().length();
                for (Map.Entry<String, Object> e : p.row().entrySet()) {
                    n += 64 + e.getKey().length() + valueBytes(e.getValue());
                }
            } else if (op instanceof Op.Delete d) {
                n += 2L * d.id().length();
            }
        }
        return n;
    }

    private static long valueBytes(Object v) {
        if (v instanceof String s) {
            return 40 + s.length();
        }
        if (v instanceof Map<?, ?> m) {
            long n = 56;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                n += 64 + String.valueOf(e.getKey()).length() + valueBytes(e.getValue());
            }
            return n;
        }
        if (v instanceof List<?> l) {
            long n = 40;
            for (Object o : l) {
                n += 8 + valueBytes(o);
            }
            return n;
        }
        return 24;
    }

    /** For tests: pulls running and waiting. */
    int[] pullsForTest() {
        return new int[] {pullLimiter.running(), pullLimiter.waiting()};
    }

    /**
     * Protocol 2: compare per-table roots, then bucket sums of the tables that differ, then pull the rows
     * of the buckets that differ. In a steady cluster this moves only the root map; under writes, only the
     * buckets that the writes touched. Completes with the number of ops merged.
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<Integer> syncByBuckets(Peer p, boolean forRead) {
        long generation = forRead ? -1 : p.generation;
        AsyncLimiter limiter = forRead ? readPullLimiter : pullLimiter;
        return client.roots(p.address).thenCompose(theirs -> {
            p.lastContact = clock.getAsLong(); // it answered; a long pull that follows must not age it out
            Map<String, Map<String, Object>> mine = engine.read(Store::rootHashes);
            List<CompletableFuture<Integer>> work = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> e : theirs.entrySet()) {
                String db = e.getKey();
                Map<String, Object> m = mine.get(db);
                long mySchema = m == null ? 0 : ((Number) m.get("schema")).longValue();
                long theirSchema = ((Number) e.getValue().get("schema")).longValue();
                CompletableFuture<Integer> schemaStep = mySchema == theirSchema
                        ? CompletableFuture.completedFuture(0)
                        : client.schema(p.address, db).thenApply(this::mergeOrThrow);
                Map<String, Object> theirTables = (Map<String, Object>) e.getValue().get("tables");
                Map<String, Object> myTables = m == null ? Map.of() : (Map<String, Object>) m.get("tables");
                for (Map.Entry<String, Object> t : theirTables.entrySet()) {
                    long theirRoot = ((Number) t.getValue()).longValue();
                    Object mineRoot = myTables.get(t.getKey());
                    long myRoot = mineRoot == null ? 0 : ((Number) mineRoot).longValue();
                    if (theirRoot == myRoot) {
                        continue;
                    }
                    String table = t.getKey();
                    // Schema first, so a drop that removes rows is applied before rows are compared.
                    work.add(schemaStep.thenCompose(x -> client.buckets(p.address, db, table)).thenCompose(sums -> {
                        long[] local = engine.read(s -> s.bucketSums(db, table));
                        List<Integer> differ = new ArrayList<>();
                        for (int i = 0; i < sums.length; i++) {
                            if (sums[i] != local[i]) {
                                differ.add(i);
                            }
                        }
                        return pullBuckets(p, limiter, generation, db, table, differ, 0, FIRST_PULL_BUCKETS, 0);
                    }));
                }
                work.add(schemaStep);
            }
            return sum(work);
        });
    }

    /** Protocol 1 (2.0 peers): compare one SHA-256 per database and pull whole databases that differ. */
    private CompletableFuture<Integer> syncWholeDatabases(Peer p) {
        return client.digests(p.address).thenCompose(theirs -> {
            p.lastContact = clock.getAsLong();
            Map<String, String> mine = engine.read(Store::digests);
            Set<String> differ = new TreeSet<>();
            for (Map.Entry<String, String> e : theirs.entrySet()) {
                if (!e.getValue().equals(mine.get(e.getKey()))) {
                    differ.add(e.getKey());
                }
            }
            List<CompletableFuture<List<Op>>> pulls = new ArrayList<>();
            for (String db : differ) {
                pulls.add(client.state(p.address, db));
            }
            List<CompletableFuture<Integer>> merged = new ArrayList<>();
            for (CompletableFuture<List<Op>> f : pulls) {
                merged.add(f.thenApply(this::mergeOrThrow));
            }
            return sum(merged);
        });
    }

    /** Ops received by sync pulls (not by pushed writes), since start. */
    private final java.util.concurrent.atomic.AtomicLong pulledOps = new java.util.concurrent.atomic.AtomicLong();

    public long pulledOps() {
        return pulledOps.get();
    }

    /**
     * Changes stamped further than this ahead of the local clock are refused. Versions come from a hybrid
     * logical clock, so a node whose wall clock runs ahead would otherwise win every conflict, and every
     * node that merged its changes would carry its clock forward too.
     */
    static final long MAX_CLOCK_AHEAD_MILLIS = 60_000;

    private Op firstTooFarAhead(List<Op> ops) {
        long limit = clock.getAsLong() + MAX_CLOCK_AHEAD_MILLIS;
        for (Op op : ops) {
            if (op.stamp().wall() > limit) {
                return op;
            }
        }
        return null;
    }

    private int mergeOrThrow(List<Op> ops) {
        pulledOps.addAndGet(ops.size());
        Op ahead = firstTooFarAhead(ops);
        if (ahead != null) {
            List<Op> ok = new ArrayList<>(ops.size());
            long limit = clock.getAsLong() + MAX_CLOCK_AHEAD_MILLIS;
            for (Op op : ops) {
                if (op.stamp().wall() <= limit) {
                    ok.add(op);
                }
            }
            LOG.warning("skipped " + (ops.size() - ok.size()) + " changes stamped more than "
                    + MAX_CLOCK_AHEAD_MILLIS / 1000 + " s ahead of this node's clock, from node "
                    + ahead.stamp().node() + "; check that node's clock");
            ops = ok;
        }
        try {
            return engine.merge(ops);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static CompletableFuture<Integer> sum(List<CompletableFuture<Integer>> parts) {
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new))
                .thenApply(v -> parts.stream().mapToInt(CompletableFuture::join).sum());
    }

    // ---- discovery ----

    private void discover() {
        if (closed || peers.size() >= size - 1) {
            return;
        }
        Set<String> local = localAddresses();
        Set<String> known = new HashSet<>();
        for (Peer p : peers) {
            known.add(p.address);
        }
        List<CompletableFuture<Void>> probes = new ArrayList<>();
        for (InetAddress a : discovery.addresses()) {
            String host = a.getHostAddress();
            String addr = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
            if (local.contains(host) || known.contains(addr) || ignored.contains(addr)) {
                continue;
            }
            probes.add(client.hello(addr).thenAccept(h -> {
                if (h.node().equals(nodeId)) {
                    ignored.add(addr);
                    return;
                }
                if (h.clusterSize() != size) {
                    LOG.warning("ignoring " + addr + ": it expects a cluster of " + h.clusterSize() + ", not " + size);
                    ignored.add(addr);
                    return;
                }
                synchronized (peers) {
                    if (peers.size() < size - 1 && peers.stream().noneMatch(p -> p.address.equals(addr))) {
                        Peer p = new Peer(addr);
                        p.node = h.node();
                        peers.add(p);
                        LOG.info("discovered peer " + addr + " (node " + h.node() + ")");
                    }
                }
            }).exceptionally(err -> null));
        }
        CompletableFuture.allOf(probes.toArray(CompletableFuture[]::new)).join();
    }

    private static Set<String> localAddresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    out.add(a.getHostAddress().replaceFirst("%.*$", ""));
                }
            }
        } catch (SocketException e) {
            LOG.log(Level.WARNING, "cannot list local addresses", e);
        }
        return out;
    }

    // ---- /internal/ ----

    @Override
    public boolean authorized(String key) {
        return key != null && MessageDigest.isEqual(keyDigest, sha256(key));
    }

    @Override
    public Object handle(String method, String path, Map<String, String> query, Map<String, Object> body)
            throws QueryService.Rejected {
        if (engine.failed()) {
            // Its memory may hold writes the disk does not: peers must not copy them.
            throw new QueryService.Rejected(503, "this node's write-ahead log failed to fsync; it serves nothing until restarted");
        }
        if (stale && !path.equals("/internal/hello")) {
            // Its rows may include ones the cluster deleted and has since forgotten the tombstones of.
            throw new QueryService.Rejected(503, "this node is out of date: it must be emptied and resynced");
        }
        switch (path) {
            case "/internal/hello" -> {
                return Map.of("node", nodeId, "cluster_size", size, "protocol", 2);
            }
            case "/internal/v2/roots" -> {
                return Map.of("node", nodeId, "dbs", engine.read(Store::rootHashes));
            }
            case "/internal/v2/schema" -> {
                String db = required(query, "db");
                List<Op> ops = engine.read(s -> s.schemaOps(db));
                return Map.of("ops", Op.toJson(ops));
            }
            case "/internal/v2/buckets" -> {
                String db = required(query, "db");
                String table = required(query, "table");
                long[] sums = engine.read(s -> s.bucketSums(db, table));
                List<Object> out = new ArrayList<>(sums.length);
                for (long v : sums) {
                    out.add(v);
                }
                return Map.of("sums", out);
            }
            case "/internal/v2/rows" -> {
                String db = required(query, "db");
                String table = required(query, "table");
                Set<Integer> wanted = new HashSet<>();
                try {
                    for (String part : required(query, "buckets").split(",")) {
                        int b = Integer.parseInt(part.trim());
                        if (b < 0 || b >= Store.BUCKETS) {
                            throw new NumberFormatException();
                        }
                        wanted.add(b);
                    }
                } catch (NumberFormatException e) {
                    throw new QueryService.Rejected(400, "buckets must be numbers from 0 to " + (Store.BUCKETS - 1));
                }
                if (wanted.size() > BUCKETS_PER_REQUEST) {
                    throw new QueryService.Rejected(400, "at most " + BUCKETS_PER_REQUEST + " buckets per request");
                }
                // Schema ops travel with the rows, so the receiver can place rows whose table it has not seen.
                List<Op> ops = engine.read(s -> {
                    List<Op> o = new ArrayList<>(s.schemaOps(db));
                    o.addAll(s.bucketOps(db, table, wanted));
                    return o;
                });
                return Map.of("ops", Op.toJson(ops));
            }
            case "/internal/digests" -> {
                return Map.of("node", nodeId, "digests", engine.read(Store::digests));
            }
            case "/internal/state" -> {
                String db = query.get("db");
                if (db == null || db.isEmpty()) {
                    throw new QueryService.Rejected(400, "db is required");
                }
                // A 2.0 route, so only ops a 2.0 node can read (no index ops).
                List<Op> ops = engine.read(s -> s.legacyOps(db));
                return Map.of("ops", Op.toJson(ops));
            }
            case "/internal/apply" -> {
                if (!"POST".equals(method)) {
                    throw new QueryService.Rejected(405, "use POST");
                }
                List<Op> ops;
                try {
                    ops = Op.listFromJson(body.get("ops"));
                } catch (IllegalArgumentException e) {
                    throw new QueryService.Rejected(400, "bad ops: " + e.getMessage());
                }
                Op ahead = firstTooFarAhead(ops);
                if (ahead != null) {
                    throw new QueryService.Rejected(409, "refused: a change is stamped " + (ahead.stamp().wall()
                            - clock.getAsLong()) / 1000 + " s ahead of this node's clock (limit "
                            + MAX_CLOCK_AHEAD_MILLIS / 1000 + " s); check the sender's clock");
                }
                try {
                    return Map.of("node", nodeId, "applied", engine.merge(ops));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            default -> throw new QueryService.Rejected(404, "no such route");
        }
    }

    private static String required(Map<String, String> query, String name) throws QueryService.Rejected {
        String v = query.get(name);
        if (v == null || v.isEmpty()) {
            throw new QueryService.Rejected(400, name + " is required");
        }
        return v;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdownNow();
        io.shutdownNow();
    }
}
