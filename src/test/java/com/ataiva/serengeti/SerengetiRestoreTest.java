package com.ataiva.serengeti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Snapshot;
import com.ataiva.serengeti.core.storage.Stamp;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SerengetiRestoreTest {
    @TempDir
    Path tmp;

    private static final class Run {
        final int status;
        final String out;
        final String err;

        Run(int status, String out, String err) {
            this.status = status;
            this.out = out;
            this.err = err;
        }
    }

    private static Run restore(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Serengeti.restore(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path backupFile() throws Exception {
        Path f = tmp.resolve("backup.jsonl");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Snapshot.write(List.of(new Op.CreateDb("d", new Stamp(1, 0, "n1")),
                new Op.Put("d", "t", "a", Map.of("k", (Object) "v"), new Stamp(2, 0, "n1"))), bytes);
        Files.write(f, bytes.toByteArray());
        return f;
    }

    @Test
    void restoreLoadsTheBackupIntoTheConfiguredDataDirectory() throws Exception {
        Path data = tmp.resolve("data");
        Run r = restore(Map.of("SERENGETI_DATA_DIR", data.toString()), backupFile().toString());
        assertEquals(0, r.status, r.err);
        assertTrue(r.out.startsWith("restored 2 operations into "), r.out);
        assertTrue(Files.exists(data.resolve("snapshot.jsonl")));
    }

    @Test
    void theConfigFileNamesTheDataDirectoryToo() throws Exception {
        Path data = tmp.resolve("from-file");
        Path conf = tmp.resolve("serengeti.properties");
        Files.writeString(conf, "data_dir=" + data.toString().replace("\\", "/") + "\n");
        Run r = restore(Map.of(), "--config", conf.toString(), backupFile().toString());
        assertEquals(0, r.status, r.err);
        assertTrue(Files.exists(data.resolve("snapshot.jsonl")));
    }

    @Test
    void refusalsExitWithStatusTwoAndWriteNothing() throws Exception {
        Path data = tmp.resolve("refused");
        Map<String, String> env = Map.of("SERENGETI_DATA_DIR", data.toString());

        Run none = restore(env);
        assertEquals(2, none.status);
        assertTrue(none.err.contains("needs one backup file"), none.err);

        Run two = restore(env, "a", "b");
        assertEquals(2, two.status);

        Run flag = restore(env, "--force", backupFile().toString());
        assertEquals(2, flag.status);
        assertTrue(flag.err.contains("unknown argument"), flag.err);

        Run missing = restore(env, tmp.resolve("absent.jsonl").toString());
        assertEquals(2, missing.status);
        assertTrue(missing.err.startsWith("serengeti: restore refused: no backup file"), missing.err);

        Path junk = tmp.resolve("junk.jsonl");
        Files.writeString(junk, "{\"format\":\"serengeti-snapshot\",\"version\":1}\n");
        Run truncated = restore(env, junk.toString());
        assertEquals(2, truncated.status);
        assertTrue(truncated.err.contains("nothing was written"), truncated.err);
        assertFalse(Files.exists(data));

        Run again = restore(Map.of("SERENGETI_DATA_DIR", tmp.resolve("ok").toString()), backupFile().toString());
        assertEquals(0, again.status);
        Run twice = restore(Map.of("SERENGETI_DATA_DIR", tmp.resolve("ok").toString()), backupFile().toString());
        assertEquals(2, twice.status);
        assertTrue(twice.err.contains("empty data directory"), twice.err);
    }
}
