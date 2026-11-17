package com.ataiva.serengeti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.server.TestCerts;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellTest {
    private static final String PW = "correct horse battery";

    @TempDir
    Path dir;

    /** What one run of the shell printed, and its exit status. */
    record Run(int status, String out, String err) {}

    private Map<String, String> nodeEnv(int port, String... kv) {
        Map<String, String> env = new HashMap<>();
        env.put("SERENGETI_DATA_DIR", dir.resolve("data").toString());
        env.put("SERENGETI_PORT", Integer.toString(port));
        env.put("SERENGETI_ADMIN_PASSWORD", PW);
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return env;
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static Run shell(Map<String, String> env, String input, Shell.Terminal terminal, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Shell.run(args, env, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                terminal);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Run remote(int port, String input, String... args) {
        List<String> a = new ArrayList<>(List.of("--url", "http://127.0.0.1:" + port));
        a.addAll(List.of(args));
        return shell(Map.of("SERENGETI_PASSWORD", PW), input, null, a.toArray(String[]::new));
    }

    @Test
    void aScriptRunsStatementsWhereverTheyEnd() throws Exception {
        int port = freePort();
        try (Node n = Node.start(Config.load(null, nodeEnv(port)), Node.QUERIES)) {
            Run r = remote(port, """
                    -- two statements on a line, one over two lines, a ; inside a string
                    CREATE DATABASE shop; CREATE TABLE shop.items
                    INSERT INTO shop.items (_id, name, qty)
                      VALUES ('bolt', 'bolt; it''s -- metric', 120), ('nut', 'nut', 300);

                    SELECT name, qty FROM shop.items ORDER BY qty
                    """);
            assertEquals(0, r.status(), r.err());
            String expected = "OK, 1 affected\nOK, 1 affected\nOK, 2 affected\n"
                    + String.format("%-20s | %s\n", "name", "qty")
                    + "-".repeat(20) + "-+-" + "-".repeat(3) + "\n"
                    + String.format("%-20s | %3s\n", "bolt; it's -- metric", "120")
                    + String.format("%-20s | %3s\n", "nut", "300")
                    + "(2 rows)\n";
            assertEquals(expected, r.out());
        }
    }

    @Test
    void tsvAndJsonAreForScripts() throws Exception {
        int port = freePort();
        try (Node n = Node.start(Config.load(null, nodeEnv(port)), Node.QUERIES)) {
            remote(port, "", "-c", "CREATE DATABASE s; CREATE TABLE s.t; "
                    + "INSERT INTO s.t (_id, a, b) VALUES ('x', 'tab\there', null), ('y', 'line\nbreak', 2.5)");
            Run tsv = remote(port, "", "--format", "tsv", "-c", "SELECT a, b, c FROM s.t ORDER BY _id");
            assertEquals(0, tsv.status(), tsv.err());
            assertEquals("a\tb\tc\ntab\\there\t\t\nline\\nbreak\t2.5\t\n", tsv.out());
            Run json = remote(port, "", "--format", "json", "-c", "SELECT COUNT(*) FROM s.t; DROP TABLE s.t");
            assertEquals(0, json.status(), json.err());
            List<String> lines = json.out().lines().toList();
            assertEquals(2, lines.size(), json.out());
            assertTrue(lines.get(0).contains("\"type\":\"rows\""), lines.get(0));
            assertTrue(lines.get(1).contains("\"type\":\"write\""), lines.get(1));
        }
    }

    @Test
    void anUpsertSaysWhatItInsertedUpdatedAndSkipped() throws Exception {
        int port = freePort();
        try (Node n = Node.start(Config.load(null, nodeEnv(port)), Node.QUERIES)) {
            // ON CONFLICT DO and qty + are incomplete, so the statement goes on to the next line.
            Run r = remote(port, """
                    CREATE DATABASE shop; CREATE TABLE shop.items
                    INSERT INTO shop.items (_id, qty) VALUES ('bolt', 1)
                    INSERT INTO shop.items (_id, qty) VALUES ('bolt', 1), ('nut', 1) ON CONFLICT DO
                      UPDATE SET qty = qty +
                      1
                    INSERT INTO shop.items (_id, qty) VALUES ('bolt', 1), ('nut', 1), ('pin', 1) ON CONFLICT DO NOTHING
                    SELECT _id, qty FROM shop.items
                    """);
            assertEquals(0, r.status(), r.err());
            assertTrue(r.out().startsWith("OK, 1 affected\nOK, 1 affected\nOK, 1 affected\n"
                    + "OK, 2 affected (1 inserted, 1 updated, 0 skipped)\nOK, 1 affected (1 inserted, 0 updated, 2 skipped)\n"),
                    r.out());
            assertTrue(r.out().contains("bolt |   2\n") && r.out().contains("nut  |   1\n") && r.out().contains("pin  |   1\n"),
                    r.out());
        }
    }

    @Test
    void aScriptStopsAtTheFirstFailure() throws Exception {
        int port = freePort();
        try (Node n = Node.start(Config.load(null, nodeEnv(port)), Node.QUERIES)) {
            Run r = remote(port, "CREATE DATABASE first\nSELECT * FROM nowhere.t\nCREATE DATABASE never\n");
            assertEquals(1, r.status());
            assertTrue(r.err().startsWith("error: 4"), r.err());
            Run dbs = remote(port, "", "--format", "tsv", "-c", "SHOW DATABASES");
            assertTrue(dbs.out().contains("first"), dbs.out());
            assertFalse(dbs.out().contains("never"), dbs.out());
            // A parse error points at where it is.
            Run bad = remote(port, "", "-c", "SELECT * FROM first.t WHERE");
            assertEquals(1, bad.status());
            List<String> err = bad.err().lines().toList();
            assertEquals("  SELECT * FROM first.t WHERE", err.get(1), bad.err());
            assertEquals(err.get(1).length() + 1, err.get(2).length(), "the caret sits just past the end: " + bad.err());
        }
    }

    @Test
    void credentialsAreCheckedBeforeAnything() throws Exception {
        int port = freePort();
        try (Node n = Node.start(Config.load(null, nodeEnv(port)), Node.QUERIES)) {
            Run wrong = shell(Map.of("SERENGETI_PASSWORD", "not the password"), "", null, "--url",
                    "http://127.0.0.1:" + port, "-c", "SHOW DATABASES");
            assertEquals(2, wrong.status());
            assertTrue(wrong.err().contains("401"), wrong.err());
            assertEquals("", wrong.out());
            Run none = shell(Map.of(), "", null, "--url", "http://127.0.0.1:" + port, "-c", "SHOW DATABASES");
            assertEquals(2, none.status());
            assertTrue(none.err().contains("SERENGETI_PASSWORD"), none.err());
            Run nobody = shell(Map.of("SERENGETI_PASSWORD", PW), "", null, "--url", "http://127.0.0.1:1", "-c", "x");
            assertEquals(2, nobody.status());
            assertTrue(nobody.err().contains("cannot reach"), nobody.err());
            assertEquals(2, shell(Map.of(), "", null, "--format", "xml").status());
            assertEquals(2, shell(Map.of(), "", null, "--url").status());
            assertEquals(2, shell(Map.of(), "", null, "--url", "ftp://x").status());
            assertEquals(2, shell(Map.of(), "", null, "extra").status());
        }
    }

    @Test
    void theLocalNodeIsTheDefaultAndItsAdminPasswordIsUsed() throws Exception {
        int port = freePort();
        Map<String, String> env = nodeEnv(port);
        try (Node n = Node.start(Config.load(null, env), Node.QUERIES)) {
            Run r = shell(env, "", null, "--format", "tsv", "-c", "SHOW DATABASES");
            assertEquals(0, r.status(), r.err());
            assertTrue(r.out().startsWith("database"), r.out());
            // Another account still needs its own password.
            Run other = shell(env, "", null, "--user", "someone", "-c", "SHOW DATABASES");
            assertEquals(2, other.status());
        }
    }

    @Test
    void atAPromptStatementsRunOnEnterOnceComplete() throws Exception {
        int port = freePort();
        try (Node n = Node.start(Config.load(null, nodeEnv(port)), Node.QUERIES)) {
            List<String> prompts = new ArrayList<>();
            List<String> lines = new ArrayList<>(List.of("CREATE DATABASE d", "CREATE TABLE d.t;",
                    "INSERT INTO d.t (_id, n)", "VALUES ('é', 1)", "help", "SELECT _id, n", "FROM d.t", "nonsense here",
                    "quit", "SHOW DATABASES"));
            Shell.Terminal t = new Shell.Terminal() {
                @Override
                public String readLine(String prompt) {
                    prompts.add(prompt);
                    return lines.isEmpty() ? null : lines.remove(0);
                }

                @Override
                public char[] readPassword(String prompt) {
                    prompts.add(prompt);
                    return PW.toCharArray();
                }
            };
            Run r = shell(Map.of(), "", t, "--url", "http://127.0.0.1:" + port);
            assertEquals(0, r.status(), r.err());
            assertEquals(List.of("password for admin: ", "serengeti> ", "serengeti> ", "serengeti> ", "     ...> ",
                    "serengeti> ", "serengeti> ", "     ...> ", "serengeti> ", "serengeti> "), prompts);
            assertTrue(r.out().startsWith("Serengeti "), r.out());
            assertTrue(r.out().contains("help      this text"), r.out());
            assertTrue(r.out().contains("é   | 1"), r.out());
            assertTrue(r.out().contains("(1 row, "), r.out());
            assertTrue(r.err().contains("error: 400"), "an error does not end the session: " + r.err());
            assertEquals(List.of("SHOW DATABASES"), lines, "quit stops reading");
        }
    }

    @Test
    void tlsWithTheClustersCaOrTheNodesOwnKeyStore() throws Exception {
        TestCerts.ca(dir, "ca");
        Path ks = TestCerts.node(dir, "ca", "node", "dns:localhost,ip:127.0.0.1");
        int port = freePort();
        Map<String, String> env = nodeEnv(port, "SERENGETI_TLS_KEYSTORE", ks.toString(),
                "SERENGETI_TLS_KEYSTORE_PASSWORD", TestCerts.PASSWORD);
        try (Node n = Node.start(Config.load(null, env), Node.QUERIES)) {
            String url = "https://127.0.0.1:" + port;
            Map<String, String> pw = Map.of("SERENGETI_PASSWORD", PW);
            Run withCa = shell(pw, "", null, "--url", url, "--ca", dir.resolve("ca-ca.pem").toString(), "-c",
                    "SHOW DATABASES");
            assertEquals(0, withCa.status(), withCa.err());
            Run withoutCa = shell(pw, "", null, "--url", url, "-c", "SHOW DATABASES");
            assertEquals(2, withoutCa.status());
            assertTrue(withoutCa.err().contains("cannot reach"), withoutCa.err());
            Run local = shell(env, "", null, "-c", "SHOW DATABASES");
            assertEquals(0, local.status(), local.err());
            Run notPem = shell(pw, "", null, "--url", url, "--ca", ks.toString(), "-c", "SHOW DATABASES");
            assertEquals(2, notPem.status());
        }
    }

    @Test
    void aBackupGoesToStandardOutputOrAPrivateFileAndRestores() throws Exception {
        int port = freePort();
        try (Node n = Node.start(Config.load(null, nodeEnv(port)), Node.QUERIES)) {
            remote(port, "", "-c", "CREATE DATABASE shop; CREATE TABLE shop.items; "
                    + "INSERT INTO shop.items (_id, qty) VALUES ('bolt', 120), ('nut', 300)");
            Map<String, String> pw = Map.of("SERENGETI_PASSWORD", PW);
            String url = "http://127.0.0.1:" + port;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int status = Shell.backup(new String[] {"--url", url}, pw, out, new PrintStream(err, true), null);
            assertEquals(0, status, err.toString());
            List<com.ataiva.serengeti.core.storage.Op> ops = com.ataiva.serengeti.core.storage.Snapshot.read(
                    new ByteArrayInputStream(out.toByteArray()), true);
            assertTrue(ops.size() >= 4, ops.toString());

            Path file = dir.resolve("b.jsonl");
            status = Shell.backup(new String[] {"--url", url, "-o", file.toString()}, pw, new ByteArrayOutputStream(),
                    new PrintStream(err, true), null);
            assertEquals(0, status, err.toString());
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    java.nio.file.Files.getPosixFilePermissions(file)));
            assertFalse(java.nio.file.Files.exists(dir.resolve("b.jsonl.tmp")));
            Path restored = dir.resolve("restored");
            assertEquals(ops.size(), com.ataiva.serengeti.core.server.Backup.restore(file, restored));

            // Backups are an admin's: a read account is refused (1), a wrong password does not get in (2).
            java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(url + "/v1/users/reader")).header("Authorization", "Basic "
                    + java.util.Base64.getEncoder().encodeToString(("admin:" + PW).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"password\": \"reader password 1\", \"role\": \"read\"}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(1, Shell.backup(new String[] {"--url", url, "--user", "reader"},
                    Map.of("SERENGETI_PASSWORD", "reader password 1"), new ByteArrayOutputStream(),
                    new PrintStream(new ByteArrayOutputStream(), true), null));
            assertEquals(2, Shell.backup(new String[] {"--url", url}, Map.of("SERENGETI_PASSWORD", "wrong password!!"),
                    new ByteArrayOutputStream(), new PrintStream(new ByteArrayOutputStream(), true), null));
            assertEquals(2, Shell.backup(new String[] {"--bogus"}, pw, new ByteArrayOutputStream(),
                    new PrintStream(new ByteArrayOutputStream(), true), null));
        }
    }

    /** A server that answers every request with {@code head} and {@code body} and closes: a node stopping. */
    private static int cutting(String head, String body) throws Exception {
        ServerSocket s = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try (s) {
                for (int i = 0; i < 4; i++) {
                    try (java.net.Socket c = s.accept()) {
                        c.getInputStream().read(new byte[8192]);
                        c.getOutputStream().write((head + body).getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (java.io.IOException e) {
                // the test is over
            }
        });
        t.setDaemon(true);
        t.start();
        return s.getLocalPort();
    }

    @Test
    void aBackupCutShortIsNeverTakenForAWholeOne() throws Exception {
        // Rows whose values hold "end":true, and a node that stops part way, having sent the full length.
        String lines = "{\"format\":\"serengeti-snapshot\",\"version\":1}\n"
                + "{\"op\":\"put\",\"db\":\"d\",\"table\":\"t\",\"_id\":\"a\",\"row\":{\"end\":true},\"at\":\"1.0.n\"}\n";
        String cut = lines + "{\"op\":\"put\",\"db\":\"d\",\"table\":\"t\",\"_id\":\"b\",\"row\":{\"end\":tr";
        Map<String, String> pw = Map.of("SERENGETI_PASSWORD", PW);
        int port = cutting("HTTP/1.1 200 OK\r\nContent-Length: " + (cut.length() + 500) + "\r\n\r\n", cut);
        Path file = dir.resolve("cut.jsonl");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(1, Shell.backup(new String[] {"--url", "http://127.0.0.1:" + port, "-o", file.toString()}, pw,
                new ByteArrayOutputStream(), new PrintStream(err, true), null), err.toString());
        assertFalse(java.nio.file.Files.exists(file));
        assertFalse(java.nio.file.Files.exists(dir.resolve("cut.jsonl.tmp")));
        // The whole length, but the last line is a row, not the trailer.
        int port2 = cutting("HTTP/1.1 200 OK\r\nContent-Length: " + lines.length() + "\r\n\r\n", lines);
        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        assertEquals(1, Shell.backup(new String[] {"--url", "http://127.0.0.1:" + port2}, pw,
                new ByteArrayOutputStream(), new PrintStream(err2, true), null));
        assertTrue(err2.toString().contains("incomplete"), err2.toString());
        byte[] whole = "x\n{\"end\":true,\"ops\":3,\"sha256\":\"ab\"}\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(Shell.endsWithTrailer(whole, whole.length));
    }

    @Test
    void theSplitterKeepsQuotesAndCommentsWhole() {
        Shell.Splitter s = new Shell.Splitter();
        assertEquals(List.of(), s.add("-- only a comment"));
        assertFalse(s.pending());
        assertEquals(List.of(), s.add(";;"));
        assertEquals(List.of(), s.add("INSERT INTO a.b (x) VALUES ('one;"));
        assertTrue(s.pending(), "inside a string");
        assertEquals(List.of("INSERT INTO a.b (x) VALUES ('one;\n-- two'') ;')"), s.add("-- two'') ;')"));
        assertEquals(List.of("SHOW DATABASES;"), s.add("SHOW DATABASES; -- the rest; of this line"));
        assertFalse(s.pending(), "a trailing comment is not a statement");
        assertEquals(List.of("SELECT * FROM a.b WHERE x = 1"), s.add("SELECT * FROM a.b WHERE x = 1"));
        assertEquals(List.of(), s.add("SELECT * FROM a.b WHERE"));
        assertEquals("SELECT * FROM a.b WHERE", s.rest());
        assertEquals(null, s.rest());
    }

    @Test
    void aComputedSetGoesOnToTheNextLineUntilItHasItsNumber() {
        Shell.Splitter s = new Shell.Splitter();
        assertEquals(List.of(), s.add("UPDATE a.b SET n = n"));
        assertTrue(s.pending(), "a column alone after = waits for + or -");
        assertEquals(List.of(), s.add("  -"));
        assertTrue(s.pending(), "and a - for its number");
        assertEquals(List.of("UPDATE a.b SET n = n\n  -\n  1 WHERE _id = 'x'"), s.add("  1 WHERE _id = 'x'"));
        assertFalse(s.pending());
        assertEquals(List.of("UPDATE a.b SET n = n-1"), s.add("UPDATE a.b SET n = n-1"));
        assertEquals(List.of("UPDATE a.b SET n = n * 2"), s.add("UPDATE a.b SET n = n * 2"), "the node says what is wrong");
    }

    @Test
    void cellsEscapeControlCharactersAndShowOtherValuesAsJson() {
        assertEquals("a\\nb\\tc\\u0001", Shell.cell("a\nb\tc\u0001"));
        assertEquals("null", Shell.cell(null));
        assertEquals("{\"k\":[1,true]}", Shell.cell(Map.of("k", List.of(1L, true))));
        assertEquals("2.5", Shell.cell(2.5));
    }
}
