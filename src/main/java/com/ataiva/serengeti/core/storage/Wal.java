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
    public record Recovered(List<List<Op>> batches, long validBytes, long discardedBytes) {}

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
     * Opens or creates the log, returning it with the batches it held. A torn or corrupt tail is
     * truncated and reported in {@link Recovered#discardedBytes()}.
     */
    public static Wal open(Path path, FsyncPolicy policy, long testFsyncDelayMs, List<List<Op>> out)
            throws IOException {
        FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        Recovered r = read(ch);
        out.addAll(r.batches());
        if (r.discardedBytes() > 0) {
            LOG.warning("write-ahead log " + path + ": discarded " + r.discardedBytes()
                    + " bytes of incomplete or corrupt tail after " + r.validBytes() + " good bytes");
            ch.truncate(r.validBytes());
            ch.force(true);
        }
        ch.position(r.validBytes());
        return new Wal(path, ch, policy, testFsyncDelayMs, r.validBytes());
    }

    static Recovered read(FileChannel ch) throws IOException {
        long total = ch.size();
        List<List<Op>> batches = new ArrayList<>();
        long pos = 0;
        ByteBuffer header = ByteBuffer.allocate(HEADER);
        while (pos + HEADER <= total) {
            header.clear();
            readFully(ch, header, pos);
            header.flip();
            int len = header.getInt();
            int crc = header.getInt();
            if (len < 0 || len > MAX_RECORD || pos + HEADER + len > total) {
                break;
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
                break;
            }
            batches.add(batch);
            pos += HEADER + len;
        }
        return new Recovered(batches, pos, total - pos);
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

    /** Appends one batch. With {@link FsyncPolicy#ALWAYS} it is on disk when this returns. */
    public synchronized void append(List<Op> batch) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(encode(batch));
        while (b.hasRemaining()) {
            ch.write(b);
        }
        size = ch.position();
        if (policy == FsyncPolicy.ALWAYS) {
            force();
        } else {
            dirty = true;
        }
    }

    /** For {@link FsyncPolicy#INTERVAL}: called by a timer. */
    public synchronized void flushIfDirty() throws IOException {
        if (dirty && ch.isOpen()) {
            force();
        }
    }

    private void force() throws IOException {
        long started = System.nanoTime();
        if (testFsyncDelayMs > 0) {
            try {
                Thread.sleep(testFsyncDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ch.force(false);
        dirty = false;
        fsyncs.incrementAndGet();
        fsyncNanos.addAndGet(System.nanoTime() - started);
    }

    /** The log's size and its forces so far. Two clock reads per force; nothing is counted per record. */
    public synchronized Stats stats() {
        return new Stats(size, fsyncs.get(), fsyncNanos.get());
    }

    /** Empties the log, after a snapshot made its contents redundant. */
    public synchronized void reset() throws IOException {
        ch.truncate(0);
        ch.position(0);
        ch.force(true);
        size = 0;
        dirty = false;
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
