package com.ataiva.serengeti.core;

import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.config.ConfigException;
import com.ataiva.serengeti.core.query.Query;
import com.ataiva.serengeti.core.query.QueryException;
import java.io.UncheckedIOException;
import com.ataiva.serengeti.core.server.HttpApi;
import com.ataiva.serengeti.core.server.NodeOps;
import com.ataiva.serengeti.core.server.QueryService;
import com.ataiva.serengeti.core.server.SetupToken;
import com.ataiva.serengeti.core.server.Users;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.StorageEngine;
import com.ataiva.serengeti.core.storage.Wal;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.ataiva.serengeti.core.cluster.Cluster;
import com.ataiva.serengeti.core.cluster.UnavailableException;
import java.util.logging.Logger;

/** One running node: storage, accounts and the HTTP API, started in that order and closed in reverse. */
public final class Node implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Node.class.getName());
    public static final String NODE_ID_FILE = "node-id";

    private final Config config;
    private final String id;
    private final StorageEngine storage;
    /** Refuses writes that add data once the data fills its share of the heap; null at memory_limit_percent 100. */
    private final MemoryGuard memory;
    private final Hlc clock;
    private final Users users;
    private final HttpApi api;
    private final long started = System.currentTimeMillis();

    /** Builds the query service once storage is open. {@code cluster} is null on a single node. */
    @FunctionalInterface
    public interface QueryFactory {
        QueryService create(StorageEngine engine, Hlc clock, Cluster cluster);
    }

    /** Every query is refused; for tests of the rest of the node. */
    public static final QueryFactory NO_QUERIES = (s, c, cl) -> (q, r) -> {
        throw new QueryService.Rejected(501, "queries are not available in this build");
    };

    /**
     * The query language of docs/QUERY.md. Query mistakes are 400, too few reachable nodes 503, storage
     * failures 500.
     */
    public static final QueryFactory QUERIES = (engine, clock, cluster) -> {
        Query q = new Query(engine, clock);
        return new QueryService() {
            @Override
            public Object run(String text, Users.Role role) throws Rejected {
                return run(text, role, false);
            }

            @Override
            public Object run(String text, Users.Role role, boolean majority) throws Rejected {
                return run(text, role, majority, null);
            }

            @Override
            public Object run(String text, Users.Role role, boolean majority, String ack) throws Rejected {
                if (engine.failed()) {
                    throw new Rejected(503, "this node's write-ahead log failed to fsync; restart it to recover from disk");
                }
                try {
                    if (majority && cluster != null) {
                        cluster.syncForRead();
                    }
                    if (ack == null || cluster == null) {
                        return q.run(text).toJson();
                    }
                    Cluster.Ack a = ack.equals("local") ? Cluster.Ack.LOCAL : Cluster.Ack.MAJORITY;
                    try {
                        return Cluster.withAck(a, () -> q.run(text).toJson());
                    } catch (QueryException | UnavailableException | IOException
                            | com.ataiva.serengeti.core.storage.WriteRefusedException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                } catch (QueryException e) {
                    throw new Rejected(400, e.getMessage());
                } catch (UnavailableException | com.ataiva.serengeti.core.storage.WriteRefusedException e) {
                    throw new Rejected(503, e.getMessage());
                } catch (IOException e) {
                    throw new UncheckedIOException("storage failed", e);
                }
            }
        };
    };

    private Cluster cluster;

    private Node(Config config, QueryFactory queries) throws IOException {
        this.config = config;
        // Load the key stores first, so a bad one stops the node before it touches the data directory.
        javax.net.ssl.SSLContext tls = config.tls()
                ? com.ataiva.serengeti.core.server.Tls.serverContext(config.tlsKeystore, config.tlsKeystorePassword)
                : null;
        javax.net.ssl.SSLContext peerTls = config.clustered() && config.tls()
                ? com.ataiva.serengeti.core.server.Tls.clientContext(config.tlsTruststore, config.tlsTruststorePassword)
                : null;
        Path dir = config.dataDir;
        try {
            Files.createDirectories(dir);
        } catch (AccessDeniedException e) {
            throw new ConfigException("cannot create the data directory " + dir + ": permission denied");
        }
        if (!Files.isDirectory(dir) || !Files.isWritable(dir)) {
            throw new ConfigException("the data directory " + dir + " is not a writable directory");
        }
        this.id = nodeId(dir);
        this.storage = StorageEngine.open(dir, config.fsync, 64L * 1024 * 1024, config.testFsyncDelayMs);
        StorageEngine.OpenStats st = storage.openStats();
        LOG.info("storage opened: " + st.snapshotOps() + " snapshot ops, " + st.walBatches() + " log batches"
                + (st.walDiscardedBytes() > 0 ? ", " + st.walDiscardedBytes() + " torn bytes discarded" : ""));
        this.clock = new Hlc(id);
        clock.observe(storage.read(s -> s.maxStamp()));
        this.memory = config.memoryLimitPercent < 100 ? MemoryGuard.ofHeap(config.memoryLimitPercent) : null;
        storage.setAdmission(memory);
        this.users = Users.open(dir);
        AtomicReference<SetupToken> token = new AtomicReference<>();
        if (!users.isSetUp()) {
            if (config.adminPassword != null) {
                users.setPassword(Users.ADMIN, config.adminPassword, Users.Role.ADMIN);
                Files.deleteIfExists(dir.resolve(SetupToken.FILE));
                LOG.info("admin password set from the configuration");
            } else {
                token.set(SetupToken.create(dir));
                LOG.warning("no admin password yet. Every request except /health is refused until it is set: "
                        + "POST /v1/setup with {\"token\": ..., \"password\": ...}, using the token in "
                        + token.get().file().toAbsolutePath());
            }
        } else if (config.adminPassword != null) {
            LOG.info("an admin account exists already; SERENGETI_ADMIN_PASSWORD is ignored");
        }
        if (config.clustered()) {
            cluster = new Cluster(config, id, storage, peerTls);
            storage.setWriteHooks(cluster);
        }
        QueryService qs = queries.create(storage, clock, cluster);
        try {
            this.api = HttpApi.start(config.bind, config.port, tls, new HttpApi.Deps(users, token, qs, this::status,
                    System::currentTimeMillis, cluster, operations()));
        } catch (IOException e) {
            if (cluster != null) {
                cluster.close();
            }
            storage.close();
            throw new ConfigException("cannot listen on " + config.bind + ":" + config.port + ": " + e.getMessage());
        }
        LOG.info("serengeti " + Version.current() + " node " + id + " listening on "
                + config.scheme() + "://" + (config.bind.contains(":") ? "[" + config.bind + "]" : config.bind)
                + ":" + api.address().getPort()
                + (cluster == null ? "" : ", cluster of " + cluster.size() + " (writes need " + cluster.quorum() + ")"));
        if (cluster != null) {
            cluster.start();
        }
        maintenance = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tombstone-gc");
            t.setDaemon(true);
            return t;
        });
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                if (cluster != null && !cluster.mayCollectTombstones()) {
                    return; // a peer not yet seen syncing by buckets could bring back what was deleted
                }
                int n = storage.collectTombstones(config.tombstoneTtlMillis);
                if (n > 0) {
                    LOG.info("dropped " + n + " tombstones older than " + config.tombstoneTtlMillis / 3_600_000 + " h");
                }
            } catch (RuntimeException e) {
                LOG.log(java.util.logging.Level.WARNING, "tombstone collection failed", e);
            }
        }, 1, 10, java.util.concurrent.TimeUnit.MINUTES);
    }

    private java.util.concurrent.ScheduledExecutorService maintenance;

    /** Runs tombstone collection now, for tests. */
    int collectTombstonesNow() {
        return storage.collectTombstones(config.tombstoneTtlMillis);
    }

    /** The storage engine, for tests. */
    StorageEngine storageForTest() {
        return storage;
    }

    /** The cluster this node belongs to, or null for a single node. */
    public Cluster cluster() {
        return cluster;
    }

    public static Node start(Config config, QueryFactory queries) throws IOException {
        return new Node(config, queries);
    }

    private static String nodeId(Path dir) throws IOException {
        Path f = dir.resolve(NODE_ID_FILE);
        if (Files.exists(f)) {
            String s = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (!s.matches("[0-9a-f-]{36}")) {
                throw new IOException(f + " does not hold a node id");
            }
            return s;
        }
        String s = UUID.randomUUID().toString();
        Files.writeString(f, s + "\n", StandardCharsets.UTF_8);
        return s;
    }

    /** What the HTTP API reads from this node for {@code /ready} and {@code /metrics}. */
    private NodeOps operations() {
        return new NodeOps() {
            @Override
            public List<java.util.function.BooleanSupplier> readiness() {
                // In a cluster, ready means caught up with a majority since start-up and a majority reachable
                // now; a node that acknowledges writes locally by default only needs to be in date.
                java.util.function.BooleanSupplier disk = () -> !storage.failed();
                return cluster == null ? List.of(disk)
                        : List.of(disk, () -> !cluster.stale() && (cluster.localAckByDefault()
                                || cluster.caughtUp() && cluster.reachable() >= cluster.quorum()));
            }

            @Override
            public Optional<List<Op>> snapshot() {
                return Optional.of(storage.snapshotOps());
            }

            @Override
            public NodeOps.Gauges gauges() {
                Wal.Stats wal = storage.walStats();
                long[] counts = storage.read(s -> new long[] {s.databases().size(), s.conflicts(), s.weight()});
                return new NodeOps.Gauges(wal.bytes(), wal.fsyncs(), wal.fsyncNanos(), counts[0], counts[1],
                        counts[2], memory == null ? 0 : memory.budget(), memory != null && counts[2] >= memory.budget());
            }
        };
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", id);
        m.put("version", Version.current());
        m.put("uptime_s", (System.currentTimeMillis() - started) / 1000);
        m.put("databases", storage.read(s -> s.databases().size()));
        m.put("fsync", config.fsync.name().toLowerCase(java.util.Locale.ROOT));
        if (cluster != null) {
            m.put("cluster", cluster.status());
        }
        return m;
    }

    public InetSocketAddress address() {
        return api.address();
    }

    public String id() {
        return id;
    }

    @Override
    public void close() throws IOException {
        if (maintenance != null) {
            maintenance.shutdownNow();
        }
        if (cluster != null) {
            cluster.close();
        }
        api.close();
        storage.close();
    }
}
