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
    /** Backups are written to a file named like this in the data directory while they are sent. */
    static final String BACKUP_PREFIX = "backup-";

    private final Config config;
    private final String id;
    private final StorageEngine storage;
    /** Scheduled backups into backup_dir; null when it is not set. */
    private final ScheduledBackups backups;
    /** When the certificates loaded at start expire, for the metrics. */
    private final NodeOps.TlsExpiry tlsExpiry;

    /** Days before a certificate's expiry from which a start logs a warning. */
    static final long EXPIRY_WARNING_DAYS = 30;

    private static void warnIfExpiring(String setting, Path store, long notAfterSeconds) {
        if (notAfterSeconds <= 0) {
            return;
        }
        long left = notAfterSeconds - System.currentTimeMillis() / 1000;
        if (left <= 0) {
            LOG.severe(setting + " " + store + " holds a certificate that expired at "
                    + java.time.Instant.ofEpochSecond(notAfterSeconds) + "; TLS clients and peers will refuse it");
        } else if (left < EXPIRY_WARNING_DAYS * 86_400) {
            LOG.warning(setting + " " + store + " holds a certificate that expires at "
                    + java.time.Instant.ofEpochSecond(notAfterSeconds) + ", in " + left / 86_400 + " days; the node"
                    + " reads its certificates at start, so restart it after replacing them");
        }
    }
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
     * The query language of docs/QUERY.md. Query mistakes are 400, statements the account may not run 403
     * (checked before anything is read, synced or written), too few reachable nodes 503, storage failures 500.
     */
    public static final QueryFactory QUERIES = (engine, clock, cluster) -> {
        Query q = new Query(engine, clock);
        return new QueryService() {
            @Override
            public Object run(String text, Users.Access access) throws Rejected {
                return run(text, access, false);
            }

            @Override
            public Object run(String text, Users.Access access, boolean majority) throws Rejected {
                return run(text, access, majority, null);
            }

            @Override
            public Object run(String text, Users.Access access, boolean majority, String ack) throws Rejected {
                if (engine.failed()) {
                    throw new Rejected(503, "this node's write-ahead log failed to fsync; restart it to recover from disk");
                }
                try {
                    Query.Checked checked = q.check(text, access.role().mayWrite(), access.databases());
                    if (majority && cluster != null) {
                        cluster.syncForRead();
                    }
                    if (ack == null || cluster == null) {
                        return checked.run().toJson();
                    }
                    Cluster.Ack a = ack.equals("local") ? Cluster.Ack.LOCAL : Cluster.Ack.MAJORITY;
                    try {
                        return Cluster.withAck(a, () -> checked.run().toJson());
                    } catch (QueryException | UnavailableException | IOException
                            | com.ataiva.serengeti.core.storage.WriteRefusedException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                } catch (com.ataiva.serengeti.core.query.ForbiddenException e) {
                    throw new Rejected(403, e.getMessage());
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
        this.tlsExpiry = new NodeOps.TlsExpiry(
                config.tls() ? com.ataiva.serengeti.core.server.Tls.earliestExpiry(config.tlsKeystore,
                        config.tlsKeystorePassword, "tls_keystore") : 0,
                config.tlsTruststore != null ? com.ataiva.serengeti.core.server.Tls.earliestExpiry(config.tlsTruststore,
                        config.tlsTruststorePassword, "tls_truststore") : 0);
        warnIfExpiring("tls_keystore", config.tlsKeystore, tlsExpiry.keystore());
        warnIfExpiring("tls_truststore", config.tlsTruststore, tlsExpiry.truststore());
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
        if (config.advertise != null) {
            LOG.warning("advertise is set but no version uses it; it is ignored");
        }
        this.storage = StorageEngine.open(dir, config.fsync, 64L * 1024 * 1024, config.testFsyncDelayMs);
        // Backups left behind by a node that stopped while sending one. The data directory is locked now.
        try (java.nio.file.DirectoryStream<Path> old = Files.newDirectoryStream(dir, BACKUP_PREFIX + "*.tmp")) {
            for (Path p : old) {
                Files.deleteIfExists(p);
            }
        }
        if (config.backupDir != null) {
            try {
                this.backups = ScheduledBackups.open(config.backupDir, dir, id, config.backupIntervalMinutes,
                        config.backupKeep, this::writeScheduledBackup, storage::failed, System::currentTimeMillis, true);
            } catch (ConfigException e) {
                storage.close();
                throw e;
            }
            LOG.info("a backup every " + config.backupIntervalMinutes + " min into " + config.backupDir + ", the newest "
                    + config.backupKeep + " kept");
        } else {
            this.backups = null;
        }
        StorageEngine.OpenStats st = storage.openStats();
        LOG.info("storage opened: " + st.snapshotOps() + " snapshot ops, " + st.walBatches() + " log batches"
                + (st.walDiscardedBytes() > 0 ? ", " + st.walDiscardedBytes() + " torn bytes discarded" : ""));
        this.clock = new Hlc(id);
        clock.observe(storage.read(s -> s.maxStamp()));
        this.memory = config.memoryLimitPercent < 100 ? MemoryGuard.ofHeap(config.memoryLimitPercent) : null;
        storage.setAdmission(memory);
        if (config.changesBuffer > 0) {
            storage.enableChanges(config.changesBuffer);
        }
        this.users = Users.open(dir);
        users.setStamps(clock::now);
        AtomicReference<SetupToken> token = new AtomicReference<>();
        boolean recoverLater = false;
        if (!users.isSetUp()) {
            if (config.adminPassword != null && !users.isEmpty() && config.clustered()) {
                // Entries (tombstones too) but no admin: either every admin was deleted, or this node has a
                // tombstone and not yet the admin that replaced it (each change is pushed on its own). Decide after
                // one account pull from a peer (see recoverAdmin), so a retired admin does not come back.
                recoverLater = true;
            } else if (config.adminPassword != null) {
                if (users.bootstrapAdmin(config.adminPassword) == Users.Bootstrap.RECOVERED) {
                    LOG.warning("no admin account was left (admins deleted, perhaps on two nodes at once); the admin "
                            + "is made again from SERENGETI_ADMIN_PASSWORD and replaces the deletion on every node");
                } else {
                    LOG.info("admin password set from the configuration");
                }
                Files.deleteIfExists(dir.resolve(SetupToken.FILE));
            } else {
                token.set(SetupToken.create(dir));
                LOG.warning((users.hasAccounts()
                        ? "no admin account is left; the other accounts still work. To make the admin again, "
                        : "no admin password yet. Every request except /health, /ready and /v1/setup is refused until it is set: ")
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
                    System::currentTimeMillis, cluster, operations()), config.httpThreads);
        } catch (IOException e) {
            if (backups != null) {
                backups.close();
            }
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
            cluster.setAccounts(users);
            cluster.start();
            if (recoverLater) {
                String pw = config.adminPassword;
                Cluster c = cluster;
                recovery = new Thread(() -> recoverAdmin(c, pw, dir), "admin-recovery");
                recovery.setDaemon(true);
                recovery.start();
            }
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
            public boolean diskFailed() {
                return storage.failed();
            }

            @Override
            public Optional<Path> backup() throws IOException {
                // In the data directory: private, and on a disk that holds the data already.
                Path f = Files.createTempFile(storage.dir(), BACKUP_PREFIX, ".tmp");
                try {
                    storage.writeBackup(f);
                } catch (IOException | RuntimeException e) {
                    Files.deleteIfExists(f);
                    throw e;
                }
                return Optional.of(f);
            }

            @Override
            public com.ataiva.serengeti.core.storage.ChangeFeed changes() {
                return storage.changes();
            }

            @Override
            public NodeOps.Gauges gauges() {
                Wal.Stats wal = storage.walStats();
                long[] counts = storage.read(s -> new long[] {s.databases().size(), s.conflicts(), s.weight()});
                com.ataiva.serengeti.core.storage.ChangeFeed f = storage.changes();
                long[] feed = f == null ? new long[2] : f.held();
                NodeOps.Cluster c = cluster == null ? NodeOps.Cluster.SINGLE
                        : new NodeOps.Cluster(cluster.size(), cluster.reachable(), cluster.quorum(),
                                cluster.caughtUp(), cluster.stale(), cluster.pulledOps());
                return new NodeOps.Gauges(wal.bytes(), wal.fsyncs(), wal.fsyncNanos(), counts[0], counts[1],
                        counts[2], memory == null ? 0 : memory.budget(), memory != null && counts[2] >= memory.budget(),
                        feed[0], feed[1], storage.failed(), c, backups == null ? NodeOps.Backups.OFF
                                : new NodeOps.Backups(backups.intervalSeconds(), backups.lastSuccessSeconds(),
                                        backups.failures(), backups.lastBytes()), tlsExpiry);
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

    /** How long a node with accounts but no admin waits for an account pull before it remakes the admin. */
    static final long RECOVERY_WAIT_MILLIS = 30_000;
    private volatile Thread recovery;

    /**
     * Makes the admin from SERENGETI_ADMIN_PASSWORD, after the accounts have been pulled from a peer once (or
     * {@link #RECOVERY_WAIT_MILLIS} without one), if there is still no admin then. Meanwhile the other accounts
     * work as usual.
     */
    private void recoverAdmin(Cluster c, String password, Path dir) {
        long before = c.accountSyncs();
        long deadline = System.currentTimeMillis() + RECOVERY_WAIT_MILLIS;
        try {
            while (c.accountSyncs() == before && System.currentTimeMillis() < deadline && !users.isSetUp()) {
                Thread.sleep(200);
            }
            if (users.isSetUp()) {
                LOG.info("an admin account came from a peer; SERENGETI_ADMIN_PASSWORD is not used");
                return;
            }
            Users.Bootstrap b = users.bootstrapAdmin(password);
            if (b == Users.Bootstrap.SKIPPED) {
                LOG.info("an admin account came from a peer; SERENGETI_ADMIN_PASSWORD is not used");
                return;
            }
            if (b == Users.Bootstrap.RECOVERED) {
                LOG.warning("no admin account was left (admins deleted, perhaps on two nodes at once"
                        + (c.accountSyncs() == before ? "; no peer answered for accounts in "
                                + RECOVERY_WAIT_MILLIS / 1000 + " s" : "") + "); the admin is made again from "
                        + "SERENGETI_ADMIN_PASSWORD and replaces the deletion on every node");
            }
            Files.deleteIfExists(dir.resolve(SetupToken.FILE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, "could not make the admin from SERENGETI_ADMIN_PASSWORD", e);
        }
    }

    /**
     * Stops in the order requests depend on things: first the HTTP server (requests in flight finish against
     * a working cluster layer, later ones get 503 "stopping"), then the cluster, then the storage. Closing the
     * cluster first made a stopping node answer 500 for about a second, also to writes already in its log.
     */
    @Override
    public void close() throws IOException {
        api.close();
        Thread r = recovery;
        if (r != null) {
            // It may be about to write the account files, which close unlocks.
            r.interrupt();
            try {
                r.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (maintenance != null) {
            maintenance.shutdownNow();
        }
        if (backups != null) {
            backups.close(); // before the storage: a backup being written stops and leaves no file
        }
        if (cluster != null) {
            cluster.close();
        }
        storage.close();
    }

    /**
     * A scheduled backup: written first to a temporary file in the data directory, which holds the storage's
     * read lock (writes wait meanwhile, as for {@code GET /v1/backup}), then copied into {@code target} without
     * it. So a slow or hung backup volume holds up only the copy, never the node's writes.
     */
    private void writeScheduledBackup(Path target) throws IOException {
        // The copy in the data directory must not take the room the node's own writes need: a full data disk
        // fails a write's fsync and stops the node from serving until it restarts. The snapshot and the log
        // hold about what a backup does; a quarter more and 64 MiB must be free besides.
        Path data = storage.dir();
        long estimate = sizeIfExists(data.resolve(StorageEngine.SNAPSHOT_FILE)) + sizeIfExists(data.resolve(StorageEngine.WAL_FILE));
        long need = estimate + estimate / 4 + (64L << 20);
        long free = Files.getFileStore(data).getUsableSpace();
        if (free < need) {
            throw new IOException("not enough room in the data directory for a backup: about " + need / (1 << 20)
                    + " MiB needed, " + free / (1 << 20) + " MiB free");
        }
        Path local = Files.createTempFile(storage.dir(), BACKUP_PREFIX, ".tmp");
        try {
            storage.writeBackup(local);
            try (java.io.OutputStream out = Files.newOutputStream(target, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                Files.copy(local, out);
            }
        } finally {
            Files.deleteIfExists(local);
        }
    }

    private static long sizeIfExists(Path p) throws IOException {
        return Files.exists(p) ? Files.size(p) : 0;
    }

    /** Writes a scheduled backup now, for tests; empty when backup_dir is not set. */
    Optional<Path> backupNowForTest() throws IOException {
        return backups == null ? Optional.empty() : Optional.of(backups.runNow());
    }
}
