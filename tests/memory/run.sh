#!/usr/bin/env bash
# Fill a node's memory through its API and check that it refuses writes that add data with 503 instead of
# running out of memory, keeps answering reads, takes deletes, and takes writes again once room is made.
# One node of the real image with a 512 MiB memory limit on a private network; nothing is published.
#
#   IMAGE=serengeti:mem tests/memory/run.sh        (builds the image first unless SKIP_BUILD=1)
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=${IMAGE:-serengeti:mem}
RUN=mem$$
PW='memory test password'
passed=0
failed=0
L=(--label "serengeti-mem=$RUN")

cleanup() {
  docker ps -aq --filter "label=serengeti-mem=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker network ls -q --filter "label=serengeti-mem=$RUN" | xargs -r docker network rm >/dev/null 2>&1 || true
}
trap cleanup EXIT
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi; }

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi
docker network create "${L[@]}" "$RUN" >/dev/null
docker run -d "${L[@]}" --name "$RUN-client" --network "$RUN" --entrypoint sleep curlimages/curl:8.11.1 3600 >/dev/null
docker run -d -m 512m "${L[@]}" --name "$RUN-node" --network "$RUN" --network-alias node \
  -e SERENGETI_ADMIN_PASSWORD="$PW" "$IMAGE" >/dev/null

q() { # query -> status; body in /tmp/body in the client
  docker exec "$RUN-client" curl -s -o /tmp/body -w '%{http_code}' --max-time 30 -u "admin:$PW" \
    -H 'Content-Type: application/json' --data-binary "{\"query\": \"$1\"}" http://node:1985/v1/query || true
}
body() { docker exec "$RUN-client" cat /tmp/body; }
for _ in $(seq 60); do [ "$(q 'SHOW DATABASES')" = 200 ] && break; sleep 1; done
expect "create database" 200 "$(q 'CREATE DATABASE d')"
expect "create table" 200 "$(q 'CREATE TABLE d.t')"

# One request inserts 200 rows of 2,000 characters each (about 400 KB, under the 1 MiB body limit).
docker exec "$RUN-client" sh -c '
  p=$(printf "%2000s" "" | tr " " x); s=$(printf "\047")
  v=""; for i in $(seq 200); do v="$v($s$p$s),"; done
  printf "{\"query\": \"INSERT INTO d.t (p) VALUES %s\"}" "${v%,}" > /tmp/insert.json'

echo "# insert until the node refuses"
code=200; n=0; start=$SECONDS
while [ "$code" = 200 ] && [ "$n" -lt 3000 ]; do
  code=$(docker exec "$RUN-client" curl -s -o /tmp/body -w '%{http_code}' --max-time 60 -u "admin:$PW" \
    -H 'Content-Type: application/json' --data-binary @/tmp/insert.json http://node:1985/v1/query || echo 000)
  [ "$code" = 200 ] && n=$((n + 1))
  if [ $((n % 50)) = 0 ]; then
    echo "  after $n: $(docker exec "$RUN-client" curl -s -u "admin:$PW" http://node:1985/metrics | grep -E '^serengeti_(data_bytes|heap_bytes)' | tr '\n' ' ') container $(docker stats --no-stream --format '{{.MemUsage}}' "$RUN-node")"
  fi
done
echo "  $n inserts of 200 rows taken in $((SECONDS - start)) s, then $code: $(body | cut -c1-200)"
expect "a write that adds data is refused with 503" 503 "$code"
case "$(body)" in *"memory_limit_percent"*) ok "and says the memory limit is reached" ;; *) bad "refusal message" "$(body)" ;; esac
expect "the node is still running" true "$(docker inspect -f '{{.State.Running}}' "$RUN-node")"
expect "and was not killed for memory" false "$(docker inspect -f '{{.State.OOMKilled}}' "$RUN-node")"
expect "reads still work" 200 "$(q 'SELECT COUNT(*) FROM d.t')"
rows=$(body | sed -n 's/.*"rows":\[\[\([0-9]*\)\]\].*/\1/p')
expect "every acknowledged insert is there" "$((n * 200))" "$rows"
expect "health still answers" 200 "$(docker exec "$RUN-client" curl -s -o /dev/null -w '%{http_code}' http://node:1985/health)"
docker exec "$RUN-client" curl -s -u "admin:$PW" http://node:1985/metrics > /dev/null
full=$(docker exec "$RUN-client" curl -s -u "admin:$PW" http://node:1985/metrics | sed -n 's/^serengeti_memory_full \([0-9]*\)$/\1/p')
expect "the serengeti_memory_full metric is 1" 1 "$full"
m=$(docker exec "$RUN-client" curl -s -u "admin:$PW" http://node:1985/metrics)
echo "  estimated data $(echo "$m" | sed -n 's/^serengeti_data_bytes \(.*\)$/\1/p') of a budget of $(echo "$m" | sed -n 's/^serengeti_data_budget_bytes \(.*\)$/\1/p') bytes; container memory $(docker stats --no-stream --format '{{.MemUsage}}' "$RUN-node")"

echo "# make room"
expect "deleting every row is taken while full" 200 "$(q 'DELETE FROM d.t')"
expect "the node is still running after it" true "$(docker inspect -f '{{.State.Running}}' "$RUN-node")"
expect "and the table is empty" 200 "$(q 'SELECT COUNT(*) FROM d.t')"
case "$(body)" in *'"rows":[[0]]'*) ok "with no rows left" ;; *) bad "rows left" "$(body)" ;; esac
expect "a write is taken again at once" 200 "$(q "INSERT INTO d.t (p) VALUES ('after')")"
expect "and the node is still running" true "$(docker inspect -f '{{.State.Running}}' "$RUN-node")"

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
