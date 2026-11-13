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

    private static final class Peer {
        final String address;
        volatile long lastContact = Long.MIN_VALUE / 2;
        volatile String node;
        volatile String lastError;

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

    public Cluster(Config config, String nodeId, StorageEngine engine, SSLContext clientTls) {
        this(config, nodeId, engine, clientTls, System::currentTimeMillis);
    }

    Cluster(Config config, String nodeId, StorageEngine engine, SSLContext clientTls, LongSupplier clock) {
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
        if (!caughtUp) {
            throw new UnavailableException("this node is still catching up with the cluster");
        }
        int r = reachable();
        if (r < quorum) {
            throw new UnavailableException(r + " of " + size + " nodes are reachable; writes need " + quorum);
        }
    }

    @Override
    public void after(List<Op> committed) {
        if (quorum == 1 && peers.isEmpty()) {
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
            client.apply(p.address, committed).whenComplete((v, err) -> {
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

    private void syncRound() {
        if (closed) {
            return;
        }
        try {
            List<CompletableFuture<Boolean>> runs = new ArrayList<>();
            for (Peer p : peers) {
                runs.add(syncWith(p));
            }
            int ok = 0;
            for (CompletableFuture<Boolean> f : runs) {
                try {
                    if (f.get(PeerClient.STATE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        ok++;
                    }
                } catch (Exception e) {
                    // counted as not synced
                }
            }
            if (!caughtUp && ok + 1 >= quorum) {
                caughtUp = true;
                LOG.info("caught up with " + ok + " of " + peers.size() + " peers; accepting writes");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "sync round failed", e);
        }
    }

    /** Pulls every database whose digest differs from the peer's. Completes with true if it worked. */
    private CompletableFuture<Boolean> syncWith(Peer p) {
        return client.digests(p.address).thenCompose(theirs -> {
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
            return CompletableFuture.allOf(pulls.toArray(CompletableFuture[]::new)).thenApply(v -> {
                int merged = 0;
                for (CompletableFuture<List<Op>> f : pulls) {
                    try {
                        merged += engine.merge(f.join());
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                if (merged > 0) {
                    LOG.fine("merged " + merged + " ops from " + p.address);
                }
                p.lastContact = clock.getAsLong();
                if (p.lastError != null) {
                    LOG.info("peer " + p.address + " is reachable again");
                    p.lastError = null;
                }
                return true;
            });
        }).exceptionally(err -> {
            String msg = PeerClient.describe(err);
            if (!msg.equals(p.lastError)) {
                LOG.warning("peer " + p.address + ": " + msg);
            }
            p.lastError = msg;
            return false;
        });
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
        switch (path) {
            case "/internal/hello" -> {
                return Map.of("node", nodeId, "cluster_size", size);
            }
            case "/internal/digests" -> {
                return Map.of("node", nodeId, "digests", engine.read(Store::digests));
            }
            case "/internal/state" -> {
                String db = query.get("db");
                if (db == null || db.isEmpty()) {
                    throw new QueryService.Rejected(400, "db is required");
                }
                List<Op> ops = engine.read(s -> s.toOps(db));
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
                try {
                    return Map.of("node", nodeId, "applied", engine.merge(ops));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            default -> throw new QueryService.Rejected(404, "no such route");
        }
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
