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
        System.setProperty(LogFormat.PROPERTY, LogFormat.PATTERN);
        // Drop clients that take more than 30 s to send a request. The JDK's limit on reading a response
        // (maxRspTime) stays off: it closes a connection whose response is overdue while it holds the set every
        // request passes through, and closing a TLS connection waits for a write blocked on a peer that stopped
        // reading, so one such peer stopped the whole node for up to 83 s (found by tests/soak). ClientLimits
        // bounds a write instead, by interrupting only the blocked thread: that closes its socket at once, and
        // takes no lock that other requests need.
        System.setProperty("sun.net.httpserver.maxReqTime", "30");
        if (args.length > 0 && args[0].equals("shell")) {
            java.io.PrintStream out = new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8);
            System.exit(Shell.run(java.util.Arrays.copyOfRange(args, 1, args.length), System.getenv(), System.in, out,
                    System.err, terminal()));
        }
        if (args.length > 0 && args[0].equals("backup")) {
            java.io.OutputStream out = new java.io.BufferedOutputStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), 1 << 16);
            int status = Shell.backup(java.util.Arrays.copyOfRange(args, 1, args.length), System.getenv(), out,
                    System.err, terminal());
            try {
                out.flush();
            } catch (IOException e) {
                System.err.println("serengeti backup: " + e.getMessage());
                status = 1;
            }
            System.exit(status);
        }
        if (args.length > 0 && args[0].equals("import")) {
            java.io.PrintStream out = new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8);
            System.exit(Import.run(java.util.Arrays.copyOfRange(args, 1, args.length), System.getenv(), System.in, out,
                    System.err, terminal()));
        }
        if (args.length > 0 && args[0].equals("export")) {
            java.io.OutputStream out = new java.io.BufferedOutputStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), 1 << 16);
            int status = Export.run(java.util.Arrays.copyOfRange(args, 1, args.length), System.getenv(), out,
                    System.err, terminal());
            try {
                out.flush();
            } catch (IOException e) {
                System.err.println("serengeti export: " + e.getMessage());
                status = 1;
            }
            System.exit(status);
        }
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
                            + "       serengeti shell [--url URL] [--user NAME] [-c STATEMENT] ... (see shell --help)\n"
                            + "       serengeti backup [--url URL] [--user NAME] [-o FILE] ... (see backup --help)\n"
                            + "       serengeti import [--url URL] [--csv] ... DB.TABLE [FILE] (see import --help)\n"
                            + "       serengeti export [--url URL] [--csv] [-o FILE] ... DB.TABLE (see export --help)\n"
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
            // The same lines as SimpleFormatter writes, made faster: every request logs one (see LogFormat).
            LogFormat.install(java.util.logging.Logger.getLogger(""));
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

    /** The terminal, if standard input and output are one: for the shell's prompt and password questions. */
    private static Shell.Terminal terminal() {
        java.io.Console console = System.console();
        return console == null ? null : new Shell.Terminal() {
            @Override
            public String readLine(String prompt) {
                return console.readLine("%s", prompt);
            }

            @Override
            public char[] readPassword(String prompt) {
                return console.readPassword("%s", prompt);
            }
        };
    }

    /** This machine's node, from its settings. A wildcard bind is reached on loopback. */
    static String localUrl(Config config) {
        String host = switch (config.bind) {
            case "0.0.0.0", "*" -> "127.0.0.1";
            case "::" -> "[::1]";
            default -> config.bind.contains(":") ? "[" + config.bind + "]" : config.bind;
        };
        return config.scheme() + "://" + host + ":" + config.port;
    }

    /** For container health checks: the image has no curl. */
    static boolean probe(Config config) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    java.net.URI.create(localUrl(config) + "/health").toURL().openConnection();
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
