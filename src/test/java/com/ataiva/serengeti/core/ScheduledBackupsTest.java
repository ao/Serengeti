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

    private static final String NODE = "a1b2c3d4-0000-4000-8000-000000000001";

    private final AtomicLong now = new AtomicLong(T0);
    private final AtomicInteger written = new AtomicInteger();
    private final AtomicBoolean diskFailed = new AtomicBoolean();

    private ScheduledBackups open(Path dir, int keep, ScheduledBackups.Writer w) {
        return ScheduledBackups.open(dir, null, NODE, 60, keep, w, diskFailed::get, now::get, false);
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
            assertEquals(List.of("serengeti-backup-a1b2c3d4-20261116T120000Z.jsonl", "serengeti-backup-a1b2c3d4-20261116T130000Z.jsonl",
                    "serengeti-backup-a1b2c3d4-20261116T140000Z.jsonl"), names(dir));
            assertEquals("backup 5", Files.readString(dir.resolve("serengeti-backup-a1b2c3d4-20261116T140000Z.jsonl")));
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
            assertEquals("serengeti-backup-a1b2c3d4-20261116T100000Z-1.jsonl", second.getFileName().toString());
            assertEquals(List.of("serengeti-backup-a1b2c3d4-20261116T100000Z-1.jsonl"), names(dir));
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
        Files.writeString(dir.resolve(".serengeti-backup-a1b2c3d4-12345.tmp"), "from this node, stopped");
        Files.writeString(dir.resolve(".serengeti-backup-ffff0000-12345.tmp"), "another node's, being written");
        Files.writeString(dir.resolve("notes.txt"), "mine");
        Files.writeString(dir.resolve("serengeti-backup-a1b2c3d4-latest.jsonl"), "not a name with a time");
        Files.writeString(dir.resolve("serengeti-backup-a1b2c3d4-20261399T000000Z.jsonl"), "no such month");
        Files.writeString(dir.resolve("serengeti-backup-20200101T000000Z.jsonl"), "no node in the name");
        Files.writeString(dir.resolve("serengeti-backup-ffff0000-20200101T000000Z.jsonl"), "another node's");
        Files.createDirectory(dir.resolve("serengeti-backup-a1b2c3d4-20200101T000000Z.jsonl"));
        try (ScheduledBackups b = open(dir, 1)) {
            b.runNow();
            now.addAndGet(HOUR);
            b.runNow();
            assertEquals(List.of(".serengeti-backup-ffff0000-12345.tmp", "notes.txt",
                    "serengeti-backup-20200101T000000Z.jsonl", "serengeti-backup-a1b2c3d4-20200101T000000Z.jsonl",
                    "serengeti-backup-a1b2c3d4-20261116T110000Z.jsonl", "serengeti-backup-a1b2c3d4-20261399T000000Z.jsonl",
                    "serengeti-backup-a1b2c3d4-latest.jsonl", "serengeti-backup-ffff0000-20200101T000000Z.jsonl"),
                    names(dir));
        }
    }

    @Test
    void nodesSharingADirectoryKeepTheirOwnBackups() throws IOException {
        Path dir = tmp.resolve("shared");
        try (ScheduledBackups a = open(dir, 1);
                ScheduledBackups b = ScheduledBackups.open(dir, null, "ffff0000-1111", 60, 1,
                        f -> Files.writeString(f, "other"), diskFailed::get, now::get, false)) {
            a.runNow();
            b.runNow();
            now.addAndGet(HOUR);
            a.runNow();
            assertEquals(List.of("serengeti-backup-a1b2c3d4-20261116T110000Z.jsonl",
                    "serengeti-backup-ffff0000-20261116T100000Z.jsonl"), names(dir));
        }
    }

    @Test
    void aBackupWrittenAfterTheClockWentBackIsKeptAndTheScheduleGoesOn() throws IOException {
        Path dir = tmp.resolve("backups");
        try (ScheduledBackups b = open(dir, 2)) {
            b.runNow();                 // 10:00
            now.addAndGet(HOUR);
            b.runNow();                 // 11:00
            now.addAndGet(-2 * HOUR);   // the clock steps back to 09:00
            b.runIfDue();
            assertEquals(3, written.get(), "the clock is before the newest backup: one is due");
            assertEquals(List.of("serengeti-backup-a1b2c3d4-20261116T090000Z.jsonl",
                    "serengeti-backup-a1b2c3d4-20261116T110000Z.jsonl"), names(dir), "the new one stays");
            assertEquals(T0 / 1000 - 3600, b.lastSuccessSeconds());
        }
    }

    @Test
    void aBackupNamedInTheFutureDoesNotPutTheScheduleOff() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("backups"));
        Files.writeString(dir.resolve("serengeti-backup-a1b2c3d4-20261117T100000Z.jsonl"), "a day ahead");
        try (ScheduledBackups b = open(dir, 5)) {
            assertEquals(T0 / 1000, b.lastSuccessSeconds(), "counted from now, not from the name");
            now.addAndGet(HOUR);
            b.runIfDue();
            assertEquals(1, written.get(), "due an interval after the start");
        }
    }

    @Test
    void aBackupDirectoryThatLinksIntoTheDataDirectoryIsRefused() throws IOException {
        Path data = Files.createDirectories(tmp.resolve("data"));
        Files.createDirectories(data.resolve("bk"));
        Path link = Files.createSymbolicLink(tmp.resolve("backups"), data.resolve("bk"));
        ConfigException e = assertThrows(ConfigException.class, () -> ScheduledBackups.open(link, data, NODE, 60, 1,
                f -> { }, diskFailed::get, now::get, false));
        assertTrue(e.getMessage().contains("inside the data directory"), e.getMessage());
        ScheduledBackups.open(tmp.resolve("elsewhere"), data, NODE, 60, 1, f -> { }, diskFailed::get, now::get, false)
                .close();
    }

    @Test
    void theNewestBackupFoundAtStartDecidesWhenTheNextIsDue() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("backups"));
        Files.writeString(dir.resolve("serengeti-backup-a1b2c3d4-20261116T093000Z.jsonl"), "older");
        Files.writeString(dir.resolve("serengeti-backup-a1b2c3d4-20261116T094500Z.jsonl"), "newest");
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
