// A small client for Serengeti's HTTP API, one ES module with no dependencies (Node 18 or later, or a
// browser). It uses fetch.
//
//   import { Client } from "./serengeti.mjs";
//   const db = new Client({ urls: ["https://n1:1985", "https://n2:1985"], user: "admin", password });
//   await db.query("INSERT INTO shop.items (_id, qty) VALUES ('bolt', 120)");
//   const r = await db.query("SELECT name, qty FROM shop.items WHERE qty > 100");   // r.rows, r.objects()
//   for await (const row of db.rows("shop.items", { where: "qty > 100" })) { ... }  // in _id order, in pages
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
const TABLE = /^[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*$/;
const BLANK = " \t\n\r\f";
const NAME_START = /[A-Za-z_]/;
const NAME_PART = /[A-Za-z0-9_]/;
const DIGIT = /[0-9]/;

const quote = (s) => "'" + s.replaceAll("'", "''") + "'";

function skipComment(s, i) {
  while (i < s.length && s[i] !== "\n" && s[i] !== "\r") i++;
  return i;
}

// OFFSET followed by a number is the clause; followed by anything else it is a column of that name.
function numberNext(s, i) {
  while (i < s.length) {
    if (BLANK.includes(s[i])) i++;
    else if (s.startsWith("--", i)) i = skipComment(s, i);
    else return DIGIT.test(s[i]);
  }
  return false;
}

// Throws unless `where` stays inside the parentheses rows() puts it in and holds no ORDER BY, LIMIT or
// OFFSET. Strings and -- comments are read as the node's lexer reads them.
function checkWhere(where) {
  if (typeof where !== "string") throw new TypeError(`where is a condition as text, not ${typeof where}`);
  const n = where.length;
  let depth = 0;
  let empty = true;
  for (let i = 0; i < n; ) {
    const c = where[i];
    if (BLANK.includes(c)) {
      i++;
    } else if (where.startsWith("--", i)) {
      i = skipComment(where, i);
    } else if (c === "'") {
      let j = i + 1;
      let q;
      for (;;) {
        q = where.indexOf("'", j);
        if (q < 0) {
          const rest = where.slice(i);
          throw new Error(`where has a string that is not closed: ${rest.length <= 32 ? rest : rest.slice(0, 32) + "..."}`);
        }
        if (where.startsWith("''", q)) j = q + 2;
        else break;
      }
      i = q + 1;
      empty = false;
    } else if (NAME_START.test(c)) {
      let j = i + 1;
      while (j < n && NAME_PART.test(where[j])) j++;
      const word = where.slice(i, j).toLowerCase();
      if (word === "order" || word === "limit" || (word === "offset" && numberNext(where, j))) {
        throw new Error("where is a condition only: rows() adds ORDER BY _id and LIMIT itself, so it takes no"
          + ` ORDER BY, LIMIT or OFFSET (found ${where.slice(i, j)})`);
      }
      i = j;
      empty = false;
    } else if (DIGIT.test(c)) {
      // A number, exponent included: its letters are not a word.
      let j = i + 1;
      while (j < n && (NAME_PART.test(where[j]) || where[j] === ".")) j++;
      i = j;
      empty = false;
    } else {
      if (c === "(") depth++;
      else if (c === ")" && --depth < 0) throw new Error("where closes a parenthesis it did not open");
      else if (c === ";") throw new Error("where is one condition, without ;");
      i++;
      empty = false;
    }
  }
  if (depth > 0) throw new Error("where opens a parenthesis it does not close");
  if (empty) throw new Error("where is empty; leave it out to read every row");
}

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
    // The node reads credentials as UTF-8; btoa alone takes Latin-1 and refuses anything beyond it.
    let latin1 = "";
    for (const b of new TextEncoder().encode(`${user}:${password}`)) latin1 += String.fromCharCode(b);
    this.auth = "Basic " + btoa(latin1);
    this.timeoutMs = timeoutMs;
  }

  /**
   * Runs one statement: a Result for SELECT and SHOW, else the write's summary. maxBytes asks the node to end
   * the answer's rows sooner than its own bound on an answer's size (docs/API.md, `max_bytes`).
   */
  async query(statement, { ack, consistency, maxBytes } = {}) {
    const body = { query: statement };
    if (ack !== undefined) body.ack = ack;
    if (consistency !== undefined) body.consistency = consistency;
    if (maxBytes !== undefined) body.max_bytes = maxBytes;
    const out = await this.#call("POST", "/v1/query", body, READS.test(statement));
    return out.type === "rows" ? new Result(out) : out;
  }

  /**
   * Every row of `table` ("db.table") in _id order, or those where `where` holds, as an async generator of
   * objects (the row's columns that are not null). Reads keyset pages, `SELECT * FROM table WHERE _id > last
   * AND (where) ORDER BY _id LIMIT page`; a page with fewer than `page` rows ends the read unless the node
   * ended it early (`truncated`). `where` is one condition as text, without ORDER BY, LIMIT or OFFSET; a bad
   * table, condition or page throws here, before anything is sent. See clients/README.md.
   */
  rows(table, { where, page = 1000, maxBytes } = {}) {
    if (typeof table !== "string" || !TABLE.test(table)) {
      throw new Error(`rows() takes a table as db.table, not ${JSON.stringify(table)}`);
    }
    if (where !== undefined && where !== null) checkWhere(where);
    if (!Number.isSafeInteger(page) || page < 1) {
      throw new Error(`page is a whole number of rows, at least 1, not ${String(page)}`);
    }
    return this.#rows(table, where ?? null, page, maxBytes);
  }

  async *#rows(table, where, page, maxBytes) {
    let last = null;
    for (;;) {
      const terms = [];
      if (last !== null) terms.push(`_id > ${quote(last)}`);
      // The newline ends a -- comment at the end of the condition.
      if (where !== null) terms.push(`(${where}\n)`);
      const statement = `SELECT * FROM ${table}${terms.length ? " WHERE " + terms.join(" AND ") : ""} ORDER BY _id LIMIT ${page}`;
      const r = await this.query(statement, { maxBytes });
      if (r.rows.length > 0 && r.columns[0] !== "_id") {
        throw new SerengetiError(0, `a page of ${table} does not start with _id`);
      }
      for (const values of r.rows) {
        const id = values[0];
        // _id order is UTF-16 code unit order (docs/QUERY.md, "Comparisons"), as JavaScript compares strings.
        // Each _id must come after the last, or the read could go round forever.
        if (typeof id !== "string" || (last !== null && !(id > last))) {
          throw new SerengetiError(0, `a page of ${table} repeated a row or lost its place`);
        }
        last = id;
        // fromEntries makes own properties, so a column called __proto__ is a column like any other.
        yield Object.fromEntries(r.columns.map((c, i) => [c, values[i]]).filter(([, v]) => v !== null && v !== undefined));
      }
      if (r.rows.length === 0 || (r.rows.length < page && !r.truncated)) return;
    }
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

  // Creates or changes an account; what is left out stays as it is. `databases` limits a read or write
  // account to a list of databases ("shop") and tables ("shop.orders"), and null removes the limit.
  setUser(name, { password, role, databases } = {}) {
    const body = {};
    if (password !== undefined) body.password = password;
    if (role !== undefined) body.role = role;
    if (databases !== undefined) {
      if (databases !== null && !Array.isArray(databases)) {
        throw new TypeError("databases is an array of database and table names, or null");
      }
      body.databases = databases;
    }
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
      } catch (e) {
        if (res.ok) {
          // A 2xx whose body was cut short: the request reached the node, so a write may have been applied.
          last = new Unavailable(0, `${base}: the answer was cut short (${e.cause?.code ?? e.message})`);
          if (!retry) throw last;
          continue;
        }
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
