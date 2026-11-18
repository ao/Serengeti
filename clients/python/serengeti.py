"""A small client for Serengeti's HTTP API, in one file with no dependencies (Python 3.8 or later).

    import serengeti

    db = serengeti.Client(["https://n1:1985", "https://n2:1985"], "admin", password, ca_file="ca.pem")
    db.query("INSERT INTO shop.items (_id, qty) VALUES ('bolt', 120)")
    for name, qty in db.query("SELECT name, qty FROM shop.items WHERE qty > 100"):
        ...
    for row in db.rows("shop.items", where="qty > 100"):   # every row, in _id order, in pages
        ...
    for change in db.follow("shop", table="items"):
        ...

Reads (SELECT and SHOW) that fail because a node cannot be reached or answers 503 are tried on the next
node in the list. Writes are sent once, to the first node that answers: a 503 on a write means it may have
been written (see docs/CONSISTENCY.md), so retrying it is the caller's decision.
"""

import base64
import http.client
import json
import re
import socket
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

__all__ = ["Client", "Result", "Error", "Unavailable", "CursorGone"]


class _Keep:
    def __repr__(self):
        return "<keep>"


_KEEP = _Keep()  # set_user leaves an account's databases as they are


class Error(Exception):
    """A request the node refused. ``status`` is the HTTP status; the message is the node's."""

    def __init__(self, status, message):
        super().__init__("%s: %s" % (status, message))
        self.status = status
        self.message = message


class Unavailable(Error):
    """503: too few nodes reachable, the memory limit, or the node is not set up."""


class CursorGone(Error):
    """410 from the change feed: read the table again and follow from ``after="now"``."""


class Result:
    """Rows from SELECT or SHOW. Iterating gives tuples; ``dicts()`` gives one dict per row."""

    def __init__(self, body):
        self.columns = body.get("columns", [])
        self.rows = [tuple(r) for r in body.get("rows", [])]
        self.truncated = body.get("truncated", False)

    def __iter__(self):
        return iter(self.rows)

    def __len__(self):
        return len(self.rows)

    def dicts(self):
        return [dict(zip(self.columns, r)) for r in self.rows]

    def __repr__(self):
        return "Result(columns=%r, rows=%d, truncated=%r)" % (self.columns, len(self.rows), self.truncated)


_READS = ("SELECT", "SHOW")


class Client:
    """One account on one or more nodes of a cluster.

    ``urls`` is a URL or a list of them, tried in order. ``ca_file`` is the CA certificate the nodes'
    certificates are signed with (``ca.pem`` from serengeti-certs); without it, the system's CAs are used.
    """

    def __init__(self, urls, user, password, ca_file=None, timeout=30):
        self.urls = [urls] if isinstance(urls, str) else list(urls)
        if not self.urls:
            raise ValueError("give at least one URL")
        self._auth = "Basic " + base64.b64encode(("%s:%s" % (user, password)).encode()).decode()
        self._timeout = timeout
        self._context = ssl.create_default_context(cafile=ca_file) if ca_file else None

    # ---- queries ----

    def query(self, statement, ack=None, consistency=None, max_bytes=None):
        """Runs one statement. Returns a Result for SELECT and SHOW, else the write's summary as a dict.

        ``ack="local"`` acknowledges a write once one node has it; ``consistency="majority"`` makes a read
        sync with a majority first. ``max_bytes`` asks the node to end the answer's rows sooner than its own
        bound on an answer's size; a page that ends there has ``truncated`` set (docs/API.md).
        """
        body = {"query": statement}
        if ack is not None:
            body["ack"] = ack
        if consistency is not None:
            body["consistency"] = consistency
        if max_bytes is not None:
            body["max_bytes"] = max_bytes
        read = statement.lstrip().upper().startswith(_READS)
        out = self._call("POST", "/v1/query", body, retry=read)
        return Result(out) if out.get("type") == "rows" else out

    def rows(self, table, where=None, page=1000, max_bytes=None):
        """Yields every row of ``table`` ("db.table") in ``_id`` order, or those where ``where`` holds.

        Reads in keyset pages, ``SELECT * FROM table WHERE _id > last AND (where) ORDER BY _id LIMIT page``,
        so no page reads the rows before it. A page with fewer than ``page`` rows ends the read unless the
        node ended it early (``truncated``, its bound on an answer's size or ``max_bytes``). Each row is a
        dict of its columns that are not null. ``where`` is one condition as text; it may not hold ORDER BY,
        LIMIT or OFFSET, and is refused with ValueError before anything is sent. See clients/README.md.
        """
        if not isinstance(table, str) or not _TABLE.fullmatch(table):
            raise ValueError("rows() takes a table as db.table, not %r" % (table,))
        if where is not None:
            _check_where(where)
        if isinstance(page, bool) or not isinstance(page, int) or page < 1:
            raise ValueError("page is a whole number of rows, at least 1, not %r" % (page,))
        return self._rows(table, where, page, max_bytes)

    def _rows(self, table, where, page, max_bytes):
        last = None
        last_key = None
        while True:
            terms = []
            if last is not None:
                terms.append("_id > " + _quote(last))
            if where is not None:
                # The newline ends a -- comment at the end of the condition.
                terms.append("(" + where + "\n)")
            statement = "SELECT * FROM %s%s ORDER BY _id LIMIT %d" % (
                table, " WHERE " + " AND ".join(terms) if terms else "", page)
            r = self.query(statement, max_bytes=max_bytes)
            if r.rows and (not r.columns or r.columns[0] != "_id"):
                raise Error(0, "a page of %s does not start with _id" % table)
            for values in r.rows:
                rid = values[0]
                key = rid.encode("utf-16-be", "surrogatepass") if isinstance(rid, str) else None
                # _id order is UTF-16 code unit order (docs/QUERY.md, "Comparisons"); big-endian UTF-16 bytes
                # sort the same way. Each _id must come after the last, or the read could go round forever.
                if key is None or (last_key is not None and key <= last_key):
                    raise Error(0, "a page of %s repeated a row or lost its place" % table)
                last, last_key = rid, key
                yield {c: v for c, v in zip(r.columns, values) if v is not None}
            if not r.rows or (len(r.rows) < page and not r.truncated):
                return

    # ---- the change feed ----

    def changes(self, db, after, table=None, wait=0, limit=1000):
        """One page of the change feed: ``(changes, cursor)``. See docs/API.md, "Changes"."""
        q = {"db": db, "after": after, "wait": wait, "limit": limit}
        if table is not None:
            q["table"] = table
        out = self._call("GET", "/v1/changes?" + urllib.parse.urlencode(q), None, retry=False, url=self.urls[0],
                         timeout=self._timeout + wait)
        return out["changes"], out["cursor"]

    def follow(self, db, table=None, after="now", wait=30):
        """Yields changes as they come, forever. Raises CursorGone if the node lost the cursor's place.

        Cursors belong to one node, so this follows the first URL only.
        """
        cursor = after
        if cursor == "now":
            _, cursor = self.changes(db, "now", table)
        while True:
            changes, cursor = self.changes(db, cursor, table, wait=wait)
            for c in changes:
                yield c

    # ---- accounts (admin) ----

    def users(self):
        return {u["user"]: u["role"] for u in self._call("GET", "/v1/users", None, retry=True)["users"]}

    def set_user(self, name, password=None, role=None, databases=_KEEP):
        """Create or change an account; what is left out stays as it is.

        ``databases`` limits a read or write account to a list of databases (``"shop"``) and tables
        (``"shop.orders"``); ``None`` removes the limit.
        Returns the account as the node has it now: ``{"user": ..., "role": ..., "databases": [...]}``.
        """
        body = {}
        if password is not None:
            body["password"] = password
        if role is not None:
            body["role"] = role
        if databases is not _KEEP:
            if isinstance(databases, (str, bytes)):
                raise TypeError("databases is a list of database and table names, or None")
            body["databases"] = None if databases is None else list(databases)
        return self._call("PUT", "/v1/users/" + urllib.parse.quote(name, safe=""), body, retry=False)

    def delete_user(self, name):
        return self._call("DELETE", "/v1/users/" + urllib.parse.quote(name, safe=""), None, retry=False)

    def status(self):
        return self._call("GET", "/v1/status", None, retry=True)

    # ---- transport ----

    def _call(self, method, path, body, retry, url=None, timeout=None):
        urls = [url] if url else self.urls
        last = None
        for i, base in enumerate(urls):
            try:
                return self._send(base, method, path, body, timeout or self._timeout)
            except (urllib.error.URLError, ConnectionError, TimeoutError, OSError, http.client.HTTPException,
                    ValueError) as e:
                # HTTPException: the answer was cut short (IncompleteRead) or malformed; ValueError: its JSON was.
                if isinstance(e, Error):
                    raise
                last = Unavailable(0, "%s: %s" % (base, getattr(e, "reason", None) or e.__class__.__name__))
                # A write that may have reached the node is not sent again.
                if not retry and not _never_sent(e):
                    raise last
            except Unavailable as e:
                last = e
                if not retry:
                    raise
            if i + 1 < len(urls):
                time.sleep(0.05 * (i + 1))
        raise last

    def _send(self, base, method, path, body, timeout):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(base.rstrip("/") + path, data=data, method=method)
        req.add_header("Authorization", self._auth)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=timeout, context=self._context) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            try:
                message = json.loads(e.read().decode()).get("error", e.reason)
            except ValueError:
                message = e.reason
            kind = {503: Unavailable, 410: CursorGone}.get(e.code, Error)
            raise kind(e.code, message) from None


_TABLE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*")
_NAME_START = frozenset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_")
_NAME_PART = _NAME_START | frozenset("0123456789")


def _quote(s):
    return "'" + s.replace("'", "''") + "'"


def _check_where(where):
    """Raises ValueError unless ``where`` is text that stays inside the parentheses rows() puts it in, and
    holds no ORDER BY, LIMIT or OFFSET. Strings and -- comments are read as the node's lexer reads them."""
    if not isinstance(where, str):
        raise TypeError("where is a condition as text, not %r" % (where,))
    n = len(where)
    i = 0
    depth = 0
    empty = True
    while i < n:
        c = where[i]
        if c in " \t\n\r\f":
            i += 1
        elif where.startswith("--", i):
            while i < n and where[i] not in "\n\r":
                i += 1
        elif c == "'":
            j = i + 1
            while True:
                q = where.find("'", j)
                if q < 0:
                    raise ValueError("where has a string that is not closed: %s" % _shorten(where[i:]))
                if where.startswith("''", q):
                    j = q + 2
                else:
                    break
            i = q + 1
            empty = False
        elif c in _NAME_START:
            j = i + 1
            while j < n and where[j] in _NAME_PART:
                j += 1
            word = where[i:j].lower()
            if word in ("order", "limit") or (word == "offset" and _number_next(where, j)):
                raise ValueError("where is a condition only: rows() adds ORDER BY _id and LIMIT itself, so it "
                                 "takes no ORDER BY, LIMIT or OFFSET (found %s)" % where[i:j])
            i = j
            empty = False
        elif c in "0123456789":
            # A number, exponent included: its letters are not a word.
            j = i + 1
            while j < n and (where[j] in _NAME_PART or where[j] == "."):
                j += 1
            i = j
            empty = False
        else:
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth < 0:
                    raise ValueError("where closes a parenthesis it did not open")
            elif c == ";":
                raise ValueError("where is one condition, without ;")
            i += 1
            empty = False
    if depth > 0:
        raise ValueError("where opens a parenthesis it does not close")
    if empty:
        raise ValueError("where is empty; leave it out to read every row")


def _number_next(s, i):
    """True if the next token from ``i`` on, past blanks and comments, is a number: OFFSET followed by one
    is the clause, OFFSET followed by anything else is a column of that name."""
    n = len(s)
    while i < n:
        if s[i] in " \t\n\r\f":
            i += 1
        elif s.startswith("--", i):
            while i < n and s[i] not in "\n\r":
                i += 1
        else:
            return s[i] in "0123456789"
    return False


def _shorten(s):
    return s if len(s) <= 32 else s[:32] + "..."


def _never_sent(e):
    """True when the request cannot have reached the node: the name did not resolve or the connection was
    refused."""
    reason = getattr(e, "reason", e)
    return isinstance(reason, (ConnectionRefusedError, socket.gaierror))
