package com.ataiva.serengeti.core.query;

import java.util.Objects;

/**
 * What a statement reaches, for an account limited by a list (see {@link Query#check} and {@link Scope}). CREATE
 * and DROP DATABASE reach the database itself; SHOW TABLES reaches the tables of one database; SHOW DATABASES
 * names none and lists only the ones the account may use; every other statement reaches one table.
 */
public sealed interface Reach {
    /** A database itself (CREATE and DROP DATABASE): needs the database's entry, whether or not it exists. */
    record Database(String name) implements Reach {
        public Database {
            Objects.requireNonNull(name, "name");
        }
    }

    /** One table of one database: needs the database's entry or the table's, whether or not either exists. */
    record Table(String db, String table) implements Reach {
        public Table {
            Objects.requireNonNull(db, "db");
            Objects.requireNonNull(table, "table");
        }
    }

    /** The tables of a database (SHOW TABLES): needs any entry for it, and lists only the tables it gives. */
    record Tables(String db) implements Reach {
        public Tables {
            Objects.requireNonNull(db, "db");
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
                return new Table(c.db(), c.table());
            }

            @Override
            public Reach dropTable(Statement.DropTable d) {
                return new Table(d.db(), d.table());
            }

            @Override
            public Reach showDatabases(Statement.ShowDatabases d) {
                return new Listing();
            }

            @Override
            public Reach showTables(Statement.ShowTables t) {
                return new Tables(t.db());
            }

            @Override
            public Reach createIndex(Statement.CreateIndex c) {
                return new Table(c.db(), c.table());
            }

            @Override
            public Reach dropIndex(Statement.DropIndex d) {
                return new Table(d.db(), d.table());
            }

            @Override
            public Reach showIndexes(Statement.ShowIndexes i) {
                return new Table(i.db(), i.table());
            }

            @Override
            public Reach showConflicts(Statement.ShowConflicts c) {
                return new Table(c.db(), c.table());
            }

            @Override
            public Reach insert(Statement.Insert i) {
                return new Table(i.db(), i.table());
            }

            @Override
            public Reach select(Statement.Select q) {
                return new Table(q.db(), q.table());
            }

            @Override
            public Reach update(Statement.Update u) {
                return new Table(u.db(), u.table());
            }

            @Override
            public Reach delete(Statement.Delete d) {
                return new Table(d.db(), d.table());
            }
        });
    }
}
