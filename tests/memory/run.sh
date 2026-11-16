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
docker cp tests/memory/small-rows.awk "$RUN-client:/tmp/small-rows.awk" >/dev/null
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

echo "# a backup and a restart while full"
bk=$(docker exec "$RUN-client" curl -s -o /tmp/backup.jsonl -w '%{http_code} %{size_download}' --max-time 120 \
  -u "admin:$PW" http://node:1985/v1/backup || echo 000)
expect "a backup is taken while full" 200 "${bk%% *}"
echo "  backup of ${bk#* } bytes"
expect "and the node is still running" true "$(docker inspect -f '{{.State.Running}}' "$RUN-node")"
restart_and_count() { # table expected-rows
  docker restart -t 30 "$RUN-node" >/dev/null
  local up=0
  for _ in $(seq 120); do [ "$(q 'SHOW DATABASES')" = 200 ] && { up=1; break; }; sleep 1; done
  expect "it starts again with its data" 1 "$up"
  expect "and was not killed for memory" false "$(docker inspect -f '{{.State.OOMKilled}}' "$RUN-node")"
  q "SELECT COUNT(*) FROM $1" >/dev/null
  expect "with every row" "$2" "$(body | sed -n 's/.*"rows":\[\[\([0-9]*\)\]\].*/\1/p')"
}
restart_and_count d.t "$((n * 200))"

echo "# make room"
expect "deleting every row is taken while full" 200 "$(q 'DELETE FROM d.t')"
expect "the node is still running after it" true "$(docker inspect -f '{{.State.Running}}' "$RUN-node")"
expect "and the table is empty" 200 "$(q 'SELECT COUNT(*) FROM d.t')"
case "$(body)" in *'"rows":[[0]]'*) ok "with no rows left" ;; *) bad "rows left" "$(body)" ;; esac
expect "a write is taken again at once" 200 "$(q "INSERT INTO d.t (p) VALUES ('after')")"
expect "and the node is still running" true "$(docker inspect -f '{{.State.Running}}' "$RUN-node")"
expect "dropping the table" 200 "$(q 'DROP TABLE d.t')"

# Small rows with two indexes: per row, the overhead of the maps, the version and the index entries is most of
# what a row takes, so this checks the estimate where it is least exact.
echo "# small rows with two indexes, until the node refuses"
expect "create the table" 200 "$(q 'CREATE TABLE d.small')"
expect "index one column" 200 "$(q 'CREATE INDEX ON d.small (n)')"
expect "index another" 200 "$(q 'CREATE INDEX ON d.small (s)')"
code=200; batches=0; start=$SECONDS
while [ "$code" = 200 ] && [ "$batches" -lt 2000 ]; do
  docker exec "$RUN-client" sh -c 'awk -v b="$1" -f /tmp/small-rows.awk > /tmp/small.json' sh "$batches"
  code=$(docker exec "$RUN-client" curl -s -o /tmp/body -w '%{http_code}' --max-time 60 -u "admin:$PW" \
    -H 'Content-Type: application/json' --data-binary @/tmp/small.json http://node:1985/v1/query || echo 000)
  [ "$code" = 200 ] && batches=$((batches + 1))
  if [ $((batches % 50)) = 0 ] && [ "$code" = 200 ]; then
    echo "  after $((batches * 2000)) rows: $(docker exec "$RUN-client" curl -s -u "admin:$PW" http://node:1985/metrics | grep -E '^serengeti_(data_bytes|heap_bytes)' | tr '\n' ' ') container $(docker stats --no-stream --format '{{.MemUsage}}' "$RUN-node")"
  fi
done
echo "  $((batches * 2000)) rows in $((SECONDS - start)) s, then $code: $(body | cut -c1-160)"
expect "a write that adds data is refused with 503" 503 "$code"
expect "the node is still running" true "$(docker inspect -f '{{.State.Running}}' "$RUN-node")"
expect "and was not killed for memory" false "$(docker inspect -f '{{.State.OOMKilled}}' "$RUN-node")"
expect "an indexed read works while full" 200 "$(q 'SELECT COUNT(*) FROM d.small WHERE n = 7')"
expect "and finds its row" '"rows":[[1]]' "$(body | grep -o '"rows":\[\[[0-9]*\]\]')"
m=$(docker exec "$RUN-client" curl -s -u "admin:$PW" http://node:1985/metrics)
echo "  estimated data $(echo "$m" | sed -n 's/^serengeti_data_bytes \(.*\)$/\1/p') of $(echo "$m" | sed -n 's/^serengeti_data_budget_bytes \(.*\)$/\1/p'); heap $(echo "$m" | grep '^serengeti_heap_bytes' | tr '\n' ' ')"
restart_and_count d.small "$((batches * 2000))"
expect "deleting them is taken" 200 "$(q 'DELETE FROM d.small')"
expect "and the node is still running" true "$(docker inspect -f '{{.State.Running}}' "$RUN-node")"
# The delete is one log record of every row (tens of MB), and the log is not folded into a snapshot until it
# reaches 64 MiB: the next start replays that record, which must not need several times its size.
echo "# a restart right after the delete"
restart_and_count d.small 0

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
