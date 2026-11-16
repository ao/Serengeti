package com.ataiva.serengeti.core.query;

import java.util.Objects;

/**
 * What a statement reaches, for an account limited to some databases (see {@link Query#check}). Every
 * statement but SHOW DATABASES names one database; SHOW DATABASES names none and lists only the ones the
 * account may use.
 */
public sealed interface Reach {
    /** The one database a statement names: refused unless the account may use it, whether or not it exists. */
    record Database(String name) implements Reach {
        public Database {
            Objects.requireNonNull(name, "name");
        }
    }

    /** The names of the databases (SHOW DATABASES): only those the account may use are shown. */
    record Listing() implements Reach {}

    /**
     * What {@code s} reaches. Decided once per statement type, with no default: a statement type added later
     * does not compile until it has a method here.
     */
    static Reach of(Statement s) {
        return s.accept(new Statement.Visitor<Reach>() {
            @Override
            public Reach createDatabase(Statement.CreateDatabase c) {
                return new Database(c.db());
            }

            @Override
            public Reach dropDatabase(Statement.DropDatabase d) {
                return new Database(d.db());
            }

            @Override
            public Reach createTable(Statement.CreateTable c) {
                return new Database(c.db());
            }

            @Override
            public Reach dropTable(Statement.DropTable d) {
                return new Database(d.db());
            }

            @Override
            public Reach showDatabases(Statement.ShowDatabases d) {
                return new Listing();
            }

            @Override
            public Reach showTables(Statement.ShowTables t) {
                return new Database(t.db());
            }

            @Override
            public Reach createIndex(Statement.CreateIndex c) {
                return new Database(c.db());
            }

            @Override
            public Reach dropIndex(Statement.DropIndex d) {
                return new Database(d.db());
            }

            @Override
            public Reach showIndexes(Statement.ShowIndexes i) {
                return new Database(i.db());
            }

            @Override
            public Reach showConflicts(Statement.ShowConflicts c) {
                return new Database(c.db());
            }

            @Override
            public Reach insert(Statement.Insert i) {
                return new Database(i.db());
            }

            @Override
            public Reach select(Statement.Select q) {
                return new Database(q.db());
            }

            @Override
            public Reach update(Statement.Update u) {
                return new Database(u.db());
            }

            @Override
            public Reach delete(Statement.Delete d) {
                return new Database(d.db());
            }
        });
    }
}
