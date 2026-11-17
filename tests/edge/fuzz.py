"""Statements generated from the grammar in docs/QUERY.md, some valid and some with one token changed.

Every answer must be 200 or a 400 with a message (never 500, never no answer), a parse error's position must
lie inside the text, a valid statement must never get a parse error, and the node must keep answering.
Valid SELECTs are also run as "WHERE (e) OR (e)", which no index lookup answers, and with "HAVING (h) OR (h)",
and must give the same rows; a valid SELECT DISTINCT must give the rows of its GROUP BY form, and a SELECT with
ORDER BY those of "ORDER BY zz, ...", which sorts (no row and no index has zz) where the first may read an index
in order. Indexes on one and on several columns are created and dropped as the run goes, a share of the
conditions is an AND that fixes the first columns of one (a = x AND b > y ...), so those are compared with the
same scan, and a share of the SELECTs orders by the next columns of one.

    FUZZ_SEED=123 FUZZ_N=5000 python3 edge.py fuzz HOST       (the seed is printed so a failure can be replayed)
"""

import os
import random
import re
import time

from common import Client, check, expect, ok, once, section, short, utf16_len

KEYWORDS = ["AND", "ASC", "BY", "COUNT", "CREATE", "DATABASE", "DATABASES", "DELETE", "DESC", "DROP", "EXISTS", "FALSE",
            "FROM", "IF", "IN", "INSERT", "INTO", "IS", "LIMIT", "NOT", "NULL", "OR", "ORDER", "SELECT", "SET", "SHOW",
            "TABLE", "TABLES", "TRUE", "UPDATE", "VALUES", "WHERE"]
SYMBOLS = ["(", ")", ",", ".", ";", "*", "=", "!=", "<>", "<", "<=", ">", ">=", "+", "-"]
CMP = ["=", "!=", "<>", "<", "<=", ">", ">="]
COLS = ["a", "b", "v", "_id", "on", "index", "conflicts", "x" * 63, "distinct", "having"]
# Columns named like the words that are keywords only where the grammar puts them stay lowercase, so that the
# checks below can find the clause keywords DISTINCT and HAVING, which are always written in capitals here.
LOWER = ("distinct", "having")
# f.k holds few distinct values, so that conditions on two or three of its columns match rows.
TABLES = [("f", "t"), ("f", "u"), ("f", "on"), ("f", "k"), ("f2", "x"), ("f", "nosuch"), ("nodb", "t")]
# Values a statement may compare or store. Strings include quotes, comment markers and non-ASCII text.
VALUES = ["NULL", "TRUE", "FALSE", "0", "-0", "1", "-1", "2", "1.0", "-0.0", "0.5", "1e300", "-2.5E-7",
          "9223372036854775807", "-9223372036854775808", "9223372036854775808", "9007199254740993", "''", "'a'",
          "'A'", "'1'", "'b'", "'It''s'", "'--'", "'; DROP DATABASE f'", "'" + chr(0xE9) + "'",
          "'" + chr(0x1F600) + "'", "'" + "z" * 200 + "'", "'\\'", "'x y'", "'" + chr(0) + "'"]
# The numbers of VALUES, and the columns, for SET c = column + number (any column but _id).
NUMBERS = [v for v in VALUES if v[0] in "-0123456789"]
SOURCES = [c for c in COLS if c != "_id"]
# Single tokens (or near-tokens) that replace one token of a valid statement.
JUNK = KEYWORDS + SYMBOLS + VALUES + [
    "a", "f", "t", "_", "_id", "INDEX", "INDEXES", "ON", "CONFLICTS", "y" * 64, "9abc", "1.", ".5", "1e", "1e309",
    "-", "+", "!", "@", "#", "`", "\"a\"", "'unterminated", "''''", "--", "/*", "*/", "\\", "$1", "?", "%", "&", "|",
    "[", "]", "{", "}", ":", "::", "==", "=<", "<<", "!==", chr(0), chr(0xA0), chr(0x1F600), chr(0xFF0A),
    chr(0x2028), "\t", "\x0b", "N'x'", "X'00'", "0x10", "1_0", "NaN", "Infinity", "LIKE", "JOIN", "GROUP", "ALL",
    "(" * 70, "NOT " * 70, "SELECT * FROM f.t", "IN", "SUM", "MIN", "MAX", "AVG", "'%'", "'a\\b'", "'\\'", "BY",
    "HAVING", "DISTINCT", "OFFSET", "ILIKE", "ESCAPE", "CONFLICT", "DO", "NOTHING", "EXCLUDED", "RETURNING"]
# Words that are keywords only where the grammar puts them, written in either case.
UNRESERVED = ("ON", "CONFLICT", "DO", "NOTHING")
SEMANTIC = ("does not exist", "already exists", "already has an index", "has no index", "_id must be",
            "_id cannot be changed", "_id needs no index", "_id cannot be a column of an index", "over the maximum",
            "nested more than", "overflows a", "cannot set", "is listed twice: an INSERT ... ON CONFLICT",
            "would make more than", "would hold more than")
AGGREGATES = ["COUNT", "SUM", "MIN", "MAX", "AVG"]
# Pieces of LIKE patterns: wildcards, the three escapes, and characters the stored values hold.
LIKE_PIECES = ["%", "_", "\\%", "\\_", "\\\\", "a", "A", "b", "1", "z", "''", " ", chr(0xE9), chr(0x1F600)]


class Gen:
    def __init__(self, rng):
        self.r = rng

    def table(self):
        db, t = self.r.choice(TABLES[:5]) if self.r.random() < 0.9 else self.r.choice(TABLES)
        return [db, ".", t]

    def case(self, n):
        return n.upper() if self.r.random() < 0.1 and n not in LOWER else n

    def name(self):
        return self.case(self.r.choice(COLS))

    def value(self):
        return self.r.choice(VALUES)

    def expr(self, depth=0, left=None):
        """A condition; `depth` counts parentheses and NOTs, which may reach 64. `left` gives the tokens of
        each left side (a column, or in HAVING a GROUP BY column or an aggregate)."""
        r = self.r
        k = r.random()
        if depth < 64 and k < 0.12:
            return ["NOT"] + self.expr(depth + 1, left)
        if depth < 64 and k < 0.24:
            return ["("] + self.expr(depth + 1, left) + [")"]
        if k < 0.36:
            parts = [self.expr(depth, left) for _ in range(r.randint(2, 4))]
            out = parts[0]
            for p in parts[1:]:
                out = out + [r.choice(["AND", "OR"])] + p
            return out
        col = left() if left else [self.name()]
        if k < 0.46:
            return col + ["IS"] + (["NOT"] if r.random() < 0.5 else []) + ["NULL"]
        neg = ["NOT"] if r.random() < 0.3 else []
        if k < 0.56:
            vals = [self.value() for _ in range(r.randint(1, 5))]
            return col + neg + ["IN", "("] + sum(([v, ","] for v in vals), [])[:-1] + [")"]
        if k < 0.66:
            return col + neg + ["LIKE", self.pattern()]
        return col + [r.choice(CMP), self.value()]

    def having(self, by, aggs):
        """A HAVING condition on the GROUP BY columns `by` and aggregates: those listed, and one that is not."""
        lefts = [[self.case(b)] for b in by] + aggs + [self.item(self.name())]
        return self.expr(left=lambda: self.r.choice(lefts))

    def order(self, keys):
        """ORDER BY's keys: 1 to 3 of `keys` (each a token list), none twice, each maybe with ASC or DESC."""
        uniq = {}
        for k in keys:
            uniq.setdefault(" ".join(k).lower(), k)  # COUNT(*) and count(*), or a and A, are one key
        chosen = self.r.sample(list(uniq.values()), self.r.randint(1, min(3, len(uniq))))
        out = []
        for i, k in enumerate(chosen):
            out += ([","] if i else []) + k + ([self.r.choice(["ASC", "DESC"])] if self.r.random() < 0.6 else [])
        return out

    def pattern(self):
        """A LIKE pattern that follows the rules, as a string literal."""
        return "'" + "".join(self.r.choice(LIKE_PIECES) for _ in range(self.r.randint(0, 5))) + "'"

    def item(self, column):
        """An aggregate: COUNT(*), COUNT(DISTINCT column), or one of COUNT, SUM, MIN, MAX, AVG of a column; any case."""
        fn = self.r.choice(AGGREGATES)
        fn = fn.lower() if self.r.random() < 0.2 else fn
        if fn.upper() == "COUNT":
            k = self.r.random()
            if k < 0.4:
                return [fn, "(", "*", ")"]
            if k < 0.6:
                return [fn, "(", "DISTINCT", column, ")"]
        return [fn, "(", column, ")"]

    def select_list(self):
        """Tokens of a SELECT's list and what follows FROM's table, following every parse rule: None for a
        plain list, [] for aggregates only, ("distinct", columns) or (GROUP BY tokens, columns, aggregates)."""
        r = self.r
        k = r.random()
        if k < 0.22:
            return ["*"], None
        if k < 0.32:
            return ["COUNT", "(", "*", ")"], None
        if k < 0.52:
            cols = [self.name() for _ in range(r.randint(1, 3))]
            return sum(([c, ","] for c in cols), [])[:-1], None
        if k < 0.62:
            cols = r.sample(COLS, r.randint(1, 3))
            return ["DISTINCT"] + sum(([self.case(c), ","] for c in cols), [])[:-1], ("distinct", cols)
        if k < 0.75:
            items = [self.item(self.name()) for _ in range(r.randint(1, 3))]
            return sum((i + [","] for i in items), [])[:-1], []
        by = r.sample(COLS, r.randint(1, 3))
        items = [[self.case(c)] for c in by if r.random() < 0.6] + [self.item(self.name()) for _ in range(r.randint(0, 3))]
        r.shuffle(items)
        if not items:
            items = [["COUNT", "(", "*", ")"]]
        group = ["GROUP", "BY"] + sum(([self.case(c), ","] for c in by), [])[:-1]
        return sum((i + [","] for i in items), [])[:-1], (group, by, [i for i in items if len(i) > 1])

    def deep(self):
        """A condition nested close to the limit."""
        n = self.r.randint(55, 64)
        kinds = [self.r.choice(["NOT", "("]) for _ in range(n)]
        toks = []
        for k in kinds:
            toks.append(k)
        toks += [self.name(), "=", self.value()]
        toks += [")" for k in kinds if k == "("]
        return toks

    def prefix_terms(self):
        """An AND that an index on several columns answers: = or IN on the first one or two of a, b, v (in the
        order of an index of setup(), or another), then maybe a range or LIKE on the next, the terms in any
        order and sometimes with one more of any kind."""
        r = self.r
        cols = r.choice([["a", "b"], ["v", "a"], ["a", "b", "v"], ["b", "a"]])
        k = r.randint(1, len(cols) - 1) if r.random() < 0.7 else len(cols)
        terms = []
        for c in cols[:k]:
            if r.random() < 0.75:
                terms.append([self.case(c), "=", self.value()])
            else:
                vals = [self.value() for _ in range(r.randint(1, 4))]
                terms.append([self.case(c), "IN", "("] + sum(([v, ","] for v in vals), [])[:-1] + [")"])
        if k < len(cols) and r.random() < 0.7:
            c = self.case(cols[k])
            for _ in range(r.randint(1, 2)):
                terms.append([c, "LIKE", self.pattern()] if r.random() < 0.2
                             else [c, r.choice(["<", "<=", ">", ">="]), self.value()])
        if r.random() < 0.3:
            terms.append(["("] + self.expr(1) + [")"])
        r.shuffle(terms)
        out = terms[0]
        for t in terms[1:]:
            out = out + ["AND"] + t
        return out

    def ordered(self):
        """A SELECT whose ORDER BY an index of setup() can give (on f.u, which has none, the same forms are
        sorted): = or IN on the first columns of one, then its next columns in one direction; maybe a key on a
        fixed column, a range or LIKE on the first ordered column, another term, _id as the last key, a LIMIT."""
        r = self.r
        t = r.choice(list(ORDERED))
        d = r.choice(ORDERED[t])
        k = r.randint(0, len(d) - 1)
        terms = []
        for c in d[:k]:
            if r.random() < 0.7:
                terms.append([self.case(c), "=", self.value()])
            else:
                vals = [self.value() for _ in range(r.randint(2, 4))]
                terms.append([self.case(c), "IN", "("] + sum(([v, ","] for v in vals), [])[:-1] + [")"])
        if r.random() < 0.3:
            for _ in range(r.randint(1, 2)):
                terms.append([self.case(d[k]), "LIKE", self.pattern()] if r.random() < 0.2
                             else [self.case(d[k]), r.choice(["<", "<=", ">", ">="]), self.value()])
        if r.random() < 0.3:
            terms.append(["("] + self.expr(1) + [")"])
        r.shuffle(terms)
        direction = r.choice([[], ["ASC"], ["DESC"], ["DESC"]])
        keys = [[self.case(c)] + direction for c in d[k:k + r.randint(1, len(d) - k)]]
        if k and r.random() < 0.3:
            keys.insert(r.randint(0, len(keys)), [self.case(d[r.randrange(k)]), r.choice(["ASC", "DESC"])])
        if r.random() < 0.2:
            keys.append(["_id"] + r.choice([[], ["ASC"], ["DESC"]]))
        toks = ["SELECT"] + r.choice([["*"], ["_id"], ["_id", ",", "a", ",", "v"]]) + ["FROM", "f", ".", t]
        if terms:
            toks += ["WHERE"] + terms[0] + sum((["AND"] + x for x in terms[1:]), [])
        toks += ["ORDER", "BY"] + keys[0] + sum(([","] + x for x in keys[1:]), [])
        if r.random() < 0.85:
            toks += ["LIMIT", str(r.choice([0, 1, 2, 5, 10, 100]))]
        return toks

    def where(self):
        if self.r.random() < 0.25:
            return []
        k = self.r.random()
        return ["WHERE"] + (self.deep() if k < 0.05 else self.prefix_terms() if k < 0.35 else self.expr())

    def sets(self):
        """The assignments of UPDATE's SET or ON CONFLICT DO UPDATE SET: 1 to 3 columns, each a literal or a column
        plus or minus a number (which a string or boolean in the row refuses)."""
        r = self.r
        cols = r.sample(["a", "b", "v", "on", "index"], r.randint(1, 3))
        return sum(([c, "=", self.value(), ","] if r.random() < 0.6 else
                    [c, "=", self.case(r.choice(SOURCES)), r.choice(["+", "-"]), r.choice(NUMBERS), ","]
                    for c in cols), [])[:-1]

    def index_columns(self):
        """1 to 4 distinct columns of CREATE or DROP INDEX, more often one or two; sometimes _id, which is refused."""
        n = self.r.choice([1, 1, 1, 2, 2, 3, 4])
        cols = self.r.sample(["a", "v", "b", "on", "_id"] if self.r.random() < 0.15 else ["a", "v", "b", "on"], n)
        return ["("] + sum(([self.case(c), ","] for c in cols), [])[:-1] + [")"]

    def statement(self):
        """Returns (tokens, kind). Every statement follows the grammar and the further parse rules."""
        r = self.r
        if r.random() < 0.12:
            return self.ordered(), "ordered"
        k = r.random()
        if k < 0.30:
            items, grouping = self.select_list()
            toks = ["SELECT"] + items + ["FROM"] + self.table() + self.where()
            kind = "select"
            if grouping == [] or (grouping is None and items[:1] == ["COUNT"]):
                # Aggregates without GROUP BY: one row (or none after HAVING), no ORDER BY or LIMIT.
                if r.random() < 0.3:
                    aggs = [["COUNT", "(", "*", ")"]] if grouping is None else self.items_of(items)
                    toks += ["HAVING"] + self.having([], aggs)
                return toks, kind
            keys = [[self.case(c)] for c in r.sample(COLS, 3)]
            if grouping is not None and grouping[0] == "distinct":
                kind = "distinct"
                keys = [[self.case(c)] for c in grouping[1]]
            elif grouping is not None:
                group, by, aggs = grouping
                toks += group
                if r.random() < 0.4:
                    toks += ["HAVING"] + self.having(by, aggs)
                keys = [[self.case(b)] for b in by] + aggs
            if r.random() < 0.4:
                toks += ["ORDER", "BY"] + self.order(keys)
            if r.random() < 0.4:
                toks += ["LIMIT", str(r.choice([0, 1, 2, 5, 100, 10000]))]
            return toks, kind
        if k < 0.45:
            cols = r.sample(["a", "b", "v", "on", "_id"], r.randint(1, 3))
            n = r.randint(1, 4)
            # ON CONFLICT needs _id; its ids come from a few, so that tuples meet rows that exist (and, rarely,
            # one id twice, which is refused).
            upsert = "_id" in cols and r.random() < 0.5
            pool = r.sample(range(40), n) if r.random() < 0.9 else [r.randrange(40) for _ in range(n)]
            toks = ["INSERT", "INTO"] + self.table() + ["("] + sum(([c, ","] for c in cols), [])[:-1] + [")", "VALUES"]
            tuples = []
            for i in range(n):
                vals = []
                for c in cols:
                    if c == "_id" and r.random() < 0.9:
                        vals.append(f"'u{pool[i]}'" if upsert else f"'r{r.randrange(10**9)}'")
                    else:
                        vals.append(self.value())
                tuples.append(["("] + sum(([v, ","] for v in vals), [])[:-1] + [")"])
            toks += sum((t + [","] for t in tuples), [])[:-1]
            if upsert:
                words = ["ON", "CONFLICT", "DO"] + (["NOTHING"] if r.random() < 0.4 else ["UPDATE", "SET"] + self.sets())
                toks += [w.lower() if w in UNRESERVED and r.random() < 0.2 else w for w in words]
                return toks, "upsert"
            return toks, "insert"
        if k < 0.60:
            return ["UPDATE"] + self.table() + ["SET"] + self.sets() + self.where(), "update"
        if k < 0.70:
            return ["DELETE", "FROM"] + self.table() + (self.where() or ["WHERE", "v", "=", self.value()]), "delete"
        if k < 0.80:
            op = r.choice(["CREATE", "DROP"])
            return [op, "INDEX"] + self._ifx(op) + ["ON"] + self.table() + self.index_columns(), "ddl"
        if k < 0.88:
            what = r.random()
            if what < 0.4:
                return ["SHOW", "DATABASES"], "show"
            if what < 0.6:
                return ["SHOW", "TABLES", "IN", r.choice(["f", "f2", "nodb"])], "show"
            return ["SHOW", r.choice(["INDEXES", "CONFLICTS"]), "IN"] + self.table(), "show"
        op = r.choice(["CREATE", "DROP"])
        if r.random() < 0.5:
            toks = [op, "DATABASE"] + (self._ifx(op)) + ["f2"]
        else:
            toks = [op, "TABLE"] + (self._ifx(op)) + ["f2", ".", r.choice(["x", "y"])]
        return toks, "ddl"

    @staticmethod
    def items_of(toks):
        """The items of a select list written as tokens, split at the commas outside parentheses."""
        out, cur, depth = [], [], 0
        for t in toks:
            if t == "," and depth == 0:
                out.append(cur)
                cur = []
                continue
            depth += (t == "(") - (t == ")")
            cur.append(t)
        return out + [cur]

    def _ifx(self, op):
        if self.r.random() < 0.5:
            return []
        return ["IF", "NOT", "EXISTS"] if op == "CREATE" else ["IF", "EXISTS"]

    def text(self, toks):
        """Joins tokens with blanks, sometimes odd ones: tabs, newlines, comments, none around symbols."""
        out = []
        for i, t in enumerate(toks):
            if i:
                k = self.r.random()
                if k < 0.80:
                    out.append(" ")
                elif k < 0.88:
                    out.append(self.r.choice(["\t", "\n", "\r\n", "  ", "\f"]))
                elif k < 0.93:
                    out.append(" -- comment " + self.r.choice(["", "'", "--", "SELECT"]) + "\n")
                elif (t in SYMBOLS or toks[i - 1] in SYMBOLS) and not (toks[i - 1] == "-" and t.startswith("-")):
                    # Not "-" and "-1" as "--1", which starts a comment.
                    out.append("")
                else:
                    out.append(" ")
            out.append(t.lower() if t in KEYWORDS and self.r.random() < 0.2 else t)
        if self.r.random() < 0.1:
            out.append(";")
        return "".join(out)

    def mutate(self, toks):
        r = self.r
        toks = list(toks)
        i = r.randrange(len(toks))
        k = r.random()
        if k < 0.6:
            toks[i] = r.choice(JUNK)
        elif k < 0.75:
            del toks[i]
        elif k < 0.9:
            toks.insert(i, toks[i])
        elif len(toks) > 1:
            j = min(i + 1, len(toks) - 1)
            toks[i], toks[j] = toks[j], toks[i]
        return toks


def answer_problem(query, s, j, data, valid):
    """None if the answer is one the docs allow for this query, else what is wrong with it."""
    if s == 200:
        if not isinstance(j, dict) or j.get("type") not in ("rows", "write"):
            return f"200 without a result: {short(data)}"
        return None
    if s != 400:
        return f"status {s}: {short(data.decode('utf-8', 'replace'))}"
    msg = (j or {}).get("error")
    if not isinstance(msg, str) or not msg:
        return f"400 without an error message: {short(data)}"
    m = re.search(r"at position (\d+)$", msg)
    if m and not 0 <= int(m.group(1)) <= utf16_len(query):  # positions count UTF-16 code units
        return f"position {m.group(1)} outside a {len(query)}-character query: {msg}"
    if valid and m:
        return f"a statement that follows the grammar got a parse error: {msg}"
    if valid and not any(s_ in msg for s_ in SEMANTIC):
        return f"unexpected refusal of a valid statement: {msg}"
    return None


CLAUSES = ("GROUP", "HAVING", "ORDER", "LIMIT")


def doubled(toks, clause):
    """`toks` with the condition of `clause` written as (c) OR (c), or None if there is none (or it is too deep)."""
    if clause not in toks:
        return None
    w = toks.index(clause)
    end = next((k for k in range(w + 1, len(toks)) if toks[k] in CLAUSES), len(toks))
    cond = toks[w + 1:end]
    if cond.count("(") + cond.count("NOT") >= 60:
        return None
    return toks[:w + 1] + ["("] + cond + [")", "OR", "("] + cond + [")"] + toks[end:]


def metamorphic(toks, kind):
    """Statements that must give exactly the rows and columns of the valid SELECT `toks`."""
    out = []
    # The same rows (or groups and aggregates) found without an index lookup: (e) OR (e) is answered by
    # none. And (h) OR (h) in HAVING keeps the same groups.
    for clause in ("WHERE", "HAVING"):
        again = doubled(toks, clause)
        if again:
            out.append(again)
    # The same rows sorted, never read in an index's order: a first ORDER BY key on a column no row has
    # decides nothing, and no index has it. With (e) OR (e) as well, no index at all.
    if kind in ("select", "ordered") and "ORDER" in toks and "GROUP" not in toks:
        o = toks.index("ORDER")
        sort = toks[:o + 2] + ["zz", ","] + toks[o + 2:]
        out.append(sort)
        again = doubled(sort, "WHERE")
        if again:
            out.append(again)
    # SELECT DISTINCT a, b is SELECT a, b ... GROUP BY a, b.
    if kind == "distinct":
        f = toks.index("FROM")
        end = next((k for k in range(f + 1, len(toks)) if toks[k] in ("ORDER", "LIMIT")), len(toks))
        out.append(toks[:1] + toks[2:end] + ["GROUP", "BY"] + toks[2:f] + toks[end:])
    return out


# The values of f.k: a few of VALUES, so that prefixes repeat.
FEW = ["NULL", "TRUE", "0", "1", "1.0", "2", "'a'", "'b'", "''"]
# Indexes on several columns, per table (f.u has none: it is scanned).
COMPOSITE = {"t": [("a", "b")], "on": [("a", "b", "v")], "k": [("a", "b"), ("b", "a", "v")]}
# The indexes of setup() that ORDER BY can read in order, per table; f.u has none, so its forms are sorted.
ORDERED = {"t": [("v",), ("a", "b")], "on": [("a",), ("a", "b", "v")], "k": [("v",), ("a", "b"), ("b", "a", "v")],
           "u": [("v",), ("a", "b")]}


def setup(c):
    c.ok("CREATE DATABASE IF NOT EXISTS f")
    for t in ("t", "u", "on", "k"):
        c.ok(f"CREATE TABLE IF NOT EXISTS f.{t}")
    c.ok("CREATE INDEX IF NOT EXISTS ON f.t (v)")
    c.ok("CREATE INDEX IF NOT EXISTS ON f.on (a)")
    c.ok("CREATE INDEX IF NOT EXISTS ON f.k (v)")
    for t, defs in COMPOSITE.items():
        for d in defs:
            c.ok(f"CREATE INDEX IF NOT EXISTS ON f.{t} ({', '.join(d)})")
    rows = []
    rng = random.Random(1)
    for i in range(300):
        rows.append(f"('s{i:03d}', {rng.choice(VALUES)}, {rng.choice(VALUES)}, {rng.choice(VALUES)})")
    # Each table on its own count: the run's deletes can empty one and not another (seed 106561184 did).
    for t, part in (("t", rows), ("u", rows[:100]), ("on", rows[100:200])):
        if c.count(f"f.{t}") == 0:
            c.ok(f"INSERT INTO f.{t} (_id, a, b, v) VALUES " + ", ".join(part))
    if c.count("f.k") == 0:
        few = [f"('k{i:03d}', {rng.choice(FEW)}, {rng.choice(FEW)}, {rng.choice(FEW)})" for i in range(300)]
        # Some rows lack a column, which reads as null and sorts first in an index.
        few += [f"('m{i:03d}', {rng.choice(FEW)})" for i in range(20)]
        c.ok("INSERT INTO f.k (_id, a, b, v) VALUES " + ", ".join(few[:300]))
        c.ok("INSERT INTO f.k (_id, a) VALUES " + ", ".join(few[300:]))


def composite_conditions(d):
    """Conditions an index on the columns `d` answers through a prefix: = and IN on the first, then a
    comparison or a LIKE on the second; and on a third, after = on the first two."""
    x, y = ["1", "1.0", "'a'", "TRUE", "NULL"], ["0", "1", "'a'", "2"]
    out = [f"{d[0]} = {a} AND {d[1]} {op} {b}" for a in x for op in ("=", "<", ">=") for b in y]
    out += [f"{d[0]} IN ({a}, 2, 'b') AND {d[1]} = 1" for a in x]
    out += [f"{d[1]} LIKE '%' AND {d[0]} = {a}" for a in x]
    if len(d) > 2:
        out += [f"{d[0]} = {a} AND {d[1]} = {b} AND {d[2]} > 0" for a in x for b in y]
    return out


def run(host, n=None, seed=None):
    n = int(os.environ.get("FUZZ_N") or "5000") if n is None else n
    seed = int(os.environ.get("FUZZ_SEED") or random.SystemRandom().randrange(10**9)) if seed is None else seed
    section(f"generated statements: {n}, seed {seed} (replay with FUZZ_SEED={seed})")
    rng = random.Random(seed)
    g = Gen(rng)
    c = Client(host)
    setup(c)
    counts = {"valid-200": 0, "valid-400": 0, "mutated-200": 0, "mutated-400": 0, "metamorphic": 0}
    problems = []
    meta_problems = []
    refusals = {}
    dropped = []
    t0 = time.time()
    for i in range(n):
        toks, kind = g.statement()
        valid = rng.random() < 0.5
        if not valid:
            toks = g.mutate(toks)
        query = g.text(toks)
        extra = {}
        if rng.random() < 0.05:
            extra = rng.choice([{"consistency": "majority"}, {"ack": "local"}, {"ack": "majority"}])
        s, j, data = c.q(query, **extra)
        p = answer_problem(query, s, j, data, valid)
        if p:
            problems.append((i, valid, query, p))
            if s == 0 and not c.q("SHOW DATABASES")[0]:
                break
        else:
            counts[("valid" if valid else "mutated") + ("-200" if s == 200 else "-400")] += 1
            if valid and s == 400:
                reason = re.sub(r"'[^']*'|\d+", "_", (j or {}).get("error", ""))[:60]
                refusals[reason] = refusals.get(reason, 0) + 1
        # A changed token can turn DROP DATABASE f2 into DROP DATABASE f; then every later statement on f
        # would be refused. Put back what is missing, as at the end of the run.
        if not valid and s == 200 and toks[:1] == ["DROP"] and (j or {}).get("affected"):
            dropped.append((i, query))
            setup(c)
        if valid and s == 200 and kind in ("select", "distinct", "ordered"):
            for again in metamorphic(toks, kind):
                s2, j2, d2 = c.q(" ".join(again))
                counts["metamorphic"] += 1
                if s2 != 200 or j2.get("rows") != j.get("rows") or j2.get("columns") != j.get("columns"):
                    meta_problems.append((i, query, f"{' '.join(again)!r:.200}: {s2} {short(d2)} vs {short(data)}"))
        if i % 250 == 249:
            st = once(host, "GET", "/health", auth=False).status
            if st != 200:
                problems.append((i, None, "GET /health", f"status {st}"))
                break
    took = time.time() - t0
    ok(f"sent {n} statements in {took:.0f} s: " + ", ".join(f"{k} {v}" for k, v in counts.items()))
    top = sorted(refusals.items(), key=lambda kv: -kv[1])[:4]
    print("  valid statements refused, most often: " + "; ".join(f"{v}x {k!r}" for k, v in top), flush=True)
    for i, query in dropped[:5]:
        print(f"  #{i} a changed statement dropped something, so the tables were set up again: {query!r:.120}",
              flush=True)
    for i, valid, query, p in problems[:8]:
        print(f"  #{i} {'valid' if valid else 'mutated'} {query!r:.300}: {p}", flush=True)
    check(f"every answer is 200 or a 400 with a message (seed {seed})", not problems,
          f"{len(problems)} bad answers, first: {problems[0][3] if problems else ''}")
    for i, query, p in meta_problems[:5]:
        print(f"  #{i} {query!r:.300}: {p}", flush=True)
    check(f"(e) OR (e) in WHERE and HAVING, DISTINCT as GROUP BY, and ORDER BY sorted, give the same rows, {counts['metamorphic']} "
          f"statements (seed {seed})", not meta_problems, f"{len(meta_problems)} differ")
    check("valid statements were mostly accepted", counts["valid-200"] > counts["valid-400"], repr(counts))
    expect("the node answers afterwards", 200, once(host, "GET", "/health", auth=False).status)
    # The writes left every table in a state where index lookups and scans agree, before and after the
    # indexes are built again from the rows. The run may have dropped the database or a table (seed
    # 715849882 did): make whatever is missing again first.
    setup(c)
    for stage in ("as the run left them", "after DROP and CREATE INDEX"):
        mism = []
        omism = []
        for t in ("t", "u", "on", "k"):
            if stage != "as the run left them":
                for col in ("a", "v"):
                    c.ok(f"DROP INDEX IF EXISTS ON f.{t} ({col})")
                    c.ok(f"CREATE INDEX ON f.{t} ({col})")
                for d in COMPOSITE.get(t, []) + [("v", "a")]:
                    c.ok(f"DROP INDEX IF EXISTS ON f.{t} ({', '.join(d)})")
                    c.ok(f"CREATE INDEX ON f.{t} ({', '.join(d)})")
            # Through indexes on several columns (those of setup(), any the run made, and (v, a) in the second
            # stage), and on f.u, which has none, by scan.
            for d in [("a", "b"), ("b", "a", "v"), ("v", "a")]:
                for cond in composite_conditions(d):
                    a = c.count(f"f.{t}", cond)
                    b = c.count(f"f.{t}", f"({cond}) OR ({cond})")
                    if a != b:
                        mism.append((t, cond, a, b))
            for col in ("a", "v"):
                for op in ["=", "<", ">="]:
                    for v in VALUES:
                        if v == "NULL":
                            continue
                        a = c.count(f"f.{t}", f"{col} {op} {v}")
                        b = c.count(f"f.{t}", f"{col} {op} {v} OR {col} {op} {v}")
                        if a != b:
                            mism.append((t, col, op, v, a, b))
                for cond in [f"{col} IN ({', '.join(VALUES[i:i + 4])})" for i in range(0, len(VALUES), 4)] + \
                        [f"{col} LIKE {p}" for p in ("'a%'", "'%'", "''", "'z%'", "'1'", "'" + chr(0xE9) + "%'",
                                                     "'" + chr(0x1F600) + "'", "'\\\\%'", "'x y'", "'It''%'")]:
                    a = c.count(f"f.{t}", cond)
                    b = c.count(f"f.{t}", f"{cond} OR {cond}")
                    if a != b:
                        mism.append((t, cond, a, b))
            # ORDER BY through the indexes (those of setup(), any the run made, and in the second stage those
            # made above) against the same statements sorted.
            for d in [("a",), ("v",), ("a", "b"), ("b", "a", "v"), ("v", "a")]:
                for q in ordered_statements(t, d):
                    s1, j1, d1 = c.q(q)
                    s2, j2, d2 = c.q(q.replace("ORDER BY ", "ORDER BY zz, ", 1))
                    if s1 != 200 or s2 != 200 or (j1["rows"], j1["truncated"]) != (j2["rows"], j2["truncated"]):
                        omism.append((q, s1, short(d1), s2, short(d2)))
        check(f"index lookups equal scans on every table, {stage}", not mism, repr(mism[:5]))
        check(f"ORDER BY in index order equals the sort on every table, {stage}", not omism, repr(omism[:3]))
    c.close()


def ordered_statements(t, d):
    """ORDER BY forms that an index on the columns `d` reads in order: nothing fixed, and = or IN on the first
    column, both ways, with a range and another term, with LIMIT and without."""
    out = []
    for direction in ("", " DESC"):
        keys = ", ".join(c + direction for c in d)
        out.append(f"SELECT _id, a, b, v FROM f.{t} ORDER BY {keys} LIMIT 7")
        out.append(f"SELECT _id FROM f.{t} ORDER BY {keys}")
        out.append(f"SELECT _id FROM f.{t} WHERE {d[0]} > 0 ORDER BY {keys} LIMIT 7")
        if len(d) > 1:
            rest = ", ".join(c + direction for c in d[1:])
            for a in ["1", "'a'", "TRUE", "NULL"]:
                out.append(f"SELECT _id, {', '.join(d)} FROM f.{t} WHERE {d[0]} = {a} ORDER BY {rest} LIMIT 7")
                out.append(f"SELECT _id FROM f.{t} WHERE {d[0]} IN ({a}, 2, 'b') ORDER BY {rest}, _id DESC LIMIT 7")
                out.append(f"SELECT _id FROM f.{t} WHERE {d[0]} = {a} AND {d[1]} >= 1 AND v != 2 ORDER BY {rest} LIMIT 3")
    return out
