package com.ataiva.serengeti.core.cluster;

import com.ataiva.serengeti.core.config.Cidr;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.server.InternalRoutes;
import com.ataiva.serengeti.core.server.QueryService;
import com.ataiva.serengeti.core.server.Users;
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
    /** Accounts are compared with each peer every this many sync rounds. */
    static final int ACCOUNT_ROUNDS = 5;

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
        /** The peer has no routes for rows by id (2.1, or 2.2 before them); asked again after PROBE_MILLIS. */
        volatile boolean noRowRoutes;
        volatile long noRowRoutesSince;
        /** Since when the peer's expiry has differed from this node's (0: it agrees), and whether that was logged. */
        volatile long expiryDiffersSince;
        volatile boolean expiryWarned;
        /** The peer has no account routes (older than 2.2); asked again after PROBE_MILLIS. */
        volatile boolean noAccounts;
        volatile long noAccountsSince;
        volatile CompletableFuture<Void> accountsInFlight;
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

    /**
     * A node away from a majority for longer than the TTL less this is stale. A delete it missed was made after
     * it left, so it expires on its peers no sooner than this long after the node may come back: time to pull
     * it before the peers leave it out of their sums and hashes (review 22e, suspicion 1).
     */
    static final long STALE_MARGIN_MILLIS = 2 * Store.EXPIRY_STEP_MILLIS;

    /** The longest this node may go without syncing with a majority and still sync: the TTL less the margin. */
    private long awayLimit() {
        return tombstoneTtl - STALE_MARGIN_MILLIS;
    }

    Cluster(Config config, String nodeId, StorageEngine engine, SSLContext clientTls, LongSupplier clock) {
        this.tombstoneTtl = config.tombstoneTtlMillis;
        this.localAckByDefault = config.localWriteAck;
        this.lastSyncFile = config.dataDir.resolve(LAST_SYNC_FILE);
        long last = readLastSync(lastSyncFile);
        if (last > 0 && clock.getAsLong() - last > awayLimit()) {
            // It starts, but stale: no sync and no writes, so its old rows cannot bring back deleted ones. It
            // still hands the changes it made after `last` to its peers (pushIsolatedWrites).
            this.stale = true;
            LOG.severe("this node last synced with a majority of its cluster at " + java.time.Instant.ofEpochMilli(last)
                    + ", longer ago than tombstone_ttl_hours (" + tombstoneTtl / 3_600_000 + ") less 20 minutes. Rows deleted since then"
                    + " could come back through it, so it does not sync or take writes. It hands the changes it made"
                    + " since then to its peers; then stop it, empty its data directory except accounts.json, users.json and node-id,"
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
                    + "tombstone_ttl_hours less 20 minutes and must be emptied and resynced");
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

    /** A schema pull for one database (see pullSchema): when it began, and whether it merged anything. */
    private record SchemaPull(long at, CompletableFuture<Boolean> merged) {}

    /** The latest schema pull of each database a statement named that was not here. */
    private final Map<String, SchemaPull> schemaPulls = new java.util.concurrent.ConcurrentHashMap<>();

    /** A statement waits at most this long for the peers' schema of a database it names that is not here. */
    static final long SCHEMA_PULL_MILLIS = 1_000;

    /**
     * Pulls the schema of {@code db} (its creates and drops of tables and indexes) from every peer, and merges
     * it, waiting up to SCHEMA_PULL_MILLIS. For a statement naming a database or table this node has not got
     * yet: a CREATE DATABASE acknowledged by a majority reaches the other nodes a moment later, and a client
     * behind a load balancer whose next statement reached one of those got "does not exist" (seen in
     * examples/kubernetes). A database is pulled at most once a second, so names that exist nowhere cost the
     * peers little; statements that name it meanwhile wait for that pull and get its answer. Returns true if
     * anything merged.
     */
    public boolean pullSchema(String db) {
        if (closed || peers.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (schemaPulls.size() > 10_000) {
            schemaPulls.clear();
        }
        SchemaPull fresh = new SchemaPull(now, new CompletableFuture<>());
        SchemaPull pull = schemaPulls.compute(db,
                (k, old) -> old != null && now - old.at() < SCHEMA_PULL_MILLIS ? old : fresh);
        if (pull == fresh) {
            List<CompletableFuture<Integer>> runs = new ArrayList<>();
            for (Peer p : peers) {
                runs.add(client.schema(p.address, db).thenApply(this::mergeOrThrow).exceptionally(e -> 0)
                        .completeOnTimeout(0, SCHEMA_PULL_MILLIS, TimeUnit.MILLISECONDS));
            }
            CompletableFuture.allOf(runs.toArray(new CompletableFuture<?>[0])).whenComplete((v, e) ->
                    fresh.merged().complete(runs.stream().mapToInt(f -> f.getNow(0)).sum() > 0));
        }
        try {
            return pull.merged().get(SCHEMA_PULL_MILLIS + 100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

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
        // Set once the read has its answer or gives up: its syncs then stop at their next page. Left running,
        // every majority read refused during a long catch-up went on pulling what differed when it began, long
        // after the node had caught up (review 22d).
        java.util.concurrent.atomic.AtomicBoolean readDone = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            List<CompletableFuture<Boolean>> runs = new ArrayList<>();
            for (Peer p : peers) {
                try {
                    runs.add(syncWith(p, readDone));
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
        } finally {
            readDone.set(true);
        }
    }

    // ---- sync ----

    /**
     * For tests: while set, sync rounds of every node in this JVM reach their peers (so they count as
     * reachable) but pull nothing from them; pullSchema and replication of new writes still run.
     */
    static volatile boolean syncPausedForTest;

    /** For tests: applies ops to this node's storage only, as if they had come from a peer, sent to nobody. */
    void applyHereOnlyForTest(List<Op> ops) throws IOException {
        engine.apply(ops);
    }

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
                    p.inFlight = syncWith(p, null).completeOnTimeout(false,
                            PeerClient.STATE_TIMEOUT.toMillis() + 5_000, TimeUnit.MILLISECONDS);
                } else if (clock.getAsLong() - p.lastContact > SYNC_INTERVAL_MILLIS
                        && (p.probe == null || p.probe.isDone())) {
                    // A sync that pulls many pages, or waits for its turn to pull, must not make a peer that
                    // answers look unreachable: writes need a majority reachable.
                    p.probe = client.hello(p.address).thenAccept(h -> p.lastContact = clock.getAsLong())
                            .exceptionally(err -> null);
                }
            }
            if (++rounds % ACCOUNT_ROUNDS == 0) {
                for (Peer p : peers) {
                    CompletableFuture<Void> f = p.accountsInFlight;
                    if (f == null || f.isDone()) {
                        p.accountsInFlight = syncAccounts(p).completeOnTimeout(null,
                                PeerClient.CALL_TIMEOUT.toMillis() * 3, TimeUnit.MILLISECONDS);
                    }
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
            } else if (!stale && clock.getAsLong() - lastMajoritySync > awayLimit()) {
                stale = true;
                LOG.severe("no sync with a majority for longer than tombstone_ttl_hours less 20 minutes; this node now refuses "
                        + "writes and syncing. Stop it, empty its data directory except accounts.json, users.json and node-id, "
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

    private long rounds;
    private volatile Users accounts;

    /**
     * Replicates accounts: changes made here are pushed to the peers at once, and every ACCOUNT_ROUNDS sync
     * rounds each peer's account digest is compared and, if it differs, its accounts are merged (the newer
     * change to a name wins; see Users.merge). Peers older than 2.2 have no account routes and are skipped.
     */
    public void setAccounts(Users users) {
        this.accounts = users;
        users.setListener(changed -> {
            for (Peer p : List.copyOf(peers)) {
                if (!p.protocol1 && !p.noAccounts) {
                    client.pushAccounts(p.address, changed).exceptionally(err -> null);
                }
            }
        });
    }

    private CompletableFuture<Void> syncAccounts(Peer p) {
        Users users = accounts;
        // Also while stale: accounts are never collected, so the reason rows wait does not apply to them.
        if (users == null || p.protocol1
                || p.noAccounts && clock.getAsLong() - p.noAccountsSince < PROBE_MILLIS) {
            return CompletableFuture.completedFuture(null);
        }
        return client.accountDigest(p.address).thenCompose(theirs -> {
            p.noAccounts = false;
            if (theirs.equals(users.digest())) {
                accountSyncs.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
            return client.accounts(p.address).thenAccept(list -> {
                try {
                    Users.Merged m = users.mergeFrom(list);
                    if (m.changed() > 0) {
                        LOG.info("merged " + m.changed() + " account changes from " + p.address);
                    }
                    warnAhead(m, p.address);
                    accountSyncs.incrementAndGet();
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
        }).exceptionally(err -> {
            Throwable cause = unwrap(err);
            if (cause instanceof PeerClient.NotSupported) {
                if (!p.noAccounts) {
                    LOG.info("peer " + p.address + " runs a version without account replication (before 2.2)");
                }
                p.noAccounts = true;
                p.noAccountsSince = clock.getAsLong();
            } else if (cause instanceof IllegalArgumentException) {
                LOG.warning("refused the accounts from " + p.address + ": " + cause.getMessage());
            } else {
                LOG.fine("account sync with " + p.address + ": " + PeerClient.describe(err));
            }
            return null;
        });
    }

    private static void warnAhead(Users.Merged m, String from) {
        if (m.ahead() > 0) {
            LOG.warning("left out " + m.ahead() + " account changes stamped more than "
                    + Users.MAX_AHEAD_MILLIS / 1000 + " s ahead of this node's clock, from " + from
                    + "; they are taken once the clocks agree");
        }
    }

    private final java.util.concurrent.atomic.AtomicLong accountSyncs = new java.util.concurrent.atomic.AtomicLong();

    /** How many times accounts were pulled from, or found equal to, a peer's. */
    public long accountSyncs() {
        return accountSyncs.get();
    }

    /** Pulls every database whose digest differs from the peer's. Completes with true if it worked. */
    private CompletableFuture<Boolean> syncWith(Peer p, java.util.concurrent.atomic.AtomicBoolean readDone) {
        if (stale) {
            return pushIsolatedWrites(p);
        }
        // A peer found running 2.0 is asked for the bucket routes again now and then: it may have been upgraded.
        boolean byBuckets = !p.protocol1 || clock.getAsLong() - p.protocol1Since > PROBE_MILLIS;
        CompletableFuture<Integer> run = !byBuckets ? syncWholeDatabases(p, readDone) : syncByBuckets(p, readDone).handle((n, err) -> {
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
        }).thenCompose(n -> n == null ? syncWholeDatabases(p, readDone) : CompletableFuture.completedFuture(n));
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

    /** A sync whose pages stopped: a newer background sync with the peer started, or its read is over. It fails. */
    static final class Superseded extends RuntimeException {
        Superseded() {
            super("a newer sync with the peer took over", null, false, false);
        }
    }

    /**
     * Pulls the rows of the differing buckets from {@code from} on, one page at a time and through
     * {@code limiter}, sizing each page from the last: a table of large rows gets pages of few buckets. A
     * sync stops with {@link Superseded} once {@code stop} says so: a background one when a newer one with the
     * peer has started (which pulls what is still missing), a read's once the read has its answer or gave up.
     * A stopped sync must not count as a completed one.
     */
    private CompletableFuture<Integer> pullBuckets(Peer p, AsyncLimiter limiter, java.util.function.BooleanSupplier stop, String db,
            String table, List<Integer> differ, int from, int buckets, int merged) {
        if (from >= differ.size()) {
            return CompletableFuture.completedFuture(merged);
        }
        RuntimeException why = stopReason(stop);
        if (why != null) {
            return CompletableFuture.failedFuture(why);
        }
        List<Integer> page = List.copyOf(differ.subList(from, Math.min(differ.size(), from + buckets)));
        return limiter.run(() -> unlessStopped(stop, () -> client.rows(p.address, db, table, page))).thenCompose(ops -> {
            p.lastContact = clock.getAsLong();
            bucketPages.incrementAndGet();
            int n = mergeOrThrow(ops);
            int next = nextPullBuckets(page.size(), ops.size(), com.ataiva.serengeti.core.storage.Weight.ofOps(ops));
            return pullBuckets(p, limiter, stop, db, table, differ, from + page.size(), next, merged + n);
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

    /** For tests: pulls running and waiting. */
    int[] pullsForTest() {
        return new int[] {pullLimiter.running(), pullLimiter.waiting()};
    }

    /**
     * The call, unless the sync is over by the time the limiter starts it: a page can wait in the limiter's
     * queue long after it was asked for, and a read that has given up must not still pull it.
     */
    private <T> CompletableFuture<T> unlessStopped(java.util.function.BooleanSupplier stop,
            java.util.function.Supplier<CompletableFuture<T>> call) {
        RuntimeException why = stopReason(stop);
        return why != null ? CompletableFuture.failedFuture(why) : call.get();
    }

    /**
     * Why the next page of a sync must not start, or null if it may: the node is stopping, or {@code stop}
     * says the sync is over (a newer background one with the peer started, or its majority read is done).
     */
    private RuntimeException stopReason(java.util.function.BooleanSupplier stop) {
        if (closed) {
            return new UnavailableException("this node is stopping");
        }
        if (stop.getAsBoolean()) {
            return new Superseded();
        }
        return null;
    }

    // ---- protocol 3: rows by id ----

    /** Most ids in one POST /internal/v3/rows; the route refuses more. */
    static final int IDS_PER_REQUEST = 2_000;
    /** Most characters of ids in one such request, so that its body stays well under the internal limit. */
    static final long IDS_REQUEST_CHARS = 1L << 20;
    /** The first page of ids pulled from a page of hashes asks for this many; the next follow the last. */
    static final int FIRST_PULL_IDS = 500;
    /** A page of row hashes aims at about this many rows, and at about {@link #PULL_TARGET_BYTES}. */
    static final int HASH_TARGET_IDS = 20_000;

    /**
     * What one table's pull from one peer works with. {@code nextIds} holds the size of the next page of ids
     * when the peer does not send row sizes, carried from one page of hashes to the next (review 22d, RS1).
     */
    private record TablePull(Peer peer, AsyncLimiter limiter, java.util.function.BooleanSupplier stop, String db,
            String table, int[] nextIds) {}

    /** The buckets of a table that differ, and the peer's rows and bytes per bucket (null if it sent none). */
    private record Differ(List<Integer> buckets, long[] peerRows, long[] peerBytes) {}

    private final java.util.concurrent.atomic.AtomicLong bucketPages = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong hashPages = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong idPages = new java.util.concurrent.atomic.AtomicLong();

    /** For tests: pages of whole buckets, of row hashes and of rows by id this node has pulled. */
    long[] pagesForTest() {
        return new long[] {bucketPages.get(), hashPages.get(), idPages.get()};
    }

    /** For tests: answer 404 to the protocol 3 routes, as 2.1 and 2.2 builds before them do. */
    private volatile boolean rowRoutesOff;

    void rowRoutesForTest(boolean on) {
        rowRoutesOff = !on;
    }

    private volatile long rowProbeMillis = PROBE_MILLIS;

    void rowProbeMillisForTest(long millis) {
        rowProbeMillis = millis;
    }

    /** A peer found without the protocol 3 routes is asked again after PROBE_MILLIS: it may have been upgraded. */
    private boolean tryRowRoutes(Peer p) {
        return !p.noRowRoutes || clock.getAsLong() - p.noRowRoutesSince > rowProbeMillis;
    }

    private void noRowRoutes(Peer p) {
        if (!p.noRowRoutes) {
            LOG.info("peer " + p.address + " cannot send rows by id (it runs 2.1, or an earlier 2.2); pulling whole buckets from it");
        }
        p.noRowRoutes = true;
        p.noRowRoutesSince = clock.getAsLong();
    }

    private void rowRoutesFound(Peer p) {
        if (p.noRowRoutes) {
            LOG.info("peer " + p.address + " now sends rows by id");
            p.noRowRoutes = false;
        }
    }

    /**
     * Pulls what differs in the buckets of a table. From a peer with the protocol 3 routes, the buckets this
     * node holds rows in are compared row by row, a page of row hashes at a time, and only the rows that
     * differ or that only the peer has are pulled; the buckets this node holds no row in are pulled whole,
     * since every row of them is missing here, in pages that the peer's rows and bytes per bucket put at
     * about PULL_TARGET_OPS rows and PULL_TARGET_BYTES. A bucket too big for one page is compared and pulled by
     * id like the others, so no page holds more than about that. From an older peer every bucket is pulled
     * whole, the first page sized blind.
     */
    private CompletableFuture<Integer> pullTable(TablePull t, Differ d) {
        List<Integer> differ = d.buckets();
        if (!tryRowRoutes(t.peer())) {
            return pullBuckets(t.peer(), t.limiter(), t.stop(), t.db(), t.table(), differ, 0, FIRST_PULL_BUCKETS, 0);
        }
        int[] counts = engine.read(s -> s.bucketRowCounts(t.db(), t.table()));
        int[] expected = counts.clone(); // rows a page of hashes will hold per bucket: here or there, the more
        List<Integer> empty = new ArrayList<>();
        List<Integer> held = new ArrayList<>();
        for (int b : differ) {
            boolean sized = d.peerRows() != null && d.peerBytes() != null;
            if (sized) {
                expected[b] = (int) Math.max(counts[b], Math.min(Integer.MAX_VALUE, d.peerRows()[b]));
            }
            boolean whole = counts[b] == 0 && (!sized || d.peerRows()[b] <= PULL_TARGET_OPS && d.peerBytes()[b] <= PULL_TARGET_BYTES);
            (whole ? empty : held).add(b);
        }
        CompletableFuture<Integer> wholeBuckets = d.peerRows() == null || d.peerBytes() == null
                ? pullBuckets(t.peer(), t.limiter(), t.stop(), t.db(), t.table(), empty, 0, FIRST_PULL_BUCKETS, 0)
                : pullBucketPages(t, bucketPages(empty, d.peerRows(), d.peerBytes()), 0, 0);
        return wholeBuckets.thenCompose(n -> compareBuckets(t, held, expected, 0, FIRST_PULL_BUCKETS, 0, HASH_TARGET_IDS, n));
    }

    /**
     * {@code buckets} grouped into pages of whole buckets, by the peer's rows and bytes per bucket: about
     * PULL_TARGET_OPS rows and PULL_TARGET_BYTES a page, at most BUCKETS_PER_REQUEST buckets, at least one.
     */
    static List<List<Integer>> bucketPages(List<Integer> buckets, long[] rows, long[] bytes) {
        List<List<Integer>> pages = new ArrayList<>();
        List<Integer> page = new ArrayList<>();
        long r = 0;
        long w = 0;
        for (int b : buckets) {
            if (!page.isEmpty() && (r + rows[b] > PULL_TARGET_OPS || w + bytes[b] > PULL_TARGET_BYTES
                    || page.size() == BUCKETS_PER_REQUEST)) {
                pages.add(List.copyOf(page));
                page.clear();
                r = 0;
                w = 0;
            }
            page.add(b);
            r += rows[b];
            w += bytes[b];
        }
        if (!page.isEmpty()) {
            pages.add(List.copyOf(page));
        }
        return pages;
    }

    /** Pulls whole buckets, page {@code i} of {@code pages} on, one page after another. */
    private CompletableFuture<Integer> pullBucketPages(TablePull t, List<List<Integer>> pages, int i, int merged) {
        if (i >= pages.size()) {
            return CompletableFuture.completedFuture(merged);
        }
        RuntimeException why = stopReason(t.stop());
        if (why != null) {
            return CompletableFuture.failedFuture(why);
        }
        List<Integer> page = pages.get(i);
        return t.limiter().run(() -> unlessStopped(t.stop(), () -> client.rows(t.peer().address, t.db(), t.table(), page)))
                .thenCompose(ops -> {
                    t.peer().lastContact = clock.getAsLong();
                    bucketPages.incrementAndGet();
                    return pullBucketPages(t, pages, i + 1, merged + mergeOrThrow(ops));
                });
    }

    /**
     * Compares the buckets {@code held} (from {@code from} on) with the peer's, one page of row hashes at a
     * time, pulling the rows that differ before the next page. A page is as many buckets as come to about
     * {@code targetIds} rows, counting for each bucket this node's rows or the peer's average in the last
     * page, whichever is more; at most {@code maxBuckets}. A peer without the routes gets the rest pulled
     * whole, as before 2.2.
     */
    private CompletableFuture<Integer> compareBuckets(TablePull t, List<Integer> held, int[] counts, int from,
            int maxBuckets, double peerPerBucket, long targetIds, int merged) {
        if (from >= held.size()) {
            return CompletableFuture.completedFuture(merged);
        }
        RuntimeException stop = stopReason(t.stop());
        if (stop != null) {
            return CompletableFuture.failedFuture(stop);
        }
        Peer p = t.peer();
        int n = hashPageBuckets(held, from, counts, peerPerBucket, targetIds, maxBuckets);
        List<Integer> page = List.copyOf(held.subList(from, from + n));
        return t.limiter().run(() -> unlessStopped(t.stop(), () -> client.hashes(p.address, t.db(), t.table(), page))).handle((theirs, err) -> {
            if (err != null && !(unwrap(err) instanceof PeerClient.NotSupported)) {
                throw new java.util.concurrent.CompletionException(unwrap(err));
            }
            return theirs; // null: the peer has no such route
        }).thenCompose(theirs -> {
            if (theirs == null) {
                noRowRoutes(p);
                return pullBuckets(p, t.limiter(), t.stop(), t.db(), t.table(), held, from, FIRST_PULL_BUCKETS, merged);
            }
            p.lastContact = clock.getAsLong();
            rowRoutesFound(p);
            hashPages.incrementAndGet();
            Map<String, Long> mine = engine.read(s -> s.rowHashes(t.db(), t.table(), new HashSet<>(page)));
            List<Long> wantWeights = theirs.weights() == null ? null : new ArrayList<>();
            List<String> want = differingIds(theirs.ids(), theirs.hashes(), mine, theirs.weights(), wantWeights);
            int rows = theirs.ids().size();
            double perBucket = (double) rows / page.size();
            double perRowBytes = rows == 0 ? 1 : Math.max(1.0, (double) theirs.bytes() / rows);
            long nextTarget = (long) Math.max(1, Math.min(HASH_TARGET_IDS, PULL_TARGET_BYTES / perRowBytes));
            long[] weights = wantWeights == null ? null : wantWeights.stream().mapToLong(Long::longValue).toArray();
            return pullIds(t, want, weights, 0, t.nextIds()[0], 0).handle((k, err) -> {
                if (err != null && !(unwrap(err) instanceof PeerClient.NotSupported)) {
                    throw new java.util.concurrent.CompletionException(unwrap(err));
                }
                return err == null ? k : null;
            }).thenCompose(k -> {
                if (k == null) {
                    noRowRoutes(p);
                    return pullBuckets(p, t.limiter(), t.stop(), t.db(), t.table(), held, from, FIRST_PULL_BUCKETS, merged);
                }
                return compareBuckets(t, held, counts, from + n, n * PULL_GROWTH, perBucket, nextTarget, merged + k);
            });
        });
    }

    /** The buckets of the next page of row hashes (at least one): see {@link #compareBuckets}. */
    static int hashPageBuckets(List<Integer> held, int from, int[] counts, double peerPerBucket, long targetIds,
            int maxBuckets) {
        int limit = Math.min(Math.min(BUCKETS_PER_REQUEST, Math.max(1, maxBuckets)), held.size() - from);
        long perBucket = (long) Math.ceil(peerPerBucket);
        long rows = 0;
        int n = 0;
        while (n < limit) {
            long e = Math.max(counts[held.get(from + n)], perBucket);
            if (n > 0 && rows + e > targetIds) {
                break;
            }
            rows += e;
            n++;
        }
        return n;
    }

    /**
     * The ids to pull after comparing a page of the peer's row hashes with this node's: those whose hashes
     * differ (the peer's versions may be the older ones; merging them then changes nothing) and those only
     * the peer has. Ids only this node has need nothing: the peer pulls them from here.
     */
    static List<String> differingIds(List<String> theirIds, long[] theirHashes, Map<String, Long> mine) {
        return differingIds(theirIds, theirHashes, mine, null, null);
    }

    /** As above, adding the peer's size of each id returned to {@code weightsOut} when it sent sizes. */
    static List<String> differingIds(List<String> theirIds, long[] theirHashes, Map<String, Long> mine,
            long[] theirWeights, List<Long> weightsOut) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < theirIds.size(); i++) {
            Long h = mine.get(theirIds.get(i));
            if (h == null || h.longValue() != theirHashes[i]) {
                out.add(theirIds.get(i));
                if (theirWeights != null && weightsOut != null) {
                    weightsOut.add(theirWeights[i]);
                }
            }
        }
        return out;
    }

    /**
     * Pulls the rows {@code ids} (from {@code from} on) by id, one page after another. With the peer's row
     * sizes ({@code weights}), a page holds about PULL_TARGET_BYTES of rows; without, its size follows the
     * last page's, as bucket pages do, and is carried on to the next page of hashes.
     */
    private CompletableFuture<Integer> pullIds(TablePull t, List<String> ids, long[] weights, int from, int max, int merged) {
        if (from >= ids.size()) {
            return CompletableFuture.completedFuture(merged);
        }
        RuntimeException stop = stopReason(t.stop());
        if (stop != null) {
            return CompletableFuture.failedFuture(stop);
        }
        int n = weights != null ? idPageSizeByBytes(ids, weights, from) : idPageSize(ids, from, max);
        List<String> page = List.copyOf(ids.subList(from, from + n));
        return t.limiter().run(() -> unlessStopped(t.stop(), () -> client.rowsById(t.peer().address, t.db(), t.table(), page))).thenCompose(ops -> {
            t.peer().lastContact = clock.getAsLong();
            idPages.incrementAndGet();
            int k = mergeOrThrow(ops);
            int next = nextPullIds(page.size(), ops.size(), com.ataiva.serengeti.core.storage.Weight.ofOps(ops));
            t.nextIds()[0] = next;
            return pullIds(t, ids, weights, from + n, next, merged + k);
        });
    }

    /**
     * How many ids from {@code from} on go in one request when their sizes are known: as many as come to
     * PULL_TARGET_BYTES (at least one), within the request limits.
     */
    static int idPageSizeByBytes(List<String> ids, long[] weights, int from) {
        int limit = Math.min(IDS_PER_REQUEST, ids.size() - from);
        long chars = 0;
        long bytes = 0;
        int n = 0;
        while (n < limit) {
            chars += ids.get(from + n).length();
            bytes += weights[from + n];
            if (n > 0 && (chars > IDS_REQUEST_CHARS || bytes > PULL_TARGET_BYTES)) {
                break;
            }
            n++;
        }
        return n;
    }

    /** How many ids from {@code from} on go in one request: at most {@code max}, and within the request limits. */
    static int idPageSize(List<String> ids, int from, int max) {
        int limit = Math.min(Math.min(IDS_PER_REQUEST, Math.max(1, max)), ids.size() - from);
        long chars = 0;
        int n = 0;
        while (n < limit) {
            chars += ids.get(from + n).length();
            if (n > 0 && chars > IDS_REQUEST_CHARS) {
                break;
            }
            n++;
        }
        return n;
    }

    /** The ids of the next page, from what the last one asked for and held: as for buckets, per id. */
    static int nextPullIds(int asked, int ops, long bytes) {
        double perIdOps = Math.max(1.0, (double) ops / asked);
        double perIdBytes = Math.max(1.0, (double) bytes / asked);
        double fit = Math.min(PULL_TARGET_OPS / perIdOps, PULL_TARGET_BYTES / perIdBytes);
        long next = (long) Math.floor(fit);
        return (int) Math.max(1, Math.min(Math.min(IDS_PER_REQUEST, (long) asked * PULL_GROWTH), next));
    }

    /**
     * Protocol 2: compare per-table roots, then bucket sums of the tables that differ, then pull what
     * differs in those buckets: the rows that differ, found by comparing row hashes with the peer (protocol
     * 3, {@link #pullTable}), or every row of the bucket from a peer without that. With a protocol 3 peer
     * the roots and sums compared count tombstones too (the full sums), so a newer delete reaches a node
     * that holds an older one; 2.1's count live rows only. In a steady cluster this moves only the root map;
     * under writes, only what the writes touched. Completes with the number of ops merged.
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<Integer> syncByBuckets(Peer p, java.util.concurrent.atomic.AtomicBoolean readDone) {
        long generation = p.generation;
        java.util.function.BooleanSupplier stop = readDone != null ? readDone::get : () -> p.generation != generation;
        AsyncLimiter limiter = readDone != null ? readPullLimiter : pullLimiter;
        return client.roots(p.address).thenCompose(roots -> {
            p.lastContact = clock.getAsLong(); // it answered; a long pull that follows must not age it out
            Map<String, Map<String, Object>> theirs = roots.dbs();
            Object[] local = engine.read(s -> new Object[] {s.rootHashes(), s.expiry()});
            Map<String, Map<String, Object>> mine = (Map<String, Map<String, Object>>) local[0];
            boolean sameExpiry = sameExpiry(p, roots.expiry(), (Long) local[1]);
            List<CompletableFuture<Integer>> work = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> e : theirs.entrySet()) {
                String db = e.getKey();
                if (syncPausedForTest) {
                    break; // the peer counts as reached; nothing of its data or schema is pulled
                }
                Map<String, Object> m = mine.get(db);
                long mySchema = m == null ? 0 : ((Number) m.get("schema")).longValue();
                long theirSchema = ((Number) e.getValue().get("schema")).longValue();
                CompletableFuture<Integer> schemaStep = mySchema == theirSchema
                        ? CompletableFuture.completedFuture(0)
                        : client.schema(p.address, db).thenApply(this::mergeOrThrow);
                // A peer with the full sums (tombstones counted) and this node's expiry is compared by those;
                // 2.1 sends only "tables", and on another expiry the full sums differ in every expired tombstone.
                Map<String, Object> theirFull = (Map<String, Object>) e.getValue().get("full");
                boolean full = theirFull != null && sameExpiry && tryRowRoutes(p);
                Map<String, Object> theirTables = full ? theirFull : (Map<String, Object>) e.getValue().get("tables");
                Map<String, Object> myTables = m == null ? Map.of() : (Map<String, Object>) m.get(full ? "full" : "tables");
                for (Map.Entry<String, Object> t : theirTables.entrySet()) {
                    long theirRoot = ((Number) t.getValue()).longValue();
                    Object mineRoot = myTables.get(t.getKey());
                    long myRoot = mineRoot == null ? 0 : ((Number) mineRoot).longValue();
                    if (theirRoot == myRoot) {
                        continue;
                    }
                    String table = t.getKey();
                    // Schema first, so a drop that removes rows is applied before rows are compared.
                    work.add(schemaStep.thenCompose(x -> differingBuckets(p, db, table, full)).thenCompose(differ ->
                            pullTable(new TablePull(p, limiter, stop, db, table, new int[] {FIRST_PULL_IDS}), differ)));
                }
                work.add(schemaStep);
            }
            return sum(work);
        });
    }

    /**
     * The buckets of a table whose sums differ from the peer's: the full sums (tombstones counted, protocol 3)
     * if {@code full}, else the live ones, which is all 2.1 has. A peer that answers 404 to the full sums is
     * treated as one without the protocol 3 routes.
     */
    private CompletableFuture<Differ> differingBuckets(Peer p, String db, String table, boolean full) {
        return differingBuckets(p, db, table, full, null);
    }

    /**
     * As above, with the peer's rows and bytes per bucket already known ({@code sizes}, from its full sums), or
     * null. The live sums carry no sizes, and without them the buckets this node holds no row in were pulled
     * whole with a first page of 16 sized blind: 12.8 MB in one answer when a sync met an expiry boundary, and
     * every round between nodes with different tombstone_ttl_hours. So with the live sums, the sizes come from
     * the full sums too (a test failed once on this under load).
     */
    private CompletableFuture<Differ> differingBuckets(Peer p, String db, String table, boolean full,
            PeerClient.FullSums sizes) {
        CompletableFuture<PeerClient.FullSums> theirs = !full
                ? client.buckets(p.address, db, table).thenApply(s -> new PeerClient.FullSums(s, null, null, null))
                : fullSums(p, db, table);
        CompletableFuture<PeerClient.FullSums> sized = full || sizes != null || !tryRowRoutes(p)
                ? CompletableFuture.completedFuture(sizes)
                : fullSums(p, db, table).exceptionally(err -> null); // sizes only: without them, as before
        return theirs.thenCombine(sized, (their, known) -> new PeerClient.FullSums[] {their, known}).thenCompose(both -> {
            PeerClient.FullSums their = both[0];
            if (their == null) {
                noRowRoutes(p);
                return differingBuckets(p, db, table, false, null);
            }
            PeerClient.FullSums withSizes = full ? their : both[1];
            long[] sums = their.sums();
            Object[] mine = engine.read(s -> new Object[] {full ? s.fullBucketSums(db, table) : s.bucketSums(db, table),
                s.expiry()});
            if (full && !java.util.Objects.equals(their.expiry(), mine[1])) {
                // One of the two crossed a boundary since the roots: compare the live sums this time.
                return differingBuckets(p, db, table, false, their);
            }
            long[] local = (long[]) mine[0];
            List<Integer> differ = new ArrayList<>();
            for (int i = 0; i < sums.length; i++) {
                if (sums[i] != local[i]) {
                    differ.add(i);
                }
            }
            return CompletableFuture.completedFuture(new Differ(differ, withSizes == null ? null : withSizes.rows(),
                    withSizes == null ? null : withSizes.bytes()));
        });
    }

    /** The peer's full sums, or null from a peer without the route (2.1). */
    private CompletableFuture<PeerClient.FullSums> fullSums(Peer p, String db, String table) {
        return client.fullBuckets(p.address, db, table).handle((sums, err) -> {
            if (err != null && !(unwrap(err) instanceof PeerClient.NotSupported)) {
                throw new java.util.concurrent.CompletionException(unwrap(err));
            }
            return sums;
        });
    }

    /**
     * True if the peer counts its full sums at this node's expiry. Nodes cross each 10-minute boundary a few
     * seconds apart, and nodes with different tombstone_ttl_hours never agree; until they do, the pair
     * compares the live sums, so expired tombstones that one leaves out and the other counts are not pulled
     * every round (review 22e, findings 2 and 5). A difference that outlasts a step is logged once.
     */
    private boolean sameExpiry(Peer p, Long theirs, Long mine) {
        if (theirs != null && theirs.equals(mine)) {
            p.expiryDiffersSince = 0;
            p.expiryWarned = false;
            return true;
        }
        long now = clock.getAsLong();
        if (p.expiryDiffersSince == 0) {
            p.expiryDiffersSince = now;
        } else if (theirs != null && !p.expiryWarned && now - p.expiryDiffersSince > Store.EXPIRY_STEP_MILLIS + 60_000) {
            p.expiryWarned = true;
            LOG.warning("peer " + p.address + " drops tombstones made before " + java.time.Instant.ofEpochMilli(theirs)
                    + ", this node those before " + java.time.Instant.ofEpochMilli(mine) + ": tombstone_ttl_hours differs"
                    + " between them. Set it the same on every node; until then they compare live rows only, and a newer"
                    + " delete may not reach a node that holds an older one.");
        }
        return false;
    }

    private final java.util.concurrent.atomic.AtomicLong readStatePulls = new java.util.concurrent.atomic.AtomicLong();

    /** For tests: whole-database pulls that majority reads have started. */
    long readStatePullsForTest() {
        return readStatePulls.get();
    }

    /**
     * Protocol 1 (2.0 peers): compare one SHA-256 per database and pull whole databases that differ. Each
     * pull checks the sync's stop when its turn comes, as row pages do: a read's pulls end with the read.
     */
    private CompletableFuture<Integer> syncWholeDatabases(Peer p, java.util.concurrent.atomic.AtomicBoolean readDone) {
        long generation = p.generation;
        java.util.function.BooleanSupplier stop = readDone != null ? readDone::get : () -> p.generation != generation;
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
                // Each answer is a whole database: through the same limit as the row pages (review 22d).
                pulls.add((readDone != null ? readPullLimiter : pullLimiter).run(() -> unlessStopped(stop, () -> {
                    if (readDone != null) {
                        readStatePulls.incrementAndGet();
                    }
                    return client.state(p.address, db);
                })));
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
        if (stale && !path.equals("/internal/hello") && !path.equals("/internal/v3/users")) {
            // Its rows may include ones the cluster deleted and has since forgotten the tombstones of.
            throw new QueryService.Rejected(503, "this node is out of date: it must be emptied and resynced");
        }
        switch (path) {
            case "/internal/hello" -> {
                return Map.of("node", nodeId, "cluster_size", size, "protocol", 2);
            }
            case "/internal/v3/users" -> {
                Users users = accounts;
                if (users == null) {
                    throw new QueryService.Rejected(404, "no such route");
                }
                if (method.equals("POST")) {
                    if (!(body.get("users") instanceof List<?> list)) {
                        throw new QueryService.Rejected(400, "users is required");
                    }
                    try {
                        Users.Merged m = users.mergeFrom(list);
                        warnAhead(m, "a peer's push");
                        return Map.of("node", nodeId, "merged", m.changed());
                    } catch (IllegalArgumentException e) {
                        throw new QueryService.Rejected(400, e.getMessage());
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                if ("1".equals(query.get("digest"))) {
                    return Map.of("node", nodeId, "digest", users.digest());
                }
                return Map.of("node", nodeId, "users", users.entries());
            }
            case "/internal/v2/roots" -> {
                // With the expiry the full roots were counted at: a peer compares them only on the same one.
                Object[] answer = engine.read(s -> new Object[] {s.rootHashes(), s.expiry()});
                return Map.of("node", nodeId, "dbs", answer[0], "expiry", answer[1]);
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
                Set<Integer> wanted = buckets(query);
                // Schema ops travel with the rows, so the receiver can place rows whose table it has not seen.
                List<Op> ops = engine.read(s -> {
                    List<Op> o = new ArrayList<>(s.schemaOps(db));
                    o.addAll(s.bucketOps(db, table, wanted));
                    return o;
                });
                return Map.of("ops", Op.toJson(ops));
            }
            case "/internal/v3/buckets" -> {
                if (rowRoutesOff) {
                    throw new QueryService.Rejected(404, "no such route");
                }
                String db = required(query, "db");
                String table = required(query, "table");
                // The rows and bytes per bucket too: the peer sizes its pulls of buckets it holds nothing of by them.
                Object[] answer = engine.read(s -> new Object[] {s.fullBucketSums(db, table), s.bucketRowCounts(db, table),
                    s.bucketBytes(db, table), s.expiry()});
                List<Object> sums = new ArrayList<>(Store.BUCKETS);
                List<Object> rows = new ArrayList<>(Store.BUCKETS);
                List<Object> bytes = new ArrayList<>(Store.BUCKETS);
                for (int i = 0; i < Store.BUCKETS; i++) {
                    sums.add(((long[]) answer[0])[i]);
                    rows.add((long) ((int[]) answer[1])[i]);
                    bytes.add(((long[]) answer[2])[i]);
                }
                return Map.of("sums", sums, "rows", rows, "bytes", bytes, "expiry", answer[3]);
            }
            case "/internal/v3/hashes" -> {
                if (rowRoutesOff) {
                    throw new QueryService.Rejected(404, "no such route");
                }
                String db = required(query, "db");
                String table = required(query, "table");
                Set<Integer> wanted = buckets(query);
                // With each row's estimated size ("w"), so the peer can page its pulls by bytes.
                Object[] answer = engine.read(s -> {
                    Map<String, Long> h = s.rowHashes(db, table, wanted);
                    return new Object[] {h, s.rowWeights(db, table, h.keySet())};
                });
                @SuppressWarnings("unchecked")
                Map<String, Long> hashes = (Map<String, Long>) answer[0];
                List<Object> weights = new ArrayList<>(hashes.size());
                for (long w : (long[]) answer[1]) {
                    weights.add(w);
                }
                return Map.of("ids", new ArrayList<Object>(hashes.keySet()), "h", new ArrayList<Object>(hashes.values()),
                        "w", weights);
            }
            case "/internal/v3/rows" -> {
                if (rowRoutesOff) {
                    throw new QueryService.Rejected(404, "no such route");
                }
                if (!"POST".equals(method)) {
                    throw new QueryService.Rejected(405, "use POST");
                }
                String db = requiredString(body, "db");
                String table = requiredString(body, "table");
                if (!(body.get("ids") instanceof List<?> list)) {
                    throw new QueryService.Rejected(400, "ids must be a list of row ids");
                }
                if (list.size() > IDS_PER_REQUEST) {
                    throw new QueryService.Rejected(400, "at most " + IDS_PER_REQUEST + " ids per request");
                }
                List<String> ids = new ArrayList<>(list.size());
                for (Object o : list) {
                    if (!(o instanceof String id)) {
                        throw new QueryService.Rejected(400, "ids must be a list of row ids");
                    }
                    ids.add(id);
                }
                // As for /internal/v2/rows: the schema ops with the rows.
                List<Op> ops = engine.read(s -> {
                    List<Op> o = new ArrayList<>(s.schemaOps(db));
                    o.addAll(s.rowOps(db, table, ids));
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

    private static String requiredString(Map<String, Object> body, String name) throws QueryService.Rejected {
        if (!(body.get(name) instanceof String v) || v.isEmpty()) {
            throw new QueryService.Rejected(400, name + " is required");
        }
        return v;
    }

    /** The {@code buckets} parameter: at most BUCKETS_PER_REQUEST bucket numbers, comma-separated. */
    private static Set<Integer> buckets(Map<String, String> query) throws QueryService.Rejected {
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
        return wanted;
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
