package com.ataiva.serengeti;

import com.ataiva.serengeti.core.Node;
import com.ataiva.serengeti.core.Version;
import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.config.ConfigException;
import java.io.IOException;

/** Command line entry point: {@code java -jar serengeti.jar [--config FILE]}. */
public final class Serengeti {
    private Serengeti() {}

    public static void main(String[] args) {
        // One line per log record, to stdout's companion stderr, before any logger exists.
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tY-%1$tm-%1$tdT%1$tH:%1$tM:%1$tS.%1$tLZ %4$s %3$s: %5$s%6$s%n");
        // Drop clients that hold a request open: 30 s to send a request, 60 s to read a response.
        System.setProperty("sun.net.httpserver.maxReqTime", "30");
        System.setProperty("sun.net.httpserver.maxRspTime", "60");
        String configPath = null;
        boolean health = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--health" -> health = true;
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        fail("--config needs a file name");
                    }
                    configPath = args[++i];
                }
                case "--version" -> {
                    System.out.println(Version.current());
                    return;
                }
                case "--help", "-h" -> {
                    System.out.println("usage: serengeti [--config FILE] [--version] [--health]\n"
                            + "--health asks the running node's GET /health and exits 0 if it answers 200.\n"
                            + "Settings come from FILE (or SERENGETI_CONFIG) and SERENGETI_* environment variables.\n"
                            + "See docs/CONFIGURATION.md.");
                    return;
                }
                default -> fail("unknown argument '" + args[i] + "' (try --help)");
            }
        }
        try {
            Config config = Config.load(configPath, System.getenv());
            if (health) {
                System.exit(probe(config) ? 0 : 1);
            }
            Node node = Node.start(config, Node.QUERIES);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    node.close();
                } catch (IOException e) {
                    System.err.println("serengeti: error while stopping: " + e.getMessage());
                }
            }, "shutdown"));
        } catch (ConfigException e) {
            fail(e.getMessage());
        } catch (IOException e) {
            fail("cannot start: " + e.getMessage());
        }
    }

    /** For container health checks: the image has no curl. A wildcard bind is reached on loopback. */
    static boolean probe(Config config) {
        String host = switch (config.bind) {
            case "0.0.0.0", "*" -> "127.0.0.1";
            case "::" -> "[::1]";
            default -> config.bind.contains(":") ? "[" + config.bind + "]" : config.bind;
        };
        try {
            java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            java.net.http.HttpResponse<Void> r = c.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(config.scheme() + "://" + host + ":" + config.port + "/health"))
                    .timeout(java.time.Duration.ofSeconds(3)).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void fail(String message) {
        System.err.println("serengeti: " + message);
        System.exit(2);
    }
}
