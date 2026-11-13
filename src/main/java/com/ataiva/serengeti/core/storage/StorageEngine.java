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
        lock.writeLock().lock();
        try {
            ensureOpen();
            wal.append(batch);
            store.applyAll(batch);
            if (wal.size() >= checkpointBytes) {
                checkpointLocked();
            }
        } finally {
            lock.writeLock().unlock();
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
        lock.writeLock().lock();
        try {
            ensureOpen();
            p = plan.apply(store);
            if (!p.ops().isEmpty()) {
                wal.append(p.ops());
                store.applyAll(p.ops());
                if (wal.size() >= checkpointBytes) {
                    checkpointLocked();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (!p.ops().isEmpty()) {
            h.after(p.ops());
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
                wal.append(fresh);
                store.applyAll(fresh);
                if (wal.size() >= checkpointBytes) {
                    checkpointLocked();
                }
            }
            return fresh.size();
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

    private void checkpointLocked() throws IOException {
        // If the process dies between these two steps, the log is replayed over the new snapshot on the
        // next start; that is harmless because applying an op twice changes nothing.
        Snapshot.writeAtomically(store.toOps(), dir.resolve(SNAPSHOT_FILE));
        wal.reset();
    }

    /** A consistent copy of the whole state, for backups and for peers catching up. */
    public List<Op> snapshotOps() {
        return read(Store::toOps);
    }

    public long walBytes() {
        return wal.size();
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
