#!/usr/bin/env bash
# Fault tests for a 3-node cluster of the real image, over TLS, in Docker. Nothing is published to the host.
#
# Networks: the nodes share one "cluster" network. The client reaches node nX over its own network
# (name cX), which no other node is on, so disconnecting a node from the cluster network partitions it
# from its peers while the client can still talk to it.
#
# A writer inserts rows round-robin through every scenario and records each insert that got a 200. At the
# end, every node must have every recorded insert.
#
#   IMAGE=serengeti:cluster tests/cluster/run.sh       (builds the image first unless SKIP_BUILD=1)
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=${IMAGE:-serengeti:cluster}
RUN=cl$$
NET=$RUN-cluster
PW='cluster test password'
KEY='cluster key for the fault tests, 32+ chars'
passed=0
failed=0

cleanup() {
  docker ps -aq --filter "label=serengeti-cl=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-cl=$RUN" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=serengeti-cl=$RUN" | xargs -r docker network rm >/dev/null 2>&1 || true
}
trap cleanup EXIT

ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi; }
L=(--label "serengeti-cl=$RUN")

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi
docker network create "${L[@]}" "$NET" >/dev/null
for i in 1 2 3; do docker network create "${L[@]}" "$RUN-c$i" >/dev/null; done
docker run -d "${L[@]}" --name "$RUN-client" --network "$RUN-c1" --entrypoint sleep curlimages/curl:8.11.1 3600 >/dev/null
docker network connect "$RUN-c2" "$RUN-client"
docker network connect "$RUN-c3" "$RUN-client"

echo "# certificates: one CA, one key store per node naming nX and cX"
certs=$RUN-certs
docker volume create "${L[@]}" "$certs" >/dev/null
docker run --rm "${L[@]}" -v "$certs:/certs" --user 0 --entrypoint sh "$IMAGE" -c '
  set -e; cd /certs; P=changeit
  k() { keytool "$@" >/dev/null 2>&1; }
  k -genkeypair -alias ca -keyalg EC -groupname secp256r1 -dname "CN=test CA" -ext bc:c -validity 2 \
    -storetype PKCS12 -keystore ca.p12 -storepass $P
  k -exportcert -rfc -alias ca -keystore ca.p12 -storepass $P -file ca.pem
  k -importcert -noprompt -alias ca -file ca.pem -storetype PKCS12 -keystore trust.p12 -storepass $P
  for i in 1 2 3; do
    k -genkeypair -alias node -keyalg EC -groupname secp256r1 -dname "CN=n$i" -validity 2 \
      -storetype PKCS12 -keystore n$i.p12 -storepass $P
    k -certreq -alias node -keystore n$i.p12 -storepass $P -file n$i.csr
    k -gencert -rfc -alias ca -keystore ca.p12 -storepass $P -infile n$i.csr -outfile n$i.pem -validity 2 \
      -ext san=dns:n$i,dns:c$i -ext eku=serverAuth,clientAuth
    k -importcert -noprompt -alias ca -file ca.pem -keystore n$i.p12 -storepass $P
    k -importcert -noprompt -alias node -file n$i.pem -keystore n$i.p12 -storepass $P
  done
  printf %s $P > pw
  chown 10001:10001 *.p12 pw; chmod 600 *.p12 pw; chmod 644 ca.pem'
docker run --rm "${L[@]}" -v "$certs:/certs" --entrypoint cat "$IMAGE" /certs/ca.pem \
  | docker exec -i "$RUN-client" sh -c 'cat > /tmp/ca.pem'

peers_of() { case $1 in 1) echo n2:1985,n3:1985 ;; 2) echo n1:1985,n3:1985 ;; 3) echo n1:1985,n2:1985 ;; esac; }

node() { # i [extra docker args]
  local i=$1
  shift
  docker volume create "${L[@]}" "$RUN-data$i" >/dev/null
  docker run -d -m 512m "${L[@]}" --name "$RUN-n$i" --network "$NET" --network-alias "n$i" \
    -v "$RUN-data$i:/data" -v "$certs:/certs:ro" \
    -e SERENGETI_PEERS="$(peers_of "$i")" -e SERENGETI_CLUSTER_KEY="$KEY" \
    -e SERENGETI_TLS_KEYSTORE=/certs/n$i.p12 -e SERENGETI_TLS_KEYSTORE_PASSWORD_FILE=/certs/pw \
    -e SERENGETI_TLS_TRUSTSTORE=/certs/trust.p12 -e SERENGETI_TLS_TRUSTSTORE_PASSWORD_FILE=/certs/pw \
    -e SERENGETI_ADMIN_PASSWORD="$PW" "$@" "$IMAGE" >/dev/null
  docker network connect --alias "c$i" "$RUN-c$i" "$RUN-n$i"
}

# q i query [consistency] -> status code; body in /tmp/body in the client
q() {
  docker exec "$RUN-client" curl -s -o /tmp/body -w '%{http_code}' --max-time 20 --cacert /tmp/ca.pem \
    -u "admin:$PW" -H 'Content-Type: application/json' \
    --data-binary "{\"query\": \"$2\", \"consistency\": \"${3:-local}\"}" "https://c$1:1985/v1/query" || true
}
body() { docker exec "$RUN-client" cat /tmp/body; }

caught_up() { # i -> 0 if the node reports it is caught up and has a majority reachable
  docker exec "$RUN-client" curl -s --max-time 5 --cacert /tmp/ca.pem -u "admin:$PW" "https://c$1:1985/v1/status" \
    | grep -q '"caught_up":true' 2>/dev/null
}

wait_writable() { # i
  for _ in $(seq 1 120); do
    if caught_up "$1" && [ "$(q "$1" 'SHOW DATABASES')" = 200 ]; then
      st=$(docker exec "$RUN-client" curl -s --max-time 5 --cacert /tmp/ca.pem -u "admin:$PW" "https://c$1:1985/v1/status")
      case "$st" in *'"reachable":1,'*) ;; *) return 0 ;; esac
    fi
    sleep 0.5
  done
  echo "node $1 never became writable"; docker logs "$RUN-n$1" 2>&1 | tail -8
  return 1
}

count() { # i -> number of rows in d.t on node i
  q "$1" 'SELECT COUNT(*) FROM d.t' >/dev/null
  body | sed -n 's/.*"rows":\[\[\([0-9]*\)\]\].*/\1/p'
}

converged() { # all three nodes report the same count, and it is the given number if one is passed
  local a b c
  a=$(count 1); b=$(count 2); c=$(count 3)
  [ -n "$a" ] && [ "$a" = "$b" ] && [ "$b" = "$c" ] && { [ -z "${1:-}" ] || [ "$a" = "$1" ]; }
}

wait_converged() {
  for _ in $(seq 1 120); do converged && return 0; sleep 0.5; done
  echo "counts: $(count 1) $(count 2) $(count 3)"
  return 1
}

writer_run=0
start_writer() { # appends to /tmp/acked; ids are w<run>-<n> so runs never collide
  writer_run=$((writer_run + 1))
  docker exec "$RUN-client" sh -c 'rm -f /tmp/stop /tmp/stopped; touch /tmp/acked'
  docker exec -d "$RUN-client" sh -c '
    i=0
    while [ ! -e /tmp/stop ]; do
      i=$((i + 1)); n=$(( (i % 3) + 1 )); id="w$2-$i"
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 --cacert /tmp/ca.pem -u "$1" \
        -H "Content-Type: application/json" \
        --data-binary "{\"query\": \"INSERT INTO d.t (_id, n) VALUES ('"'"'$id'"'"', $i)\"}" "https://c$n:1985/v1/query")
      [ "$code" = 200 ] && echo "$id" >> /tmp/acked
    done; echo done > /tmp/stopped' sh "admin:$PW" "$writer_run"
}
stop_writer() {
  docker exec "$RUN-client" sh -c 'touch /tmp/stop; for i in $(seq 1 60); do [ -e /tmp/stopped ] && break; sleep 0.5; done'
}

echo "# a node with peers and neither TLS nor the plaintext opt-in refuses to start"
docker run -d "${L[@]}" --name "$RUN-plain" --network "$NET" -e SERENGETI_PEERS=n1:1985 \
  -e SERENGETI_CLUSTER_KEY="$KEY" -e SERENGETI_ADMIN_PASSWORD="$PW" "$IMAGE" >/dev/null
expect "plaintext cluster guard exits 2" 2 "$(docker wait "$RUN-plain")"

echo "# start three nodes"
for i in 1 2 3; do node "$i"; done
for i in 1 2 3; do wait_writable "$i"; done
ok "all three nodes caught up"
expect "create database" 200 "$(q 1 'CREATE DATABASE d')"
expect "create table" 200 "$(q 1 'CREATE TABLE d.t')"
expect "insert on n1" 200 "$(q 1 "INSERT INTO d.t (_id) VALUES ('first')")"
wait_converged && ok "the insert reached every node" || bad "replication" "counts differ"
expect "plain HTTP between peers is not served" 000 "$(docker exec "$RUN-client" curl -s -o /dev/null \
  -w '%{http_code}' --max-time 5 http://c1:1985/health || true)"
expect "internal route without the key" 401 "$(docker exec "$RUN-client" curl -s -o /dev/null -w '%{http_code}' \
  --max-time 5 --cacert /tmp/ca.pem https://c1:1985/internal/digests)"

start_writer

echo "# kill one node: writes continue"
sleep 2
docker kill -s KILL "$RUN-n3" >/dev/null
sleep 4
expect "write on n1 with n3 down" 200 "$(q 1 "INSERT INTO d.t (_id) VALUES ('n3-down')")"
expect "write on n2 with n3 down" 200 "$(q 2 "INSERT INTO d.t (_id) VALUES ('n3-down-2')")"

echo "# kill a second node: writes stop"
docker kill -s KILL "$RUN-n2" >/dev/null
sleep 4
expect "write on n1 with two nodes down is 503" 503 "$(q 1 "INSERT INTO d.t (_id) VALUES ('two-down')")"
expect "a local read still works" 200 "$(q 1 'SELECT COUNT(*) FROM d.t')"
expect "a majority read is 503" 503 "$(q 1 'SELECT COUNT(*) FROM d.t' majority)"

echo "# restart both: they catch up"
docker start "$RUN-n2" "$RUN-n3" >/dev/null
for i in 1 2 3; do wait_writable "$i"; done
ok "restarted nodes caught up"

echo "# partition n3 from its peers"
# Not "docker network disconnect": some Docker hosts (OrbStack, for one) route between bridge networks,
# and the JVM caches the peers' addresses, so n3 would still reach them. Instead, drop every packet
# between n3 and the peers' addresses inside n3's network namespace. The client can still reach n3.
docker build -q -t serengeti-netfault - >/dev/null <<'DOCKERFILE'
FROM alpine:3.20
RUN apk add --no-cache iptables
DOCKERFILE
peer_ips=$(for i in 1 2; do docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' "$RUN-n$i"; done)
netfault() { # rules for n3's namespace: "cut" or "heal"
  docker run --rm "${L[@]}" --network "container:$RUN-n3" --cap-add NET_ADMIN serengeti-netfault sh -c '
    set -e
    if [ "$1" = heal ]; then iptables -F INPUT; iptables -F OUTPUT; exit 0; fi
    shift
    for ip in "$@"; do iptables -A INPUT -s "$ip" -j DROP; iptables -A OUTPUT -d "$ip" -j DROP; done' sh "$@"
}
sleep 2
# shellcheck disable=SC2086
netfault cut $peer_ips
sleep 5
st3=$(docker exec "$RUN-client" curl -s --max-time 5 --cacert /tmp/ca.pem -u "admin:$PW" https://c3:1985/v1/status)
case "$st3" in *'"reachable":1,'*) ok "the partition is real: n3 reaches no peer" ;;
  *) bad "partition" "n3 still reaches peers: $st3" ;; esac
minority=$(q 3 "INSERT INTO d.t (_id) VALUES ('minority')")
expect "write on the minority side (n3) is 503" 503 "$minority"
if [ "$minority" != 503 ]; then
  echo "--- n3 status and log at the failed minority write:"
  docker exec "$RUN-client" curl -s --max-time 5 --cacert /tmp/ca.pem -u "admin:$PW" https://c3:1985/v1/status; echo
  docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$RUN-n3"
  docker logs "$RUN-n3" 2>&1 | grep -v ' request ' | tail -20
fi
expect "write on the majority side (n1) succeeds" 200 "$(q 1 "INSERT INTO d.t (_id) VALUES ('majority')")"
expect "majority read on n2 sees it" 200 "$(q 2 "SELECT _id FROM d.t WHERE _id = 'majority'" majority)"
case "$(body)" in *'"count":1'*) ok "and returns the row" ;; *) bad "majority read" "$(body)" ;; esac
netfault heal
wait_writable 3
ok "n3 accepts writes again after the partition heals"

median_write() { # i tag -> median seconds of five inserts through node i (each curl includes a TLS handshake)
  for k in 1 2 3 4 5; do
    docker exec "$RUN-client" curl -s -o /dev/null -w '%{time_total}\n' --max-time 20 --cacert /tmp/ca.pem \
      -u "admin:$PW" -H 'Content-Type: application/json' \
      --data-binary "{\"query\": \"INSERT INTO d.t (_id) VALUES ('$2-$k')\"}" "https://c$1:1985/v1/query"
  done | sort -n | sed -n 3p
}

echo "# slow disk on n3 (1 s per fsync): writes through n1 need only n1 and n2"
stop_writer
base=$(median_write 1 fast)
docker rm -f "$RUN-n3" >/dev/null
node 3 -e SERENGETI_TEST_FSYNC_DELAY_MS=1000
wait_writable 3
slow=$(median_write 1 slow)
if awk "BEGIN { exit !($slow < $base + 0.5) }"; then
  ok "median write through n1: ${base}s before, ${slow}s with n3's disk slowed by 1 s"
else bad "slow disk" "median write through n1: ${base}s before, ${slow}s after"; fi
through3=$(median_write 3 via3)
if awk "BEGIN { exit !($through3 >= 1.0) }"; then ok "writes through n3 itself wait for its disk (${through3}s)"
else bad "slow disk injection" "writes through n3 took ${through3}s; the delay did not apply"; fi
start_writer
sleep 3
stop_writer

echo "# restart every node: nothing acknowledged is lost"
docker restart "$RUN-n1" "$RUN-n2" "$RUN-n3" >/dev/null
for i in 1 2 3; do wait_writable "$i"; done
wait_converged && ok "all nodes converge after the restart" || bad "convergence" "counts differ"
acked=$(docker exec "$RUN-client" sh -c 'sort -u /tmp/acked | wc -l' | tr -d ' ')
for i in 1 2 3; do
  q "$i" 'SELECT _id FROM d.t LIMIT 10000' >/dev/null
  lost=$(docker exec "$RUN-client" sh -c 'tr "[]," "\n\n\n" < /tmp/body | tr -d "\"" | grep "^w" | sort -u > /tmp/present
    sort -u /tmp/acked | comm -23 - /tmp/present | wc -l' | tr -d ' ')
  expect "node $i has every acknowledged insert ($acked)" 0 "$lost"
done
[ "$acked" -gt 50 ] && ok "the writer got $acked acknowledgements" || bad "writer" "only $acked acknowledged"

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
