"""Tests for serengeti.py against a running node: SERENGETI_URL and SERENGETI_PASSWORD (the admin's)."""

import os
import random
import threading
import time
import unittest

import serengeti

URL = os.environ["SERENGETI_URL"]
PW = os.environ["SERENGETI_PASSWORD"]


def lit(s):
    return "'" + s.replace("'", "''") + "'"


def utf16(s):
    """The node's order for strings: by UTF-16 code unit."""
    return s.encode("utf-16-be", "surrogatepass")


def counting(db):
    """Records each statement db.query runs, with its answer."""
    seen = []
    query = db.query

    def q(statement, *args, **kwargs):
        r = query(statement, *args, **kwargs)
        seen.append((statement, r))
        return r

    db.query = q
    return seen


def fresh(db, table):
    db.query("DROP TABLE IF EXISTS " + table)
    db.query("CREATE TABLE " + table)


class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.db = serengeti.Client(URL, "admin", PW)
        cls.db.query("CREATE DATABASE IF NOT EXISTS py")
        cls.db.query("CREATE TABLE IF NOT EXISTS py.items")

    def test_writes_and_reads(self):
        w = self.db.query("INSERT INTO py.items (_id, name, qty) VALUES ('bolt', 'bolt', 120), ('nut', 'nut', 300)")
        self.assertEqual(w["type"], "write")
        self.assertEqual(w["affected"], 2)
        r = self.db.query("SELECT name, qty FROM py.items WHERE qty > 100 ORDER BY qty")
        self.assertEqual(r.columns, ["name", "qty"])
        self.assertEqual(list(r), [("bolt", 120), ("nut", 300)])
        self.assertEqual(r.dicts()[1], {"name": "nut", "qty": 300})
        self.assertEqual(len(self.db.query("SHOW TABLES IN py")), 1)
        u = self.db.query("INSERT INTO py.items (_id, qty) VALUES ('bolt', 1), ('pin', 5) ON CONFLICT DO UPDATE SET qty = qty + 1")
        self.assertEqual((u["affected"], u["inserted"], u["updated"], u["skipped"], u["ids"]), (2, 1, 1, 0, ["bolt", "pin"]))
        u = self.db.query("INSERT INTO py.items (_id, qty) VALUES ('nut', 1) ON CONFLICT DO NOTHING")
        self.assertEqual((u["affected"], u["skipped"], u["ids"]), (0, 1, []))
        self.assertEqual(list(self.db.query("SELECT qty FROM py.items WHERE _id IN ('bolt', 'nut', 'pin')")), [(121,), (300,), (5,)])
        self.db.query("DELETE FROM py.items")

    def test_errors_carry_the_status_and_the_nodes_message(self):
        with self.assertRaises(serengeti.Error) as e:
            self.db.query("SELECT * FROM")
        self.assertEqual(e.exception.status, 400)
        self.assertIn("position", e.exception.message)
        with self.assertRaises(serengeti.Error) as e:
            serengeti.Client(URL, "admin", "wrong password here").status()
        self.assertEqual(e.exception.status, 401)

    def test_a_read_account_reads_and_is_refused_writes(self):
        self.db.set_user("py-reader", password="reader password 1", role="read")
        try:
            reader = serengeti.Client(URL, "py-reader", "reader password 1")
            self.assertEqual(len(reader.query("SELECT * FROM py.items")), 0)
            with self.assertRaises(serengeti.Error) as e:
                reader.query("INSERT INTO py.items (name) VALUES ('x')")
            self.assertEqual(e.exception.status, 403)
            self.assertEqual(self.db.users()["py-reader"], "read")
        finally:
            self.db.delete_user("py-reader")
        self.assertNotIn("py-reader", self.db.users())

    def test_an_account_limited_to_some_databases(self):
        made = self.db.set_user("py-shop", password="shop password 1", role="write", databases=["PY"])
        try:
            self.assertEqual(made, {"user": "py-shop", "role": "write", "databases": ["py"]})
            self.assertIn(made, self.db.accounts())
            self.assertEqual(self.db.users()["py-shop"], "write")
            shop = serengeti.Client(URL, "py-shop", "shop password 1")
            self.assertEqual(shop.query("INSERT INTO py.items (_id) VALUES ('py-shop')")["affected"], 1)
            self.assertEqual(list(shop.query("SHOW DATABASES")), [("py",)])
            for q in ("SELECT * FROM js.items", "SELECT * FROM nowhere.items", "CREATE DATABASE py2"):
                with self.assertRaises(serengeti.Error) as e:
                    shop.query(q)
                self.assertEqual(e.exception.status, 403)
                self.assertIn("this account may not use database", e.exception.message)
            with self.assertRaises(serengeti.Error) as e:
                shop.changes("js", "now")
            self.assertEqual(e.exception.status, 403)
            # Left out, the list stays; None removes it.
            self.assertEqual(self.db.set_user("py-shop", role="read")["databases"], ["py"])
            self.assertEqual(self.db.set_user("py-shop", databases=None), {"user": "py-shop", "role": "read"})
            with self.assertRaises(TypeError):
                self.db.set_user("py-shop", databases="py")
            with self.assertRaises(serengeti.Error) as e:
                self.db.set_user("py-shop", role="admin", databases=["py"])
            self.assertEqual(e.exception.status, 400)
        finally:
            self.db.delete_user("py-shop")
            self.db.query("DELETE FROM py.items WHERE _id = 'py-shop'")

    def test_an_unreachable_first_node_is_skipped(self):
        db = serengeti.Client(["http://no-such-node.invalid:1985", URL], "admin", PW, timeout=5)
        self.assertEqual(db.status()["version"], self.db.status()["version"])
        self.assertEqual(len(db.query("SHOW DATABASES")) >= 1, True)
        # A connection that never opened did not reach a node, so even a write goes to the next one.
        db.query("INSERT INTO py.items (_id) VALUES ('via-second')")
        self.assertEqual(len(self.db.query("SELECT _id FROM py.items WHERE _id = 'via-second'")), 1)
        self.db.query("DELETE FROM py.items WHERE _id = 'via-second'")

    def test_a_write_that_may_have_reached_a_node_is_not_sent_again(self):
        db = serengeti.Client([os.environ["SERENGETI_SILENT_URL"], URL], "admin", PW, timeout=2)
        start = time.time()
        with self.assertRaises(serengeti.Unavailable):
            db.query("INSERT INTO py.items (_id) VALUES ('maybe')")
        self.assertGreaterEqual(time.time() - start, 1.5)  # it waited for its timeout: the node took it in
        self.assertEqual(len(self.db.query("SELECT _id FROM py.items WHERE _id = 'maybe'")), 0)
        self.assertGreaterEqual(len(db.query("SHOW DATABASES")), 1)  # a read moves on after the timeout

    def test_an_answer_cut_short(self):
        db = serengeti.Client([os.environ["SERENGETI_CUT_URL"], URL], "admin", PW, timeout=5)
        # A read moves on to the next node; a write may have been applied, so it is not sent again.
        self.assertGreaterEqual(len(db.query("SHOW DATABASES")), 1)
        with self.assertRaises(serengeti.Unavailable):
            db.query("INSERT INTO py.items (_id) VALUES ('cut')")
        self.assertEqual(len(self.db.query("SELECT _id FROM py.items WHERE _id = 'cut'")), 0)

    def test_a_password_beyond_latin1(self):
        self.db.set_user("py-utf8", password="pässwörd-über-12 密码", role="read")
        try:
            self.assertEqual(serengeti.Client(URL, "py-utf8", "pässwörd-über-12 密码").status()["version"],
                             self.db.status()["version"])
        finally:
            self.db.delete_user("py-utf8")

    def test_tls_with_the_clusters_ca(self):
        tls = os.environ["SERENGETI_TLS_URL"]
        db = serengeti.Client(tls, "admin", PW, ca_file=os.environ["SERENGETI_CA"])
        self.assertEqual(db.status()["version"], self.db.status()["version"])
        with self.assertRaises(serengeti.Unavailable) as e:
            serengeti.Client(tls, "admin", PW).status()  # the system's CAs do not know this one
        self.assertIn("CERTIFICATE_VERIFY_FAILED", e.exception.message)

    def test_the_change_feed(self):
        _, cursor = self.db.changes("py", "now")
        self.db.query("INSERT INTO py.items (_id, qty) VALUES ('fed', 1)")
        changes, cursor = self.db.changes("py", cursor, table="items")
        self.assertEqual([(c["_id"], c["row"]) for c in changes], [("fed", {"qty": 1})])
        # follow() waits for the next change.
        threading.Timer(0.3, lambda: self.db.query("DELETE FROM py.items WHERE _id = 'fed'")).start()
        start = time.time()
        change = next(self.db.follow("py", table="items", wait=10))
        self.assertLess(time.time() - start, 8)
        self.assertEqual((change["_id"], change["deleted"]), ("fed", True))
        with self.assertRaises(serengeti.CursorGone):
            self.db.changes("py", "000000000000:1")


class RowsTest(unittest.TestCase):
    """rows(): every row of a table, or of a condition on it, in keyset pages."""

    @classmethod
    def setUpClass(cls):
        cls.db = serengeti.Client(URL, "admin", PW)
        cls.db.query("CREATE DATABASE IF NOT EXISTS py")
        fresh(cls.db, "py.many")
        # 2,500 rows with random _ids (the node's UUIDs), 500 to an INSERT.
        cls.many = {}
        for start in range(0, 2500, 500):
            w = cls.db.query("INSERT INTO py.many (n, kind) VALUES "
                             + ", ".join("(%d, %d)" % (n, n % 5) for n in range(start, start + 500)))
            cls.many.update(zip(w["ids"], range(start, start + 500)))
        cls.many_ids = sorted(cls.many, key=utf16)

    def read(self, table, **kwargs):
        db = serengeti.Client(URL, "admin", PW)
        pages = counting(db)
        return list(db.rows(table, **kwargs)), [r for _, r in pages], [s for s, _ in pages]

    def test_a_table_read_whole_in_pages_of_1000_7_and_1(self):
        # 1,000: two full pages and one of 500. 7: 357 full pages and one of 1 row. 1: 2,500 full pages and an
        # empty one. A short page that is not truncated ends the read.
        for page, requests in ((1000, 3), (7, 358), (1, 2501)):
            got, pages, _ = self.read("py.many", page=page)
            self.assertEqual([r["_id"] for r in got], self.many_ids, page)
            self.assertEqual(got, [{"_id": i, "n": self.many[i], "kind": self.many[i] % 5} for i in self.many_ids])
            self.assertEqual(len(pages), requests, page)
            self.assertTrue(all(len(r) <= page and not r.truncated for r in pages))
        got, pages, statements = self.read("py.many")
        self.assertEqual((len(got), len(pages)), (2500, 3))
        self.assertEqual(statements[:2], [
            "SELECT * FROM py.many ORDER BY _id LIMIT 1000",
            "SELECT * FROM py.many WHERE _id > %s ORDER BY _id LIMIT 1000" % lit(self.many_ids[999])])
        fresh(self.db, "py.empty")
        got, pages, _ = self.read("py.empty")
        self.assertEqual((got, len(pages)), ([], 1))

    def test_large_rows_with_a_small_max_bytes(self):
        fresh(self.db, "py.big")
        ids = ["b%02d" % i for i in range(40)]
        random.Random(3).shuffle(ids)
        for k in range(0, 40, 10):
            self.db.query("INSERT INTO py.big (_id, pad) VALUES "
                          + ", ".join("(%s, %s)" % (lit(i), lit(i * 2500)) for i in ids[k:k + 10]))
        want = sorted(ids)
        # Rows of 7,500 characters, at most 20,000 bytes to a page: each page ends early, truncated, well short of
        # its 1,000 rows, and the read goes on from its last _id.
        got, pages, statements = self.read("py.big", max_bytes=20000)
        self.assertEqual([r["_id"] for r in got], want)
        self.assertEqual([r["pad"] for r in got], [i * 2500 for i in want])
        self.assertGreaterEqual(len(pages), 10)
        self.assertTrue(all(r.truncated and 1 <= len(r) < 1000 for r in pages[:-1]))
        self.assertFalse(pages[-1].truncated)
        self.assertEqual(statements[1], "SELECT * FROM py.big WHERE _id > %s ORDER BY _id LIMIT 1000" % lit(pages[0].rows[-1][0]))
        # The node returns at least one row, so max_bytes=1 reads one row to a page.
        got, pages, _ = self.read("py.big", max_bytes=1, where="pad IS NOT NULL")
        self.assertEqual([r["_id"] for r in got], want)
        self.assertEqual([len(r) for r in pages], [1] * 40)
        # max_bytes on one query.
        r = self.db.query("SELECT * FROM py.big ORDER BY _id LIMIT 100", max_bytes=1)
        self.assertEqual((len(r), r.truncated, r.rows[0][0]), (1, True, want[0]))
        r = self.db.query("SELECT * FROM py.big ORDER BY _id LIMIT 100")
        self.assertEqual((len(r), r.truncated), (40, False))
        with self.assertRaises(serengeti.Error) as e:
            self.db.query("SELECT * FROM py.big", max_bytes=0)
        self.assertEqual(e.exception.status, 400)
        self.assertIn("max_bytes", e.exception.message)

    def test_a_condition(self):
        want = [i for i in self.many_ids if self.many[i] % 5 in (1, 3)]
        # Without its parentheses the OR would take every row after the first page again.
        got, pages, statements = self.read("py.many", where="kind = 1 OR kind = 3", page=7)
        self.assertEqual([r["_id"] for r in got], want)
        self.assertEqual(len(pages), 143)  # 142 pages of 7 and one of 6
        self.assertEqual(statements[1], "SELECT * FROM py.many WHERE _id > %s AND (kind = 1 OR kind = 3\n) ORDER BY _id LIMIT 7"
                         % lit(want[6]))
        got, _, _ = self.read("py.many", where="n >= 2490 -- the last ten", page=3)
        self.assertEqual(sorted(r["n"] for r in got), list(range(2490, 2500)))
        # Words in a string, and a column called offset, are not the clauses.
        got, _, _ = self.read("py.many", where="kind = 'order by _id limit 1 offset 2; -- ) (' OR (offset IS NULL AND n < 3)")
        self.assertEqual([r["_id"] for r in got], [i for i in self.many_ids if self.many[i] < 3])

    def test_a_condition_that_could_leave_its_page_is_refused(self):
        db = serengeti.Client(URL, "admin", PW)
        seen = counting(db)
        for where in ("n > 1 ORDER BY n", "n > 1 order by n", "n > 1 LIMIT 5", "n > 1 OFFSET 5",
                      "n > 1 offset -- five\n 5", "n > 1) OR (n < 1", "(n > 1", "n > 1; DELETE FROM py.many",
                      "n = 'open", "n = 'it''s", "", "  -- a comment only"):
            with self.assertRaises(ValueError, msg=repr(where)):
                db.rows("py.many", where=where)
        with self.assertRaises(ValueError) as e:
            db.rows("py.many", where="n > 1 LIMIT 5")
        self.assertIn("no ORDER BY, LIMIT or OFFSET (found LIMIT)", str(e.exception))
        with self.assertRaises(TypeError):
            db.rows("py.many", where=5)
        for table in ("many", "py.many WHERE n = 1", "py.many;", "py..many", "py.many.x", " py.many", "py.many\n"):
            with self.assertRaises(ValueError, msg=repr(table)):
                db.rows(table)
        for page in (0, -1, 1.5, True, "10", None):
            with self.assertRaises(ValueError, msg=repr(page)):
                db.rows("py.many", page=page)
        self.assertEqual(seen, [])  # all refused before anything was sent
        with self.assertRaises(serengeti.Error) as e:
            list(db.rows("py.nowhere"))
        self.assertIn("does not exist", e.exception.message)

    def test_rows_written_during_the_read(self):
        fresh(self.db, "py.live")
        ids = ["r%03d" % i for i in range(100)]
        self.db.query("INSERT INTO py.live (_id, v) VALUES " + ", ".join("(%s, 0)" % lit(i) for i in ids))
        it = serengeti.Client(URL, "admin", PW).rows("py.live", page=10)
        got = [next(it) for _ in range(10)]  # the first page, r000 to r009; the second is not read yet
        # Before the last _id read: missed, or read as it was. After it: read as it is when its page runs.
        self.db.query("INSERT INTO py.live (_id, v) VALUES ('r0005', 1), ('r0505', 1), ('r999', 1)")
        self.db.query("DELETE FROM py.live WHERE _id = 'r060'")
        self.db.query("UPDATE py.live SET v = 2 WHERE _id IN ('r001', 'r070')")
        got += [next(it) for _ in range(5)]  # r010 to r014, of the second page, r010 to r019
        # Inside a page already read (r0175) is missed too; after its last _id (r0195 after r019) is read.
        self.db.query("INSERT INTO py.live (_id, v) VALUES ('r0175', 3), ('r0195', 3)")
        got += list(it)
        want = sorted([i for i in ids if i != "r060"] + ["r0505", "r999", "r0195"])
        self.assertEqual([r["_id"] for r in got], want)
        v = {r["_id"]: r["v"] for r in got}
        self.assertEqual((v["r001"], v["r070"], v["r0505"], v["r0195"]), (0, 2, 1, 3))

    def test_ids_with_quotes_unicode_and_surrogate_pairs(self):
        fresh(self.db, "py.odd")
        ids = ["it's", "'", "''", "a'b''c", "--", "-- x\n", "x') OR ('1' = '1", "back\\slash", "ü",
               "Ünïcödé", "密码", "\U0001F600", "\U0001F600\U0001F600", "\U0001D11E clef",
               "\ue000", "\ufffd", "\uff5e", "a\nb", "tab\there", "%_", "z", "Z", " lead"]
        self.db.query("INSERT INTO py.odd (_id, v) VALUES " + ", ".join("(%s, %s)" % (lit(i), lit(i)) for i in ids))
        want = sorted(ids, key=utf16)
        self.assertNotEqual(want, sorted(ids))  # in code points, U+E000 to U+FFFF come before the pairs
        for page in (1, 2, 1000):
            got = list(self.db.rows("py.odd", page=page))
            self.assertEqual([r["_id"] for r in got], want, page)
            self.assertEqual([r["v"] for r in got], want, page)
        got = list(self.db.rows("py.odd", where="v >= '\U0001F600'", page=1))
        self.assertEqual([r["_id"] for r in got], [i for i in want if utf16(i) >= utf16("\U0001F600")])

    def test_an_upserts_counts(self):
        fresh(self.db, "py.ups")
        w = self.db.query("INSERT INTO py.ups (_id, n) VALUES ('a', 1)")
        self.assertEqual((w["affected"], w["ids"]), (1, ["a"]))
        self.assertEqual([k for k in ("inserted", "updated", "skipped") if k in w], [])  # only ON CONFLICT sends them
        keys = ("affected", "inserted", "updated", "skipped", "ids")
        u = self.db.query("INSERT INTO py.ups (_id, n) VALUES ('a', 1), ('b', 1) ON CONFLICT DO UPDATE SET n = n + 1")
        self.assertEqual(tuple(u[k] for k in keys), (2, 1, 1, 0, ["a", "b"]))
        u = self.db.query("INSERT INTO py.ups (_id, n) VALUES ('b', 5), ('c', 5) ON CONFLICT DO NOTHING")
        self.assertEqual(tuple(u[k] for k in keys), (1, 1, 0, 1, ["c"]))
        self.assertEqual(list(self.db.rows("py.ups")), [{"_id": "a", "n": 2}, {"_id": "b", "n": 1}, {"_id": "c", "n": 5}])


if __name__ == "__main__":
    unittest.main(verbosity=2)
