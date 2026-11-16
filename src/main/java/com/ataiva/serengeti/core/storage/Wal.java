package com.ataiva.serengeti.core.storage;

import com.ataiva.serengeti.core.json.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.zip.CRC32C;

/**
 * Write-ahead log. Each record is one batch of ops: a 4-byte big-endian payload length, a 4-byte
 * CRC32C of the payload, and the payload (UTF-8 JSON array of ops). On open, records are read until
 * the first one that is short or fails its checksum; the file is truncated there, because that tail
 * is a write that never completed.
 */
public final class Wal implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Wal.class.getName());
    static final int HEADER = 8;
    static final int MAX_RECORD = 64 * 1024 * 1024;

    private final Path path;
    private final FileChannel ch;
    private final FsyncPolicy policy;
    private final long testFsyncDelayMs;
    private long size;
    private volatile boolean dirty;
    private final AtomicLong fsyncs = new AtomicLong();
    private final AtomicLong fsyncNanos = new AtomicLong();

    /** Result of reading the log on open. */
    public record Recovered(List<List<Op>> batches, long validBytes, long discardedBytes, long records) {}

    /** Counters for {@code GET /metrics}: the log's size, and the forces done on it since the node started. */
    public record Stats(long bytes, long fsyncs, long fsyncNanos) {}

    private Wal(Path path, FileChannel ch, FsyncPolicy policy, long testFsyncDelayMs, long size) {
        this.path = path;
        this.ch = ch;
        this.policy = policy;
        this.testFsyncDelayMs = testFsyncDelayMs;
        this.size = size;
    }

    /**
     * Opens or creates the log, returning it with the batches it held. A torn or corrupt tail (a short
     * record, or one whose checksum does not match) is truncated and reported in
     * {@link Recovered#discardedBytes()}. A record with a valid checksum that cannot be parsed is not a
     * torn write: opening fails with an IOException and nothing is truncated.
     */
    public static Wal open(Path path, FsyncPolicy policy, long testFsyncDelayMs, List<List<Op>> out)
            throws IOException {
        return open(path, policy, testFsyncDelayMs, (java.util.function.Consumer<List<Op>>) out::add);
    }

    /**
     * As {@link #open(Path, FsyncPolicy, long, List)}, handing each batch to {@code sink} as it is read, so the
     * log is never held in memory whole. If opening then fails, the batches already handed over are not
     * known to be the whole log: the caller must discard what it built from them.
     */
    public static Wal open(Path path, FsyncPolicy policy, long testFsyncDelayMs,
            java.util.function.Consumer<List<Op>> sink) throws IOException {
        FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        Recovered r;
        try {
            r = read(ch, sink);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
        if (r.discardedBytes() > 0) {
            LOG.warning("write-ahead log " + path + ": discarded " + r.discardedBytes()
                    + " bytes of incomplete or corrupt tail after " + r.validBytes() + " good bytes");
            ch.truncate(r.validBytes());
            ch.force(true);
        }
        ch.position(r.validBytes());
        Wal w = new Wal(path, ch, policy, testFsyncDelayMs, r.validBytes());
        w.recordsAtOpen = r.records();
        return w;
    }

    static Recovered read(FileChannel ch) throws IOException {
        List<List<Op>> batches = new ArrayList<>();
        Recovered r = read(ch, batches::add);
        return new Recovered(batches, r.validBytes(), r.discardedBytes(), r.records());
    }

    /** Reads the log, handing each batch to {@code sink}; the result's batch list is empty. */
    static Recovered read(FileChannel ch, java.util.function.Consumer<List<Op>> sink) throws IOException {
        long total = ch.size();
        long pos = 0;
        long records = 0;
        ByteBuffer header = ByteBuffer.allocate(HEADER);
        while (pos + HEADER <= total) {
            header.clear();
            readFully(ch, header, pos);
            header.flip();
            int len = header.getInt();
            int crc = header.getInt();
            // A payload is at least "[]": a shorter one is a zero-filled or torn tail (the CRC32C of an empty
            // payload is 0, so eight zero bytes would otherwise pass the checksum).
            if (len < 2 || len > MAX_RECORD || pos + HEADER + len > total) {
                break;
            }
            if (len > STREAM_RECORD) {
                // A large record (one statement over many rows) is never held whole: its checksum is checked
                // reading it in pieces, then its ops are parsed and handed over a part at a time. Parsing it at
                // once took several times its size, and a full node could not start again after a big DELETE.
                if (!checksumMatches(ch, pos + HEADER, len, crc)) {
                    break;
                }
                try {
                    streamOps(ch, pos + HEADER, len, sink);
                } catch (RuntimeException e) {
                    throw new IOException("write-ahead log record at byte " + pos + " has a valid checksum but cannot be"
                            + " read (" + e.getMessage() + "). It may have been written by a newer version of"
                            + " Serengeti. Nothing was discarded; start that version, or move the data directory aside.", e);
                }
                pos += HEADER + len;
                records++;
                continue;
            }
            ByteBuffer payload = ByteBuffer.allocate(len);
            readFully(ch, payload, pos + HEADER);
            byte[] bytes = payload.array();
            CRC32C c = new CRC32C();
            c.update(bytes, 0, len);
            if ((int) c.getValue() != crc) {
                break;
            }
            List<Op> batch;
            try {
                batch = Op.listFromJson(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
            } catch (RuntimeException e) {
                // The checksum matched, so this is not a torn write: discarding it, and every record after
                // it, would throw away acknowledged writes. Refuse to open instead.
                throw new IOException("write-ahead log record at byte " + pos + " has a valid checksum but cannot be"
                        + " read (" + e.getMessage() + "). It may have been written by a newer version of"
                        + " Serengeti. Nothing was discarded; start that version, or move the data directory aside.", e);
            }
            sink.accept(batch);
            pos += HEADER + len;
            records++;
        }
        return new Recovered(List.of(), pos, total - pos, records);
    }

    /** Records larger than this are read in pieces (see {@link #read(FileChannel, java.util.function.Consumer)}). */
    static final int STREAM_RECORD = 1 << 20;
    /** A large record's ops reach the sink in parts of this many. Applying them in parts or at once is the same. */
    static final int STREAM_PART = 1000;

    private static boolean checksumMatches(FileChannel ch, long at, int len, int crc) throws IOException {
        CRC32C c = new CRC32C();
        ByteBuffer buf = ByteBuffer.allocate(1 << 16);
        long left = len;
        while (left > 0) {
            buf.clear();
            buf.limit((int) Math.min(left, buf.capacity()));
            readFully(ch, buf, at);
            buf.flip();
            c.update(buf);
            at += buf.limit();
            left -= buf.limit();
        }
        return (int) c.getValue() == crc;
    }

    /**
     * Parses a record's JSON array {@code [op,op,...]} one element at a time and hands the ops to the sink in
     * parts. The elements are JSON objects as {@link Json#write} makes them; strings are tracked so that braces
     * and brackets inside them do not count.
     */
    private static void streamOps(FileChannel ch, long at, int len, java.util.function.Consumer<List<Op>> sink)
            throws IOException {
        // Positional reads of this record only: the log's channel is not moved, and never closed here.
        java.io.Reader r = new java.io.InputStreamReader(new RegionStream(ch, at, at + len), StandardCharsets.UTF_8);
        if (next(r) != '[') {
            throw new IllegalArgumentException("expected a list of ops");
        }
        List<Op> part = new ArrayList<>(STREAM_PART);
        StringBuilder el = new StringBuilder();
        boolean first = true;
        for (;;) {
            int c = next(r);
            if (c == ']' && first) {
                break; // []
            }
            if (c != '{') {
                throw new IllegalArgumentException("expected an op object");
            }
            el.setLength(0);
            el.append('{');
            int depth = 1;
            boolean inString = false;
            while (depth > 0) {
                int d = next(r);
                el.append((char) d);
                if (inString) {
                    if (d == '\\') {
                        el.append((char) next(r));
                    } else if (d == '"') {
                        inString = false;
                    }
                } else if (d == '"') {
                    inString = true;
                } else if (d == '{' || d == '[') {
                    depth++;
                } else if (d == '}' || d == ']') {
                    depth--;
                }
            }
            Op op = Op.fromJson(Json.parseObject(el.toString()));
            if (op != null) {
                part.add(op);
            }
            if (part.size() == STREAM_PART) {
                sink.accept(part);
                part = new ArrayList<>(STREAM_PART);
            }
            first = false;
            int sep = next(r);
            if (sep == ']') {
                break;
            }
            if (sep != ',') {
                throw new IllegalArgumentException("expected ',' or ']' after an op");
            }
        }
        if (r.read() >= 0) {
            throw new IllegalArgumentException("data after the list of ops");
        }
        if (!part.isEmpty()) {
            sink.accept(part);
        }
    }

    private static int next(java.io.Reader r) throws IOException {
        int c = r.read();
        if (c < 0) {
            throw new IllegalArgumentException("the record ends early");
        }
        return c;
    }

    /** Bytes {@code [from, to)} of a file, read with positional reads 64 KiB at a time. */
    private static final class RegionStream extends java.io.InputStream {
        private final FileChannel ch;
        private long pos;
        private final long end;
        private final ByteBuffer buf = ByteBuffer.allocate(1 << 16);

        RegionStream(FileChannel ch, long from, long to) {
            this.ch = ch;
            this.pos = from;
            this.end = to;
            buf.limit(0);
        }

        @Override
        public int read() throws IOException {
            if (!fill()) {
                return -1;
            }
            return buf.get() & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int n) throws IOException {
            if (n == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int k = Math.min(n, buf.remaining());
            buf.get(b, off, k);
            return k;
        }

        private boolean fill() throws IOException {
            if (buf.hasRemaining()) {
                return true;
            }
            if (pos >= end) {
                return false;
            }
            buf.clear();
            buf.limit((int) Math.min(buf.capacity(), end - pos));
            readFully(ch, buf, pos);
            pos += buf.limit();
            buf.flip();
            return true;
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long at) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf, at + buf.position());
            if (n < 0) {
                throw new IOException("unexpected end of file");
            }
        }
    }

    static byte[] encode(List<Op> batch) {
        byte[] payload = Json.write(Op.toJson(batch)).getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_RECORD) {
            throw new IllegalArgumentException("batch larger than " + MAX_RECORD + " bytes");
        }
        CRC32C c = new CRC32C();
        c.update(payload, 0, payload.length);
        ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length);
        b.putInt(payload.length).putInt((int) c.getValue()).put(payload);
        return b.array();
    }

    /** Appends one batch and forces it before returning ({@link #write} then {@link #awaitDurable}). */
    public void append(List<Op> batch) throws IOException {
        awaitDurable(write(batch));
    }

    /**
     * Writes one batch to the file without forcing it, and returns a position to pass to
     * {@link #awaitDurable}. Callers write under their own lock and wait outside it, so that writers who
     * arrive while one force is running share the next force (group commit).
     */
    public synchronized long write(List<Op> batch) throws IOException {
        checkHealthy();
        // The payload is encoded one op at a time into one reused buffer, with its checksum computed as it
        // goes, and the header slot at the front of the buffer. A record that fits (nearly all) is written with
        // one call. A larger one is written as the buffer fills, header slot zero, and its header goes in
        // last: a statement that deletes a few hundred thousand rows must not need its whole record in memory
        // several times over. Until the header is written, the record reads as a torn tail (length 0), so a
        // crash part way leaves nothing that recovery would take.
        long start = ch.position();
        ByteBuffer buf = writeBuffer;
        buf.clear();
        buf.put(new byte[HEADER]);
        CRC32C crc = new CRC32C();
        long len = 0;
        boolean streamed = false;
        try {
            for (int i = 0; i <= batch.size() + 1; i++) {
                byte[] bytes = i == 0 ? OPEN : i == batch.size() + 1 ? CLOSE
                        : Json.write(Op.toJson(batch.get(i - 1))).getBytes(StandardCharsets.UTF_8);
                if (i > 1 && i <= batch.size()) {
                    streamed |= put(buf, crc, COMMA);
                    len += 1;
                }
                streamed |= put(buf, crc, bytes);
                len += bytes.length;
                if (len > MAX_RECORD) {
                    throw new IllegalArgumentException("batch larger than " + MAX_RECORD + " bytes");
                }
            }
            if (!streamed) {
                buf.putInt(0, (int) len).putInt(4, (int) crc.getValue());
                drain(buf);
            } else {
                drain(buf);
                ByteBuffer header = ByteBuffer.allocate(HEADER);
                header.putInt((int) len).putInt((int) crc.getValue()).flip();
                while (header.hasRemaining()) {
                    ch.write(header, start + header.position());
                }
            }
        } catch (IOException | RuntimeException e) {
            // Nothing of this batch may stay: the next write goes where it began.
            try {
                ch.truncate(start);
                ch.position(start);
            } catch (IOException suppressed) {
                // Part of the batch may stay in the file, and a record written after it would be lost behind it
                // on recovery: the log takes no more writes.
                e.addSuppressed(suppressed);
                failure = new IOException("a failed write could not be taken back: " + suppressed.getMessage(), suppressed);
            }
            throw e;
        }
        size = ch.position();
        written += HEADER + len;
        dirty = true;
        return written;
    }

    private static final byte[] OPEN = {'['};
    private static final byte[] CLOSE = {']'};
    private static final byte[] COMMA = {','};
    /** Reused by {@link #write}, which is synchronized. */
    private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(1 << 16);

    /** Appends to the buffer, writing it out when full. True if it wrote any of it out. */
    private boolean put(ByteBuffer buf, CRC32C crc, byte[] bytes) throws IOException {
        crc.update(bytes, 0, bytes.length);
        boolean out = false;
        int off = 0;
        while (off < bytes.length) {
            if (!buf.hasRemaining()) {
                drain(buf);
                out = true;
            }
            int n = Math.min(buf.remaining(), bytes.length - off);
            buf.put(bytes, off, n);
            off += n;
        }
        return out;
    }

    private void drain(ByteBuffer buf) throws IOException {
        buf.flip();
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
        buf.clear();
    }

    /**
     * Returns once everything up to {@code position} is on disk (with {@link FsyncPolicy#ALWAYS}; other
     * policies return at once). One caller forces everything written so far; callers waiting behind it
     * usually find their write already covered. A failed force is permanent: after it, every write and
     * wait throws, because the kernel may have dropped the unwritten pages and a later force that
     * succeeds would not bring them back.
     */
    public void awaitDurable(long position) throws IOException {
        if (policy != FsyncPolicy.ALWAYS) {
            return;
        }
        // Leader and followers: one caller forces, outside the monitor; the others wait on it and wake when
        // it is done, so a caller whose write that force covered returns without a force of its own.
        while (true) {
            synchronized (syncLock) {
                checkHealthy();
                if (durable >= position) {
                    return;
                }
                if (forcing) {
                    try {
                        syncLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while waiting for fsync", e);
                    }
                    continue;
                }
                forcing = true;
            }
            forceAsLeader();
        }
    }

    private void forceAsLeader() throws IOException {
        long covered = -1;
        try {
            covered = forceNow();
        } finally {
            synchronized (syncLock) {
                forcing = false;
                if (covered >= 0) {
                    durable = Math.max(durable, covered);
                }
                syncLock.notifyAll();
            }
        }
    }

    /** For {@link FsyncPolicy#INTERVAL}: called by a timer. */
    public void flushIfDirty() throws IOException {
        synchronized (syncLock) {
            if (!dirty || !ch.isOpen() || failure != null || forcing) {
                return;
            }
            forcing = true;
        }
        forceAsLeader();
    }

    private boolean forcing;

    private final Object syncLock = new Object();
    /** Bytes written since the log was opened, across resets; positions handed out by {@link #write}. */
    private long written;
    /** Records read when the log was opened (a large one reaches the sink in several parts). */
    private long recordsAtOpen;

    public long recordsAtOpen() {
        return recordsAtOpen;
    }
    private volatile long durable;
    private volatile IOException failure;

    private volatile IOException injectedFailure;

    void failNextForceForTest(IOException e) {
        injectedFailure = e;
    }

    /** True once a force has failed: the log no longer says what is on disk, and the node must restart. */
    public boolean failed() {
        return failure != null;
    }

    private void checkHealthy() throws IOException {
        IOException f = failure;
        if (f != null) {
            throw new IOException("the write-ahead log is unusable after a failed fsync: " + f.getMessage(), f);
        }
    }

    /** Forces the file and returns the position it covers: everything written before the force began. */
    private long forceNow() throws IOException {
        long started = System.nanoTime();
        if (testFsyncDelayMs > 0) {
            try {
                Thread.sleep(testFsyncDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long covered;
        synchronized (this) {
            covered = written;
        }
        try {
            dirty = false;
            IOException injected = injectedFailure;
            if (injected != null) {
                injectedFailure = null;
                throw injected;
            }
            ch.force(false);
        } catch (IOException e) {
            failure = e;
            LOG.severe("fsync of " + path + " failed; refusing further writes: " + e);
            throw e;
        }
        fsyncs.incrementAndGet();
        fsyncNanos.addAndGet(System.nanoTime() - started);
        return covered;
    }

    /** The log's size and its forces so far. Two clock reads per force; nothing is counted per record. */
    public synchronized Stats stats() {
        return new Stats(size, fsyncs.get(), fsyncNanos.get());
    }

    /** Empties the log, after a snapshot made its contents redundant. */
    public void reset() throws IOException {
        // The caller has just written and forced a snapshot that holds everything in the log, so all
        // positions handed out so far are durable through it.
        synchronized (syncLock) {
            synchronized (this) {
                ch.truncate(0);
                ch.position(0);
                ch.force(true);
                size = 0;
                dirty = false;
                durable = written;
            }
            syncLock.notifyAll();
        }
    }

    public synchronized long size() {
        return size;
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void close() throws IOException {
        if (ch.isOpen()) {
            if (dirty && policy != FsyncPolicy.OFF) {
                ch.force(false);
            }
            ch.close();
        }
    }
}
