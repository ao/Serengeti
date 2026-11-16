#!/usr/bin/env bash
# A node that missed writes catches up without running itself or its peers out of memory. Three nodes of the
# real image, 768 MiB each, on a private network (plaintext between them; nothing is published): 120,000
# rows of 1 KB, then one node is stopped while 6,000 more are written across both tables, and started again.
# A few thousand missed rows touch most of every table's buckets, so the restarted node has nearly all of
# each table to compare with each peer. Before the pull limit, the two other nodes ran out of heap within
# seconds of the restart (docs/SOAK.md, finding 3).
#
#   IMAGE=serengeti:catchup tests/memory/catchup.sh     (builds the image first unless SKIP_BUILD=1)
#   ROWS=60000 PAD=1000 MISS=3000 MEM=768m              the defaults: rows per table and their padding
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=${IMAGE:-serengeti:catchup}
ROWS=${ROWS:-60000}
PAD=${PAD:-1000}
MISS=${MISS:-3000}
MEM=${MEM:-768m}
RUN=cu$$
NET=$RUN-net
PW='catch up test password'
KEY='cluster key for the catch-up test, 32+ chars'
L=(--label "serengeti-catchup=$RUN")
passed=0
failed=0

cleanup() {
  docker ps -aq --filter "label=serengeti-catchup=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-catchup=$RUN" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi; }

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi
docker network create "${L[@]}" "$NET" >/dev/null
peers_of() { case $1 in 1) echo n2:1985,n3:1985 ;; 2) echo n1:1985,n3:1985 ;; 3) echo n1:1985,n2:1985 ;; esac; }
for i in 1 2 3; do
  docker volume create "${L[@]}" "$RUN-d$i" >/dev/null
  docker run -d -m "$MEM" "${L[@]}" --name "$RUN-n$i" --network "$NET" --network-alias "n$i" -v "$RUN-d$i:/data" \
    -e SERENGETI_PEERS="$(peers_of "$i")" -e SERENGETI_CLUSTER_KEY="$KEY" -e SERENGETI_CLUSTER_PLAINTEXT=allow \
    -e SERENGETI_ADMIN_PASSWORD="$PW" "$IMAGE" >/dev/null
done
docker run -d "${L[@]}" --name "$RUN-py" --network "$NET" --entrypoint sleep python:3.12-alpine 3600 >/dev/null
# The loader: INSERTs of 400 rows into d.a and d.b. A write retried after an error that may have applied it
# can find its rows there; that answer (400, "exists") counts as done.
docker exec -i "$RUN-py" sh -c 'cat > /load.py' <<'EOF'
import base64, json, sys, time, urllib.error, urllib.request
node, start, count, pad, pw = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
auth = "Basic " + base64.b64encode(("admin:" + pw).encode()).decode()
def q(s):
    for _ in range(60):
        try:
            r = urllib.request.Request("http://%s:1985/v1/query" % node, data=json.dumps({"query": s}).encode(),
                headers={"Content-Type": "application/json", "Authorization": auth})
            return json.load(urllib.request.urlopen(r, timeout=60))
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            if e.code == 400 and "exists" in text:
                return {}
            last = "%d %s" % (e.code, text)
        except OSError as e:
            last = str(e)
        time.sleep(1)
    sys.exit("gave up: " + last)
if start == 0:
    for s in ("CREATE DATABASE IF NOT EXISTS d", "CREATE TABLE IF NOT EXISTS d.a", "CREATE TABLE IF NOT EXISTS d.b"):
        q(s)
for b in range(start, start + count, 400):
    for table in ("a", "b"):
        rows = ", ".join("('%s%d', %d, '%s')" % (table, i, i, ("x%d" % i * pad)[:pad])
                         for i in range(b, min(start + count, b + 400)))
        q("INSERT INTO d.%s (_id, n, pad) VALUES %s" % (table, rows))
print(count * 2)
EOF
count() { # node table -> rows
  docker exec "$RUN-py" python -c '
import base64, json, sys, urllib.request
r = urllib.request.Request("http://%s:1985/v1/query" % sys.argv[1],
    data=json.dumps({"query": "SELECT COUNT(*) FROM d.%s" % sys.argv[2]}).encode(),
    headers={"Content-Type": "application/json",
             "Authorization": "Basic " + base64.b64encode(("admin:" + sys.argv[3]).encode()).decode()})
try:
    print(json.load(urllib.request.urlopen(r, timeout=10))["rows"][0][0])
except Exception as e:
    print("error")' "$1" "$2" "$PW"
}
for _ in $(seq 60); do [ "$(count n1 a 2>/dev/null)" = error ] || break; sleep 1; done
expect "load $((ROWS * 2)) rows of $PAD bytes through n1" "$((ROWS * 2))" \
  "$(docker exec "$RUN-py" python /load.py n1 0 "$ROWS" "$PAD" "$PW" 2>&1 | tail -1)"
docker stop -t 10 "$RUN-n3" >/dev/null
expect "write $((MISS * 2)) more while n3 is stopped" "$((MISS * 2))" \
  "$(docker exec "$RUN-py" python /load.py n1 "$ROWS" "$MISS" "$PAD" "$PW" 2>&1 | tail -1)"
docker start "$RUN-n3" >/dev/null
want=$((ROWS + MISS))
for _ in $(seq 120); do
  [ "$(count n3 a)" = "$want" ] && [ "$(count n3 b)" = "$want" ] && break
  sleep 1
done
expect "n3 catches up: both tables have every row" "$want $want" "$(count n3 a) $(count n3 b)"
for i in 1 2 3; do
  expect "n$i is still running" "running 0" \
    "$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$RUN-n$i")"
  expect "n$i logged no OutOfMemoryError" 0 "$(docker logs "$RUN-n$i" 2>&1 | grep -c "java.lang.OutOfMemoryError" || true)"
done
expect "n1 and n2 still have every row" "$want $want" "$(count n1 b) $(count n2 a)"

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
