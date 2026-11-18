package com.ataiva.serengeti.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ataiva.serengeti.core.storage.FsyncPolicy;
import com.ataiva.serengeti.core.storage.Hlc;
import com.ataiva.serengeti.core.storage.StorageEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

/**
 * For any statement the generators of {@link QueryProperties} make (every type), printed and parsed again:
 * an account limited to some databases may run it exactly when the database it names (its {@code db}, read by
 * reflection) is one of them, and the refusal names that database.
 */
class DatabaseLimitProperties {
    private static Path dir;
    private static StorageEngine engine;
    private static Query query;

    @BeforeContainer
    static void open() throws IOException {
        dir = Files.createTempDirectory("limit-properties");
        engine = StorageEngine.open(dir, FsyncPolicy.OFF);
        query = new Query(engine, new Hlc("n1"));
    }

    @AfterContainer
    static void close() throws IOException {
        engine.close();
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Provide
    Arbitrary<Statement> statements() {
        return new QueryProperties().statements();
    }

    @Provide
    Arbitrary<List<String>> lists() {
        return Arbitraries.oneOf(Arbitraries.of("a", "b", "x1", "_", "price"),
                Arbitraries.strings().withCharRange('a', 'c').ofMinLength(1).ofMaxLength(2))
                .filter(s -> Query.nameOf(s) != null).list().uniqueElements().ofMinSize(1).ofMaxSize(4);
    }

    @Property(tries = 3000)
    void aLimitedAccountRunsExactlyTheStatementsNamingItsDatabases(@ForAll("statements") Statement s,
            @ForAll("lists") List<String> databases, @ForAll boolean mayWrite) {
        String text = Printer.print(s);
        Reach reach = DatabaseLimitTest.expectedReach(s);
        assertEquals(reach, Reach.of(Parser.parse(text)), text);
        boolean roleRefuses = !mayWrite && !Query.reads(s);
        String db = DatabaseLimitTest.databaseOf(reach);
        if (db != null && !databases.contains(db) && !roleRefuses) {
            ForbiddenException e = assertThrows(ForbiddenException.class, () -> query.check(text, mayWrite, databases), text);
            assertEquals("this account may not use database " + db, e.getMessage());
        } else if (roleRefuses) {
            assertThrows(ForbiddenException.class, () -> query.check(text, mayWrite, databases), text);
        } else {
            assertEquals(s, query.check(text, mayWrite, databases).statement(), text);
        }
        // Without a list, only the role decides.
        if (!roleRefuses) {
            query.check(text, mayWrite, null);
        }
    }
}
