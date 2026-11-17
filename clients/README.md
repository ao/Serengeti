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
in an object, `query(statement, { ack, consistency })`, and ignores anything else in that place (a bare
`"local"` sends no `ack`).

- `query(statement, ack, consistency)`: rows (`columns`, `rows`; `dicts()` or `objects()`) for SELECT and
  SHOW, the node's summary for anything else. `ack` and `consistency` are as in
  [docs/CONSISTENCY.md](../docs/CONSISTENCY.md).
- `changes(db, after, table, wait, limit)` for one page of the change feed (Python returns `(changes,
  cursor)`, JavaScript `{ changes, cursor }`), and `follow(db, table)`, which yields changes as they come,
  from now on. Cursors belong to one node, so both use the first URL. A cursor outlives a restart of that
  node: after `Unavailable`, call `changes` again with the last cursor.
- `users()`, `set_user` / `setUser` (password, role and `databases`: a list limits the account to those
  databases, None or null removes the limit, left out keeps it), `delete_user` / `deleteUser` (an
  admin's) and `status()`.
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

JavaScript reads every JSON number as a double, so an integer beyond 2^53 (9007199254740992) comes back
from the JavaScript client as the nearest double; the node and the Python client keep it exact.

For certificates from `serengeti-certs`, give Python `ca_file="ca.pem"`. Node.js reads extra CAs only at
start: run it with `NODE_EXTRA_CA_CERTS=ca.pem`.

`clients/run.sh` tests both, each in its official container, against a node of the image, a TLS node and
a socket that never answers; CI runs it.
