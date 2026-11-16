"""Tests for serengeti.py against a running node: SERENGETI_URL and SERENGETI_PASSWORD (the admin's)."""

import os
import threading
import time
import unittest

import serengeti

URL = os.environ["SERENGETI_URL"]
PW = os.environ["SERENGETI_PASSWORD"]


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
