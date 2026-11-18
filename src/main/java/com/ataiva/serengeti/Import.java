package com.ataiva.serengeti;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.json.JsonException;
import com.ataiva.serengeti.core.query.Parser;
import com.ataiva.serengeti.core.query.Printer;
import com.ataiva.serengeti.core.server.HttpApi;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code serengeti import DB.TABLE [FILE]}: reads rows, as NDJSON or CSV, and inserts them with batched INSERT
 * statements over {@code POST /v1/query}, each request under the node's body limit.
 *
 * <p>Rows wait in groups, one per column list (an INSERT has one), and every group is sent once one is full, so
 * when a statement fails, the lines before the one that failed can all be written first: the rows not yet written
 * are sent again one at a time, in line order, up to the first the node refuses.
 */
final class Import {
    static final String USAGE = """
            usage: serengeti import [--url URL] [--user NAME] [--ca FILE] [--config FILE] [--csv] [--csv-types]
                                    [--on-conflict fail|skip|update] [--generate-ids] [--create] [--batch N]
                                    [--retries N] DB.TABLE [FILE]
            Reads rows from FILE (or standard input) and inserts them into DB.TABLE with batched INSERT statements
            (POST /v1/query, each request under the node's 1 MiB limit). The node, the account and the password
            are found as for serengeti shell.
            Rows are NDJSON, one JSON object per line, or with --csv, CSV with a header line (RFC 4180). In CSV a
            quoted field is a string, an empty unquoted field leaves the column out, and any other field is a
            string; with --csv-types an unquoted true, false or number is that value. See docs/IMPORT.md.
            --on-conflict   what a row whose _id is in the table does: fail (the default), skip, or update the
                            columns the row has
            --generate-ids  give a row without _id a random one; without it such a row is refused
            --create        create the database and the table if they do not exist
            --batch         rows per INSERT, at most (default 1000, at most 10000)
            --retries       how many times a statement is sent again after a 503, a 429 or a failed connection,
                            0.5 s, 1 s, 2 s, ... up to 8 s apart (default 8)
            Prints the rows read, inserted, updated and skipped. After a failure, the lines before the one named
            are written, and a rerun with --on-conflict skip finishes the job.
            Exit status: 0 when every row was imported, 1 when a row was refused, by these rules or by the node
            (the message names its line), 2 when the arguments are wrong or the node cannot be reached or
            refuses the account, 3 when the node stayed unavailable through the retries.""";

    enum Mode { FAIL, SKIP, UPDATE }

    static final int DEFAULT_BATCH = 1000;
    static final int MAX_BATCH = 10_000;
    /** Most characters in one input line or CSV record: more than a statement under the body limit can hold. */
    static final int MAX_LINE_CHARS = 4 << 20;
    /** Most groups, and request bytes, waiting to be sent at once. */
    static final int MAX_GROUPS = 32;
    static final long MAX_PENDING_BYTES = 4L << 20;
    /** {"query":""}: the bytes of a request body around its statement. */
    static final int BODY_OVERHEAD = Remote.body("").length;

    /** A line of input refused: its number and why. */
    static final class BadLine extends Exception {
        final long line;

        BadLine(long line, String message) {
            super(message);
            this.line = line;
        }
    }

    /** The end of a run that did not import everything: the exit status, and the line it stopped at or -1. */
    static final class Stop extends Exception {
        final int status;
        final long line;

        Stop(int status, long line, String message) {
            super(message);
            this.status = status;
            this.line = line;
        }
    }

    /** One row to insert: where it came from, its columns (_id first if it was made here), their values as text. */
    static final class Row {
        final long line;
        final List<String> columns;
        final List<String> literals;
        final String id;
        final boolean generated;
        final long tupleBytes;
        boolean written;
        /** Why a statement holding the row was sent again before the node refused it, or null. */
        String resent;

        Row(long line, List<String> columns, List<String> literals, String id, boolean generated) {
            this.line = line;
            this.columns = columns;
            this.literals = literals;
            this.id = id;
            this.generated = generated;
            long n = 2 + 2L * (literals.size() - 1);
            for (String l : literals) {
                n += jsonBytes(l);
            }
            this.tupleBytes = n;
        }

        String tuple() {
            return "(" + String.join(", ", literals) + ")";
        }

        /** The SET of an upsert: every column but _id, to the row's value. Empty if the row has only _id. */
        String assignments() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (!columns.get(i).equals("_id")) {
                    if (b.length() > 0) {
                        b.append(", ");
                    }
                    b.append(columns.get(i)).append(" = ").append(literals.get(i));
                }
            }
            return b.toString();
        }
    }

    /** Where rows come from: NDJSON lines or CSV records. */
    interface Rows {
        /** The next row, or null at the end of the input. */
        Row next() throws IOException, BadLine;

        /** The line being read, for a read that fails. */
        long line();
    }

    private final String db;
    private final String table;
    private final Mode mode;
    private final int batch;
    private final Remote remote;
    private long read;
    private long inserted;
    private long updated;
    private long skipped;

    private final Map<String, List<Row>> groups = new LinkedHashMap<>();
    private final Set<String> pendingIds = new HashSet<>();
    private long pendingBytes;

    private Import(String db, String table, Mode mode, int batch, Remote remote) {
        this.db = db;
        this.table = table;
        this.mode = mode;
        this.batch = batch;
        this.remote = remote;
    }

    /** Runs the command; returns its exit status. */
    static int run(String[] args, Map<String, String> env, InputStream stdin, PrintStream out, PrintStream err,
            Shell.Terminal terminal) {
        String url = null;
        String user = env.getOrDefault("SERENGETI_USER", "admin");
        String ca = null;
        String configPath = null;
        boolean csv = false;
        boolean types = false;
        boolean generate = false;
        boolean create = false;
        Mode mode = Mode.FAIL;
        int batch = DEFAULT_BATCH;
        int retries = Remote.DEFAULT_RETRIES;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> {
                    out.println(USAGE);
                    return 0;
                }
                case "--csv" -> csv = true;
                case "--csv-types" -> types = true;
                case "--generate-ids" -> generate = true;
                case "--create" -> create = true;
                case "--url", "--user", "--ca", "--config", "--on-conflict", "--batch", "--retries" -> {
                    if (i + 1 >= args.length) {
                        err.println("serengeti import: " + a + " needs a value");
                        return 2;
                    }
                    String v = args[++i];
                    switch (a) {
                        case "--url" -> url = v;
                        case "--user" -> user = v;
                        case "--ca" -> ca = v;
                        case "--config" -> configPath = v;
                        case "--on-conflict" -> {
                            mode = switch (v) {
                                case "fail" -> Mode.FAIL;
                                case "skip" -> Mode.SKIP;
                                case "update" -> Mode.UPDATE;
                                default -> null;
                            };
                            if (mode == null) {
                                err.println("serengeti import: --on-conflict is fail, skip or update, not '" + v + "'");
                                return 2;
                            }
                        }
                        case "--batch" -> {
                            batch = number(v, 1, MAX_BATCH);
                            if (batch < 0) {
                                err.println("serengeti import: --batch is a number from 1 to " + MAX_BATCH + ", not '" + v + "'");
                                return 2;
                            }
                        }
                        default -> {
                            retries = number(v, 0, 100);
                            if (retries < 0) {
                                err.println("serengeti import: --retries is a number from 0 to 100, not '" + v + "'");
                                return 2;
                            }
                        }
                    }
                }
                default -> {
                    if (a.startsWith("-") && !a.equals("-")) {
                        err.println("serengeti import: unknown argument '" + a + "' (try --help)");
                        return 2;
                    }
                    positional.add(a);
                }
            }
        }
        if (positional.isEmpty() || positional.size() > 2) {
            err.println("serengeti import: give the table, and the file unless the rows come on standard input:"
                    + " serengeti import DB.TABLE [FILE]");
            return 2;
        }
        if (types && !csv) {
            err.println("serengeti import: --csv-types goes with --csv");
            return 2;
        }
        String[] name = tableName(positional.get(0));
        if (name == null) {
            err.println("serengeti import: '" + positional.get(0) + "' is not a table name (DB.TABLE)");
            return 2;
        }
        String file = positional.size() > 1 && !positional.get(1).equals("-") ? positional.get(1) : null;
        InputStream in;
        try {
            in = file == null ? stdin : Files.newInputStream(Path.of(file));
        } catch (IOException | RuntimeException e) {
            err.println("serengeti import: cannot read " + file + ": " + e.getMessage());
            return 2;
        }
        try {
            Shell.Target t = Shell.target("import", url, user, ca, configPath, env, err, terminal);
            if (t == null) {
                return 2;
            }
            Remote remote = new Remote(t, retries, "import", err);
            Shell.Answer status = remote.status();
            if (status.code() != 200) {
                err.println("serengeti import: " + t.base() + ": " + status.message());
                return 2;
            }
            Import job = new Import(name[0], name[1], mode, batch, remote);
            long t0 = System.nanoTime();
            int code;
            try {
                job.prepare(create);
                Chars chars;
                try {
                    chars = new Chars(in);
                } catch (CharacterCodingException e) {
                    throw new Stop(1, 1, "the input is not UTF-8 text");
                } catch (IOException e) {
                    throw new Stop(1, 1, "the input could not be read: " + e.getMessage());
                }
                job.load(csv ? new CsvRows(chars, types, generate) : new JsonRows(chars, generate));
                code = 0;
            } catch (Stop s) {
                err.println("serengeti import: " + (s.line > 0 ? "line " + s.line + ": " : "") + s.getMessage());
                if (s.line > 0) {
                    err.println("serengeti import: the lines before line " + s.line + " are written; "
                            + (s.status == 1 ? "with line " + s.line + " put right, " : "")
                            + "a rerun with --on-conflict skip finishes the job");
                }
                code = s.status;
            }
            out.println(job.counts(System.nanoTime() - t0));
            return code;
        } finally {
            if (file != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // read to the end or given up on; nothing to report
                }
            }
        }
    }

    String counts(long nanos) {
        return "read " + read + (read == 1 ? " row: " : " rows: ") + inserted + " inserted, " + updated + " updated, "
                + skipped + " skipped (" + String.format(Locale.ROOT, "%.1f s", nanos / 1e9) + ")";
    }

    /** Creates the table if asked, and checks that it is there and the account may read it. */
    private void prepare(boolean create) throws Stop {
        String q = db + "." + table;
        List<String> checks = new ArrayList<>();
        if (create) {
            checks.add("CREATE DATABASE IF NOT EXISTS " + db);
            checks.add("CREATE TABLE IF NOT EXISTS " + q);
        }
        checks.add("SELECT _id FROM " + q + " LIMIT 0");
        for (String s : checks) {
            Remote.Reply r = send(s);
            if (r.code() != 200) {
                boolean missing = !create && r.answer().message().contains("does not exist");
                throw new Stop(r.code() == 401 ? 2 : 1, -1, r.answer().message() + (missing ? " (--create makes it)" : ""));
            }
        }
    }

    private Remote.Reply send(String statement) throws Stop {
        try {
            return remote.query(statement);
        } catch (Remote.Unavailable e) {
            throw new Stop(3, -1, remote.base() + " stayed unavailable: " + e.getMessage());
        }
    }

    /** Reads every row and sends it; a refused line is reported once every line before it is written. */
    private void load(Rows rows) throws Stop {
        for (;;) {
            Row row;
            try {
                row = rows.next();
                if (row == null) {
                    break;
                }
                add(row);
                read++;
            } catch (BadLine b) {
                flush();
                throw new Stop(1, b.line, b.getMessage());
            } catch (CharacterCodingException e) {
                flush();
                throw new Stop(1, rows.line(), "the input is not UTF-8 text");
            } catch (IOException e) {
                flush();
                throw new Stop(1, rows.line(), "the input could not be read: " + e.getMessage());
            }
        }
        flush();
    }

    // ---- batching ----

    private String prefix(List<String> columns) {
        return "INSERT INTO " + db + "." + table + " (" + String.join(", ", columns) + ") VALUES ";
    }

    /**
     * After the tuples. A made _id is sent with DO NOTHING in every mode: it can only be in the table if an
     * earlier attempt of the same statement wrote it.
     */
    private String suffix(Row r) {
        return r.generated || mode != Mode.FAIL ? " ON CONFLICT DO NOTHING" : "";
    }

    private String key(Row r) {
        return (r.generated ? "+" : "") + String.join(",", r.columns);
    }

    private void add(Row r) throws BadLine, Stop {
        if (pendingIds.contains(r.id)) {
            flush(); // an INSERT takes each _id once, and the earlier line goes first
        }
        String key = key(r);
        List<Row> g = groups.get(key);
        long base = BODY_OVERHEAD + jsonBytes(prefix(r.columns)) + jsonBytes(suffix(r));
        if (g != null && (g.size() >= batch || base + groupBytes(g) + 2 + r.tupleBytes > HttpApi.MAX_BODY)) {
            flush();
            g = null;
        }
        if (g == null) {
            if (base + r.tupleBytes > HttpApi.MAX_BODY) {
                flush();
                throw new BadLine(r.line, "the row takes " + (base + r.tupleBytes) + " bytes as an INSERT; a request"
                        + " may have at most " + HttpApi.MAX_BODY);
            }
            if (mode == Mode.UPDATE && !r.generated) {
                long upsert = BODY_OVERHEAD + jsonBytes(prefix(r.columns)) + r.tupleBytes
                        + jsonBytes(" ON CONFLICT DO UPDATE SET " + r.assignments());
                if (upsert > HttpApi.MAX_BODY) {
                    flush();
                    throw new BadLine(r.line, "the row takes " + upsert + " bytes as an INSERT ... ON CONFLICT DO"
                            + " UPDATE; a request may have at most " + HttpApi.MAX_BODY);
                }
            }
            if (groups.size() >= MAX_GROUPS || pendingBytes + base + r.tupleBytes > MAX_PENDING_BYTES) {
                flush();
            }
            g = new ArrayList<>();
            groups.put(key, g);
            pendingBytes += base;
        } else {
            pendingBytes += 2;
        }
        g.add(r);
        pendingBytes += r.tupleBytes;
        pendingIds.add(r.id);
    }

    private static long groupBytes(List<Row> g) {
        long n = 2L * (g.size() - 1);
        for (Row r : g) {
            n += r.tupleBytes;
        }
        return n;
    }

    /** A statement the node refused, with its answer. */
    private static final class Refused extends Exception {
        final Remote.Reply reply;

        Refused(Remote.Reply reply) {
            super(reply.answer().message());
            this.reply = reply;
        }
    }

    /**
     * Sends every waiting group. If one is refused, the rows not yet written go again one at a time, in line
     * order, so the first line the node refuses is found and every line before it is written.
     */
    private void flush() throws Stop {
        if (groups.isEmpty()) {
            return;
        }
        List<List<Row>> todo = new ArrayList<>(groups.values());
        groups.clear();
        pendingIds.clear();
        pendingBytes = 0;
        try {
            try {
                for (List<Row> g : todo) {
                    sendGroup(g);
                }
            } catch (Refused r) {
                if (r.reply.code() == 401) {
                    throw new Stop(2, -1, remote.base() + ": " + r.getMessage());
                }
                List<Row> left = new ArrayList<>();
                todo.forEach(g -> g.stream().filter(row -> !row.written).forEach(left::add));
                left.sort(Comparator.comparingLong(row -> row.line));
                for (Row row : left) {
                    try {
                        sendGroup(List.of(row));
                    } catch (Refused one) {
                        String why = row.resent;
                        String again = why != null ? " (it was sent again after \""
                                + why + "\", so the first attempt may have written it)" : "";
                        throw new Stop(one.reply.code() == 401 ? 2 : 1, row.line, one.getMessage() + again);
                    }
                }
            }
        } catch (Remote.Unavailable u) {
            long first = Long.MAX_VALUE;
            for (List<Row> g : todo) {
                for (Row row : g) {
                    if (!row.written) {
                        first = Math.min(first, row.line);
                    }
                }
            }
            throw new Stop(3, first == Long.MAX_VALUE ? -1 : first, remote.base() + " stayed unavailable: "
                    + u.getMessage());
        }
    }

    /** One INSERT for rows of one column list; in update mode, then an upsert for each row that was there. */
    private void sendGroup(List<Row> g) throws Refused, Remote.Unavailable {
        Row first = g.get(0);
        StringBuilder s = new StringBuilder(prefix(first.columns));
        for (int i = 0; i < g.size(); i++) {
            if (i > 0) {
                s.append(", ");
            }
            s.append(g.get(i).tuple());
        }
        s.append(suffix(first));
        Remote.Reply r = query(s.toString(), g);
        Map<?, ?> body = r.body();
        if (first.generated) {
            inserted += count(body, "inserted") + count(body, "skipped"); // what was skipped, an earlier attempt wrote
        } else if (mode == Mode.FAIL) {
            inserted += count(body, "affected");
        } else {
            inserted += count(body, "inserted");
            if (mode == Mode.SKIP) {
                skipped += count(body, "skipped");
            }
        }
        if (mode != Mode.UPDATE || first.generated) {
            g.forEach(row -> row.written = true);
            return;
        }
        Set<Object> written = new HashSet<>(body.get("ids") instanceof List<?> l ? l : List.of());
        for (Row row : g) {
            if (written.contains(row.id)) {
                row.written = true;
            }
        }
        for (Row row : g) {
            if (row.written) {
                continue;
            }
            String set = row.assignments();
            if (set.isEmpty()) {
                skipped++; // only _id: nothing to update
            } else {
                Map<?, ?> b = query(prefix(row.columns) + row.tuple() + " ON CONFLICT DO UPDATE SET " + set, List.of(row)).body();
                inserted += count(b, "inserted");
                updated += count(b, "updated");
            }
            row.written = true;
        }
    }

    private Remote.Reply query(String statement, List<Row> rows) throws Refused, Remote.Unavailable {
        byte[] body = Remote.body(statement);
        if (body.length > HttpApi.MAX_BODY) {
            throw new IllegalStateException("a batch of " + body.length + " bytes was made");
        }
        Remote.Reply r = remote.query(statement);
        if (r.code() != 200) {
            if (r.retriedAfter() != null) {
                rows.forEach(row -> row.resent = r.retriedAfter());
            }
            throw new Refused(r);
        }
        return r;
    }

    private static long count(Map<?, ?> body, String key) {
        return body.get(key) instanceof Number n ? n.longValue() : 0;
    }

    // ---- rows from the input ----

    /** A row from its columns and values, in the order of the input; checks and adds _id. */
    static Row row(long line, List<String> columns, List<Object> values, boolean generate) throws BadLine {
        int at = columns.indexOf("_id");
        boolean generated = false;
        String id;
        if (at < 0) {
            if (!generate) {
                throw new BadLine(line, "the row has no _id (--generate-ids gives such rows a random one)");
            }
            id = UUID.randomUUID().toString();
            columns = new ArrayList<>(columns);
            values = new ArrayList<>(values);
            columns.add(0, "_id");
            values.add(0, id);
            generated = true;
        } else if (values.get(at) instanceof String s && !s.isEmpty()) {
            id = s;
        } else {
            throw new BadLine(line, "_id must be a non-empty string, not " + kind(values.get(at)));
        }
        List<String> literals = new ArrayList<>(values.size());
        for (Object v : values) {
            literals.add(Printer.value(v));
        }
        return new Row(line, List.copyOf(columns), literals, id, generated);
    }

    /** A key of the input as a column name, folded to lowercase as the query language folds names. */
    static String column(String key) {
        boolean ascii = key.chars().allMatch(c -> c < 0x80);
        String name = key.toLowerCase(Locale.ROOT);
        if (!ascii || !Parser.isName(name)) {
            throw new IllegalArgumentException("'" + shorten(key) + "' is not a column name: names are ASCII letters,"
                    + " digits and _, do not start with a digit, have at most 63 characters and are not keywords");
        }
        return name;
    }

    /** {@code db.table}, folded to lowercase, or null. */
    static String[] tableName(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            return new String[] {column(parts[0]), column(parts[1])};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** How deeply objects and arrays nest in a value: 0 for any other value. */
    static int depth(Object v) {
        int d = 0;
        if (v instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                d = Math.max(d, depth(o));
            }
            return d + 1;
        }
        if (v instanceof List<?> l) {
            for (Object o : l) {
                d = Math.max(d, depth(o));
            }
            return d + 1;
        }
        return 0;
    }

    static String kind(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return "an empty string";
        }
        if (v instanceof Boolean) {
            return "a boolean";
        }
        if (v instanceof Number) {
            return "a number";
        }
        return v instanceof Map<?, ?> ? "an object" : "an array";
    }

    static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    /** The UTF-8 bytes of {@code s} inside a JSON string, escaped as {@link Json#write} escapes it. */
    static long jsonBytes(String s) {
        return Json.write(s).getBytes(StandardCharsets.UTF_8).length - 2;
    }

    private static int number(String v, int min, int max) {
        try {
            int n = Integer.parseInt(v);
            return n >= min && n <= max ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** NDJSON: one JSON object per line; blank lines are skipped. */
    static final class JsonRows implements Rows {
        private final Chars in;
        private final boolean generate;
        private long line;

        JsonRows(Chars in, boolean generate) {
            this.in = in;
            this.generate = generate;
        }

        @Override
        public long line() {
            return line;
        }

        @Override
        public Row next() throws IOException, BadLine {
            for (;;) {
                String text = nextLine();
                if (text == null) {
                    return null;
                }
                if (text.isBlank()) {
                    continue;
                }
                Object parsed;
                try {
                    parsed = Json.parseExact(text);
                } catch (JsonException e) {
                    throw new BadLine(line, "not JSON: " + e.getMessage());
                }
                if (!(parsed instanceof Map<?, ?> m)) {
                    throw new BadLine(line, "not a JSON object");
                }
                List<String> columns = new ArrayList<>(m.size());
                List<Object> values = new ArrayList<>(m.size());
                Map<String, String> seen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String key = (String) e.getKey();
                    String c;
                    try {
                        c = column(key);
                    } catch (IllegalArgumentException x) {
                        throw new BadLine(line, x.getMessage());
                    }
                    String before = seen.put(c, key);
                    if (before != null) {
                        throw new BadLine(line, "'" + shorten(before) + "' and '" + shorten(key) + "' are one column");
                    }
                    if (depth(e.getValue()) > Parser.MAX_VALUE_DEPTH) {
                        throw new BadLine(line, "the value of " + c + " nests objects and arrays more than "
                                + Parser.MAX_VALUE_DEPTH + " deep");
                    }
                    columns.add(c);
                    values.add(e.getValue());
                }
                return row(line, columns, values, generate);
            }
        }

        /** The next line without its line break, or null at the end. */
        private String nextLine() throws IOException, BadLine {
            int c = in.read();
            if (c < 0) {
                return null;
            }
            line++;
            StringBuilder b = new StringBuilder();
            while (c >= 0 && c != '\n') {
                if (c == '\r' && in.peek() == '\n') {
                    in.read();
                    break;
                }
                b.append((char) c);
                if (b.length() > MAX_LINE_CHARS) {
                    while ((c = in.read()) >= 0 && c != '\n') {
                        // skip the rest of it
                    }
                    throw new BadLine(line, "the line is longer than " + MAX_LINE_CHARS + " characters");
                }
                c = in.read();
            }
            return b.toString();
        }
    }

    /** CSV with a header line: see {@link Csv} for the type rule. */
    static final class CsvRows implements Rows {
        private final Csv.Reader in;
        private final boolean types;
        private final boolean generate;
        private List<String> header;
        private long line;

        CsvRows(Chars chars, boolean types, boolean generate) {
            this.in = new Csv.Reader(chars, MAX_LINE_CHARS);
            this.types = types;
            this.generate = generate;
        }

        @Override
        public long line() {
            return line;
        }

        @Override
        public Row next() throws IOException, BadLine {
            if (header == null) {
                Csv.Record h = in.next();
                if (h == null) {
                    return null;
                }
                line = h.line();
                header = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Csv.Field f : h.fields()) {
                    String c;
                    try {
                        c = column(f.text());
                    } catch (IllegalArgumentException x) {
                        throw new BadLine(h.line(), "in the header, " + x.getMessage());
                    }
                    if (!seen.add(c)) {
                        throw new BadLine(h.line(), "in the header, " + c + " is listed twice");
                    }
                    header.add(c);
                }
            }
            Csv.Record r = in.next();
            if (r == null) {
                return null;
            }
            line = r.line();
            if (r.fields().size() != header.size()) {
                throw new BadLine(r.line(), r.fields().size() + (r.fields().size() == 1 ? " field" : " fields")
                        + ", where the header has " + header.size());
            }
            List<String> columns = new ArrayList<>(header.size());
            List<Object> values = new ArrayList<>(header.size());
            for (int i = 0; i < header.size(); i++) {
                Csv.Field f = r.fields().get(i);
                String c = header.get(i);
                Object v;
                if (c.equals("_id")) {
                    v = !f.quoted() && f.text().isEmpty() ? Csv.ABSENT : f.text();
                } else {
                    try {
                        v = Csv.value(f, types);
                    } catch (IllegalArgumentException x) {
                        throw new BadLine(r.line(), c + ": " + x.getMessage());
                    }
                }
                if (v != Csv.ABSENT) {
                    columns.add(c);
                    values.add(v);
                }
            }
            return row(r.line(), columns, values, generate);
        }
    }
}
