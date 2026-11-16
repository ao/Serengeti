"""Acknowledged writes across kill -9 and restarts.

`write` runs detached in the client container while run.sh kills the node. Each writer thread owns its rows
and logs every statement before sending it ("S") and once it is acknowledged ("A"), so after the restart
every row can be checked: its state must be the one its last acknowledged statement left, or, if the
thread had one more statement in flight when the node died, the state that statement would leave.
"""

import glob
import os
import threading

from common import Client, check, expect, lit, section

TABLE = "d.w"


def write(host, tag, threads, logdir):
    c = Client(host)
    c.ok("CREATE DATABASE IF NOT EXISTS d")
    c.ok(f"CREATE TABLE IF NOT EXISTS {TABLE}")
    c.ok(f"CREATE INDEX IF NOT EXISTS ON {TABLE} (v)")
    c.close()
    os.makedirs(logdir, exist_ok=True)

    def worker(t):
        cl = Client(host, timeout=10)
        with open(f"{logdir}/{tag}-{t}.log", "w", buffering=1) as log:
            i = 0
            while not os.path.exists(f"{logdir}/stop"):
                rid = f"{tag}-{t}-{i:06d}"
                ops = [("I", rid, f"INSERT INTO {TABLE} (_id, v, t) VALUES ('{rid}', 0, {t})")]
                if i >= 1 and i % 3 == 0:
                    prev = f"{tag}-{t}-{i - 1:06d}"
                    ops.append(("U", prev, f"UPDATE {TABLE} SET v = 1 WHERE _id = '{prev}'"))
                if i >= 2 and i % 5 == 0:
                    prev = f"{tag}-{t}-{i - 2:06d}"
                    ops.append(("D", prev, f"DELETE FROM {TABLE} WHERE _id = '{prev}'"))
                for kind, row, q in ops:
                    log.write(f"S {kind} {row}\n")
                    s, j, data = cl.q(q)
                    if s != 200:
                        log.write(f"E {s}\n")
                        return
                    log.write(f"A {kind} {row}\n")
                i += 1

    ts = [threading.Thread(target=worker, args=(t,)) for t in range(threads)]
    for x in ts:
        x.start()
    for x in ts:
        x.join()


def expected(logdir):
    """For each row: the set of states it may be in, as None (absent), 0 or 1."""
    after = {"I": 0, "U": 1, "D": None}
    rows = {}
    acked = 0
    for path in sorted(glob.glob(f"{logdir}/*.log")):
        last_state = {}
        inflight = None
        for line in open(path):
            parts = line.split()
            if not parts:
                continue
            if parts[0] == "S":
                inflight = (parts[1], parts[2])
            elif parts[0] == "A":
                last_state[parts[2]] = after[parts[1]]
                inflight = None
                acked += 1
        for row, st in last_state.items():
            rows[row] = {st}
        if inflight:
            kind, row = inflight
            rows.setdefault(row, {None}).add(after[kind])
    return rows, acked


def verify(host, logdir, label):
    section(f"acknowledged writes after {label}")
    rows, acked = expected(logdir)
    c = Client(host)
    got = {}
    last = None
    while True:
        where = "" if last is None else f" WHERE _id > {lit(last)}"
        page = c.rows(f"SELECT _id, v FROM {TABLE}{where} LIMIT 10000")
        for rid, v in page:
            got[rid] = v
        if len(page) < 10000:
            break
        last = page[-1][0]
    check(f"writes were acknowledged ({acked} statements, {len(rows)} rows)", acked > 0, "none")
    wrong = [(r, sorted(map(str, ok_states)), got.get(r, None)) for r, ok_states in rows.items()
             if got.get(r, None) not in ok_states]
    check(f"every row is in the state its acknowledged statements left ({label})", not wrong,
          f"{len(wrong)} rows wrong, e.g. {wrong[:5]}")
    extra = [r for r in got if r not in rows]
    check("and no row appeared that no statement wrote", not extra, repr(extra[:5]))
    expect("COUNT(*) equals the rows read", len(got), c.count(TABLE))
    for v in (0, 1):
        expect(f"index lookup v = {v} equals a scan", c.count(TABLE, f"v = {v} OR v = {v}"), c.count(TABLE, f"v = {v}"))
    expect("the index is still there", [["v"]], c.rows(f"SHOW INDEXES IN {TABLE}"))
    c.close()
