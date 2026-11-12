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
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
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
                    System.out.println("usage: serengeti [--config FILE] [--version]\n"
                            + "Settings come from FILE (or SERENGETI_CONFIG) and SERENGETI_* environment variables.\n"
                            + "See docs/CONFIGURATION.md.");
                    return;
                }
                default -> fail("unknown argument '" + args[i] + "' (try --help)");
            }
        }
        try {
            Config config = Config.load(configPath, System.getenv());
            Node node = Node.start(config, Node.NO_QUERIES);
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

    private static void fail(String message) {
        System.err.println("serengeti: " + message);
        System.exit(2);
    }
}
