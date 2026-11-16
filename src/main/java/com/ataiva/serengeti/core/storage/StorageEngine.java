package com.ataiva.serengeti.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private DirLock dirLock;

    /** What {@link #open} found. */
    public record OpenStats(long snapshotOps, long walBatches, long walDiscardedBytes) {}

    private OpenStats openStats;

    private StorageEngine(Path dir, FsyncPolicy policy, long checkpointBytes, long testFsyncDelayMs)
            throws IOException {
        this.dir = dir;
        this.checkpointBytes = checkpointBytes;
        Files.createDirectories(dir);
        // Recovery applies each op as it is read, so a node whose data is near its memory limit needs no more
        // memory to start than to run. If anything fails, the half-built store is dropped with this object.
        long snapOps = 0;
        Path snap = dir.resolve(SNAPSHOT_FILE);
        if (Files.exists(snap)) {
            try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(snap), 1 << 16)) {
                snapOps = Snapshot.read(in, false, store::apply);
            }
        }
        long[] batches = {0};
        long before = Files.exists(dir.resolve(WAL_FILE)) ? Files.size(dir.resolve(WAL_FILE)) : 0;
        this.wal = Wal.open(dir.resolve(WAL_FILE), policy, testFsyncDelayMs, (java.util.function.Consumer<List<Op>>) b -> {
            store.applyAll(b);
            batches[0]++;
        });
        Path indexes = dir.resolve(INDEX_FILE);
        if (Files.exists(indexes)) {
            try (InputStream in = Files.newInputStream(indexes)) {
                Snapshot.read(in, false, store::apply);
            }
        }
        this.openStats = new OpenStats(snapOps, batches[0], before - wal.size());
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
        return open(dir, policy, 64L * 1024 * 1024, 0);
    }

    /** Opens {@code dir}, taking its {@link DirLock} first: a directory another engine has open is refused. */
    public static StorageEngine open(Path dir, FsyncPolicy policy, long checkpointBytes, long testFsyncDelayMs)
            throws IOException {
        Files.createDirectories(dir);
        DirLock dirLock = DirLock.acquire(dir);
        try {
            StorageEngine e = new StorageEngine(dir, policy, checkpointBytes, testFsyncDelayMs);
            e.dirLock = dirLock;
            return e;
        } catch (IOException | RuntimeException | Error t) {
            dirLock.close();
            throw t;
        }
    }

    public OpenStats openStats() {
        return openStats;
    }

    /**
     * Logs and applies a batch. With {@link FsyncPolicy#ALWAYS}, the batch is on disk when this returns.
     * The batch is applied whole or, if writing the log fails, not at all. It is applied before its fsync
     * (group commit), so if the fsync then fails the node stops serving its data: see {@link #failed()}.
     */
    public void apply(List<Op> batch) throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        long pos;
        long upto = 0;
        lock.writeLock().lock();
        try {
            ensureOpen();
            pos = commitLocked(batch);
            upto = lastChange();
        } finally {
            lock.writeLock().unlock();
        }
        if (pos >= 0) {
            wal.awaitDurable(pos);
            publish(upto);
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
        long upto = 0;
        lock.writeLock().lock();
        try {
            ensureOpen();
            p = plan.apply(store);
            admission.admit(store, p.ops());
            // Each row write records what this node held for its row, so peers can tell a later write
            // from a concurrent one (see Store).
            ops = store.withContext(p.ops());
            if (!ops.isEmpty()) {
                pos = commitLocked(ops);
                upto = lastChange();
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (pos >= 0) {
            // Outside the lock: writers that arrive during one fsync share the next (group commit).
            wal.awaitDurable(pos);
            publish(upto);
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
        long upto = 0;
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
                upto = lastChange();
            }
            count = fresh.size();
        } finally {
            lock.writeLock().unlock();
        }
        if (pos >= 0) {
            wal.awaitDurable(pos);
            publish(upto);
        }
        return count;
    }

    /**
     * True once an fsync of the log has failed. Writes applied in memory since the last good fsync may not be
     * on disk, so the node then serves nothing from its data (queries, peers, readiness) until it restarts
     * and recovers from what the disk holds.
     */
    public boolean failed() {
        return wal.failed();
    }

    private volatile ChangeFeed changes;

    /** Starts keeping the last {@code capacity} changes for {@code GET /v1/changes} (see ChangeFeed). */
    public void enableChanges(int capacity) {
        lock.writeLock().lock();
        try {
            // At most a sixteenth of the heap: the feed holds row values the memory limit does not count.
            // Changes are published once on disk (see publish), not as they are applied.
            changes = new ChangeFeed(capacity, Runtime.getRuntime().maxMemory() / 16, false);
            store.setFeed(changes);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** The change feed, or null if it is not enabled. */
    public ChangeFeed changes() {
        return changes;
    }

    /** Makes the next fsync fail with {@code e}, for tests of the failure path. */
    public void failNextFsyncForTest(IOException e) {
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

    /**
     * Decides whether a client write may go ahead once its ops are known; throws (for example
     * {@link WriteRefusedException}) to refuse it, before anything is logged. Not asked for ops from peers.
     */
    public interface Admission {
        Admission ALL = (store, planned) -> { };

        void admit(Store store, List<Op> planned);
    }

    private volatile Admission admission = Admission.ALL;

    public void setAdmission(Admission admission) {
        this.admission = admission == null ? Admission.ALL : admission;
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


    /** The feed's newest change, read under the write lock right after a commit. */
    private long lastChange() {
        ChangeFeed f = changes;
        return f == null ? 0 : f.last();
    }

    /**
     * After the log is on disk up to a commit, its changes (and every earlier one, as the log is written in
     * order) may be shown to feed readers. A commit whose fsync fails never gets here.
     */
    private void publish(long upto) {
        ChangeFeed f = changes;
        if (f != null) {
            f.publish(upto);
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
            Snapshot.writeAtomically(store::forEachIndexOp, dir.resolve(INDEX_FILE));
        }
        if (wal.size() >= checkpointBytes) {
            checkpointLocked();
        }
        return pos;
    }

    private void checkpointLocked() throws IOException {
        // If the process dies between these two steps, the log is replayed over the new snapshot on the
        // next start; that is harmless because applying an op twice changes nothing. The ops are written one
        // at a time: a list of them all would need memory a node near its limit does not have.
        Snapshot.writeAtomically(sink -> store.forEachOp(op -> {
            if (Op.legacy(op)) {
                sink.accept(op);
            }
        }), dir.resolve(SNAPSHOT_FILE));
        wal.reset();
    }

    /** A consistent copy of the whole state, as a list. For tests; backups use {@link #writeBackup}. */
    public List<Op> snapshotOps() {
        return read(Store::toOps);
    }

    /**
     * Writes a consistent copy of the whole state to {@code file} in the backup format, one op at a time, under
     * the read lock: writes wait while it is written, as they do for a checkpoint.
     */
    public void writeBackup(Path file) throws IOException {
        lock.readLock().lock();
        try {
            ensureOpen();
            try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
                Snapshot.write(store::forEachOp, out);
            }
        } finally {
            lock.readLock().unlock();
        }
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
            try {
                wal.close();
            } finally {
                if (dirLock != null) {
                    dirLock.close();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
