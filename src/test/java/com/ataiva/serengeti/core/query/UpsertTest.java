package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.storage.ChangeFeed;
import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Stamp;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code INSERT ... ON CONFLICT DO NOTHING} and {@code ON CONFLICT DO UPDATE SET}: what the executor does. */
class UpsertTest {
    @TempDir
    Path dir;

    private StorageEngine engine;
    private Query query;

    @BeforeEach
    void open() throws IOException {
        engine = StorageEngine.open(dir, FsyncPolicy.ALWAYS, 64L * 1024 * 1024, 0, 100);
        query = new Query(engine, new Hlc("n1"));
        run("CREATE DATABASE d");
        run("CREATE TABLE d.t");
    }

    @AfterEach
    void close() throws IOException {
        engine.close();
    }

    private void reopen(boolean checkpoint) throws IOException {
        if (checkpoint) {
            engine.checkpoint();
        }
        engine.close();
        engine = StorageEngine.open(dir, FsyncPolicy.ALWAYS, 64L * 1024 * 1024, 0, 100);
        query = new Query(engine, new Hlc("n1"));
    }

    private Result run(String q) throws IOException {
        return query.run(q);
    }

    private Result.Write write(String q) throws IOException {
        return (Result.Write) run(q);
    }

    /** The answer as the HTTP API writes it. */
    private String json(String q) throws IOException {
        return Json.write(run(q).toJson());
    }

    private String fails(String q) {
        QueryException e = assertThrows(QueryException.class, () -> run(q));
        assertTrue(!(e instanceof ParseException), e.getMessage());
        return e.getMessage();
    }

    private List<List<Object>> rows(String q) throws IOException {
        return ((Result.Rows) run(q)).rows();
    }

    /** Every row of d.t as {@code [_id, row]}, in _id order. */
    private String table() throws IOException {
        return String.valueOf(rows("SELECT * FROM d.t"));
    }

    private List<String> feed(String after) throws IOException {
        try {
            List<String> out = new ArrayList<>();
            for (ChangeFeed.Change c : engine.changes().read(after, "d", null, 100, 0).changes()) {
                out.add(c.kind() + " " + c.id() + " " + c.row());
            }
            return out;
        } catch (ChangeFeed.Gone | InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void doNothingSkipsTheRowsThatExistAndInsertsTheRest() throws IOException {
        run("INSERT INTO d.t (_id, n, s) VALUES ('a', 1, 'x'), ('b', 5, 'y')");
        assertEquals("{\"type\":\"write\",\"affected\":2,\"inserted\":2,\"updated\":0,\"skipped\":1,\"ids\":[\"c\",\"d\"]}",
                json("INSERT INTO d.t (_id, n, s) VALUES ('c', 3, 'z'), ('a', 10, 'new'), ('d', 4, NULL)"
                        + " ON CONFLICT DO NOTHING"));
        assertEquals(List.of(List.of("a", 1L, "x"), List.of("b", 5L, "y"), List.of("c", 3L, "z"), Arrays.asList("d", 4L, null)),
                rows("SELECT _id, n, s FROM d.t"));
        // Every tuple skipped: nothing is written, and the answer still lists the (no) ids.
        assertEquals("{\"type\":\"write\",\"affected\":0,\"inserted\":0,\"updated\":0,\"skipped\":2,\"ids\":[]}",
                json("INSERT INTO d.t (_id, n) VALUES ('b', 0), ('a', 0) ON CONFLICT DO NOTHING"));
        assertEquals(Map.of("type", "write", "affected", 0L, "inserted", 0L, "updated", 0L, "skipped", 1L, "ids", List.of()),
                write("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO NOTHING").toJson());
        // A plain INSERT answers as before: affected and ids only.
        assertEquals("{\"type\":\"write\",\"affected\":1,\"ids\":[\"e\"]}", json("INSERT INTO d.t (_id) VALUES ('e')"));
        assertEquals(null, write("INSERT INTO d.t (_id) VALUES ('f')").upsert());
    }

    @Test
    void doUpdateChangesTheRowsThatExistAndInsertsTheRest() throws IOException {
        run("INSERT INTO d.t (_id, n, s) VALUES ('a', 1, 'x'), ('b', 5, 'y')");
        Result.Write w = write("INSERT INTO d.t (_id, n, s) VALUES ('b', 10, 'v'), ('c', 4, 'w'), ('a', 7, 'u')"
                + " ON CONFLICT DO UPDATE SET n = n + 1, t = 'up'");
        assertEquals(new Result.Write(3, List.of("b", "c", "a"), new Result.Write.Upsert(1, 2, 0)), w);
        assertEquals("{\"type\":\"write\",\"affected\":3,\"inserted\":1,\"updated\":2,\"skipped\":0,\"ids\":[\"b\",\"c\",\"a\"]}",
                Json.write(w.toJson()));
        // An existing row gets the assignments only (the tuple's s is not written); a new row gets the tuple only.
        assertEquals(List.of(List.of("a", 2L, "x", "up"), List.of("b", 6L, "y", "up"), Arrays.asList("c", 4L, "w", null)),
                rows("SELECT _id, n, s, t FROM d.t"));
        assertEquals(List.of("_id", "n", "s", "t"), ((Result.Rows) run("SELECT * FROM d.t")).columns());
        // The documented counter: the first statement inserts 1, each one after adds 1.
        String counter = "INSERT INTO d.t (_id, n) VALUES ('ctr', 1) ON CONFLICT DO UPDATE SET n = n + 1";
        assertEquals(new Result.Write.Upsert(1, 0, 0), write(counter).upsert());
        assertEquals(new Result.Write.Upsert(0, 1, 0), write(counter).upsert());
        assertEquals(new Result.Write.Upsert(0, 1, 0), write(counter).upsert());
        assertEquals(List.of(List.of(3L)), rows("SELECT n FROM d.t WHERE _id = 'ctr'"));
        // Every right side reads the row as it was, as in UPDATE; SET may name columns the tuple does not.
        assertEquals(1, write("INSERT INTO d.t (_id) VALUES ('a') ON CONFLICT DO UPDATE SET n = m + 1, m = n + 10")
                .affected());
        assertEquals(List.of(Arrays.asList(null, 12L, "x")), rows("SELECT n, m, s FROM d.t WHERE _id = 'a'"));
    }

    @Test
    void theSameIdTwiceIsRefusedAndNothingIsWritten() throws IOException {
        run("INSERT INTO d.t (_id, n) VALUES ('a', 1)");
        String before = table();
        assertEquals("_id 'new' is listed twice: an INSERT ... ON CONFLICT takes each _id once",
                fails("INSERT INTO d.t (_id, n) VALUES ('new', 1), ('new', 2) ON CONFLICT DO NOTHING"));
        assertEquals("_id 'a' is listed twice: an INSERT ... ON CONFLICT takes each _id once",
                fails("INSERT INTO d.t (_id, n) VALUES ('a', 1), ('x', 1), ('a', 2) ON CONFLICT DO UPDATE SET n = n + 1"));
        // Without ON CONFLICT, the message is as before.
        assertEquals("a row with _id 'new' already exists in 'd.t'", fails("INSERT INTO d.t (_id) VALUES ('new'), ('new')"));
        assertEquals("a row with _id 'a' already exists in 'd.t'", fails("INSERT INTO d.t (_id) VALUES ('b'), ('a')"));
        assertEquals("_id must be a non-empty string", fails("INSERT INTO d.t (_id) VALUES ('') ON CONFLICT DO NOTHING"));
        assertEquals("_id must be a non-empty string", fails("INSERT INTO d.t (_id) VALUES (1) ON CONFLICT DO NOTHING"));
        assertEquals("_id cannot be changed", fails("INSERT INTO d.t (_id) VALUES ('b') ON CONFLICT DO UPDATE SET _id = 'c'"));
        assertEquals("table 'd.u' does not exist", fails("INSERT INTO d.u (_id) VALUES ('b') ON CONFLICT DO NOTHING"));
        assertEquals(before, table());
    }

    @Test
    void aDeletedRowIsAbsentAndARowInConflictIsPresent() throws IOException {
        run("INSERT INTO d.t (_id, n) VALUES ('gone', 41), ('gone2', 41)");
        run("DELETE FROM d.t");
        // A tombstone counts as no row: the tuple is inserted, and n = n + 1 is not computed from the old 41.
        assertEquals(new Result.Write.Upsert(2, 0, 0), write("INSERT INTO d.t (_id, n) VALUES ('gone', 1), ('gone2', 1)"
                + " ON CONFLICT DO UPDATE SET n = n + 1").upsert());
        assertEquals(List.of(List.of("gone", 1L), List.of("gone2", 1L)), rows("SELECT _id, n FROM d.t"));
        run("DELETE FROM d.t WHERE _id = 'gone'");
        assertEquals(new Result.Write.Upsert(1, 0, 0), write("INSERT INTO d.t (_id, n) VALUES ('gone', 7) ON CONFLICT DO"
                + " NOTHING").upsert());
        assertEquals(List.of(List.of(7L)), rows("SELECT n FROM d.t WHERE _id = 'gone'"));

        // Two versions that had not seen each other, as two nodes' writes: the row is present, with the newer value.
        long now = System.currentTimeMillis();
        engine.apply(List.of(new Op.Put("d", "t", "c", Map.of("n", 10L), new Stamp(now - 2000, 0, "n2"), Map.of())));
        engine.apply(List.of(new Op.Put("d", "t", "c", Map.of("n", 20L), new Stamp(now - 1000, 0, "n3"), Map.of())));
        assertEquals(2, rows("SHOW CONFLICTS IN d.t").size());
        assertEquals(new Result.Write.Upsert(0, 0, 1), write("INSERT INTO d.t (_id, n) VALUES ('c', 1) ON CONFLICT DO"
                + " NOTHING").upsert());
        assertEquals(2, rows("SHOW CONFLICTS IN d.t").size(), "DO NOTHING writes nothing, so the conflict stays");
        assertEquals(new Result.Write.Upsert(0, 1, 0), write("INSERT INTO d.t (_id, n) VALUES ('c', 1) ON CONFLICT DO"
                + " UPDATE SET n = n + 1").upsert());
        assertEquals(List.of(List.of(21L)), rows("SELECT n FROM d.t WHERE _id = 'c'"), "computed from the current version");
        assertEquals(List.of(), rows("SHOW CONFLICTS IN d.t"), "and the write, which has seen both, resolves the conflict");

        // When the current version is a delete, the row reads as absent, so the tuple is inserted, which resolves it.
        engine.apply(List.of(new Op.Put("d", "t", "e", Map.of("n", 10L), new Stamp(now - 2000, 0, "n2"), Map.of())));
        engine.apply(List.of(new Op.Delete("d", "t", "e", new Stamp(now - 1000, 0, "n3"), Map.of())));
        assertEquals(2, rows("SHOW CONFLICTS IN d.t").size());
        assertEquals(List.of(), rows("SELECT n FROM d.t WHERE _id = 'e'"));
        assertEquals(new Result.Write.Upsert(1, 0, 0), write("INSERT INTO d.t (_id, n) VALUES ('e', 1) ON CONFLICT DO"
                + " UPDATE SET n = n + 1").upsert());
        assertEquals(List.of(List.of(1L)), rows("SELECT n FROM d.t WHERE _id = 'e'"));
        assertEquals(List.of(), rows("SHOW CONFLICTS IN d.t"));
    }

    @Test
    void aRowThatCannotBeComputedFailsTheStatementWithNothingWritten() throws IOException {
        run("INSERT INTO d.t (_id, n) VALUES ('s', 'text'), ('top', 9223372036854775807), ('ok', 1)");
        String before = table();
        String start = engine.changes().now();
        assertEquals("cannot set n = n + 1 in the row with _id 's': n is a string",
                fails("INSERT INTO d.t (_id, n) VALUES ('new', 1), ('ok', 1), ('s', 1) ON CONFLICT DO UPDATE SET n = n + 1"));
        assertEquals("cannot set n = n + 1 in the row with _id 'top': the result overflows a 64-bit integer",
                fails("INSERT INTO d.t (_id, n) VALUES ('new', 1), ('top', 1), ('s', 1) ON CONFLICT DO UPDATE SET n = n + 1"));
        assertEquals("cannot set m = n - 0.5 in the row with _id 's': n is a string",
                fails("INSERT INTO d.t (_id) VALUES ('ok'), ('s') ON CONFLICT DO UPDATE SET k = 1, m = n - 0.5"));
        assertEquals(before, table(), "neither the new row nor the computable ones were written");
        assertEquals(List.of(), feed(start), "nothing reached the change feed");
        reopen(false);
        assertEquals(before, table(), "nor the log");
        // A missing or null column gives null, as in UPDATE; a double makes a double.
        assertEquals(2, write("INSERT INTO d.t (_id) VALUES ('ok'), ('top') ON CONFLICT DO UPDATE SET z = z + 1,"
                + " f = n + 0.5").affected());
        assertEquals(List.of(Arrays.asList("ok", 1L, null, 1.5), Arrays.asList("top", Long.MAX_VALUE, null, 0x1p63)),
                rows("SELECT _id, n, z, f FROM d.t WHERE _id IN ('ok', 'top')"));
    }

    @Test
    void anIndexOnAnAssignedColumnFollowsTheRows() throws IOException {
        run("INSERT INTO d.t (_id, qty, kind) VALUES ('a', 5, 1), ('b', 5, 2)");
        run("CREATE INDEX ON d.t (qty)");
        run("CREATE INDEX ON d.t (kind, qty)");
        assertEquals(new Result.Write.Upsert(1, 2, 0), write("INSERT INTO d.t (_id, qty, kind) VALUES ('a', 100, 9),"
                + " ('b', 100, 9), ('c', 7, 1) ON CONFLICT DO UPDATE SET qty = qty - 1").upsert());
        assertEquals(List.of(), ids("qty = 5"));
        assertEquals(List.of(), ids("qty = 100"), "the tuple's qty of an existing row is not written");
        assertEquals(List.of("a", "b"), ids("qty = 4"));
        assertEquals(List.of("c"), ids("qty = 7"));
        assertEquals(List.of("a"), ids("kind = 1 AND qty = 4"));
        assertEquals(List.of("c"), ids("kind = 1 AND qty > 4"));
        assertEquals(List.of(), ids("kind = 9"));
        assertEquals(new Result.Write.Upsert(0, 0, 2), write("INSERT INTO d.t (_id, qty) VALUES ('a', 1), ('c', 1)"
                + " ON CONFLICT DO NOTHING").upsert());
        assertEquals(List.of("a", "b", "c"), ids("qty >= 4"));
        for (String w : List.of("qty = 4", "qty > 4", "qty <= 7", "kind = 1 AND qty >= 4", "kind = 2 AND qty = 4")) {
            // No index read looks inside NOT, so this scans.
            assertEquals(ids("NOT (NOT (" + w + "))"), ids(w), w);
        }
    }

    private List<Object> ids(String where) throws IOException {
        List<Object> out = new ArrayList<>();
        for (List<Object> r : rows("SELECT _id FROM d.t WHERE " + where)) {
            out.add(r.get(0));
        }
        return out;
    }

    @Test
    void theChangeFeedListsTheRowsWrittenAndNotTheRowsSkipped() throws IOException {
        run("INSERT INTO d.t (_id, n, s) VALUES ('a', 1, 'x'), ('b', 2, 'y')");
        String start = engine.changes().now();
        assertEquals(1, write("INSERT INTO d.t (_id, n) VALUES ('a', 9), ('c', 3) ON CONFLICT DO NOTHING").affected());
        assertEquals(List.of("row c {n=3}"), feed(start));
        String next = engine.changes().now();
        assertEquals(2, write("INSERT INTO d.t (_id, n) VALUES ('b', 9), ('d', 4) ON CONFLICT DO UPDATE SET n = n + 40")
                .affected());
        assertEquals(List.of("row b {n=42, s=y}", "row d {n=4}"), feed(next));
    }

    @Test
    void upsertsSurviveTheLogAndTheSnapshot() throws IOException {
        String counter = "INSERT INTO d.t (_id, n) VALUES ('c', 1) ON CONFLICT DO UPDATE SET n = n + 1, last = 'x'";
        run(counter);
        run("INSERT INTO d.t (_id, n, big) VALUES ('b', 1, 9007199254740992) ON CONFLICT DO NOTHING");
        run("INSERT INTO d.t (_id, n, big) VALUES ('b', 2, 0) ON CONFLICT DO UPDATE SET big = big + 1, z = -0.0");
        String want = "[[b, 1, 9007199254740993, null, -0.0], [c, 1, null, null, null]]";
        for (String stage : List.of("as written", "from the log", "from the snapshot", "from the snapshot again")) {
            assertEquals(want, String.valueOf(rows("SELECT _id, n, big, last, z FROM d.t")), stage);
            assertEquals("{\"type\":\"rows\",\"columns\":[\"big\",\"z\"],\"rows\":[[9007199254740993,-0.0]],\"count\":1,"
                    + "\"truncated\":false}", json("SELECT big, z FROM d.t WHERE _id = 'b'"), stage);
            reopen(!stage.equals("as written"));
        }
        // The counter goes on from the value read back.
        run(counter);
        assertEquals(List.of(List.of(2L, "x")), rows("SELECT n, last FROM d.t WHERE _id = 'c'"));
        reopen(false);
        assertEquals(List.of(List.of(2L, "x")), rows("SELECT n, last FROM d.t WHERE _id = 'c'"));
    }

    @Test
    void concurrentUpsertsOfOneNewRowInsertItOnceAndLoseNoIncrement() throws Exception {
        int threads = 16;
        int each = 500;
        AtomicLong inserted = new AtomicLong();
        AtomicLong updated = new AtomicLong();
        AtomicLong onceInserted = new AtomicLong();
        AtomicLong onceSkipped = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int me = t;
                done.add(pool.submit(() -> {
                    for (int k = 0; k < each; k++) {
                        Result.Write.Upsert u = write("INSERT INTO d.t (_id, n) VALUES ('c', 1) ON CONFLICT DO UPDATE"
                                + " SET n = n + 1").upsert();
                        inserted.addAndGet(u.inserted());
                        updated.addAndGet(u.updated());
                        Result.Write.Upsert o = write("INSERT INTO d.t (_id, who) VALUES ('once', " + me + ") ON CONFLICT"
                                + " DO NOTHING").upsert();
                        onceInserted.addAndGet(o.inserted());
                        onceSkipped.addAndGet(o.skipped());
                    }
                    return null;
                }));
            }
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(List.of(List.of(8000L)), rows("SELECT n FROM d.t WHERE _id = 'c'"));
        assertEquals(1, inserted.get(), "exactly one of the 8,000 inserted the row");
        assertEquals(7999, updated.get());
        assertEquals(1, onceInserted.get());
        assertEquals(7999, onceSkipped.get());
        assertEquals(List.of(List.of(2L)), rows("SELECT COUNT(*) FROM d.t"));
        reopen(false);
        assertEquals(List.of(List.of(8000L)), rows("SELECT n FROM d.t WHERE _id = 'c'"));
    }
}
