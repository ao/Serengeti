#!/usr/bin/env bash
# Upgrade tests: a node of the previous release (OLD_IMAGE) replaced by this build (IMAGE) on the same data
# volume, and a 3-node TLS cluster upgraded one node at a time while a writer keeps inserting. Nothing is
# published to the host; the client is a curl container on the nodes' private networks.
#
#   OLD_IMAGE=ataiva/serengeti:2.0.0 IMAGE=serengeti:upgrade tests/upgrade/run.sh   (builds IMAGE unless SKIP_BUILD=1)
set -euo pipefail
cd "$(dirname "$0")/../.."

OLD_IMAGE=${OLD_IMAGE:-ataiva/serengeti:2.0.0}
IMAGE=${IMAGE:-serengeti:upgrade}
RUN=up$$
NET=$RUN-cluster
PW='upgrade test password'
KEY='cluster key for the upgrade tests, 32+ chars'
passed=0
failed=0

cleanup() {
  docker ps -aq --filter "label=serengeti-up=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-up=$RUN" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=serengeti-up=$RUN" | xargs -r docker network rm >/dev/null 2>&1 || true
}
trap cleanup EXIT

ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi; }
L=(--label "serengeti-up=$RUN")

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi
docker pull -q "$OLD_IMAGE" >/dev/null
echo "# from $(docker run --rm "$OLD_IMAGE" --version 2>/dev/null | head -1) to $(docker run --rm "$IMAGE" --version 2>/dev/null | head -1)"

docker network create "${L[@]}" "$NET" >/dev/null
for i in 1 2 3; do docker network create "${L[@]}" "$RUN-c$i" >/dev/null; done
docker run -d "${L[@]}" --name "$RUN-client" --network "$RUN-c1" --entrypoint sleep curlimages/curl:8.11.1 3600 >/dev/null
docker network connect "$RUN-c2" "$RUN-client"
docker network connect "$RUN-c3" "$RUN-client"

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

node() { # i image [peers]: a node on data volume i; with peers "-", a single node
  local i=$1 image=$2 peers=${3:-$(peers_of "$1")}
  local env=(-e SERENGETI_ADMIN_PASSWORD="$PW"
    -e "SERENGETI_TLS_KEYSTORE=/certs/n$i.p12" -e SERENGETI_TLS_KEYSTORE_PASSWORD_FILE=/certs/pw)
  if [ "$peers" != - ]; then
    env+=(-e SERENGETI_PEERS="$peers" -e SERENGETI_CLUSTER_KEY="$KEY"
      -e SERENGETI_TLS_TRUSTSTORE=/certs/trust.p12 -e SERENGETI_TLS_TRUSTSTORE_PASSWORD_FILE=/certs/pw)
  fi
  docker volume create "${L[@]}" "$RUN-data$i" >/dev/null
  docker run -d -m 512m "${L[@]}" --name "$RUN-n$i" --network "$NET" --network-alias "n$i" \
    -v "$RUN-data$i:/data" -v "$certs:/certs:ro" "${env[@]}" "$image" >/dev/null
  docker network connect --alias "c$i" "$RUN-c$i" "$RUN-n$i"
}
replace() { # i image: stop node i gracefully and start image on the same volume
  docker stop -t 20 "$RUN-n$1" >/dev/null
  docker rm "$RUN-n$1" >/dev/null
  node "$1" "$2" "${3:-}"
}

q() { # i query -> status code; body in /tmp/body in the client
  docker exec "$RUN-client" curl -s -o /tmp/body -w '%{http_code}' --max-time 20 --cacert /tmp/ca.pem \
    -u "admin:$PW" -H 'Content-Type: application/json' \
    --data-binary "{\"query\": \"$2\"}" "https://c$1:1985/v1/query" || true
}
body() { docker exec "$RUN-client" cat /tmp/body; }
wait_up() { # i: answers queries (single node)
  for _ in $(seq 1 120); do [ "$(q "$1" 'SHOW DATABASES')" = 200 ] && return 0; sleep 0.5; done
  echo "node $1 never answered"; docker logs "$RUN-n$1" 2>&1 | tail -8; return 1
}
wait_writable() { # i: caught up, and a majority reachable
  local st
  for _ in $(seq 1 120); do
    st=$(docker exec "$RUN-client" curl -s --max-time 5 --cacert /tmp/ca.pem -u "admin:$PW" "https://c$1:1985/v1/status" || true)
    case "$st" in *'"caught_up":true'*) case "$st" in *'"reachable":1,'*) ;; *) return 0 ;; esac ;; esac
    sleep 0.5
  done
  echo "node $1 never became writable: $st"; docker logs "$RUN-n$1" 2>&1 | tail -8; return 1
}
count() { # i [where] -> rows in d.t on node i
  q "$1" "SELECT COUNT(*) FROM d.t${2:+ WHERE $2}" >/dev/null
  body | sed -n 's/.*"rows":\[\[\([0-9]*\)\]\].*/\1/p'
}
conflict_rows() { # i -> number of rows SHOW CONFLICTS lists on node i
  q "$1" 'SHOW CONFLICTS IN d.t' >/dev/null
  body | sed -n 's/.*"count":\([0-9]*\).*/\1/p'
}

echo "# one node: $OLD_IMAGE's data directory opened by this build"
node 1 "$OLD_IMAGE" -
wait_up 1
expect "create database on the old node" 200 "$(q 1 'CREATE DATABASE d')"
expect "create table" 200 "$(q 1 'CREATE TABLE d.t')"
values=$(for k in $(seq 1 50); do printf "('u%s', %s)," "$k" "$k"; done)
expect "insert 50 rows" 200 "$(q 1 "INSERT INTO d.t (_id, n) VALUES ${values%,}")"
expect "delete 5" 200 "$(q 1 "DELETE FROM d.t WHERE n <= 5")"
expect "update one" 200 "$(q 1 "UPDATE d.t SET n = 600 WHERE _id = 'u6'")"
replace 1 "$IMAGE" -
wait_up 1
expect "the new build has the 45 live rows" 45 "$(count 1)"
expect "deleted rows stay deleted" 0 "$(count 1 'n <= 5')"
expect "the updated row has its new value" 1 "$(count 1 'n = 600')"
expect "SHOW CONFLICTS works on old data and lists nothing" 200 "$(q 1 'SHOW CONFLICTS IN d.t')"
case "$(body)" in *'"rows":[]'*) ok "no conflicts" ;; *) bad "conflicts on old data" "$(body)" ;; esac
expect "new writes on old data" 200 "$(q 1 "UPDATE d.t SET n = 700 WHERE _id = 'u6'")"
expect "insert after the upgrade" 200 "$(q 1 "INSERT INTO d.t (_id, n) VALUES ('new', 1)")"
replace 1 "$IMAGE" -
wait_up 1
expect "a restart keeps old and new writes" 46 "$(count 1)"
expect "and the update" 1 "$(count 1 'n = 700')"
expect "create an index" 200 "$(q 1 'CREATE INDEX ON d.t (n)')"
expect "a write after it" 200 "$(q 1 "INSERT INTO d.t (_id, n) VALUES ('post-index', 800)")"
acct() { # i user password method path [body] -> status
  local args=(-s -o /tmp/body -w '%{http_code}' --max-time 20 --cacert /tmp/ca.pem -u "$2:$3" -X "$4")
  if [ -n "${6:-}" ]; then args+=(-H 'Content-Type: application/json' --data-binary "$6"); fi
  docker exec "$RUN-client" curl "${args[@]}" "https://c$1:1985$5" || true
}
if [ "$(acct 1 admin "$PW" GET /v1/users)" = 200 ]; then # this build has accounts and roles
  expect "make a write account" 201 "$(acct 1 admin "$PW" PUT /v1/users/writer '{"password": "writer password 1", "role": "write"}')"
  expect "make a read account" 201 "$(acct 1 admin "$PW" PUT /v1/users/reader '{"password": "reader password 1", "role": "read"}')"
  expect "make and delete one" 200 "$(acct 1 admin "$PW" PUT /v1/users/gone '{"password": "gone password 1"}' >/dev/null; acct 1 admin "$PW" DELETE /v1/users/gone)"
  accounts=1
fi
replace 1 "$OLD_IMAGE" -
wait_up 1
expect "rolled back to $OLD_IMAGE, every row is still there" 47 "$(count 1)"
expect "including the one written after the index" 1 "$(count 1 'n = 800')"
if docker logs "$RUN-n1" 2>&1 | grep -q discarded; then bad "rollback" "$OLD_IMAGE discarded part of its log"
else ok "$OLD_IMAGE discarded nothing from the log"; fi
if [ "${accounts:-0}" = 1 ]; then
  expect "$OLD_IMAGE reads users.json: the admin logs in" 200 "$(acct 1 admin "$PW" GET /v1/status)"
  expect "the write account works as a 2.0 user" 200 "$(acct 1 writer 'writer password 1' POST /v1/query "{\"query\": \"INSERT INTO d.t (_id, n) VALUES ('by-writer', 900)\"}")"
  expect "the read account cannot log in to $OLD_IMAGE (it fails closed)" 401 "$(acct 1 reader 'reader password 1' GET /v1/status)"
  expect "nor the deleted one" 401 "$(acct 1 gone 'gone password 1' GET /v1/status)"
fi
replace 1 "$IMAGE" -
wait_up 1
if [ "${accounts:-0}" = 1 ]; then
  expect "upgraded again, the read account is back" 200 "$(acct 1 reader 'reader password 1' GET /v1/status)"
  expect "and may still only read" 403 "$(acct 1 reader 'reader password 1' POST /v1/query '{"query": "INSERT INTO d.t (n) VALUES (1)"}')"
  expect "the deleted one stays deleted" 401 "$(acct 1 gone 'gone password 1' GET /v1/status)"
fi
q 1 'SHOW INDEXES IN d.t' >/dev/null
case "$(body)" in *'"rows":[["n"]]'*) ok "upgraded again, the index is back" ;; *) bad "index after rollback" "$(body)" ;; esac
expect "and finds rows" 1 "$(count 1 'n = 800')"
docker rm -f "$RUN-n1" >/dev/null
docker volume rm "$RUN-data1" >/dev/null

echo "# three nodes of $OLD_IMAGE, upgraded one at a time under a writer"
for i in 1 2 3; do node "$i" "$OLD_IMAGE"; done
for i in 1 2 3; do wait_writable "$i"; done
expect "create database" 200 "$(q 1 'CREATE DATABASE d')"
expect "create table" 200 "$(q 1 'CREATE TABLE d.t')"
docker exec "$RUN-client" sh -c 'rm -f /tmp/stop /tmp/stopped /tmp/acked; touch /tmp/acked'
docker exec -d "$RUN-client" sh -c '
  i=0
  while [ ! -e /tmp/stop ]; do
    i=$((i + 1)); n=$(( (i % 3) + 1 )); id="w-$i"
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 --cacert /tmp/ca.pem -u "$1" \
      -H "Content-Type: application/json" \
      --data-binary "{\"query\": \"INSERT INTO d.t (_id, n) VALUES ('"'"'$id'"'"', $i)\"}" "https://c$n:1985/v1/query")
    [ "$code" = 200 ] && echo "$id" >> /tmp/acked
    sleep 0.05
  done; echo done > /tmp/stopped' sh "admin:$PW"
sleep 3
for i in 1 2 3; do
  replace "$i" "$IMAGE"
  wait_writable "$i"
  ok "n$i runs this build and is caught up"
  sleep 3
  if [ "$i" = 1 ]; then
    expect "a write through the upgraded node reaches a majority" 200 "$(q 1 "INSERT INTO d.t (_id, n) VALUES ('mixed-new', 0)")"
    # 2.0 cannot read index ops: they are kept from its nodes, which still take and pass on writes.
    expect "CREATE INDEX through the upgraded node while 2.0 nodes remain" 200 "$(q 1 'CREATE INDEX ON d.t (n)')"
    expect "a write through an old node too" 200 "$(q 2 "INSERT INTO d.t (_id, n) VALUES ('mixed-old', 0)")"
  fi
done
docker exec "$RUN-client" sh -c 'touch /tmp/stop; for i in $(seq 1 60); do [ -e /tmp/stopped ] && break; sleep 0.5; done'
acked=$(docker exec "$RUN-client" sh -c 'wc -l < /tmp/acked' | tr -d ' ')
# A write that got a 503 may still have been written (docs/CONSISTENCY.md), so nodes can hold more rows
# than were acknowledged: check that they agree, and that every acknowledged id is on every one.
for _ in $(seq 1 120); do
  c1=$(count 1)
  [ -n "$c1" ] && [ "$c1" = "$(count 2)" ] && [ "$c1" = "$(count 3)" ] && break
  sleep 0.5
done
expect "all three nodes hold the same number of rows" "$(count 1)" "$(count 2)"
expect "and the third" "$(count 1)" "$(count 3)"
for i in 1 2 3; do
  q "$i" 'SELECT _id FROM d.t LIMIT 10000' >/dev/null
  lost=$(docker exec "$RUN-client" sh -c 'tr "[]," "\n\n\n" < /tmp/body | tr -d "\"" | grep -E "^(w-|mixed-)" | sort -u > /tmp/present
    { sort -u /tmp/acked; echo mixed-new; echo mixed-old; } | sort -u | comm -23 - /tmp/present | wc -l' | tr -d ' ')
  expect "n$i has every acknowledged insert ($acked) and both mixed-version writes" 0 "$lost"
done
for i in 1 2 3; do
  expect "n$i lists no conflicts (every row was written once)" 0 "$(conflict_rows "$i")"
done
for i in 1 2 3; do
  q "$i" 'SHOW INDEXES IN d.t' >/dev/null
  case "$(body)" in *'"rows":[["n"]]'*) ok "n$i has the index created while the versions were mixed" ;; *) bad "index on n$i" "$(body)" ;; esac
  expect "n$i finds the mixed-version rows through it" 2 "$(count "$i" "n = 0")"
done
if [ "$acked" -gt 20 ]; then ok "the writer got $acked acknowledgements during the upgrade"; else bad "writer" "only $acked acknowledgements"; fi

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
