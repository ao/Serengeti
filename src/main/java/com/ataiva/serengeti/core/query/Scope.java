package com.ataiva.serengeti.core.query;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What an account limited by a list may reach. Each entry of the list is a database name, {@code db}, which gives
 * the database and every table in it, or a table name, {@code db.table}, which gives that table only: not the
 * database itself (CREATE and DROP DATABASE, the change feed of the whole database), and no other table of it.
 * An account with a table of a database sees that database in SHOW DATABASES, and SHOW TABLES IN it lists the
 * tables it may use.
 *
 * <p>Every decision depends on the list and the names a statement or request gives, never on what is stored, so
 * a refusal reads the same whether or not the database or table exists.
 */
public final class Scope {
    private final Set<String> databases = new HashSet<>();
    private final Map<String, Set<String>> tables = new HashMap<>();

    /**
     * An entry as lists are stored: the parser's form of a name, or two joined by a dot. Not checked against the
     * keywords, so that a word reserved by a later version does not make a stored list unreadable.
     */
    private static final Pattern STORED = Pattern.compile("[a-z_][a-z0-9_]{0,62}(\\.[a-z_][a-z0-9_]{0,62})?");

    private Scope() {}

    /** Whether {@code e} is an entry in the form lists are stored in (see {@link #entry}). */
    public static boolean stored(String e) {
        return e != null && STORED.matcher(e).matches();
    }

    /**
     * The scope of a list of entries in the stored form ({@link #stored}).
     *
     * @throws IllegalArgumentException if an entry is not in that form
     */
    public static Scope of(Collection<String> entries) {
        Scope s = new Scope();
        for (String e : entries) {
            if (!stored(e)) {
                throw new IllegalArgumentException("not an entry of a list: " + shown(String.valueOf(e)));
            }
            int dot = e.indexOf('.');
            if (dot < 0) {
                s.databases.add(e);
            } else {
                s.tables.computeIfAbsent(e.substring(0, dot), k -> new HashSet<>()).add(e.substring(dot + 1));
            }
        }
        return s;
    }

    /**
     * The entry that {@code written} stands for, as the parser folds names: {@code db} or {@code db.table}, each
     * name as {@link Query#nameOf} takes it. Null if it is neither.
     */
    public static String entry(String written) {
        if (written == null) {
            return null;
        }
        int dot = written.indexOf('.');
        if (dot < 0) {
            return Query.nameOf(written);
        }
        String db = Query.nameOf(written.substring(0, dot));
        String table = Query.nameOf(written.substring(dot + 1)); // null if it holds another dot
        return db == null || table == null ? null : db + "." + table;
    }

    /** Whether the list gives database {@code db} itself, and so every table of it. */
    public boolean database(String db) {
        return databases.contains(db);
    }

    /** Whether the list gives table {@code table} of {@code db}: by the database's entry or the table's. */
    public boolean table(String db, String table) {
        return databases.contains(db) || tables.getOrDefault(db, Set.of()).contains(table);
    }

    /** Whether the list gives {@code db} or any table of it: SHOW DATABASES shows it then, if it exists. */
    public boolean touches(String db) {
        return databases.contains(db) || tables.containsKey(db);
    }

    /**
     * Why an account with this list may not do what {@code reach} names, or null if it may. SHOW DATABASES is
     * always allowed (it lists only what {@link #touches} accepts).
     */
    public String refusal(Reach reach) {
        if (reach instanceof Reach.Listing) {
            return null;
        }
        if (reach instanceof Reach.Database d) {
            if (database(d.name())) {
                return null;
            }
            return touches(d.name())
                    ? "this account may not use database " + shown(d.name()) + " as a whole, only some of its tables"
                    : noDatabase(d.name());
        }
        if (reach instanceof Reach.Tables t) {
            return touches(t.db()) ? null : noDatabase(t.db());
        }
        if (reach instanceof Reach.Table t) {
            if (table(t.db(), t.table())) {
                return null;
            }
            return touches(t.db()) ? "this account may not use table " + shown(t.db()) + "." + shown(t.table())
                    : noDatabase(t.db());
        }
        return "this account may not run this statement"; // no other kind: refused, not let through
    }

    private static String noDatabase(String db) {
        return "this account may not use database " + shown(db);
    }

    /** A name as a message shows it: names from a request's parameters can be any length. */
    private static String shown(String name) {
        return name.length() > 64 ? name.substring(0, 64) + "..." : name;
    }
}
