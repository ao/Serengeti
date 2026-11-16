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
        /** The peer has no account routes (older than 2.2); asked again after PROBE_MILLIS. */
        volatile boolean noAccounts;
        volatile long noAccountsSince;
        volatile CompletableFuture<Void> accountsInFlight;

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
        return batch.isEmpty() ? CompletableFuture.completedFuture(null) : client.apply(p.address, batch);
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
        int needed = quorum - 1;
        if (needed <= 0) {
            return;
        }
        List<CompletableFuture<Boolean>> runs = new ArrayList<>();
        for (Peer p : peers) {
            runs.add(syncWith(p));
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
                    p.inFlight = syncWith(p).completeOnTimeout(false,
                            PeerClient.STATE_TIMEOUT.toMillis() + 5_000, TimeUnit.MILLISECONDS);
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
            } else if (!stale && clock.getAsLong() - lastMajoritySync > tombstoneTtl) {
                stale = true;
                LOG.severe("no sync with a majority for longer than tombstone_ttl_hours; this node now refuses "
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
                return CompletableFuture.completedFuture(null);
            }
            return client.accounts(p.address).thenAccept(list -> {
                try {
                    int n = users.merge(list);
                    if (n > 0) {
                        LOG.info("merged " + n + " account changes from " + p.address);
                    }
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
        }).exceptionally(err -> {
            if (unwrap(err) instanceof PeerClient.NotSupported) {
                if (!p.noAccounts) {
                    LOG.info("peer " + p.address + " runs a version without account replication (before 2.2)");
                }
                p.noAccounts = true;
                p.noAccountsSince = clock.getAsLong();
            } else {
                LOG.fine("account sync with " + p.address + ": " + PeerClient.describe(err));
            }
            return null;
        });
    }

    /** Pulls every database whose digest differs from the peer's. Completes with true if it worked. */
    private CompletableFuture<Boolean> syncWith(Peer p) {
        if (stale) {
            return pushIsolatedWrites(p);
        }
        // A peer found running 2.0 is asked for the bucket routes again now and then: it may have been upgraded.
        boolean byBuckets = !p.protocol1 || clock.getAsLong() - p.protocol1Since > PROBE_MILLIS;
        CompletableFuture<Integer> run = !byBuckets ? syncWholeDatabases(p) : syncByBuckets(p).handle((n, err) -> {
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
     * Protocol 2: compare per-table roots, then bucket sums of the tables that differ, then pull the rows
     * of the buckets that differ. In a steady cluster this moves only the root map; under writes, only the
     * buckets that the writes touched. Completes with the number of ops merged.
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<Integer> syncByBuckets(Peer p) {
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
                        List<CompletableFuture<Integer>> pulls = new ArrayList<>();
                        for (int from = 0; from < differ.size(); from += BUCKETS_PER_REQUEST) {
                            List<Integer> chunk = differ.subList(from, Math.min(differ.size(), from + BUCKETS_PER_REQUEST));
                            pulls.add(client.rows(p.address, db, table, List.copyOf(chunk)).thenApply(this::mergeOrThrow));
                        }
                        return sum(pulls);
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
                        return Map.of("node", nodeId, "merged", users.merge(list));
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
