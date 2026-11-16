"""A small client for Serengeti's HTTP API, in one file with no dependencies (Python 3.8 or later).

    import serengeti

    db = serengeti.Client(["https://n1:1985", "https://n2:1985"], "admin", password, ca_file="ca.pem")
    db.query("INSERT INTO shop.items (_id, qty) VALUES ('bolt', 120)")
    for name, qty in db.query("SELECT name, qty FROM shop.items WHERE qty > 100"):
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

    def query(self, statement, ack=None, consistency=None):
        """Runs one statement. Returns a Result for SELECT and SHOW, else the write's summary as a dict.

        ``ack="local"`` acknowledges a write once one node has it; ``consistency="majority"`` makes a read
        sync with a majority first.
        """
        body = {"query": statement}
        if ack is not None:
            body["ack"] = ack
        if consistency is not None:
            body["consistency"] = consistency
        read = statement.lstrip().upper().startswith(_READS)
        out = self._call("POST", "/v1/query", body, retry=read)
        return Result(out) if out.get("type") == "rows" else out

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

        ``databases`` limits a read or write account to a list of database names; ``None`` removes the limit.
        Returns the account as the node has it now: ``{"user": ..., "role": ..., "databases": [...]}``.
        """
        body = {}
        if password is not None:
            body["password"] = password
        if role is not None:
            body["role"] = role
        if databases is not _KEEP:
            if isinstance(databases, (str, bytes)):
                raise TypeError("databases is a list of database names, or None")
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


def _never_sent(e):
    """True when the request cannot have reached the node: the name did not resolve or the connection was
    refused."""
    reason = getattr(e, "reason", e)
    return isinstance(reason, (ConnectionRefusedError, socket.gaierror))
