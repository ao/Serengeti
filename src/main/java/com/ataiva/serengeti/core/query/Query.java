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

    public Executor executor() {
        return executor;
    }
}
