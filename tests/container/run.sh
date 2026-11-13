#!/usr/bin/env bash
# Integration tests against the real image. Every container runs on a private Docker network made for
# this run; nothing is published to the host. A curl container on the same network is the client.
#
#   IMAGE=serengeti:it tests/container/run.sh        (builds the image first unless SKIP_BUILD=1)
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=${IMAGE:-serengeti:it}
RUN=it$$
NET=serengeti-$RUN
PW='integration password 1'
KILL_ROUNDS=${KILL_ROUNDS:-5}
passed=0
failed=0

cleanup() {
  docker ps -aq --filter "label=serengeti-it=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-it=$RUN" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi
docker network create --label "serengeti-it=$RUN" "$NET" >/dev/null
docker run -d --label "serengeti-it=$RUN" --name "$RUN-client" --network "$NET" \
  --entrypoint sleep curlimages/curl:8.11.1 3600 >/dev/null

ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }

expect() { # description expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi
}

contains() { # description needle haystack
  case "$3" in *"$2"*) ok "$1" ;; *) bad "$1" "'$2' not in: $3" ;; esac
}

volume() {
  docker volume create --label "serengeti-it=$RUN" "$RUN-$1" >/dev/null
  echo "$RUN-$1"
}

node() { # name [docker run args...]
  local name=$1
  shift
  docker run -d -m 512m --label "serengeti-it=$RUN" --name "$RUN-$name" --network "$NET" --network-alias "$name" \
    "$@" "$IMAGE" >/dev/null
}

# req node METHOD PATH [credentials] [json body]  ->  prints the status code, then the body on the next line
req() {
  local args=(-s -o /tmp/body -w '%{http_code}' -X "$2" --max-time 10)
  if [ -n "${4:-}" ]; then args+=(-u "$4"); fi
  if [ -n "${5:-}" ]; then args+=(-H 'Content-Type: application/json' --data-binary "$5"); fi
  docker exec "$RUN-client" sh -c 'curl "$@"; echo; cat /tmp/body; rm -f /tmp/body' sh "${args[@]}" "http://$1:1985$3"
}

status() { req "$@" | sed -n 1p; }
body() { req "$@" | tail -n +2; }
query() { # node query  -> status
  status "$1" POST /v1/query "admin:$PW" "$(printf '{"query": "%s"}' "$2")"
}

wait_up() { # node
  for _ in $(seq 1 120); do
    if [ "$(status "$1" GET /health 2>/dev/null)" = 200 ]; then return 0; fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$RUN-$1")" != true ]; then
      echo "node $1 exited:"; docker logs "$RUN-$1" 2>&1 | tail -5; return 1
    fi
    sleep 0.5
  done
  echo "node $1 did not answer /health"; docker logs "$RUN-$1" 2>&1 | tail -5
  return 1
}

exit_code() { # container: wait for it to stop and print its exit code
  docker wait "$RUN-$1"
}

echo "# first start without a password"
node fresh -v "$(volume fresh):/data"
wait_up fresh
expect "health answers without credentials" 200 "$(status fresh GET /health)"
expect "query before setup is 503" 503 "$(status fresh POST /v1/query "admin:admin" '{"query": "SHOW DATABASES"}')"
expect "console before setup is 503" 503 "$(status fresh GET /)"
token=$(docker exec "$RUN-fresh" cat /data/setup-token)
expect "setup token file is mode 600" "600" "$(docker exec "$RUN-fresh" stat -c %a /data/setup-token)"
expect "setup with a wrong token is 403" 403 "$(status fresh POST /v1/setup "" '{"token": "wrong", "password": "'"$PW"'"}')"
expect "setup with the token is 200" 200 "$(status fresh POST /v1/setup "" '{"token": "'"$token"'", "password": "'"$PW"'"}')"
expect "setup token is deleted" "gone" "$(docker exec "$RUN-fresh" sh -c 'test -e /data/setup-token && echo there || echo gone')"
expect "setup a second time is 409" 409 "$(status fresh POST /v1/setup "" '{"token": "'"$token"'", "password": "another password 2"}')"
expect "query with the new password is 200" 200 "$(query fresh "SHOW DATABASES")"
expect "users file is mode 600" "600" "$(docker exec "$RUN-fresh" stat -c %a /data/users.json)"
expect "health probe command exits 0" 0 "$(docker exec "$RUN-fresh" java -jar /opt/serengeti/serengeti.jar --health >/dev/null 2>&1; echo $?)"
expect "runs as a non-root user" 10001 "$(docker exec "$RUN-fresh" id -u)"

echo "# password from the environment, data across a restart"
node env -v "$(volume env):/data" -e SERENGETI_ADMIN_PASSWORD="$PW"
wait_up env
expect "no credentials is 401" 401 "$(status env POST /v1/query "" '{"query": "SHOW DATABASES"}')"
expect "admin/admin is 401" 401 "$(status env POST /v1/query "admin:admin" '{"query": "SHOW DATABASES"}')"
expect "console without credentials is 401" 401 "$(status env GET /)"
expect "console with credentials is 200" 200 "$(status env GET / "admin:$PW")"
expect "create database" 200 "$(query env "CREATE DATABASE shop")"
expect "create table" 200 "$(query env "CREATE TABLE shop.items")"
expect "insert" 200 "$(query env "INSERT INTO shop.items (_id, name) VALUES ('a', 'bolt'), ('b', 'nut')")"
expect "a parse error is 400" 400 "$(query env "SELECT * FROM")"
expect "text/plain is refused" 415 "$(docker exec "$RUN-client" curl -s -o /dev/null -w '%{http_code}' -u "admin:$PW" \
  -H 'Content-Type: text/plain' --data 'SHOW DATABASES' http://env:1985/v1/query)"
expect "a 2 MB body is refused" 413 "$(docker exec "$RUN-client" sh -c \
  'head -c 2000000 /dev/zero | tr "\0" a > /tmp/big; curl -s -o /dev/null -w "%{http_code}" -u "$1" \
   -H "Content-Type: application/json" --data-binary @/tmp/big http://env:1985/v1/query' sh "admin:$PW")"
docker restart "$RUN-env" >/dev/null
wait_up env
contains "rows are there after a restart" '"rows":[["a","bolt"],["b","nut"]]' \
  "$(body env POST /v1/query "admin:$PW" '{"query": "SELECT _id, name FROM shop.items"}')"

echo "# data directory edge cases"
node nested -e SERENGETI_DATA_DIR=/data/a/b/c -e SERENGETI_ADMIN_PASSWORD="$PW"
wait_up nested
expect "a missing nested data directory is created" 200 "$(query nested "SHOW DATABASES")"

node unwritable -e SERENGETI_DATA_DIR=/opt/serengeti/data -e SERENGETI_ADMIN_PASSWORD="$PW"
expect "an unwritable data directory exits 2" 2 "$(exit_code unwritable)"
contains "and says why" "data directory" "$(docker logs "$RUN-unwritable" 2>&1)"

ro=$(volume ro)
node ro --mount "type=volume,src=$ro,dst=/data,readonly" -e SERENGETI_ADMIN_PASSWORD="$PW"
expect "a read-only data volume exits 2" 2 "$(exit_code ro)"

node shortpw -e SERENGETI_ADMIN_PASSWORD=short
expect "a short admin password exits 2" 2 "$(exit_code shortpw)"

node peers -e SERENGETI_PEERS=other:1985
expect "peers without a cluster key exit 2" 2 "$(exit_code peers)"
contains "and say why" "CLUSTER_KEY" "$(docker logs "$RUN-peers" 2>&1)"

echo "# default bind outside the image"
listen=$(docker run --rm --label "serengeti-it=$RUN" --network "$NET" --entrypoint sh "$IMAGE" -c \
  'unset SERENGETI_BIND; java -jar /opt/serengeti/serengeti.jar >/dev/null 2>&1 &
   for i in $(seq 1 60); do grep -q ":07C1 00000000:0000 0A" /proc/net/tcp /proc/net/tcp6 2>/dev/null && break; sleep 0.5; done
   cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk "\$4 == \"0A\" && \$2 ~ /:07C1\$/ {print \$2}"')
# Java may report the IPv4 loopback socket in tcp6 as ::ffff:127.0.0.1; both forms are loopback only.
listen=${listen/0000000000000000FFFF00000100007F/0100007F}
expect "without SERENGETI_BIND the node listens on 127.0.0.1:1985 only" "0100007F:07C1" "$listen"

echo "# TLS"
certs=$(volume certs)
docker run --rm -m 512m --label "serengeti-it=$RUN" -v "$certs:/certs" --user 0 --entrypoint sh "$IMAGE" -c '
  set -e; cd /certs
  keytool -genkeypair -alias node -keyalg EC -groupname secp256r1 -dname CN=tls -ext san=dns:tls -validity 2 \
    -storetype PKCS12 -keystore node.p12 -storepass changeit >/dev/null 2>&1
  keytool -exportcert -rfc -alias node -keystore node.p12 -storepass changeit -file node.pem >/dev/null 2>&1
  printf changeit > pw
  chown 10001:10001 node.p12 pw && chmod 600 node.p12 pw'
docker run --rm --label "serengeti-it=$RUN" -v "$certs:/certs" --entrypoint cat "$IMAGE" /certs/node.pem \
  | docker exec -i "$RUN-client" sh -c 'cat > /tmp/node.pem'
node tls -v "$certs:/certs:ro" -e SERENGETI_TLS_KEYSTORE=/certs/node.p12 \
  -e SERENGETI_TLS_KEYSTORE_PASSWORD_FILE=/certs/pw -e SERENGETI_ADMIN_PASSWORD="$PW"
tls_status() { # path [credentials]
  docker exec "$RUN-client" curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert /tmp/node.pem \
    ${2:+-u "$2"} "https://tls:1985$1" || true
}
for _ in $(seq 1 120); do [ "$(tls_status /health)" = 200 ] && break; sleep 0.5; done
expect "HTTPS health with the node's certificate" 200 "$(tls_status /health)"
expect "HTTPS status needs credentials" 401 "$(tls_status /v1/status)"
expect "HTTPS status with credentials" 200 "$(tls_status /v1/status "admin:$PW")"
expect "plain HTTP to the TLS port gets no HTTP answer" 000 \
  "$(docker exec "$RUN-client" curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://tls:1985/health || true)"
expect "an untrusting client is refused" 000 \
  "$(docker exec "$RUN-client" curl -s -o /dev/null -w '%{http_code}' --max-time 5 https://tls:1985/health || true)"
expect "TLS 1.1 is refused" 000 \
  "$(docker exec "$RUN-client" curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert /tmp/node.pem \
     --tlsv1.1 --tls-max 1.1 https://tls:1985/health || true)"
expect "health probe over TLS exits 0" 0 \
  "$(docker exec "$RUN-tls" java -jar /opt/serengeti/serengeti.jar --health >/dev/null 2>&1; echo $?)"
node badtls -e SERENGETI_TLS_KEYSTORE=/nope.p12 -e SERENGETI_TLS_KEYSTORE_PASSWORD=x -e SERENGETI_ADMIN_PASSWORD="$PW"
expect "a missing key store exits 2" 2 "$(exit_code badtls)"

echo "# kill -9 during writes, $KILL_ROUNDS rounds"
node crash -v "$(volume crash):/data" -e SERENGETI_ADMIN_PASSWORD="$PW"
wait_up crash
query crash "CREATE DATABASE d" >/dev/null
query crash "CREATE TABLE d.t" >/dev/null
docker exec "$RUN-client" sh -c ': > /tmp/acked'
for round in $(seq 1 "$KILL_ROUNDS"); do
  docker exec -d "$RUN-client" sh -c '
    i=0
    while [ $i -lt 100000 ]; do
      i=$((i + 1)); id="r$1-$i"
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 -u "$2" -H "Content-Type: application/json" \
        --data-binary "{\"query\": \"INSERT INTO d.t (_id, n) VALUES ('"'"'$id'"'"', $i)\"}" http://crash:1985/v1/query) || break
      [ "$code" = 200 ] || break
      echo "$id" >> /tmp/acked
    done' sh "$round" "admin:$PW"
  sleep "$((1 + RANDOM % 3)).$((RANDOM % 10))"
  docker kill -s KILL "$RUN-crash" >/dev/null
  sleep 1
  docker start "$RUN-crash" >/dev/null
  wait_up crash
done
acked=$(docker exec "$RUN-client" sh -c 'sort -u /tmp/acked | wc -l' | tr -d ' ')
docker exec "$RUN-client" sh -c 'curl -s -u "$1" -H "Content-Type: application/json" \
  --data-binary "{\"query\": \"SELECT _id FROM d.t LIMIT 10000\"}" http://crash:1985/v1/query \
  | tr "[]," "\n\n\n" | tr -d "\"" | grep "^r" | sort -u > /tmp/present
  sort -u /tmp/acked | comm -23 - /tmp/present > /tmp/lost' sh "admin:$PW"
lost=$(docker exec "$RUN-client" sh -c 'wc -l < /tmp/lost' | tr -d ' ')
if [ "$acked" -gt 0 ]; then ok "writes were acknowledged before the kills ($acked)"; else bad "kill test" "no writes acknowledged"; fi
expect "every acknowledged write survived kill -9 ($acked acknowledged)" 0 "$lost"

echo "# readiness, metrics, request log, backup and restore"
expect "ready needs no credentials" 200 "$(status env GET /ready)"
contains "ready says so" '{"status":"ready"}' "$(body env GET /ready)"
expect "metrics need credentials" 401 "$(status env GET /metrics)"
metrics=$(body env GET /metrics "admin:$PW")
contains "metrics count query requests" 'serengeti_http_requests_total{route="/v1/query",code="200"}' "$metrics"
contains "metrics report write-ahead log forces" 'serengeti_wal_fsyncs_total ' "$metrics"
expect "backup needs credentials" 401 "$(status env GET /v1/backup)"
backup_headers=$(docker exec "$RUN-client" curl -s -D - -o /tmp/backup.jsonl -u "admin:$PW" http://env:1985/v1/backup)
contains "backup is octet-stream" "application/octet-stream" "$backup_headers"
contains "backup names its file" 'filename="serengeti-backup-' "$backup_headers"
contains "backup starts with the snapshot header" '"format":"serengeti-snapshot"' \
  "$(docker exec "$RUN-client" head -1 /tmp/backup.jsonl)"

restore_into() { # volume backup-file-in-client -> exit status of "serengeti restore" in a new container
  local rc=0
  docker exec "$RUN-client" cat "$2" | docker run --rm -i --label "serengeti-it=$RUN" -v "$1:/data" \
    --entrypoint sh "$IMAGE" -c 'cat > /tmp/in && exec java -jar /opt/serengeti/serengeti.jar restore /tmp/in' \
    >/dev/null 2>&1 || rc=$?
  echo "$rc"
}
restored=$(volume restored)
expect "restore into an empty volume exits 0" 0 "$(restore_into "$restored" /tmp/backup.jsonl)"
expect "restore into a volume that holds data exits 2" 2 "$(restore_into "$restored" /tmp/backup.jsonl)"
docker exec "$RUN-client" sh -c 'head -c $(( $(wc -c < /tmp/backup.jsonl) / 2 )) /tmp/backup.jsonl > /tmp/truncated.jsonl'
trunc=$(volume trunc)
expect "a truncated backup is refused with exit 2" 2 "$(restore_into "$trunc" /tmp/truncated.jsonl)"
expect "and the volume is left without a snapshot" gone \
  "$(docker run --rm -v "$trunc:/data" --entrypoint sh "$IMAGE" -c \
     'test -e /data/snapshot.jsonl && echo there || echo gone')"
node copy -v "$restored:/data" -e SERENGETI_ADMIN_PASSWORD="$PW"
wait_up copy
contains "a node on the restored volume has the rows" '"rows":[["a","bolt"],["b","nut"]]' \
  "$(body copy POST /v1/query "admin:$PW" '{"query": "SELECT _id, name FROM shop.items"}')"
contains "the request log names route and status, not the query" \
  'request method=POST route=/v1/query status=200' "$(docker logs "$RUN-env" 2>&1)"
expect "the request log holds no query text" 0 \
  "$(docker logs "$RUN-env" 2>&1 | grep -c -F 'SELECT _id, name' || true)"
expect "the request log holds no password" 0 "$(docker logs "$RUN-env" 2>&1 | grep -c -F "$PW" || true)"

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
