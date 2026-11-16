#!/usr/bin/env bash
# Runs examples/cluster/compose.yaml as its comments say, with a built image, and checks the cluster works:
# certificates, three nodes over TLS, a write on one node read on another, writes going on with one node
# stopped, and the published ports bound to 127.0.0.1 only. Everything it makes is removed at the end.
#
#   IMAGE=serengeti:cluster-example examples/cluster/run.sh     (builds the image unless SKIP_BUILD=1)
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=${IMAGE:-serengeti:cluster-example}
PROJECT=sgx$$
passed=0
failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi; }

work=$(mktemp -d)
cleanup() {
  (cd "$work" && docker compose -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1) || true
  rm -rf "$work"
}
trap cleanup EXIT
if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" ../.. >/dev/null
fi
cp compose.yaml "$work/"
cd "$work"
PW=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
printf 'SERENGETI_ADMIN_PASSWORD=%s\nSERENGETI_CLUSTER_KEY=%s\nSERENGETI_IMAGE=%s\n' \
  "$PW-admin" "$(head -c 48 /dev/urandom | base64 | tr -d '/+=')" "$IMAGE" > .env
PW="$PW-admin"
dc() { docker compose -p "$PROJECT" "$@"; }

echo "# certificates"
expect "serengeti-certs makes the key stores" 0 "$(dc run --rm certs >/dev/null 2>&1; echo $?)"
expect "a second run refuses to overwrite them" 1 "$(dc run --rm certs >/dev/null 2>&1; echo $?)"
dc run --rm --entrypoint cat certs /certs/ca.pem > ca.pem
expect "the CA key is not left with the nodes" 0 "$(dc run --rm --entrypoint sh certs -c 'ls /certs/ca.p12 2>/dev/null | wc -l' | tr -d ' ')"

echo "# three nodes"
dc up -d >/dev/null 2>&1
q() { # port query -> status; body in ./body
  curl -s -o body -w '%{http_code}' --max-time 20 --cacert ca.pem -u "admin:$PW" \
    -H 'Content-Type: application/json' --data-binary "{\"query\": \"$2\"}" "https://127.0.0.1:$1/v1/query" || true
}
ready() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert ca.pem "https://127.0.0.1:$1/ready" || true; }
for p in 19851 19852 19853; do
  for _ in $(seq 120); do [ "$(ready $p)" = 200 ] && break; sleep 1; done
  expect "the node on 127.0.0.1:$p is ready, over TLS" 200 "$(ready $p)"
done
expect "plain HTTP is not served" 000 "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:19851/health || true)"
expect "create a database through n1" 200 "$(q 19851 'CREATE DATABASE shop')"
expect "create a table" 200 "$(q 19851 'CREATE TABLE shop.items')"
expect "insert through n2" 200 "$(q 19852 "INSERT INTO shop.items (_id, qty) VALUES ('bolt', 120)")"
for _ in $(seq 30); do [ "$(q 19853 'SELECT qty FROM shop.items')" = 200 ] && grep -q '120' body && break; sleep 0.5; done
expect "read it on n3" 1 "$(grep -c '"rows":\[\[120\]\]' body || true)"
dc stop n3 >/dev/null 2>&1
expect "with n3 stopped, a majority write still works" 200 "$(q 19851 "INSERT INTO shop.items (_id, qty) VALUES ('nut', 300)")"
dc start n3 >/dev/null 2>&1
for _ in $(seq 60); do [ "$(q 19853 'SELECT COUNT(*) FROM shop.items')" = 200 ] && grep -q '"rows":\[\[2\]\]' body && break; sleep 1; done
expect "n3 catches up after it starts again" 1 "$(grep -c '"rows":\[\[2\]\]' body || true)"
for p in 19851 19852 19853; do
  bound=$(docker port "$(dc ps -q "n$(( p - 19850 ))")" 1985/tcp | tr '\n' ' ')
  case $bound in 127.0.0.1:*) ok "n$(( p - 19850 )) is published on 127.0.0.1 only ($bound)" ;; *) bad "publish" "$bound" ;; esac
done

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
