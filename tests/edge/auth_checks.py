"""Credentials, the failed-login limit, the setup token and password changes (docs/API.md)."""

import base64
import threading
import time

from common import PW, Client, auth_header, check, check_error, expect, is_json_error, once, section


def basic(raw):
    return [("Authorization", raw)]


def run_basic(host):
    """Malformed and odd Authorization headers. At most four of these count as failed logins."""
    section("Basic credentials")
    q = b'{"query": "SHOW DATABASES"}'
    r = once(host, "POST", "/v1/query", q, auth=False)
    check_error("no credentials is 401", r, 401)
    check("and names the Basic realm", 'Basic realm="serengeti"' in (r.header("www-authenticate") or ""),
          repr(r.headers))
    good = base64.b64encode(f"admin:{PW}".encode()).decode()
    for label, raw in [("an empty Basic header", "Basic"), ("Basic and a space", "Basic "),
                       ("text that is not base64", "Basic !!!not-base64!!!"),
                       ("base64 without a colon", "Basic " + base64.b64encode(b"adminpassword").decode()),
                       ("a Bearer token", "Bearer " + good), ("Digest", 'Digest username="admin"'),
                       ("the credentials with no scheme", good), ("BasicX", "BasicX " + good),
                       ("base64 cut in the middle", "Basic " + good[:-3] + "!")]:
        r = once(host, "POST", "/v1/query", q, headers=basic(raw))
        check_error(f"{label} is 401", r, 401)
        check(f"{label}: with WWW-Authenticate", (r.header("www-authenticate") or "").startswith("Basic"), repr(r.headers))
    for label, raw in [("a lower-case scheme", "basic " + good), ("an upper-case scheme", "BASIC " + good),
                       ("extra spaces", "Basic    " + good + "  "), ("no base64 padding", "Basic " + good.rstrip("="))]:
        expect(f"{label} is accepted", 200, once(host, "POST", "/v1/query", q, headers=basic(raw)).status)
    for label, user, pw in [("a wrong user", "root", PW), ("an empty password", "admin", ""),
                            ("a 1,000-byte password", "admin", "x" * 1000), ("a user with a colon", "ad:min", PW)]:
        r = once(host, "POST", "/v1/query", q, headers=basic(auth_header(user, pw)))
        check_error(f"{label} is 401", r, 401)
    r = once(host, "POST", "/v1/query", q,
             headers=basic("Basic " + base64.b64encode(b"admin:\xff\xfe" + PW.encode()).decode()))
    check_error("a password that is not UTF-8 is 401", r, 401)
    for path in ["/v1/status", "/metrics", "/v1/backup", "/"]:
        check_error(f"GET {path} without credentials is 401", once(host, "GET", path, auth=False), 401)
    check_error("POST /v1/password without credentials is 401",
                once(host, "POST", "/v1/password", b'{"password": "not going to happen"}', auth=False), 401)


def run_limit(host):
    """Ten failed logins in a minute, then 429 even for the right password, until the minute is over."""
    section("failed-login limit")
    q = b'{"query": "SHOW DATABASES"}'
    statuses = [once(host, "POST", "/v1/query", q, headers=basic(auth_header("admin", f"wrong password {i}"))).status
                for i in range(10)]
    expect("ten wrong passwords are 401", [401] * 10, statuses)
    r = once(host, "POST", "/v1/query", q, headers=basic(auth_header("admin", "wrong password 11")))
    check_error("the eleventh is 429", r, 429)
    retry = r.header("retry-after") or ""
    check("with Retry-After between 1 and 60 seconds", retry.isdigit() and 1 <= int(retry) <= 60, repr(r.headers))
    r = once(host, "POST", "/v1/query", q)
    check_error("the right password is 429 while limited", r, 429)
    check_error("so is a status request", once(host, "GET", "/v1/status"), 429)
    expect("health still answers", 200, once(host, "GET", "/health", auth=False).status)
    expect("malformed credentials still get 401, not 429", 401,
           once(host, "POST", "/v1/query", q, headers=basic("Basic !!!")).status)
    wait = int(retry) if retry.isdigit() else 60
    time.sleep(wait + 1)
    expect(f"after Retry-After ({wait} s) the right password works", 200, once(host, "POST", "/v1/query", q).status)
    m = once(host, "GET", "/metrics").body.decode()
    fails = [ln for ln in m.splitlines() if ln.startswith("serengeti_login_failures_total ")]
    limited = [ln for ln in m.splitlines() if ln.startswith("serengeti_login_limited_total ")]
    check("metrics count the 10 failed logins (the 11th was refused before its check)", fails and float(fails[0].split()[1]) >= 10, repr(fails))
    check("and the 3 limited requests", limited and float(limited[0].split()[1]) >= 3, repr(limited))
    expect("one more wrong password is 401 again (a new window)", 401,
           once(host, "POST", "/v1/query", q, headers=basic(auth_header("admin", "wrong again"))).status)


def run_setup(host, token, others):
    """A node with no password yet. `others` are the passwords the concurrent setups try."""
    section("setup token")
    q = b'{"query": "SHOW DATABASES"}'
    check_error("a query before setup is 503", once(host, "POST", "/v1/query", q), 503)
    check_error("status before setup is 503", once(host, "GET", "/v1/status"), 503)
    expect("ready before setup is 200", 200, once(host, "GET", "/ready", auth=False).status)
    for label, body, want in [
            ("a wrong token", '{"token": "wrong", "password": "a good password 1"}', 403),
            ("a token as a number", '{"token": 5, "password": "a good password 1"}', 403),
            ("no token", '{"password": "a good password 1"}', 403),
            ("the token with one character changed", '{"token": "%s", "password": "a good password 1"}'
             % (token[:-1] + ("A" if token[-1] != "A" else "B")), 403),
            ("the token and an 11-character password", '{"token": "%s", "password": "elevenchars"}' % token, 400),
            ("the token and a 73-byte password", '{"token": "%s", "password": "%s"}' % (token, "x" * 73), 400),
            ("the token and 25 euro signs (75 bytes)", '{"token": "%s", "password": "%s"}' % (token, "€" * 25),
             400),
            ("the token and no password", '{"token": "%s"}' % token, 400),
            ("the token and a numeric password", '{"token": "%s", "password": 123456789012345}' % token, 400)]:
        r = once(host, "POST", "/v1/setup", body.encode(), auth=False)
        check_error(f"setup with {label} is {want}", r, want)
    check_error("setup with text/plain is 415", once(host, "POST", "/v1/setup", b"{}", auth=False, ctype="text/plain"),
                415)
    check_error("a query is still 503", once(host, "POST", "/v1/query", q), 503)

    # Many setups at once with the right token: one wins, the rest are refused, and only the winner's
    # password works.
    results = [None] * len(others)
    barrier = threading.Barrier(len(others))

    def attempt(i):
        barrier.wait()
        body = ('{"token": "%s", "password": "%s"}' % (token, others[i])).encode()
        results[i] = once(host, "POST", "/v1/setup", body, auth=False, timeout=60)

    threads = [threading.Thread(target=attempt, args=(i,)) for i in range(len(others))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    codes = [r.status for r in results]
    winners = [i for i, r in enumerate(results) if r.status == 200]
    expect(f"{len(others)} concurrent setups: exactly one is 200", 1, len(winners))
    check("the others are 409 (or 403 once the token is gone)", all(c in (200, 403, 409) for c in codes), repr(codes))
    check("and every refusal is a JSON error",
          all(is_json_error(r, r.status)[0] for r in results if r.status != 200), repr(results))
    if len(winners) != 1:
        return None
    win = others[winners[0]]
    expect("the winner's password works", 200, once(host, "POST", "/v1/query", q, headers=basic(auth_header("admin", win))).status)
    losers = [p for p in others if p != win][:2]
    expect("a loser's password does not", [401] * len(losers),
           [once(host, "POST", "/v1/query", q, headers=basic(auth_header("admin", p))).status for p in losers])
    r = once(host, "POST", "/v1/setup", ('{"token": "%s", "password": "another password 9"}' % token).encode(),
             auth=False)
    check_error("the token used again is 409", r, 409)
    return win


def run_setup_limit(host, token):
    section("setup attempts count towards the limit")
    codes = [once(host, "POST", "/v1/setup", ('{"token": "wrong %d", "password": "a good password 1"}' % i).encode(),
                  auth=False).status for i in range(10)]
    expect("ten wrong tokens are 403", [403] * 10, codes)
    r = once(host, "POST", "/v1/setup", b'{"token": "wrong 11", "password": "a good password 1"}', auth=False)
    check_error("the eleventh is 429", r, 429)
    good = ('{"token": "%s", "password": "%s"}' % (token, PW)).encode()
    r = once(host, "POST", "/v1/setup", good, auth=False)
    check_error("the right token is 429 while limited", r, 429)
    retry = r.header("retry-after") or "60"
    time.sleep(int(retry) + 1 if retry.isdigit() else 61)
    expect("after Retry-After the right token is 200", 200, once(host, "POST", "/v1/setup", good, auth=False).status)


def run_password(host, current):
    """Changes the admin password from `current`; prints the new one on the last line for run.sh."""
    section("password change")
    q = b'{"query": "SHOW DATABASES"}'
    cur = basic(auth_header("admin", current))
    for label, body, want in [("an 11-character password", '{"password": "elevenchars"}', 400),
                              ("a 73-byte password", '{"password": "%s"}' % ("y" * 73), 400),
                              ("no password", "{}", 400), ("a null password", '{"password": null}', 400),
                              ("a password that is an array", '{"password": ["a good password 1"]}', 400),
                              ("a body that is not JSON", "password=abc", 400)]:
        r = once(host, "POST", "/v1/password", body.encode(), headers=cur)
        check_error(f"changing to {label} is {want}", r, want)
    expect("the password is unchanged after the refusals", 200, once(host, "POST", "/v1/query", q, headers=cur).status)

    # Readers keep querying with the old password while it changes; once the change has answered, the old
    # password must fail on every new request.
    new = "über:geheim € 2026"  # 12+ characters, non-ASCII bytes and a colon
    stop = threading.Event()
    seen = []

    def reader():
        c = Client(host, pw=current)
        while not stop.is_set():
            seen.append(c.q("SHOW DATABASES")[0])
        c.close()

    readers = [threading.Thread(target=reader) for _ in range(2)]
    for t in readers:
        t.start()
    time.sleep(0.5)
    r = once(host, "POST", "/v1/password", ('{"password": "%s"}' % new).encode("utf-8"), headers=cur)
    # Each reader request with the old password now fails and counts towards the limit, so stop them first.
    stop.set()
    expect("a valid change is 200", 200, r.status)
    expect("and names the user", {"user": "admin"}, r.json())
    for t in readers:
        t.join()
    after = [once(host, "POST", "/v1/query", q, headers=cur).status for _ in range(3)]
    expect("the old password fails at once", [401] * 3, after)
    check("readers saw only 200 and 401", set(seen) <= {200, 401}, repr(sorted(set(seen))))
    nh = basic(auth_header("admin", new))
    expect("the new password (non-ASCII, with a colon) works", 200, once(host, "POST", "/v1/query", q, headers=nh).status)
    r = once(host, "POST", "/v1/password", ('{"password": "%s"}' % ("€" * 24)).encode("utf-8"), headers=nh)
    expect("24 euro signs (72 bytes) is accepted", 200, r.status)
    final = "€" * 24
    expect("and works", 200, once(host, "POST", "/v1/query", q, headers=basic(auth_header("admin", final))).status)
    print(f"value {final}", flush=True)


def run_password_after_restart(host, pw, old):
    section("password after a restart")
    q = b'{"query": "SHOW DATABASES"}'
    expect("the changed password works after a restart", 200,
           once(host, "POST", "/v1/query", q, headers=basic(auth_header("admin", pw))).status)
    expect("the first one does not", 401, once(host, "POST", "/v1/query", q, headers=basic(auth_header("admin", old))).status)
