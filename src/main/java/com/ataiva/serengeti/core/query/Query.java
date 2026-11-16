package com.ataiva.serengeti.core.query;

import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;

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
        Statement s = Parser.parse(text);
        if (!mayWrite && !reads(s)) {
            throw new ForbiddenException("this account has the read role: it can run SELECT and SHOW only");
        }
        return executor.execute(s);
    }

    /** True for the statements that change nothing. */
    public static boolean reads(Statement s) {
        return s instanceof Statement.Select || s instanceof Statement.ShowDatabases || s instanceof Statement.ShowTables
                || s instanceof Statement.ShowConflicts || s instanceof Statement.ShowIndexes;
    }

    public Executor executor() {
        return executor;
    }
}
