#!/usr/bin/env bash
# Slow clients against one node, while a probe measures whether it still answers.
#
# For each mode, number of connections N and number of attacker addresses, a fresh node (1 GiB) starts on a
# private Docker network made for this run; nothing is published to the host. N connections from one
# container, or split across several (one address each), then hold the node as tests/slow/slow.py describes:
# "headers" (headers one byte a second), "body" (a body one byte a second), "noread" (signed in, a 4 MB
# answer never read). After a 10 s ramp a probe container asks GET /health, GET /v1/status and a small
# POST /v1/query, each on a new connection, for SECONDS, and prints success rate and latency per route.
#
#   IMAGE=serengeti:slow tests/slow/run.sh             (builds the image first unless SKIP_BUILD=1)
#   MODES="headers body noread" NS="64 256 1000" SPREADS="1 4" SECONDS_=60 TLS=0 tests/slow/run.sh
#   QUICK=1 tests/slow/run.sh   (for CI: N=1000 from 4 addresses, 20 s a mode; fails if a probe route
#                                answers under MIN_OK percent (99) or its p99 is over MAX_P99_MS (2000))
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=${IMAGE:-serengeti:slow}
CLIENT_IMAGE=${CLIENT_IMAGE:-python:3.12-alpine}
RUN=slow$$
NET=$RUN-net
PW='slow test password 1'
L=(--label "serengeti-slow=$RUN")
if [ "${QUICK:-0}" = 1 ]; then
  MODES=${MODES:-headers body noread} NS=${NS:-1000} SPREADS=${SPREADS:-4} SECONDS_=${SECONDS_:-20}
  CHECK=1
else
  MODES=${MODES:-headers body noread} NS=${NS:-64 256 1000} SPREADS=${SPREADS:-1 4} SECONDS_=${SECONDS_:-60}
  CHECK=${CHECK:-0}
fi
MIN_OK=${MIN_OK:-99}
MAX_P99_MS=${MAX_P99_MS:-2000}
TLS=${TLS:-0}
NODE_MEM=${NODE_MEM:-1g}
NODE_ENV=${NODE_ENV:-}
RAMP=10
failed=0

cleanup() {
  docker ps -aq --filter "label=serengeti-slow=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-slow=$RUN" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=serengeti-slow=$RUN" | xargs -r docker network rm >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi
docker network create --internal "${L[@]}" "$NET" >/dev/null
T=(-v "$PWD/tests/slow:/t:ro")
docker run -d "${L[@]}" --name "$RUN-probe" --network "$NET" -m 512m "${T[@]}" "$CLIENT_IMAGE" sleep 86400 >/dev/null

TLSARGS=()
CA=()
if [ "$TLS" = 1 ]; then
  docker volume create "${L[@]}" "$RUN-certs" >/dev/null
  docker run --rm -m 512m "${L[@]}" -v "$RUN-certs:/certs" --user 0 --entrypoint sh "$IMAGE" -c '
    set -e; cd /certs
    keytool -genkeypair -alias node -keyalg EC -groupname secp256r1 -dname CN=node -ext san=dns:node \
      -validity 2 -storetype PKCS12 -keystore node.p12 -storepass changeit >/dev/null 2>&1
    keytool -exportcert -rfc -alias node -keystore node.p12 -storepass changeit -file node.pem >/dev/null 2>&1
    printf changeit > pw
    chown 10001:10001 node.p12 pw && chmod 600 node.p12 pw && chmod 644 node.pem'
  TLSARGS=(-v "$RUN-certs:/certs:ro" -e SERENGETI_TLS_KEYSTORE=/certs/node.p12
    -e SERENGETI_TLS_KEYSTORE_PASSWORD_FILE=/certs/pw)
  docker run --rm "${L[@]}" -v "$RUN-certs:/certs" --entrypoint cat "$IMAGE" /certs/node.pem \
    | docker exec -i "$RUN-probe" sh -c 'cat > /tmp/node.pem'
  CA=(--ca /tmp/node.pem)
fi

ENVARGS=()
for kv in $NODE_ENV; do ENVARGS+=(-e "$kv"); done

# One line per sample while the probe runs: the node's memory (cgroup) and its thread count.
sample_node() {
  local id; id=$(docker inspect -f '{{.Id}}' "$RUN-node")
  local f=/sys/fs/cgroup/system.slice/docker-$id.scope/memory.current
  while sleep 5; do
    local mem threads
    mem=$(cat "$f" 2>/dev/null || echo 0)
    threads=$(docker exec "$RUN-node" sh -c 'grep Threads /proc/1/status' 2>/dev/null | awk '{print $2}')
    echo "$((mem / 1048576)) ${threads:-0}"
  done
}

printf '%-8s %5s %5s | %-30s | %-30s | %-30s | %s\n' mode N addrs "health ok% p50 p99 (ms)" \
  "status ok% p50 p99 (ms)" "query ok% p50 p99 (ms)" "node MiB/threads max, attackers"
for mode in $MODES; do
  for n in $NS; do
    for spread in $SPREADS; do
      docker rm -f "$RUN-node" >/dev/null 2>&1 || true
      docker run -d "${L[@]}" --name "$RUN-node" --network "$NET" --network-alias node -m "$NODE_MEM" \
        -e SERENGETI_ADMIN_PASSWORD="$PW" "${TLSARGS[@]}" "${ENVARGS[@]}" "$IMAGE" >/dev/null
      docker exec "$RUN-probe" python3 /t/slow.py seed "${CA[@]}" >/dev/null
      per=$((n / spread))
      for a in $(seq 1 "$spread"); do
        docker run -d "${L[@]}" --name "$RUN-attack$a" --network "$NET" -m 512m --ulimit nofile=8192:8192 \
          "${T[@]}" "$CLIENT_IMAGE" python3 /t/slow.py attack --mode "$mode" --n "$per" \
          --seconds $((SECONDS_ + RAMP + 5)) "${CA[@]}" >/dev/null
      done
      sleep "$RAMP"
      sample_node > "/tmp/$RUN-samples" &
      sampler=$!
      result=$(docker exec "$RUN-probe" python3 /t/slow.py probe --seconds "$SECONDS_" "${CA[@]}")
      kill "$sampler" 2>/dev/null || true
      wait "$sampler" 2>/dev/null || true
      peak=$(awk 'BEGIN {m = 0; t = 0} {if ($1 > m) m = $1; if ($2 > t) t = $2} END {print m "/" t}' "/tmp/$RUN-samples")
      rm -f "/tmp/$RUN-samples"
      held=0
      for a in $(seq 1 "$spread"); do
        line=$(docker logs "$RUN-attack$a" 2>/dev/null | tail -1)
        held=$((held + $(echo "$line" | sed -n 's/.*"max_open": \([0-9]*\).*/\1/p')))
        docker rm -f "$RUN-attack$a" >/dev/null 2>&1 || true
      done
      row=$(echo "$result" | docker exec -i "$RUN-probe" python3 /t/slow.py row 2>/dev/null || echo "$result")
      printf '%-8s %5s %5s | %s | %s, %s open at most\n' "$mode" "$n" "$spread" "$row" "$peak" "$held"
      echo "# $mode n=$n addrs=$spread $result" >&2
      if [ "$CHECK" = 1 ]; then
        bad=$(echo "$result" | docker exec -i "$RUN-probe" python3 /t/slow.py check --min-ok "$MIN_OK" \
          --max-p99 "$MAX_P99_MS" || echo "unreadable")
        if [ -n "$bad" ]; then
          echo "FAIL $mode n=$n addrs=$spread: $bad below ${MIN_OK}% or p99 over ${MAX_P99_MS} ms"
          failed=$((failed + 1))
        fi
      fi
    done
  done
done
docker rm -f "$RUN-node" >/dev/null 2>&1 || true
[ "$failed" = 0 ]
