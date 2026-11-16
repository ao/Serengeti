// Tests for serengeti.mjs against a running node: SERENGETI_URL and SERENGETI_PASSWORD (the admin's).
import { test } from "node:test";
import assert from "node:assert/strict";
import { Client, SerengetiError, Unavailable, CursorGone } from "./serengeti.mjs";

const URL = process.env.SERENGETI_URL;
const PW = process.env.SERENGETI_PASSWORD;
const db = new Client({ urls: URL, user: "admin", password: PW });

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
