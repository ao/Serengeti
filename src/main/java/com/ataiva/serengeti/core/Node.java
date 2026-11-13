package com.ataiva.serengeti.core;

import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.config.ConfigException;
import com.ataiva.serengeti.core.query.Query;
import com.ataiva.serengeti.core.query.QueryException;
import java.io.UncheckedIOException;
import com.ataiva.serengeti.core.server.HttpApi;
import com.ataiva.serengeti.core.server.QueryService;
import com.ataiva.serengeti.core.server.SetupToken;
import com.ataiva.serengeti.core.server.Users;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
                try {
                    if (majority && cluster != null) {
                        cluster.syncForRead();
                    }
                    return q.run(text).toJson();
                } catch (QueryException e) {
                    throw new Rejected(400, e.getMessage());
                } catch (UnavailableException e) {
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
                    System::currentTimeMillis, cluster));
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
        if (cluster != null) {
            cluster.close();
        }
        api.close();
        storage.close();
    }
}
