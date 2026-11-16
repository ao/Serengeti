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
import java.util.Collections;
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
 * Backups written to a directory on a schedule, the newest {@code keep} of them kept.
 *
 * <p>Each backup is written to a hidden temporary file in the directory, forced to disk, and renamed to
 * {@code serengeti-backup-YYYYMMDDTHHMMSSZ.jsonl}, so a file with that name is always whole. Only files with
 * that name are ever deleted. The time of the newest one decides when the next is due, so a node that
 * restarts more often than the interval still backs up.
 */
final class ScheduledBackups implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ScheduledBackups.class.getName());

    static final String PREFIX = "serengeti-backup-";
    static final String SUFFIX = ".jsonl";
    private static final String TMP_PREFIX = "." + PREFIX;
    private static final Pattern NAME = Pattern.compile("serengeti-backup-(\\d{8}T\\d{6}Z)(?:-(\\d{1,4}))?\\.jsonl");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** Writes a whole backup to the given file, which exists, is empty and is private. */
    interface Writer {
        void write(Path file) throws IOException;
    }

    private final Path dir;
    private final long intervalMillis;
    private final int keep;
    private final Writer writer;
    private final BooleanSupplier diskFailed;
    private final LongSupplier clock;
    private final ScheduledExecutorService exec;
    private final AtomicLong failures = new AtomicLong();
    private volatile long lastSuccessMillis;
    private volatile long lastBytes;

    private ScheduledBackups(Path dir, long intervalMillis, int keep, Writer writer, BooleanSupplier diskFailed,
            LongSupplier clock, boolean schedule) {
        this.dir = dir;
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

    /**
     * Opens the directory (made if missing, private), removes temporary files a stopped node left, and, with
     * {@code schedule}, checks once a minute whether a backup is due. A directory that cannot be written is a
     * {@link ConfigException}, so the node does not start.
     */
    static ScheduledBackups open(Path dir, int intervalMinutes, int keep, Writer writer, BooleanSupplier diskFailed,
            LongSupplier clock, boolean schedule) {
        ScheduledBackups b = new ScheduledBackups(dir, intervalMinutes * 60_000L, keep, writer, diskFailed, clock,
                schedule);
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                setPrivate(dir, "rwx------");
            }
            Path probe = Files.createTempFile(dir, TMP_PREFIX, ".tmp");
            Files.delete(probe);
            try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, TMP_PREFIX + "*.tmp")) {
                for (Path p : old) {
                    Files.deleteIfExists(p);
                }
            }
            List<Backup> existing = b.list();
            b.lastSuccessMillis = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).millis();
        } catch (IOException | RuntimeException e) {
            if (b.exec != null) {
                b.exec.shutdownNow();
            }
            throw new ConfigException("backup_dir " + dir + " cannot be written: " + e.getMessage());
        }
        if (b.exec != null) {
            b.exec.scheduleWithFixedDelay(b::runIfDue, 1, 1, TimeUnit.MINUTES);
        }
        return b;
    }

    /** A backup file in the directory and the time in its name. */
    record Backup(Path file, long millis) {}

    /** The backups in the directory, oldest first. */
    List<Backup> list() throws IOException {
        List<Backup> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path p : s) {
                Matcher m = NAME.matcher(p.getFileName().toString());
                if (m.matches() && Files.isRegularFile(p)) {
                    try {
                        out.add(new Backup(p, Instant.from(STAMP.parse(m.group(1))).toEpochMilli()));
                    } catch (DateTimeParseException e) {
                        // not one of ours: left alone
                    }
                }
            }
        }
        // Names sort by time; a "-N" suffix (two backups in one second) sorts after the plain name.
        out.sort((x, y) -> x.millis() != y.millis() ? Long.compare(x.millis(), y.millis())
                : Integer.compare(suffix(x.file()), suffix(y.file())));
        return out;
    }

    private static int suffix(Path p) {
        Matcher m = NAME.matcher(p.getFileName().toString());
        return m.matches() && m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
    }

    /** Writes a backup if the interval has passed since the newest one. */
    void runIfDue() {
        try {
            if (clock.getAsLong() - lastSuccessMillis >= intervalMillis) {
                runNow();
            }
        } catch (IOException | RuntimeException e) {
            // runNow has logged and counted it; the next check tries again.
        }
    }

    /** Writes a backup now and deletes the oldest beyond {@code keep}. Returns the file. */
    Path runNow() throws IOException {
        long now = clock.getAsLong();
        Path tmp = null;
        try {
            if (diskFailed.getAsBoolean()) {
                throw new IOException("a write to the data directory failed; the node needs a restart");
            }
            tmp = Files.createTempFile(dir, TMP_PREFIX, ".tmp");
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
            prune();
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

    private Path freeName(long now) throws IOException {
        String stamp = STAMP.format(Instant.ofEpochMilli(now));
        for (int i = 0; i < 10_000; i++) {
            Path p = dir.resolve(PREFIX + stamp + (i == 0 ? "" : "-" + i) + SUFFIX);
            if (!Files.exists(p)) {
                return p;
            }
        }
        throw new FileAlreadyExistsException(dir.resolve(PREFIX + stamp + SUFFIX).toString());
    }

    private void prune() throws IOException {
        List<Backup> all = list();
        Collections.reverse(all);
        for (int i = keep; i < all.size(); i++) {
            Files.deleteIfExists(all.get(i).file());
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

    /** Unix seconds of the newest backup in the directory, written by this run or found at start; 0: none. */
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
