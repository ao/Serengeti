#!/usr/bin/env python3
"""Randomized fault test (soak) for a Serengeti cluster. tests/soak/run.sh drives it; docs/SOAK.md says what it
checks.

    soak.py plan        print the fault schedule for SOAK_SEED, SOAK_NODES and SOAK_SECS
    soak.py nemesis     carry it out against the node containers (on the Docker host)
    soak.py setup       create the tables and hot rows (in the client container)
    soak.py work        run the clients until SOAK_T0 + SOAK_SECS (client container)
    soak.py converge    wait until every node holds the same data (client container)
    soak.py check       compare every node with what the clients were told (client container)

Settings come from SOAK_* environment variables, set by run.sh. Standard library only, so the same file runs on
the host's python3 and in python:3.12-alpine. The same seed gives the same fault schedule and, per client, the
same sequence of operations; how many operations fit in the run depends on how fast the nodes answer.
"""
import base64
import bisect
import collections
import glob
import gzip
import http.client
import itertools
import json
import os
import random
import re
import socket
import ssl
import subprocess
import sys
import threading
import time

ENV = os.environ
N = int(ENV.get("SOAK_NODES", "3"))
SEED = ENV.get("SOAK_SEED", "1")
SECS = float(ENV.get("SOAK_SECS", "600"))
OUT = ENV.get("SOAK_OUT", "/out")
T0 = float(ENV.get("SOAK_T0", "0") or 0)
QUORUM = N // 2 + 1
NODES = list(range(1, N + 1))

DB_TABLES = ["soak.hot", "soak.a", "soak.b", "soak2.c"]
COLD = ["soak.a", "soak.b", "soak2.c"]
HOT = "soak.hot"
HOT_ROWS = [f"h{i}" for i in range(8)]
# Indexes made at setup and never dropped, so check 6 always has something to compare.
FIXED_INDEXES = [("soak.a", "k"), ("soak.hot", "k"), ("soak2.c", "k")]
# Indexes the clients create and drop now and then.
TOGGLED_INDEXES = [("soak.a", "s"), ("soak.a", "m"), ("soak.b", "k"), ("soak.b", "n"), ("soak2.c", "m"),
                   ("soak2.c", "s"), ("soak.hot", "v")]
STRINGS = ["s0", "s1", "s2", "s3", "s4", "s5", "o'k", "Zed", "a b", "10"]


def now_ms():
    return time.time() * 1000.0


def elapsed(t_ms=None):
    t = (t_ms / 1000.0 if t_ms is not None else time.time()) - T0
    sign = "-" if t < 0 else "+"
    t = abs(t)
    return f"{sign}{int(t // 60):02d}m{t % 60:04.1f}s"


_event_lock = threading.Lock()


def event(text):
    """Appends one line to the run's event log and prints it."""
    line = f"{time.strftime('%H:%M:%S')} {elapsed()} {text}"
    with _event_lock:
        with open(os.path.join(OUT, "events.log"), "a") as f:
            f.write(line + "\n")
        print(line, flush=True)


def lit(v):
    """A value as a literal of the query language."""
    if v is None:
        return "NULL"
    if v is True:
        return "TRUE"
    if v is False:
        return "FALSE"
    if isinstance(v, (int, float)):
        return repr(v)
    return "'" + v.replace("'", "''") + "'"


# ---------------------------------------------------------------------------------------------------------------
# The fault schedule


class Episode:
    """One fault: actions at its start, actions at its end, and what it does to the cluster meanwhile."""

    def __init__(self, kind, start, end, start_actions, end_actions, down=(), cuts=(), busy=(), loss=False):
        self.kind, self.start, self.end = kind, start, end
        self.start_actions, self.end_actions = start_actions, end_actions
        self.down = frozenset(down)  # nodes not serving: killed, stopped, restarting or paused
        self.cuts = frozenset(cuts)  # node pairs that cannot reach each other
        self.busy = frozenset(busy)  # nodes this episode holds; no other node-level fault may touch them
        self.loss = loss  # a majority is lost on purpose

    def describe(self):
        acts = "; ".join(fmt_action(a) for a in self.start_actions)
        undo = "; ".join(fmt_action(a) for a in self.end_actions) or "-"
        return f"{self.start:8.1f} {self.end:8.1f} {self.kind:<16} {acts} | then {undo}"


def fmt_action(a):
    name, arg = a[0], a[1] if len(a) > 1 else None
    if name in ("cut", "heal"):
        return f"{name} " + ",".join(f"n{x}-n{y}" for x, y in sorted(arg))
    if name == "netem":
        p = a[2]
        return f"netem n{arg} delay {p['delay']}ms jitter {p['jitter']}ms loss {p['loss']}%"
    return f"{name} n{arg}"


def pairs_between(groups):
    out = set()
    for g, h in itertools.combinations(groups, 2):
        for x in g:
            for y in h:
                out.add((min(x, y), max(x, y)))
    return out


def core_ok(episodes):
    """True if some majority of nodes is serving and every pair of them can reach each other."""
    down, cuts = set(), set()
    for e in episodes:
        down |= e.down
        cuts |= e.cuts
    alive = [i for i in NODES if i not in down]
    for size in range(len(alive), QUORUM - 1, -1):
        for s in itertools.combinations(alive, size):
            if all((x, y) not in cuts for x, y in itertools.combinations(s, 2)):
                return True
    return False


# Kinds of fault for the normal mix, and how often each is picked.
KINDS = [("kill", 20), ("stop", 8), ("restart", 6), ("pause", 15), ("partition", 20), ("bridge", 8), ("netem", 12)]


def episode(kind, rng, t, active):
    busy = set().union(*[e.busy for e in active]) if active else set()
    free = [i for i in NODES if i not in busy]
    partitioned = any(e.cuts for e in active)
    if kind == "kill":
        if not free:
            return None
        i = rng.choice(free)
        d = 0.2 if rng.random() < 0.3 else rng.uniform(1, 20)
        return Episode("kill", t, t + d, [("kill", i)], [("start", i)], down=[i], busy=[i])
    if kind == "stop":
        if not free:
            return None
        i = rng.choice(free)
        d = rng.uniform(0.2, 15)
        return Episode("stop", t, t + d, [("stop", i)], [("start", i)], down=[i], busy=[i])
    if kind == "restart":
        if not free:
            return None
        i = rng.choice(free)
        return Episode("restart", t, t + 8, [("restart", i)], [], down=[i], busy=[i])
    if kind == "pause":
        if not free:
            return None
        i = rng.choice(free)
        d = rng.uniform(1, 20)
        return Episode("pause", t, t + d, [("pause", i)], [("unpause", i)], down=[i], busy=[i])
    if kind == "netem":
        if not free:
            return None
        i = rng.choice(free)
        delay = rng.choice([10, 50, 100, 250, 500])
        p = {"delay": delay, "jitter": int(delay * rng.choice([0, 0.2, 0.5])), "loss": rng.choice([0, 0, 1, 5, 10, 30])}
        d = rng.uniform(5, 40)
        return Episode("netem", t, t + d, [("netem", i, p)], [("netem_off", i)], busy=[i])
    if kind == "partition":
        if partitioned:
            return None
        m = rng.randint(1, N - QUORUM)
        side = sorted(rng.sample(NODES, m))
        rest = [i for i in NODES if i not in side]
        groups = [side, rest]
        if len(side) >= 2 and rng.random() < 0.5:
            groups = [[x] for x in side] + [rest]
        cuts = pairs_between(groups)
        d = rng.uniform(2, 30)
        return Episode("partition", t, t + d, [("cut", frozenset(cuts))], [("heal", frozenset(cuts))], cuts=cuts)
    if kind == "bridge":
        if partitioned:
            return None
        all_pairs = list(itertools.combinations(NODES, 2))
        k = 1 if N <= 3 else rng.randint(1, 3)
        cuts = set(rng.sample(all_pairs, k))
        d = rng.uniform(3, 30)
        return Episode("bridge", t, t + d, [("cut", frozenset(cuts))], [("heal", frozenset(cuts))], cuts=cuts)
    raise ValueError(kind)


def majority_loss(rng, t):
    """A short period with no majority: writes that need one get 503, local acks go on."""
    kind = rng.choice(["kill-majority", "pause-majority", "split", "kill-all", "isolate-and-kill"])
    d = rng.uniform(5, 25)
    if kind == "kill-majority":
        ns = sorted(rng.sample(NODES, QUORUM))
        return Episode(kind, t, t + d, [("kill", i) for i in ns], [("start", i) for i in ns], down=ns, busy=ns, loss=True)
    if kind == "pause-majority":
        ns = sorted(rng.sample(NODES, QUORUM))
        return Episode(kind, t, t + d, [("pause", i) for i in ns], [("unpause", i) for i in ns], down=ns, busy=ns,
                       loss=True)
    if kind == "kill-all":
        d = rng.uniform(0.5, 8)
        return Episode(kind, t, t + d, [("kill", i) for i in NODES], [("start", i) for i in NODES], down=NODES,
                       busy=NODES, loss=True)
    if kind == "split":
        order = NODES[:]
        rng.shuffle(order)
        groups, g = [], []
        for i in order:  # groups of at most QUORUM - 1 nodes, so none has a majority
            g.append(i)
            if len(g) == QUORUM - 1 or rng.random() < 0.4:
                groups.append(g)
                g = []
        if g:
            groups.append(g)
        cuts = pairs_between(groups)
        return Episode(kind, t, t + d, [("cut", frozenset(cuts))], [("heal", frozenset(cuts))], cuts=cuts, loss=True)
    # isolate-and-kill: cut off a minority, then kill one node of the rest
    side = sorted(rng.sample(NODES, N - QUORUM))
    rest = [i for i in NODES if i not in side]
    victim = rng.choice(rest)
    cuts = pairs_between([side, rest])
    return Episode(kind, t, t + d, [("cut", frozenset(cuts)), ("kill", victim)],
                   [("start", victim), ("heal", frozenset(cuts))], down=[victim], cuts=cuts, busy=[victim], loss=True)


SCENARIO = ENV.get("SOAK_SCENARIO", "")


def make_plan():
    if SCENARIO == "graceful-stop":
        # docker stop (SIGTERM) on n2 while several clients send it writes and majority reads. A majority write
        # in flight when the cluster layer closes never completes, which keeps the HTTP server's stop(1) waiting
        # for a second; the requests that the other clients send meanwhile meet the closed cluster layer.
        return [Episode("stop", 6, 12, [("stop", 2)], [("start", 2)], down=[2], busy=[2])]
    rng = random.Random(f"{SEED}/faults/{N}")
    eps, active = [], []
    t = rng.uniform(5, 10)
    next_loss = rng.uniform(40, 120)  # so that even a 5-minute run loses its majority at least once

    names, weights = zip(*KINDS)
    while t < SECS - 10:
        active = [e for e in active if e.end > t]
        if t >= next_loss:
            if active:  # let the running faults end first
                t = max(e.end for e in active) + rng.uniform(0.5, 2)
                continue
            e = majority_loss(rng, t)
            eps.append(e)
            active = [e]
            t = e.end + rng.uniform(3, 8)
            next_loss = t + 60 + rng.expovariate(1 / 240)
            continue
        e = episode(rng.choices(names, weights)[0], rng, t, active)
        if e is not None and core_ok(active + [e]):
            eps.append(e)
            active.append(e)
        t += rng.uniform(1, 5)
    return eps


def cmd_plan():
    eps = make_plan()
    print(f"# fault schedule: seed {SEED}, {N} nodes, {SECS:.0f} s; start end (s from the start) kind actions")
    for e in eps:
        print(e.describe())
    c = collections.Counter(e.kind for e in eps)
    print("# " + ", ".join(f"{k} {v}" for k, v in sorted(c.items())))


# ---------------------------------------------------------------------------------------------------------------
# The nemesis: runs on the Docker host and carries out the schedule


class Nemesis:
    def __init__(self):
        self.run = ENV["SOAK_RUN"]
        self.image = ENV["SOAK_FAULT_IMAGE"]
        self.ips = dict(zip(NODES, ENV["SOAK_IPS"].split()))
        self.netem_ok = ENV.get("SOAK_NETEM", "1") == "1"
        self.lock = threading.RLock()
        self.state = {i: "up" for i in NODES}  # up, killed, stopped, restarting, paused
        self.cuts = set()
        self.netem = {}
        self.pending = set()  # nodes whose network rules could not be set; set again when they start
        self.done = threading.Event()
        self.counts = collections.Counter()
        self.unexpected = []
        self.errors = []
        self.max_lag = 0.0
        self.history = []  # (unix ms, action, node, seconds, exit code) of every stop, restart, kill and start
        self.stalls = []  # nodes that did not answer /health while running (see prober)

    def name(self, i):
        return f"{self.run}-n{i}"

    def docker(self, *args, timeout=90):
        try:
            p = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
            return p.returncode, (p.stdout + p.stderr).strip()
        except subprocess.TimeoutExpired:
            return 124, "timed out"

    def must(self, what, *args, timeout=90):
        rc, out = self.docker(*args, timeout=timeout)
        if rc != 0:
            self.errors.append(f"{elapsed()} {what}: exit {rc}: {out[-300:]}")
            event(f"nemesis error: {what}: exit {rc}: {out[-200:]}")
        return rc == 0

    def net_script(self, i):
        lines = ["iptables -F INPUT", "iptables -F OUTPUT"]
        for x, y in sorted(self.cuts):
            if i in (x, y):
                peer = self.ips[y if x == i else x]
                lines.append(f"iptables -A INPUT -s {peer} -j DROP")
                lines.append(f"iptables -A OUTPUT -d {peer} -j DROP")
        if self.netem_ok:
            lines.append(f"IF=$(ip -o -4 addr | grep ' {self.ips[i]}/' | cut -d' ' -f2)")
            lines.append('tc qdisc del dev "$IF" root 2>/dev/null || true')
            p = self.netem.get(i)
            if p:
                q = f'tc qdisc add dev "$IF" root netem delay {p["delay"]}ms'
                if p["jitter"]:
                    q += f' {p["jitter"]}ms'
                if p["loss"]:
                    q += f' loss {p["loss"]}%'
                lines.append(q)
        return "set -e\n" + "\n".join(lines) + "\n"

    def sync_net(self, nodes):
        """Makes the packet rules and netem in each node's network namespace match the wanted state."""
        nodes = [i for i in nodes if self.state[i] in ("up", "paused")]
        results = {}

        def one(i):
            results[i] = self.docker("run", "--rm", "--label", f"serengeti-soak={self.run}", "--network",
                                     f"container:{self.name(i)}", "--cap-add", "NET_ADMIN", self.image, "sh", "-c",
                                     self.net_script(i), timeout=60)

        ts = [threading.Thread(target=one, args=(i,)) for i in nodes]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        for i in nodes:
            rc, out = results[i]
            if rc != 0:
                self.pending.add(i)
                self.errors.append(f"{elapsed()} network rules on n{i}: exit {rc}: {out[-300:]}")
                event(f"nemesis error: network rules on n{i}: {out[-200:]}")
            else:
                self.pending.discard(i)

    def act(self, a):
        name, i = a[0], a[1] if len(a) > 1 else None
        self.counts[name] += 1
        began = now_ms()
        exit_code = None
        if name == "kill":
            self.state[i] = "killed"
            self.must(f"kill n{i}", "kill", "-s", "KILL", self.name(i))
        elif name == "stop":
            self.state[i] = "stopped"
            if self.must(f"stop n{i}", "stop", "-t", "10", self.name(i)):
                # 143: the JVM exited on SIGTERM; 137: docker killed it after the 10 s grace period.
                exit_code = self.docker("inspect", "-f", "{{.State.ExitCode}}", self.name(i))[1]
        elif name == "start":
            if self.must(f"start n{i}", "start", self.name(i)):
                self.state[i] = "up"
                self.sync_net([i])
        elif name == "restart":
            self.state[i] = "restarting"
            ok = self.must(f"restart n{i}", "restart", "-t", "10", self.name(i))
            self.state[i] = "up" if ok else "stopped"
            if ok:
                self.sync_net([i])
        elif name == "pause":
            if self.must(f"pause n{i}", "pause", self.name(i)):
                self.state[i] = "paused"
        elif name == "unpause":
            if self.must(f"unpause n{i}", "unpause", self.name(i)):
                self.state[i] = "up"
                if i in self.pending:
                    self.sync_net([i])
        elif name == "cut":
            self.cuts |= set(i)
            self.sync_net(sorted({x for p in i for x in p}))
        elif name == "heal":
            self.cuts -= set(i)
            self.sync_net(sorted({x for p in i for x in p}))
        elif name == "netem":
            self.netem[i] = a[2]
            if self.netem_ok:
                self.sync_net([i])
            else:
                self.counts["netem_skipped"] += 1
        elif name == "netem_off":
            self.netem.pop(i, None)
            if self.netem_ok:
                self.sync_net([i])
        else:
            raise ValueError(name)
        if name in ("kill", "stop", "restart", "start", "pause", "unpause"):
            # When it was sent, how long the docker command (and the network rules after a start) took.
            self.history.append((round(began), name, i, round((now_ms() - began) / 1000, 2), exit_code))

    def monitor(self):
        """Every 2 s: a node the nemesis has not stopped must be running. One that exited is logged and started."""
        names = [self.name(i) for i in NODES]
        while not self.done.wait(2):
            with self.lock:
                rc, out = self.docker("inspect", "-f", "{{.Name}} {{.State.Status}} {{.State.ExitCode}} "
                                      "{{.State.OOMKilled}}", *names, timeout=30)
                if rc != 0:
                    continue
                for line in out.splitlines():
                    parts = line.strip().lstrip("/").split()
                    if len(parts) < 4:
                        continue
                    i = int(parts[0].rsplit("-n", 1)[1])
                    status, code, oom = parts[1], parts[2], parts[3]
                    if self.state[i] in ("up", "paused") and status not in ("running", "paused", "restarting"):
                        tail = self.docker("logs", "--tail", "60", self.name(i))[1]
                        self.unexpected.append({"node": i, "at": elapsed(), "status": status, "exit": code,
                                                "oom": oom, "log_tail": tail[-6000:]})
                        event(f"UNEXPECTED EXIT n{i}: status {status}, exit code {code}, OOM killed {oom}; starting it")
                        self.state[i] = "stopped"
                        if self.must(f"start n{i} after an unexpected exit", "start", self.name(i)):
                            self.state[i] = "up"
                            self.sync_net([i])

    def prober(self, i):
        """Every 2 s, GET /health on node i once it has been running, unpaused and without netem for 20 s. After
        15 s without an answer the JVM gets SIGQUIT once, so the node's log holds a thread dump of the stall."""
        since, dumped = None, False
        while not self.done.wait(2):
            with self.lock:
                quiet = self.state[i] == "up" and i not in self.netem
                last = max((h[0] + h[3] * 1000 for h in self.history if h[2] == i), default=0)
            t = now_ms()
            if not quiet or t - last < 20_000:
                if dumped:  # the nemesis acted on the node during the stall
                    self.stalls.append({"node": i, "from": round(since), "to": round(t)})
                since, dumped = None, False
                continue
            try:
                ok = subprocess.run(["curl", "-sk", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "5",
                                     f"https://{self.ips[i]}:1985/health"], capture_output=True, text=True,
                                    timeout=10).stdout == "200"
            except (OSError, subprocess.TimeoutExpired):
                ok = False
            if ok:
                if dumped:
                    self.stalls.append({"node": i, "from": round(since), "to": round(t)})
                    event(f"n{i} answers /health again, after {(t - since) / 1000:.0f} s")
                since, dumped = None, False
            elif since is None:
                since = t
            elif not dumped and now_ms() - since >= 15_000:
                self.docker("kill", "-s", "QUIT", self.name(i))
                dumped = True
                event(f"STALL n{i} has not answered /health for {(now_ms() - since) / 1000:.0f} s while running; "
                      f"sent SIGQUIT, so its log has a thread dump")
        if dumped:  # still not answering when the run ended
            self.stalls.append({"node": i, "from": round(since), "to": None})

    def heal_all(self):
        with self.lock:
            for i in NODES:
                if self.state[i] == "paused":
                    self.act(("unpause", i))
            for i in NODES:
                if self.state[i] in ("killed", "stopped"):
                    self.act(("start", i))
            self.cuts.clear()
            self.netem.clear()
            self.sync_net(NODES)

    def main(self):
        eps = make_plan()
        timeline = []
        for k, e in enumerate(eps):
            for a in e.start_actions:
                timeline.append((e.start, k, 0, e, a))
            for a in e.end_actions:
                timeline.append((e.end, k, 1, e, a))
        timeline.sort(key=lambda x: (x[0], x[1], x[2]))
        mon = threading.Thread(target=self.monitor, daemon=True)
        while time.time() < T0:
            time.sleep(0.05)
        mon.start()
        probers = [threading.Thread(target=self.prober, args=(i,), daemon=True) for i in NODES]
        for p in probers:
            p.start()
        event(f"nemesis: {len(eps)} faults planned over {SECS:.0f} s (seed {SEED}, {N} nodes)")
        for at, k, phase, e, a in timeline:
            if at >= SECS:
                break
            wait = T0 + at - time.time()
            if wait > 0:
                time.sleep(wait)
            lag = time.time() - (T0 + at)
            self.max_lag = max(self.max_lag, lag)
            with self.lock:
                if phase == 0 and a is e.start_actions[0]:
                    event(f"fault {k} {e.kind}{' (majority lost)' if e.loss else ''}: {fmt_action(a)}"
                          + (f" (+{len(e.start_actions) - 1} more)" if len(e.start_actions) > 1 else ""))
                elif phase == 1 and a is e.end_actions[0]:
                    event(f"undo {k} {e.kind}: {fmt_action(a)}"
                          + (f" (+{len(e.end_actions) - 1} more)" if len(e.end_actions) > 1 else ""))
                self.act(a)
        while time.time() < T0 + SECS:
            time.sleep(0.2)
        event("nemesis: the run is over; undoing every fault")
        self.heal_all()
        time.sleep(3)
        self.done.set()
        mon.join(timeout=10)
        for p in probers:
            p.join(timeout=15)
        kinds = collections.Counter(e.kind for e in eps if e.start < SECS)
        summary = {"faults": dict(kinds), "actions": dict(self.counts), "history": self.history,
                   "unexpected_exits": self.unexpected, "stalls": self.stalls,
                   "errors": self.errors, "max_lag_s": round(self.max_lag, 2), "netem": self.netem_ok}
        with open(os.path.join(OUT, "nemesis.json"), "w") as f:
            json.dump(summary, f, indent=1)
        stops = [h for h in self.history if h[1] in ("stop", "restart")]
        codes = collections.Counter(h[4] for h in self.history if h[1] == "stop")
        event("nemesis: faults " + ", ".join(f"{k} {v}" for k, v in sorted(kinds.items()))
              + f"; unexpected exits {len(self.unexpected)}; nemesis errors {len(self.errors)}; "
              f"largest lag behind the schedule {self.max_lag:.1f} s; docker stop/restart took "
              f"{max((h[3] for h in stops), default=0):.1f} s at most, {sum(1 for h in stops if h[3] >= 10)} of "
              f"{len(stops)} 10 s or more; stop exit codes {dict(codes)}")


# ---------------------------------------------------------------------------------------------------------------
# HTTP client (client container)


def tls_context():
    return ssl.create_default_context(cafile=os.path.join(OUT, "ca.pem"))


AUTH = "Basic " + base64.b64encode(("admin:" + ENV.get("SOAK_PW", "")).encode()).decode()


class Conn:
    """One kept-alive HTTPS connection to node i, through its client network name cI."""

    def __init__(self, i, ctx, timeout=12.0):
        self.i, self.ctx, self.timeout = i, ctx, timeout
        self.c = None
        self.used = 0.0

    def close(self):
        if self.c is not None:
            try:
                self.c.close()
            except Exception:
                pass
        self.c = None

    def call(self, method, path, body=None, headers=None):
        """Returns (status, parsed body). status is the HTTP code, or "conn" if the request was never sent (no
        connection), or "timeout"/"error" if it was sent and no answer came back (it may have been applied)."""
        if self.c is not None and time.time() - self.used > 10:
            self.close()  # the server may have dropped an idle connection
        sent = False
        try:
            if self.c is None:
                self.c = http.client.HTTPSConnection(f"c{self.i}", 1985, context=self.ctx, timeout=self.timeout)
                self.c.connect()
            sent = True
            h = {"Authorization": AUTH}
            if body is not None:
                h["Content-Type"] = "application/json"
            if headers:
                h.update(headers)
            self.c.request(method, path, body=None if body is None else json.dumps(body), headers=h)
            r = self.c.getresponse()
            raw = r.read()
            self.used = time.time()
            if r.getheader("Connection", "").lower() == "close":
                self.close()
            try:
                return r.status, json.loads(raw) if raw else None
            except ValueError:
                return r.status, raw.decode("utf-8", "replace")
        except (socket.timeout, TimeoutError) as e:
            self.close()
            return ("timeout" if sent else "conn"), str(e)[:200]
        except Exception as e:
            self.close()
            return ("error" if sent else "conn"), f"{type(e).__name__}: {e}"[:200]

    def query(self, q, ack=None, consistency=None):
        body = {"query": q}
        if ack:
            body["ack"] = ack
        if consistency:
            body["consistency"] = consistency
        return self.call("POST", "/v1/query", body)


def err_of(body):
    if isinstance(body, dict):
        return body.get("error")
    return body[:300] if isinstance(body, str) else body


# ---------------------------------------------------------------------------------------------------------------
# Setup and the workload (client container)


class OpLog:
    def __init__(self, path):
        self.f = open(path, "a", buffering=1 << 16)
        self.lock = threading.Lock()

    def write(self, rec):
        with self.lock:
            self.f.write(json.dumps(rec, separators=(",", ":")) + "\n")

    def flush(self):
        with self.lock:
            self.f.flush()


def pad(rid, n):
    """The padding of a row: its id repeated to n characters, so the checker can tell it is intact."""
    return ((rid + ":") * (n // (len(rid) + 1) + 1))[:n]


def pad_token(n):
    """How the logs and the checker write a padding of n characters that is intact."""
    return f"#pad{n}"


def run_logged(log, conn, rec, q, ack=None, consistency=None):
    rec["q"] = q[:300]
    rec["n"] = conn.i
    rec["t0"] = round(now_ms(), 1)
    status, body = conn.query(q, ack, consistency)
    rec["t1"] = round(now_ms(), 1)
    rec["s"] = status
    if status == 200 and isinstance(body, dict):
        if body.get("type") == "write":
            rec["aff"] = body.get("affected")
        else:
            rec["cols"] = body.get("columns")
            rec["rows"] = body.get("rows")
    else:
        rec["e"] = err_of(body)
    log.write(rec)
    return status, body


def status_of(i, conn):
    s, b = conn.call("GET", "/v1/status")
    return b if s == 200 and isinstance(b, dict) else None


def wait_writable(conns, limit=180):
    end = time.time() + limit
    for i, c in conns.items():
        while True:
            st = status_of(i, c)
            cl = (st or {}).get("cluster") or st or {}
            if st and cl.get("caught_up") and cl.get("reachable") == N:
                break
            if time.time() > end:
                sys.exit(f"node {i} never became writable: {st}")
            time.sleep(0.5)


def cmd_setup():
    ctx = tls_context()
    conns = {i: Conn(i, ctx) for i in NODES}
    wait_writable(conns)
    ids = {}
    for i, c in conns.items():
        st = status_of(i, c)
        ids[i] = st.get("node") or st.get("node_id") or st.get("id")
    with open(os.path.join(OUT, "nodes.json"), "w") as f:
        json.dump({"ids": ids, "status": status_of(1, conns[1])}, f, indent=1)
    os.makedirs(os.path.join(OUT, "ops"), exist_ok=True)
    log = OpLog(os.path.join(OUT, "ops", "setup.jsonl"))
    c = conns[1]

    def must(rec, q):
        s, b = run_logged(log, c, rec, q, "majority")
        if s != 200:
            sys.exit(f"setup: {q}: {s} {b}")

    for db in ["soak", "soak2"]:
        must({"c": "s", "op": "schema"}, f"CREATE DATABASE {db}")
    for t in DB_TABLES:
        must({"c": "s", "op": "schema"}, f"CREATE TABLE {t}")
    for h in HOT_ROWS:
        v, k = f"init-{h}", 0
        must({"c": "s", "op": "hins", "id": h, "v": v, "k": k, "a": "majority"},
             f"INSERT INTO {HOT} (_id, v, k) VALUES ('{h}', '{v}', {k})")
    for t, col in FIXED_INDEXES:
        must({"c": "s", "op": "ddl", "tb": t, "col": col, "what": "create", "a": "majority"},
             f"CREATE INDEX ON {t} ({col})")
    log.flush()
    # Every node has the schema before the clients start.
    for i, cc in conns.items():
        for _ in range(120):
            s, b = cc.query("SHOW TABLES IN soak2")
            if s == 200 and b.get("rows") == [["c"]]:
                s, b = cc.query(f"SELECT COUNT(*) FROM {HOT}")
                if s == 200 and b.get("rows") == [[len(HOT_ROWS)]]:
                    break
            time.sleep(0.5)
        else:
            sys.exit(f"setup: node {i} did not get the schema")
    print(f"setup: {len(DB_TABLES)} tables, {len(HOT_ROWS)} hot rows, {len(FIXED_INDEXES)} indexes; node ids {ids}")


def mixed(rng):
    r = rng.random()
    if r < 0.35:
        return rng.randint(-3, 12)
    if r < 0.5:
        return float(rng.randint(-3, 12))
    if r < 0.6:
        return rng.randint(0, 12) + 0.5
    if r < 0.8:
        return rng.choice(["m1", "m2", "M", "o'k", "10"])
    if r < 0.9:
        return rng.random() < 0.5
    return None


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.minute = collections.Counter()
        self.total = collections.Counter()

    def add(self, op, status):
        cls = status if status in (200, 503, 400) else ("5xx" if isinstance(status, int) and status >= 500
                                                       else ("other" if isinstance(status, int) else status))
        with self.lock:
            self.minute[(op, cls)] += 1
            self.total[(op, cls)] += 1

    def take(self):
        with self.lock:
            m = self.minute
            self.minute = collections.Counter()
        return m


def fmt_counts(m):
    by_status = collections.Counter()
    by_op = collections.Counter()
    for (op, cls), v in m.items():
        by_status[cls] += v
        by_op[op] += v
    order = [200, 400, 503, "5xx", "other", "timeout", "error", "conn"]
    st = " ".join(f"{k}={by_status[k]}" for k in order if by_status[k])
    ops = " ".join(f"{k}={v}" for k, v in sorted(by_op.items()))
    return f"ops={sum(by_status.values())} {st} | {ops}"


def client(ci, node, stats, stop_at):
    rng = random.Random(f"{SEED}/client/{ci}")
    ctx = tls_context()
    conn = Conn(node, ctx)
    log = OpLog(os.path.join(OUT, "ops", f"c{ci}.jsonl"))
    mine = []  # (table, id, row) of every insert this client sent, ok or not
    seq = 0
    last_flush = time.time()
    while time.time() < stop_at:
        seq += 1
        cid = f"c{ci}-{seq}"
        r = rng.random()
        ack = "local" if rng.random() < 0.35 else "majority"
        if SCENARIO == "graceful-stop":
            # Only inserts and majority reads: the kinds that a stopping node answered with 500.
            r = 0.0 if r < 0.7 else 0.95
        rec = {"c": ci, "i": seq}
        if r < 0.42:
            tb = rng.choice(COLD)
            nrows = 1 if rng.random() < 0.8 else rng.randint(2, 4)
            with_m = rng.random() < 0.85
            rows = []
            pads = []
            for j in range(nrows):
                rid = cid if nrows == 1 else f"{cid}.{j}"
                row = {"n": seq, "k": rng.randrange(40), "s": rng.choice(STRINGS)}
                if with_m:
                    row["m"] = mixed(rng)
                # 1 KB on average, so each node's log passes the 64 MiB checkpoint a few times an hour.
                n_pad = rng.randrange(2000)
                row["p"] = pad_token(n_pad)
                pads.append(pad(rid, n_pad))
                rows.append([rid, row])
            cols = ["_id", "n", "k", "s"] + (["m"] if with_m else []) + ["p"]
            tuples = ", ".join("(" + ", ".join(lit(x) for x in [rid] + [row[c] for c in cols[1:-1]] + [p]) + ")"
                               for (rid, row), p in zip(rows, pads))
            rec.update({"op": "ins", "tb": tb, "rows_in": rows, "a": ack})
            q = f"INSERT INTO {tb} ({', '.join(cols)}) VALUES {tuples}"
            for rid, row in rows:
                mine.append((tb, rid))
            status, _ = run_logged(log, conn, rec, q, ack)
            stats.add("ins", status)
        elif r < 0.70:
            h = rng.choice(HOT_ROWS)
            w = rng.random()
            k = rng.randrange(10)
            if w < 0.55:
                rec.update({"op": "upd", "id": h, "v": cid, "k": k, "a": ack})
                q = f"UPDATE {HOT} SET v = '{cid}', k = {k} WHERE _id = '{h}'"
            elif w < 0.75:
                rec.update({"op": "del", "id": h, "a": ack})
                q = f"DELETE FROM {HOT} WHERE _id = '{h}'"
            else:
                rec.update({"op": "hins", "id": h, "v": cid, "k": k, "a": ack})
                q = f"INSERT INTO {HOT} (_id, v, k) VALUES ('{h}', '{cid}', {k})"
            status, _ = run_logged(log, conn, rec, q, ack)
            stats.add(rec["op"], status)
        elif r < 0.9996:
            cons = "local" if r < 0.84 else "majority"
            w = rng.random()
            if w < 0.5 or not mine:
                h = rng.choice(HOT_ROWS)
                rec.update({"op": "rd", "tb": HOT, "id": h, "cons": cons})
                q = f"SELECT _id, v, k FROM {HOT} WHERE _id = '{h}'"
            elif w < 0.8:
                tb, rid = mine[rng.randrange(len(mine))]
                rec.update({"op": "rd", "tb": tb, "id": rid, "cons": cons})
                q = f"SELECT _id, n, k, s, m FROM {tb} WHERE _id = '{rid}'"
            else:
                tb = rng.choice(COLD)
                rec.update({"op": "cnt", "tb": tb, "cons": cons})
                q = f"SELECT COUNT(*) FROM {tb} WHERE k = {rng.randrange(40)}"
            status, _ = run_logged(log, conn, rec, q, None, cons)
            stats.add("read" if cons == "local" else "mread", status)
        else:
            tb, col = rng.choice(TOGGLED_INDEXES)
            what = "create" if rng.random() < 0.5 else "drop"
            rec.update({"op": "ddl", "tb": tb, "col": col, "what": what, "a": ack})
            q = (f"CREATE INDEX IF NOT EXISTS ON {tb} ({col})" if what == "create"
                 else f"DROP INDEX IF EXISTS ON {tb} ({col})")
            status, _ = run_logged(log, conn, rec, q, ack)
            stats.add("ddl", status)
        if time.time() - last_flush > 1:
            log.flush()
            last_flush = time.time()
        time.sleep(min(0.2, rng.expovariate(1 / (0.005 if SCENARIO else 0.025))))
    log.flush()
    conn.close()


def node_metrics(ctx):
    out = []
    for i in NODES:
        c = Conn(i, ctx, timeout=2)
        s, b = c.call("GET", "/metrics")
        c.close()
        if s != 200 or not isinstance(b, str):
            out.append(f"n{i}=-")
            continue
        m = re.search(r"^serengeti_data_bytes (\S+)", b, re.M)
        w = re.search(r"^serengeti_wal_bytes (\S+)", b, re.M)
        k = re.search(r"^serengeti_conflicts (\S+)", b, re.M)
        out.append(f"n{i}={float(m.group(1)) / 2**20:.0f}/{float(w.group(1)) / 2**20:.0f}MiB/{int(float(k.group(1)))}c"
                   if m and w and k else f"n{i}=?")
    return " ".join(out)


def cmd_work():
    clients = int(ENV.get("SOAK_CLIENTS", str(2 * N)))
    os.makedirs(os.path.join(OUT, "ops"), exist_ok=True)
    stats = Stats()
    stop_at = T0 + SECS
    while time.time() < T0:
        time.sleep(0.05)
    event(f"work: {clients} clients over {N} nodes for {SECS:.0f} s")
    ts = [threading.Thread(target=client, args=(ci, (ci - 1) % N + 1, stats, stop_at), daemon=True)
          for ci in range(1, clients + 1)]
    for t in ts:
        t.start()
    ctx = tls_context()
    nxt = T0 + 60
    while any(t.is_alive() for t in ts):
        if time.time() >= nxt:
            event(f"minute {int((nxt - T0) // 60)}: {fmt_counts(stats.take())} | data/log MiB, conflicts "
                  f"{node_metrics(ctx)}")
            nxt += 60
        time.sleep(0.2)
    rest = stats.take()
    if rest:
        event(f"last {time.time() - (nxt - 60):.0f} s: {fmt_counts(rest)}")
    event(f"work: total {fmt_counts(stats.total)}")
    with open(os.path.join(OUT, "work.json"), "w") as f:
        json.dump({f"{op} {cls}": v for (op, cls), v in sorted(stats.total.items(), key=str)}, f, indent=1)


# ---------------------------------------------------------------------------------------------------------------
# Convergence


def roots(conn):
    s, b = conn.call("GET", "/internal/v2/roots", headers={"X-Serengeti-Cluster-Key": ENV.get("SOAK_KEY", "")})
    return b.get("dbs") if s == 200 and isinstance(b, dict) else None


def cmd_converge():
    limit = float(ENV.get("SOAK_CONVERGE_SECS", "300"))
    ctx = tls_context()
    conns = {i: Conn(i, ctx, timeout=10) for i in NODES}
    start = time.time()
    last = None
    while time.time() - start < limit:
        ready, sts, rs = {}, {}, {}
        for i, c in conns.items():
            s, _ = c.call("GET", "/ready")
            ready[i] = s
            st = status_of(i, c)
            cl = (st or {}).get("cluster") or st or {}
            sts[i] = (cl.get("caught_up"), cl.get("reachable"))
            rs[i] = json.dumps(roots(c), sort_keys=True)
        ok = (all(v == 200 for v in ready.values()) and all(v == (True, N) for v in sts.values())
              and len(set(rs.values())) == 1 and "null" not in rs.values())
        last = {"ready": ready, "status": {i: list(v) for i, v in sts.items()},
                "roots_equal": len(set(rs.values())) == 1}
        if ok:
            took = time.time() - start
            event(f"converge: every node ready, caught up, reaching {N} nodes, same table roots after {took:.1f} s")
            with open(os.path.join(OUT, "converge.json"), "w") as f:
                json.dump({"converged": True, "seconds": round(took, 1)}, f)
            return 0
        time.sleep(2)
    event(f"converge: NOT converged after {limit:.0f} s: {last}")
    with open(os.path.join(OUT, "converge.json"), "w") as f:
        json.dump({"converged": False, "seconds": limit, "last": last}, f)
    return 1


# ---------------------------------------------------------------------------------------------------------------
# The checker


def kind(v):
    if isinstance(v, bool):
        return "b"
    if isinstance(v, (int, float)):
        return "n"
    if isinstance(v, str):
        return "s"
    if v is None:
        return "z"
    return "o"


def same(a, b):
    """Equal values of the same kind (True is not 1; 3 and 3.0 are equal numbers)."""
    if kind(a) != kind(b):
        return False
    if isinstance(a, dict):
        return a.keys() == b.keys() and all(same(a[k], b[k]) for k in a)
    if isinstance(a, list):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return a == b


def same_row(a, b):
    a = {k: v for k, v in a.items() if v is not None}
    b = {k: v for k, v in b.items() if v is not None}
    return same(a, b)


def model_match(val, op, probe):
    """docs/QUERY.md comparison rules: a comparison with null, or between kinds, is never true."""
    if val is None or probe is None or kind(val) != kind(probe) or kind(val) not in "bns":
        return False
    return {"=": val == probe, "<": val < probe, "<=": val <= probe, ">": val > probe, ">=": val >= probe}[op]


class W:
    """One write a client sent, with what the client knows about its outcome."""
    __slots__ = ("rec", "t0", "t1", "answered", "applied", "acked", "ack", "node", "op", "id", "v", "k")

    def __init__(self, rec):
        self.rec = rec
        self.op = rec["op"]
        self.t0, self.t1 = rec["t0"], rec["t1"]
        s = rec["s"]
        self.answered = isinstance(s, int)
        self.node = rec["n"]
        self.ack = rec.get("a", "majority")
        self.id, self.v, self.k = rec.get("id"), rec.get("v"), rec.get("k")
        err = rec.get("e") or ""
        if s == 200:
            aff = rec.get("aff")
            self.applied = "no" if (self.op in ("upd", "del", "ddl") and aff == 0) else "yes"
        elif s == 400 or s == "conn" or (s == 503 and "may still reach" not in str(err)):
            self.applied = "no"
        else:
            self.applied = "maybe"  # 503 after its own log, no answer, or an unexpected status
        self.acked = s == 200 and self.applied == "yes"

    def latest(self):
        """The latest wall-clock millisecond its version can carry: it was stamped before the answer came."""
        return int(self.t1) if self.answered else float("inf")

    def earliest(self):
        return int(self.t0)

    def name(self):
        r = self.rec
        return (f"c{r['c']}#{r.get('i')} {r['op']} via n{r['n']} ack={self.ack} status={r['s']} "
                f"t={elapsed(self.t0)}..{elapsed(self.t1)}" + (f" v={self.v}" if self.v else ""))


class Prefix:
    """Writes sorted by answer time, with the running maximum of their start time, so 'the newest-started write
    answered before time t' is one bisect."""

    def __init__(self, ws):
        ws = sorted(ws, key=lambda w: w.t1)
        self.t1 = [w.t1 for w in ws]
        self.best = []
        b = None
        for w in ws:
            if b is None or w.t0 > b.t0:
                b = w
            self.best.append(b)

    def newest_before(self, t):
        k = bisect.bisect_left(self.t1, t)
        return self.best[k - 1] if k else None


class Deletes:
    """Candidate deletes of a row (applied or maybe), sorted by start time, with the running maximum of latest()."""

    def __init__(self, ws):
        ws = sorted(ws, key=lambda w: w.t0)
        self.t0 = [w.t0 for w in ws]
        self.best = []
        b = None
        for w in ws:
            if b is None or w.latest() > b.latest():
                b = w
            self.best.append(b)

    def latest_started_before(self, t):
        k = bisect.bisect_left(self.t0, t)
        return self.best[k - 1] if k else None


class Checker:
    def __init__(self, live=True, node_ids=None):
        self.results = {}
        self.live = live
        if live:
            self.ctx = tls_context()
            self.conns = {i: Conn(i, self.ctx, timeout=60) for i in NODES}
            self.details = open(os.path.join(OUT, "check-details.txt"), "w")
            with open(os.path.join(OUT, "nodes.json")) as f:
                node_ids = {int(k): v for k, v in json.load(f)["ids"].items()}
        self.node_ids = node_ids
        self.id_node = {v: k for k, v in node_ids.items()}

    def result(self, num, ok, text, problems=()):
        self.results[num] = (ok, f"{'ok  ' if ok else 'FAIL'} {num} {text}", list(problems))

    def q(self, i, text):
        for attempt in range(5):
            s, b = self.conns[i].query(text)
            if s == 200:
                return b
            time.sleep(1 + attempt)
        raise RuntimeError(f"n{i}: {text}: {s} {b}")

    @staticmethod
    def load_ops():
        recs = []
        for p in sorted(glob.glob(os.path.join(OUT, "ops", "*.jsonl")) + glob.glob(os.path.join(OUT, "ops", "*.jsonl.gz"))):
            with (gzip.open(p, "rt") if p.endswith(".gz") else open(p)) as f:
                for line in f:
                    line = line.strip()
                    if line:
                        try:
                            recs.append(json.loads(line))
                        except ValueError:
                            pass  # a line cut short by the end of the run
        return recs

    def dump(self, i):
        d = {"tables": {}, "conflicts": {}, "indexes": {}}
        d["databases"] = sorted(r[0] for r in self.q(i, "SHOW DATABASES")["rows"])
        d["table_list"] = {db: sorted(r[0] for r in self.q(i, f"SHOW TABLES IN {db}")["rows"]) for db in d["databases"]}
        for t in DB_TABLES:
            rows, last = {}, None
            while True:
                where = "" if last is None else f" WHERE _id > {lit(last)}"
                b = self.q(i, f"SELECT * FROM {t}{where} ORDER BY _id LIMIT 10000")
                cols = b["columns"]
                for r in b["rows"]:
                    row = dict(zip(cols, r))
                    rid = row.pop("_id")
                    p = row.get("p")
                    if isinstance(p, str) and p == pad(rid, len(p)):
                        row["p"] = pad_token(len(p))  # intact; anything else is kept as it is and will not match
                    rows[rid] = {k: v for k, v in row.items() if v is not None}
                    last = rid
                if len(b["rows"]) < 10000:
                    break
            d["tables"][t] = rows
            b = self.q(i, f"SHOW CONFLICTS IN {t}")
            d["conflicts"][t] = {"rows": b["rows"], "truncated": b.get("truncated")}
            d["indexes"][t] = sorted(r[0] for r in self.q(i, f"SHOW INDEXES IN {t}")["rows"])
        return d

    def main(self):
        recs = self.load_ops()
        dumps = {i: self.dump(i) for i in NODES}
        with open(os.path.join(OUT, "dump-n1.json"), "w") as f:
            json.dump(dumps[1], f)
        print(f"check: {len(recs)} operations logged; rows per table on n1: "
              + ", ".join(f"{t} {len(dumps[1]['tables'][t])}" for t in DB_TABLES), flush=True)
        counts = self.evaluate(recs, dumps)
        self.check_exits(recs)
        self.check_liveness(recs)
        self.check_indexes(dumps)
        failed = 0
        for num in sorted(self.results):
            ok, line, problems = self.results[num]
            failed += not ok
            print(line, flush=True)
            for p in problems[:3]:
                print("       " + p[:400], flush=True)
            self.details.write(line + "\n")
            for p in problems[:2000]:
                self.details.write("     " + p + "\n")
        self.details.close()
        summary = {"passed": len(self.results) - failed, "failed": failed,
                   "lines": [self.results[n][1] for n in sorted(self.results)],
                   "writes": {f"{op} {ack} {ap}": v for (op, ack, ap), v in sorted(counts.items())}}
        with open(os.path.join(OUT, "check.json"), "w") as f:
            json.dump(summary, f, indent=1)
        print(f"check: {summary['passed']} passed, {summary['failed']} failed; details in check-details.txt")
        return 1 if failed else 0

    def evaluate(self, recs, dumps):
        """Checks 1, 2, 3, 4, 7, 8 and 9: they need only the clients' records and each node's data."""
        ws = [W(r) for r in recs if r.get("op") in ("ins", "upd", "del", "hins", "ddl")]
        reads = [r for r in recs if r.get("op") == "rd" and r.get("s") == 200]
        ins = [w for w in ws if w.op == "ins"]
        hot = [w for w in ws if w.op in ("upd", "del", "hins")]
        ddl = [w for w in ws if w.op == "ddl"]
        by_row = collections.defaultdict(list)
        for w in hot:
            by_row[w.id].append(w)
        vmap = {w.v: w for w in hot if w.v is not None}

        # --- 1, 2, 3, 7: final state against the acknowledgements
        p1, p2, p3, p7 = [], [], [], []
        expected_rows = {}  # (table, id) -> (row, W)
        for w in ins:
            for rid, row in w.rec["rows_in"]:
                expected_rows[(w.rec["tb"], rid)] = (row, w)
        for i in NODES:
            tables = dumps[i]["tables"]
            for w in ins:
                present = [rid in tables[w.rec["tb"]] for rid, _ in w.rec["rows_in"]]
                for (rid, row), here in zip(w.rec["rows_in"], present):
                    if here and not same_row(tables[w.rec["tb"]][rid], row):
                        p7.append(f"n{i}: {w.rec['tb']} {rid} is {tables[w.rec['tb']][rid]}, was written as {row}"
                                  f" by {w.name()}")
                if w.acked and not all(present):
                    (p1 if w.ack == "majority" else p3).append(
                        f"n{i}: missing {[rid for (rid, _), h in zip(w.rec['rows_in'], present) if not h]} "
                        f"in {w.rec['tb']}, acknowledged: {w.name()}")
                elif w.applied == "no" and any(present):
                    p7.append(f"n{i}: {w.rec['tb']} has rows of a refused insert: {w.name()}")
                elif w.applied == "maybe" and any(present) and not all(present):
                    p7.append(f"n{i}: {w.rec['tb']} has only some rows of one insert ({sum(present)} of "
                              f"{len(present)}): {w.name()}")
            for t in COLD:
                for rid in tables[t]:
                    if (t, rid) not in expected_rows:
                        p7.append(f"n{i}: {t} has a row nobody inserted: {rid} {tables[t][rid]}")
            for h in HOT_ROWS:
                self.explain_final(i, h, tables[HOT].get(h), by_row[h], vmap, p1, p2, p3, p7)
            for rid in tables[HOT]:
                if rid not in HOT_ROWS:
                    p7.append(f"n{i}: {HOT} has a row nobody inserted: {rid}")
            self.ddl_final(i, dumps[i]["indexes"], ddl, p1, p3, p7)
        n_maj = sum(1 for w in ws if w.acked and w.ack == "majority")
        n_loc = sum(1 for w in ws if w.acked and w.ack == "local")
        n_del = sum(1 for w in hot if w.acked and w.op == "del")
        self.result(1, not p1, f"every write acknowledged by a majority is on every node with its value or a newer "
                    f"one ({n_maj} writes: {sum(1 for w in ins if w.acked and w.ack == 'majority')} inserts, "
                    f"{sum(1 for w in hot if w.acked and w.ack == 'majority' and w.op != 'del')} hot-row writes, "
                    f"{sum(1 for w in ddl if w.acked and w.ack == 'majority')} index changes)", p1)
        self.result(2, not p2, f"every acknowledged delete stays deleted unless a newer write recreated the row "
                    f"({n_del} deletes)", p2)
        self.result(3, not p3, f"every write acknowledged locally is on every node or superseded by a newer one "
                    f"({n_loc} writes)", p3)

        # --- 4: the nodes agree
        p4 = []
        base = dumps[1]
        for i in NODES[1:]:
            d = dumps[i]
            for key in ("databases", "table_list", "indexes"):
                if d[key] != base[key]:
                    p4.append(f"n{i} {key} {d[key]} != n1 {base[key]}")
            for t in DB_TABLES:
                a, b = base["tables"][t], d["tables"][t]
                diff = sorted(k for k in set(a) & set(b) if not same(a[k], b[k]))
                if a.keys() != b.keys() or diff:
                    only1 = sorted(set(a) - set(b))
                    only_i = sorted(set(b) - set(a))
                    p4.append(f"{t}: n1 has {len(a)} rows, n{i} {len(b)}; only on n1 {only1[:5]}, only on n{i} "
                              f"{only_i[:5]}, different {[(k, a[k], b[k]) for k in diff[:3]]}")
                if json.dumps(base["conflicts"][t], sort_keys=True) != json.dumps(d["conflicts"][t], sort_keys=True):
                    p4.append(f"{t}: SHOW CONFLICTS differs: n1 {base['conflicts'][t]} n{i} {d['conflicts'][t]}"[:1500])
        nrows = sum(len(base["tables"][t]) for t in DB_TABLES)
        ncf = sum(len(base["conflicts"][t]["rows"]) for t in DB_TABLES)
        self.result(4, not p4, f"every node returns the same rows ({nrows}), SHOW CONFLICTS ({ncf} versions) and "
                    f"SHOW INDEXES ({sum(len(v) for v in base['indexes'].values())} indexes) for every table", p4)

        # --- 7: nothing appears that was not written
        self.result(7, not p7, "every row and value held comes from a write that was sent and not refused; inserts "
                    "of several rows are all there or all missing", p7)

        # --- 8: reads
        p8, nchk = self.check_reads(reads, by_row, vmap, expected_rows)
        self.result(8, not p8, f"reads saw every earlier acknowledged write they must see ({nchk} reads: majority "
                    f"reads every majority write acknowledged before they began, any read the writes its own node "
                    f"acknowledged before it began)", p8)

        # --- 9: conflicts
        p9 = self.check_conflicts(dumps, by_row, vmap)
        self.result(9, not p9, "SHOW CONFLICTS lists only real writes, the current one first and equal to the row, "
                    "and no version that a later write on the same node had seen; insert-only tables list none", p9)
        return collections.Counter((w.op, w.ack, w.applied) for w in ws)

    def check_exits(self, recs):
        """Check 5: no unexpected exit, no 5xx but 503, ready at the end."""
        p5 = []
        try:
            with open(os.path.join(OUT, "nemesis.json")) as f:
                nem = json.load(f)
        except OSError:
            nem = {"unexpected_exits": [], "errors": ["nemesis.json missing"]}
            p5.append("nemesis.json is missing: the nemesis did not finish")
        for u in nem["unexpected_exits"]:
            p5.append(f"n{u['node']} exited at {u['at']} without the nemesis: status {u['status']} exit {u['exit']} "
                      f"OOM {u['oom']}")
        # A 5xx answered while its node was stopping (in the 3 s after the nemesis sent it a docker stop or
        # restart) is counted apart, so that finding 1 of docs/SOAK.md does not hide any other.
        stops = collections.defaultdict(list)
        for at, action, node, *_ in nem.get("history", []):
            if action in ("stop", "restart"):
                stops[node].append(at)
        bad_status = collections.Counter()
        while_stopping = collections.Counter()
        other = collections.Counter()
        for r in recs:
            s = r.get("s")
            if isinstance(s, int) and s >= 500 and s != 503:
                bad_status[s] += 1
                stopping = any(at <= r["t1"] <= at + 3000 for at in stops[r["n"]])
                if stopping:
                    while_stopping[(s, f"{r.get('op')} {r.get('a') or r.get('cons')}")] += 1
                    continue
                other[s] += 1
                if other[s] <= 5:
                    p5.append(f"client got {s} (node not stopping): c{r.get('c')}#{r.get('i')} via n{r['n']} at "
                              f"{elapsed(r['t0'])} {r.get('q', '')[:120]} -> {r.get('e')}")
        for (s, kind_), v in sorted(while_stopping.items()):
            p5.append(f"client got {s} {v} times for {kind_} from a node that was stopping (finding 1)")
        log_5xx = collections.Counter()
        log_lines = collections.Counter()
        for i in NODES:
            path = os.path.join(OUT, "nodes", f"n{i}.log")
            if not os.path.exists(path):
                p5.append(f"no log for n{i}")
                continue
            with open(path, errors="replace") as f:
                for line in f:
                    m = re.search(r" route=(\S+) status=(5\d\d) ", line)
                    if m and m.group(2) != "503":
                        log_5xx[(i, m.group(1), m.group(2))] += 1
                    if "java.lang.OutOfMemoryError" in line:
                        p5.append(f"n{i} log: {line.strip()[:300]}")
                    for word in ("SEVERE", "WARNING", "Exception"):
                        if word in line:
                            log_lines[(i, word)] += 1
        for (i, route, code), v in sorted(log_5xx.items()):
            p5.append(f"n{i} answered {code} on {route} {v} times")
        for i in NODES:
            s, _ = self.conns[i].call("GET", "/ready")
            if s != 200:
                p5.append(f"n{i} /ready is {s}")
        self.result(5, not p5, f"no node exited except those the nemesis killed ({len(nem['unexpected_exits'])} "
                    f"other exits); no 5xx but 503 (clients {sum(bad_status.values())}, "
                    f"{sum(while_stopping.values())} of them from a stopping node; node logs "
                    f"{sum(log_5xx.values())}); /ready on every node", p5)
        with open(os.path.join(OUT, "log-summary.txt"), "w") as f:
            for (i, word), v in sorted(log_lines.items()):
                f.write(f"n{i} {word} {v}\n")

    def check_liveness(self, recs):
        """Check 10: a node that was running and not paused answered its clients."""
        try:
            with open(os.path.join(OUT, "nemesis.json")) as f:
                nem = json.load(f)
        except OSError:
            nem = {}
        p10 = silences(recs, nem.get("history", []))
        for s in nem.get("stalls", []):
            p10.append(f"n{s['node']} did not answer /health from {elapsed(s['from'])} to "
                       f"{'the end of the run' if s['to'] is None else elapsed(s['to'])} (a thread dump is in its log)")
        self.result(10, not p10, "every node that was running and not paused answered its clients: no 30 s in which "
                    "every request to it went unanswered", p10)

    def check_indexes(self, dumps):
        """Check 6: an indexed comparison, the same comparison as a scan, and the rows held all agree."""
        p6, nq = [], 0
        for i in NODES:
            for t in DB_TABLES:
                rows = dumps[i]["tables"][t]
                for col in dumps[i]["indexes"][t]:
                    vals = sorted({json.dumps(r[col]) for r in rows.values() if col in r})
                    pick = [json.loads(vals[k]) for k in range(0, len(vals), max(1, len(vals) // 6))][:7]
                    for v in pick + [5, 5.5, "m", "zzz", True]:
                        for op in ("=", "<", "<=", ">", ">="):
                            idx = self.q(i, f"SELECT COUNT(*) FROM {t} WHERE {col} {op} {lit(v)}")["rows"][0][0]
                            # OR is never answered through an index (docs/QUERY.md), so this scans.
                            scan = self.q(i, f"SELECT COUNT(*) FROM {t} WHERE {col} {op} {lit(v)} OR {col} {op} "
                                             f"{lit(v)}")["rows"][0][0]
                            model = sum(1 for r in rows.values() if model_match(r.get(col), op, v))
                            nq += 2
                            if not (idx == scan == model):
                                p6.append(f"n{i} {t} WHERE {col} {op} {lit(v)}: index {idx}, scan {scan}, "
                                          f"rows held {model}")
                            if op == "=" and idx == scan and 0 < idx <= 10000:
                                a = self.q(i, f"SELECT _id FROM {t} WHERE {col} = {lit(v)} LIMIT 10000")["rows"]
                                b = self.q(i, f"SELECT _id FROM {t} WHERE {col} = {lit(v)} OR {col} = {lit(v)} "
                                              f"LIMIT 10000")["rows"]
                                nq += 2
                                if a != b:
                                    p6.append(f"n{i} {t} WHERE {col} = {lit(v)}: the index and the scan return "
                                              f"different ids")
        self.result(6, not p6, f"index lookups return the same rows as scans on every node ({nq} queries)", p6)

    def explain_final(self, i, h, row, writes, vmap, p1, p2, p3, p7):
        """The row's final state on node i against every acknowledged write to it."""
        maj = [w for w in writes if w.acked and w.ack == "majority" and w.op != "del"]
        loc = [w for w in writes if w.acked and w.ack == "local" and w.op != "del"]
        dels = [w for w in writes if w.acked and w.op == "del"]
        cands = [w for w in writes if w.op == "del" and w.applied != "no"]
        if row is not None:
            x = vmap.get(row.get("v"))
            if x is None or x.id != h:
                p7.append(f"n{i}: {HOT} {h} = {row}, which no client wrote")
                return
            if x.applied == "no":
                p7.append(f"n{i}: {HOT} {h} = {row}, written by a refused write: {x.name()}")
            if not same_row(row, {"v": x.v, "k": x.k}):
                p7.append(f"n{i}: {HOT} {h} = {row}, but {x.name()} wrote k={x.k}")
            for group, out in ((maj, p1), (loc, p3), (dels, p2)):
                newest = max(group, key=lambda w: w.t0, default=None)
                if newest is not None and newest is not x and x.latest() < newest.earliest():
                    out.append(f"n{i}: {HOT} {h} holds {x.name()}, answered before {newest.name()} was sent")
        else:
            best = max(cands, key=lambda w: w.latest(), default=None)
            for group, out in ((maj, p1), (loc, p3)):
                newest = max(group, key=lambda w: w.t0, default=None)
                if newest is not None and (best is None or best.latest() < newest.earliest()):
                    out.append(f"n{i}: {HOT} {h} is deleted, but no delete can be newer than {newest.name()}")

    def ddl_final(self, i, indexes, ddl, p1, p3, p7):
        """The indexes on node i against every acknowledged CREATE INDEX and DROP INDEX."""
        fixed = set(FIXED_INDEXES)
        for t, col in TOGGLED_INDEXES + FIXED_INDEXES:
            has = col in indexes[t]
            ops = [w for w in ddl if w.rec["tb"] == t and w.rec["col"] == col]
            if (t, col) in fixed:
                if not has:
                    p1.append(f"n{i}: the index on {t} ({col}) made at setup is gone")
                continue
            want = "create" if has else "drop"
            # Toggled indexes start out absent, which is older than any write.
            cands = [w for w in ops if w.rec["what"] == want and w.applied != "no"]
            if has and not cands:
                p7.append(f"n{i}: {t} has an index on {col} that no client created")
                continue
            best = max((w.latest() for w in cands), default=None)
            for ack, out in (("majority", p1), ("local", p3)):
                other = [w for w in ops if w.acked and w.ack == ack and w.rec["what"] != want]
                newest = max(other, key=lambda w: w.t0, default=None)
                if newest is not None and (best is None or best < newest.earliest()):
                    out.append(f"n{i}: {t} ({col}) {'has' if has else 'has no'} index, but nothing can be newer "
                               f"than {newest.name()}")

    def check_reads(self, reads, by_row, vmap, expected_rows):
        problems, n = [], 0
        # Per hot row: writes acknowledged by a majority, and by each node, sorted by answer time.
        maj_pre, node_pre, del_cands = {}, {}, {}
        for h in HOT_ROWS:
            ws = by_row[h]
            acked = [w for w in ws if w.acked]
            maj_pre[h] = (Prefix([w for w in acked if w.ack == "majority"]),
                          Prefix([w for w in acked if w.ack == "majority" and w.op != "del"]))
            for i in NODES:
                mine = [w for w in acked if w.node == i]
                node_pre[(h, i)] = (Prefix(mine), Prefix([w for w in mine if w.op != "del"]))
            del_cands[h] = Deletes([w for w in ws if w.op == "del" and w.applied != "no"])
        for r in reads:
            node, t0, t1 = r["n"], r["t0"], r["t1"]
            if r["tb"] == HOT:
                h = r["id"]
                n += 1
                pres = [node_pre[(h, node)]] + ([maj_pre[h]] if r["cons"] == "majority" else [])
                rows = r.get("rows") or []
                if rows:
                    _, v, k = rows[0]
                    x = vmap.get(v)
                    if x is None or x.id != h:
                        problems.append(f"read c{r['c']}#{r['i']} of {h} on n{node} saw {rows[0]}, which no client "
                                        f"wrote")
                        continue
                    if x.t0 >= t1:
                        problems.append(f"read c{r['c']}#{r['i']} of {h} on n{node} at {elapsed(t0)}..{elapsed(t1)} "
                                        f"saw {x.name()}, sent after the read was answered")
                    if x.applied == "no":
                        problems.append(f"read c{r['c']}#{r['i']} of {h} saw a refused write: {x.name()}")
                    for all_pre, _ in pres:
                        w = all_pre.newest_before(t0)
                        if w is not None and w is not x and x.latest() < w.earliest():
                            problems.append(f"{r['cons']} read c{r['c']}#{r['i']} of {h} on n{node} at "
                                            f"{elapsed(t0)} saw {x.name()}, older than {w.name()}")
                else:
                    for _, put_pre in pres:
                        w = put_pre.newest_before(t0)
                        if w is None:
                            continue
                        d = del_cands[h].latest_started_before(t1)
                        if d is None or d.latest() < w.earliest():
                            problems.append(f"{r['cons']} read c{r['c']}#{r['i']} of {h} on n{node} at {elapsed(t0)} "
                                            f"found no row, but no delete can be newer than {w.name()}")
            else:
                key = (r["tb"], r["id"])
                if key not in expected_rows:
                    continue
                row, w = expected_rows[key]
                n += 1
                rows = r.get("rows") or []
                must = w.acked and w.t1 < t0 and (w.node == node or (w.ack == "majority" and r["cons"] == "majority"))
                if rows:
                    got = dict(zip(r.get("cols") or [], rows[0]))
                    rid = got.pop("_id", None)
                    if w.applied == "no":
                        problems.append(f"read c{r['c']}#{r['i']} on n{node} saw {key}, from a refused insert "
                                        f"{w.name()}")
                    elif w.t0 >= t1:
                        problems.append(f"read c{r['c']}#{r['i']} on n{node} saw {key} before it was sent")
                    elif rid != r["id"] or not same_row(got, {c: v for c, v in row.items() if c in got}):
                        problems.append(f"read c{r['c']}#{r['i']} on n{node} asked for {key}, got {rows[0]}, "
                                        f"written as {row}")
                elif must:
                    problems.append(f"{r['cons']} read c{r['c']}#{r['i']} on n{node} at {elapsed(t0)} missed {key}, "
                                    f"acknowledged earlier: {w.name()}")
        return problems, n

    def check_conflicts(self, dumps, by_row, vmap):
        problems = []
        d = dumps[1]
        for t in COLD:
            if d["conflicts"][t]["rows"]:
                problems.append(f"{t} is insert-only but lists conflicts: {d['conflicts'][t]['rows'][:3]}")
        if d["conflicts"][HOT]["truncated"]:
            problems.append("SHOW CONFLICTS IN soak.hot was truncated")
        versions = collections.defaultdict(list)
        for cid, current, deleted, node, at, row in d["conflicts"][HOT]["rows"]:
            versions[cid].append((current, deleted, node, at, row))
        for h, vs in versions.items():
            if not vs[0][0] or any(v[0] for v in vs[1:]):
                problems.append(f"{h}: the current version is not the only one marked current and listed first: {vs}")
            cur = vs[0]
            held = d["tables"][HOT].get(h)
            if cur[1]:
                if held is not None:
                    problems.append(f"{h}: the current version is a delete, but the row reads {held}")
            elif held is None or not same_row(held, cur[4] or {}):
                problems.append(f"{h}: the current version {cur[4]} is not what the row reads: {held}")
            ws = []
            for current, deleted, node, at, row in vs:
                writer = self.id_node.get(node)
                if deleted:
                    fits = [w for w in by_row[h] if w.op == "del" and w.applied != "no" and w.node == writer
                            and w.earliest() <= at <= w.latest()]
                    if not fits:
                        problems.append(f"{h}: a delete by n{writer} at {elapsed(at)} matches no delete sent")
                    elif len(fits) == 1:  # with more than one candidate, which delete it is stays open
                        ws.append(fits[0])
                    continue
                x = vmap.get((row or {}).get("v"))
                if x is None or x.id != h:
                    problems.append(f"{h}: version {row} matches no write")
                    continue
                if x.applied == "no":
                    problems.append(f"{h}: version {row} is from a refused write {x.name()}")
                if x.node != writer:
                    problems.append(f"{h}: version {row} is listed as written by n{writer}, but was sent to n{x.node}")
                if not (x.earliest() <= at <= x.latest()):
                    problems.append(f"{h}: version {row} is stamped {elapsed(at)}, outside {x.name()}")
                ws.append(x)
            for a, b in itertools.combinations(ws, 2):
                for x, y in ((a, b), (b, a)):
                    # x was in its node's log before that node answered 200 or "may still reach" 503.
                    held = x.rec["s"] in (200, 503) and x.applied != "no"
                    if x.node == y.node and held and x.latest() < y.earliest():
                        problems.append(f"{h}: lists both {x.name()} and {y.name()}, though n{y.node} held the first "
                                        f"when it made the second")
        return problems


def silences(recs, history, limit_ms=30_000, grace_ms=20_000):
    """Stretches longer than limit_ms in which every request to a node got no answer (no connection, or a
    timeout) while the nemesis had it running and not paused. Each start, unpause and restart is given
    grace_ms more, since a node needs a few seconds to open its data."""
    down = collections.defaultdict(list)  # node -> [(from, to)] in unix ms when it is not expected to answer
    since = {}
    for h in history:
        at, action, node = h[0], h[1], h[2]
        took = h[3] * 1000 if len(h) > 3 else 0
        if action in ("kill", "stop", "pause"):
            since.setdefault(node, at)
        elif action in ("start", "unpause"):
            down[node].append((since.pop(node, at), at + took + grace_ms))
        elif action == "restart":
            down[node].append((at, at + took + grace_ms))
    for node, at in since.items():
        down[node].append((at, float("inf")))
    by_node = collections.defaultdict(list)
    for r in recs:
        if isinstance(r.get("n"), int) and isinstance(r.get("t0"), (int, float)) and r.get("c") != "s":
            by_node[r["n"]].append(r)
    out = []
    for node, rs in sorted(by_node.items()):
        rs.sort(key=lambda r: r["t0"])
        last = rs[-1]["t1"]
        start = None
        for r in rs + [None]:
            silent = r is not None and r["s"] in ("conn", "timeout")
            if silent and start is None:
                start = r["t0"]
            elif not silent and start is not None:
                end = last if r is None else r["t0"]
                # The parts of [start, end] that no down period covers.
                segs = [(start, end)]
                for a, b in down[node]:
                    segs = [p for s, e in segs for p in ((s, min(e, a)), (max(s, b), e)) if p[1] > p[0]]
                for s, e in segs:
                    if e - s > limit_ms:
                        out.append(f"n{node} answered no request from {elapsed(s)} to {elapsed(e)} "
                                   f"({time.strftime('%H:%M:%S', time.gmtime(s / 1000))} to "
                                   f"{time.strftime('%H:%M:%S', time.gmtime(e / 1000))} UTC) "
                                   f"({(e - s) / 1000:.0f} s), running and not paused")
                start = None
    return out


def cmd_liveness():
    """Check 10 alone, on the files of a finished run: soak.py liveness (with SOAK_OUT set)."""
    recs = Checker.load_ops()
    with open(os.path.join(OUT, "nemesis.json")) as f:
        problems = silences(recs, json.load(f).get("history", []))
    for p in problems:
        print(p)
    print(f"liveness: {len(problems)} silences")
    return 1 if problems else 0


def cmd_check():
    return Checker().main()


def cmd_selftest():
    """Runs the checker on a small made-up history, then on copies with one defect planted in each, and fails
    unless each defect fails exactly the checks it should. Needs no cluster."""
    import copy
    if N != 3:
        sys.exit("selftest: run it with SOAK_NODES=3")
    ids = {1: "A", 2: "B", 3: "C"}

    def rec(c, i, op, n, t0, s=200, **kw):
        r = {"c": c, "i": i, "op": op, "n": n, "t0": float(t0), "t1": float(t0 + 10), "s": s}
        r.update(kw)
        return r

    recs = [rec("s", k, "hins", 1, 1000 + k, id=h, v=f"init-{h}", k=0, a="majority", aff=1)
            for k, h in enumerate(HOT_ROWS)]
    recs += [rec("s", 20 + k, "ddl", 1, 1100 + k, tb=t, col=c, what="create", a="majority", aff=1)
             for k, (t, c) in enumerate(FIXED_INDEXES)]
    a1 = {"n": 1, "k": 3, "s": "s0", "m": 2}
    b1 = {"n": 1, "k": 4, "s": "o'k", "m": True}
    m0, m1 = {"n": 2, "k": 5, "s": "s1", "m": 2.5}, {"n": 2, "k": 6, "s": "s1", "m": None}
    recs += [
        rec(1, 1, "ins", 1, 2000, tb="soak.a", rows_in=[["c1-1", a1]], a="majority", aff=1),
        rec(2, 1, "ins", 2, 2100, tb="soak.b", rows_in=[["c2-1", b1]], a="local", aff=1),
        rec(3, 1, "ins", 3, 2200, s=503, e="1 of 3 nodes are reachable; writes need 2", tb="soak2.c",
            rows_in=[["c3-1", {"n": 1, "k": 1, "s": "s2"}]], a="majority"),
        rec(1, 2, "ins", 1, 2300, s="timeout", e="timed out", tb="soak.a", rows_in=[["c1-2.0", m0], ["c1-2.1", m1]],
            a="majority"),
        rec(3, 9, "upd", 3, 2900, id="h0", v="c3-9", k=5, a="local", aff=1),
        rec(1, 3, "upd", 1, 3000, id="h0", v="c1-3", k=1, a="majority", aff=1),
        rec(2, 2, "del", 2, 3100, id="h0", a="majority", aff=1),
        rec(3, 2, "upd", 3, 3200, id="h0", v="c3-2", k=2, a="local", aff=1),
        rec(3, 3, "rd", 3, 2500, tb="soak.a", id="c1-1", cons="majority", cols=["_id", "n", "k", "s", "m"],
            rows=[["c1-1", 1, 3, "s0", 2]]),
        rec(3, 4, "rd", 3, 3300, tb=HOT, id="h0", cons="local", cols=["_id", "v", "k"], rows=[["h0", "c3-2", 2]]),
    ]
    hot = {h: {"v": f"init-{h}", "k": 0} for h in HOT_ROWS}
    hot["h0"] = {"v": "c3-2", "k": 2}
    one = {"databases": ["soak", "soak2"], "table_list": {"soak": ["a", "b", "hot"], "soak2": ["c"]},
           "tables": {"soak.a": {"c1-1": a1, "c1-2.0": m0, "c1-2.1": {k: v for k, v in m1.items() if v is not None}},
                      "soak.b": {"c2-1": b1}, "soak2.c": {}, HOT: hot},
           "conflicts": {t: {"rows": [], "truncated": False} for t in DB_TABLES},
           "indexes": {"soak.a": ["k"], "soak.b": [], "soak2.c": ["k"], HOT: ["k"]}}

    def everywhere(f):
        def g(r, d):
            for i in NODES:
                f(d[i])
        return g

    def read_of(seq, rows):
        def g(r, d):
            next(x for x in r if x["c"] == 3 and x["i"] == seq)["rows"] = rows
        return g

    def conflict(d):
        d["conflicts"][HOT]["rows"] = [["h0", True, False, "C", 3205, {"v": "c3-2", "k": 2}],
                                       ["h0", False, False, "C", 2905, {"v": "c3-9", "k": 5}]]

    cases = [
        ("the made-up history as it is", set(), lambda r, d: None),
        ("a majority insert missing on n2", {1, 4}, lambda r, d: d[2]["tables"]["soak.a"].pop("c1-1")),
        ("a local insert missing everywhere", {3}, everywhere(lambda x: x["tables"]["soak.b"].pop("c2-1"))),
        ("h0 back at a value written before an acknowledged delete", {2, 3},
         everywhere(lambda x: x["tables"][HOT].update(h0={"v": "c1-3", "k": 1}))),
        ("h0 deleted, though writes were acknowledged after every delete", {1, 3},
         lambda r, d: (r.append(rec(1, 7, "upd", 1, 3500, id="h0", v="c1-7", k=7, a="majority", aff=1)),
                       everywhere(lambda x: x["tables"][HOT].pop("h0"))(r, d))),
        ("a refused insert present", {7},
         everywhere(lambda x: x["tables"]["soak2.c"].update({"c3-1": {"n": 1, "k": 1, "s": "s2"}}))),
        ("half of a two-row insert present", {7}, everywhere(lambda x: x["tables"]["soak.a"].pop("c1-2.1"))),
        ("a hot-row value nobody wrote", {7}, everywhere(lambda x: x["tables"][HOT].update(h1={"v": "zz", "k": 0}))),
        ("a row value changed on one node", {4, 7}, lambda r, d: d[3]["tables"]["soak.b"]["c2-1"].update(k=99)),
        ("a majority read missing an earlier majority insert", {8}, read_of(3, [])),
        ("a local read on n3 older than a write n3 acknowledged", {8}, read_of(4, [["h0", "c1-3", 1]])),
        ("a conflict between two writes that n3 made one after the other", {9}, everywhere(conflict)),
        ("a fixed index gone on n3", {1, 4}, lambda r, d: d[3]["indexes"]["soak.a"].remove("k")),
    ]
    bad = 0
    for name, want, mutate in cases:
        r, d = copy.deepcopy(recs), {i: copy.deepcopy(one) for i in NODES}
        mutate(r, d)
        c = Checker(live=False, node_ids=ids)
        c.evaluate(r, d)
        got = {num for num, (ok, _, _) in c.results.items() if not ok}
        ok = got == want
        bad += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {name}: fails {sorted(got) or 'nothing'}"
              + ("" if ok else f", expected {sorted(want) or 'nothing'}"))
        if not ok:
            for num in sorted(got | want):
                for p in c.results[num][2][:3]:
                    print("       " + p[:300])
    print(f"selftest: {len(cases) - bad} of {len(cases)} as expected")
    return 1 if bad else 0


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    fn = {"plan": cmd_plan, "nemesis": lambda: Nemesis().main(), "setup": cmd_setup, "work": cmd_work,
          "converge": cmd_converge, "check": cmd_check, "selftest": cmd_selftest,
          "liveness": cmd_liveness}.get(cmd)
    if fn is None:
        sys.exit(__doc__)
    sys.exit(fn() or 0)


if __name__ == "__main__":
    main()
