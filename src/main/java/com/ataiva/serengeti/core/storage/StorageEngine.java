package com.ataiva.serengeti.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
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
 *
 * <p>With a change feed, opening replays the log with the feed attached, so the log's records make their
 * changes again with the same numbers, after the ones {@link #CHANGES_FILE} kept from before the last
 * checkpoint (see FeedFiles). The feed's epoch is kept there too, and changes only when the directory can no
 * longer give the same answers; settleFeed lists when.
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
    /** The change feed's epoch and last changes at the last checkpoint (see FeedFiles). */
    public static final String CHANGES_FILE = "changes.jsonl";
    /** Where tombstones were dropped since the last checkpoint, and clean stops with fsync off (see FeedFiles). */
    public static final String MARKS_FILE = "changes-marks.jsonl";
    /** {@link #CHANGES_FILE} for the checkpoint being written, renamed into place once the log is emptied. */
    static final String CHANGES_NEXT = "changes.jsonl.next";

    private final Path dir;
    private final Store store = new Store();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final long checkpointBytes;
    private final FsyncPolicy policy;
    private final Wal wal;
    private final ScheduledExecutorService flusher;
    private boolean closed;
    private DirLock dirLock;
    /**
     * The id in the snapshot's header, new at each checkpoint, or null for a snapshot without one (none yet,
     * or written by 2.0 or 2.1). The change feed's files name it, so they are used only with that snapshot.
     */
    private String checkpointId;

    /** What {@link #open} found. {@code changes} says how the change feed started; null without one. */
    public record OpenStats(long snapshotOps, long walBatches, long walDiscardedBytes, String changes) {
        public OpenStats(long snapshotOps, long walBatches, long walDiscardedBytes) {
            this(snapshotOps, walBatches, walDiscardedBytes, null);
        }
    }

    private OpenStats openStats;

    private StorageEngine(Path dir, FsyncPolicy policy, long checkpointBytes, long testFsyncDelayMs,
            int changesCapacity) throws IOException {
        this.dir = dir;
        this.checkpointBytes = checkpointBytes;
        this.policy = policy;
        Files.createDirectories(dir);
        // Recovery applies each op as it is read, so a node whose data is near its memory limit needs no more
        // memory to start than to run. If anything fails, the half-built store is dropped with this object.
        long snapOps = 0;
        Path snap = dir.resolve(SNAPSHOT_FILE);
        if (Files.exists(snap)) {
            try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(snap), 1 << 16)) {
                snapOps = Snapshot.read(in, false, store::apply, h -> {
                    if (h.get("checkpoint") instanceof String id) {
                        checkpointId = id;
                    }
                });
            }
        }
        Feed feed = changesCapacity > 0 ? startFeed(changesCapacity) : null;
        if (feed == null) {
            // No feed: nothing keeps its files up to date, so a feed turned on later must not take them.
            for (String f : List.of(CHANGES_FILE, CHANGES_NEXT, MARKS_FILE)) {
                Files.deleteIfExists(dir.resolve(f));
            }
        }
        // Tombstones dropped before a record are dropped again before it (see FeedFiles), so the log's records
        // make the same changes as when they were applied.
        List<FeedFiles.Mark> collects = new ArrayList<>();
        if (feed != null) {
            for (FeedFiles.Mark m : feed.marks().marks()) {
                if (m.kind().equals("collect")) {
                    collects.add(m);
                }
            }
        }
        int[] nextMark = {0};
        long[] batches = {0};
        long before = Files.exists(dir.resolve(WAL_FILE)) ? Files.size(dir.resolve(WAL_FILE)) : 0;
        this.wal = Wal.open(dir.resolve(WAL_FILE), policy, testFsyncDelayMs, (ops, start) -> {
            while (nextMark[0] < collects.size() && collects.get(nextMark[0]).at() <= start) {
                collect(collects.get(nextMark[0]++).cutoff());
            }
            store.applyAll(ops);
            batches[0]++;
        });
        String note;
        try {
            boolean marksPastEnd = false;
            while (nextMark[0] < collects.size()) {
                // Marks past the log's end: the records before them were lost (never acknowledged). They are
                // applied here, and a checkpoint keeps them from landing inside the records written next.
                FeedFiles.Mark m = collects.get(nextMark[0]++);
                marksPastEnd |= m.at() > wal.size();
                collect(m.cutoff());
            }
            Path indexes = dir.resolve(INDEX_FILE);
            if (Files.exists(indexes)) {
                try (InputStream in = Files.newInputStream(indexes)) {
                    Snapshot.read(in, false, store::apply);
                }
            }
            note = feed == null ? null : settleFeed(feed, before, marksPastEnd);
        } catch (IOException | RuntimeException | Error e) {
            wal.close(false);
            throw e;
        }
        this.openStats = new OpenStats(snapOps, wal.recordsAtOpen(), before - wal.size(), note);
        if (policy == FsyncPolicy.INTERVAL) {
            flusher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wal-flush");
                t.setDaemon(true);
                return t;
            });
            flusher.scheduleWithFixedDelay(this::flushAndPublish, 1, 1, TimeUnit.SECONDS);
        } else {
            flusher = null;
        }
    }

    /** The feed being started, from {@link #CHANGES_FILE} or new, and what was found. */
    private record Feed(ChangeFeed feed, String newEpochBecause, boolean fromNext, FeedFiles.Marks marks) {}

    /**
     * Makes the change feed before the log is replayed, so the replay adds the log's changes to it. It goes on
     * from {@link #CHANGES_FILE} when that was written with the snapshot just read; otherwise it is new, and
     * {@link #settleFeed} starts a new epoch.
     */
    private Feed startFeed(int capacity) {
        // At most a sixteenth of the heap: the feed holds row values the memory limit does not count.
        long maxBytes = Runtime.getRuntime().maxMemory() / 16;
        FeedFiles.Loaded loaded = null;
        boolean fromNext = false;
        String why = null;
        if (checkpointId == null) {
            why = Files.exists(dir.resolve(SNAPSHOT_FILE)) ? "snapshot.jsonl was written by an older version or a restore"
                    : "the data directory is new";
        } else {
            // A checkpoint that stopped after its snapshot was renamed into place, before the new changes.jsonl was.
            loaded = FeedFiles.read(dir.resolve(CHANGES_NEXT), checkpointId, capacity, maxBytes);
            fromNext = loaded != null;
            if (loaded == null) {
                loaded = FeedFiles.read(dir.resolve(CHANGES_FILE), checkpointId, capacity, maxBytes);
            }
            if (loaded == null) {
                why = Files.exists(dir.resolve(CHANGES_FILE)) ? "changes.jsonl is not the one written with snapshot.jsonl"
                        : "there is no changes.jsonl";
            }
        }
        FeedFiles.Marks marks = FeedFiles.readMarks(dir.resolve(MARKS_FILE), checkpointId);
        if (marks.damaged() && why == null) {
            why = "changes-marks.jsonl is damaged";
        }
        ChangeFeed feed = loaded != null ? loaded.feed() : new ChangeFeed(capacity, maxBytes, false);
        // Changes are published once on disk (see publish), not as they are applied.
        store.setFeed(feed);
        changes = feed;
        return new Feed(feed, why, fromNext, marks);
    }

    /**
     * After the replay: decides whether the feed keeps its epoch, starts a new one, and writes a checkpoint if
     * the files must be made to agree again. Returns what it did, for the log.
     */
    private String settleFeed(Feed f, long walBytesBefore, boolean marksPastEnd) throws IOException {
        ChangeFeed feed = f.feed();
        Wal.Recovered rec = wal.recovered();
        String why = f.newEpochBecause();
        if (why == null && f.fromNext() && rec.validBytes() > 0) {
            // The log was emptied after this snapshot, or is the one the snapshot already holds: no way to tell.
            why = "the node stopped during a checkpoint";
        }
        if (why == null && rec.wholeRecordDiscarded()) {
            why = "the log lost a record it held whole";
        }
        List<FeedFiles.Mark> ms = f.marks().marks();
        FeedFiles.Mark last = ms.isEmpty() ? null : ms.get(ms.size() - 1);
        if (why == null && policy == FsyncPolicy.OFF) {
            // Without fsync, changes are listed before they are on disk, and a crash of the machine can lose
            // them. Only a clean stop says the log holds everything that was listed.
            if (last == null || !last.kind().equals("close") || last.at() != walBytesBefore || rec.discardedBytes() > 0) {
                why = "with fsync off, only a clean stop keeps the feed";
            }
        }
        if (why == null) {
            // The run before may have had fsync off, whatever this one has: its changes were listed before they
            // were on disk, and only its clean stop ("close" after its "open") says the log holds them.
            FeedFiles.Mark lastRun = null;
            for (FeedFiles.Mark m : ms) {
                if (m.kind().equals("open") || m.kind().equals("close")) {
                    lastRun = m;
                }
            }
            if (lastRun != null && lastRun.kind().equals("open")) {
                why = "the last run had fsync off and did not stop cleanly";
            }
        }
        String note;
        if (why != null) {
            feed.newEpoch();
            note = "new epoch " + feed.epoch() + " (" + why + "); cursors from before get 410";
        } else {
            long[] held = feed.held();
            note = "epoch " + feed.epoch() + " kept, " + held[0] + " changes held, the newest " + feed.last();
        }
        try {
            if (why != null) {
                // First, so that if the checkpoint fails the next start cannot take the old files for current.
                forgetFeedFiles();
                checkpointLocked();
            } else if (f.fromNext()) {
                // The checkpoint got as far as emptying the log: finish it.
                Files.move(dir.resolve(CHANGES_NEXT), dir.resolve(CHANGES_FILE), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                Snapshot.forceDirectory(dir.toAbsolutePath());
                Files.deleteIfExists(dir.resolve(MARKS_FILE));
            } else if (marksPastEnd) {
                checkpointLocked();
            }
            Files.deleteIfExists(dir.resolve(CHANGES_NEXT));
            if (policy == FsyncPolicy.OFF) {
                // Until the next clean stop, a start must not take this run's changes for ones on disk.
                FeedFiles.appendMark(dir.resolve(MARKS_FILE), new FeedFiles.Mark(checkpointId, "open", 0, 0));
            }
        } catch (IOException e) {
            // The node can still run (a full disk, say); only the next start must begin a new epoch.
            forgetFeedFiles();
            LOG.warning("cannot write the change feed's files (" + e.getMessage() + "); the next start begins a new"
                    + " epoch");
        }
        // A record the killed run wrote but never forced may be only in the page cache: force the log before
        // its changes are listed (review 22f). Linux reports a writeback error once, so after a failed fsync this
        // force can succeed for a page that never reached the disk; API.md says so.
        wal.forceReplayed();
        feed.publish(feed.last());
        return note;
    }

    /** Removes the feed's files, so that the next start begins a new epoch. */
    private void forgetFeedFiles() throws IOException {
        Files.deleteIfExists(dir.resolve(CHANGES_FILE));
        Files.deleteIfExists(dir.resolve(CHANGES_NEXT));
        Snapshot.forceDirectory(dir.toAbsolutePath());
    }

    /** Drops the tombstones {@link #expireTombstones} would at {@code cutoff}, as it does: expiry first. */
    private void collect(long cutoff) {
        store.setExpiry(cutoff);
        store.collectTombstones(cutoff);
    }

    /**
     * With {@link FsyncPolicy#INTERVAL}, once a second: forces the log and then publishes the changes the force
     * covers, so the feed lists a change only once it is on disk with this policy too.
     */
    private void flushAndPublish() {
        long upto;
        long pos;
        // Writers log and apply under the write lock, so the feed's newest change and the log's end match here.
        lock.readLock().lock();
        try {
            if (closed) {
                return;
            }
            upto = lastChange();
            pos = wal.position();
        } finally {
            lock.readLock().unlock();
        }
        try {
            if (wal.flushTo(pos)) {
                publish(upto);
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "write-ahead log flush failed", e);
        }
    }

    public static StorageEngine open(Path dir, FsyncPolicy policy) throws IOException {
        return open(dir, policy, 64L * 1024 * 1024, 0);
    }

    /** Opens {@code dir} without a change feed. */
    public static StorageEngine open(Path dir, FsyncPolicy policy, long checkpointBytes, long testFsyncDelayMs)
            throws IOException {
        return open(dir, policy, checkpointBytes, testFsyncDelayMs, 0);
    }

    /**
     * Opens {@code dir}, taking its {@link DirLock} first: a directory another engine has open is refused. With
     * {@code changesCapacity} above 0, keeps the last that many changes for {@code GET /v1/changes} (see
     * ChangeFeed), and rebuilds them from the data directory.
     */
    public static StorageEngine open(Path dir, FsyncPolicy policy, long checkpointBytes, long testFsyncDelayMs,
            int changesCapacity) throws IOException {
        Files.createDirectories(dir);
        DirLock dirLock = DirLock.acquire(dir);
        try {
            StorageEngine e = new StorageEngine(dir, policy, checkpointBytes, testFsyncDelayMs, changesCapacity);
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
            awaitThenPublish(pos, upto);
        }
    }

    /**
     * Waits until the log holds a commit on disk, then lets feed readers see its changes. With
     * {@link FsyncPolicy#INTERVAL} the wait returns at once and the timer publishes after its force instead
     * ({@link #flushAndPublish}).
     */
    private void awaitThenPublish(long pos, long upto) throws IOException {
        wal.awaitDurable(pos);
        if (policy != FsyncPolicy.INTERVAL) {
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
            awaitThenPublish(pos, upto);
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
     * from replicas and catch-up, where most of a peer's state is usually known already. Expired deletes of
     * rows not held here are left out (Store.withoutExpiredDeletes).
     */
    public int merge(List<Op> ops) throws IOException {
        long pos = -1;
        long upto = 0;
        int count;
        lock.writeLock().lock();
        try {
            ensureOpen();
            List<Op> fresh = new ArrayList<>();
            for (Op op : store.withoutExpiredDeletes(ops)) {
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
            awaitThenPublish(pos, upto);
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

    /** Set while opening, before the log is replayed (see startFeed). */
    private volatile ChangeFeed changes;

    /** The change feed, or null if it is not enabled. */
    public ChangeFeed changes() {
        return changes;
    }

    /** Makes the next fsync fail with {@code e}, for tests of the failure path. */
    public void failNextFsyncForTest(IOException e) {
        wal.failNextForceForTest(e);
    }

    /**
     * Sets the store's expiry to {@code cutoffWallMillis} (see Store.expiry: what sync leaves out and
     * merges skip) and, if {@code collect}, drops the row tombstones it covers. Nothing is logged: a restart
     * replays them from the log or snapshot and the next collection drops them again. With a change feed,
     * where in the log they were dropped is recorded first (see FeedFiles), so the replay drops them there.
     *
     * @return the number of tombstones dropped
     * @throws java.io.UncheckedIOException if that record cannot be written; nothing is dropped then
     */
    public int expireTombstones(long cutoffWallMillis, boolean collect) {
        lock.writeLock().lock();
        try {
            ensureOpen();
            store.setExpiry(cutoffWallMillis);
            if (!collect) {
                return 0;
            }
            if (changes != null && store.hasTombstonesBefore(cutoffWallMillis)) {
                Path marks = dir.resolve(MARKS_FILE);
                try {
                    FeedFiles.appendMark(marks, new FeedFiles.Mark(checkpointId, "collect", wal.size(), cutoffWallMillis));
                } catch (IOException e) {
                    // The line may be on disk without its force having succeeded. Without changes.jsonl the next
                    // start begins a new epoch instead of replaying the log with or without this drop; the next
                    // checkpoint writes the file again.
                    try {
                        forgetFeedFiles();
                    } catch (IOException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                    throw new java.io.UncheckedIOException("cannot write " + marks + ", so no tombstones were dropped", e);
                }
            }
            return store.collectTombstones(cutoffWallMillis);
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

    /**
     * Folds the log into a new snapshot and empties it. With a change feed, the feed's last changes go into a new
     * {@link #CHANGES_FILE} first, under the snapshot's new id, and it is renamed into place only once the log is
     * empty. A start after a crash part way tells the steps apart (see startFeed and settleFeed): before the
     * snapshot's rename it uses the old files; after the log is emptied it finishes the rename; in between it
     * cannot tell an old log from a new one and starts a new epoch.
     */
    private void checkpointLocked() throws IOException {
        String id = HexFormat.of().formatHex(randomId());
        ChangeFeed f = changes;
        Path next = dir.resolve(CHANGES_NEXT);
        if (f != null) {
            FeedFiles.write(next, f.epoch(), id, f.last() + 1, f.heldChanges());
            stopForTest(1);
        }
        // If the process dies after the snapshot's rename and before the log is emptied, the log is replayed over
        // the new snapshot on the next start; that is harmless for the data because applying an op twice changes
        // nothing. The ops are written one at a time: a list of them all would need memory a node near its limit
        // does not have.
        Snapshot.writeAtomically(sink -> store.forEachOp(op -> {
            if (Op.legacy(op)) {
                sink.accept(op);
            }
        }), dir.resolve(SNAPSHOT_FILE), java.util.Map.of("checkpoint", id));
        checkpointId = id;
        stopForTest(2);
        wal.reset();
        stopForTest(3);
        if (f != null) {
            Files.move(next, dir.resolve(CHANGES_FILE), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            Snapshot.forceDirectory(dir.toAbsolutePath());
            // Its lines name the old checkpoint, so a crash before this leaves nothing that applies.
            Files.deleteIfExists(dir.resolve(MARKS_FILE));
        }
    }

    private static byte[] randomId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return b;
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** For tests of a crash during a checkpoint: after which step to stop (1 to 3), 0 for none. */
    volatile int stopCheckpointAtForTest;

    private void stopForTest(int step) throws IOException {
        if (stopCheckpointAtForTest == step) {
            throw new IOException("stopped for a test after checkpoint step " + step);
        }
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
                if (policy == FsyncPolicy.OFF && changes != null) {
                    // A clean stop with fsync off: the log is forced once, and the mark says it holds everything
                    // the feed listed, so the next start keeps the epoch (see settleFeed).
                    wal.close(true);
                    FeedFiles.appendMark(dir.resolve(MARKS_FILE), new FeedFiles.Mark(checkpointId, "close", wal.size(), 0));
                } else {
                    wal.close();
                }
            } finally {
                if (dirLock != null) {
                    dirLock.close();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Lets go of the directory as a crash would: nothing more is forced or written. For tests. */
    void abandonForTest() throws IOException {
        lock.writeLock().lock();
        try {
            closed = true;
            if (flusher != null) {
                flusher.shutdownNow();
            }
            wal.abandonForTest();
            if (dirLock != null) {
                dirLock.close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
