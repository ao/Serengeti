"""Many clients changing the same objects at once: DDL racing DML, then checks that the node answers and its
state is consistent (counts match, index lookups match scans), and that a restart gives the same state."""

import json
import random
import threading
import time

from common import Client, bad, check, dump, expect, lit, ok, once, section, short
from fuzz import Gen, answer_problem, setup as fuzz_setup

ALLOWED = ("does not exist", "already exists", "already has an index", "has no index")


class Tally:
    def __init__(self):
        self.lock = threading.Lock()
        self.codes = {}
        self.problems = []

    def add(self, query, s, j, data, allowed=ALLOWED):
        with self.lock:
            self.codes[s] = self.codes.get(s, 0) + 1
            msg = (j or {}).get("error", "") if s == 400 else ""
            if s == 200 or (s == 400 and any(a in msg for a in allowed)):
                return True
            self.problems.append((short(query, 120), s, short(data.decode("utf-8", "replace"), 200)))
            return False

    def report(self, name):
        check(f"{name}: every answer is 200 or an expected 400 ({self.codes})", not self.problems,
              repr(self.problems[:4]))


def run_threads(targets, seconds):
    stop = threading.Event()
    threads = [threading.Thread(target=t, args=(stop,)) for t in targets]
    for t in threads:
        t.start()
    time.sleep(seconds)
    stop.set()
    for t in threads:
        t.join(120)
    return all(not t.is_alive() for t in threads)


def index_matches_scan(c, table, column, literals):
    mism = []
    for L in literals:
        for op in ["=", "<", "<=", ">", ">="]:
            a = c.count(table, f"{column} {op} {lit(L)}")
            b = c.count(table, f"NOT (NOT ({column} {op} {lit(L)}))")
            if a != b:
                mism.append((op, L, a, b))
    return mism


def count_matches_rows(c, table):
    n = c.count(table)
    rows, last = 0, None
    while True:
        where = "" if last is None else f" WHERE _id > {lit(last)}"
        got = c.ids(f"SELECT _id FROM {table}{where} LIMIT 10000")
        rows += len(got)
        if len(got) < 10000:
            break
        last = got[-1]
    return n, rows


def run(host, seconds):
    c = Client(host)
    c.ok("CREATE DATABASE IF NOT EXISTS c")

    section(f"DROP TABLE and CREATE INDEX while 8 clients insert ({seconds} s)")
    c.ok("CREATE TABLE IF NOT EXISTS c.t")
    tally = Tally()

    def inserter(w):
        def go(stop):
            cl = Client(host)
            i = 0
            while not stop.is_set():
                n = 1 + i % 5
                vals = ", ".join(f"('t{w}-{i}-{k}', {w}, {(i + k) % 13})" for k in range(n))
                q = f"INSERT INTO c.t (_id, w, i) VALUES {vals}"
                tally.add(q, *cl.q(q))
                i += 1
            cl.close()
        return go

    def dropper(stop):
        cl = Client(host)
        r = random.Random(1)
        while not stop.is_set():
            q = r.choice(["DROP TABLE c.t", "DROP TABLE IF EXISTS c.t", "CREATE TABLE c.t", "CREATE TABLE IF NOT EXISTS c.t",
                          "CREATE TABLE IF NOT EXISTS c.t"])
            tally.add(q, *cl.q(q))
            time.sleep(r.random() * 0.05)
        cl.close()

    def indexer(stop):
        cl = Client(host)
        r = random.Random(2)
        while not stop.is_set():
            q = r.choice(["CREATE INDEX ON c.t (i)", "DROP INDEX ON c.t (i)", "CREATE INDEX IF NOT EXISTS ON c.t (w)",
                          "DROP INDEX IF EXISTS ON c.t (w)"])
            tally.add(q, *cl.q(q))
            time.sleep(r.random() * 0.05)
        cl.close()

    done = run_threads([inserter(w) for w in range(8)] + [dropper, indexer], seconds)
    check("every client finished", done, "a client thread is still waiting for an answer")
    tally.report("DROP TABLE while inserting")
    c.ok("CREATE TABLE IF NOT EXISTS c.t")
    n, rows = count_matches_rows(c, "c.t")
    expect(f"COUNT(*) equals the rows a scan returns ({n})", n, rows)
    c.ok("CREATE INDEX IF NOT EXISTS ON c.t (i)")
    c.ok("CREATE INDEX IF NOT EXISTS ON c.t (w)")
    check("index lookups on c.t equal scans", not index_matches_scan(c, "c.t", "i", list(range(-1, 14))) and
          not index_matches_scan(c, "c.t", "w", list(range(-1, 9))), "mismatch")

    section(f"CREATE and DROP INDEX while 8 clients update the indexed column ({seconds} s)")
    c.ok("CREATE TABLE IF NOT EXISTS c.u")
    c.ok("DELETE FROM c.u")
    c.ok("INSERT INTO c.u (_id, v) VALUES " + ", ".join(f"('u{i:04d}', {i % 50})" for i in range(2000)))
    pool = list(range(50)) + [None, "a", "b", "", 1.5, -0.0, True, False]
    tally = Tally()

    def updater(w):
        def go(stop):
            cl = Client(host)
            r = random.Random(100 + w)
            while not stop.is_set():
                k = r.random()
                v = r.choice(pool)
                if k < 0.4:
                    q = f"UPDATE c.u SET v = {lit(v)} WHERE _id = 'u{r.randrange(2000):04d}'"
                elif k < 0.7:
                    q = f"UPDATE c.u SET v = {lit(v)} WHERE v = {lit(r.choice(pool[:50]))}"
                elif k < 0.9:
                    lo = r.randrange(50)
                    q = f"UPDATE c.u SET v = {lit(v)}, w = {w} WHERE v >= {lo} AND v < {lo + 2}"
                else:
                    q = f"UPDATE c.u SET v = {lit(v)} WHERE v IS NULL OR v = 'a'"
                tally.add(q, *cl.q(q))
            cl.close()
        return go

    def index_flipper(stop):
        cl = Client(host)
        r = random.Random(3)
        while not stop.is_set():
            q = r.choice(["CREATE INDEX ON c.u (v)", "DROP INDEX ON c.u (v)", "CREATE INDEX IF NOT EXISTS ON c.u (v)",
                          "DROP INDEX IF EXISTS ON c.u (v)", "CREATE INDEX IF NOT EXISTS ON c.u (w)"])
            tally.add(q, *cl.q(q))
        cl.close()

    done = run_threads([updater(w) for w in range(8)] + [index_flipper, index_flipper], seconds)
    check("every client finished", done, "a client thread is still waiting for an answer")
    tally.report("CREATE INDEX while updating")
    expect("updates kept the row count", 2000, c.count("c.u"))
    c.ok("CREATE INDEX IF NOT EXISTS ON c.u (v)")
    mism = index_matches_scan(c, "c.u", "v", pool[:52:3] + ["a", "", 1.5, -0.0, True])
    check("index lookups on c.u equal scans", not mism, repr(mism[:5]))
    c.ok("DROP INDEX ON c.u (v)")
    c.ok("CREATE INDEX ON c.u (v)")
    mism2 = index_matches_scan(c, "c.u", "v", pool[:52:3] + ["a", "", 1.5, -0.0, True])
    check("and after the index is built again", not mism2, repr(mism2[:5]))

    section(f"DROP DATABASE while 8 clients read ({seconds} s)")
    tally = Tally()
    partial = []

    def ddl(stop):
        cl = Client(host)
        while not stop.is_set():
            for q in ["DROP DATABASE IF EXISTS c2", "CREATE DATABASE c2", "CREATE TABLE c2.t",
                      "INSERT INTO c2.t (_id, n) VALUES " + ", ".join(f"('p{i:03d}', {i})" for i in range(100)),
                      "CREATE INDEX ON c2.t (n)"]:
                tally.add(q, *cl.q(q))
        cl.close()

    def reader(w):
        def go(stop):
            cl = Client(host)
            r = random.Random(200 + w)
            while not stop.is_set():
                q = r.choice(["SELECT COUNT(*) FROM c2.t", "SELECT * FROM c2.t WHERE n > 50",
                              "SELECT n FROM c2.t ORDER BY n DESC LIMIT 5", "SHOW TABLES IN c2", "SHOW INDEXES IN c2.t",
                              "SHOW CONFLICTS IN c2.t", "SELECT COUNT(*) FROM c2.t WHERE n >= 0", "SHOW DATABASES"])
                s, j, data = cl.q(q)
                tally.add(q, s, j, data)
                if s == 200 and q.startswith("SELECT COUNT") and j["rows"][0][0] not in (0, 100):
                    partial.append((q, j["rows"]))
                if s == 200 and "n > 50" in q and j["count"] not in (0, 49):
                    partial.append((q, j["count"]))
            cl.close()
        return go

    done = run_threads([ddl] + [reader(w) for w in range(8)], seconds)
    check("every client finished", done, "a client thread is still waiting for an answer")
    tally.report("DROP DATABASE while reading")
    check("no read saw part of a 100-row INSERT (counts are 0 or 100)", not partial, repr(partial[:5]))

    section("16 clients insert the same 200 ids")
    results = {k: [] for k in range(200)}
    tally = Tally()
    c.ok("CREATE TABLE IF NOT EXISTS c.same")
    barrier = threading.Barrier(16)

    def same(w):
        cl = Client(host)
        barrier.wait()
        for k in range(200):
            q = f"INSERT INTO c.same (_id, w) VALUES ('same-{k}', {w})"
            s, j, data = cl.q(q)
            tally.add(q, s, j, data)
            results[k].append(s)
        cl.close()

    ts = [threading.Thread(target=same, args=(w,)) for w in range(16)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    tally.report("same-id inserts")
    wrong = {k: v for k, v in results.items() if v.count(200) != 1}
    check("each id was inserted exactly once", not wrong, repr(list(wrong.items())[:3]))
    expect("and the table holds 200 rows", 200, c.count("c.same"))

    section(f"16 clients send generated statements to one schema ({seconds} s)")
    fuzz_setup(c)
    tally = Tally()
    bad_answers = []

    def gen(w):
        def go(stop):
            cl = Client(host)
            g = Gen(random.Random(300 + w))
            while not stop.is_set():
                toks, _ = g.statement()
                q = g.text(toks)
                s, j, data = cl.q(q)
                p = answer_problem(q, s, j, data, True)
                with tally.lock:
                    tally.codes[s] = tally.codes.get(s, 0) + 1
                    if p:
                        bad_answers.append((short(q, 120), p))
            cl.close()
        return go

    done = run_threads([gen(w) for w in range(16)], seconds)
    check("every client finished", done, "a client thread is still waiting for an answer")
    check(f"every answer is 200 or a documented 400 ({tally.codes})", not bad_answers, repr(bad_answers[:4]))
    for t in ("t", "u", "on"):
        n, rows = count_matches_rows(c, f"f.{t}")
        expect(f"f.{t}: COUNT(*) equals a scan", n, rows)
    c.ok("CREATE INDEX IF NOT EXISTS ON f.t (v)")
    mism = index_matches_scan(c, "f.t", "v", [0, 1, -1, 2, "a", "", "1", 0.5, True, 1e300])
    check("f.t: index lookups equal scans", not mism, repr(mism[:5]))

    expect("the node answers /health", 200, once(host, "GET", "/health", auth=False).status)
    expect("and /ready", 200, once(host, "GET", "/ready", auth=False).status)
    c.close()


def save(host, path):
    c = Client(host)
    state = dump(c)
    with open(path, "w") as f:
        json.dump(state, f, sort_keys=True)
    c.close()
    ok(f"saved the state of {host}: {sum(len(t) for t in state.values())} tables, "
       f"{sum(t['count'] for d in state.values() for t in d.values())} rows")


def compare_saved(host, path, label):
    c = Client(host)
    with open(path) as f:
        before = json.load(f)
    after = json.loads(json.dumps(dump(c), sort_keys=True))
    diff = []
    for db in sorted(set(before) | set(after)):
        for t in sorted(set(before.get(db, {})) | set(after.get(db, {}))):
            a, b = before.get(db, {}).get(t), after.get(db, {}).get(t)
            if a != b:
                diff.append((db, t, None if a is None else (a["count"], a["indexes"]),
                             None if b is None else (b["count"], b["indexes"])))
    check(f"{label}: every database, table, index and row is as before", not diff, repr(diff[:5]))
    for db, tables in after.items():
        for t, info in tables.items():
            for col in info["indexes"]:
                vals = sorted({json.dumps(json.loads(r[1]).get(col)) for r in info["rows"]})[:20]
                lits = [json.loads(v) for v in vals if json.loads(v) is not None and not isinstance(json.loads(v), (list, dict))]
                mism = index_matches_scan(c, f"{db}.{t}", col, lits)
                if mism:
                    bad(f"{label}: index {db}.{t} ({col}) equals a scan", repr(mism[:3]))
    ok(f"{label}: index lookups checked against scans")
    c.close()


def drop_all(host):
    """Dropping every database gives back what the rows took, by the node's own estimate."""
    section("dropping everything gives the memory estimate back")
    c = Client(host)
    for (db,) in c.rows("SHOW DATABASES"):
        c.ok(f"DROP DATABASE {db}")
    expect("no database is left", [], c.rows("SHOW DATABASES"))
    s, body = c.get("/metrics")
    gauges = {}
    for line in body.decode().splitlines():
        if line.startswith(("serengeti_data_bytes ", "serengeti_databases ", "serengeti_memory_full ")):
            k, v = line.split()
            gauges[k] = float(v)
    expect("serengeti_databases is 0", 0.0, gauges.get("serengeti_databases"))
    expect("serengeti_data_bytes is 0", 0.0, gauges.get("serengeti_data_bytes"))
    expect("serengeti_memory_full is 0", 0.0, gauges.get("serengeti_memory_full"))
    c.close()
