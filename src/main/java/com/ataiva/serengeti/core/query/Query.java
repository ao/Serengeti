package com.ataiva.serengeti.core.query;

import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;

/**
 * Parses and runs query text. This is the entry point for callers such as an HTTP handler:
 * a {@link QueryException} (including {@link ParseException}) means the query was at fault and
 * nothing was written; an {@link IOException} means storage failed.
 */
public final class Query {
    private final Executor executor;

    public Query(StorageEngine engine, Hlc clock) {
        this(new Executor(engine, clock));
    }

    public Query(Executor executor) {
        this.executor = executor;
    }

    public Result run(String text) throws IOException {
        return executor.execute(Parser.parse(text));
    }

    /**
     * As {@link #run(String)}, for a caller that may only read when {@code mayWrite} is false: then any
     * statement but SELECT and SHOW is refused with {@link ForbiddenException} before it runs.
     */
    public Result run(String text, boolean mayWrite) throws IOException {
        return check(text, mayWrite, null).run();
    }

    /** As {@link #run(String, boolean)}, for a caller limited to {@code databases} (null: every database). */
    public Result run(String text, boolean mayWrite, Collection<String> databases) throws IOException {
        return check(text, mayWrite, databases).run();
    }

    /**
     * Parses {@code text} and checks that the caller may run it, reading and writing nothing. A caller that
     * may not write may run SELECT and SHOW only. A caller limited to {@code databases} (null: every database)
     * may run a statement only if it names one of them, whether or not the one it names exists, and SHOW
     * DATABASES shows only those that exist.
     *
     * @throws ParseException if the text is not a statement
     * @throws ForbiddenException if the caller may not run it
     */
    public Checked check(String text, boolean mayWrite, Collection<String> databases) {
        Statement s = Parser.parse(text);
        if (!mayWrite && !reads(s)) {
            throw new ForbiddenException("this account has the read role: it can run SELECT and SHOW only");
        }
        Set<String> only = databases == null ? null : Set.copyOf(databases);
        Reach reach = Reach.of(s);
        if (only != null && reach instanceof Reach.Database d && !only.contains(d.name())) {
            throw new ForbiddenException("this account may not use database " + d.name());
        }
        return new Checked(s, reach, only);
    }

    /** A statement the caller may run, from {@link #check}. */
    public final class Checked {
        private final Statement statement;
        private final Reach reach;
        private final Set<String> databases;

        private Checked(Statement statement, Reach reach, Set<String> databases) {
            this.statement = statement;
            this.reach = reach;
            this.databases = databases;
        }

        public Statement statement() {
            return statement;
        }

        /** Runs it. See {@link Executor#execute}. */
        public Result run() throws IOException {
            if (databases != null) {
                if (reach instanceof Reach.Listing) {
                    return executor.databases(databases::contains);
                }
                if (!(reach instanceof Reach.Database)) {
                    throw new ForbiddenException("this account may not run this statement"); // no other kind
                }
            }
            return executor.execute(statement);
        }
    }

    /** True for the statements that change nothing. */
    public static boolean reads(Statement s) {
        return s instanceof Statement.Select || s instanceof Statement.ShowDatabases || s instanceof Statement.ShowTables
                || s instanceof Statement.ShowConflicts || s instanceof Statement.ShowIndexes;
    }

    /**
     * The name that {@code written} stands for in query text, as the parser gives it (folded to lowercase), or
     * null if it is not a name there: ASCII letters, digits and {@code _}, not starting with a digit, at most
     * 63 characters, and not a keyword.
     */
    public static String nameOf(String written) {
        if (written == null) {
            return null;
        }
        for (int i = 0; i < written.length(); i++) {
            if (written.charAt(i) > 127) {
                return null; // toLowerCase would fold some of these to ASCII letters, which the lexer never does
            }
        }
        String lower = written.toLowerCase(java.util.Locale.ROOT);
        return Lexer.isValidName(lower) ? lower : null;
    }

    public Executor executor() {
        return executor;
    }
}
