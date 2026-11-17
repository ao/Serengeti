package com.ataiva.serengeti.core.storage;

import com.ataiva.serengeti.core.json.Json;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The two files that let the change feed outlive a restart (see StorageEngine). The log already holds every op
 * in the order it was applied, so replaying it at start gives the changes of its records again, with the same
 * numbers; these files hold only what the log cannot give.
 *
 * <p>{@code changes.jsonl} is written with each checkpoint, which folds the log into the snapshot and empties
 * it. It holds the feed's epoch, the checkpoint id that the snapshot's header carries too (so the file is used
 * only with the snapshot it was written with), the number the log's first change gets, and the last changes the
 * feed held, which the emptied log no longer has. JSON lines: a header, one change per line, and a trailer with
 * the count and a SHA-256 over the lines before it, as in a snapshot.
 *
 * <p>{@code changes-marks.jsonl} is appended between checkpoints. Dropping tombstones is not logged, and a row
 * whose tombstone was dropped takes an older write that its tombstone would have hidden, so the replay drops
 * them at the same place: a {@code collect} line holds the log offset and cutoff, and is forced before anything
 * is dropped. With {@code fsync} off, {@code open} and {@code close} lines tell a clean stop from a crash. Each
 * line names the checkpoint it belongs to; lines of an earlier one are ignored.
 */
final class FeedFiles {
    private static final Logger LOG = Logger.getLogger(FeedFiles.class.getName());
    static final String FORMAT = "serengeti-changes";
    static final long VERSION = 1;

    private FeedFiles() {}

    /** A feed read back from {@code changes.jsonl}, and the number its log's first change gets. */
    record Loaded(ChangeFeed feed, long next) {}

    /** One line of {@code changes-marks.jsonl}: {@code kind} is "collect", "open" or "close". */
    record Mark(String checkpoint, String kind, long at, long cutoff) {}

    /** The marks of one checkpoint, and whether a line that is not the last one could not be read. */
    record Marks(List<Mark> marks, boolean damaged) {}

    /**
     * Writes the feed's state to {@code file}, then forces the file and its directory, so the file is there
     * after a crash. The caller renames it into place.
     */
    static void write(Path file, String epoch, String checkpoint, long next, List<ChangeFeed.Change> changes)
            throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            Writer w = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8),
                    1 << 16);
            MessageDigest md = sha256();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("format", FORMAT);
            header.put("version", VERSION);
            header.put("epoch", epoch);
            header.put("checkpoint", checkpoint);
            header.put("next", next);
            line(w, md, Json.write(header));
            for (ChangeFeed.Change c : changes) {
                line(w, md, Json.write(toJson(c)));
            }
            Map<String, Object> trailer = new LinkedHashMap<>();
            trailer.put("end", true);
            trailer.put("changes", (long) changes.size());
            trailer.put("sha256", HexFormat.of().formatHex(md.digest()));
            w.write(Json.write(trailer));
            w.write('\n');
            w.flush();
            ch.force(true);
        }
        Snapshot.forceDirectory(file.toAbsolutePath().getParent());
    }

    /**
     * Reads {@code file} into a new feed of {@code capacity} changes and {@code maxBytes}, if it is whole and was
     * written with checkpoint {@code checkpoint}. Returns null if the file is missing, belongs to another
     * checkpoint, or is not whole (that is logged). Changes beyond the feed's bounds, which may be smaller than
     * when the file was written, are dropped oldest first as they are read.
     */
    static Loaded read(Path file, String checkpoint, int capacity, long maxBytes) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8), 1 << 16)) {
            MessageDigest md = sha256();
            String line = r.readLine();
            Map<String, Object> h = line == null ? null : Json.parseObject(line);
            if (h == null || !FORMAT.equals(h.get("format")) || !Long.valueOf(VERSION).equals(h.get("version"))) {
                LOG.warning(file + " is not a change feed file of this version; the feed starts a new epoch");
                return null;
            }
            if (!checkpoint.equals(h.get("checkpoint"))) {
                return null;
            }
            String epoch = (String) h.get("epoch");
            long next = (Long) h.get("next");
            md.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            ChangeFeed feed = new ChangeFeed(capacity, maxBytes, false, epoch, next);
            long count = 0;
            while ((line = r.readLine()) != null) {
                Map<String, Object> m = Json.parseObject(line);
                if (Boolean.TRUE.equals(m.get("end"))) {
                    if (!Long.valueOf(count).equals(m.get("changes"))
                            || !HexFormat.of().formatHex(md.digest()).equals(m.get("sha256")) || r.readLine() != null) {
                        throw new IOException("its trailer does not match its changes");
                    }
                    if (feed.last() != next - 1) {
                        throw new IOException("its last change is " + feed.last() + ", not " + (next - 1));
                    }
                    return new Loaded(feed, next);
                }
                feed.restore(fromJson(m));
                count++;
                md.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            throw new IOException("it has no trailer");
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            LOG.warning("cannot read " + file + " (" + e.getMessage() + "); the change feed starts a new epoch");
            return null;
        }
    }

    private static Map<String, Object> toJson(ChangeFeed.Change c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", c.seq());
        m.put("kind", c.kind());
        m.put("db", c.db());
        m.put("table", c.table());
        m.put("id", c.id());
        m.put("row", c.row());
        m.put("at", c.stamp().toString());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static ChangeFeed.Change fromJson(Map<String, Object> m) {
        Object seq = m.get("seq");
        Object kind = m.get("kind");
        Object db = m.get("db");
        Object at = m.get("at");
        if (!(seq instanceof Long s) || s < 1 || !(kind instanceof String k) || !(db instanceof String d)
                || !(at instanceof String a)) {
            throw new IllegalArgumentException("a change needs seq, kind, db and at");
        }
        Object table = m.get("table");
        Object id = m.get("id");
        Object row = m.get("row");
        return new ChangeFeed.Change(s, k, d, (String) table, (String) id, (Map<String, Object>) row, Stamp.parse(a));
    }

    /**
     * Reads the marks of {@code checkpoint}. A last line that is not whole is an append that never finished, and
     * is left out (nothing was dropped for it); any other line that cannot be read makes the result damaged.
     */
    static Marks readMarks(Path file, String checkpoint) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new Marks(List.of(), false);
        } catch (IOException | RuntimeException e) {
            LOG.warning("cannot read " + file + ": " + e.getMessage());
            return new Marks(List.of(), true);
        }
        List<Mark> out = new ArrayList<>();
        boolean damaged = false;
        for (int i = 0; i < lines.size(); i++) {
            try {
                Map<String, Object> m = Json.parseObject(lines.get(i));
                String kind = (String) m.get("mark");
                if (!List.of("collect", "open", "close").contains(kind)) {
                    throw new IllegalArgumentException("unknown mark " + kind);
                }
                if (checkpoint != null && checkpoint.equals(m.get("checkpoint"))) {
                    out.add(new Mark(checkpoint, kind, number(m.get("at")), number(m.get("cutoff"))));
                }
            } catch (RuntimeException e) {
                if (i < lines.size() - 1) {
                    LOG.warning(file + " line " + (i + 1) + " cannot be read: " + e.getMessage());
                    damaged = true;
                }
            }
        }
        return new Marks(out, damaged);
    }

    private static long number(Object v) {
        return v instanceof Long l ? l : 0;
    }

    /** Appends {@code m} and forces it, with the directory when the file is new. */
    static void appendMark(Path file, Mark m) throws IOException {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("checkpoint", m.checkpoint());
        j.put("mark", m.kind());
        if (!m.kind().equals("open")) {
            j.put("at", m.at());
        }
        if (m.kind().equals("collect")) {
            j.put("cutoff", m.cutoff());
        }
        boolean fresh = !Files.exists(file);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            // After an append that a crash cut off, the file does not end its last line. That line was skipped as
            // torn (nothing was dropped for it): cut it off first, or this mark would be glued to it and skipped
            // as torn too at the next start (review 22f).
            long whole = endOfLastWholeLine(ch);
            if (whole < ch.size()) {
                ch.truncate(whole);
                ch.force(true);
            }
            ByteBuffer b = ByteBuffer.wrap((Json.write(j) + "\n").getBytes(StandardCharsets.UTF_8));
            long at = whole;
            while (b.hasRemaining()) {
                at += ch.write(b, at);
            }
            ch.force(true);
        }
        if (fresh) {
            Snapshot.forceDirectory(file.toAbsolutePath().getParent());
        }
    }

    /** The length of the file up to and including its last line feed (0 if it has none). */
    static long endOfLastWholeLine(FileChannel ch) throws IOException {
        long pos = ch.size();
        ByteBuffer buf = ByteBuffer.allocate(4096);
        while (pos > 0) {
            int n = (int) Math.min(buf.capacity(), pos);
            buf.clear().limit(n);
            long from = pos - n;
            while (buf.hasRemaining()) {
                if (ch.read(buf, from + buf.position()) < 0) {
                    break;
                }
            }
            for (int i = n - 1; i >= 0; i--) {
                if (buf.get(i) == '\n') {
                    return from + i + 1;
                }
            }
            pos = from;
        }
        return 0;
    }

    private static void line(Writer w, MessageDigest md, String json) throws IOException {
        String text = json + "\n";
        md.update(text.getBytes(StandardCharsets.UTF_8));
        w.write(text);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
