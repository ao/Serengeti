package com.ataiva.serengeti.core.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;

/** The console: one HTML page, its script and its style sheet, read from the jar once. */
final class Static {
    /** Scripts and styles only from this server; nothing inline, nothing framed. */
    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
            + "img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    record Page(String contentType, byte[] body) {}

    private static final Map<String, String[]> ROUTES = Map.of(
            "/", new String[] {"web/index.html", "text/html; charset=utf-8"},
            "/app.js", new String[] {"web/app.js", "text/javascript; charset=utf-8"},
            "/app.css", new String[] {"web/app.css", "text/css; charset=utf-8"});

    private final Map<String, Page> pages;

    Static() {
        Map<String, Page> m = new java.util.HashMap<>();
        for (Map.Entry<String, String[]> e : ROUTES.entrySet()) {
            m.put(e.getKey(), new Page(e.getValue()[1], load(e.getValue()[0])));
        }
        pages = Map.copyOf(m);
    }

    Optional<Page> get(String path) {
        return Optional.ofNullable(pages.get(path));
    }

    private static byte[] load(String resource) {
        try (InputStream in = Static.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
