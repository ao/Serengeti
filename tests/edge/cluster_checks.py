"""Three nodes: DDL and DML on the same objects through different nodes, conflicts made across a partition,
and a backup with indexes and conflicts restored onto a new node."""

import json
import random
import time

from common import Client, bad, check, dump, expect, ok, once, section, short
from conc_checks import ALLOWED, Tally, run_threads

CONFLICT_COLUMNS = ["_id", "current", "deleted", "node", "at", "row"]


def converge(hosts, seconds=90):
    """Waits until every node has the same state; returns (equal, states)."""
    deadline = time.time() + seconds
    states = []
    while time.time() < deadline:
        states = []
        try:
            for h in hosts:
                cl = Client(h)
                states.append(dump(cl))
                cl.close()
        except RuntimeError:  # a table went away between SHOW TABLES and SELECT while syncing
            time.sleep(1)
            continue
        if all(s == states[0] for s in states):
            return True, states
        time.sleep(1)
    return False, states


def ddl_race(hosts, seconds):
    section(f"DDL and DML on the same table through three nodes at once ({seconds} s)")
    c = Client(hosts[0])
    c.ok("CREATE DATABASE IF NOT EXISTS r")
    c.ok("CREATE TABLE IF NOT EXISTS r.x")
    tally = Tally()

    def inserter(h, w):
        def go(stop):
            cl = Client(h)
            i = 0
            while not stop.is_set():
                q = f"INSERT INTO r.x (_id, n, w) VALUES ('x{w}-{i}', {i % 7}, {w})"
                tally.add(q, *cl.q(q), allowed=ALLOWED)
                i += 1
            cl.close()
        return go

    def ddl(h, seed):
        def go(stop):
            cl = Client(h)
            r = random.Random(seed)
            while not stop.is_set():
                q = r.choice(["DROP TABLE IF EXISTS r.x", "CREATE TABLE IF NOT EXISTS r.x", "CREATE INDEX IF NOT EXISTS ON r.x (n)",
                              "DROP INDEX IF EXISTS ON r.x (n)", "UPDATE r.x SET n = 99 WHERE n = 3",
                              "DELETE FROM r.x WHERE n = 5"])
                tally.add(q, *cl.q(q), allowed=ALLOWED)
                time.sleep(r.random() * 0.1)
            cl.close()
        return go

    targets = [inserter(hosts[0], 0), inserter(hosts[1], 1), inserter(hosts[2], 2), inserter(hosts[1], 3),
               ddl(hosts[0], 1), ddl(hosts[2], 2)]
    done = run_threads(targets, seconds)
    check("every client finished", done, "a client is still waiting")
    tally.report("three-node DDL race")
    same, states = converge(hosts)
    check("the three nodes converge to the same databases, tables, indexes and rows", same,
          short(json.dumps([s.get("r") for s in states])[:600]))
    for h in hosts:
        cl = Client(h)
        cl.ok("CREATE TABLE IF NOT EXISTS r.x")
        n = cl.count("r.x")
        cl.close()
        check(f"{h} answers COUNT(*)", n >= 0, "")


def prep(hosts):
    section("conflicts: rows written on both sides of a partition")
    c = Client(hosts[0])
    c.ok("CREATE DATABASE IF NOT EXISTS k")
    c.ok("CREATE TABLE IF NOT EXISTS k.t")
    c.ok("CREATE INDEX IF NOT EXISTS ON k.t (v)")
    c.ok("INSERT INTO k.t (_id, v) VALUES ('a', 1), ('b', 2), ('c', 3), ('keep', 4)")
    same, _ = converge(hosts, 60)
    check("the rows reached every node", same, "")


def split(majority, minority):
    """Run with the minority node cut off from the others."""
    m = Client(majority)
    n = Client(minority)
    expect("minority: a write with ack local is 200", 200, n.q("UPDATE k.t SET v = 30, side = 'minority' WHERE _id = 'a'", ack="local")[0])
    expect("majority: a write to the same row is 200", 200, m.q("UPDATE k.t SET v = 10, side = 'majority' WHERE _id = 'a'")[0])
    expect("minority: delete b", 200, n.q("DELETE FROM k.t WHERE _id = 'b'", ack="local")[0])
    expect("majority: update b", 200, m.q("UPDATE k.t SET v = 20 WHERE _id = 'b'")[0])
    expect("minority: insert 'both'", 200, n.q("INSERT INTO k.t (_id, v) VALUES ('both', 300)", ack="local")[0])
    expect("majority: insert 'both'", 200, m.q("INSERT INTO k.t (_id, v) VALUES ('both', 100)")[0])
    expect("minority: a write without ack local is 503", 503, n.q("UPDATE k.t SET v = 0 WHERE _id = 'c'")[0])
    m.close()
    n.close()


def after_heal(hosts, path):
    same, states = converge(hosts, 90)
    check("after the partition heals, the nodes converge", same, "")
    # Current values can agree before every node holds every losing version: wait for the versions too.
    clients = [Client(h) for h in hosts]
    deadline = time.time() + 60
    started = time.time()
    lists = []
    while time.time() < deadline:
        lists = [cl.rows("SHOW CONFLICTS IN k.t") for cl in clients]
        if all(x == lists[0] for x in lists) and {r[0] for r in lists[0]} >= {"a", "both"}:
            break
        time.sleep(1)
    for cl in clients:
        cl.close()
    rows = lists[0]
    check(f"every node lists the same conflicts (after {time.time() - started:.0f} s)",
          all(x == rows for x in lists), repr(lists)[:600])
    c = Client(hosts[0])
    ids = sorted({r[0] for r in rows})
    check("SHOW CONFLICTS lists the rows written on both sides", {"a", "both"} <= set(ids), repr(rows))
    per = {}
    for r in rows:
        per.setdefault(r[0], []).append(r)
    check("each conflicting row has its current version first, and one current version",
          all(v[0][1] is True and sum(1 for x in v if x[1]) == 1 for v in per.values()), repr(rows))
    indexes = c.rows("SHOW INDEXES IN k.t")
    expect("the index replicated", [["v"]], indexes)
    with open(path, "w") as f:
        json.dump({"dump": states[0], "conflicts": rows, "indexes": indexes,
                   "values": c.rows("SELECT _id, v, side FROM k.t ORDER BY _id")}, f, sort_keys=True)
    ok(f"saved {len(rows)} conflict rows for the restore check")
    c.close()


def restored(host, path):
    section("backup with indexes and conflicts, restored onto a new node")
    with open(path) as f:
        saved = json.load(f)
    c = Client(host)
    expect("the restored node has the same databases, tables, indexes and rows",
           json.loads(json.dumps(saved["dump"])), json.loads(json.dumps(dump(c))))
    expect("the same indexes on k.t", saved["indexes"], c.rows("SHOW INDEXES IN k.t"))
    expect("the same conflicts, every version with node, time and value", saved["conflicts"],
           c.rows("SHOW CONFLICTS IN k.t"))
    expect("the same current values", saved["values"], c.rows("SELECT _id, v, side FROM k.t ORDER BY _id"))
    for v in [1, 4, 10, 20, 30, 100, 300]:
        a = c.count("k.t", f"v = {v}")
        b = c.count("k.t", f"v = {v} OR v = {v}")
        if a != b:
            bad(f"restored index lookup v = {v} equals a scan", f"{a} != {b}")
    ok("restored index lookups checked against scans")
    expect("a restored single node takes writes", 200, c.q("INSERT INTO k.t (_id, v) VALUES ('new', 5)")[0])
    c.close()


def wait_writable(host, seconds=90):
    """A cluster node takes writes once it has synced with a majority."""
    c = Client(host, timeout=10)
    deadline = time.time() + seconds
    s = 0
    while time.time() < deadline:
        s = c.q("CREATE DATABASE IF NOT EXISTS ready")[0]
        if s == 200:
            break
        time.sleep(0.5)
    c.close()
    expect(f"{host} takes writes", 200, s)


def backup(host, path):
    r = once(host, "GET", "/v1/backup", timeout=120)
    lines = r.body.rstrip(b"\n").split(b"\n")
    expect("GET /v1/backup is 200", 200, r.status)
    expect("as application/octet-stream", "application/octet-stream", r.header("content-type"))
    check("the backup ends with its trailer", b'"end":true' in lines[-1], repr(lines[-1][:200]))
    with open(path, "wb") as f:
        f.write(r.body)
