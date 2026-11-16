"""The query language at its edges (docs/QUERY.md): names, words, quoting, ids, numbers, limits, ordering,
nesting, every comparison across types and NULL with and without an index, and SHOW CONFLICTS."""

import functools
import json
import random
import re
from fractions import Fraction

from common import Client, bad, check, compare, expect, lit, section, short, sort_key_cmp, utf16, utf16_len

KEYWORDS = ["and", "asc", "by", "count", "create", "database", "databases", "delete", "desc", "drop", "exists",
            "false", "from", "if", "in", "insert", "into", "is", "limit", "not", "null", "or", "order", "select",
            "set", "show", "table", "tables", "true", "update", "values", "where"]
OPS = ["=", "!=", "<>", "<", "<=", ">", ">="]
MISSING = "missing"


def err(c, query):
    """Status and message of a statement that should fail."""
    s, j, data = c.q(query)
    return s, (j or {}).get("error", data.decode("utf-8", "replace"))


def refused(name, c, query, needle=None):
    s, msg = err(c, query)
    good = s == 400 and isinstance(msg, str) and msg != "" and (needle is None or needle in msg)
    return check(name, good, f"{s} {short(msg)!r} for {short(query, 120)!r}")


def accepted(name, c, query):
    s, j, data = c.q(query)
    check(name, s == 200, f"{s} {short(data.decode('utf-8', 'replace'))} for {short(query, 120)!r}")
    return j


def run(host):
    c = Client(host)
    c.ok("CREATE DATABASE IF NOT EXISTS q")
    names(c)
    words(c)
    strings_and_ids(c)
    numbers(c)
    statements(c)
    limits(c)
    ordering(c)
    nesting(c)
    comparisons(c)
    in_and_like(c, random.Random(11))
    aggregates(c, random.Random(13))
    index_model(c, random.Random(7))
    conflicts(c)
    big_insert(c)
    c.close()


def names(c):
    section("names: 63 characters, case, characters")
    n63 = "n" * 63
    accepted("a 63-character database name", c, f"CREATE DATABASE {n63}")
    accepted("a 63-character table name", c, f"CREATE TABLE {n63}.{'t' * 63}")
    accepted("a 63-character column name", c, f"INSERT INTO {n63}.{'t' * 63} ({'c' * 63}) VALUES (1)")
    expect("and it reads back", [[1]], c.rows(f"SELECT {'c' * 63} FROM {n63}.{'t' * 63}"))
    refused("a 64-character database name", c, f"CREATE DATABASE {'n' * 64}", "at most 63")
    refused("a 64-character table name", c, f"CREATE TABLE q.{'t' * 64}", "at most 63")
    refused("a 64-character column name", c, f"SELECT {'c' * 64} FROM q.t", "at most 63")
    refused("a 10,000-character name", c, f"SELECT * FROM q.{'t' * 10000}", "at most 63")
    accepted("an upper-case name", c, "CREATE DATABASE MixedCase")
    check("is folded to lower case", ["mixedcase"] in c.rows("SHOW DATABASES"), repr(c.rows("SHOW DATABASES")))
    refused("and the same name in lower case already exists", c, "CREATE DATABASE mixedcase", "already exists")
    accepted("underscore names", c, "CREATE DATABASE _")
    accepted("a name of underscores and digits", c, "CREATE TABLE _.__9")
    refused("a name starting with a digit", c, "CREATE DATABASE 9lives")
    refused("a non-ASCII name", c, "CREATE DATABASE caf" + chr(0xE9))
    refused("a name with a dash", c, "CREATE DATABASE a-b")
    refused("a quoted name", c, 'CREATE DATABASE "quoted"')
    refused("a three-part table name", c, "CREATE TABLE q.a.b")
    refused("a table name without a database", c, "CREATE TABLE lonely")
    refused("an empty table name", c, "CREATE TABLE q.")
    for stmt in [f"DROP DATABASE {n63}", "DROP DATABASE mixedcase", "DROP DATABASE _"]:
        c.ok(stmt)


def words(c):
    section("reserved and non-reserved words")
    bad_db, bad_col = [], []
    for kw in KEYWORDS:
        for spelled in (kw, kw.upper()):
            s, msg = err(c, f"CREATE DATABASE {spelled}")
            if not (s == 400 and ("keyword" in msg or kw == "if")):  # CREATE DATABASE IF reads as IF NOT EXISTS
                bad_db.append((spelled, s, msg))
            s, msg = err(c, f"SELECT {spelled} FROM q.t") if kw not in ("count",) else (400, "keyword")
            if s != 400:
                bad_col.append((spelled, s, msg))
    check(f"each of the {len(KEYWORDS)} keywords is refused as a database name", not bad_db, repr(bad_db[:5]))
    check("and as a column name", not bad_col, repr(bad_col[:5]))
    refused("a keyword as a table name", c, "CREATE TABLE q.select", "keyword")
    refused("a keyword as an INSERT column", c, "INSERT INTO q.t (values) VALUES (1)", "keyword")
    refused("a keyword as an UPDATE column", c, "UPDATE q.t SET where = 1")
    accepted("words that start with a keyword are names", c, "CREATE DATABASE selected")
    accepted("so are ones that contain one", c, "CREATE TABLE selected.order_by")
    c.ok("DROP DATABASE selected")
    # CONFLICTS, INDEX, INDEXES and ON keep their meaning only where the grammar puts them.
    steps = ["CREATE DATABASE conflicts", "CREATE TABLE conflicts.index", "CREATE TABLE conflicts.on",
             "CREATE TABLE conflicts.indexes", "CREATE INDEX ON conflicts.index (on)",
             "CREATE INDEX IF NOT EXISTS ON conflicts.on (index)", "CREATE INDEX ON conflicts.indexes (conflicts)",
             "INSERT INTO conflicts.index (_id, on, indexes, conflicts) VALUES ('a', 1, 2, 3), ('b', 2, 3, 4)",
             "INSERT INTO conflicts.on (index) VALUES ('x')", "UPDATE conflicts.index SET indexes = 9 WHERE on = 2"]
    for s in steps:
        accepted(f"non-reserved words as names: {s}", c, s)
    expect("SELECT on, indexes FROM conflicts.index WHERE on = 2", [[2, 9]],
           c.rows("SELECT on, indexes FROM conflicts.index WHERE on = 2"))
    expect("SHOW INDEXES IN conflicts.index", [["on"]], c.rows("SHOW INDEXES IN conflicts.index"))
    expect("SHOW INDEXES IN conflicts.on", [["index"]], c.rows("SHOW INDEXES IN conflicts.on"))
    expect("SHOW CONFLICTS IN conflicts.conflicts is refused (no such table)", 400, c.q("SHOW CONFLICTS IN conflicts.conflicts")[0])
    accepted("SHOW CONFLICTS IN conflicts.index", c, "SHOW CONFLICTS IN conflicts.index")
    expect("SHOW TABLES IN conflicts", [["index"], ["indexes"], ["on"]], c.rows("SHOW TABLES IN conflicts"))
    accepted("DROP INDEX ON conflicts.index (on)", c, "DROP INDEX ON conflicts.index (on)")
    refused("CREATE INDEX without ON", c, "CREATE INDEX conflicts.index (on)")
    refused("SHOW INDEX (singular)", c, "SHOW INDEX IN conflicts.index")
    refused("SHOW CONFLICT (singular)", c, "SHOW CONFLICTS conflicts.index")
    c.ok("DROP DATABASE conflicts")
    # Nor are GROUP, LIKE and the aggregate names other than COUNT.
    steps = ["CREATE DATABASE group", "CREATE TABLE group.like", "CREATE INDEX ON group.like (sum)",
             "INSERT INTO group.like (_id, sum, min, max, avg, group, like) VALUES ('a', 1, 2, 3, 4, 'g', 'x'), "
             "('b', 5, 6, 7, 8, 'g', 'y')", "UPDATE group.like SET like = 'z' WHERE group = 'g' AND like LIKE 'y%'"]
    for s in steps:
        accepted(f"new non-reserved words as names: {s}", c, s)
    expect("SELECT sum, min, max, avg, group, like", [[1, 2, 3, 4, "g", "x"], [5, 6, 7, 8, "g", "z"]],
           c.rows("SELECT sum, min, max, avg, group, like FROM group.like"))
    expect("sum(sum), max(group) and a GROUP BY group", [["g", 6, "g", 2]],
           c.rows("SELECT group, sum(sum), max(group), COUNT(like) FROM group.like GROUP BY group ORDER BY sum(sum)"))
    expect("like LIKE and like IN", [["b"]], c.rows("SELECT _id FROM group.like WHERE like LIKE 'z' AND like IN ('z')"))
    c.ok("DROP DATABASE group")

    section("keywords, blanks and comments")
    c.ok("CREATE TABLE IF NOT EXISTS q.t")
    for label, stmt in [("mixed-case keywords", "sElEcT * fRoM q.t wHeRe _id iS nOt NuLl"),
                        ("tabs, CR LF and form feeds", "SELECT\t*\r\nFROM\fq.t"),
                        ("a comment between tokens", "SELECT * -- the columns\nFROM q.t"),
                        ("a comment ended by CR", "SELECT * -- x\rFROM q.t"),
                        ("a comment at the end", "SELECT * FROM q.t -- no newline"),
                        ("a trailing semicolon", "SELECT * FROM q.t;"),
                        ("a semicolon then a comment", "SELECT * FROM q.t; -- done"),
                        ("blanks around the semicolon", "SELECT * FROM q.t  ;  \n"),
                        ("no blanks around symbols", "SELECT*FROM q.t WHERE _id='a'AND(_id<>'b')"),
                        ("a dashes-only comment", "SELECT * FROM q.t ----"),
                        ("blanks around the dot", "SELECT * FROM q . t")]:
        accepted(label, c, stmt)
    for label, stmt in [("an empty query", ""), ("only blanks", "  \n\t "), ("only a comment", "-- nothing"),
                        ("only a semicolon", ";"), ("two semicolons", "SELECT * FROM q.t;;"),
                        ("two statements", "SELECT * FROM q.t; SELECT * FROM q.t"),
                        ("a vertical tab", "SELECT\x0b* FROM q.t"), ("a NUL byte", "SELECT * FROM q.t\x00"),
                        ("a block comment", "SELECT /* x */ * FROM q.t"), ("a hash comment", "SELECT * FROM q.t # x"),
                        ("a no-break space", "SELECT" + chr(0xA0) + "* FROM q.t"), ("a full-width asterisk", "SELECT " + chr(0xFF0A) + " FROM q.t"),
                        ("a single dash", "SELECT * FROM q.t -"), ("a backtick name", "SELECT `a` FROM q.t"),
                        ("a statement word that is not one", "EXPLAIN SELECT * FROM q.t"),
                        ("an emoji", "SELECT \U0001F600 FROM q.t")]:
        refused(label, c, stmt)
    s, msg = err(c, "SELECT * FORM q.t")
    expect("the documented example message", "expected FROM but found name 'form' at position 9", msg)
    positions = []
    for stmt in ["SELECT * FROM", "SELEC * FROM q.t", "SELECT * FROM q.t WHERE", "INSERT INTO q.t (a) VALUES (1, 2)",
                 "SELECT * FROM q.t WHERE a = 'unterminated", "SELECT * FROM q.t LIMIT -1", "x" * 70]:
        s, msg = err(c, stmt)
        m = re.search(r"at position (\d+)$", msg)
        positions.append((stmt[:30], s, m is not None and 0 <= int(m.group(1)) <= utf16_len(stmt)))
    check("parse errors end with 'at position N', N inside the text", all(p[1] == 400 and p[2] for p in positions),
          repr(positions))
    emoji = "SELECT * FROM q.t WHERE a = '" + chr(0x1F600) + chr(0x1F600) + "' )"
    s, msg = err(c, emoji)
    expect("positions count UTF-16 code units: two emoji before the error add 4", f"at position {len(emoji) + 1}",
           msg[-len("at position 00"):])


def strings_and_ids(c):
    section("quoting, unicode values and ids")
    c.ok("CREATE TABLE IF NOT EXISTS q.s")
    for label, text, want in [("a doubled quote", "'It''s'", "It's"), ("an empty string", "''", ""),
                              ("only a quote", "''''", "'"), ("two quotes", "''''''", "''"),
                              ("a backslash is literal", "'a\\b'", "a\\b"), ("a backslash before a quote", "'a\\'''", "a\\'"),
                              ("dashes in a string", "'a -- b'", "a -- b"), ("a semicolon in a string", "'a;b'", "a;b"),
                              ("a newline in a string", "'a\nb'", "a\nb"), ("a NUL in a string", "'a\x00b'", "a\x00b"),
                              ("CJK", "'" + chr(0x4E2D) + chr(0x6587) + "'", chr(0x4E2D) + chr(0x6587)), ("an emoji", "'" + chr(0x1F600) + "'", chr(0x1F600)),
                              ("a flag", "'\U0001F1FF\U0001F1E6'", "\U0001F1FF\U0001F1E6"),
                              ("combining marks", "'e" + chr(0x301) + chr(0x302) + "'", "e" + chr(0x301) + chr(0x302)),
                              ("right-to-left", "'" + chr(0x202E) + chr(0x5E9) + "'", chr(0x202E) + chr(0x5E9)),
                              ("a keyword in a string", "'SELECT'", "SELECT"),
                              ("a 100,000-character string", "'" + "z" * 100000 + "'", "z" * 100000)]:
        j = c.q(f"INSERT INTO q.s (v) VALUES ({text})")
        if not expect(f"{label}: insert", 200, j[0]):
            continue
        rid = j[1]["ids"][0]
        expect(f"{label}: reads back", [[want]], c.rows(f"SELECT v FROM q.s WHERE _id = '{rid}'"))
        expect(f"{label}: found by equality", 1, c.count("q.s", f"_id = '{rid}' AND v = {lit(want)}"))
    refused("an unterminated string", c, "INSERT INTO q.s (v) VALUES ('abc)", "unterminated string")
    refused("a string whose last quote is doubled", c, "INSERT INTO q.s (v) VALUES ('abc'')", "unterminated string")
    refused("double-quoted strings are not strings", c, 'INSERT INTO q.s (v) VALUES ("abc")')

    ids = ["a/b", "a/../b", "/", ".", "..", "a.b", " ", "  lead", "trail ", "it's", '"', "\\", "%2F", "a?b#c",
           chr(0xE9), "e" + chr(0x301), chr(0x1F600), chr(0x4E2D), "\x00", "\n", "\t", "NULL", "null", "TRUE", "1", "-0",
           "0x10", "_id", "*", "--", "'; DROP DATABASE q; --", "x" * 1000, "y" * 60000, chr(0xA0), chr(0xFEFF), chr(0x202E) + "abc"]
    c.ok("CREATE TABLE IF NOT EXISTS q.ids")
    problems = []
    for rid in ids:
        L = lit(rid)
        steps = [
            (f"INSERT INTO q.ids (_id, n) VALUES ({L}, 1)", lambda j: j["ids"] == [rid]),
            (f"SELECT _id, n FROM q.ids WHERE _id = {L}", lambda j: j["rows"] == [[rid, 1]]),
            (f"UPDATE q.ids SET n = 2 WHERE _id = {L}", lambda j: j["affected"] == 1),
            (f"SELECT COUNT(*) FROM q.ids WHERE _id = {L} AND n = 2", lambda j: j["rows"] == [[1]]),
            (f"DELETE FROM q.ids WHERE _id = {L}", lambda j: j["affected"] == 1),
            (f"SELECT COUNT(*) FROM q.ids WHERE _id = {L}", lambda j: j["rows"] == [[0]]),
            (f"INSERT INTO q.ids (_id, n) VALUES ({L}, 3)", lambda j: j["ids"] == [rid]),
        ]
        for stmt, good in steps:
            s, j, data = c.q(stmt)
            if s != 200 or not good(j):
                problems.append((short(repr(rid), 40), short(stmt, 60), s, short(data.decode("utf-8", "replace"), 100)))
                break
    check(f"{len(ids)} unusual ids: insert, find, update, delete and reuse", not problems, repr(problems[:4]))
    got = sorted(c.ids("SELECT _id FROM q.ids"), key=lambda s: s.encode("utf-16-be"))
    expect("and they come back in _id order (UTF-16 code units)", got, c.ids("SELECT _id FROM q.ids"))
    expect("all of them", sorted(ids), sorted(c.ids("SELECT _id FROM q.ids")))
    for label, v in [("an empty _id", "''"), ("a number _id", "1"), ("a NULL _id", "NULL"), ("a TRUE _id", "TRUE"),
                     ("a double _id", "1.5")]:
        refused(f"{label} is refused", c, f"INSERT INTO q.ids (_id) VALUES ({v})", "_id must be a non-empty string")
    refused("an _id that exists", c, "INSERT INTO q.ids (_id) VALUES ('a/b')", "already exists")
    refused("the same _id twice in one INSERT", c, "INSERT INTO q.ids (_id) VALUES ('twice'), ('twice')", "already exists")
    refused("a new and an existing _id in one INSERT", c, "INSERT INTO q.ids (_id) VALUES ('fresh'), ('a/b')", "already exists")
    expect("and neither of those wrote anything", 0, c.count("q.ids", "_id = 'twice' OR _id = 'fresh'"))
    refused("UPDATE of _id", c, "UPDATE q.ids SET _id = 'z' WHERE _id = 'a/b'", "_id cannot be changed")
    refused("UPDATE of _id to the same value", c, "UPDATE q.ids SET _id = 'a/b' WHERE _id = 'a/b'", "_id cannot be changed")
    expect("_id compared with a number matches nothing", 0, c.count("q.ids", "_id = 1"))
    expect("_id != a number matches every row", len(ids), c.count("q.ids", "_id != 1"))
    expect("_id IS NULL matches nothing", 0, c.count("q.ids", "_id IS NULL"))
    expect("_id range", len([i for i in ids if i.encode("utf-16-be") >= "a".encode("utf-16-be")]),
           c.count("q.ids", "_id >= 'a'"))


def numbers(c):
    section("numbers")
    c.ok("CREATE TABLE IF NOT EXISTS q.n")
    for label, text, want in [("the largest long", "9223372036854775807", 9223372036854775807),
                              ("the smallest long", "-9223372036854775808", -9223372036854775808),
                              ("one past the largest long is a double", "9223372036854775808", 9.223372036854776e18),
                              ("a 40-digit integer", "9" * 40, 1e40), ("1e308", "1e308", 1e308),
                              ("1E-400 underflows to 0.0", "1E-400", 0.0), ("-0", "-0", 0), ("-0.0", "-0.0", -0.0),
                              ("0.1", "0.1", 0.1), ("2.5e+3", "2.5e+3", 2500.0), ("leading zeros", "007", 7),
                              ("4.9e-324", "4.9e-324", 5e-324)]:
        j = c.q(f"INSERT INTO q.n (v) VALUES ({text})")
        if not expect(f"{label}: insert", 200, j[0]):
            continue
        got = c.rows(f"SELECT v FROM q.n WHERE _id = '{j[1]['ids'][0]}'")[0][0]
        same = got == want and type(got) is type(want) and (str(got) == str(want) or want != 0)
        check(f"{label}: reads back as {want!r}", same, f"got {got!r}")
    for label, text in [("1e309", "1e309"), ("-1e309", "-1e309"), ("a 400-digit integer", "9" * 400),
                        ("a trailing dot", "1."), ("a leading dot", ".5"), ("an exponent without digits", "1e"),
                        ("an exponent sign without digits", "1e+"), ("hex", "0x10"), ("two dots", "1.2.3"),
                        ("letters after digits", "12abc"), ("a space after the minus", "- 1"), ("a plus sign", "+1"),
                        ("NaN", "NaN"), ("Infinity", "Infinity"), ("an underscore", "1_000"), ("a comma", "1,5")]:
        refused(f"{label} is refused", c, f"INSERT INTO q.n (_id, v) VALUES ('bad', {text})")
    expect("none of them wrote a row", 0, c.count("q.n", "_id = 'bad'"))
    refused("'--1' is a comment, so nothing follows '='", c, "SELECT * FROM q.n WHERE v = --1")
    expect("v = 0 finds 0, 0.0 and -0.0", 3, c.count("q.n", "v = 0 AND (v < 1e-300 AND v > -1e-300)"))
    expect("the largest long equals itself only, not the double 2^63", 1, c.count("q.n", "v = 9223372036854775807"))
    expect("and the double 2^63 is above it", 3, c.count("q.n", "v > 9223372036854775807"))


def statements(c):
    section("each statement at its edges")
    for label, stmt, want, needle in [
            ("CREATE DATABASE twice", "CREATE DATABASE q", 400, "already exists"),
            ("CREATE DATABASE IF NOT EXISTS on an existing one", "CREATE DATABASE IF NOT EXISTS q", 0, None),
            ("DROP DATABASE of a missing one", "DROP DATABASE nosuchdb", 400, "does not exist"),
            ("DROP DATABASE IF EXISTS of a missing one", "DROP DATABASE IF EXISTS nosuchdb", 0, None),
            ("CREATE TABLE in a missing database", "CREATE TABLE nosuchdb.t", 400, "does not exist"),
            ("CREATE TABLE IF NOT EXISTS in a missing database", "CREATE TABLE IF NOT EXISTS nosuchdb.t", 400,
             "does not exist"),
            ("CREATE TABLE twice", "CREATE TABLE q.t", 400, "already exists"),
            ("CREATE TABLE IF NOT EXISTS on an existing one", "CREATE TABLE IF NOT EXISTS q.t", 0, None),
            ("DROP TABLE of a missing one", "DROP TABLE q.nosuch", 400, "does not exist"),
            ("DROP TABLE in a missing database", "DROP TABLE nosuchdb.t", 400, "does not exist"),
            ("DROP TABLE IF EXISTS of a missing one", "DROP TABLE IF EXISTS q.nosuch", 0, None),
            ("DROP TABLE IF EXISTS in a missing database", "DROP TABLE IF EXISTS nosuchdb.t", 0, None),
            ("CREATE INDEX on a missing table", "CREATE INDEX ON q.nosuch (a)", 400, "does not exist"),
            ("CREATE INDEX on _id", "CREATE INDEX ON q.t (_id)", 400, "_id needs no index"),
            ("DROP INDEX that does not exist", "DROP INDEX ON q.t (nosuch)", 400, "has no index"),
            ("DROP INDEX IF EXISTS that does not exist", "DROP INDEX IF EXISTS ON q.t (nosuch)", 0, None),
            ("DROP INDEX IF EXISTS on a missing table", "DROP INDEX IF EXISTS ON q.nosuch (a)", 0, None),
            ("SHOW TABLES IN a missing database", "SHOW TABLES IN nosuchdb", 400, "does not exist"),
            ("SHOW INDEXES IN a missing table", "SHOW INDEXES IN q.nosuch", 400, "does not exist"),
            ("SHOW TABLES without IN", "SHOW TABLES", 400, None),
            ("SHOW DATABASES IN q", "SHOW DATABASES IN q", 400, None),
            ("INSERT into a missing table", "INSERT INTO q.nosuch (a) VALUES (1)", 400, "does not exist"),
            ("INSERT with a column twice", "INSERT INTO q.t (a, a) VALUES (1, 2)", 400, "listed twice"),
            ("INSERT with a column twice in other case", "INSERT INTO q.t (a, A) VALUES (1, 2)", 400, "listed twice"),
            ("INSERT with too few values", "INSERT INTO q.t (a, b) VALUES (1)", 400, "expected 2 values"),
            ("INSERT with too many values", "INSERT INTO q.t (a) VALUES (1, 2)", 400, "expected 1 values"),
            ("INSERT with a short second tuple", "INSERT INTO q.t (a, b) VALUES (1, 2), (3)", 400, None),
            ("INSERT with no columns", "INSERT INTO q.t () VALUES ()", 400, None),
            ("INSERT with no tuples", "INSERT INTO q.t (a) VALUES", 400, None),
            ("INSERT with a name as a value", "INSERT INTO q.t (a) VALUES (b)", 400, None),
            ("INSERT with an expression as a value", "INSERT INTO q.t (a) VALUES (1 + 1)", 400, None),
            ("INSERT with a trailing comma", "INSERT INTO q.t (a) VALUES (1),", 400, None),
            ("SELECT with no columns", "SELECT FROM q.t", 400, None),
            ("SELECT * and a column", "SELECT *, a FROM q.t", 400, None),
            ("SELECT with a trailing comma", "SELECT a, FROM q.t", 400, None),
            ("SELECT COUNT(*) and a column", "SELECT COUNT(*), a FROM q.t", 400, "GROUP BY"),
            ("SUM(*)", "SELECT SUM(*) FROM q.t", 400, "only COUNT takes *"),
            ("COUNT() of nothing", "SELECT COUNT() FROM q.t", 400, None),
            ("an aggregate of two columns", "SELECT SUM(a, b) FROM q.t", 400, None),
            ("an aggregate of an expression", "SELECT SUM(a + 1) FROM q.t", 400, None),
            ("aggregates without GROUP BY and ORDER BY", "SELECT SUM(a) FROM q.t ORDER BY a", 400, "ORDER BY"),
            ("aggregates without GROUP BY and LIMIT", "SELECT MIN(a), MAX(a) FROM q.t LIMIT 1", 400, "LIMIT"),
            ("GROUP BY with *", "SELECT * FROM q.t GROUP BY a", 400, "GROUP BY"),
            ("a column that is not grouped", "SELECT a, b FROM q.t GROUP BY a", 400, "not in GROUP BY"),
            ("GROUP BY a column twice", "SELECT a FROM q.t GROUP BY a, A", 400, "twice"),
            ("GROUP BY without a column", "SELECT a FROM q.t GROUP BY", 400, None),
            ("GROUP BY before WHERE", "SELECT a FROM q.t GROUP BY a WHERE a = 1", 400, None),
            ("ORDER BY a column that is not grouped", "SELECT a FROM q.t GROUP BY a ORDER BY b", 400, "GROUP BY"),
            ("ORDER BY an aggregate not listed", "SELECT a FROM q.t GROUP BY a ORDER BY count(*)", 400, "select list"),
            ("ORDER BY an aggregate without GROUP BY", "SELECT a FROM q.t ORDER BY SUM(a)", 400, "needs GROUP BY"),
            ("HAVING", "SELECT a, COUNT(*) FROM q.t GROUP BY a HAVING COUNT(*) > 1", 400, None),
            ("COUNT(DISTINCT a)", "SELECT COUNT(DISTINCT a) FROM q.t", 400, None),
            ("COUNT(*) with ORDER BY", "SELECT COUNT(*) FROM q.t ORDER BY a", 400, "ORDER BY"),
            ("COUNT(*) with LIMIT", "SELECT COUNT(*) FROM q.t LIMIT 1", 400, "LIMIT"),
            ("ORDER BY two columns", "SELECT * FROM q.t ORDER BY a, b", 400, None),
            ("ORDER BY without a column", "SELECT * FROM q.t ORDER BY", 400, None),
            ("LIMIT before ORDER BY", "SELECT * FROM q.t LIMIT 1 ORDER BY a", 400, None),
            ("two LIMITs", "SELECT * FROM q.t LIMIT 1 LIMIT 2", 400, None),
            ("a literal on the left", "SELECT * FROM q.t WHERE 1 = a", 400, None),
            ("two columns compared", "SELECT * FROM q.t WHERE a = b", 400, None),
            ("IS a value", "SELECT * FROM q.t WHERE a IS 1", 400, None),
            ("IS NOT without NULL", "SELECT * FROM q.t WHERE a IS NOT", 400, None),
            ("NOT NULL as a value", "SELECT * FROM q.t WHERE a = NOT NULL", 400, None),
            ("an unclosed parenthesis", "SELECT * FROM q.t WHERE (a = 1", 400, None),
            ("an extra closing parenthesis", "SELECT * FROM q.t WHERE a = 1)", 400, None),
            ("empty parentheses", "SELECT * FROM q.t WHERE ()", 400, None),
            ("AND without a right side", "SELECT * FROM q.t WHERE a = 1 AND", 400, None),
            ("a dangling NOT", "SELECT * FROM q.t WHERE NOT", 400, None),
            ("== as an operator", "SELECT * FROM q.t WHERE a == 1", 400, None),
            ("=< as an operator", "SELECT * FROM q.t WHERE a =< 1", 400, None),
            ("! alone", "SELECT * FROM q.t WHERE a ! 1", 400, None),
            ("an empty IN list", "SELECT * FROM q.t WHERE a IN ()", 400, None),
            ("IN without parentheses", "SELECT * FROM q.t WHERE a IN 1", 400, None),
            ("IN of a column", "SELECT * FROM q.t WHERE a IN (b)", 400, None),
            ("IN with a trailing comma", "SELECT * FROM q.t WHERE a IN (1,)", 400, None),
            ("IN of a subquery", "SELECT * FROM q.t WHERE a IN (SELECT a FROM q.t)", 400, None),
            ("1,001 IN values", "SELECT * FROM q.t WHERE a IN (" + ", ".join(["1"] * 1001) + ")", 400, "at most 1000"),
            ("NOT without IN or LIKE", "SELECT * FROM q.t WHERE a NOT = 1", 400, "IN or LIKE"),
            ("LIKE with an unknown escape", "SELECT * FROM q.t WHERE a LIKE 'a\\b'", 400, "LIKE pattern"),
            ("LIKE ending in a backslash", "SELECT * FROM q.t WHERE a LIKE 'a\\'", 400, "at the end"),
            ("LIKE a number", "SELECT * FROM q.t WHERE a LIKE 1", 400, None),
            ("LIKE NULL", "SELECT * FROM q.t WHERE a LIKE NULL", 400, None),
            ("LIKE a column", "SELECT * FROM q.t WHERE a LIKE b", 400, None),
            ("LIKE with ESCAPE", "SELECT * FROM q.t WHERE a LIKE 'x!%' ESCAPE '!'", 400, None),
            ("UPDATE of a missing table", "UPDATE q.nosuch SET a = 1", 400, "does not exist"),
            ("UPDATE with a column twice", "UPDATE q.t SET a = 1, a = 2", 400, "set twice"),
            ("UPDATE without SET", "UPDATE q.t WHERE a = 1", 400, None),
            ("UPDATE SET a column to a column", "UPDATE q.t SET a = b", 400, None),
            ("DELETE without FROM", "DELETE q.t", 400, None),
            ("DELETE from a missing table", "DELETE FROM q.nosuch", 400, "does not exist"),
            ("SHOW CONFLICTS in a missing table", "SHOW CONFLICTS IN q.nosuch", 400, "does not exist"),
            ("SHOW CONFLICTS in a missing database", "SHOW CONFLICTS IN nosuchdb.t", 400, "does not exist"),
            ("SHOW CONFLICTS of a database", "SHOW CONFLICTS IN q", 400, None)]:
        if want == 400:
            refused(label, c, stmt, needle)
        else:
            j = accepted(label, c, stmt)
            if j is not None:
                expect(f"{label} affects {want}", want, j.get("affected"))

    c.ok("CREATE TABLE IF NOT EXISTS q.w")
    c.ok("DELETE FROM q.w")
    j = c.ok("INSERT INTO q.w (_id, a) VALUES ('x', 1), ('y', NULL), ('z', 'z')")
    expect("INSERT returns the ids in order", ["x", "y", "z"], j["ids"])
    expect("an explicit NULL reads as null", [["y", None]], c.rows("SELECT _id, a FROM q.w WHERE a IS NULL"))
    expect("a missing column reads as null", [[None, None]], c.rows("SELECT nope, other FROM q.w WHERE _id = 'x'"))
    expect("UPDATE without WHERE changes every row", 3, c.ok("UPDATE q.w SET b = TRUE")["affected"])
    expect("UPDATE that matches nothing affects 0", 0, c.ok("UPDATE q.w SET b = 1 WHERE a = 'nothing'")["affected"])
    expect("SET c = NULL stores a null", 1, c.ok("UPDATE q.w SET a = NULL WHERE _id = 'x'")["affected"])
    expect("which IS NULL finds", 2, c.count("q.w", "a IS NULL"))
    expect("SELECT * lists _id first, then columns in the order first seen", ["_id", "a", "b"],
           c.ok("SELECT * FROM q.w")["columns"])
    expect("SELECT of the same column twice", [["z", "z", "z"]], c.rows("SELECT _id, a, a FROM q.w WHERE _id = 'z'"))
    expect("SELECT * of an empty result has only _id", ["_id"], c.ok("SELECT * FROM q.w WHERE a = 'none'")["columns"])
    expect("DELETE without WHERE removes every row", 3, c.ok("DELETE FROM q.w")["affected"])
    expect("DELETE of nothing affects 0", 0, c.ok("DELETE FROM q.w")["affected"])
    expect("COUNT(*) of an empty table", [[0]], c.rows("SELECT COUNT(*) FROM q.w"))
    c.ok("CREATE DATABASE gone")
    c.ok("CREATE TABLE gone.t")
    c.ok("INSERT INTO gone.t (a) VALUES (1)")
    c.ok("CREATE INDEX ON gone.t (a)")
    expect("DROP DATABASE with a table and an index affects 1", 1, c.ok("DROP DATABASE gone")["affected"])
    refused("its table is gone", c, "SELECT * FROM gone.t", "does not exist")
    c.ok("CREATE DATABASE gone")
    c.ok("CREATE TABLE gone.t")
    expect("a re-created table is empty", 0, c.count("gone.t"))
    expect("and has no index", [], c.rows("SHOW INDEXES IN gone.t"))
    c.ok("DROP DATABASE gone")


def limits(c):
    section("LIMIT and the 10,000-row cap")
    c.ok("CREATE TABLE IF NOT EXISTS q.big")
    if c.count("q.big") == 0:
        c.ok("INSERT INTO q.big (_id, n) VALUES " + ", ".join(f"('r{i:05d}', {i})" for i in range(10001)))
    expect("10,001 rows are there", 10001, c.count("q.big"))
    j = c.ok("SELECT _id FROM q.big")
    expect("without LIMIT: 10,000 rows", 10000, j["count"])
    expect("and truncated", True, j["truncated"])
    expect("the first 10,000 in _id order", "r09999", j["rows"][-1][0])
    j = c.ok("SELECT _id FROM q.big LIMIT 0")
    expect("LIMIT 0: no rows, not truncated", (0, False), (j["count"], j["truncated"]))
    j = c.ok("SELECT _id FROM q.big LIMIT 10000")
    expect("LIMIT 10000: 10,000 rows, not truncated", (10000, False), (j["count"], j["truncated"]))
    j = c.ok("SELECT _id FROM q.big LIMIT 1")
    expect("LIMIT 1", [["r00000"]], j["rows"])
    refused("LIMIT 10001 is over the cap", c, "SELECT _id FROM q.big LIMIT 10001", "over the maximum")
    refused("LIMIT 10001 with nothing to return is still over the cap", c,
            "SELECT _id FROM q.big WHERE n = -5 LIMIT 10001", "over the maximum")
    refused("LIMIT of the largest long", c, "SELECT _id FROM q.big LIMIT 9223372036854775807", "over the maximum")
    for label, text in [("-1", "-1"), ("1.5", "1.5"), ("1e2", "1e2"), ("'5'", "'5'"), ("NULL", "NULL"),
                        ("past a long", "9223372036854775808"), ("nothing", ""), ("ALL", "ALL")]:
        refused(f"LIMIT {label}", c, f"SELECT _id FROM q.big LIMIT {text}")
    expect("COUNT(*) is not capped", 10001, c.count("q.big"))
    j = c.ok("SELECT _id FROM q.big WHERE n < 10000")
    expect("exactly 10,000 matches without LIMIT: not truncated", (10000, False), (j["count"], j["truncated"]))
    j = c.ok("SELECT n FROM q.big ORDER BY n DESC LIMIT 3")
    expect("ORDER BY DESC LIMIT 3 sorts all rows first", [[10000], [9999], [9998]], j["rows"])
    j = c.ok("SELECT n FROM q.big ORDER BY n DESC")
    expect("ORDER BY without LIMIT: the top 10,000, truncated", (10000, True, [10000], [1]),
           (j["count"], j["truncated"], j["rows"][0], j["rows"][-1]))
    j = c.ok("SELECT n FROM q.big WHERE _id >= 'r05000' ORDER BY n LIMIT 2")
    expect("an _id range with ORDER BY and LIMIT", [[5000], [5001]], j["rows"])


def ordering(c):
    section("ORDER BY over mixed types")
    c.ok("CREATE TABLE IF NOT EXISTS q.mix")
    c.ok("DELETE FROM q.mix")
    values = [None, MISSING, False, True, -1, 0, -0.0, 0.5, 1, 1.0, 2, 9007199254740993, 9007199254740992.0, "",
              "1", "A", "a", "b", chr(0xE9), chr(0x1F600), chr(0xFF21), True, None, "a", 1]
    rows = []
    for i, v in enumerate(values):
        rid = f"m{i:02d}"
        if v is MISSING:
            c.ok(f"INSERT INTO q.mix (_id, other) VALUES ('{rid}', 1)")
            rows.append((rid, None))
        else:
            c.ok(f"INSERT INTO q.mix (_id, v) VALUES ('{rid}', {lit(v)})")
            rows.append((rid, v))

    def key(desc):
        def cmp(a, b):
            c_ = sort_key_cmp(a[1], b[1])
            if desc:
                c_ = -c_
            return c_ if c_ else (a[0] > b[0]) - (a[0] < b[0])
        return functools.cmp_to_key(cmp)

    for desc in (False, True):
        want = [r[0] for r in sorted(rows, key=key(desc))]
        got = c.ids(f"SELECT _id FROM q.mix ORDER BY v {'DESC' if desc else 'ASC'}")
        expect(f"ORDER BY v {'DESC' if desc else 'ASC'}: nulls, false, true, numbers, strings; ties by _id ascending",
               want, got)
    expect("ASC is the default", c.ids("SELECT _id FROM q.mix ORDER BY v ASC"), c.ids("SELECT _id FROM q.mix ORDER BY v"))
    expect("ORDER BY _id DESC", sorted([r[0] for r in rows], reverse=True), c.ids("SELECT _id FROM q.mix ORDER BY _id DESC"))
    expect("ORDER BY a column no row has is _id order", sorted(r[0] for r in rows),
           c.ids("SELECT _id FROM q.mix ORDER BY nope DESC"))
    c.ok("CREATE INDEX IF NOT EXISTS ON q.mix (v)")
    want = [r[0] for r in sorted(rows, key=key(False))]
    expect("the same order with an index on v", want, c.ids("SELECT _id FROM q.mix ORDER BY v"))
    want = [r[0] for r in sorted((r for r in rows if r[1] is not None and compare(r[1], ">", 0)), key=key(True))]
    expect("ORDER BY DESC LIMIT through an index range", want[:4],
           c.ids("SELECT _id FROM q.mix WHERE v > 0 ORDER BY v DESC LIMIT 4"))


def nesting(c):
    section("nesting: 64 levels")
    c.ok("CREATE TABLE IF NOT EXISTS q.t")
    t = "SELECT COUNT(*) FROM q.t WHERE "
    accepted("64 parentheses", c, t + "(" * 64 + "a = 1" + ")" * 64)
    refused("65 parentheses", c, t + "(" * 65 + "a = 1" + ")" * 65, "nested more than 64")
    accepted("64 NOTs", c, t + "NOT " * 64 + "a = 1")
    refused("65 NOTs", c, t + "NOT " * 65 + "a = 1", "nested more than 64")
    accepted("32 NOTs inside 32 parentheses", c, t + "(" * 32 + "NOT " * 32 + "a = 1" + ")" * 32)
    refused("33 NOTs inside 32 parentheses", c, t + "(" * 32 + "NOT " * 33 + "a = 1" + ")" * 32, "nested more than 64")
    accepted("64 levels, then back out and 64 again", c, t + "(" * 64 + "a = 1" + ")" * 64 + " AND " + "(" * 64 + "a = 1" + ")" * 64)
    refused("100,000 parentheses", c, t + "(" * 100000 + "a = 1" + ")" * 100000, "nested more than 64")
    refused("100,000 NOTs", c, t + "NOT " * 100000 + "a = 1", "nested more than 64")
    accepted("5,000 ANDs do not count as nesting", c, t + " AND ".join(["a IS NULL"] * 5000))
    accepted("50,000 ORs do not count either", c, t + " OR ".join(["a = 1"] * 50000))
    accepted("ANDs and ORs inside 63 levels", c, t + "(" * 63 + " OR ".join(["a = 1 AND b = 2"] * 500) + ")" * 63)
    c.ok("DELETE FROM q.t")
    c.ok("INSERT INTO q.t (_id, a) VALUES ('one', 1), ('two', 2), ('none', NULL)")
    expect("NOT binds tighter than AND", 1, c.count("q.t", "NOT a = 1 AND a = 2"))
    expect("AND binds tighter than OR", 2, c.count("q.t", "a = 1 OR a = 2 AND a = 2"))
    expect("64 NOTs (even) keep the truth value", 1, c.count("q.t", "NOT " * 64 + "a = 1"))
    expect("63 NOTs invert it, and unknown stays unknown", 1, c.count("q.t", "NOT " * 63 + "a = 1"))


def comparisons(c):
    section("every comparison across types and NULL, without and with an index")
    c.ok("CREATE TABLE IF NOT EXISTS q.cmp")
    c.ok("DROP INDEX IF EXISTS ON q.cmp (v)")
    c.ok("DELETE FROM q.cmp")
    stored = [MISSING, None, True, False, -1, 0, -0.0, 1, 1.0, 1.5, -2.5e-7, 9007199254740993, 9007199254740992.0,
              9223372036854775807, 1e300, "a", "A", "", "1", "b", chr(0xE9), chr(0x1F600), chr(0xFFFF)]
    model = {}
    for i, v in enumerate(stored):
        rid = f"c{i:02d}"
        if v is MISSING:
            c.ok(f"INSERT INTO q.cmp (_id) VALUES ('{rid}')")
            model[rid] = None
        else:
            c.ok(f"INSERT INTO q.cmp (_id, v) VALUES ('{rid}', {lit(v)})")
            model[rid] = v
    literals = [None, True, False, -1, 0, -0.0, 1, 1.0, 1.5, 9007199254740992, 9007199254740993, 9007199254740993.0,
                9223372036854775807, 9.3e18, "a", "1", "", "B", chr(0x1F600), chr(0xFFFE)]
    for indexed in (False, True):
        if indexed:
            c.ok("CREATE INDEX ON q.cmp (v)")
        tag = "with an index" if indexed else "by scan"
        for op in OPS:
            for form in ("plain", "NOT", "AND _id", "OR"):
                mism = []
                for L in literals:
                    cond = f"v {op} {lit(L)}"
                    if form == "NOT":
                        where = f"NOT {cond}"
                    elif form == "AND _id":
                        where = f"{cond} AND _id >= 'c'"
                    elif form == "OR":
                        where = f"{cond} OR {cond}"
                    else:
                        where = cond
                    want = set()
                    for rid, v in model.items():
                        r = compare(v, op, L)
                        if form == "NOT":
                            r = None if r is None else not r
                        if r is True:
                            want.add(rid)
                    got = set(c.ids(f"SELECT _id FROM q.cmp WHERE {where}"))
                    n = c.count("q.cmp", where)
                    if got != want or n != len(want):
                        mism.append((where, sorted(want - got), sorted(got - want), n))
                check(f"v {op} literal, {form}, {tag}: {len(literals)} literals match the rules", not mism,
                      repr(mism[:3]))
        for neg in ("", "NOT "):
            want = {rid for rid, v in model.items() if (v is None) != (neg == "NOT ")}
            expect(f"v IS {neg}NULL, {tag}", want, set(c.ids(f"SELECT _id FROM q.cmp WHERE v IS {neg}NULL")))
            want2 = {rid for rid, v in model.items() if (v is None) == (neg == "NOT ")}
            expect(f"NOT v IS {neg}NULL, {tag}", want2, set(c.ids(f"SELECT _id FROM q.cmp WHERE NOT v IS {neg}NULL")))
    # Three-valued logic: a row with no v makes "v = 1" unknown, and unknown is never a match.
    for where, want in [("v = NULL", set()), ("v != NULL", set()), ("NOT v = NULL", set()),
                        ("v = 1 OR v IS NULL", {r for r, v in model.items() if v is None or compare(v, "=", 1)}),
                        ("NOT (v = 1 AND v IS NULL)", {r for r, v in model.items() if v is not None}),
                        ("NOT (v IS NULL AND v = 1)", {r for r, v in model.items() if v is not None}),
                        ("v IS NULL OR NOT v = 1", {r for r, v in model.items() if v is None or not compare(v, "=", 1)}),
                        ("NOT (v = 1 OR v = 2)", {r for r, v in model.items() if v is not None
                                                  and not compare(v, "=", 1) and not compare(v, "=", 2)})]:
        expect(f"three-valued: {where}", want, set(c.ids(f"SELECT _id FROM q.cmp WHERE {where}")))


def like_match(s, p):
    """LIKE as docs/QUERY.md has it, on UTF-16 code units: % any run, _ one unit, \\%, \\_ and \\\\ themselves."""
    s, units = utf16(s), []
    i = 0
    while i < len(p):
        ch = p[i]
        if ch == "\\":
            units.append(("lit", utf16(p[i + 1])))
            i += 2
            continue
        units.append(("any",) if ch == "%" else ("one",) if ch == "_" else ("lit", utf16(ch)))
        i += 1
    flat = []
    for u in units:  # a literal outside the BMP is two code units, each matched on its own
        if u[0] == "lit":
            flat += [("lit", u[1][k:k + 2]) for k in range(0, len(u[1]), 2)]
        else:
            flat.append(u)
    n = len(s) // 2

    @functools.lru_cache(maxsize=None)
    def m(i, j):
        if j == len(flat):
            return i == n
        u = flat[j]
        if u[0] == "any":
            return m(i, j + 1) or (i < n and m(i + 1, j))
        if i >= n:
            return False
        return (u[0] == "one" or s[2 * i:2 * i + 2] == u[1]) and m(i + 1, j + 1)

    return m(0, 0)


def three(op_result, negated):
    return None if op_result is None else (not op_result if negated else op_result)


def in_and_like(c, rng):
    section("IN and LIKE across types and NULL, without and with an index")
    c.ok("CREATE TABLE IF NOT EXISTS q.il")
    c.ok("DROP INDEX IF EXISTS ON q.il (v)")
    c.ok("DELETE FROM q.il")
    stored = [MISSING, None, True, False, -1, 0, -0.0, 1, 1.0, 1.5, 9007199254740993, 9007199254740992.0, "", "a",
              "A", "ab", "abc", "b", "a%", "a_b", "a\\b", "%", "_", "it's", "1", chr(0xE9), chr(0x1F600),
              chr(0x1F600) + "a", "a" + chr(0x1F600), chr(0xFFFF), "a" + chr(0xFFFF), chr(0xFFFF) + "a"]
    model = {}
    for i, v in enumerate(stored):
        rid = f"l{i:02d}"
        if v is MISSING:
            c.ok(f"INSERT INTO q.il (_id) VALUES ('{rid}')")
            model[rid] = None
        else:
            c.ok(f"INSERT INTO q.il (_id, v) VALUES ('{rid}', {lit(v)})")
            model[rid] = v
    literals = [None, True, False, -1, 0, -0.0, 1, 1.0, 1.5, 9007199254740993, 9007199254740993.0, "a", "A", "",
                "1", "a%", chr(0x1F600), chr(0xFFFF), "zz"]
    pieces = ["a", "b", "A", "%", "_", "\\%", "\\_", "\\\\", "''", chr(0x1F600), chr(0xFFFF), "1", "c"]
    patterns = ["", "%", "_", "a", "a%", "%a", "%a%", "_a", "a_", "__", "a\\%", "a\\_b", "a\\\\b", "\\%", "\\_",
                "%\\%%", chr(0x1F600) + "%", "__a", "_" + chr(0x1F600), chr(0xFFFF) + "%", "a" + chr(0xFFFF) + "%",
                "it''s", "A%", "%b%"]
    patterns += ["".join(rng.choice(pieces) for _ in range(rng.randint(1, 5))) for _ in range(40)]
    lists = [[rng.choice(literals) for _ in range(rng.randint(1, 5))] for _ in range(40)] + [[1], [None], ["a", None]]

    def want_in(v, values, negated):
        if v is None:
            return None
        hit, unknown = False, False
        for x in values:
            r = compare(v, "=", x)
            hit |= r is True
            unknown |= r is None
        return three(True if hit else None if unknown else False, negated)

    def want_like(v, p, negated):
        if v is None:
            return None
        return three(isinstance(v, str) and like_match(v, p.replace("''", "'")), negated)

    for indexed in (False, True):
        if indexed:
            c.ok("CREATE INDEX ON q.il (v)")
        tag = "with an index" if indexed else "by scan"
        for column in ("v", "_id"):
            mism = []
            conds = []
            for vals in lists:
                for neg in (False, True):
                    conds.append((f"{column} {'NOT ' if neg else ''}IN ({', '.join(lit(x) for x in vals)})",
                                  lambda v, vals=vals, neg=neg: want_in(v, vals, neg)))
            for p in patterns:
                for neg in (False, True):
                    conds.append((f"{column} {'NOT ' if neg else ''}LIKE '{p}'",
                                  lambda v, p=p, neg=neg: want_like(v, p, neg)))
            for where, rule in conds:
                want = sorted(r for r, v in model.items() if rule(r if column == "_id" else v) is True)
                got = c.ids(f"SELECT _id FROM q.il WHERE {where}")
                n = c.count("q.il", where)
                both = c.ids(f"SELECT _id FROM q.il WHERE ({where}) OR ({where})")
                anded = c.ids(f"SELECT _id FROM q.il WHERE {where} AND _id >= 'l10'")
                if got != want or n != len(want) or both != want or anded != [r for r in want if r >= "l10"]:
                    mism.append((where, want, got, n))
            check(f"{column} [NOT] IN and [NOT] LIKE, {tag}: {len(conds)} conditions match the rules", not mism,
                  repr(mism[:2]))
    expect("IN with a null never makes NOT IN true", [], c.ids("SELECT _id FROM q.il WHERE v NOT IN (1, NULL)"))
    expect("LIKE '%' is every string", sorted(r for r, v in model.items() if isinstance(v, str)),
           c.ids("SELECT _id FROM q.il WHERE v LIKE '%'"))
    expect("NOT LIKE '%' is every value that is not a string or null",
           sorted(r for r, v in model.items() if v is not None and not isinstance(v, str)),
           c.ids("SELECT _id FROM q.il WHERE v NOT LIKE '%'"))
    expect("an emoji is two UTF-16 code units: '__' matches it, '_' does not", (True, False),
           ("l26" in c.ids("SELECT _id FROM q.il WHERE v LIKE '__'"),
            "l26" in c.ids("SELECT _id FROM q.il WHERE v LIKE '_'")))
    values = ", ".join(str(i) for i in range(1000))
    accepted("an IN list of 1,000 values", c, f"SELECT COUNT(*) FROM q.il WHERE v IN ({values})")


def agg_model(fn, values):
    """One aggregate over a group's values in _id order, as docs/QUERY.md says: (value, None) or (None, error)."""
    if fn == "count(*)":
        return len(values), None
    if fn == "count":
        return sum(1 for v in values if v is not None), None
    if fn in ("min", "max"):
        best = None
        for v in values:
            if v is not None and (best is None or (sort_key_cmp(v, best) < 0 if fn == "min" else sort_key_cmp(v, best) > 0)):
                best = v
        return best, None
    ints = [v for v in values if isinstance(v, int) and not isinstance(v, bool)]
    dbls = [v for v in values if isinstance(v, float)]
    if not ints and not dbls:
        return None, None
    if fn == "sum" and not dbls:
        s = sum(ints)
        return (s, None) if -2 ** 63 <= s < 2 ** 63 else (None, "SUM overflows a 64-bit integer")
    total = -0.0
    for d in dbls:
        total += d
    if ints:
        total += float(sum(ints))
    if fn == "avg":
        total /= len(ints) + len(dbls)
    if total in (float("inf"), float("-inf")):
        return None, f"{fn.upper()} overflows a double"
    return total, None


def group_key(v):
    """Values are one group when = holds between them; Python's True == 1 must not merge booleans and numbers."""
    if v is None:
        return (0,)
    if isinstance(v, bool):
        return (1, v)
    if isinstance(v, (int, float)):
        return (2, Fraction(v))
    return (3, utf16(v))


def aggregates(c, rng):
    section("aggregates and GROUP BY against a model")
    c.ok("CREATE TABLE IF NOT EXISTS q.agg")
    c.ok("DROP INDEX IF EXISTS ON q.agg (g)")
    c.ok("DELETE FROM q.agg")
    gs = [MISSING, None, True, False, 0, -0.0, 1, 1.0, 2.5, "", "a", "A", "b", chr(0x1F600)]
    ns = [MISSING, None, 1, 2, -3, 0, 0.5, -0.0, 1e300, 0.1, 9007199254740993, "7", True, False, 2 ** 62]
    rows = {}
    tuples = []
    for i in range(300):
        rid = f"a{(i * 7) % 300:03d}"
        g, h, n = rng.choice(gs), rng.choice(gs[:6] + ["x", "y"]), rng.choice(ns)
        rows[rid] = {"g": g, "h": h, "n": n}
        tuples.append((rid, g, h, n))
    for rid, g, h, n in tuples:
        cols = ["_id"] + [k for k, v in (("g", g), ("h", h), ("n", n)) if v is not MISSING]
        vals = [lit(rid)] + [lit(v) for v in (g, h, n) if v is not MISSING]
        c.ok(f"INSERT INTO q.agg ({', '.join(cols)}) VALUES ({', '.join(vals)})")
    ordered = sorted(rows)

    def val(r, col):
        v = rows[r][col] if col != "_id" else r
        return None if v is MISSING else v

    fns = ["count(*)", "count", "sum", "min", "max", "avg"]
    specs = [([], ["count(*)", "count", "sum", "min", "max", "avg"], None), (["g"], fns, None), (["h", "g"], fns, None),
             (["g"], ["count(*)"], ("count(*)", True)), (["g"], ["sum", "count(*)"], ("sum", False)),
             (["h"], ["max", "min"], ("h", True)), (["n"], ["count(*)"], None), (["g", "h"], [], None)]
    for indexed in (False, True):
        if indexed:
            c.ok("CREATE INDEX ON q.agg (g)")
        tag = "with an index on g" if indexed else "by scan"
        for where, keep in [(None, lambda r: True), ("g IN (1, 'a', NULL, TRUE)", lambda r: compare(val(r, "g"), "=", 1)
                                                      or compare(val(r, "g"), "=", "a") or val(r, "g") is True),
                            ("g LIKE 'a%'", lambda r: isinstance(val(r, "g"), str) and val(r, "g").startswith("a"))]:
            mism = []
            for by, aggs, order in specs:
                items = [b for b in by] + [("COUNT(*)" if f == "count(*)" else f"{f.upper()}(n)") for f in aggs]
                labels = by + ["count" if f == "count(*)" else f"{f}(n)" for f in aggs]
                q = f"SELECT {', '.join(items)} FROM q.agg" + (f" WHERE {where}" if where else "")
                if by:
                    q += " GROUP BY " + ", ".join(by)
                if order:
                    key = order[0]
                    q += " ORDER BY " + (key if key in by else "COUNT(*)" if key == "count(*)" else f"{key.upper()}(n)")
                    q += " DESC" if order[1] else ""
                groups = {}
                for r in ordered:
                    if not keep(r):
                        continue
                    k = tuple(group_key(val(r, b)) for b in by)
                    if k not in groups:
                        groups[k] = ([val(r, b) for b in by], [])
                    groups[k][1].append(r)
                if not by and not groups:
                    groups[()] = ([], [])
                keys = sorted(groups, key=functools.cmp_to_key(
                    lambda a, b: next((x for x in (sort_key_cmp(p, q_) for p, q_ in zip(groups[a][0], groups[b][0])) if x), 0)))
                out, error = [], None
                for k in keys:
                    gv, members = groups[k]
                    res = {}
                    for f in aggs:
                        v, e = agg_model(f, [True if f == "count(*)" else val(r, "n") for r in members])
                        if e and not error:
                            error = e
                        res[f] = v
                    out.append((gv, res))
                if order:
                    key = order[0]
                    out.sort(key=functools.cmp_to_key(lambda a, b: sort_key_cmp(
                        a[0][by.index(key)] if key in by else a[1][key], b[0][by.index(key)] if key in by else b[1][key])),
                        reverse=order[1])
                want = [gv + [res[f] for f in aggs] for gv, res in out]
                s, j, data = c.q(q)
                if error:
                    if s != 400 or error not in (j or {}).get("error", ""):
                        mism.append((q, error, s, short(data)))
                elif s != 200 or j["columns"] != labels or j["rows"] != want:
                    mism.append((q, want[:3], s, short(data)))
            check(f"aggregates and GROUP BY{' WHERE ' + where if where else ''}, {tag}: {len(specs)} statements "
                  "match the model", not mism, repr(mism[:2]))
    c.ok("INSERT INTO q.agg (_id, g, n) VALUES ('zz1', 'over', 9223372036854775807), ('zz2', 'over', 1)")
    refused("a SUM of integers that leaves 64 bits", c, "SELECT SUM(n) FROM q.agg WHERE g = 'over'",
            "SUM overflows a 64-bit integer")
    expect("its AVG is a double", [[4.611686018427388e18]], c.rows("SELECT AVG(n) FROM q.agg WHERE g = 'over'"))
    c.ok("INSERT INTO q.agg (_id, g, n) VALUES ('zz3', 'over', -1)")
    expect("and back in range, it is exact", [[9223372036854775807]], c.rows("SELECT SUM(n) FROM q.agg WHERE g = 'over'"))
    j = c.ok("SELECT n, COUNT(*) FROM q.big GROUP BY n")
    expect("10,001 groups: 10,000 returned, truncated", (10000, True, [0, 1]), (j["count"], j["truncated"], j["rows"][0]))
    j = c.ok("SELECT n, COUNT(*) FROM q.big GROUP BY n ORDER BY n DESC LIMIT 2")
    expect("ORDER BY a GROUP BY column DESC with LIMIT", ([[10000, 1], [9999, 1]], False), (j["rows"], j["truncated"]))
    c.ok("CREATE TABLE IF NOT EXISTS q.groups")
    if c.count("q.groups") < 100001:
        c.ok("DELETE FROM q.groups")
        for start in range(0, 100001, 50000):
            c.ok("INSERT INTO q.groups (_id) VALUES " + ", ".join(f"('{i:06d}')" for i in range(start, min(start + 50000, 100001))))
    refused("100,001 groups are refused", c, "SELECT _id, COUNT(*) FROM q.groups GROUP BY _id",
            "GROUP BY would make more than 100,000 groups")
    accepted("100,000 are not", c, "SELECT COUNT(*) FROM q.groups WHERE _id < '100000' GROUP BY _id LIMIT 1")
    expect("COUNT(*) of the whole table is not capped", 100001, c.count("q.groups"))
    pages, seen, last = 0, [], None
    while True:
        where = "" if last is None else f" WHERE _id > '{last}'"
        page = c.ids(f"SELECT _id FROM q.big{where} ORDER BY _id LIMIT 1000")
        seen += page
        pages += 1
        if len(page) < 1000:
            break
        last = page[-1]
    expect("keyset pages of 1,000 read every row once, in order", (11, sorted(c.ids("SELECT _id FROM q.big") + ["r10000"])),
           (pages, seen))


def index_model(c, rng):
    section("an index under inserts, updates and deletes of mixed types")
    c.ok("CREATE TABLE IF NOT EXISTS q.ix")
    c.ok("DELETE FROM q.ix")
    pool = [None, True, False, -3, -1, 0, 0.0, -0.0, 1, 1.0, 2, 2.5, 10, 1e15, 1e15 + 1, "", "0", "1", "a", "b", "A",
            chr(0xE9), "\U0001F600"]
    model = {}

    def put(rid, v):
        model[rid] = v

    vals = []
    for i in range(400):
        rid = f"x{i:03d}"
        v = rng.choice(pool + [MISSING])
        vals.append(f"('{rid}', {lit(None if v is MISSING else v)})")
        put(rid, None if v is MISSING else v)
    c.ok("INSERT INTO q.ix (_id, v) VALUES " + ", ".join(vals))
    c.ok("CREATE INDEX IF NOT EXISTS ON q.ix (v)")

    def verify(stage):
        mism = []
        for op in ["=", "<", "<=", ">", ">="]:
            for L in pool:
                if L is None:
                    continue
                want = sorted(r for r, v in model.items() if compare(v, op, L) is True)
                via = c.ids(f"SELECT _id FROM q.ix WHERE v {op} {lit(L)} LIMIT 10000")
                scan = c.ids(f"SELECT _id FROM q.ix WHERE v {op} {lit(L)} OR v {op} {lit(L)} LIMIT 10000")
                if via != want or scan != want:
                    mism.append((op, L, len(want), len(via), len(scan)))
        check(f"index lookups equal scans and the model ({stage})", not mism, repr(mism[:4]))

    verify("after the build")
    for step in range(6):
        for _ in range(30):
            kind = rng.random()
            v = rng.choice(pool)
            if kind < 0.35:
                rid = rng.choice(sorted(model))
                c.ok(f"UPDATE q.ix SET v = {lit(v)} WHERE _id = '{rid}'")
                put(rid, v)
            elif kind < 0.55:
                old, op = rng.choice(pool[1:]), rng.choice(["=", "<", ">=", "!="])
                c.ok(f"UPDATE q.ix SET v = {lit(v)} WHERE v {op} {lit(old)}")
                for rid, cur in model.items():
                    if compare(cur, op, old) is True:
                        model[rid] = v
            elif kind < 0.7:
                old, op = rng.choice(pool[1:]), rng.choice(["=", "<=", ">"])
                j = c.ok(f"DELETE FROM q.ix WHERE v {op} {lit(old)} AND _id < 'x2'")
                gone = [r for r, cur in model.items() if compare(cur, op, old) is True and r < "x2"]
                for r in gone:
                    del model[r]
                if j["affected"] != len(gone):
                    bad("DELETE through an index affects what the model says", f"{j['affected']} != {len(gone)}")
            elif kind < 0.85:
                rid = f"y{step}{rng.randrange(10**6):06d}"
                if rid not in model:
                    c.ok(f"INSERT INTO q.ix (_id, v) VALUES ('{rid}', {lit(v)})")
                    put(rid, v)
            else:
                rid = rng.choice(sorted(model))
                c.ok(f"UPDATE q.ix SET w = 1 WHERE _id = '{rid}'")
        verify(f"after round {step + 1} of writes")
    c.ok("DROP INDEX ON q.ix (v)")
    c.ok("CREATE INDEX ON q.ix (v)")
    verify("after DROP INDEX and CREATE INDEX")
    c.ok("CREATE INDEX IF NOT EXISTS ON q.ix (w)")
    expect("a second index on the same table", [["v"], ["w"]], c.rows("SHOW INDEXES IN q.ix"))
    expect("an indexed term OR another", sorted(r for r, v in model.items() if compare(v, ">=", 0) is True),
           sorted(c.ids("SELECT _id FROM q.ix WHERE v >= 0 AND w IS NULL OR v >= 0 AND w = 1")))
    c.ok("DROP TABLE q.ix")
    c.ok("CREATE TABLE q.ix")
    expect("DROP TABLE drops its indexes", [], c.rows("SHOW INDEXES IN q.ix"))


def conflicts(c):
    section("SHOW CONFLICTS on a single node")
    c.ok("CREATE TABLE IF NOT EXISTS q.empty")
    j = c.ok("SHOW CONFLICTS IN q.empty")
    expect("an empty table: no rows and the documented columns",
           (["_id", "current", "deleted", "node", "at", "row"], [], False), (j["columns"], j["rows"], j["truncated"]))
    j = c.ok("SHOW CONFLICTS IN q.big")
    expect("a table written on one node has no conflicts", [], j["rows"])


def big_insert(c):
    section("one INSERT of as many rows as fit in 1 MiB")
    c.ok("CREATE TABLE IF NOT EXISTS q.many")
    c.ok("DELETE FROM q.many")
    head = "INSERT INTO q.many (_id, n, s) VALUES "
    tuples = []
    size = len(json.dumps({"query": head}).encode())
    i = 0
    while True:
        t = f"('k{i:06d}', {i}, 'v')"
        extra = len(t) + (2 if tuples else 0)
        if size + extra > (1 << 20):
            break
        tuples.append(t)
        size += extra
        i += 1
    body = json.dumps({"query": head + ", ".join(tuples)}).encode()
    check("the body is within 64 bytes of 1 MiB", (1 << 20) - 64 <= len(body) <= (1 << 20), str(len(body)))
    s, j, data = c.post("/v1/query", body, raw=True)
    expect(f"{len(tuples)} rows in one INSERT is 200", 200, s)
    if s == 200:
        expect("affected counts them", len(tuples), j["affected"])
        expect("ids are in insert order", [f"k{n:06d}" for n in range(len(tuples))], j["ids"])
    expect("COUNT(*) agrees", len(tuples), c.count("q.many"))
    more = json.dumps({"query": head + ", ".join(tuples + ["('over', 1, 'v')"] * 4)}).encode()
    s, j, data = c.post("/v1/query", more, raw=True)
    expect("a few more rows push it over 1 MiB: 413", 413, s)
    expect("and nothing more was written", len(tuples), c.count("q.many"))
    dup = json.dumps({"query": head + ", ".join([f"('n{n:06d}', {n}, 'v')" for n in range(2000)] + ["('k000000', 0, 'v')"])}).encode()
    expect("a long INSERT whose last row collides writes nothing", (400, len(tuples)),
           (c.post("/v1/query", dup, raw=True)[0], c.count("q.many")))
