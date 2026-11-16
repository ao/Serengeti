package com.ataiva.serengeti;

import com.ataiva.serengeti.core.config.Config;
import com.ataiva.serengeti.core.config.ConfigException;
import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.json.JsonException;
import com.ataiva.serengeti.core.query.ParseException;
import com.ataiva.serengeti.core.query.Parser;
import com.ataiva.serengeti.core.server.Tls;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * {@code serengeti shell}: runs statements against a node, typed at a prompt or read from a script, and prints
 * the results as a table, tab-separated values or the node's JSON.
 *
 * <p>A statement runs when it ends with {@code ;}, or at the end of a line once it is complete. A script may hold
 * several statements per line and one statement over several lines; {@code --} starts a comment.
 */
final class Shell {
    enum Format { TABLE, TSV, JSON }

    /** The terminal, when there is one: a prompt to read lines from and a way to ask for a password. */
    interface Terminal {
        /** The next line, or null at the end of input. */
        String readLine(String prompt);

        char[] readPassword(String prompt);
    }

    static final String USAGE = """
            usage: serengeti shell [--url URL] [--user NAME] [--ca FILE] [--format table|tsv|json] [-c STATEMENT]
                                   [--config FILE]
            Runs statements against a node: typed at the prompt, from standard input (a script), or given with -c.
            --url     the node (default: this machine's node, from its settings: SERENGETI_BIND and SERENGETI_PORT)
            --user    the account (default: SERENGETI_USER, else admin)
            --ca      a PEM file with the CA certificate that signed the node's certificate (ca.pem from
                      serengeti-certs); without it, Java's own CAs are trusted
            --format  table (the default), tsv (tab-separated, for scripts) or json (the node's answer, one per line)
            -c        run these statements and exit
            The password comes from SERENGETI_PASSWORD, or is asked for at the terminal. For the admin of this
            machine's node, SERENGETI_ADMIN_PASSWORD is also tried, so in the node's container
            "docker exec -it NAME serengeti shell" needs nothing more.
            Exit status: 0 when every statement succeeded, 1 when one failed (a script stops there), 2 when the
            arguments are wrong or the node cannot be reached or refuses the account.""";

    private static final String HELP = """
            Statements are those of docs/QUERY.md, for example:
              SHOW DATABASES
              CREATE TABLE shop.items
              INSERT INTO shop.items (_id, name, qty) VALUES ('bolt', 'bolt', 120)
              SELECT name, qty FROM shop.items WHERE qty > 100 ORDER BY qty LIMIT 10
            A statement runs when you press Enter on a complete one, or end it with ;. An incomplete statement
            continues on the next line.
            help      this text
            quit      leave (also exit, \\q, or Ctrl-D)""";

    private static final Pattern POSITION = Pattern.compile(" at position (\\d+)$");
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    private final URI base;
    private final String auth;
    private final SSLContext tls;
    private final boolean skipHostCheck;
    private final Format format;
    private final PrintStream out;
    private final PrintStream err;
    private final boolean interactive;

    private Shell(URI base, String auth, SSLContext tls, boolean skipHostCheck, Format format, PrintStream out,
            PrintStream err, boolean interactive) {
        this.base = base;
        this.auth = auth;
        this.tls = tls;
        this.skipHostCheck = skipHostCheck;
        this.format = format;
        this.out = out;
        this.err = err;
        this.interactive = interactive;
    }

    /** Runs the shell; returns its exit status. {@code terminal} is null when input does not come from one. */
    static int run(String[] args, Map<String, String> env, InputStream in, PrintStream out, PrintStream err,
            Terminal terminal) {
        String url = null;
        String user = env.getOrDefault("SERENGETI_USER", "admin");
        String ca = null;
        String configPath = null;
        String command = null;
        Format format = Format.TABLE;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) {
                out.println(USAGE);
                return 0;
            }
            if (!List.of("--url", "--user", "--ca", "--format", "-c", "--config").contains(a)) {
                err.println("serengeti shell: unknown argument '" + a + "' (try --help)");
                return 2;
            }
            if (i + 1 >= args.length) {
                err.println("serengeti shell: " + a + " needs a value");
                return 2;
            }
            String v = args[++i];
            switch (a) {
                case "--url" -> url = v;
                case "--user" -> user = v;
                case "--ca" -> ca = v;
                case "--config" -> configPath = v;
                case "-c" -> command = v;
                default -> {
                    try {
                        format = Format.valueOf(v.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        err.println("serengeti shell: --format is table, tsv or json, not '" + v + "'");
                        return 2;
                    }
                }
            }
        }

        Target t = target("shell", url, user, ca, configPath, env, err, terminal);
        if (t == null) {
            return 2;
        }
        URI base = t.base();
        boolean interactive = command == null && terminal != null;
        Shell shell = new Shell(base, t.auth(), t.tls(), t.skipHostCheck(), format, out, err, interactive);

        Answer status = shell.send("GET", "/v1/status", null);
        if (status.code != 200) {
            err.println("serengeti shell: " + base + ": " + status.message());
            return 2;
        }
        if (interactive) {
            Object version = status.json instanceof Map<?, ?> m ? m.get("version") : null;
            out.println("Serengeti " + version + " at " + base + ", as " + user + ". Type help for help, quit to leave.");
        }
        if (command != null) {
            return shell.script(new BufferedReader(new java.io.StringReader(command)));
        }
        if (interactive) {
            return shell.prompt(terminal);
        }
        return shell.script(new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    /** A node to talk to: where, which certificates to trust, and the account's Basic credentials. */
    record Target(URI base, SSLContext tls, boolean skipHostCheck, String auth) {}

    /**
     * The node from {@code --url} (or this machine's, from its settings), and the password from
     * SERENGETI_PASSWORD, the node's admin password, or the terminal. Null, with the reason printed, if not.
     */
    static Target target(String cmd, String url, String user, String ca, String configPath, Map<String, String> env,
            PrintStream err, Terminal terminal) {
        URI base;
        SSLContext tls = null;
        boolean skipHostCheck = false;
        String password = env.get("SERENGETI_PASSWORD");
        try {
            if (url != null) {
                base = URI.create(url.replaceAll("/+$", ""));
                if (!"http".equals(base.getScheme()) && !"https".equals(base.getScheme()) || base.getHost() == null) {
                    err.println("serengeti " + cmd + ": --url wants http://HOST:PORT or https://HOST:PORT, not '" + url + "'");
                    return null;
                }
                if (ca != null) {
                    tls = trusting(Path.of(ca));
                }
            } else {
                Config config = Config.load(configPath, env);
                base = URI.create(Serengeti.localUrl(config));
                if (config.tls()) {
                    // This machine's node: trust its own certificate, as the health probe does. The name is not
                    // checked, as the connection goes to loopback and the certificate names the node's host.
                    tls = ca != null ? trusting(Path.of(ca))
                            : Tls.clientContext(config.tlsKeystore, config.tlsKeystorePassword);
                    skipHostCheck = ca == null;
                }
                if (password == null && user.equals("admin") && config.adminPassword != null) {
                    password = config.adminPassword;
                }
            }
        } catch (IllegalArgumentException | ConfigException | IOException | GeneralSecurityException e) {
            err.println("serengeti " + cmd + ": " + e.getMessage());
            return null;
        }
        if (password == null) {
            if (terminal == null) {
                err.println("serengeti " + cmd + ": set SERENGETI_PASSWORD to the password of '" + user + "'");
                return null;
            }
            char[] typed = terminal.readPassword("password for " + user + ": ");
            if (typed == null) {
                return null;
            }
            password = new String(typed);
        }
        if ("http".equals(base.getScheme()) && !loopback(base.getHost())) {
            err.println("serengeti " + cmd + ": warning: " + base.getHost() + " is reached over plain HTTP, so the"
                    + " password crosses the network unencrypted");
        }
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new Target(base, tls, skipHostCheck, auth);
    }

    static final String BACKUP_USAGE = """
            usage: serengeti backup [--url URL] [--user NAME] [--ca FILE] [--config FILE] [-o FILE]
            Takes a backup of a node (GET /v1/backup; an admin's) and writes it to FILE (mode 0600; written to
            FILE.tmp and renamed once complete) or to standard output. The node, the account and the password are
            found as for serengeti shell: in a node's container, "docker exec NAME serengeti backup > b.jsonl"
            needs nothing more. Restore it with "serengeti restore FILE" into an empty data directory.
            Exit status: 0 for a complete backup, 1 when the node refused it or it was cut short, 2 when the
            arguments are wrong or the node cannot be reached or refuses the account.""";

    /** {@code serengeti backup}: returns the exit status. */
    static int backup(String[] args, Map<String, String> env, java.io.OutputStream stdout, PrintStream err,
            Terminal terminal) {
        String url = null;
        String user = env.getOrDefault("SERENGETI_USER", "admin");
        String ca = null;
        String configPath = null;
        String file = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) {
                new PrintStream(stdout, true, StandardCharsets.UTF_8).println(BACKUP_USAGE);
                return 0;
            }
            if (!List.of("--url", "--user", "--ca", "--config", "-o").contains(a)) {
                err.println("serengeti backup: unknown argument '" + a + "' (try --help)");
                return 2;
            }
            if (i + 1 >= args.length) {
                err.println("serengeti backup: " + a + " needs a value");
                return 2;
            }
            String v = args[++i];
            switch (a) {
                case "--url" -> url = v;
                case "--user" -> user = v;
                case "--ca" -> ca = v;
                case "--config" -> configPath = v;
                default -> file = v;
            }
        }
        Target t = target("backup", url, user, ca, configPath, env, err, terminal);
        if (t == null) {
            return 2;
        }
        Path target = file == null ? null : Path.of(file);
        Path tmp = target == null ? null : target.resolveSibling(target.getFileName() + ".tmp");
        HttpURLConnection c = null;
        boolean receiving = false;
        try {
            c = open(t, "/v1/backup");
            int code = c.getResponseCode();
            if (code != 200) {
                InputStream s = c.getErrorStream();
                String text = s == null ? "" : new String(s.readAllBytes(), StandardCharsets.UTF_8);
                Answer a = new Answer(code, text, parseOrNull(text), null);
                err.println("serengeti backup: " + t.base() + ": " + a.message());
                return code == 401 ? 2 : 1;
            }
            receiving = true;
            java.io.OutputStream out;
            if (tmp != null) {
                Files.deleteIfExists(tmp);
                out = Files.newOutputStream(Files.createFile(tmp, privateFile()));
            } else {
                out = stdout;
            }
            // The last bytes, to check that the trailer arrived: a backup cut short has none.
            byte[] tail = new byte[256];
            int tailLen = 0;
            long total = 0;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    total += n;
                    if (n >= tail.length) {
                        System.arraycopy(buf, n - tail.length, tail, 0, tail.length);
                        tailLen = tail.length;
                    } else {
                        int keep = Math.min(tailLen, tail.length - n);
                        System.arraycopy(tail, tailLen - keep, tail, 0, keep);
                        System.arraycopy(buf, 0, tail, keep, n);
                        tailLen = keep + n;
                    }
                }
            } finally {
                out.flush();
                if (tmp != null) {
                    out.close();
                }
            }
            long expected = c.getContentLengthLong();
            if ((expected >= 0 && total != expected) || !endsWithTrailer(tail, tailLen)) {
                // A body cut short reads as a normal end of stream, so both the length and the trailer are checked.
                err.println("serengeti backup: the backup was cut short after " + total + " bytes"
                        + (tmp != null ? "; nothing was kept" : "; what was written to standard output is incomplete"));
                if (tmp != null) {
                    Files.deleteIfExists(tmp);
                }
                return 1;
            }
            if (tmp != null) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                err.println("serengeti backup: " + total + " bytes in " + target);
            }
            return 0;
        } catch (IOException e) {
            err.println("serengeti backup: " + t.base() + ": " + e.getMessage()
                    + (receiving ? "; the backup was cut short" + (tmp != null ? ", nothing was kept" : "") : ""));
            try {
                if (tmp != null) {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException ignored) {
                // the message above says what went wrong
            }
            return receiving ? 1 : 2;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** True if the last line of the bytes read is a backup's trailer: {"end": true, "ops": N, ...}. */
    static boolean endsWithTrailer(byte[] tail, int len) {
        int end = len;
        while (end > 0 && (tail[end - 1] == '\n' || tail[end - 1] == '\r')) {
            end--;
        }
        int start = end;
        while (start > 0 && tail[start - 1] != '\n') {
            start--;
        }
        if (start == 0 && len == tail.length) {
            return false; // the last line is longer than what was kept: not a trailer
        }
        try {
            Map<String, Object> m = Json.parseObject(new String(tail, start, end - start, StandardCharsets.UTF_8));
            return Boolean.TRUE.equals(m.get("end")) && m.get("ops") instanceof Number;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static java.nio.file.attribute.FileAttribute<?>[] privateFile() {
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return new java.nio.file.attribute.FileAttribute<?>[] {java.nio.file.attribute.PosixFilePermissions
                    .asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))};
        }
        return new java.nio.file.attribute.FileAttribute<?>[0];
    }

    private static Object parseOrNull(String text) {
        try {
            return text.isBlank() ? null : Json.parse(text);
        } catch (JsonException e) {
            return null;
        }
    }

    private static HttpURLConnection open(Target t, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) t.base().resolve(path).toURL().openConnection();
        if (c instanceof HttpsURLConnection https) {
            if (t.tls() != null) {
                https.setSSLSocketFactory(t.tls().getSocketFactory());
            }
            if (t.skipHostCheck()) {
                https.setHostnameVerifier((h, s) -> true);
            }
        }
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Authorization", t.auth());
        return c;
    }

    // ---- reading statements ----

    /** Runs a script: statements end at ; or at the end of a line once complete. Stops at the first failure. */
    private int script(BufferedReader in) {
        Splitter s = new Splitter();
        try {
            for (String line; (line = in.readLine()) != null; ) {
                for (String statement : s.add(line)) {
                    if (!execute(statement)) {
                        return 1;
                    }
                }
            }
        } catch (IOException e) {
            err.println("serengeti shell: " + e.getMessage());
            return 1;
        }
        String rest = s.rest();
        return rest == null || execute(rest) ? 0 : 1;
    }

    private int prompt(Terminal terminal) {
        Splitter s = new Splitter();
        for (;;) {
            String line = terminal.readLine(s.pending() ? "     ...> " : "serengeti> ");
            if (line == null) {
                out.println();
                return 0;
            }
            if (!s.pending()) {
                String word = line.strip().replaceAll(";$", "").toLowerCase(Locale.ROOT);
                switch (word) {
                    case "quit", "exit", "\\q" -> {
                        return 0;
                    }
                    case "help", "\\?", "\\h" -> {
                        out.println(HELP);
                        continue;
                    }
                    default -> { }
                }
            }
            for (String statement : s.add(line)) {
                execute(statement);
            }
        }
    }

    /**
     * Collects lines and hands back whole statements: text up to a ; outside quotes and comments, and what is
     * left at the end of a line if it parses as a complete statement. Text the parser rejects for any reason
     * other than ending too soon also goes to the node, whose message then says what is wrong.
     */
    static final class Splitter {
        private final StringBuilder buf = new StringBuilder();

        List<String> add(String line) {
            buf.append(line).append('\n');
            List<String> done = new ArrayList<>();
            boolean quoted = false;
            boolean comment = false;
            int start = 0;
            for (int i = 0; i < buf.length(); i++) {
                char c = buf.charAt(i);
                if (comment) {
                    comment = c != '\n' && c != '\r';
                } else if (c == '\'') {
                    quoted = !quoted;
                } else if (!quoted && c == '-' && i + 1 < buf.length() && buf.charAt(i + 1) == '-') {
                    comment = true;
                } else if (!quoted && c == ';') {
                    String s = buf.substring(start, i + 1);
                    if (!blank(s)) {
                        done.add(s.strip());
                    }
                    start = i + 1;
                }
            }
            buf.delete(0, start);
            if (!quoted && !blank(buf) && complete(buf.toString())) {
                done.add(buf.toString().strip());
                buf.setLength(0);
            } else if (blank(buf)) {
                buf.setLength(0);
            }
            return done;
        }

        /** True while a statement is started but not yet complete. */
        boolean pending() {
            return buf.length() > 0;
        }

        /** What is left at the end of the input, or null. */
        String rest() {
            String s = buf.toString();
            buf.setLength(0);
            return blank(s) ? null : s.strip();
        }

        private static boolean complete(String text) {
            try {
                Parser.parse(text);
                return true;
            } catch (ParseException e) {
                return !e.getMessage().contains("but found end of input");
            } catch (RuntimeException e) {
                return true; // the node will say what is wrong
            }
        }

        /** Only blanks and comments. */
        private static boolean blank(CharSequence s) {
            boolean comment = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (comment) {
                    comment = c != '\n' && c != '\r';
                } else if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                    comment = true;
                } else if (!Character.isWhitespace(c) && c != ';') {
                    return false;
                }
            }
            return true;
        }
    }

    // ---- running one statement ----

    private boolean execute(String statement) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", statement);
        long t0 = System.nanoTime();
        Answer a = send("POST", "/v1/query", Json.write(body));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (a.code != 200) {
            err.println("error: " + a.message());
            Matcher m = POSITION.matcher(a.message());
            if (m.find() && !statement.contains("\n")) {
                int at = Integer.parseInt(m.group(1));
                if (at <= statement.length()) {
                    err.println("  " + statement);
                    err.println("  " + " ".repeat(at) + "^");
                }
            }
            return false;
        }
        if (format == Format.JSON) {
            out.println(a.text.strip());
            return true;
        }
        if (!(a.json instanceof Map<?, ?> m)) {
            out.println(a.text.strip());
            return true;
        }
        String timing = interactive ? " (" + ms + " ms)" : "";
        if ("rows".equals(m.get("type"))) {
            List<?> columns = (List<?>) m.get("columns");
            List<?> rows = (List<?>) m.get("rows");
            if (format == Format.TSV) {
                printTsv(columns, rows);
            } else {
                printTable(columns, rows);
                String n = rows.size() == 1 ? "1 row" : rows.size() + " rows";
                out.println("(" + n + (interactive ? ", " + ms + " ms" : "") + ")");
                if (Boolean.TRUE.equals(m.get("truncated"))) {
                    out.println("More rows matched; add LIMIT, or narrow WHERE, to see the rest.");
                }
            }
        } else if (format == Format.TABLE) {
            Object affected = m.get("affected");
            out.println("OK, " + affected + " affected" + timing);
        }
        return true;
    }

    private void printTable(List<?> columns, List<?> rows) {
        int n = columns.size();
        int[] width = new int[n];
        boolean[] numeric = new boolean[n];
        java.util.Arrays.fill(numeric, true);
        List<String[]> cells = new ArrayList<>(rows.size());
        for (int c = 0; c < n; c++) {
            width[c] = length(String.valueOf(columns.get(c)));
        }
        for (Object r : rows) {
            List<?> row = (List<?>) r;
            String[] line = new String[n];
            for (int c = 0; c < n; c++) {
                Object v = c < row.size() ? row.get(c) : null;
                line[c] = cell(v);
                width[c] = Math.max(width[c], length(line[c]));
                numeric[c] &= v == null || v instanceof Number;
            }
            cells.add(line);
        }
        StringBuilder head = new StringBuilder();
        StringBuilder rule = new StringBuilder();
        for (int c = 0; c < n; c++) {
            if (c > 0) {
                head.append(" | ");
                rule.append("-+-");
            }
            head.append(pad(String.valueOf(columns.get(c)), width[c], false));
            rule.append("-".repeat(width[c]));
        }
        out.println(head.toString().stripTrailing());
        out.println(rule);
        for (String[] line : cells) {
            StringBuilder b = new StringBuilder();
            for (int c = 0; c < n; c++) {
                if (c > 0) {
                    b.append(" | ");
                }
                b.append(pad(line[c], width[c], numeric[c]));
            }
            out.println(b.toString().stripTrailing());
        }
    }

    private void printTsv(List<?> columns, List<?> rows) {
        List<String> head = new ArrayList<>();
        for (Object c : columns) {
            head.add(tsv(c));
        }
        out.println(String.join("\t", head));
        for (Object r : rows) {
            List<String> line = new ArrayList<>();
            for (Object v : (List<?>) r) {
                line.add(v == null ? "" : tsv(v));
            }
            out.println(String.join("\t", line));
        }
    }

    /** A value for a table: text as it is (control characters escaped), anything else as JSON. */
    static String cell(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (ch < 0x20 || ch == 0x7f) {
                            b.append(String.format("\\u%04x", (int) ch));
                        } else {
                            b.append(ch);
                        }
                    }
                }
            }
            return b.toString();
        }
        return Json.write(v);
    }

    private static String tsv(Object v) {
        String s = v instanceof String str ? str : Json.write(v);
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String pad(String s, int width, boolean right) {
        String fill = " ".repeat(Math.max(0, width - length(s)));
        return right ? fill + s : s + fill;
    }

    // ---- HTTP ----

    /** A node's answer: the status (0 when there was none), the body as text and as JSON when it is JSON. */
    record Answer(int code, String text, Object json, String failure) {
        String message() {
            if (code == 0) {
                return failure;
            }
            if (json instanceof Map<?, ?> m && m.get("error") instanceof String e) {
                return code + ": " + e;
            }
            return code == 401 ? "401: wrong user name or password" : code + ": " + text.strip();
        }
    }

    private Answer send(String method, String path, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) base.resolve(path).toURL().openConnection();
            if (c instanceof HttpsURLConnection https) {
                if (tls != null) {
                    https.setSSLSocketFactory(tls.getSocketFactory());
                }
                if (skipHostCheck) {
                    https.setHostnameVerifier((h, s) -> true);
                }
            }
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", auth);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream s = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = s == null ? "" : new String(s.readAllBytes(), StandardCharsets.UTF_8);
            Object json = null;
            try {
                json = text.isBlank() ? null : Json.parse(text);
            } catch (JsonException e) {
                // not JSON: shown as text
            }
            return new Answer(code, text, json, null);
        } catch (IOException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Answer(0, "", null, "cannot reach " + base + ": " + why);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** TLS that trusts the certificates in a PEM file, and only those. */
    static SSLContext trusting(Path pem) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        try (InputStream s = Files.newInputStream(pem)) {
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(s)) {
                ks.setCertificateEntry("ca" + i++, cert);
            }
        }
        if (i == 0) {
            throw new IOException(pem + " holds no certificate");
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static boolean loopback(String host) {
        String h = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        if (h.equals("localhost")) {
            return true;
        }
        try {
            // A literal address only: no name lookup.
            return (h.matches("[0-9.]+") || h.contains(":")) && InetAddress.getByName(h).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }
}
