package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Snapshot;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreTest {
    private static final List<Op> OPS = List.of(
            new Op.CreateDb("d", new Stamp(1, 0, "n1")),
            new Op.CreateTable("d", "t", new Stamp(2, 0, "n1")),
            new Op.Put("d", "t", "a", Map.of("name", (Object) "bolt"), new Stamp(3, 0, "n1")),
            new Op.Put("d", "t", "b", Map.of("name", (Object) "nut"), new Stamp(4, 0, "n1")));

    @TempDir
    Path tmp;

    private Path backup(String name, byte[] bytes) throws IOException {
        Path f = tmp.resolve(name);
        Files.write(f, bytes);
        return f;
    }

    private static byte[] snapshotBytes(List<Op> ops) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Snapshot.write(ops, out);
        return out.toByteArray();
    }

    @Test
    void aBackupRestoresIntoAnEmptyDirectoryAndOpensWithItsRows() throws Exception {
        Path f = backup("b.jsonl", snapshotBytes(OPS));
        Path data = tmp.resolve("data/nested");
        assertEquals(4, Backup.restore(f, data));
        try (StorageEngine e = StorageEngine.open(data, FsyncPolicy.ALWAYS)) {
            assertEquals("bolt", e.read(s -> s.row("d", "t", "a")).get("name"));
            assertEquals("nut", e.read(s -> s.row("d", "t", "b")).get("name"));
        }
    }

    @Test
    void everyTruncatedBackupIsRefusedAndWritesNothing() throws Exception {
        byte[] full = snapshotBytes(OPS);
        // Every prefix shorter than the file lacks the trailer or part of it, so none is a complete snapshot.
        // The file without only its final line feed is still complete, and is accepted.
        for (int n = 0; n < full.length - 1; n++) {
            Path f = backup("t" + n + ".jsonl", java.util.Arrays.copyOf(full, n));
            Path data = tmp.resolve("d" + n);
            assertThrows(IOException.class, () -> Backup.restore(f, data), "prefix of " + n + " bytes");
            assertFalse(Files.exists(data), "nothing is created for prefix " + n);
        }
        Path almost = backup("almost.jsonl", java.util.Arrays.copyOf(full, full.length - 1));
        assertEquals(4, Backup.restore(almost, tmp.resolve("almost")));
    }

    @Test
    void aChangedByteIsCaughtEvenWhereTheJsonStaysValid() throws Exception {
        byte[] full = snapshotBytes(OPS);
        String text = new String(full, StandardCharsets.UTF_8);
        int at = text.indexOf("bolt");
        assertTrue(at > 0);
        String changed = text.substring(0, at) + "colt" + text.substring(at + 4);
        Path f = backup("changed.jsonl", changed.getBytes(StandardCharsets.UTF_8));
        Path data = tmp.resolve("changed");
        IOException e = assertThrows(IOException.class, () -> Backup.restore(f, data));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        assertFalse(Files.exists(data));
    }

    @Test
    void aBackupWithoutAChecksumIsRefusedAsABackup() throws Exception {
        String text = new String(snapshotBytes(OPS), StandardCharsets.UTF_8);
        String legacy = text.replaceAll(",\"sha256\":\"[0-9a-f]{64}\"", "");
        assertFalse(legacy.contains("sha256"));
        assertEquals(OPS, Snapshot.read(new java.io.ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8))),
                "a snapshot without a checksum still loads at start-up");
        Path f = backup("legacy.jsonl", legacy.getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class, () -> Backup.restore(f, tmp.resolve("legacy")));
        assertTrue(e.getMessage().contains("no checksum"), e.getMessage());
    }

    @Test
    void aDirectoryWithAWriteAheadLogOrASnapshotIsRefusedAndKeptAsItWas() throws Exception {
        Path f = backup("b.jsonl", snapshotBytes(OPS));
        for (String name : List.of(StorageEngine.WAL_FILE, StorageEngine.SNAPSHOT_FILE)) {
            Path data = tmp.resolve("busy-" + name);
            Files.createDirectories(data);
            Files.writeString(data.resolve(name), "existing state\n");
            IOException e = assertThrows(IOException.class, () -> Backup.restore(f, data));
            assertTrue(e.getMessage().contains(name), e.getMessage());
            assertEquals("existing state\n", Files.readString(data.resolve(name)));
            assertEquals(List.of(name), Files.list(data).map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void accountsAreKeptAndNotPartOfTheBackup() throws Exception {
        Path data = tmp.resolve("with-accounts");
        Files.createDirectories(data);
        Files.writeString(data.resolve(Users.FILE), "{\"users\":{}}\n");
        assertEquals(4, Backup.restore(backup("b.jsonl", snapshotBytes(OPS)), data));
        assertEquals("{\"users\":{}}\n", Files.readString(data.resolve(Users.FILE)));
    }

    @Test
    void missingAndNonBackupFilesAreRefused() throws Exception {
        IOException missing = assertThrows(IOException.class, () -> Backup.restore(tmp.resolve("none"), tmp.resolve("x")));
        assertTrue(missing.getMessage().contains("no backup file"), missing.getMessage());
        for (String junk : List.of("", "hello\n", "{}\n", "{\"format\":\"serengeti-snapshot\",\"version\":1}\n")) {
            Path f = backup("junk", junk.getBytes(StandardCharsets.UTF_8));
            Path data = tmp.resolve("junk-" + junk.length());
            assertThrows(IOException.class, () -> Backup.restore(f, data), "junk " + junk.length());
            assertFalse(Files.exists(data));
        }
    }

    @Test
    void theRestoredStateIsTheSnapshotsStateExactly() throws Exception {
        byte[] full = snapshotBytes(OPS);
        Path data = tmp.resolve("exact");
        Backup.restore(backup("b.jsonl", full), data);
        assertArrayEquals(full, Files.readAllBytes(data.resolve(StorageEngine.SNAPSHOT_FILE)));
    }
}
