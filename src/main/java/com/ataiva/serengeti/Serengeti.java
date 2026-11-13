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
                "%1$tY-%1$tm-%1$tdT%1$tH:%1$tM:%1$tS.%1$tL%1$tz %4$s %3$s: %5$s%6$s%n");
        // Drop clients that hold a request open: 30 s to send a request, 60 s to read a response.
        System.setProperty("sun.net.httpserver.maxReqTime", "30");
        System.setProperty("sun.net.httpserver.maxRspTime", "60");
        if (args.length > 0 && args[0].equals("restore")) {
            System.exit(restore(java.util.Arrays.copyOfRange(args, 1, args.length), System.getenv(),
                    System.out, System.err));
        }
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
                            + "       serengeti restore [--config FILE] BACKUP_FILE\n"
                            + "--health asks the running node's GET /health and exits 0 if it answers 200.\n"
                            + "restore loads a backup into an empty data directory; stop the node first.\n"
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
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    java.net.URI.create(config.scheme() + "://" + host + ":" + config.port + "/health").toURL()
                            .openConnection();
            if (c instanceof javax.net.ssl.HttpsURLConnection https) {
                // Trust only this node's own certificate. The host name is not checked: the probe connects
                // to loopback, and the certificate names the node's public host.
                https.setSSLSocketFactory(com.ataiva.serengeti.core.server.Tls
                        .clientContext(config.tlsKeystore, config.tlsKeystorePassword).getSocketFactory());
                https.setHostnameVerifier((h, s) -> true);
            }
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (IOException | ConfigException e) {
            return false;
        }
    }

    /**
     * {@code serengeti restore [--config FILE] BACKUP_FILE}. Returns the exit status: 0 when the backup was loaded,
     * 2 when it was refused or the arguments or settings are wrong. Nothing is written on a refusal.
     */
    static int restore(String[] args, java.util.Map<String, String> env, java.io.PrintStream out,
            java.io.PrintStream err) {
        String configPath = null;
        java.util.List<String> files = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config")) {
                if (i + 1 >= args.length) {
                    err.println("serengeti: --config needs a file name");
                    return 2;
                }
                configPath = args[++i];
            } else if (args[i].startsWith("--")) {
                err.println("serengeti: unknown argument '" + args[i] + "' for restore");
                return 2;
            } else {
                files.add(args[i]);
            }
        }
        if (files.size() != 1) {
            err.println("serengeti: restore needs one backup file: serengeti restore [--config FILE] BACKUP_FILE");
            return 2;
        }
        try {
            Config config = Config.load(configPath, env);
            int n = com.ataiva.serengeti.core.server.Backup.restore(java.nio.file.Path.of(files.get(0)), config.dataDir);
            out.println("restored " + n + " operations into " + config.dataDir.toAbsolutePath());
            return 0;
        } catch (ConfigException e) {
            err.println("serengeti: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("serengeti: restore refused: " + e.getMessage());
            return 2;
        }
    }

    private static void fail(String message) {
        System.err.println("serengeti: " + message);
        System.exit(2);
    }
}
