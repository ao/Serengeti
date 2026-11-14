package com.ataiva.serengeti.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Durable storage for one node: a {@link Store} in memory, a {@link Wal} for every change, and a
 * {@link Snapshot} that the log is folded into once it grows past a threshold.
 *
 * <p>Writers hold an exclusive lock while appending to the log and applying the ops, so the in-memory
 * state never shows a change the log does not have. Readers share a read lock.
 */
public final class StorageEngine implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(StorageEngine.class.getName());
    public static final String WAL_FILE = "wal.log";
    public static final String SNAPSHOT_FILE = "snapshot.jsonl";
    /**
     * Index definitions (create and drop ops), kept out of the log and the snapshot so that a 2.0 binary,
     * which cannot read them, can still open this data directory: 2.0 cuts its log at the first record it
     * cannot read. Rewritten whole, atomically, whenever an index is created or dropped.
     */
    public static final String INDEX_FILE = "indexes.jsonl";

    private final Path dir;
    private final Store store = new Store();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final long checkpointBytes;
    private final Wal wal;
    private final ScheduledExecutorService flusher;
    private boolean closed;

    /** What {@link #open} found. */
    public record OpenStats(long snapshotOps, long walBatches, long walDiscardedBytes) {}

    private OpenStats openStats;

    private StorageEngine(Path dir, FsyncPolicy policy, long checkpointBytes, long testFsyncDelayMs)
            throws IOException {
        this.dir = dir;
        this.checkpointBytes = checkpointBytes;
        Files.createDirectories(dir);
        long snapOps = 0;
        Path snap = dir.resolve(SNAPSHOT_FILE);
        if (Files.exists(snap)) {
            try (InputStream in = Files.newInputStream(snap)) {
                List<Op> ops = Snapshot.read(in);
                store.applyAll(ops);
                snapOps = ops.size();
            }
        }
        List<List<Op>> batches = new ArrayList<>();
        long before = Files.exists(dir.resolve(WAL_FILE)) ? Files.size(dir.resolve(WAL_FILE)) : 0;
        this.wal = Wal.open(dir.resolve(WAL_FILE), policy, testFsyncDelayMs, batches);
        for (List<Op> b : batches) {
            store.applyAll(b);
        }
        Path indexes = dir.resolve(INDEX_FILE);
        if (Files.exists(indexes)) {
            try (InputStream in = Files.newInputStream(indexes)) {
                store.applyAll(Snapshot.read(in));
            }
        }
        this.openStats = new OpenStats(snapOps, batches.size(), before - wal.size());
        if (policy == FsyncPolicy.INTERVAL) {
            flusher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wal-flush");
                t.setDaemon(true);
                return t;
            });
            flusher.scheduleWithFixedDelay(() -> {
                try {
                    wal.flushIfDirty();
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "write-ahead log flush failed", e);
                }
            }, 1, 1, TimeUnit.SECONDS);
        } else {
            flusher = null;
        }
    }

    public static StorageEngine open(Path dir, FsyncPolicy policy) throws IOException {
        return new StorageEngine(dir, policy, 64L * 1024 * 1024, 0);
    }

    public static StorageEngine open(Path dir, FsyncPolicy policy, long checkpointBytes, long testFsyncDelayMs)
            throws IOException {
        return new StorageEngine(dir, policy, checkpointBytes, testFsyncDelayMs);
    }

    public OpenStats openStats() {
        return openStats;
    }

    /**
     * Logs and applies a batch. With {@link FsyncPolicy#ALWAYS}, the batch is on disk when this returns.
     * The batch is applied whole or, if the log write fails, not at all.
     */
    public void apply(List<Op> batch) throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        long pos;
        lock.writeLock().lock();
        try {
            ensureOpen();
            pos = commitLocked(batch);
        } finally {
            lock.writeLock().unlock();
        }
        if (pos >= 0) {
            wal.awaitDurable(pos);
        }
    }

    /**
     * Runs a check-then-write under the write lock: {@code plan} sees the current state and returns the
     * ops to apply (or throws to refuse). Used so that, for example, "insert unless the id exists" is atomic
     * on this node.
     */
    public <T> T update(Function<Store, Planned<T>> plan) throws IOException {
        WriteHooks h = hooks;
        h.before();
        Planned<T> p;
        List<Op> ops;
        long pos = -1;
        lock.writeLock().lock();
        try {
            ensureOpen();
            p = plan.apply(store);
            // Each row write records what this node held for its row, so peers can tell a later write
            // from a concurrent one (see Store).
            ops = store.withContext(p.ops());
            if (!ops.isEmpty()) {
                pos = commitLocked(ops);
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (pos >= 0) {
            // Outside the lock: writers that arrive during one fsync share the next (group commit).
            wal.awaitDurable(pos);
        }
        if (!ops.isEmpty()) {
            h.after(ops);
        }
        return p.result();
    }

    /**
     * Called around client writes ({@link #update}), outside the lock. Replication uses them: {@code before}
     * refuses a write when too few replicas are reachable, {@code after} sends the committed ops to the
     * replicas and may throw if too few acknowledge. Neither runs for {@link #apply} or {@link #merge}, which
     * take ops that came from elsewhere.
     */
    public interface WriteHooks {
        WriteHooks NONE = new WriteHooks() {
            @Override
            public void before() {}

            @Override
            public void after(List<Op> committed) {}
        };

        void before();

        void after(List<Op> committed) throws IOException;
    }

    private volatile WriteHooks hooks = WriteHooks.NONE;

    public void setWriteHooks(WriteHooks hooks) {
        this.hooks = hooks == null ? WriteHooks.NONE : hooks;
    }

    /**
     * Applies the ops that change the state and logs only those; returns how many that was. Used for ops
     * from replicas and catch-up, where most of a peer's state is usually known already.
     */
    public int merge(List<Op> ops) throws IOException {
        long pos = -1;
        int count;
        lock.writeLock().lock();
        try {
            ensureOpen();
            List<Op> fresh = new ArrayList<>();
            for (Op op : ops) {
                if (store.isNew(op)) {
                    fresh.add(op);
                }
            }
            if (!fresh.isEmpty()) {
                pos = commitLocked(fresh);
            }
            count = fresh.size();
        } finally {
            lock.writeLock().unlock();
        }
        if (pos >= 0) {
            wal.awaitDurable(pos);
        }
        return count;
    }

    /** Makes the next fsync fail with {@code e}, for tests of the failure path. */
    void failNextFsyncForTest(IOException e) {
        wal.failNextForceForTest(e);
    }

    /**
     * Drops row tombstones older than {@code ttlMillis}. Nothing is logged: a restart replays them from
     * the log or snapshot and the next collection drops them again.
     */
    public int collectTombstones(long ttlMillis) {
        long cutoff = System.currentTimeMillis() - ttlMillis;
        lock.writeLock().lock();
        try {
            ensureOpen();
            return store.collectTombstones(cutoff);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Ops chosen by an {@link #update} plan, and the value to return to its caller. */
    public record Planned<T>(List<Op> ops, T result) {}

    public <T> T read(Function<Store, T> reader) {
        lock.readLock().lock();
        try {
            return reader.apply(store);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Writes a snapshot of the current state and empties the log. */
    public void checkpoint() throws IOException {
        lock.writeLock().lock();
        try {
            ensureOpen();
            checkpointLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Logs the batch's ops except index definitions, applies all of it, and rewrites the index file when the
     * batch holds index ops. Returns the log position to wait for, or -1 if nothing was logged. Under the
     * write lock.
     */
    private long commitLocked(List<Op> ops) throws IOException {
        List<Op> logged = Op.legacyOnly(ops);
        long pos = logged.isEmpty() ? -1 : wal.write(logged);
        store.applyAll(ops);
        if (logged.size() != ops.size()) {
            Snapshot.writeAtomically(Op.indexOnly(store.toOps()), dir.resolve(INDEX_FILE));
        }
        if (wal.size() >= checkpointBytes) {
            checkpointLocked();
        }
        return pos;
    }

    private void checkpointLocked() throws IOException {
        // If the process dies between these two steps, the log is replayed over the new snapshot on the
        // next start; that is harmless because applying an op twice changes nothing.
        Snapshot.writeAtomically(Op.legacyOnly(store.toOps()), dir.resolve(SNAPSHOT_FILE));
        wal.reset();
    }

    /** A consistent copy of the whole state, for backups and for peers catching up. */
    public List<Op> snapshotOps() {
        return read(Store::toOps);
    }

    public long walBytes() {
        return wal.size();
    }

    /** The log's size and forces, for {@code GET /metrics}. */
    public Wal.Stats walStats() {
        return wal.stats();
    }

    public Path dir() {
        return dir;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("storage is closed");
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (flusher != null) {
                flusher.shutdownNow();
            }
            wal.close();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
