package com.ataiva.serengeti.core;

import com.ataiva.serengeti.core.config.ConfigException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backups written to a directory on a schedule, the newest {@code keep} of this node's kept.
 *
 * <p>Each backup is written to a hidden temporary file in the directory, forced to disk, and renamed to
 * {@code serengeti-backup-NODE-YYYYMMDDTHHMMSSZ-SEQ.jsonl}, where NODE is the first 8 characters of the node's
 * id and SEQ counts this node's backups, so a file with that name is always whole and nodes can share a
 * directory. Only files with this node's name are ever deleted, the lowest SEQ first (the order they were
 * written in, whatever the clock did), and never the one just written. The time of the last one written
 * decides when the next is due, so a node that restarts more often than the interval still backs up.
 */
final class ScheduledBackups implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ScheduledBackups.class.getName());

    static final String PREFIX = "serengeti-backup-";
    static final String SUFFIX = ".jsonl";
    private static final Pattern NAME =
            Pattern.compile("serengeti-backup-([0-9a-z]{8})-(\\d{8}T\\d{6}Z)-(\\d{6,18})\\.jsonl");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    /** A name this far ahead of the clock is reported: the clock went back, or the file came from elsewhere. */
    static final long FUTURE_MILLIS = 60_000;

    /** Writes a whole backup to the given file, which exists, is empty and is private. */
    interface Writer {
        void write(Path file) throws IOException;
    }

    private final Path dir;
    private final String node;
    private final String tmpPrefix;
    private final long intervalMillis;
    private final int keep;
    private final Writer writer;
    private final BooleanSupplier diskFailed;
    private final LongSupplier clock;
    private final ScheduledExecutorService exec;
    private final AtomicLong failures = new AtomicLong();
    private volatile long lastSuccessMillis;
    private volatile long lastBytes;

    private ScheduledBackups(Path dir, String node, long intervalMillis, int keep, Writer writer,
            BooleanSupplier diskFailed, LongSupplier clock, boolean schedule) {
        this.dir = dir;
        this.node = node;
        this.tmpPrefix = "." + PREFIX + node + "-";
        this.intervalMillis = intervalMillis;
        this.keep = keep;
        this.writer = writer;
        this.diskFailed = diskFailed;
        this.clock = clock;
        this.exec = schedule ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduled-backup");
            t.setDaemon(true);
            return t;
        }) : null;
    }

    /** The part of a node id that names its backups: its first 8 letters and digits, lower case. */
    static String tag(String nodeId) {
        String t = nodeId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^0-9a-z]", "");
        if (t.length() < 8) {
            throw new IllegalArgumentException("a node id needs 8 letters or digits: " + nodeId);
        }
        return t.substring(0, 8);
    }

    /**
     * Opens the directory (made if missing, private), checks that it is not in the data directory once links
     * are followed, removes temporary files this node left, and, with {@code schedule}, checks once a minute
     * whether a backup is due. A directory that cannot be written is a {@link ConfigException}, so the node
     * does not start.
     */
    static ScheduledBackups open(Path dir, Path dataDir, String nodeId, int intervalMinutes, int keep, Writer writer,
            BooleanSupplier diskFailed, LongSupplier clock, boolean schedule) {
        ScheduledBackups b = new ScheduledBackups(dir, tag(nodeId), intervalMinutes * 60_000L, keep, writer, diskFailed,
                clock, schedule);
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                setPrivate(dir, "rwx------");
            }
            if (dataDir != null && dir.toRealPath().startsWith(dataDir.toRealPath())) {
                throw new ConfigException("backup_dir " + dir + " is inside the data directory once links are followed");
            }
            Path probe = Files.createTempFile(dir, b.tmpPrefix, ".tmp");
            Files.delete(probe);
            try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, b.tmpPrefix + "*.tmp")) {
                for (Path p : old) {
                    Files.deleteIfExists(p);
                }
            }
            List<Backup> existing = b.list();
            long newest = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).millis();
            long now = clock.getAsLong();
            if (newest > now + FUTURE_MILLIS) {
                LOG.warning("the newest backup in " + dir + " is named " + Instant.ofEpochMilli(newest) + ", after this"
                        + " node's clock (" + Instant.ofEpochMilli(now) + "); the schedule counts from now");
            }
            b.lastSuccessMillis = Math.min(newest, now);
        } catch (ConfigException e) {
            b.shutdown();
            throw e;
        } catch (IOException | RuntimeException e) {
            b.shutdown();
            throw new ConfigException("backup_dir " + dir + " cannot be written: " + e.getMessage());
        }
        if (b.exec != null) {
            b.exec.scheduleWithFixedDelay(b::runIfDue, 1, 1, TimeUnit.MINUTES);
        }
        return b;
    }

    private void shutdown() {
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    /** A backup file of this node in the directory, the time in its name and its sequence number. */
    record Backup(Path file, long millis, long seq) {}

    /**
     * This node's backups in the directory, oldest first by sequence number: the order they were written in,
     * whatever the clock did meanwhile.
     */
    List<Backup> list() throws IOException {
        List<Backup> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, PREFIX + node + "-*" + SUFFIX)) {
            for (Path p : s) {
                Matcher m = NAME.matcher(p.getFileName().toString());
                if (m.matches() && m.group(1).equals(node) && Files.isRegularFile(p)) {
                    try {
                        out.add(new Backup(p, Instant.from(STAMP.parse(m.group(2))).toEpochMilli(),
                                Long.parseLong(m.group(3))));
                    } catch (DateTimeParseException | NumberFormatException e) {
                        // not one of ours: left alone
                    }
                }
            }
        }
        out.sort((x, y) -> Long.compare(x.seq(), y.seq()));
        return out;
    }

    /**
     * Writes a backup if the interval has passed since the newest one, or if the clock is now before it (it
     * went back): then the newest name says nothing about the current data.
     */
    void runIfDue() {
        try {
            long elapsed = clock.getAsLong() - lastSuccessMillis;
            if (elapsed >= intervalMillis || elapsed < 0) {
                runNow();
            }
        } catch (IOException | RuntimeException e) {
            // runNow has logged and counted it; the next check tries again.
        }
    }

    /** Writes a backup now and deletes this node's oldest beyond {@code keep}, never this one. Returns the file. */
    Path runNow() throws IOException {
        long now = clock.getAsLong();
        Path tmp = null;
        try {
            if (diskFailed.getAsBoolean()) {
                throw new IOException("a write to the data directory failed; the node needs a restart");
            }
            tmp = Files.createTempFile(dir, tmpPrefix, ".tmp");
            setPrivate(tmp, "rw-------");
            writer.write(tmp);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Path target = freeName(now);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
            forceDirectory();
            lastSuccessMillis = now;
            lastBytes = Files.size(target);
            prune(target);
            LOG.info("backup " + target.getFileName() + " written, " + lastBytes + " bytes");
            return target;
        } catch (IOException | RuntimeException e) {
            failures.incrementAndGet();
            LOG.log(Level.WARNING, "scheduled backup into " + dir + " failed: " + e.getMessage());
            throw e;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "cannot remove " + tmp + ": " + e.getMessage());
                }
            }
        }
    }

    /** The name of the next backup: the time, and one more than the highest sequence number of this node's. */
    private Path freeName(long now) throws IOException {
        List<Backup> all = list();
        long seq = all.isEmpty() ? 1 : all.get(all.size() - 1).seq() + 1;
        Path p = dir.resolve(String.format(java.util.Locale.ROOT, "%s%s-%s-%06d%s", PREFIX, node,
                STAMP.format(Instant.ofEpochMilli(now)), seq, SUFFIX));
        if (Files.exists(p)) {
            throw new FileAlreadyExistsException(p.toString());
        }
        return p;
    }

    /** Keeps {@code written} and the {@code keep - 1} others written last, by sequence number. */
    private void prune(Path written) throws IOException {
        List<Backup> others = new ArrayList<>();
        for (Backup b : list()) {
            if (!b.file().equals(written)) {
                others.add(b);
            }
        }
        for (int i = 0; i < others.size() - (keep - 1); i++) {
            Files.deleteIfExists(others.get(i).file());
        }
    }

    /** Makes the rename durable: on Linux a directory can be opened and forced; elsewhere this is skipped. */
    private void forceDirectory() {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            // Not every platform can open a directory; the rename is still atomic.
        }
    }

    private static void setPrivate(Path p, String perms) throws IOException {
        if (p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        }
    }

    long intervalSeconds() {
        return intervalMillis / 1000;
    }

    /**
     * Unix seconds of the newest backup: written by this run, or found at start (and then not after the
     * clock); 0: none.
     */
    long lastSuccessSeconds() {
        return lastSuccessMillis / 1000;
    }

    long failures() {
        return failures.get();
    }

    long lastBytes() {
        return lastBytes;
    }

    @Override
    public void close() {
        if (exec == null) {
            return;
        }
        // An interrupted write stops at once (the file's channel is closed) and its temporary file is removed.
        exec.shutdownNow();
        try {
            exec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
