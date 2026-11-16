package com.ataiva.serengeti.core.storage;

import com.ataiva.serengeti.core.json.Json;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-state snapshot file, also the backup format. JSON lines: a header object, one op per line, and a
 * trailer with the op count and a SHA-256 over the header and op lines (each with its line feed). A file
 * without a matching trailer is rejected rather than half-loaded, and a checksum that does not match
 * means the file was changed after it was written.
 */
public final class Snapshot {
    public static final String FORMAT = "serengeti-snapshot";
    public static final long FORMAT_VERSION = 1;

    private Snapshot() {}

    /** Where a snapshot's ops come from: something that hands each one to a sink, in order. */
    @FunctionalInterface
    public interface Source {
        void forEach(java.util.function.Consumer<Op> sink);
    }

    public static void write(List<Op> ops, OutputStream out) throws IOException {
        write((Source) ops::forEach, out);
    }

    /** Writes the ops of {@code source} one at a time, so they are never all in memory as a list. */
    public static void write(Source source, OutputStream out) throws IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        MessageDigest md = sha256();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("format", FORMAT);
        header.put("version", FORMAT_VERSION);
        line(w, md, Json.write(header));
        long[] count = {0};
        try {
            source.forEach(op -> {
                try {
                    line(w, md, Json.write(Op.toJson(op)));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                count[0]++;
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        Map<String, Object> trailer = new LinkedHashMap<>();
        trailer.put("end", true);
        trailer.put("ops", count[0]);
        trailer.put("sha256", HexFormat.of().formatHex(md.digest()));
        w.write(Json.write(trailer));
        w.write('\n');
        w.flush();
    }

    private static void line(Writer w, MessageDigest md, String json) throws IOException {
        String text = json + "\n";
        md.update(text.getBytes(StandardCharsets.UTF_8));
        w.write(text);
    }

    /** Reads a snapshot; throws {@link IOException} if it is not a complete, valid snapshot. */
    public static List<Op> read(InputStream in) throws IOException {
        return read(in, false);
    }

    /**
     * Reads a snapshot and checks its checksum. With {@code requireChecksum}, a snapshot without one is refused
     * too; backups are read that way, so a file that lost its checksum cannot be restored.
     */
    public static List<Op> read(InputStream in, boolean requireChecksum) throws IOException {
        List<Op> ops = new java.util.ArrayList<>();
        read(in, requireChecksum, ops::add);
        return ops;
    }

    /**
     * Reads a snapshot, handing each op to {@code sink} as it is read, and returns how many there were. The
     * checksum is checked at the end: if this throws, the ops handed over are not a valid snapshot and whatever
     * was built from them must be discarded.
     */
    public static long read(InputStream in, boolean requireChecksum, java.util.function.Consumer<Op> sink)
            throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        MessageDigest md = sha256();
        String line = r.readLine();
        Map<String, Object> header;
        try {
            header = line == null ? null : Json.parseObject(line);
        } catch (RuntimeException e) {
            header = null;
        }
        if (header == null || !FORMAT.equals(header.get("format"))) {
            throw new IOException("not a Serengeti snapshot");
        }
        if (!Long.valueOf(FORMAT_VERSION).equals(header.get("version"))) {
            throw new IOException("unsupported snapshot version " + header.get("version"));
        }
        md.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        long count = 0;
        long lineNo = 1;
        long skipped = 0;
        while ((line = r.readLine()) != null) {
            lineNo++;
            Map<String, Object> m;
            try {
                m = Json.parseObject(line);
            } catch (RuntimeException e) {
                throw new IOException("snapshot line " + lineNo + ": " + e.getMessage(), e);
            }
            if (Boolean.TRUE.equals(m.get("end"))) {
                if (!Long.valueOf(count + skipped).equals(m.get("ops"))) {
                    throw new IOException("snapshot trailer says " + m.get("ops") + " ops, read " + (count + skipped));
                }
                Object sum = m.get("sha256");
                if (sum == null && requireChecksum) {
                    throw new IOException("snapshot has no checksum");
                }
                if (sum != null && !HexFormat.of().formatHex(md.digest()).equals(sum)) {
                    throw new IOException("snapshot checksum does not match: the file is corrupt or was changed");
                }
                if (r.readLine() != null) {
                    throw new IOException("data after the snapshot trailer");
                }
                return count;
            }
            try {
                Op op = Op.fromJson(m);
                if (op != null) {
                    sink.accept(op);
                    count++;
                } else {
                    skipped++; // a kind from a newer version: it still counts toward the trailer
                }
            } catch (RuntimeException e) {
                throw new IOException("snapshot line " + lineNo + ": " + e.getMessage(), e);
            }
            md.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        throw new IOException("snapshot is truncated (no trailer)");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes to a temporary file, forces it, renames it over {@code target}, and forces the directory. */
    public static void writeAtomically(List<Op> ops, Path target) throws IOException {
        writeAtomically((Source) ops::forEach, target);
    }

    public static void writeAtomically(Source source, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream out = new java.io.BufferedOutputStream(java.nio.channels.Channels.newOutputStream(ch), 1 << 16);
            write(source, out);
            out.flush();
            ch.force(true);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        forceDirectory(target.toAbsolutePath().getParent());
    }

    static void forceDirectory(Path dir) {
        try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
            d.force(true);
        } catch (IOException e) {
            // Not supported on every platform (e.g. Windows); the rename itself is still atomic.
        }
    }
}
