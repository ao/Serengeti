// Tests for serengeti.mjs against a running node: SERENGETI_URL and SERENGETI_PASSWORD (the admin's).
import { test } from "node:test";
import assert from "node:assert/strict";
import { Client, SerengetiError, Unavailable, CursorGone } from "./serengeti.mjs";

const URL = process.env.SERENGETI_URL;
const PW = process.env.SERENGETI_PASSWORD;
const db = new Client({ urls: URL, user: "admin", password: PW });

const lit = (s) => "'" + s.replaceAll("'", "''") + "'";

// Records each statement c.query runs, with its answer.
function counting(c) {
  const seen = [];
  const query = c.query.bind(c);
  c.query = async (statement, options) => {
    const r = await query(statement, options);
    seen.push([statement, r]);
    return r;
  };
  return seen;
}

async function fresh(table) {
  await db.query(`DROP TABLE IF EXISTS ${table}`);
  await db.query(`CREATE TABLE ${table}`);
}

async function read(table, options) {
  const c = new Client({ urls: URL, user: "admin", password: PW });
  const seen = counting(c);
  const got = [];
  for await (const row of c.rows(table, options)) got.push(row);
  return { got, pages: seen.map(([, r]) => r), statements: seen.map(([s]) => s) };
}

test("setup", async () => {
  await db.query("CREATE DATABASE IF NOT EXISTS js");
  await db.query("CREATE TABLE IF NOT EXISTS js.items");
});

test("writes and reads", async () => {
  const w = await db.query("INSERT INTO js.items (_id, name, qty) VALUES ('bolt', 'bolt', 120), ('nut', 'nut', 300)");
  assert.equal(w.affected, 2);
  const r = await db.query("SELECT name, qty FROM js.items WHERE qty > 100 ORDER BY qty");
  assert.deepEqual(r.columns, ["name", "qty"]);
  assert.deepEqual([...r], [["bolt", 120], ["nut", 300]]);
  assert.deepEqual(r.objects()[1], { name: "nut", qty: 300 });
  let u = await db.query("INSERT INTO js.items (_id, qty) VALUES ('bolt', 1), ('pin', 5) ON CONFLICT DO UPDATE SET qty = qty + 1");
  assert.deepEqual([u.affected, u.inserted, u.updated, u.skipped, u.ids], [2, 1, 1, 0, ["bolt", "pin"]]);
  u = await db.query("INSERT INTO js.items (_id, qty) VALUES ('nut', 1) ON CONFLICT DO NOTHING");
  assert.deepEqual([u.affected, u.skipped, u.ids], [0, 1, []]);
  assert.deepEqual([...(await db.query("SELECT qty FROM js.items WHERE _id IN ('bolt', 'nut', 'pin')"))], [[121], [300], [5]]);
  await db.query("DELETE FROM js.items");
});

test("errors carry the status and the node's message", async () => {
  await assert.rejects(db.query("SELECT * FROM"), (e) => e instanceof SerengetiError && e.status === 400 && /position/.test(e.serverMessage));
  const wrong = new Client({ urls: URL, user: "admin", password: "wrong password here" });
  await assert.rejects(wrong.status(), (e) => e.status === 401);
});

test("a read account reads and is refused writes", async () => {
  await db.setUser("js-reader", { password: "reader password 1", role: "read" });
  try {
    const reader = new Client({ urls: URL, user: "js-reader", password: "reader password 1" });
    assert.equal((await reader.query("SELECT * FROM js.items")).rows.length, 0);
    await assert.rejects(reader.query("INSERT INTO js.items (name) VALUES ('x')"), (e) => e.status === 403);
    assert.equal((await db.users())["js-reader"], "read");
  } finally {
    await db.deleteUser("js-reader");
  }
  assert.equal((await db.users())["js-reader"], undefined);
});

test("an account limited to some databases", async () => {
  const made = await db.setUser("js-shop", { password: "shop password 1", role: "write", databases: ["JS"] });
  try {
    assert.deepEqual(made, { user: "js-shop", role: "write", databases: ["js"] });
    assert.deepEqual((await db.accounts()).find((a) => a.user === "js-shop"), made);
    assert.equal((await db.users())["js-shop"], "write");
    const shop = new Client({ urls: URL, user: "js-shop", password: "shop password 1" });
    assert.equal((await shop.query("INSERT INTO js.items (_id) VALUES ('js-shop')")).affected, 1);
    assert.deepEqual([...(await shop.query("SHOW DATABASES"))], [["js"]]);
    for (const q of ["SELECT * FROM py.items", "SELECT * FROM nowhere.items", "CREATE DATABASE js2"]) {
      await assert.rejects(shop.query(q), (e) => e.status === 403 && /may not use database/.test(e.serverMessage), q);
    }
    await assert.rejects(shop.changes("py", "now"), (e) => e.status === 403);
    // Left out, the list stays; null removes it.
    assert.deepEqual((await db.setUser("js-shop", { role: "read" })).databases, ["js"]);
    assert.deepEqual(await db.setUser("js-shop", { databases: null }), { user: "js-shop", role: "read" });
    assert.throws(() => db.setUser("js-shop", { databases: "js" }), TypeError);
    await assert.rejects(db.setUser("js-shop", { role: "admin", databases: ["js"] }), (e) => e.status === 400);
  } finally {
    await db.deleteUser("js-shop");
    await db.query("DELETE FROM js.items WHERE _id = 'js-shop'");
  }
});

test("an unreachable first node is skipped for reads", async () => {
  const two = new Client({ urls: ["http://127.0.0.1:7998", URL], user: "admin", password: PW, timeoutMs: 5000 });
  assert.ok((await two.query("SHOW DATABASES")).rows.length >= 1);
  await two.query("INSERT INTO js.items (_id) VALUES ('via-second')"); // refused connection: never sent
  assert.equal((await db.query("SELECT _id FROM js.items WHERE _id = 'via-second'")).rows.length, 1);
  await db.query("DELETE FROM js.items WHERE _id = 'via-second'");
});

test("a write that may have reached a node is not sent again", async () => {
  const two = new Client({ urls: [process.env.SERENGETI_SILENT_URL, URL], user: "admin", password: PW, timeoutMs: 2000 });
  const started = Date.now();
  await assert.rejects(two.query("INSERT INTO js.items (_id) VALUES ('maybe')"), (e) => e instanceof Unavailable);
  assert.ok(Date.now() - started >= 1500, "the write waited for its timeout"); // not refused outright
  assert.equal((await db.query("SELECT _id FROM js.items WHERE _id = 'maybe'")).rows.length, 0);
  assert.ok((await two.query("SHOW DATABASES")).rows.length >= 1); // a read moves on after the timeout
});

test("an answer cut short", async () => {
  const two = new Client({ urls: [process.env.SERENGETI_CUT_URL, URL], user: "admin", password: PW, timeoutMs: 5000 });
  assert.ok((await two.query("SHOW DATABASES")).rows.length >= 1); // a read moves on
  await assert.rejects(two.query("INSERT INTO js.items (_id) VALUES ('cut')"), (e) => e instanceof Unavailable);
  assert.equal((await db.query("SELECT _id FROM js.items WHERE _id = 'cut'")).rows.length, 0, "not sent again");
});

test("a password beyond Latin-1", async () => {
  await db.setUser("js-utf8", { password: "pässwörd-über-12 密码", role: "read" });
  try {
    const u = new Client({ urls: URL, user: "js-utf8", password: "pässwörd-über-12 密码" });
    assert.equal((await u.status()).version, (await db.status()).version);
  } finally {
    await db.deleteUser("js-utf8");
  }
});

test("TLS with the cluster's CA", async () => {
  const tls = new Client({ urls: process.env.SERENGETI_TLS_URL, user: "admin", password: PW });
  assert.equal((await tls.status()).version, (await db.status()).version);
});

test("the change feed", async () => {
  const { cursor } = await db.changes("js", "now");
  await db.query("INSERT INTO js.items (_id, qty) VALUES ('fed', 1)");
  const page = await db.changes("js", cursor, { table: "items" });
  assert.deepEqual(page.changes.map((c) => [c._id, c.row]), [["fed", { qty: 1 }]]);
  setTimeout(() => db.query("DELETE FROM js.items WHERE _id = 'fed'"), 300);
  const started = Date.now();
  for await (const c of db.follow("js", { table: "items", wait: 10 })) {
    assert.ok(Date.now() - started < 8000);
    assert.deepEqual([c._id, c.deleted], ["fed", true]);
    break;
  }
  await assert.rejects(db.changes("js", "000000000000:1"), (e) => e instanceof CursorGone && e.status === 410);
});

// rows(): every row of a table, or of a condition on it, in keyset pages. 2,500 rows with the node's random
// _ids, 500 to an INSERT; JavaScript sorts strings by UTF-16 code unit, as the node does.
const many = new Map();
let manyIds;

test("rows: setup", async () => {
  await fresh("js.many");
  for (let start = 0; start < 2500; start += 500) {
    const tuples = Array.from({ length: 500 }, (_, k) => `(${start + k}, ${(start + k) % 5})`);
    const w = await db.query(`INSERT INTO js.many (n, kind) VALUES ${tuples.join(", ")}`);
    w.ids.forEach((id, k) => many.set(id, start + k));
  }
  manyIds = [...many.keys()].sort();
});

test("rows: a table read whole in pages of 1,000, 7 and 1", async () => {
  // 1,000: two full pages and one of 500. 7: 357 full pages and one of 1 row. 1: 2,500 full pages and an empty
  // one. A short page that is not truncated ends the read.
  for (const [page, requests] of [[1000, 3], [7, 358], [1, 2501]]) {
    const { got, pages } = await read("js.many", { page });
    assert.deepEqual(got.map((r) => r._id), manyIds, `page ${page}`);
    assert.deepEqual(got, manyIds.map((id) => ({ _id: id, n: many.get(id), kind: many.get(id) % 5 })));
    assert.equal(pages.length, requests, `page ${page}`);
    assert.ok(pages.every((r) => r.rows.length <= page && !r.truncated));
  }
  const { got, pages, statements } = await read("js.many");
  assert.deepEqual([got.length, pages.length], [2500, 3]);
  assert.deepEqual(statements.slice(0, 2), [
    "SELECT * FROM js.many ORDER BY _id LIMIT 1000",
    `SELECT * FROM js.many WHERE _id > ${lit(manyIds[999])} ORDER BY _id LIMIT 1000`,
  ]);
  await fresh("js.empty");
  const empty = await read("js.empty");
  assert.deepEqual([empty.got, empty.pages.length], [[], 1]);
});

test("rows: large rows with a small maxBytes", async () => {
  await fresh("js.big");
  const ids = Array.from({ length: 40 }, (_, i) => `b${String(i).padStart(2, "0")}`);
  const order = ids.map((_, i) => ids[(i * 17) % 40]);
  for (let k = 0; k < 40; k += 10) {
    await db.query(`INSERT INTO js.big (_id, pad) VALUES ${order.slice(k, k + 10).map((i) => `(${lit(i)}, ${lit(i.repeat(2500))})`).join(", ")}`);
  }
  // Rows of 7,500 characters, at most 20,000 bytes to a page: each page ends early, truncated, well short of its
  // 1,000 rows, and the read goes on from its last _id.
  let { got, pages, statements } = await read("js.big", { maxBytes: 20000 });
  assert.deepEqual(got.map((r) => r._id), ids);
  assert.deepEqual(got.map((r) => r.pad), ids.map((i) => i.repeat(2500)));
  assert.ok(pages.length >= 10, `${pages.length} pages`);
  assert.ok(pages.slice(0, -1).every((r) => r.truncated && r.rows.length >= 1 && r.rows.length < 1000));
  assert.equal(pages.at(-1).truncated, false);
  assert.equal(statements[1], `SELECT * FROM js.big WHERE _id > ${lit(pages[0].rows.at(-1)[0])} ORDER BY _id LIMIT 1000`);
  // The node returns at least one row, so maxBytes 1 reads one row to a page.
  ({ got, pages } = await read("js.big", { maxBytes: 1, where: "pad IS NOT NULL" }));
  assert.deepEqual(got.map((r) => r._id), ids);
  assert.deepEqual(pages.map((r) => r.rows.length), Array(40).fill(1));
  // maxBytes on one query.
  let r = await db.query("SELECT * FROM js.big ORDER BY _id LIMIT 100", { maxBytes: 1 });
  assert.deepEqual([r.rows.length, r.truncated, r.rows[0][0]], [1, true, ids[0]]);
  r = await db.query("SELECT * FROM js.big ORDER BY _id LIMIT 100");
  assert.deepEqual([r.rows.length, r.truncated], [40, false]);
  await assert.rejects(db.query("SELECT * FROM js.big", { maxBytes: 0 }), (e) => e.status === 400 && /max_bytes/.test(e.serverMessage));
});

test("rows: a condition", async () => {
  const want = manyIds.filter((id) => [1, 3].includes(many.get(id) % 5));
  // Without its parentheses the OR would take every row after the first page again.
  let { got, pages, statements } = await read("js.many", { where: "kind = 1 OR kind = 3", page: 7 });
  assert.deepEqual(got.map((r) => r._id), want);
  assert.equal(pages.length, 143); // 142 pages of 7 and one of 6
  assert.equal(statements[1], `SELECT * FROM js.many WHERE _id > ${lit(want[6])} AND (kind = 1 OR kind = 3\n) ORDER BY _id LIMIT 7`);
  ({ got } = await read("js.many", { where: "n >= 2490 -- the last ten", page: 3 }));
  assert.deepEqual(got.map((x) => x.n).sort((a, b) => a - b), Array.from({ length: 10 }, (_, k) => 2490 + k));
  // Words in a string, and a column called offset, are not the clauses.
  ({ got } = await read("js.many", { where: "kind = 'order by _id limit 1 offset 2; -- ) (' OR (offset IS NULL AND n < 3)" }));
  assert.deepEqual(got.map((x) => x._id), manyIds.filter((id) => many.get(id) < 3));
});

test("rows: a condition that could leave its page is refused", async () => {
  const c = new Client({ urls: URL, user: "admin", password: PW });
  const seen = counting(c);
  for (const where of ["n > 1 ORDER BY n", "n > 1 order by n", "n > 1 LIMIT 5", "n > 1 OFFSET 5",
    "n > 1 offset -- five\n 5", "n > 1) OR (n < 1", "(n > 1", "n > 1; DELETE FROM js.many",
    "n = 'open", "n = 'it''s", "", "  -- a comment only"]) {
    assert.throws(() => c.rows("js.many", { where }), Error, JSON.stringify(where));
  }
  assert.throws(() => c.rows("js.many", { where: "n > 1 LIMIT 5" }), /no ORDER BY, LIMIT or OFFSET \(found LIMIT\)/);
  assert.throws(() => c.rows("js.many", { where: 5 }), TypeError);
  for (const table of ["many", "js.many WHERE n = 1", "js.many;", "js..many", "js.many.x", " js.many", "js.many\n", 5]) {
    assert.throws(() => c.rows(table), /db\.table/, JSON.stringify(table));
  }
  for (const page of [0, -1, 1.5, true, "10", null, NaN]) {
    assert.throws(() => c.rows("js.many", { page }), /page is a whole number/, String(page));
  }
  assert.deepEqual(seen, []); // all refused before anything was sent
  await assert.rejects(async () => {
    for await (const _ of c.rows("js.nowhere"));
  }, (e) => e instanceof SerengetiError && /does not exist/.test(e.serverMessage));
});

test("rows: rows written during the read", async () => {
  await fresh("js.live");
  const ids = Array.from({ length: 100 }, (_, i) => `r${String(i).padStart(3, "0")}`);
  await db.query(`INSERT INTO js.live (_id, v) VALUES ${ids.map((i) => `(${lit(i)}, 0)`).join(", ")}`);
  const it = new Client({ urls: URL, user: "admin", password: PW }).rows("js.live", { page: 10 });
  const got = [];
  const take = async (n) => {
    for (let k = 0; k < n; k++) got.push((await it.next()).value);
  };
  await take(10); // the first page, r000 to r009; the second is not read yet
  // Before the last _id read: missed, or read as it was. After it: read as it is when its page runs.
  await db.query("INSERT INTO js.live (_id, v) VALUES ('r0005', 1), ('r0505', 1), ('r999', 1)");
  await db.query("DELETE FROM js.live WHERE _id = 'r060'");
  await db.query("UPDATE js.live SET v = 2 WHERE _id IN ('r001', 'r070')");
  await take(5); // r010 to r014, of the second page, r010 to r019
  // Inside a page already read (r0175) is missed too; after its last _id (r0195 after r019) is read.
  await db.query("INSERT INTO js.live (_id, v) VALUES ('r0175', 3), ('r0195', 3)");
  for await (const row of it) got.push(row);
  const want = [...ids.filter((i) => i !== "r060"), "r0505", "r999", "r0195"].sort();
  assert.deepEqual(got.map((r) => r._id), want);
  const v = Object.fromEntries(got.map((r) => [r._id, r.v]));
  assert.deepEqual([v.r001, v.r070, v.r0505, v.r0195], [0, 2, 1, 3]);
});

test("rows: _id values with quotes, unicode and surrogate pairs", async () => {
  await fresh("js.odd");
  const ids = ["it's", "'", "''", "a'b''c", "--", "-- x\n", "x') OR ('1' = '1", "back\\slash", "ü",
    "Ünïcödé", "密码", "\u{1F600}", "\u{1F600}\u{1F600}", "\u{1D11E} clef",
    "\uE000", "\uFFFD", "\uFF5E", "a\nb", "tab\there", "%_", "z", "Z", " lead"];
  // A column called __proto__ is a name like any other, and stays a column of the row.
  await db.query(`INSERT INTO js.odd (_id, v, __proto__) VALUES ${ids.map((i) => `(${lit(i)}, ${lit(i)}, 1)`).join(", ")}`);
  const want = [...ids].sort();
  for (const page of [1, 2, 1000]) {
    const { got } = await read("js.odd", { page });
    assert.deepEqual(got.map((r) => r._id), want, `page ${page}`);
    assert.deepEqual(got.map((r) => r.v), want, `page ${page}`);
    assert.ok(got.every((r) => Object.hasOwn(r, "__proto__") && Object.getPrototypeOf(r) === Object.prototype));
  }
  const { got } = await read("js.odd", { where: "v >= '\u{1F600}'", page: 1 });
  assert.deepEqual(got.map((r) => r._id), want.filter((i) => i >= "\u{1F600}"));
});

test("an upsert's counts", async () => {
  await fresh("js.ups");
  const w = await db.query("INSERT INTO js.ups (_id, n) VALUES ('a', 1)");
  assert.deepEqual([w.affected, w.ids], [1, ["a"]]);
  assert.deepEqual(["inserted", "updated", "skipped"].filter((k) => k in w), []); // only ON CONFLICT sends them
  const counts = (u) => [u.affected, u.inserted, u.updated, u.skipped, u.ids];
  assert.deepEqual(counts(await db.query("INSERT INTO js.ups (_id, n) VALUES ('a', 1), ('b', 1) ON CONFLICT DO UPDATE SET n = n + 1")),
    [2, 1, 1, 0, ["a", "b"]]);
  assert.deepEqual(counts(await db.query("INSERT INTO js.ups (_id, n) VALUES ('b', 5), ('c', 5) ON CONFLICT DO NOTHING")),
    [1, 1, 0, 1, ["c"]]);
  const { got } = await read("js.ups");
  assert.deepEqual(got, [{ _id: "a", n: 2 }, { _id: "b", n: 1 }, { _id: "c", n: 5 }]);
});
