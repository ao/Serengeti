"""Creates a database and a table, inserts two rows, selects them and prints them, tab-separated.

Standard library only (urllib). Each run adds two rows to examples.python_items.

    SERENGETI_URL=http://127.0.0.1:1985 SERENGETI_PASSWORD='...' python3 client.py

SERENGETI_URL defaults to http://127.0.0.1:1985. A password is required; there is no default.
Exit status is 0 on success, 2 when SERENGETI_PASSWORD is not set, and 1 when the node refuses a
request or cannot be reached. Errors print the node's "error" message to stderr.
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.request

URL = os.environ.get("SERENGETI_URL", "http://127.0.0.1:1985").rstrip("/")
PASSWORD = os.environ.get("SERENGETI_PASSWORD")


class SerengetiError(Exception):
    pass


def query(sql):
    """Runs one statement and returns the decoded JSON reply."""
    body = json.dumps({"query": sql}).encode("utf-8")
    request = urllib.request.Request(URL + "/v1/query", data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    token = base64.b64encode(f"admin:{PASSWORD}".encode("utf-8")).decode("ascii")
    request.add_header("Authorization", "Basic " + token)
    try:
        with urllib.request.urlopen(request, timeout=10) as reply:
            return json.loads(reply.read())
    except urllib.error.HTTPError as err:
        text = err.read().decode("utf-8", "replace")
        try:
            message = json.loads(text).get("error")
        except (ValueError, AttributeError):
            message = None
        raise SerengetiError(f"HTTP {err.code}: {message or 'no error message from the server'}")
    except urllib.error.URLError as err:
        raise SerengetiError(f"cannot reach {URL}: {err.reason}")


def main():
    if not PASSWORD:
        print("set SERENGETI_PASSWORD to the admin password", file=sys.stderr)
        return 2
    try:
        query("CREATE DATABASE IF NOT EXISTS examples")
        query("CREATE TABLE IF NOT EXISTS examples.python_items")
        query("INSERT INTO examples.python_items (name, qty) VALUES ('bolt', 120), ('nut', 300)")
        result = query("SELECT name, qty FROM examples.python_items WHERE qty > 100 ORDER BY name")
    except SerengetiError as err:
        print(f"serengeti: {err}", file=sys.stderr)
        return 1
    print("\t".join(result["columns"]))
    for row in result["rows"]:
        print("\t".join(str(value) for value in row))
    return 0


if __name__ == "__main__":
    sys.exit(main())
