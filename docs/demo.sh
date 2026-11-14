#!/usr/bin/env bash
# Demo: a 3-node cluster keeps taking writes on a node that is cut off, and converges when it is back.
#
# What it does, in order: start three nodes; create a table and insert a row; cut n3 off from n1 and n2
# with iptables; show that a normal (majority) write on n3 is refused with 503; write with "ack": "local"
# on both sides, including one row that both sides change; show what each side sees; heal the network;
# wait until all three nodes return the same rows; list both versions of the row both sides changed with
# SHOW CONFLICTS, and resolve it with one more write. It exits non-zero if any expectation fails, so CI
# runs it as a test.
#
# For brevity the nodes talk plain HTTP (SERENGETI_CLUSTER_PLAINTEXT=allow) on a private Docker network.
# Nothing is published to the host, and the admin password and cluster key are random per run. A real
# cluster should use TLS; tests/cluster/run.sh runs the same partition over TLS.
#
# The cut is made as in tests/cluster/run.sh: a helper container joins n3's network namespace and drops
# every packet between n3 and the addresses of n1 and n2 (needs NET_ADMIN in that container). The client
# is not in the drop list, so it can still reach n3.
#
# Needs Docker and bash. Everything the script creates carries the label serengeti-demo=<run id> and is
# removed on exit; the two images it builds are kept as a cache.
#
#   docs/demo.sh                        builds the image from this checkout as serengeti:demo
#   IMAGE=serengeti:2.1 docs/demo.sh    uses an existing image
#   PAUSE=2 docs/demo.sh                waits 2 s before each step, for a recording
set -euo pipefail
cd "$(dirname "$0")/.."

RUN=demo$$
LABEL=serengeti-demo=$RUN
NET=$RUN-net
TOOLS=serengeti-demo-tools
CLIENT=$RUN-client
PW=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
KEY=$(od -An -tx1 -N24 /dev/urandom | tr -d ' \n')
failed=0
SECONDS=0

cleanup() {
  docker ps -aq --filter "label=$LABEL" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=$LABEL" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=$LABEL" | xargs -r docker network rm >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ -t 1 ]; then BOLD=$'\e[1m'; RED=$'\e[31m'; DIM=$'\e[2m'; OFF=$'\e[0m'; else BOLD=; RED=; DIM=; OFF=; fi
n=0
step() { n=$((n + 1)); [ "$n" = 1 ] || sleep "${PAUSE:-0}"; printf '\n%s%d. %s%s\n' "$BOLD" "$n" "$1" "$OFF"; }
say() { printf '   %-3s %s\n' "$1" "$2"; }
note() { printf '       %s\n' "$1"; }
# check WANT GOT: print nothing if they match, a red line if not, and remember the failure.
check() {
  if [ "$1" != "$2" ]; then
    failed=$((failed + 1))
    printf '   %sFAIL: expected "%s", got "%s"%s\n' "$RED" "$1" "$2" "$OFF"
  fi
}
die() { printf '%sFAIL: %s%s\n' "$RED" "$1" "$OFF" >&2; exit 1; }

# api NODE QUERY [ACK]: run one query through NODE; sets CODE (HTTP status) and BODY.
api() {
  local ack=${3:+, \"ack\": \"$3\"} out
  out=$(docker exec "$CLIENT" curl -s -w '\n%{http_code}' --max-time 20 -u "admin:$PW" \
    -H 'Content-Type: application/json' --data-binary "{\"query\": \"$2\"$ack}" "http://$1:1985/v1/query" || true)
  CODE=${out##*$'\n'}
  BODY=${out%$'\n'*}
}
j() { docker exec -i "$CLIENT" jq -r "$@"; }

# view NODE: the rows of shop.items on NODE as "bolt=100 washer=50".
view() {
  api "$1" 'SELECT _id, qty FROM shop.items ORDER BY _id'
  if [ "$CODE" = 200 ]; then printf '%s' "$BODY" | j '[.rows[] | "\(.[0])=\(.[1])"] | join(" ")'; else echo "(http $CODE)"; fi
}

# conflicts NODE: SHOW CONFLICTS on NODE as "bolt=90 current from n1; bolt=100 from n3", or "none".
# Node ids are mapped to n1..n3 with NAMES, a JSON object filled in once the nodes are up.
conflicts() {
  api "$1" 'SHOW CONFLICTS IN shop.items'
  if [ "$CODE" != 200 ]; then echo "(http $CODE)"; return; fi
  # shellcheck disable=SC2016  # $names is a jq variable
  printf '%s' "$BODY" | j --argjson names "$NAMES" '[.rows[] | "\(.[0])=\(if .[2] then "deleted" else .[5].qty end)"
    + (if .[1] then " current" else "" end) + " from \($names[.[3]] // .[3])"] | if length == 0 then "none" else join("; ") end'
}
sees_conflicts() { [ "$(conflicts "$1")" = "$2" ]; }
no_conflicts() { [ "$(conflicts "$1")" = none ]; }

# peers NODE: how many of NODE's peers it can currently reach, from GET /v1/status.
peers() {
  docker exec "$CLIENT" curl -s --max-time 5 -u "admin:$PW" "http://$1:1985/v1/status" \
    | j '"\([.cluster.peers[] | select(.reachable)] | length)"' 2>/dev/null || echo '?'
}

# until SECONDS CMD...: run CMD until it succeeds; fail after SECONDS seconds.
until_ok() {
  local limit=$1 start=$SECONDS
  shift
  until "$@"; do
    [ $((SECONDS - start)) -lt "$limit" ] || return 1
    sleep 0.5
  done
}
peers_are() { [ "$(peers "$1")" = "$2" ]; }
# ready NODE: NODE has synced with a majority since it started, so it takes majority writes, and it reaches
# both peers. A node can report its peers reachable a moment before it has synced with them.
ready() {
  [ "$(docker exec "$CLIENT" curl -s --max-time 5 -u "admin:$PW" "http://$1:1985/v1/status" \
    | j '"\(.cluster.caught_up) \([.cluster.peers[] | select(.reachable)] | length)"' 2>/dev/null)" = 'true 2' ]
}
sees() { [ "$(view "$1")" = "$2" ]; }

netfault() { # cut | heal: drop (or restore) all packets between n3 and the addresses of n1 and n2
  docker run --rm --label "$LABEL" --network "container:$RUN-n3" --cap-add NET_ADMIN "$TOOLS" sh -c '
    set -e
    if [ "$1" = heal ]; then iptables -F INPUT; iptables -F OUTPUT; exit 0; fi
    shift
    for ip in "$@"; do iptables -A INPUT -s "$ip" -j DROP; iptables -A OUTPUT -d "$ip" -j DROP; done' sh "$@"
}

printf '%sSerengeti: three nodes, one cut off from the other two%s\n' "$BOLD" "$OFF"
if [ -z "${IMAGE:-}" ]; then
  IMAGE=serengeti:demo
  printf '%sbuilding %s from this checkout%s\n' "$DIM" "$IMAGE" "$OFF"
  docker build -q -t "$IMAGE" . >/dev/null
fi
docker build -q -t "$TOOLS" - >/dev/null <<'DOCKERFILE'
FROM alpine:3.20
RUN apk add --no-cache iptables curl jq
DOCKERFILE

step "Start three nodes on a private network, and a client container"
docker network create --label "$LABEL" "$NET" >/dev/null
docker run -d -m 64m --label "$LABEL" --name "$CLIENT" --network "$NET" --entrypoint sleep "$TOOLS" 3600 >/dev/null
for i in 1 2 3; do
  case $i in 1) plist=n2:1985,n3:1985 ;; 2) plist=n1:1985,n3:1985 ;; 3) plist=n1:1985,n2:1985 ;; esac
  docker volume create --label "$LABEL" "$RUN-data$i" >/dev/null
  docker run -d -m 512m --label "$LABEL" --name "$RUN-n$i" --network "$NET" --network-alias "n$i" \
    -v "$RUN-data$i:/data" -e SERENGETI_PEERS="$plist" -e SERENGETI_CLUSTER_KEY="$KEY" \
    -e SERENGETI_CLUSTER_PLAINTEXT=allow -e SERENGETI_ADMIN_PASSWORD="$PW" "$IMAGE" >/dev/null
done
for i in 1 2 3; do until_ok 120 ready "n$i" || die "n$i did not come up"; done
# shellcheck disable=SC2016  # $n is a jq variable
NAMES=$(for i in 1 2 3; do
  docker exec "$CLIENT" curl -s --max-time 5 -u "admin:$PW" "http://n$i:1985/v1/status" | j -c --arg n "n$i" '{(.node): $n}'
done | j -s -c add)
note "n1, n2 and n3 are up, and each reaches both peers"

step "Create a table and insert a row through n1 (a normal, majority write)"
api n1 'CREATE DATABASE shop'
check 200 "$CODE"
api n1 'CREATE TABLE shop.items'
check 200 "$CODE"
api n1 "INSERT INTO shop.items (_id, qty) VALUES ('bolt', 120)"
check 200 "$CODE"
say n1 "INSERT bolt qty=120  -> $CODE"

step "Read it on every node"
for i in 1 2 3; do until_ok 30 sees "n$i" 'bolt=120' || true; done
for i in 1 2 3; do v=$(view "n$i"); check 'bolt=120' "$v"; say "n$i" "$v"; done

step "Cut n3 off from n1 and n2 (iptables drops its packets to them)"
# shellcheck disable=SC2046
netfault cut $(for i in 1 2; do docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$RUN-n$i"; done)
until_ok 30 peers_are n3 0 || true
until_ok 30 peers_are n1 1 || true
p3=$(peers n3)
p1=$(peers n1)
check 0 "$p3"
check 1 "$p1"
say n3 "reaches $p3 of 2 peers (/v1/status)"
say n1 "reaches $p1 of 2 peers"

step "A normal write on n3 needs a majority, so n3 refuses it"
api n3 "INSERT INTO shop.items (_id, qty) VALUES ('washer', 50)"
check 503 "$CODE"
say n3 "INSERT washer qty=50  -> $CODE"
printf '%s' "$BODY" | j .error | fold -s -w 70 | while IFS= read -r l; do note "$l"; done

step "Write with \"ack\": \"local\" on both sides, changing bolt on each (n1 last)"
api n3 "INSERT INTO shop.items (_id, qty) VALUES ('washer', 50)" local
check 200 "$CODE"
say n3 "INSERT washer qty=50  -> $CODE"
api n3 "UPDATE shop.items SET qty = 100 WHERE _id = 'bolt'" local
check 200 "$CODE"
say n3 "UPDATE bolt qty=100   -> $CODE"
sleep 1
api n1 "INSERT INTO shop.items (_id, qty) VALUES ('nut', 300)" local
check 200 "$CODE"
say n1 "INSERT nut qty=300    -> $CODE"
api n1 "UPDATE shop.items SET qty = 90 WHERE _id = 'bolt'" local
check 200 "$CODE"
say n1 "UPDATE bolt qty=90    -> $CODE"

step "Each side sees only its own writes"
until_ok 30 sees n2 'bolt=90 nut=300' || true
say n3 "$(view n3)"
check 'bolt=100 washer=50' "$(view n3)"
for i in 1 2; do v=$(view "n$i"); check 'bolt=90 nut=300' "$v"; say "n$i" "$v"; done

step "Heal the network, and wait until every node returns the same rows"
healed=$SECONDS
netfault heal
until_ok 30 peers_are n3 2 || true
p3=$(peers n3)
check 2 "$p3"
say n3 "reaches $p3 of 2 peers"
for i in 1 2 3; do until_ok 60 sees "n$i" 'bolt=90 nut=300 washer=50' || true; done
# SECONDS counts whole seconds, so the time since healing is less than this.
note "all three agree within $((SECONDS - healed + 1)) s of healing; bolt=90, the later write, won"
for i in 1 2 3; do v=$(view "n$i"); check 'bolt=90 nut=300 washer=50' "$v"; say "n$i" "$v"; done

step "SHOW CONFLICTS keeps both writes to bolt, the current one first"
want='bolt=90 current from n1; bolt=100 from n3'
until_ok 20 sees_conflicts n2 "$want" || true
v=$(conflicts n2)
check "$want" "$v"
say n2 "$v"

step "One more write to bolt has seen both versions, so it resolves the conflict"
api n2 "UPDATE shop.items SET qty = 95 WHERE _id = 'bolt'"
check 200 "$CODE"
say n2 "UPDATE bolt qty=95    -> $CODE"
for i in 1 2 3; do until_ok 20 no_conflicts "n$i" || true; done
for i in 1 2 3; do
  c=$(conflicts "n$i") v=$(view "n$i")
  check none "$c"
  check 'bolt=95 nut=300 washer=50' "$v"
  say "n$i" "conflicts: $c; $v"
done

echo
if [ "$failed" = 0 ]; then
  echo "All expectations held ($SECONDS s)."
else
  echo "$failed expectation(s) failed."
  exit 1
fi
