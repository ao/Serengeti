"""Statements generated from the grammar in docs/QUERY.md, some valid and some with one token changed.

Every answer must be 200 or a 400 with a message (never 500, never no answer), a parse error's position must
lie inside the text, a valid statement must never get a parse error, and the node must keep answering.
Valid SELECTs are also run as "WHERE (e) OR (e)", which never uses an index, and must give the same rows.

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
SYMBOLS = ["(", ")", ",", ".", ";", "*", "=", "!=", "<>", "<", "<=", ">", ">="]
CMP = ["=", "!=", "<>", "<", "<=", ">", ">="]
COLS = ["a", "b", "v", "_id", "on", "index", "conflicts", "x" * 63]
TABLES = [("f", "t"), ("f", "u"), ("f", "on"), ("f2", "x"), ("f", "nosuch"), ("nodb", "t")]
# Values a statement may compare or store. Strings include quotes, comment markers and non-ASCII text.
VALUES = ["NULL", "TRUE", "FALSE", "0", "-0", "1", "-1", "2", "1.0", "-0.0", "0.5", "1e300", "-2.5E-7",
          "9223372036854775807", "-9223372036854775808", "9223372036854775808", "9007199254740993", "''", "'a'",
          "'A'", "'1'", "'b'", "'It''s'", "'--'", "'; DROP DATABASE f'", "'" + chr(0xE9) + "'",
          "'" + chr(0x1F600) + "'", "'" + "z" * 200 + "'", "'\\'", "'x y'", "'" + chr(0) + "'"]
# Single tokens (or near-tokens) that replace one token of a valid statement.
JUNK = KEYWORDS + SYMBOLS + VALUES + [
    "a", "f", "t", "_", "_id", "INDEX", "INDEXES", "ON", "CONFLICTS", "y" * 64, "9abc", "1.", ".5", "1e", "1e309",
    "-", "!", "@", "#", "`", "\"a\"", "'unterminated", "''''", "--", "/*", "*/", "\\", "$1", "?", "%", "&", "|",
    "[", "]", "{", "}", ":", "::", "==", "=<", "<<", "!==", chr(0), chr(0xA0), chr(0x1F600), chr(0xFF0A),
    chr(0x2028), "\t", "\x0b", "N'x'", "X'00'", "0x10", "1_0", "NaN", "Infinity", "LIKE", "JOIN", "GROUP", "ALL",
    "(" * 70, "NOT " * 70, "SELECT * FROM f.t"]
SEMANTIC = ("does not exist", "already exists", "already has an index", "has no index", "_id must be",
            "_id cannot be changed", "_id needs no index", "over the maximum", "nested more than")


class Gen:
    def __init__(self, rng):
        self.r = rng

    def table(self):
        db, t = self.r.choice(TABLES[:4]) if self.r.random() < 0.9 else self.r.choice(TABLES)
        return [db, ".", t]

    def name(self):
        n = self.r.choice(COLS)
        return n.upper() if self.r.random() < 0.1 else n

    def value(self):
        return self.r.choice(VALUES)

    def expr(self, depth=0, budget=None):
        """A condition; `depth` counts parentheses and NOTs, which may reach 64."""
        r = self.r
        k = r.random()
        if depth < 64 and k < 0.12:
            return ["NOT"] + self.expr(depth + 1)
        if depth < 64 and k < 0.24:
            return ["("] + self.expr(depth + 1) + [")"]
        if k < 0.36:
            parts = [self.expr(depth) for _ in range(r.randint(2, 4))]
            out = parts[0]
            for p in parts[1:]:
                out = out + [r.choice(["AND", "OR"])] + p
            return out
        col = self.name()
        if k < 0.46:
            return [col, "IS"] + (["NOT"] if r.random() < 0.5 else []) + ["NULL"]
        return [col, r.choice(CMP), self.value()]

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

    def where(self):
        if self.r.random() < 0.25:
            return []
        return ["WHERE"] + (self.deep() if self.r.random() < 0.05 else self.expr())

    def statement(self):
        """Returns (tokens, kind). Every statement follows the grammar and the further parse rules."""
        r = self.r
        k = r.random()
        if k < 0.30:
            proj = r.random()
            if proj < 0.3:
                toks = ["SELECT", "*"]
            elif proj < 0.45:
                toks = ["SELECT", "COUNT", "(", "*", ")"]
            else:
                cols = [self.name() for _ in range(r.randint(1, 3))]
                toks = ["SELECT"] + sum(([c, ","] for c in cols), [])[:-1]
            toks += ["FROM"] + self.table() + self.where()
            if toks[1] != "COUNT":
                if r.random() < 0.4:
                    toks += ["ORDER", "BY", self.name()] + ([r.choice(["ASC", "DESC"])] if r.random() < 0.6 else [])
                if r.random() < 0.4:
                    toks += ["LIMIT", str(r.choice([0, 1, 2, 5, 100, 10000]))]
            return toks, "select"
        if k < 0.45:
            cols = r.sample(["a", "b", "v", "on", "_id"], r.randint(1, 3))
            n = r.randint(1, 4)
            toks = ["INSERT", "INTO"] + self.table() + ["("] + sum(([c, ","] for c in cols), [])[:-1] + [")", "VALUES"]
            tuples = []
            for _ in range(n):
                vals = []
                for c in cols:
                    vals.append(f"'r{r.randrange(10**9)}'" if c == "_id" and r.random() < 0.9 else self.value())
                tuples.append(["("] + sum(([v, ","] for v in vals), [])[:-1] + [")"])
            toks += sum((t + [","] for t in tuples), [])[:-1]
            return toks, "insert"
        if k < 0.60:
            cols = r.sample(["a", "b", "v", "on", "index"], r.randint(1, 3))
            sets = sum(([c, "=", self.value(), ","] for c in cols), [])[:-1]
            return ["UPDATE"] + self.table() + ["SET"] + sets + self.where(), "update"
        if k < 0.70:
            return ["DELETE", "FROM"] + self.table() + (self.where() or ["WHERE", "v", "=", self.value()]), "delete"
        if k < 0.80:
            op = r.choice(["CREATE", "DROP"])
            return [op, "INDEX"] + self._ifx(op) + ["ON"] + self.table() \
                + ["(", r.choice(["a", "v", "b", "_id", "on"]), ")"], "ddl"
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
                elif t in SYMBOLS or toks[i - 1] in SYMBOLS:
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


def setup(c):
    c.ok("CREATE DATABASE IF NOT EXISTS f")
    for t in ("t", "u", "on"):
        c.ok(f"CREATE TABLE IF NOT EXISTS f.{t}")
    c.ok("CREATE INDEX IF NOT EXISTS ON f.t (v)")
    c.ok("CREATE INDEX IF NOT EXISTS ON f.on (a)")
    rows = []
    rng = random.Random(1)
    for i in range(300):
        rows.append(f"('s{i:03d}', {rng.choice(VALUES)}, {rng.choice(VALUES)}, {rng.choice(VALUES)})")
    if c.count("f.t") == 0:
        c.ok("INSERT INTO f.t (_id, a, b, v) VALUES " + ", ".join(rows))
        c.ok("INSERT INTO f.u (_id, a, b, v) VALUES " + ", ".join(rows[:100]))
        c.ok("INSERT INTO f.on (_id, a, b, v) VALUES " + ", ".join(rows[100:200]))


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
        # The same rows without the index: (e) OR (e) never uses one.
        if valid and s == 200 and kind == "select" and "WHERE" in toks and "COUNT" not in toks[:2]:
            w = toks.index("WHERE")
            end = next((k for k in range(w + 1, len(toks)) if toks[k] in ("ORDER", "LIMIT")), len(toks))
            cond = toks[w + 1:end]
            depth = cond.count("(") + cond.count("NOT")
            if depth < 60:
                again = toks[:w + 1] + ["("] + cond + [")", "OR", "("] + cond + [")"] + toks[end:]
                s2, j2, d2 = c.q(" ".join(again))
                counts["metamorphic"] += 1
                if s2 != 200 or j2.get("rows") != j.get("rows") or j2.get("columns") != j.get("columns"):
                    meta_problems.append((i, query, f"{s2} {short(d2)} vs {short(data)}"))
        if i % 250 == 249:
            st = once(host, "GET", "/health", auth=False).status
            if st != 200:
                problems.append((i, None, "GET /health", f"status {st}"))
                break
    took = time.time() - t0
    ok(f"sent {n} statements in {took:.0f} s: " + ", ".join(f"{k} {v}" for k, v in counts.items()))
    for i, valid, query, p in problems[:8]:
        print(f"  #{i} {'valid' if valid else 'mutated'} {query!r:.300}: {p}", flush=True)
    check(f"every answer is 200 or a 400 with a message (seed {seed})", not problems,
          f"{len(problems)} bad answers, first: {problems[0][3] if problems else ''}")
    for i, query, p in meta_problems[:5]:
        print(f"  #{i} {query!r:.300}: {p}", flush=True)
    check(f"(e) OR (e) gives the same rows as e, {counts['metamorphic']} SELECTs (seed {seed})", not meta_problems,
          f"{len(meta_problems)} differ")
    check("valid statements were mostly accepted", counts["valid-200"] > counts["valid-400"], repr(counts))
    expect("the node answers afterwards", 200, once(host, "GET", "/health", auth=False).status)
    # The writes left every table in a state where index lookups and scans agree, before and after the
    # indexes are built again from the rows.
    for stage in ("as the run left them", "after DROP and CREATE INDEX"):
        mism = []
        for t in ("t", "u", "on"):
            if stage != "as the run left them":
                for col in ("a", "v"):
                    c.ok(f"DROP INDEX IF EXISTS ON f.{t} ({col})")
                    c.ok(f"CREATE INDEX ON f.{t} ({col})")
            for col in ("a", "v"):
                for op in ["=", "<", ">="]:
                    for v in VALUES:
                        if v == "NULL":
                            continue
                        a = c.count(f"f.{t}", f"{col} {op} {v}")
                        b = c.count(f"f.{t}", f"{col} {op} {v} OR {col} {op} {v}")
                        if a != b:
                            mism.append((t, col, op, v, a, b))
        check(f"index lookups equal scans on every table, {stage}", not mism, repr(mism[:5]))
    c.close()
