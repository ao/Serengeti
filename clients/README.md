# Clients

Two small clients for the HTTP API, each one file with no dependencies: copy it into your project.

- [python/serengeti.py](python/serengeti.py): Python 3.8 or later, standard library only.
- [js/serengeti.mjs](js/serengeti.mjs): an ES module for Node.js 18 or later, on the built-in `fetch`.

```python
import serengeti

db = serengeti.Client(["https://n1:1985", "https://n2:1985"], "app", password, ca_file="ca.pem")
db.query("INSERT INTO shop.items (_id, name, qty) VALUES ('bolt', 'bolt', 120)")
for name, qty in db.query("SELECT name, qty FROM shop.items WHERE qty > 100"):
    print(name, qty)
for change in db.follow("shop", table="items"):
    print(change["_id"], change.get("row"), change.get("deleted"))
```

```js
import { Client } from "./serengeti.mjs";

const db = new Client({ urls: ["https://n1:1985", "https://n2:1985"], user: "app", password });
await db.query("INSERT INTO shop.items (_id, name, qty) VALUES ('bolt', 'bolt', 120)");
const r = await db.query("SELECT name, qty FROM shop.items WHERE qty > 100");
console.log(r.objects());
for await (const change of db.follow("shop", { table: "items" })) console.log(change);
```

Both have the same parts. Python takes the optional arguments by position or name; JavaScript takes them
in an object, `query(statement, { ack, consistency, maxBytes })`, and ignores anything else in that place
(a bare `"local"` sends no `ack`).

- `query(statement, ack, consistency, max_bytes)`: rows (`columns`, `rows`, `truncated`; `dicts()` or
  `objects()`) for SELECT and SHOW, the node's summary for anything else: `affected`, and `ids` for an
  INSERT; an `INSERT ... ON CONFLICT` also has `inserted`, `updated` and `skipped`
  ([docs/QUERY.md](../docs/QUERY.md)). `ack` and `consistency` are as in
  [docs/CONSISTENCY.md](../docs/CONSISTENCY.md). `max_bytes` (JavaScript `maxBytes`, sent as
  `"max_bytes"`) asks the node to end the answer's rows at about that many bytes, sooner than its own
  bound on an answer's size; a page that ends there has `truncated` set ([docs/API.md](../docs/API.md)).
- `rows(table, where, page, max_bytes)`, in JavaScript `rows(table, { where, page, maxBytes })`: every
  row of a table, or those where a condition holds, in `_id` order. See "Reading a whole table" below.
- `changes(db, after, table, wait, limit)` for one page of the change feed (Python returns `(changes,
  cursor)`, JavaScript `{ changes, cursor }`), and `follow(db, table)`, which yields changes as they come,
  from now on. Cursors belong to one node, so both use the first URL. A cursor outlives a restart of that
  node: after `Unavailable`, call `changes` again with the last cursor.
- `users()`, `set_user` / `setUser` (password, role and `databases`: a list of databases and `db.table`
  names limits the account to those, None or null removes the limit, left out keeps it), `delete_user` /
  `deleteUser` (an admin's) and `status()`.
- Errors carry the HTTP status and the node's message (Python `serengeti.Error`, JavaScript
  `SerengetiError`). `Unavailable` is 503, or no answer (status 0, also for a TLS failure); `CursorGone`
  is 410 from the change feed: read the table again and follow from now.

With several nodes, list the URLs in order. A read (SELECT or SHOW) that gets no answer or a 503 is tried
on the next node. A write is sent once: if it reached a node and the answer was lost, or was 503, it may
have been written (see "Writes" in docs/CONSISTENCY.md), so trying again is your decision: an
`UPDATE ... SET n = n + 1` sent twice may count twice, and `INSERT ... ON CONFLICT DO NOTHING` sent twice
skips the row the first one wrote. A
write goes on to the next node only when it cannot have arrived: the connection was refused or the name
did not resolve.

## Reading a whole table

```python
for row in db.rows("shop.items", where="qty > 100"):
    print(row["_id"], row.get("name"))
```

```js
for await (const row of db.rows("shop.items", { where: "qty > 100" })) console.log(row._id, row.name);
```

`rows` is a generator in Python and an async generator in JavaScript. It reads keyset pages, as in
"Reading a large table" in [docs/QUERY.md](../docs/QUERY.md), one page each time the rows read so far run
out:

```
SELECT * FROM shop.items WHERE (qty > 100
) ORDER BY _id LIMIT 1000
SELECT * FROM shop.items WHERE _id > 'the last _id of the page before' AND (qty > 100
) ORDER BY _id LIMIT 1000
```

so no page reads the rows before it. A page with fewer than `page` rows (1,000 unless given; at most
10,000, the node's cap) ends the read, unless it is `truncated`: the node ended it early, at its bound on
an answer's size or at `max_bytes`, and the read goes on from its last `_id`. Each page is a read like
any other, so with several URLs it moves on to the next node as a read does. A row comes as a dict
(JavaScript an object) of its columns that are not null, as `serengeti export` writes them, so it looks
the same whatever page it came in; in Python, `row.get("qty")` reads a column the row may not have. Each
`_id` must come after the one before it, in the node's order (UTF-16 code units): a page that repeats a
row or goes back stops the read with `Error` (JavaScript `SerengetiError`), status 0, rather than read
the same rows again.

The condition is text, put inside `AND (...)` as above, with a newline before the `)` so a `--` comment
at its end stays a comment. It is refused before anything is sent (Python `ValueError`, JavaScript
`Error`) if it holds ORDER BY, LIMIT or OFFSET, which `rows` adds itself, a `;`, a string that is not
closed, or a `)` it did not open or a `(` it does not close, so it cannot reach out of its parentheses.
Words in strings and comments do not count, nor does a column called `offset`. The table must be
`db.table`. Any other mistake in the condition is the node's 400, whose position counts from the start of
the page's statement.

Each page sees the table as it is when it runs. A row written during the read is read if its `_id` comes
after the last `_id` of the page read before, and missed otherwise, even when it comes after the last row
taken from that page; a row deleted before its page runs is not returned. To go on after an error, read
again from the last `_id` taken: `where="_id > 'it''s'"` (a quote inside a string is written twice).

## Numbers and certificates

JavaScript reads every JSON number as a double, so an integer beyond 2^53 (9007199254740992) comes back
from the JavaScript client as the nearest double; the node and the Python client keep it exact.

For certificates from `serengeti-certs`, give Python `ca_file="ca.pem"`. Node.js reads extra CAs only at
start: run it with `NODE_EXTRA_CA_CERTS=ca.pem`.

## Tests

`clients/run.sh` tests both, each in its official container, against a node of the image, a TLS node, a
socket that never answers and a server whose answers stop part way; CI runs it.
