#!/usr/bin/env bash
# Conformance and limits tests for the HTTP API and the query language (docs/API.md, docs/QUERY.md): edges of
# valid input, invalid requests that must get a clear 4xx, concurrent statements on the same objects,
# restarts and kill -9 during writes, backup and restore, and a few thousand generated statements.
#
# Every container runs on a private Docker network made for this run; nothing is published to the host. The
# client is a python:3.12-alpine container running tests/edge/*.py (standard library only). One line per
# check; "xfail" lines are known bugs listed in tests/edge/common.py (EDGE_STRICT=1 counts them as failures).
#
#   IMAGE=serengeti:edge tests/edge/run.sh        (builds the image first unless SKIP_BUILD=1)
#   FUZZ_SEED=123 FUZZ_N=5000 CONC_SECONDS=8 KILL_ROUNDS=3 tests/edge/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=${IMAGE:-serengeti:edge}
CLIENT_IMAGE=${CLIENT_IMAGE:-python:3.12-alpine}
RUN=edge$$
NET=$RUN-net
PW='edge test password 1'
KEY='edge suite cluster key, at least 32 characters'
CONC_SECONDS=${CONC_SECONDS:-8}
KILL_ROUNDS=${KILL_ROUNDS:-3}
L=(--label "serengeti-edge=$RUN")
OUT=$(mktemp -d)
passed=0
failed=0
xfailed=0
started=$(date +%s)

cleanup() {
  docker ps -aq --filter "label=serengeti-edge=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-edge=$RUN" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=serengeti-edge=$RUN" | xargs -r docker network rm >/dev/null 2>&1 || true
  rm -rf "$OUT"
}
trap cleanup EXIT

ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi; }

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi
docker network create "${L[@]}" "$NET" >/dev/null
docker run -d -m 512m "${L[@]}" --name "$RUN-client" --network "$NET" --entrypoint sleep "$CLIENT_IMAGE" 7200 >/dev/null
docker cp tests/edge/. "$RUN-client:/edge" >/dev/null

# Counts the check lines a client step prints. "value X" lines hand a value back to this script.
value=
count_lines() { # step-name < output (ends with "__rc N")
  local line rc=0
  while IFS= read -r line; do
    case $line in
      "__rc "*) rc=${line#__rc } ;;
      "value "*) value=${line#value } ;;
      "ok   "*) passed=$((passed + 1)); echo "$line" ;;
      "FAIL "* | "XPASS "*) failed=$((failed + 1)); echo "$line" ;;
      "xfail "*) xfailed=$((xfailed + 1)); echo "$line" ;;
      *) echo "$line" ;;
    esac
  done
  # 3 means the step stopped and already printed a FAIL line.
  if [ "$rc" != 0 ] && [ "$rc" != 3 ]; then bad "client step $1" "exited $rc"; fi
}
client_env=(-e PW="$PW" -e FUZZ_SEED="${FUZZ_SEED:-}" -e FUZZ_N="${FUZZ_N:-}" -e EDGE_STRICT="${EDGE_STRICT:-0}")
py() { # step args...: runs one client step and counts its checks
  count_lines "$1" < <(docker exec "${client_env[@]}" "$RUN-client" python3 -u /edge/edge.py "$@" 2>&1; echo "__rc $?")
}
declare -A BG
py_bg() { # tag step args...: the same, in the background; py_join collects it
  local tag=$1
  shift
  (docker exec "${client_env[@]}" "$RUN-client" python3 -u /edge/edge.py "$@" > "$OUT/$tag" 2>&1
   echo "__rc $?" >> "$OUT/$tag") &
  BG[$tag]=$!
}
py_join() { wait "${BG[$1]}" || true; count_lines "$1" < "$OUT/$1"; }

volume() { docker volume create "${L[@]}" "$RUN-$1" >/dev/null; echo "$RUN-$1"; }
node() { # name [docker run args...]
  local name=$1
  shift
  docker run -d -m 512m "${L[@]}" --name "$RUN-$name" --network "$NET" --network-alias "$name" "$@" "$IMAGE" >/dev/null
}
up() { # node...
  local n
  for n in "$@"; do
    if ! docker exec "$RUN-client" python3 /edge/edge.py wait "$n"; then
      bad "node $n answers /health" "it did not; last log lines:"
      docker logs "$RUN-$n" 2>&1 | tail -20
      return 1
    fi
  done
}
gone() { # node...: checks the JVM was never killed for memory, then removes the node
  local n
  for n in "$@"; do
    expect "node $n is still running, never killed for memory" "true false" \
      "$(docker inspect -f '{{.State.Running}} {{.State.OOMKilled}}' "$RUN-$n")"
    docker rm -f "$RUN-$n" >/dev/null
  done
}
token_of() { # node: waits for the setup token file and prints it
  local t=
  for _ in $(seq 1 60); do
    t=$(docker exec "$RUN-$1" cat /data/setup-token 2>/dev/null | tr -d '\n' || true)
    [ -n "$t" ] && break
    sleep 0.5
  done
  echo "$t"
}
no_500_in_log() { # node label: the node logged no request that ended in a 500
  local n
  n=$(docker logs "$RUN-$1" 2>&1 | grep -c 'status=500' || true)
  expect "$2: the request log has no status=500" 0 "$n"
  if [ "$n" != 0 ]; then docker logs "$RUN-$1" 2>&1 | grep -B2 -A12 'SEVERE' | head -40; fi
}

echo "# node a: HTTP, JSON, the query language, generated statements"
node a -v "$(volume a):/data" -e SERENGETI_ADMIN_PASSWORD="$PW"
up a
py http a
py json a
docker restart "$RUN-a" >/dev/null
up a
py json-verify a
py query a
py fuzz a
no_500_in_log a "node a"
gone a

echo "# credentials, the failed-login limit, the setup token, password changes"
node basic -e SERENGETI_ADMIN_PASSWORD="$PW"
node limit -e SERENGETI_ADMIN_PASSWORD="$PW"
node setup -v "$(volume setup):/data"
node slimit
node pw -v "$(volume pw):/data" -e SERENGETI_ADMIN_PASSWORD="$PW"
up basic limit setup slimit pw
# The two limit steps wait out a one-minute window each; run them beside the others.
py_bg limit limit limit
py_bg slimit setup-limit slimit "$(token_of slimit)"
py basic basic
token=$(token_of setup)
expect "a setup token was written" 43 "${#token}"
docker restart "$RUN-setup" >/dev/null
up setup
expect "the setup token is the same after a restart" "$token" "$(token_of setup)"
py setup setup "$token"
expect "the setup token file is deleted after setup" gone \
  "$(docker exec "$RUN-setup" sh -c 'test -e /data/setup-token && echo there || echo gone')"
py password pw "$PW"
newpw=$value
docker restart "$RUN-pw" >/dev/null
up pw
py password-restart pw "$newpw" "$PW"
py_join limit
py_join slimit
for n in basic limit setup slimit pw; do no_500_in_log "$n" "node $n"; done
gone basic limit setup slimit pw

echo "# node b: concurrent DDL and DML on the same objects, then restarts"
node b -v "$(volume b):/data" -e SERENGETI_ADMIN_PASSWORD="$PW"
up b
py conc b "$CONC_SECONDS"
py save b /tmp/b-state.json
docker restart "$RUN-b" >/dev/null
up b
py compare b /tmp/b-state.json "after a restart"
docker kill -s KILL "$RUN-b" >/dev/null
docker start "$RUN-b" >/dev/null
up b
py compare b /tmp/b-state.json "after kill -9 with no write in flight"
py drop-all b
no_500_in_log b "node b"
gone b

echo "# node k: a restart and kill -9 during writes from 8 clients, $KILL_ROUNDS rounds"
node k -v "$(volume k):/data" -e SERENGETI_ADMIN_PASSWORD="$PW"
up k
for round in $(seq 1 "$KILL_ROUNDS"); do
  docker exec -d "${client_env[@]}" "$RUN-client" python3 /edge/edge.py write k "r$round" 8 /tmp/wlog
  sleep "$((2 + RANDOM % 3)).$((RANDOM % 10))"
  if [ "$round" = 1 ]; then
    label="a restart (SIGTERM) during writes"
    docker restart -t 10 "$RUN-k" >/dev/null
  else
    label="kill -9 during writes, round $round"
    docker kill -s KILL "$RUN-k" >/dev/null
    sleep 1
    docker start "$RUN-k" >/dev/null
  fi
  up k
  for _ in $(seq 1 80); do
    docker exec "$RUN-client" pgrep -f "edge.py write" >/dev/null || break
    sleep 0.5
  done
  py verify-writes k /tmp/wlog "$label"
done
no_500_in_log k "node k"
gone k

echo "# three nodes: DDL races through different nodes, conflicts across a partition, backup and restore"
peers_of() { case $1 in 1) echo n2:1985,n3:1985 ;; 2) echo n1:1985,n3:1985 ;; 3) echo n1:1985,n2:1985 ;; esac; }
for i in 1 2 3; do
  node "n$i" -v "$(volume "n$i"):/data" -e SERENGETI_ADMIN_PASSWORD="$PW" -e SERENGETI_PEERS="$(peers_of "$i")" \
    -e SERENGETI_CLUSTER_KEY="$KEY" -e SERENGETI_CLUSTER_PLAINTEXT=allow
done
up n1 n2 n3
for i in 1 2 3; do py writable "n$i"; done
py cl-ddl n1 n2 n3 "$CONC_SECONDS"
py cl-prep n1 n2 n3
# Drop every packet between n3 and its peers inside n3's network namespace (see tests/cluster/run.sh); the
# client can still reach n3.
docker build -q -t serengeti-netfault - >/dev/null <<'DOCKERFILE'
FROM alpine:3.20
RUN apk add --no-cache iptables
DOCKERFILE
peer_ips=$(for i in 1 2; do docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' "$RUN-n$i"; done)
netfault() { # cut|heal [peer ips]
  docker run --rm "${L[@]}" --network "container:$RUN-n3" --cap-add NET_ADMIN serengeti-netfault sh -c '
    set -e
    if [ "$1" = heal ]; then iptables -F INPUT; iptables -F OUTPUT; exit 0; fi
    shift
    for ip in "$@"; do iptables -A INPUT -s "$ip" -j DROP; iptables -A OUTPUT -d "$ip" -j DROP; done' sh "$@"
}
# shellcheck disable=SC2086
netfault cut $peer_ips
sleep 5
py cl-split n1 n3
netfault heal
py cl-heal n1 n2 n3 /tmp/cl.json
py backup n1 /tmp/backup.jsonl
restored=$(volume restored)
rc=0
docker exec "$RUN-client" cat /tmp/backup.jsonl | docker run --rm -i -m 512m "${L[@]}" -v "$restored:/data" \
  --entrypoint sh "$IMAGE" -c 'cat > /tmp/in && exec java -jar /opt/serengeti/serengeti.jar restore /tmp/in' \
  >/dev/null 2>&1 || rc=$?
expect "serengeti restore of the backup exits 0" 0 "$rc"
node restored -v "$restored:/data" -e SERENGETI_ADMIN_PASSWORD="$PW"
up restored
py restored restored /tmp/cl.json
for n in n1 n2 n3 restored; do no_500_in_log "$n" "node $n"; done
gone n1 n2 n3 restored

echo
echo "$passed passed, $failed failed, $xfailed known bugs (xfail) in $(( $(date +%s) - started )) s"
[ "$failed" = 0 ]
