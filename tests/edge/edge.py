"""Entry point of the edge suite's client side; run.sh calls one step at a time:

    python3 edge.py STEP ARGS...

Each check prints one line: "ok   ...", "FAIL ...", or "xfail ..." for a known, reported bug.
"""

import sys
import traceback

sys.path.insert(0, __file__.rsplit("/", 1)[0])

import auth_checks  # noqa: E402
import cluster_checks  # noqa: E402
import conc_checks  # noqa: E402
import dur_checks  # noqa: E402
import fuzz  # noqa: E402
import http_checks  # noqa: E402
import query_checks  # noqa: E402
from common import out, wait_up  # noqa: E402

SETUP_PASSWORDS = [f"concurrent setup password {i:02d}" for i in range(12)]

STEPS = {
    "wait": lambda h: sys.exit(0 if wait_up(h) else 1),
    "http": http_checks.run,
    "json": http_checks.run_json,
    "json-verify": http_checks.verify_json,
    "basic": auth_checks.run_basic,
    "limit": auth_checks.run_limit,
    "setup": lambda h, token: auth_checks.run_setup(h, token, SETUP_PASSWORDS),
    "setup-limit": auth_checks.run_setup_limit,
    "password": auth_checks.run_password,
    "password-restart": auth_checks.run_password_after_restart,
    "query": query_checks.run,
    "conc": lambda h, s: conc_checks.run(h, int(s)),
    "save": conc_checks.save,
    "compare": conc_checks.compare_saved,
    "drop-all": conc_checks.drop_all,
    "write": lambda h, tag, n, d: dur_checks.write(h, tag, int(n), d),
    "verify-writes": dur_checks.verify,
    "fuzz": fuzz.run,
    "cl-ddl": lambda a, b, c, s: cluster_checks.ddl_race([a, b, c], int(s)),
    "cl-prep": lambda a, b, c: cluster_checks.prep([a, b, c]),
    "cl-split": cluster_checks.split,
    "cl-heal": lambda a, b, c, path: cluster_checks.after_heal([a, b, c], path),
    "restored": cluster_checks.restored,
    "writable": cluster_checks.wait_writable,
    "backup": cluster_checks.backup,
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in STEPS:
        out(f"usage: edge.py STEP ARGS (steps: {', '.join(STEPS)})")
        sys.exit(2)
    step = sys.argv[1]
    try:
        STEPS[step](*sys.argv[2:])
    except SystemExit:
        raise
    except BaseException as e:  # a crash in the client is a failed check, with where it happened
        tb = traceback.extract_tb(e.__traceback__)[-3:]
        where = " <- ".join(f"{f.name}:{f.lineno}" for f in reversed(tb))
        out(f"FAIL step {step} stopped: {type(e).__name__}: {str(e)[:500]} ({where})")
        sys.exit(3)


if __name__ == "__main__":
    main()
