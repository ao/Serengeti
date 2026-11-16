package com.ataiva.serengeti.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.config.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduledBackupsTest {
    private static final long HOUR = 3_600_000L;
    /** 2026-11-16T10:00:00Z. */
    private static final long T0 = 1_794_823_200_000L;

    @TempDir
    Path tmp;

    private final AtomicLong now = new AtomicLong(T0);
    private final AtomicInteger written = new AtomicInteger();
    private final AtomicBoolean diskFailed = new AtomicBoolean();

    private ScheduledBackups open(Path dir, int keep, ScheduledBackups.Writer w) {
        return ScheduledBackups.open(dir, 60, keep, w, diskFailed::get, now::get, false);
    }

    private ScheduledBackups open(Path dir, int keep) {
        return open(dir, keep, f -> Files.writeString(f, "backup " + written.incrementAndGet()));
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void eachBackupIsNamedByItsTimePrivateAndOnlyTheNewestAreKept() throws IOException {
        Path dir = tmp.resolve("a/b/backups");
        try (ScheduledBackups b = open(dir, 3)) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
            for (int i = 0; i < 5; i++) {
                Path f = b.runNow();
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
                now.addAndGet(HOUR);
            }
            assertEquals(List.of("serengeti-backup-20261116T120000Z.jsonl", "serengeti-backup-20261116T130000Z.jsonl",
                    "serengeti-backup-20261116T140000Z.jsonl"), names(dir));
            assertEquals("backup 5", Files.readString(dir.resolve("serengeti-backup-20261116T140000Z.jsonl")));
            assertEquals((T0 + 4 * HOUR) / 1000, b.lastSuccessSeconds());
            assertEquals(8, b.lastBytes());
            assertEquals(0, b.failures());
        }
    }

    @Test
    void twoBackupsInOneSecondGetTwoNamesAndTheLaterOneIsKept() throws IOException {
        Path dir = tmp.resolve("backups");
        try (ScheduledBackups b = open(dir, 1)) {
            b.runNow();
            now.addAndGet(400);
            Path second = b.runNow();
            assertEquals("serengeti-backup-20261116T100000Z-1.jsonl", second.getFileName().toString());
            assertEquals(List.of("serengeti-backup-20261116T100000Z-1.jsonl"), names(dir));
            assertEquals("backup 2", Files.readString(second));
        }
    }

    @Test
    void aFailedWriteLeavesNoFileAndIsCounted() throws IOException {
        Path dir = tmp.resolve("backups");
        try (ScheduledBackups b = open(dir, 3, f -> {
            Files.writeString(f, "half a back");
            throw new IOException("disk full");
        })) {
            IOException e = assertThrows(IOException.class, b::runNow);
            assertEquals("disk full", e.getMessage());
            assertEquals(List.of(), names(dir), "neither the temporary file nor a backup");
            assertEquals(1, b.failures());
            assertEquals(0, b.lastSuccessSeconds());
        }
    }

    @Test
    void aNodeWhoseDiskFailedWritesNoBackup() throws IOException {
        Path dir = tmp.resolve("backups");
        try (ScheduledBackups b = open(dir, 3)) {
            diskFailed.set(true);
            assertThrows(IOException.class, b::runNow);
            assertEquals(0, written.get(), "the state was not read");
            assertEquals(List.of(), names(dir));
            assertEquals(1, b.failures());
        }
    }

    @Test
    void leftoverTemporaryFilesGoAndNothingElseIsEverDeleted() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("backups"));
        Files.writeString(dir.resolve(".serengeti-backup-12345.tmp"), "from a node that stopped");
        Files.writeString(dir.resolve("notes.txt"), "mine");
        Files.writeString(dir.resolve("serengeti-backup-latest.jsonl"), "not a name with a time");
        Files.writeString(dir.resolve("serengeti-backup-20261399T000000Z.jsonl"), "no such month");
        Files.createDirectory(dir.resolve("serengeti-backup-20200101T000000Z.jsonl"));
        try (ScheduledBackups b = open(dir, 1)) {
            b.runNow();
            now.addAndGet(HOUR);
            b.runNow();
            assertEquals(List.of("notes.txt", "serengeti-backup-20200101T000000Z.jsonl",
                    "serengeti-backup-20261116T110000Z.jsonl", "serengeti-backup-20261399T000000Z.jsonl",
                    "serengeti-backup-latest.jsonl"), names(dir));
        }
    }

    @Test
    void theNewestBackupFoundAtStartDecidesWhenTheNextIsDue() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("backups"));
        Files.writeString(dir.resolve("serengeti-backup-20261116T093000Z.jsonl"), "older");
        Files.writeString(dir.resolve("serengeti-backup-20261116T094500Z.jsonl"), "newest");
        try (ScheduledBackups b = open(dir, 5)) {
            assertEquals((T0 - 15 * 60_000L) / 1000, b.lastSuccessSeconds());
            b.runIfDue(); // 15 minutes after the newest, with a 60-minute interval
            assertEquals(0, written.get());
            now.set(T0 + 45 * 60_000L);
            b.runIfDue();
            assertEquals(1, written.get(), "due 60 minutes after the newest");
            b.runIfDue();
            assertEquals(1, written.get(), "and not again until the next interval");
        }
    }

    @Test
    void aDirectoryThatCannotBeMadeStopsTheNode() throws IOException {
        Path file = Files.writeString(tmp.resolve("file"), "x");
        ConfigException e = assertThrows(ConfigException.class, () -> open(file, 3));
        assertTrue(e.getMessage().startsWith("backup_dir " + file + " cannot be written"), e.getMessage());
        assertThrows(ConfigException.class, () -> open(file.resolve("below"), 3));
    }

    @Test
    void theSettingsAreCheckedAndTheDataDirectoryIsRefused() {
        Path data = tmp.resolve("data");
        Map<String, String> base = Map.of("SERENGETI_DATA_DIR", data.toString());
        Config off = Config.load(null, base);
        assertEquals(null, off.backupDir);
        assertEquals(60, off.backupIntervalMinutes);
        assertEquals(24, off.backupKeep);
        Config on = Config.load(null, Map.of("SERENGETI_DATA_DIR", data.toString(),
                "SERENGETI_BACKUP_DIR", tmp.resolve("backups").toString(),
                "SERENGETI_BACKUP_INTERVAL_MINUTES", "15", "SERENGETI_BACKUP_KEEP", "96"));
        assertEquals(tmp.resolve("backups"), on.backupDir);
        assertEquals(15, on.backupIntervalMinutes);
        assertEquals(96, on.backupKeep);
        for (String inside : new String[] {data.toString(), data.resolve("backups").toString(),
            data.resolve("x/../y").toString(), data + "/"}) {
            ConfigException e = assertThrows(ConfigException.class, () -> Config.load(null,
                    Map.of("SERENGETI_DATA_DIR", data.toString(), "SERENGETI_BACKUP_DIR", inside)), inside);
            assertTrue(e.getMessage().contains("must not be the data directory"), e.getMessage());
        }
        assertEquals(tmp.resolve("data-backups"), Config.load(null, Map.of("SERENGETI_DATA_DIR", data.toString(),
                "SERENGETI_BACKUP_DIR", tmp.resolve("data-backups").toString())).backupDir, "a sibling is fine");
        for (String[] bad : new String[][] {{"SERENGETI_BACKUP_INTERVAL_MINUTES", "0"},
            {"SERENGETI_BACKUP_INTERVAL_MINUTES", "10081"}, {"SERENGETI_BACKUP_KEEP", "0"},
            {"SERENGETI_BACKUP_KEEP", "x"}}) {
            assertThrows(ConfigException.class, () -> Config.load(null,
                    Map.of("SERENGETI_DATA_DIR", data.toString(), bad[0], bad[1])), bad[0] + "=" + bad[1]);
        }
        assertFalse(Files.exists(tmp.resolve("backups")), "loading the settings makes no directory");
    }
}
