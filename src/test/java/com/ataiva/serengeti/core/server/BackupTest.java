package com.ataiva.serengeti.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ataiva.serengeti.core.storage.Op;
import com.ataiva.serengeti.core.storage.Snapshot;
import com.ataiva.serengeti.core.storage.Stamp;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupTest {
    private static final String PASSWORD = "correct horse battery";
    private static final List<Op> OPS = List.of(
            new Op.CreateDb("d", new Stamp(1, 0, "n1")),
            new Op.CreateTable("d", "t", new Stamp(2, 0, "n1")),
            new Op.Put("d", "t", "a", Map.of("name", (Object) "bolt"), new Stamp(3, 0, "n1")));

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final HttpClient http = HttpClient.newHttpClient();
    private Users users;
    private HttpApi api;

    private static NodeOps withSnapshot(Optional<List<Op>> ops) {
        return new NodeOps() {
            @Override
            public Optional<List<Op>> snapshot() {
                return ops;
            }
        };
    }

    @BeforeEach
    void start() throws Exception {
        users = Users.open(dir, 4, now::get);
        api = start(withSnapshot(Optional.of(OPS)));
    }

    private HttpApi start(NodeOps ops) throws Exception {
        return HttpApi.start("127.0.0.1", 0, new HttpApi.Deps(users, new AtomicReference<>(),
                (q, r) -> Map.of(), () -> Map.of(), now::get, ops));
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private HttpResponse<byte[]> get(HttpApi a, String path, String user, String password) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + a.address().getPort() + path));
        if (user != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void backupNeedsAnAdminAccountAndStreamsTheSnapshot() throws Exception {
        assertEquals(503, get(api, "/v1/backup", "admin", PASSWORD).statusCode(), "before setup");
        users.setPassword(Users.ADMIN, PASSWORD, Users.Role.ADMIN);
        users.setPassword("reader", "reader password 1", Users.Role.USER);

        assertEquals(401, get(api, "/v1/backup", null, null).statusCode());
        assertEquals(401, get(api, "/v1/backup", "admin", "wrong password here").statusCode());
        HttpResponse<byte[]> reader = get(api, "/v1/backup", "reader", "reader password 1");
        assertEquals(403, reader.statusCode());
        assertTrue(new String(reader.body(), StandardCharsets.UTF_8).contains("admin role"));

        HttpResponse<byte[]> r = get(api, "/v1/backup", "admin", PASSWORD);
        assertEquals(200, r.statusCode());
        assertEquals("application/octet-stream", r.headers().firstValue("Content-Type").orElse(null));
        String disposition = r.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(disposition.matches("attachment; filename=\"serengeti-backup-\\d{8}T\\d{6}Z\\.jsonl\""), disposition);
        assertEquals(OPS, Snapshot.read(new ByteArrayInputStream(r.body()), true));
    }

    @Test
    void theRouteAcceptsOnlyGetAndNeedsASnapshotToSend() throws Exception {
        users.setPassword(Users.ADMIN, PASSWORD, Users.Role.ADMIN);
        HttpRequest post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.address().getPort() + "/v1/backup"))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(405, http.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
        try (HttpApi bare = start(NodeOps.NONE)) {
            assertEquals(501, get(bare, "/v1/backup", "admin", PASSWORD).statusCode());
        }
    }
}
