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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-state snapshot file, also the backup format. JSON lines: a header object, one op per line, and a
 * trailer with the op count. A file without a matching trailer is rejected rather than half-loaded.
 */
public final class Snapshot {
    public static final String FORMAT = "serengeti-snapshot";
    public static final long FORMAT_VERSION = 1;

    private Snapshot() {}

    public static void write(List<Op> ops, OutputStream out) throws IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("format", FORMAT);
        header.put("version", FORMAT_VERSION);
        w.write(Json.write(header));
        w.write('\n');
        for (Op op : ops) {
            w.write(Json.write(Op.toJson(op)));
            w.write('\n');
        }
        Map<String, Object> trailer = new LinkedHashMap<>();
        trailer.put("end", true);
        trailer.put("ops", (long) ops.size());
        w.write(Json.write(trailer));
        w.write('\n');
        w.flush();
    }

    /** Reads a snapshot; throws {@link IOException} if it is not a complete, valid snapshot. */
    public static List<Op> read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
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
        List<Op> ops = new java.util.ArrayList<>();
        long lineNo = 1;
        while ((line = r.readLine()) != null) {
            lineNo++;
            Map<String, Object> m;
            try {
                m = Json.parseObject(line);
            } catch (RuntimeException e) {
                throw new IOException("snapshot line " + lineNo + ": " + e.getMessage(), e);
            }
            if (Boolean.TRUE.equals(m.get("end"))) {
                if (!Long.valueOf(ops.size()).equals(m.get("ops"))) {
                    throw new IOException("snapshot trailer says " + m.get("ops") + " ops, read " + ops.size());
                }
                if (r.readLine() != null) {
                    throw new IOException("data after the snapshot trailer");
                }
                return ops;
            }
            try {
                ops.add(Op.fromJson(m));
            } catch (RuntimeException e) {
                throw new IOException("snapshot line " + lineNo + ": " + e.getMessage(), e);
            }
        }
        throw new IOException("snapshot is truncated (no trailer)");
    }

    /** Writes to a temporary file, forces it, renames it over {@code target}, and forces the directory. */
    public static void writeAtomically(List<Op> ops, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream out = java.nio.channels.Channels.newOutputStream(ch);
            write(ops, out);
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
