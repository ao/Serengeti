// A small client for Serengeti's HTTP API, one ES module with no dependencies (Node 18 or later, or a
// browser). It uses fetch.
//
//   import { Client } from "./serengeti.mjs";
//   const db = new Client({ urls: ["https://n1:1985", "https://n2:1985"], user: "admin", password });
//   await db.query("INSERT INTO shop.items (_id, qty) VALUES ('bolt', 120)");
//   const r = await db.query("SELECT name, qty FROM shop.items WHERE qty > 100");   // r.rows, r.objects()
//   for await (const change of db.follow("shop", { table: "items" })) { ... }
//
// Reads (SELECT and SHOW) that fail because a node cannot be reached or answers 503 are tried on the next
// URL. Writes are sent once: a 503 on a write means it may have been written (docs/CONSISTENCY.md), so
// retrying is the caller's decision. Node verifies TLS against its CAs; for a cluster's own CA, start node
// with NODE_EXTRA_CA_CERTS=ca.pem.

export class SerengetiError extends Error {
  constructor(status, message) {
    super(`${status}: ${message}`);
    this.status = status;
    this.serverMessage = message;
  }
}

/** 503: too few nodes reachable, the memory limit, or the node is not set up. */
export class Unavailable extends SerengetiError {}

/** 410 from the change feed: read the table again and follow from after "now". */
export class CursorGone extends SerengetiError {}

export class Result {
  constructor(body) {
    this.columns = body.columns ?? [];
    this.rows = body.rows ?? [];
    this.truncated = body.truncated ?? false;
  }
  objects() {
    return this.rows.map((r) => Object.fromEntries(this.columns.map((c, i) => [c, r[i]])));
  }
  [Symbol.iterator]() {
    return this.rows[Symbol.iterator]();
  }
}

const READS = /^\s*(SELECT|SHOW)\b/i;
const NEVER_SENT = new Set(["ECONNREFUSED", "ENOTFOUND", "EAI_AGAIN"]);

// fetch wraps the socket error, sometimes in an AggregateError (one per address tried).
function errorCode(e) {
  for (let c = e; c; c = c.cause) {
    if (typeof c.code === "string") return c.code;
    const inner = c.errors?.find((x) => typeof x.code === "string");
    if (inner) return inner.code;
  }
  return undefined;
}

export class Client {
  /** urls: a URL or a list of them, tried in order. */
  constructor({ urls, user, password, timeoutMs = 30000 }) {
    this.urls = Array.isArray(urls) ? [...urls] : [urls];
    if (this.urls.length === 0 || !this.urls[0]) throw new Error("give at least one URL");
    this.auth = "Basic " + btoa(`${user}:${password}`);
    this.timeoutMs = timeoutMs;
  }

  /** Runs one statement: a Result for SELECT and SHOW, else the write's summary. */
  async query(statement, { ack, consistency } = {}) {
    const body = { query: statement };
    if (ack !== undefined) body.ack = ack;
    if (consistency !== undefined) body.consistency = consistency;
    const out = await this.#call("POST", "/v1/query", body, READS.test(statement));
    return out.type === "rows" ? new Result(out) : out;
  }

  /** One page of the change feed: { changes, cursor }. See docs/API.md, "Changes". */
  async changes(db, after, { table, wait = 0, limit = 1000 } = {}) {
    const q = new URLSearchParams({ db, after, wait: String(wait), limit: String(limit) });
    if (table !== undefined) q.set("table", table);
    return this.#call("GET", "/v1/changes?" + q, undefined, false, this.urls[0], this.timeoutMs + wait * 1000);
  }

  /** Yields changes as they come. Throws CursorGone if the node lost the cursor's place. First URL only. */
  async *follow(db, { table, after = "now", wait = 30 } = {}) {
    let cursor = after;
    if (cursor === "now") cursor = (await this.changes(db, "now", { table })).cursor;
    for (;;) {
      const page = await this.changes(db, cursor, { table, wait });
      cursor = page.cursor;
      for (const c of page.changes) yield c;
    }
  }

  async users() {
    const out = await this.#call("GET", "/v1/users", undefined, true);
    return Object.fromEntries(out.users.map((u) => [u.user, u.role]));
  }

  setUser(name, { password, role } = {}) {
    const body = {};
    if (password !== undefined) body.password = password;
    if (role !== undefined) body.role = role;
    return this.#call("PUT", "/v1/users/" + encodeURIComponent(name), body, false);
  }

  deleteUser(name) {
    return this.#call("DELETE", "/v1/users/" + encodeURIComponent(name), undefined, false);
  }

  status() {
    return this.#call("GET", "/v1/status", undefined, true);
  }

  async #call(method, path, body, retry, url, timeoutMs) {
    const urls = url ? [url] : this.urls;
    let last;
    for (const base of urls) {
      let res;
      try {
        res = await fetch(base.replace(/\/$/, "") + path, {
          method,
          headers: { Authorization: this.auth, ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
          body: body !== undefined ? JSON.stringify(body) : undefined,
          signal: AbortSignal.timeout(timeoutMs ?? this.timeoutMs),
        });
      } catch (e) {
        const code = errorCode(e);
        last = new Unavailable(0, `${base}: ${code ?? e.message}`);
        // A write that may have reached the node is not sent again. One whose connection was refused, or
        // whose host name did not resolve, never did.
        if (!retry && !NEVER_SENT.has(code)) throw last;
        continue;
      }
      let out;
      try {
        out = await res.json();
      } catch {
        out = { error: res.statusText };
      }
      if (res.ok) return out;
      const Kind = res.status === 503 ? Unavailable : res.status === 410 ? CursorGone : SerengetiError;
      last = new Kind(res.status, out.error ?? res.statusText);
      if (!(retry && res.status === 503)) throw last;
    }
    throw last;
  }
}
