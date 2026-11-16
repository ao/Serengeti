"""HTTP conformance: methods, content types, body limits, transfer encodings, HTTP/1.0 and keep-alive,
the Origin check, and the JSON reader behind every body (docs/API.md)."""

import json
import threading
import time

from common import Client, Conn, bad, build, check, check_error, chunked, expect, is_json_error, lit, once, section

MAX_BODY = 1 << 20

# Every documented route, its method, and whether it takes a JSON body.
ROUTES = [
    ("GET", "/health"), ("GET", "/ready"), ("POST", "/v1/setup"), ("POST", "/v1/query"), ("POST", "/post"),
    ("GET", "/v1/status"), ("POST", "/v1/password"), ("GET", "/v1/backup"), ("GET", "/metrics"), ("GET", "/"),
]


def padded_query(size, query="SHOW DATABASES"):
    """A JSON body of exactly `size` bytes: the query plus an ignored "pad" field."""
    base = json.dumps({"query": query, "pad": ""}, separators=(",", ":"))
    pad = size - len(base.encode())
    assert pad >= 0
    return json.dumps({"query": query, "pad": "x" * pad}, separators=(",", ":")).encode()


def run(host):
    c = Client(host)
    c.ok("CREATE DATABASE IF NOT EXISTS h")
    c.ok("CREATE TABLE IF NOT EXISTS h.t")

    section("wrong method on every route: 405 and a JSON error")
    for method, path in ROUTES:
        others = ["POST", "PUT", "DELETE", "PATCH"] if method == "GET" else ["GET", "PUT", "DELETE"]
        for m in others:
            r = once(host, m, path, body=b"{}" if m != "GET" else None)
            check_error(f"{m} {path} is 405", r, 405)
            check(f"{m} {path} names the allowed method in Allow", (r.header("allow") or "") == method,
                  f"Allow: {r.header('allow')!r}")
    r = once(host, "OPTIONS", "/v1/query")
    check_error("OPTIONS /v1/query is 405", r, 405)
    r = once(host, "BREW", "/v1/query", body=b"{}")
    check_error("an unknown method is 405", r, 405)
    r = once(host, "HEAD", "/health", head=True)
    check("HEAD /health gets a 2xx or 405 answer", r.status in (200, 405), repr(r))

    section("unknown routes: 404")
    for m, p in [("GET", "/nope"), ("GET", "/v1/query/"), ("GET", "/v1/Query"), ("GET", "/v1//status"),
                 ("POST", "/nope"), ("POST", "/v1/query/"), ("PUT", "/nope"), ("GET", "/v1/%71uery")]:
        r = once(host, m, p, body=b"{}" if m != "GET" else None)
        check_error(f"{m} {p} is 404", r, 404)
    expect("a query string does not change the route", 200, once(host, "GET", "/health?x=1&y", auth=False).status)
    r = once(host, "GET", "/v1/status")
    check("every answer has X-Content-Type-Options: nosniff", r.header("x-content-type-options") == "nosniff",
          repr(r.headers))

    section("Content-Type: 415 unless application/json")
    q = b'{"query": "SHOW DATABASES"}'
    for label, ct in [("missing", None), ("text/plain", "text/plain"), ("form", "application/x-www-form-urlencoded"),
                      ("multipart", "multipart/form-data; boundary=x"), ("application/jsonx", "application/jsonx"),
                      ("empty", ""), ("json5", "application/json5"), ("two types", "application/json, text/plain")]:
        hdrs = [("Content-Type", ct)] if ct is not None else []
        r = once(host, "POST", "/v1/query", body=q, headers=hdrs, ctype=None)
        check_error(f"Content-Type {label} is 415", r, 415)
    for label, ct in [("with charset", "application/json; charset=utf-8"), ("upper case", "APPLICATION/JSON"),
                      ("space before parameter", "application/json ;charset=UTF-8")]:
        r = once(host, "POST", "/v1/query", body=q, headers=[("Content-Type", ct)], ctype=None)
        expect(f"Content-Type {label} is accepted", 200, r.status)
    r = once(host, "POST", "/v1/password", body=b'{"password": "will not be set 1"}',
             headers=[("Content-Type", "text/plain")], ctype=None)
    check_error("password change with text/plain is 415", r, 415)
    expect("and the password did not change", 200, c.q("SHOW DATABASES")[0])

    section("body size: 1 MiB is the limit")
    exact = padded_query(MAX_BODY)
    expect("the test body is exactly 1 MiB", MAX_BODY, len(exact))
    expect("a body of exactly 1 MiB is accepted", 200, once(host, "POST", "/v1/query", exact).status)
    over = padded_query(MAX_BODY + 1)
    check_error("1 MiB and one byte is 413", once(host, "POST", "/v1/query", over), 413)
    ch = build("POST", "/v1/query", host, chunked(exact, 65536), headers=[("Transfer-Encoding", "chunked")],
               length=False)
    cn = Conn(host)
    cn.send(ch)
    expect("a chunked body of exactly 1 MiB is accepted", 200, cn.response().status)
    cn.close()
    cn = Conn(host)
    cn.send(build("POST", "/v1/query", host, chunked(over, 65536), headers=[("Transfer-Encoding", "chunked")],
                  length=False))
    check_error("a chunked body of 1 MiB and one byte is 413", cn.response(), 413)
    cn.close()
    # With Expect: 100-continue the client waits for the go-ahead (or a final answer) before the body.
    cn = Conn(host)
    cn.send(build("POST", "/v1/query", host, None, headers=[("Content-Length", str(MAX_BODY + 1)),
                                                            ("Content-Type", "application/json"),
                                                            ("Expect", "100-continue")]))
    r = cn.response()
    if r.status == 100:
        cn.send(over)
        r = cn.response()
    check_error("Expect: 100-continue with 1 MiB + 1 is 413", r, 413)
    cn.close()
    # Far over the limit: either a 413 or a cut connection, never a 200 or a hang.
    big = padded_query(9 * MAX_BODY)
    res = {}

    def sender(conn):
        try:
            conn.send(build("POST", "/v1/query", host, big))
        except OSError as e:
            res["send"] = repr(e)

    cn = Conn(host, timeout=30)
    t = threading.Thread(target=sender, args=(cn,))
    t.start()
    try:
        r = cn.response()
        res["status"] = r.status
    except (OSError, EOFError, ValueError) as e:
        res["status"] = f"cut ({type(e).__name__})"
    t.join(30)
    cn.close()
    check("a 9 MiB body gets 413 or a closed connection", res.get("status") == 413 or "cut" in str(res.get("status")),
          repr(res))

    section("chunked transfer encoding")
    body = b'{"query": "SELECT COUNT(*) FROM h.t"}'
    for label, data in [("one chunk", chunked(body, 1 << 16)), ("one byte per chunk", chunked(body, 1)),
                        ("chunk extensions", chunked(body, 7, ";name=value")),
                        ("upper-case hex sizes", b"%X\r\n" % len(body) + body + b"\r\n0\r\n\r\n"),
                        ("a trailer", chunked(body, 10)[:-2] + b"X-Trailer: 1\r\n\r\n")]:
        cn = Conn(host)
        cn.send(build("POST", "/v1/query", host, data, headers=[("Transfer-Encoding", "chunked")], length=False))
        r = cn.response()
        expect(f"chunked body, {label}", 200, r.status)
        cn.close()
    for label, data in [("a bad chunk size", b"zz\r\n" + body + b"\r\n0\r\n\r\n"),
                        ("a chunk longer than its size", b"3\r\n" + body + b"\r\n0\r\n\r\n")]:
        cn = Conn(host, timeout=10)
        cn.send(build("POST", "/v1/query", host, data, headers=[("Transfer-Encoding", "chunked")], length=False))
        try:
            r = cn.response()
            check(f"chunked body with {label} gets a 4xx", 400 <= r.status < 500, repr(r))
        except (OSError, EOFError, ValueError) as e:
            bad(f"chunked body with {label} gets a 4xx", f"no answer: {type(e).__name__} {e}")
        cn.close()
    r = once(host, "POST", "/v1/query", chunked(body), headers=[("Transfer-Encoding", "chunked"),
                                                               ("Content-Length", "5")], length=False)
    check("chunked with a Content-Length too is not a 5xx", r.status < 500, repr(r))

    section("empty and malformed bodies")
    check_error("an empty body is 400", once(host, "POST", "/v1/query", b""), 400)
    r = once(host, "POST", "/v1/query", None, headers=[("Content-Type", "application/json")])
    check_error("no body at all is 400", r, 400)
    r = once(host, "POST", "/v1/query", b"{}", headers=[("Content-Length", "abc")])
    check("a non-numeric Content-Length is 400", r.status == 400, repr(r))
    r = once(host, "POST", "/v1/query", b"{}", headers=[("Content-Length", "-1")])
    check("a negative Content-Length is 400", r.status == 400, repr(r))

    section("invalid UTF-8 in a body: 400, never stored")
    for label, raw in [("a 0xff byte", b"\xff"), ("a truncated sequence", b"\xe2\x82"),
                       ("an overlong slash", b"\xc0\xaf"), ("an encoded surrogate", b"\xed\xa0\x80"),
                       ("a code point above U+10FFFF", b"\xf4\x90\x80\x80")]:
        q = b'{"query": "INSERT INTO h.t (_id, v) VALUES (\'utf8\', \'' + raw + b'\')"}'
        r = once(host, "POST", "/v1/query", q)
        check_error(f"{label} is 400", r, 400)
    expect("no invalid row was stored", 0, c.count("h.t", "_id = 'utf8'"))

    section("HTTP/1.0 and keep-alive")
    cn = Conn(host)
    cn.send(b"GET /health HTTP/1.0\r\n\r\n")
    r = cn.response()
    expect("HTTP/1.0 without Host gets 200", 200, r.status)
    check("and the connection is closed after it", cn.closed_by_peer(), "connection stayed open")
    cn.close()
    cn = Conn(host)
    good = True
    for i in range(3):
        cn.send(build("GET", "/v1/status", host, version="HTTP/1.0", headers=[("Connection", "keep-alive")]))
        r = cn.response()
        good &= r.status == 200 and r.header("content-length") is not None
    check("HTTP/1.0 with Connection: keep-alive serves three requests on one connection", good, repr(r))
    cn.close()
    cn = Conn(host)
    cn.send(build("POST", "/v1/query", host, b'{"query": "SHOW DATABASES"}', version="HTTP/1.0"))
    r = cn.response()
    expect("an HTTP/1.0 query is 200", 200, r.status)
    cn.close()
    cn = Conn(host, timeout=30)
    cn.send(build("GET", "/v1/backup", host, version="HTTP/1.0"))
    r = cn.response()
    lines = r.body.decode("utf-8", "replace").strip().split("\n")
    check("a backup over HTTP/1.0 is complete (it ends with the trailer)", r.status == 200 and '"end":true' in lines[-1],
          f"{r.status} last line {lines[-1][:200]!r}")
    cn.close()

    # Kept-alive HTTP/1.1: every kind of answer, one connection, and it stays usable after each.
    cn = Conn(host)
    sequence = [
        (build("POST", "/v1/query", host, b'{"query": "SHOW DATABASES"}'), 200),
        (build("POST", "/v1/query", host, b'{"query": "SELEC"}'), 400),
        (build("POST", "/v1/query", host, b'{"query": "SHOW DATABASES"}', auth=False), 401),
        (build("GET", "/nope", host), 404),
        (build("PUT", "/v1/query", host, b"{}"), 405),
        (build("POST", "/v1/query", host, over), 413),
        (build("POST", "/v1/query", host, b"{}", ctype="text/plain"), 415),
        (build("POST", "/v1/query", host, b"[1,2"), 400),
        (build("GET", "/v1/status", host), 200),
    ]
    got = []
    try:
        for req, want in sequence * 3:
            cn.send(req)
            got.append(cn.response().status)
    except (OSError, EOFError, ValueError) as e:
        got.append(f"{type(e).__name__}")
    expect("one kept-alive connection answers 200/400/401/404/405/413/415 in turn, three times",
           [w for _, w in sequence] * 3, got)
    cn.close()
    cn = Conn(host)
    cn.send(b"".join(build("POST", "/v1/query", host, ('{"query": "SELECT COUNT(*) FROM h.t", "n": %d}' % i).encode())
                     for i in range(10)))
    statuses = []
    try:
        for _ in range(10):
            statuses.append(cn.response().status)
    except (OSError, EOFError, ValueError) as e:
        statuses.append(type(e).__name__)
    expect("ten pipelined requests get ten answers in order", [200] * 10, statuses)
    cn.close()
    cn = Conn(host)
    cn.send(build("GET", "/health", host, headers=[("Connection", "close")], auth=False))
    expect("Connection: close is answered", 200, cn.response().status)
    check("and then closed", cn.closed_by_peer(), "connection stayed open")
    cn.close()
    cn = Conn(host)
    cn.send(build("POST", "/v1/query", host, None, headers=[("Expect", "100-continue"),
                                                            ("Content-Length", "27"),
                                                            ("Content-Type", "application/json")]))
    r = cn.response()
    if r.status == 100:
        cn.send(b'{"query": "SHOW DATABASES"}')
        r = cn.response()
    expect("Expect: 100-continue then the body is 200", 200, r.status)
    cn.close()

    section("malformed requests get an answer and the node keeps serving")
    for label, data in [("a garbage request line", b"GARBAGE\r\n\r\n"),
                        ("a header without a colon", b"GET /health HTTP/1.1\r\nHost: x\r\nbroken\r\n\r\n"),
                        ("HTTP/2.0", b"GET /health HTTP/2.0\r\nHost: x\r\n\r\n"),
                        ("a 64 KiB header", b"GET /health HTTP/1.1\r\nHost: x\r\nX-Big: " + b"a" * 65536 + b"\r\n\r\n"),
                        ("500 headers", b"GET /health HTTP/1.1\r\nHost: x\r\n" +
                         b"".join(b"X-%d: 1\r\n" % i for i in range(500)) + b"\r\n")]:
        cn = Conn(host, timeout=10)
        try:
            cn.send(data)
            r = cn.response()
            ans = r.status
        except (OSError, EOFError, ValueError) as e:
            ans = f"closed ({type(e).__name__})"
        cn.close()
        check(f"{label}: an answer or a close, not a hang or a 500", ans != 500, f"got {ans}")
    expect("the node still answers after them", 200, once(host, "GET", "/health", auth=False).status)
    # A client that sends half a request and goes away must not take a worker for good.
    socks = []
    for _ in range(40):
        cn = Conn(host)
        cn.send(b"POST /v1/query HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{")
        socks.append(cn)
    for cn in socks:
        cn.close()
    time.sleep(1)
    ok_all = all(c.q("SHOW DATABASES")[0] == 200 for _ in range(20))
    check("after 40 clients left mid-body, queries are answered", ok_all, "")
    # Clients that stop mid-body and stay connected, more of them than the node has request threads (16).
    stalled = []
    for _ in range(20):
        cn = Conn(host)
        cn.send(b"POST /v1/query HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{")
        stalled.append(cn)
    time.sleep(1)
    t0 = time.time()
    probe = Client(host, timeout=10)
    st = probe.q("SHOW DATABASES")[0]
    probe.close()
    check("with 20 clients stalled mid-body, another client's query is answered",
          st == 200, f"status {st} after {time.time() - t0:.1f} s")
    for cn in stalled:
        cn.close()
    expect("and once they leave, the node answers", 200, Client(host).q("SHOW DATABASES")[0])

    section("Origin")
    for label, origin, want in [("another site", "http://evil.example", 403), ("null", "null", 403),
                                ("another port", f"http://{host}:1986", 403),
                                ("a malformed one", "http://[", 403)]:
        r = once(host, "POST", "/v1/query", b'{"query": "SHOW DATABASES"}', headers=[("Origin", origin)])
        check_error(f"POST with Origin {label} is {want}", r, want)
    r = once(host, "POST", "/v1/query", b'{"query": "SHOW DATABASES"}', headers=[("Origin", f"http://{host}:{1985}")])
    expect("POST with the node's own Origin is 200", 200, r.status)
    r = once(host, "GET", "/v1/status", headers=[("Origin", "http://evil.example")])
    expect("GET with a foreign Origin is allowed (reads are not refused)", 200, r.status)
    c.close()


def run_json(host):
    c = Client(host)
    c.ok("CREATE DATABASE IF NOT EXISTS j")
    c.ok("CREATE TABLE IF NOT EXISTS j.t")

    def post(raw):
        return once(host, "POST", "/v1/query", raw)

    section("JSON: duplicate keys, nesting, numbers, literals")
    r = post(b'{"query": "SHOW DATABASES", "query": "DROP DATABASE j"}')
    check_error("duplicate keys are 400", r, 400)
    expect("and neither statement ran", 200, c.q("SHOW TABLES IN j")[0])
    check_error("duplicate keys written differently are 400",
                post(b'{"query": "SHOW DATABASES", "q\\u0075ery": "SHOW DATABASES"}'), 400)
    expect("an escaped key is the same key", 200, post(b'{"q\\u0075ery": "SHOW DATABASES"}').status)
    # The reader allows 128 levels; the body object is the first, so a field may hold arrays 127 deep.
    for depth, want in [(127, 200), (128, 400), (100000, 400)]:
        nested = b"[" * depth + b"]" * depth
        r = post(b'{"query": "SHOW DATABASES", "x": ' + nested + b"}")
        if want == 200:
            expect(f"arrays nested {depth} deep in an ignored field are accepted", 200, r.status)
        else:
            check_error(f"arrays nested {depth} deep are 400", r, 400)
    objs = b'{"a":' * 150000 + b"1" + b"}" * 150000
    check_error("objects nested 150,000 deep are 400", post(b'{"query": "SHOW DATABASES", "x": ' + objs + b"}"), 400)
    expect("the node still answers after deep nesting", 200, c.q("SHOW DATABASES")[0])
    for label, num, want in [("1e308", b"1e308", 200), ("1e309", b"1e309", 400), ("-1e309", b"-1e309", 400),
                             ("1e-400", b"1e-400", 200), ("-0", b"-0", 200), ("a 40-digit integer", b"9" * 40, 200),
                             ("a 5000-digit integer", b"9" * 5000, 400), ("0.1e1", b"0.1e1", 200),
                             ("leading zero", b"01", 400), ("a trailing dot", b"1.", 400), ("a leading dot", b".5", 400),
                             ("a plus sign", b"+1", 400), ("hex", b"0x10", 400), ("1e", b"1e", 400),
                             ("NaN", b"NaN", 400), ("Infinity", b"Infinity", 400), ("-Infinity", b"-Infinity", 400),
                             ("nan", b"nan", 400), ("True", b"True", 400), ("undefined", b"undefined", 400)]:
        r = post(b'{"query": "SHOW DATABASES", "x": ' + num + b"}")
        if want == 200:
            expect(f"number {label} is accepted", 200, r.status)
        elif label == "a 5000-digit integer":
            check(f"number {label} is 200 or 400, quickly", r.status in (200, 400), repr(r))
        else:
            check_error(f"{label} is 400", r, 400)

    section("JSON: BOM, top level, unknown fields, types")
    r = post(b'\xef\xbb\xbf{"query": "SHOW DATABASES"}')
    check("a byte order mark is ignored or refused with 400", r.status == 200 or is_json_error(r, 400)[0], repr(r))
    for label, raw in [("an array", b'["SHOW DATABASES"]'), ("a string", b'"SHOW DATABASES"'), ("a number", b"1"),
                       ("null", b"null"), ("true", b"true"), ("whitespace", b"  \r\n\t "), ("two objects", b"{}{}"),
                       ("trailing data", b'{"query": "SHOW DATABASES"} x'),
                       ("a trailing comma", b'{"query": "SHOW DATABASES",}'),
                       ("single quotes", b"{'query': 'SHOW DATABASES'}"), ("an unquoted key", b'{query: "SHOW DATABASES"}'),
                       ("a comment", b'{"query": "SHOW DATABASES" /* c */}'),
                       ("a raw tab in a string", b'{"query": "SHOW\tDATABASES"}'),
                       ("a raw newline in a string", b'{"query": "SHOW\nDATABASES"}'),
                       ("a form feed as whitespace", b'\x0c{"query": "SHOW DATABASES"}'),
                       ("a no-break space as whitespace", b'\xc2\xa0{"query": "SHOW DATABASES"}'),
                       ("an unterminated string", b'{"query": "SHOW DATABASES}')]:
        check_error(f"a body that is {label} is 400", post(raw), 400)
    expect("CR LF and tabs between tokens are fine", 200, post(b'\r\n\t{\r\n\t"query"\t:\r\n"SHOW DATABASES"\r\n}\r\n').status)
    expect("unknown fields are ignored", 200, post(b'{"query": "SHOW DATABASES", "foo": [1, {"b": null}], "x": true}').status)
    for label, raw in [("query as a number", b'{"query": 5}'), ("query null", b'{"query": null}'),
                       ("query an array", b'{"query": ["SHOW DATABASES"]}'), ("no query", b'{"q": "SHOW DATABASES"}'),
                       ("consistency 5", b'{"query": "SHOW DATABASES", "consistency": 5}'),
                       ("consistency LOCAL", b'{"query": "SHOW DATABASES", "consistency": "LOCAL"}'),
                       ("ack true", b'{"query": "SHOW DATABASES", "ack": true}'),
                       ("ack all", b'{"query": "SHOW DATABASES", "ack": "all"}')]:
        check_error(f"{label} is 400", post(raw), 400)
    expect("consistency majority on a single node is 200", 200,
           post(b'{"query": "SHOW DATABASES", "consistency": "majority"}').status)
    expect("ack local on a single node is 200", 200, post(b'{"query": "SHOW DATABASES", "ack": "local"}').status)

    section("JSON: escapes and surrogates survive a write and a read")
    values = {
        "esc-quote": ('\\"', '"'), "esc-backslash": ("\\\\", "\\"), "esc-slash": ("\\/", "/"),
        "esc-controls": ("\\b\\f\\n\\r\\t", "\b\f\n\r\t"), "esc-nul": ("a\\u0000b", "a\x00b"),
        "esc-unicode": ("\\u00e9\\u00E9\\u4e2d", "\u00e9\u00e9\u4e2d"), "esc-separators": ("\\u2028\\u2029", "\u2028\u2029"),
        "esc-pair": ("\\ud83d\\ude00", "\U0001F600"), "raw-emoji": ("\U0001F600\U0001F1FF\U0001F1E6", "\U0001F600\U0001F1FF\U0001F1E6"),
        "esc-html": ("<script>&amp;", "<script>&amp;"), "esc-del": ("\x7f", "\x7f"),
        "raw-combining": ("e\u0301", "e\u0301"), "raw-rtl": ("\u05e9\u05dc\u05d5\u05dd", "\u05e9\u05dc\u05d5\u05dd"),
        "esc-bom": ("\\ufeff", "\ufeff"), "esc-max": ("\\udbff\\udfff", "\U0010FFFF"),
    }
    for key, (encoded, want) in values.items():
        body = ('{"query": "INSERT INTO j.t (_id, v) VALUES (\'%s\', \'%s\')"}' % (key, encoded)).encode("utf-8")
        r = post(body)
        if not expect(f"{key}: insert", 200, r.status):
            continue
        got = c.rows(f"SELECT v FROM j.t WHERE _id = '{key}'")
        expect(f"{key}: reads back the same", [[want]], got)
        expect(f"{key}: and matches by equality", 1, c.count("j.t", f"_id = '{key}' AND v = {lit(want)}"))
    for n, (label, esc) in enumerate([("a lone high surrogate", "\\ud800"), ("a lone low surrogate", "\\udfff"),
                                      ("a high surrogate then a letter", "\\ud83dA"),
                                      ("two high surrogates", "\\ud83d\\ud83d"),
                                      ("a low then a high surrogate", "\\ude00\\ud83d")]):
        body = ('{"query": "INSERT INTO j.t (_id, v) VALUES (\'lone%d\', \'%s\')"}' % (n, esc)).encode()
        check_error(f"{label} is 400 (not valid Unicode text)", post(body), 400)
    # Two ids that differ only in a lone surrogate: if they were stored, both would be written to the log as
    # "?" and one row would vanish at the next start.
    body = b'{"query": "INSERT INTO j.t (_id) VALUES (\'\\ud800\'), (\'\\udbff\')"}'
    check_error("ids made of lone surrogates are 400", post(body), 400)
    expect("no row with a lone surrogate was stored", 0, c.count("j.t", "_id >= 'lone' AND _id < 'lonf'")
           + c.count("j.t", "_id = '?'") + c.count("j.t", "_id > '\ud7ff' AND _id < '\ue000'"))
    for label, esc in [("\\x41", "\\x41"), ("\\'", "\\'"), ("\\U0041", "\\U00000041"), ("a short \\u", "\\u12"),
                       ("\\u with non-hex", "\\u00g0"), ("\\u with full-width digits", "\\u\uff10\uff10\uff14\uff11"),
                       ("\\u with Arabic-Indic digits", "\\u\u0660\u0660\u0664\u0661"), ("a lone backslash", "\\")]:
        body = ('{"query": "SELECT * FROM j.t WHERE v = \'%s\'"}' % esc).encode("utf-8")
        check_error(f"escape {label} is 400", post(body), 400)
    c.close()


def verify_json(host):
    """After a restart: the values written by run_json are unchanged."""
    c = Client(host)
    section("JSON values after a restart")
    want = {"esc-quote": '"', "esc-backslash": "\\", "esc-slash": "/", "esc-controls": "\b\f\n\r\t",
            "esc-nul": "a\x00b", "esc-unicode": "\u00e9\u00e9\u4e2d", "esc-separators": "\u2028\u2029",
            "esc-pair": "\U0001F600", "raw-emoji": "\U0001F600\U0001F1FF\U0001F1E6", "esc-html": "<script>&amp;",
            "esc-del": "\x7f", "raw-combining": "e\u0301", "raw-rtl": "\u05e9\u05dc\u05d5\u05dd", "esc-bom": "\ufeff",
            "esc-max": "\U0010FFFF"}
    got = {r[0]: r[1] for r in c.rows("SELECT _id, v FROM j.t")}
    for k, v in want.items():
        expect(f"{k} is unchanged after the restart", v, got.get(k))
    expect("no other rows appeared", sorted(want), sorted(got))
    c.close()
