package com.ataiva.serengeti;

import com.ataiva.serengeti.core.json.Json;
import com.ataiva.serengeti.core.query.ParseException;
import com.ataiva.serengeti.core.query.Parser;
import com.ataiva.serengeti.core.query.Printer;
import com.ataiva.serengeti.core.query.Statement;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code serengeti export DB.TABLE}: writes every row, in {@code _id} order, as NDJSON or CSV, reading keyset
 * pages ({@code WHERE _id > last ORDER BY _id LIMIT n}) so that no page reads the rows before it.
 */
final class Export {
    static final String USAGE = """
            usage: serengeti export [--url URL] [--user NAME] [--ca FILE] [--config FILE] [--csv]
                                    [--where CONDITION] [--page N] [--retries N] [-o FILE] DB.TABLE
            Writes every row of DB.TABLE in _id order to FILE (mode 0600; written to FILE.tmp and renamed once
            complete) or to standard output: as NDJSON, one JSON object per row with its null values left out,
            or with --csv as CSV, with a header of every column found (_id first) and strings quoted. The node,
            the account and the password are found as for serengeti shell. serengeti import reads either back.
            Rows are read in pages that start after the last _id read, so the export works for a table of any
            size. Each page sees the table as it is when it runs: a row written meanwhile is in the export if
            its _id comes after the last one read when it is written. It is not a snapshot; serengeti backup is.
            --where    only the rows where CONDITION holds, a WHERE condition of docs/QUERY.md
            --page     rows per page, at most (default 1000, at most 10000); pages of large rows are smaller
            --retries  how many times a page is asked for again after a 503, a 429 or a failed connection
                       (default 8)
            Exit status: 0 for a complete export, 1 when the node refused a page or the output could not be
            written, 2 when the arguments are wrong or the node cannot be reached or refuses the account, 3
            when the node stayed unavailable through the retries.""";

    static final int DEFAULT_PAGE = 1000;
    static final int MAX_PAGE = 10_000;
    /** The first page is small, and later pages are sized to about this many bytes of answer. */
    static final int FIRST_PAGE = 10;
    static final long PAGE_BYTES = 4L << 20;

    private Export() {}

    /** A failure that ends the export: the exit status and the message. */
    private static final class Fail extends Exception {
        final int status;

        Fail(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static int run(String[] args, Map<String, String> env, OutputStream stdout, PrintStream err,
            Shell.Terminal terminal) {
        String url = null;
        String user = env.getOrDefault("SERENGETI_USER", "admin");
        String ca = null;
        String configPath = null;
        String file = null;
        String where = null;
        boolean csv = false;
        int page = DEFAULT_PAGE;
        int retries = Remote.DEFAULT_RETRIES;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> {
                    new PrintStream(stdout, true, StandardCharsets.UTF_8).println(USAGE);
                    return 0;
                }
                case "--csv" -> csv = true;
                case "--url", "--user", "--ca", "--config", "-o", "--where", "--page", "--retries" -> {
                    if (i + 1 >= args.length) {
                        err.println("serengeti export: " + a + " needs a value");
                        return 2;
                    }
                    String v = args[++i];
                    switch (a) {
                        case "--url" -> url = v;
                        case "--user" -> user = v;
                        case "--ca" -> ca = v;
                        case "--config" -> configPath = v;
                        case "-o" -> file = v;
                        case "--where" -> where = v;
                        case "--page" -> {
                            page = number(v, 1, MAX_PAGE);
                            if (page < 0) {
                                err.println("serengeti export: --page is a number from 1 to " + MAX_PAGE + ", not '" + v + "'");
                                return 2;
                            }
                        }
                        default -> {
                            retries = number(v, 0, 100);
                            if (retries < 0) {
                                err.println("serengeti export: --retries is a number from 0 to 100, not '" + v + "'");
                                return 2;
                            }
                        }
                    }
                }
                default -> {
                    if (a.startsWith("-")) {
                        err.println("serengeti export: unknown argument '" + a + "' (try --help)");
                        return 2;
                    }
                    positional.add(a);
                }
            }
        }
        if (positional.size() != 1) {
            err.println("serengeti export: give one table: serengeti export DB.TABLE");
            return 2;
        }
        String[] name = Import.tableName(positional.get(0));
        if (name == null) {
            err.println("serengeti export: '" + positional.get(0) + "' is not a table name (DB.TABLE)");
            return 2;
        }
        String table = name[0] + "." + name[1];
        String condition = null;
        if (where != null) {
            String head = "SELECT * FROM " + table + " WHERE ";
            Statement s;
            try {
                s = Parser.parse(head + where);
            } catch (ParseException e) {
                String m = e.getMessage().replaceAll(" at position \\d+$", "");
                int at = e.position() - head.length();
                err.println("serengeti export: --where: " + m + (at >= 0 ? " at position " + at : ""));
                return 2;
            }
            Statement.Select q = (Statement.Select) s;
            if (q.where() == null || !q.groupBy().isEmpty() || q.having() != null || !q.orderBy().isEmpty()
                    || q.limit() != null || q.offset() != null) {
                err.println("serengeti export: --where takes a condition only, without GROUP BY, HAVING, ORDER BY,"
                        + " LIMIT or OFFSET");
                return 2;
            }
            condition = Printer.print(q.where());
        }

        Shell.Target t = Shell.target("export", url, user, ca, configPath, env, err, terminal);
        if (t == null) {
            return 2;
        }
        Remote remote = new Remote(t, retries, "export", err);
        Shell.Answer status = remote.status();
        if (status.code() != 200) {
            err.println("serengeti export: " + t.base() + ": " + status.message());
            return 2;
        }

        Path target = file == null ? null : Path.of(file);
        Path tmp = target == null ? null : target.resolveSibling(target.getFileName() + ".tmp");
        Path spool = null;
        long t0 = System.nanoTime();
        long[] rows = {0};
        // A signal skips `finally`: the files of rows written so far would stay on disk (review 22l).
        Path[] left = {tmp, null};
        Thread cleanup = new Thread(() -> {
            delete(left[0]);
            delete(left[1]);
        }, "export-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            OutputStream raw;
            if (tmp != null) {
                Files.deleteIfExists(tmp);
                raw = Files.newOutputStream(Files.createFile(tmp, privateFile()));
            } else {
                raw = stdout;
            }
            Writer out = new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8), 1 << 16);
            try {
                if (csv) {
                    // The header lists every column, which is known once every row is read: the rows wait in a
                    // file of their own meanwhile.
                    spool = target != null ? target.resolveSibling(target.getFileName() + ".rows.tmp")
                            : Files.createTempFile("serengeti-export", ".jsonl", privateFile());
                    left[1] = spool;
                    Files.deleteIfExists(spool);
                    Files.createFile(spool, privateFile());
                    Set<String> columns = new LinkedHashSet<>(List.of("_id"));
                    try (Writer s = Files.newBufferedWriter(spool, StandardCharsets.UTF_8)) {
                        pages(remote, table, condition, page, (row, json) -> {
                            columns.addAll(row.keySet());
                            s.write(json);
                            s.write('\n');
                            rows[0]++;
                        });
                    }
                    writeCsv(spool, List.copyOf(columns), out);
                } else {
                    pages(remote, table, condition, page, (row, json) -> {
                        out.write(json);
                        out.write('\n');
                        rows[0]++;
                    });
                }
            } finally {
                out.flush();
                if (tmp != null) {
                    out.close();
                }
            }
            if (tmp != null) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            err.println("serengeti export: " + rows[0] + (rows[0] == 1 ? " row" : " rows")
                    + (target != null ? " in " + target : "") + String.format(java.util.Locale.ROOT, " (%.1f s)",
                    (System.nanoTime() - t0) / 1e9));
            return 0;
        } catch (Fail f) {
            err.println("serengeti export: " + f.getMessage());
            err.println("serengeti export: stopped after " + rows[0] + (rows[0] == 1 ? " row" : " rows")
                    + (tmp != null ? "; nothing was kept" : "; what was written to standard output is incomplete"));
            return f.status;
        } catch (IOException e) {
            err.println("serengeti export: the output could not be written: " + e.getMessage()
                    + (tmp != null ? "; nothing was kept" : ""));
            return 1;
        } finally {
            delete(spool);
            delete(tmp);
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException shuttingDown) {
                // The hook runs anyway.
            }
        }
    }

    /** What is done with each row read. */
    interface Sink {
        void row(Map<String, Object> row, String json) throws IOException;
    }

    /** Reads the table page by page, in _id order, and hands each row on with its null values left out. */
    private static void pages(Remote remote, String table, String condition, int maxPage, Sink sink)
            throws IOException, Fail {
        String last = null;
        int page = Math.min(FIRST_PAGE, maxPage);
        long largest = 1;
        for (;;) {
            String where;
            if (last == null) {
                where = condition == null ? "" : " WHERE " + condition;
            } else {
                where = " WHERE _id > " + Printer.value(last) + (condition == null ? "" : " AND (" + condition + ")");
            }
            Remote.Reply r;
            try {
                r = remote.query("SELECT * FROM " + table + where + " ORDER BY _id LIMIT " + page, PAGE_BYTES);
            } catch (Remote.Unavailable e) {
                throw new Fail(3, remote.base() + " stayed unavailable: " + e.getMessage());
            }
            if (r.code() != 200) {
                throw new Fail(r.code() == 401 ? 2 : 1, remote.base() + ": " + r.answer().message());
            }
            Map<?, ?> body = r.body();
            if (!(body.get("columns") instanceof List<?> columns) || !(body.get("rows") instanceof List<?> list)) {
                throw new Fail(1, remote.base() + ": the answer is not a page of rows");
            }
            if (!list.isEmpty() && (columns.isEmpty() || !"_id".equals(columns.get(0)))) {
                throw new Fail(1, remote.base() + ": the page does not start with _id");
            }
            String pageLast = last;
            for (Object o : list) {
                List<?> values = (List<?>) o;
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < values.size() && i < columns.size(); i++) {
                    if (values.get(i) != null) {
                        row.put((String) columns.get(i), values.get(i));
                    }
                }
                if (!(row.get("_id") instanceof String id) || id.equals(pageLast)) {
                    throw new Fail(1, remote.base() + ": a page repeated or lost its place");
                }
                pageLast = id;
                String json = Json.write(row);
                largest = Math.max(largest, json.length());
                sink.row(row, json);
            }
            // A page ends early with `truncated` when its answer reached the node's bound on size: read on.
            if (list.isEmpty() || list.size() < page && !Boolean.TRUE.equals(body.get("truncated"))) {
                return;
            }
            last = pageLast;
            // Sized from the largest row seen, not the mean, and at most doubled each time: rows that grow along
            // the table made a page of 60 MB from the mean of small ones (review 22l).
            page = (int) Math.max(1, Math.min(Math.min(maxPage, 2L * page), PAGE_BYTES / Math.max(1, largest)));
        }
    }

    /** The CSV: the header, then one record per row of the spool file. */
    private static void writeCsv(Path spool, List<String> columns, Writer out) throws IOException {
        out.write(String.join(",", columns));
        out.write("\r\n");
        try (BufferedReader in = Files.newBufferedReader(spool, StandardCharsets.UTF_8)) {
            StringBuilder b = new StringBuilder();
            for (String line; (line = in.readLine()) != null; ) {
                Map<String, Object> row = Json.parseObject(line);
                b.setLength(0);
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    b.append(Csv.field(row.get(columns.get(i))));
                }
                b.append("\r\n");
                out.write(b.toString());
            }
        }
    }

    private static java.nio.file.attribute.FileAttribute<?>[] privateFile() {
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return new java.nio.file.attribute.FileAttribute<?>[] {java.nio.file.attribute.PosixFilePermissions
                    .asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))};
        }
        return new java.nio.file.attribute.FileAttribute<?>[0];
    }

    private static void delete(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                // left behind; the next export replaces it
            }
        }
    }

    private static int number(String v, int min, int max) {
        try {
            int n = Integer.parseInt(v);
            return n >= min && n <= max ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
