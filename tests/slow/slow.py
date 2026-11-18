#!/usr/bin/env python3
"""Slow clients and a probe for tests/slow/run.sh. Standard library only (python:3.12-alpine).

    slow.py seed   --host H                      tables for the probe and the large answer
    slow.py attack --host H --mode M --n N       N connections that hold the node, renewed when it drops them
    slow.py probe  --host H --seconds S          GET /health, GET /v1/status and a small POST /v1/query
                                                 on new connections; prints one JSON line of results

Modes: "headers" sends a request's headers one byte a second; "body" sends a POST's headers at once and its
body one byte a second; "noread" signs in, asks for a large answer and never reads it, and opens a new
connection after --hold seconds. Requests in the first two modes carry no credentials.
"""
import argparse
import asyncio
import base64
import http.client
import json
import socket
import ssl
import sys
import threading
import time

BIG_ROWS = 4000
SMALL_QUERY = "SELECT * FROM slow.big WHERE _id = 'r7'"
BIG_QUERY = "SELECT * FROM slow.big"


def auth(pw):
    return "Basic " + base64.b64encode(("admin:" + pw).encode()).decode()


def tls_context(args):
    if not args.ca:
        return None
    ctx = ssl.create_default_context(cafile=args.ca)
    return ctx


def connection(args, timeout):
    ctx = tls_context(args)
    if ctx:
        return http.client.HTTPSConnection(args.host, args.port, timeout=timeout, context=ctx)
    return http.client.HTTPConnection(args.host, args.port, timeout=timeout)


def call(args, method, path, body=None, timeout=30, creds=True):
    c = connection(args, timeout)
    headers = {}
    if creds:
        headers["Authorization"] = auth(args.password)
    if body is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(body).encode()
    try:
        c.request(method, path, body=body, headers=headers)
        r = c.getresponse()
        return r.status, r.read()
    finally:
        c.close()


def seed(args):
    for _ in range(120):
        try:
            if call(args, "GET", "/health", creds=False, timeout=2)[0] == 200:
                break
        except OSError:
            pass
        time.sleep(0.5)
    else:
        sys.exit("node did not answer /health")
    for q in ("CREATE DATABASE IF NOT EXISTS slow", "CREATE TABLE IF NOT EXISTS slow.big"):
        status, body = call(args, "POST", "/v1/query", {"query": q})
        if status != 200:
            sys.exit(f"{q}: {status} {body[:200]!r}")
    pad = "x" * 1000
    for start in range(0, BIG_ROWS, 200):
        tuples = ", ".join(f"('r{i}', {i}, '{pad}')" for i in range(start, start + 200))
        status, body = call(args, "POST", "/v1/query",
                            {"query": f"INSERT INTO slow.big (_id, n, pad) VALUES {tuples}"})
        if status != 200:
            sys.exit(f"insert: {status} {body[:200]!r}")
    status, body = call(args, "POST", "/v1/query", {"query": BIG_QUERY})
    print(json.dumps({"seeded": BIG_ROWS, "big_answer_bytes": len(body)}))


class Stats:
    def __init__(self):
        self.connects = 0
        self.connect_errors = 0
        self.dropped = 0
        self.answered = 0
        self.open = 0
        self.max_open = 0


async def attack_one(args, i, stats, request, stop_at):
    loop = asyncio.get_running_loop()
    ctx = tls_context(args)
    while loop.time() < stop_at:
        writer = None
        counted = False
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            if args.mode == "noread":
                # A small receive window, so the node's write blocks after a few kilobytes.
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
            sock.setblocking(False)
            addr = (await loop.getaddrinfo(args.host, args.port, type=socket.SOCK_STREAM))[0][4]
            await asyncio.wait_for(loop.sock_connect(sock, addr), 10)
            reader, writer = await asyncio.wait_for(asyncio.open_connection(
                sock=sock, ssl=ctx, server_hostname=args.host if ctx else None, limit=1024), 10)
            stats.connects += 1
            stats.open += 1
            counted = True
            stats.max_open = max(stats.max_open, stats.open)
            if args.mode == "noread":
                writer.write(request)
                await writer.drain()
                await asyncio.sleep(args.hold)
                continue
            # Anything the node sends (an error) or its close ends this connection; then a new one starts.
            heard = asyncio.ensure_future(reader.read(1))
            if args.mode == "headers":
                trickle = request
            else:
                writer.write(request)
                await writer.drain()
                trickle = b" " * 100000
            for k in range(len(trickle)):
                if heard.done() or loop.time() >= stop_at:
                    break
                writer.write(trickle[k:k + 1])
                await writer.drain()
                await asyncio.sleep(1)
            if heard.done():
                if heard.exception() is None and heard.result():
                    stats.answered += 1
                else:
                    stats.dropped += 1
            else:
                heard.cancel()
        except (OSError, asyncio.TimeoutError, ssl.SSLError):
            if counted:
                stats.dropped += 1
            else:
                stats.connect_errors += 1
                await asyncio.sleep(0.2)
        finally:
            if counted:
                stats.open -= 1
            if writer is not None:
                writer.transport.abort()


async def attack_main(args):
    loop = asyncio.get_running_loop()
    stop_at = loop.time() + args.seconds
    if args.mode == "headers":
        request = ("GET /v1/status HTTP/1.1\r\nHost: node\r\nX-Slow: " + "a" * 4000 + "\r\n\r\n").encode()
    elif args.mode == "body":
        request = (b"POST /v1/query HTTP/1.1\r\nHost: node\r\nContent-Type: application/json\r\n"
                   b"Content-Length: 100000\r\n\r\n")
    else:
        body = json.dumps({"query": BIG_QUERY}).encode()
        request = (f"POST /v1/query HTTP/1.1\r\nHost: node\r\nAuthorization: {auth(args.password)}\r\n"
                   f"Content-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n").encode() + body
    stats = Stats()

    async def report():
        while loop.time() < stop_at:
            await asyncio.sleep(5)
            print(json.dumps(vars(stats)), flush=True)

    tasks = []
    for i in range(args.n):
        tasks.append(asyncio.ensure_future(attack_one(args, i, stats, request, stop_at)))
        if i % 50 == 49:
            await asyncio.sleep(0.05)
    rep = asyncio.ensure_future(report())
    await asyncio.gather(*tasks, return_exceptions=True)
    rep.cancel()
    print(json.dumps(vars(stats)), flush=True)


def attack(args):
    asyncio.run(attack_main(args))


def percentile(sorted_values, p):
    if not sorted_values:
        return None
    k = min(len(sorted_values) - 1, max(0, int(round(p / 100.0 * len(sorted_values) + 0.5)) - 1))
    return sorted_values[k]


def probe(args):
    targets = [
        ("health", "GET", "/health", None, False),
        ("status", "GET", "/v1/status", None, True),
        ("query", "POST", "/v1/query", {"query": SMALL_QUERY}, True),
    ]
    results = {name: [] for name, *_ in targets}
    end = time.monotonic() + args.seconds

    def run(name, method, path, body, creds):
        while time.monotonic() < end:
            t0 = time.perf_counter()
            try:
                status, _ = call(args, method, path, body, timeout=args.timeout, creds=creds)
                outcome = str(status)
            except (OSError, http.client.HTTPException, ssl.SSLError) as e:
                outcome = type(e).__name__
            results[name].append((outcome, time.perf_counter() - t0))
            time.sleep(args.interval)

    threads = [threading.Thread(target=run, args=t) for t in targets]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    out = {}
    for name, rs in results.items():
        times = sorted(dt for _, dt in rs)
        outcomes = {}
        for o, _ in rs:
            outcomes[o] = outcomes.get(o, 0) + 1
        ok = outcomes.get("200", 0)
        out[name] = {
            "n": len(rs),
            "ok_pct": round(100.0 * ok / len(rs), 1) if rs else 0.0,
            "p50_ms": round(1000 * percentile(times, 50), 1) if rs else None,
            "p99_ms": round(1000 * percentile(times, 99), 1) if rs else None,
            "max_ms": round(1000 * times[-1], 1) if rs else None,
            "outcomes": outcomes,
        }
    print(json.dumps(out))


def row(args):
    r = json.load(sys.stdin)
    print(" | ".join("%-30s" % ("%5.1f %7.1f %7.1f" % (r[k]["ok_pct"], r[k]["p50_ms"], r[k]["p99_ms"]))
                     for k in ("health", "status", "query")))


def check(args):
    r = json.load(sys.stdin)
    print(" ".join(k for k in r if r[k]["ok_pct"] < args.min_ok or r[k]["p99_ms"] > args.max_p99))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("cmd", choices=["seed", "attack", "probe", "row", "check"])
    p.add_argument("--min-ok", type=float, default=99)
    p.add_argument("--max-p99", type=float, default=2000)
    p.add_argument("--host", default="node")
    p.add_argument("--port", type=int, default=1985)
    p.add_argument("--password", default="slow test password 1")
    p.add_argument("--ca", default="", help="CA file: speak TLS")
    p.add_argument("--mode", choices=["headers", "body", "noread"], default="headers")
    p.add_argument("--n", type=int, default=64)
    p.add_argument("--seconds", type=float, default=60)
    p.add_argument("--hold", type=float, default=30, help="noread: seconds before a connection is renewed")
    p.add_argument("--timeout", type=float, default=5, help="probe: seconds before a request counts as failed")
    p.add_argument("--interval", type=float, default=0.1, help="probe: pause between requests")
    args = p.parse_args()
    {"seed": seed, "attack": attack, "probe": probe, "row": row, "check": check}[args.cmd](args)


if __name__ == "__main__":
    main()
