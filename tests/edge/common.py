"""Shared pieces of the edge suite: a raw HTTP/1.x client, the query helper, check output and the
reference rules of docs/QUERY.md that results are compared with.

Standard library only; runs in the python:3.12-alpine client container, never on the host.
"""

import base64
import http.client
import json
import os
import socket
import sys
import time
from fractions import Fraction

PORT = 1985
PW = os.environ.get("PW", "")
STRICT = os.environ.get("EDGE_STRICT") == "1"

# Checks that fail because of a bug that is reported but not fixed yet. A known failure prints "xfail" and
# does not fail the run (unless EDGE_STRICT=1); a known failure that passes prints "XPASS" and fails the run,
# so the entry is removed once the bug is fixed.
KNOWN = {
    "with 20 clients stalled mid-body, another client's query is answered":
        "the HTTP server has 16 request threads and no read timeout, so clients that stop sending a body hold "
        "them all and every other request waits",
    "chunked body, a trailer":
        "the JDK HTTP server cannot read chunked trailer fields (RFC 9112 section 7.1.2), so the read fails; "
        "the node answers 400 'the request body could not be read'",
}


def out(line):
    print(line, flush=True)


def ok(name):
    if name in KNOWN:
        out(f"XPASS {name}: listed as a known bug ({KNOWN[name]}) but passed; remove it from KNOWN")
    else:
        out(f"ok   {name}")


def bad(name, why):
    why = short(str(why), 600)
    if name in KNOWN and not STRICT:
        out(f"xfail {name}: {why} [known: {KNOWN[name]}]")
    else:
        out(f"FAIL {name}: {why}")


def check(name, cond, why=""):
    if cond:
        ok(name)
    else:
        bad(name, why)
    return cond


def expect(name, want, got):
    return check(name, want == got, f"expected {short(repr(want))}, got {short(repr(got))}")


def short(s, n=300):
    s = str(s)
    return s if len(s) <= n else s[:n] + f"... ({len(s)} chars)"


def section(title):
    out(f"# {title}")


# --- raw HTTP --------------------------------------------------------------------------------------------


class Resp:
    def __init__(self, status, reason, version, headers, body):
        self.status = status
        self.reason = reason
        self.version = version
        self.headers = headers
        self.body = body

    def header(self, name):
        return self.headers.get(name.lower())

    def json(self):
        try:
            return json.loads(self.body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None

    def __repr__(self):
        return f"<{self.status} {short(self.body.decode('utf-8', 'replace'), 200)!r}>"


class Conn:
    """One TCP connection; requests are bytes the test wrote, responses are parsed by hand."""

    def __init__(self, host, timeout=20):
        self.host = host
        self.sock = socket.create_connection((host, PORT), timeout=timeout)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.file = self.sock.makefile("rb")

    def send(self, data):
        self.sock.sendall(data)

    def response(self, head=False):
        line = self.file.readline(65537)
        if not line:
            raise EOFError("connection closed before a status line")
        parts = line.decode("latin-1").rstrip("\r\n").split(" ", 2)
        if len(parts) < 2 or not parts[0].startswith("HTTP/"):
            raise ValueError(f"not a status line: {line!r}")
        version, status = parts[0], int(parts[1])
        reason = parts[2] if len(parts) > 2 else ""
        headers = {}
        while True:
            h = self.file.readline(65537)
            if h in (b"\r\n", b"\n", b""):
                break
            k, _, v = h.decode("latin-1").partition(":")
            headers[k.strip().lower()] = v.strip()
        if head or status in (204, 304) or 100 <= status < 200:
            body = b""
        elif "chunked" in headers.get("transfer-encoding", "").lower():
            body = self._chunked()
        elif "content-length" in headers:
            body = self._exact(int(headers["content-length"]))
        else:
            body = self.file.read()
        return Resp(status, reason, version, headers, body)

    def _exact(self, n):
        data = self.file.read(n)
        if len(data) != n:
            raise EOFError(f"body ended after {len(data)} of {n} bytes")
        return data

    def _chunked(self):
        body = bytearray()
        while True:
            size = int(self.file.readline().split(b";")[0].strip(), 16)
            if size == 0:
                while self.file.readline() not in (b"\r\n", b"\n", b""):
                    pass
                return bytes(body)
            body += self._exact(size)
            self.file.readline()

    def closed_by_peer(self, wait=3.0):
        """True if the server closed the connection (EOF or reset) within `wait` seconds."""
        self.sock.settimeout(wait)
        try:
            return self.sock.recv(1) == b""
        except socket.timeout:
            return False
        except OSError:
            return True

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def auth_header(user="admin", pw=None):
    pw = PW if pw is None else pw
    return "Basic " + base64.b64encode(f"{user}:{pw}".encode("utf-8")).decode("ascii")


def build(method, path, host, body=None, headers=None, version="HTTP/1.1", auth=True,
          ctype="application/json", length=True):
    """A request as bytes. `body` is bytes or str; headers is a list of (name, value) or raw header lines."""
    lines = [f"{method} {path} {version}"]
    names = set()
    for h in headers or []:
        if isinstance(h, tuple):
            names.add(h[0].lower())
            lines.append(f"{h[0]}: {h[1]}")
        else:
            lines.append(h)
    if "host" not in names and version != "HTTP/1.0":
        lines.append(f"Host: {host}:{PORT}")
    if auth and "authorization" not in names:
        lines.append(f"Authorization: {auth_header()}")
    if isinstance(body, str):
        body = body.encode("utf-8")
    if body is not None:
        if ctype and "content-type" not in names:
            lines.append(f"Content-Type: {ctype}")
        if length and "content-length" not in names and "transfer-encoding" not in names:
            lines.append(f"Content-Length: {len(body)}")
    head = ("\r\n".join(lines) + "\r\n\r\n").encode("latin-1")
    return head + (body or b"")


def once(host, method, path, body=None, timeout=20, head=False, **kw):
    """Sends one request on a new connection and returns the response."""
    c = Conn(host, timeout)
    try:
        c.send(build(method, path, host, body, **kw))
        return c.response(head=head)
    finally:
        c.close()


def chunked(data, size=8192, ext=""):
    if isinstance(data, str):
        data = data.encode("utf-8")
    out_ = bytearray()
    for i in range(0, len(data), size):
        part = data[i:i + size]
        out_ += f"{len(part):x}{ext}\r\n".encode() + part + b"\r\n"
    return bytes(out_ + b"0\r\n\r\n")


def is_json_error(r, status):
    """The documented error shape: the status, a JSON content type and {"error": "non-empty message"}."""
    if r.status != status:
        return False, f"status {r.status}, wanted {status}: {r!r}"
    ct = (r.header("content-type") or "").lower()
    if not ct.startswith("application/json"):
        return False, f"Content-Type {ct!r}: {r!r}"
    j = r.json()
    if not isinstance(j, dict) or not isinstance(j.get("error"), str) or not j["error"]:
        return False, f"body is not {{\"error\": \"...\"}}: {r!r}"
    if "Exception" in j["error"] or "\tat " in j["error"] or "at com." in j["error"]:
        return False, f"message looks like a stack trace: {r!r}"
    return True, ""


def check_error(name, r, status):
    good, why = is_json_error(r, status)
    return check(name, good, why)


# --- the query API, on kept-alive connections ---------------------------------------------------------


class Client:
    """POST /v1/query on one kept-alive connection, reconnecting after any failure."""

    def __init__(self, host, pw=None, timeout=30):
        self.host = host
        self.timeout = timeout
        self.auth = auth_header(pw=pw)
        self.conn = None

    def _conn(self):
        if self.conn is None:
            self.conn = http.client.HTTPConnection(self.host, PORT, timeout=self.timeout)
        return self.conn

    def post(self, path, payload, raw=False):
        """Returns (status, parsed JSON or None, raw body). Status 0 means no answer (the error is the body)."""
        body = payload if raw else json.dumps(payload).encode("utf-8")
        try:
            c = self._conn()
            c.request("POST", path, body, {"Content-Type": "application/json", "Authorization": self.auth})
            r = c.getresponse()
            data = r.read()
            if r.getheader("connection", "").lower() == "close":
                self.close()
        except (OSError, http.client.HTTPException) as e:
            self.close()
            return 0, None, f"{type(e).__name__}: {e}".encode()
        try:
            j = json.loads(data.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            j = None
        return r.status, j, data

    def get(self, path, auth=True):
        try:
            c = self._conn()
            c.request("GET", path, headers={"Authorization": self.auth} if auth else {})
            r = c.getresponse()
            data = r.read()
        except (OSError, http.client.HTTPException) as e:
            self.close()
            return 0, f"{type(e).__name__}: {e}".encode()
        return r.status, data

    def q(self, query, **extra):
        payload = {"query": query}
        payload.update(extra)
        return self.post("/v1/query", payload)

    def ok(self, query, **extra):
        """Runs a statement that must succeed and returns its JSON result."""
        status, j, data = self.q(query, **extra)
        if status != 200:
            raise RuntimeError(f"{short(query, 200)!r} answered {status}: {short(data.decode('utf-8', 'replace'))}")
        return j

    def rows(self, query):
        return self.ok(query)["rows"]

    def ids(self, query):
        return [r[0] for r in self.rows(query)]

    def count(self, table, where=None):
        w = f" WHERE {where}" if where else ""
        return self.rows(f"SELECT COUNT(*) FROM {table}{w}")[0][0]

    def close(self):
        if self.conn is not None:
            self.conn.close()
            self.conn = None


def wait_up(host, seconds=90):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            if once(host, "GET", "/health", auth=False, timeout=3).status == 200:
                return True
        except (OSError, ValueError, EOFError):
            pass
        time.sleep(0.3)
    return False


def dump(c, page=10000):
    """Every database, table, index and row of a node, read through the API, for comparing states."""
    state = {}
    for (db,) in c.rows("SHOW DATABASES"):
        tables = {}
        for (t,) in c.rows(f"SHOW TABLES IN {db}"):
            name = f"{db}.{t}"
            rows = []
            last = None
            while True:
                where = "" if last is None else f" WHERE _id > {lit(last)}"
                j = c.ok(f"SELECT * FROM {name}{where} LIMIT {page}")
                cols = j["columns"]
                for r in j["rows"]:
                    rows.append((r[0], json.dumps({k: v for k, v in zip(cols[1:], r[1:]) if v is not None},
                                                  sort_keys=True)))
                if len(j["rows"]) < page:
                    break
                last = j["rows"][-1][0]
            tables[t] = {"indexes": [r[0] for r in c.rows(f"SHOW INDEXES IN {name}")], "rows": rows,
                         "count": c.count(name)}
        state[db] = tables
    return state


# --- values and the rules of docs/QUERY.md -------------------------------------------------------------


def lit(v):
    """A Python value as a query literal."""
    if v is None:
        return "NULL"
    if v is True:
        return "TRUE"
    if v is False:
        return "FALSE"
    if isinstance(v, str):
        return "'" + v.replace("'", "''") + "'"
    if isinstance(v, float):
        return repr(v)
    return str(v)


def utf16(s):
    return s.encode("utf-16-be", "surrogatepass")


def order(a, b):
    """-1, 0 or 1 for two non-null values of comparable types, None if their types differ."""
    if isinstance(a, bool) or isinstance(b, bool):
        if isinstance(a, bool) and isinstance(b, bool):
            return (a > b) - (a < b)
        return None
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        x, y = Fraction(a), Fraction(b)
        return (x > y) - (x < y)
    if isinstance(a, str) and isinstance(b, str):
        x, y = utf16(a), utf16(b)
        return (x > y) - (x < y)
    return None


def compare(a, op, b):
    """Three-valued: True, False or None (unknown)."""
    if a is None or b is None:
        return None
    c = order(a, b)
    if c is None:
        return op in ("!=", "<>")
    return {"=": c == 0, "!=": c != 0, "<>": c != 0, "<": c < 0, "<=": c <= 0, ">": c > 0, ">=": c >= 0}[op]


def rank(v):
    if v is None:
        return 0
    if isinstance(v, bool):
        return 1
    if isinstance(v, (int, float)):
        return 2
    if isinstance(v, str):
        return 3
    return 4


def sort_key_cmp(a, b):
    ra, rb = rank(a), rank(b)
    if ra != rb:
        return (ra > rb) - (ra < rb)
    if ra in (0, 4):
        return 0
    return order(a, b)


MISSING = object()


def stderr(msg):
    print(msg, file=sys.stderr, flush=True)


def utf16_len(s):
    """Length in UTF-16 code units, the unit of the parser's error positions."""
    return len(s.encode("utf-16-le", "surrogatepass")) // 2
