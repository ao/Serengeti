#!/usr/bin/env bash
# Randomized fault test (soak) for a cluster of the real image, over TLS, in Docker. Clients write and read
# through every node while a nemesis kills, stops, pauses, partitions and slows nodes at random; at the end every
# fault is undone, the nodes must converge, and every node is checked against what the clients were told.
# docs/SOAK.md has the details. Nothing is published to the host.
#
#   tests/soak/run.sh                              3 nodes, 10 minutes, a random seed; builds serengeti:soak
#   NODES=5 DURATION=60 SEED=42 tests/soak/run.sh
#   IMAGE=repo/name:tag tests/soak/run.sh          uses that image as it is and builds nothing
#
# Settings:
#   NODES          3 or 5 (any number from 3 to 7 works)
#   DURATION       minutes of faults and client work (default 10)
#   SEED           the fault schedule and each client's operations follow from it (default: random, printed)
#   IMAGE          the image to test; without it, serengeti:soak is built from this tree
#   CLIENTS        concurrent clients, spread over the nodes (default 2 per node)
#   NODE_MEM       memory limit of each node container (default 1536m)
#   CLIENT_MEM     memory limit of the client container, which also runs the checker (default 3g)
#   CONVERGE_SECS  how long the nodes get to converge once the faults are undone (default 300)
#   NETEM=0        leave out the packet delay and loss faults (tc netem)
#   OUT            where the run's files go (default tests/soak/out/<time>-n<NODES>-s<SEED>)
#   KEEP=1         leave the containers, networks and volumes for inspection
#   SCENARIO       instead of the random schedule, a short fixed one that reproduces a finding of docs/SOAK.md:
#                  graceful-stop (docker stop on a node under local writes and majority reads, 20 s)
#
# Needs docker and python3 (the fault scheduler runs on the host; the clients and the checker run in
# python:3.12-alpine). Exit status 0 only if every check passes.
set -euo pipefail
cd "$(dirname "$0")/../.."

NODES=${NODES:-3}
DURATION=${DURATION:-10}
SEED=${SEED:-$(( $(date +%s) % 100000 * 10 + RANDOM % 10 ))}
SCENARIO=${SCENARIO:-}
PER_NODE=2
[ -z "$SCENARIO" ] || PER_NODE=4
CLIENTS=${CLIENTS:-$(( PER_NODE * NODES ))}
NODE_MEM=${NODE_MEM:-1536m}
CLIENT_MEM=${CLIENT_MEM:-3g}
CONVERGE_SECS=${CONVERGE_SECS:-300}
NETEM=${NETEM:-1}
PY_IMAGE=python:3.12-alpine
FAULT_IMAGE=serengeti-soakfault
RUN=sk$$
NET=$RUN-cluster
PW='soak test admin password'
KEY='soak test cluster key, at least 32 characters'
OUT=${OUT:-tests/soak/out/$(date -u +%Y%m%dT%H%M%SZ)-n$NODES-s$SEED${SCENARIO:+-$SCENARIO}}
L=(--label "serengeti-soak=$RUN")

[ "$NODES" -ge 3 ] && [ "$NODES" -le 7 ] || { echo "NODES must be 3 to 7"; exit 2; }
command -v python3 >/dev/null || { echo "python3 is needed on the host for the fault scheduler"; exit 2; }
mkdir -p "$OUT/nodes" "$OUT/ops"
OUT=$(cd "$OUT" && pwd)
echo "soak: ${SCENARIO:+scenario $SCENARIO, }seed $SEED, $NODES nodes, $DURATION min, $CLIENTS clients; files in $OUT"
# The checker first finds the defects planted in a made-up history, or nothing below means much.
SOAK_NODES=3 python3 tests/soak/soak.py selftest > "$OUT/selftest.txt" || { cat "$OUT/selftest.txt"; exit 1; }
tail -1 "$OUT/selftest.txt"

cleanup() {
  [ -n "${work_pid:-}" ] && kill "$work_pid" 2>/dev/null || true
  if [ "${KEEP:-0}" = 1 ]; then echo "soak: KEEP=1, containers left with label serengeti-soak=$RUN"; return; fi
  docker ps -aq --filter "label=serengeti-soak=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-soak=$RUN" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=serengeti-soak=$RUN" | xargs -r docker network rm >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ -z "${IMAGE:-}" ]; then
  IMAGE=serengeti:soak
  docker build -q -t "$IMAGE" . >/dev/null
fi
echo "soak: image $IMAGE $(docker image inspect -f '{{.Id}}' "$IMAGE" | cut -c1-19)"
docker build -q -t "$FAULT_IMAGE" - >/dev/null <<'DOCKERFILE'
FROM alpine:3.20
RUN apk add --no-cache iptables iproute2
DOCKERFILE
docker pull -q "$PY_IMAGE" >/dev/null

# Fixed addresses on the cluster network: the partition rules name them, and they stay the same across restarts.
for _ in 1 2 3 4 5; do
  SUB=10.250.$(( (RANDOM % 200) + 20 ))
  docker network create "${L[@]}" --subnet "$SUB.0/24" "$NET" >/dev/null 2>&1 && break
  SUB=
done
[ -n "$SUB" ] || { echo "cannot create the cluster network"; exit 1; }
IPS=""
for i in $(seq 1 "$NODES"); do
  docker network create "${L[@]}" "$RUN-c$i" >/dev/null
  IPS="$IPS $SUB.$(( 10 + i ))"
done

echo "# certificates: one CA, one key store per node naming nX and cX"
certs=$RUN-certs
docker volume create "${L[@]}" "$certs" >/dev/null
docker run --rm "${L[@]}" -v "$certs:/certs" --user 0 -e NODES="$NODES" --entrypoint sh "$IMAGE" -c '
  set -e; cd /certs; P=changeit
  k() { keytool "$@" >/dev/null 2>&1; }
  k -genkeypair -alias ca -keyalg EC -groupname secp256r1 -dname "CN=soak CA" -ext bc:c -validity 3 \
    -storetype PKCS12 -keystore ca.p12 -storepass $P
  k -exportcert -rfc -alias ca -keystore ca.p12 -storepass $P -file ca.pem
  k -importcert -noprompt -alias ca -file ca.pem -storetype PKCS12 -keystore trust.p12 -storepass $P
  for i in $(seq 1 $NODES); do
    k -genkeypair -alias node -keyalg EC -groupname secp256r1 -dname "CN=n$i" -validity 3 \
      -storetype PKCS12 -keystore n$i.p12 -storepass $P
    k -certreq -alias node -keystore n$i.p12 -storepass $P -file n$i.csr
    k -gencert -rfc -alias ca -keystore ca.p12 -storepass $P -infile n$i.csr -outfile n$i.pem -validity 3 \
      -ext san=dns:n$i,dns:c$i -ext eku=serverAuth,clientAuth
    k -importcert -noprompt -alias ca -file ca.pem -keystore n$i.p12 -storepass $P
    k -importcert -noprompt -alias node -file n$i.pem -keystore n$i.p12 -storepass $P
  done
  printf %s $P > pw
  chown 10001:10001 *.p12 pw; chmod 600 *.p12 pw; chmod 644 ca.pem'
docker run --rm "${L[@]}" -v "$certs:/certs" --entrypoint cat "$IMAGE" /certs/ca.pem > "$OUT/ca.pem"

peers_of() {
  local j out=""
  for j in $(seq 1 "$NODES"); do [ "$j" = "$1" ] || out="$out${out:+,}n$j:1985"; done
  echo "$out"
}
for i in $(seq 1 "$NODES"); do
  docker volume create "${L[@]}" "$RUN-data$i" >/dev/null
  docker run -d -m "$NODE_MEM" "${L[@]}" --name "$RUN-n$i" --network "$NET" --ip "$SUB.$(( 10 + i ))" \
    --network-alias "n$i" -v "$RUN-data$i:/data" -v "$certs:/certs:ro" \
    -e SERENGETI_PEERS="$(peers_of "$i")" -e SERENGETI_CLUSTER_KEY="$KEY" \
    -e SERENGETI_TLS_KEYSTORE=/certs/n$i.p12 -e SERENGETI_TLS_KEYSTORE_PASSWORD_FILE=/certs/pw \
    -e SERENGETI_TLS_TRUSTSTORE=/certs/trust.p12 -e SERENGETI_TLS_TRUSTSTORE_PASSWORD_FILE=/certs/pw \
    -e SERENGETI_ADMIN_PASSWORD="$PW" "$IMAGE" >/dev/null
  docker network connect --alias "c$i" "$RUN-c$i" "$RUN-n$i"
done

if [ "$NETEM" = 1 ]; then
  if ! out=$(docker run --rm "${L[@]}" --network "container:$RUN-n1" --cap-add NET_ADMIN "$FAULT_IMAGE" sh -c \
      'IF=$(ip -o -4 addr | grep " '"$SUB.11"'/" | cut -d" " -f2); tc qdisc add dev "$IF" root netem delay 1ms && tc qdisc del dev "$IF" root' 2>&1); then
    echo "soak: tc netem does not work here ($out); running without packet delay and loss (NETEM=0)"
    NETEM=0
  fi
fi

SECS=$(( DURATION * 60 ))
[ -z "$SCENARIO" ] || SECS=20
ENVS=(-e SOAK_SCENARIO="$SCENARIO" -e SOAK_NODES="$NODES" -e SOAK_SEED="$SEED" -e SOAK_SECS="$SECS"
  -e SOAK_OUT=/out -e SOAK_PW="$PW" -e SOAK_KEY="$KEY" -e SOAK_CLIENTS="$CLIENTS"
  -e SOAK_CONVERGE_SECS="$CONVERGE_SECS")
# The client runs as the caller's user, so the files it writes in OUT belong to the caller.
docker run -d -m "$CLIENT_MEM" "${L[@]}" --user "$(id -u):$(id -g)" --name "$RUN-client" --network "$RUN-c1" \
  -v "$PWD/tests/soak:/soak:ro" -v "$OUT:/out" "${ENVS[@]}" "$PY_IMAGE" sleep infinity >/dev/null
for i in $(seq 2 "$NODES"); do docker network connect "$RUN-c$i" "$RUN-client"; done
py() { docker exec "${ENVS[@]}" -e SOAK_T0="${T0:-0}" "$RUN-client" python3 /soak/soak.py "$@"; }

SOAK_SCENARIO=$SCENARIO SOAK_NODES=$NODES SOAK_SEED=$SEED SOAK_SECS=$SECS python3 tests/soak/soak.py plan > "$OUT/plan.txt"
tail -1 "$OUT/plan.txt"
py setup

T0=$(( $(date +%s) + 3 ))
py work > "$OUT/work.out" 2>&1 &
work_pid=$!
nemesis_rc=0
SOAK_SCENARIO=$SCENARIO SOAK_NODES=$NODES SOAK_SEED=$SEED SOAK_SECS=$SECS SOAK_OUT=$OUT SOAK_T0=$T0 SOAK_RUN=$RUN SOAK_IPS="$IPS" \
  SOAK_FAULT_IMAGE=$FAULT_IMAGE SOAK_NETEM=$NETEM python3 tests/soak/soak.py nemesis > "$OUT/nemesis.out" 2>&1 \
  || nemesis_rc=$?
work_rc=0
wait "$work_pid" || work_rc=$?
work_pid=
[ "$nemesis_rc" = 0 ] || { echo "soak: the nemesis failed (exit $nemesis_rc):"; tail -20 "$OUT/nemesis.out"; }
[ "$work_rc" = 0 ] || { echo "soak: the clients failed (exit $work_rc):"; tail -20 "$OUT/work.out"; }

# Whatever happened above, every node runs and no packet rule or netem is left before converging.
for i in $(seq 1 "$NODES"); do
  st=$(docker inspect -f '{{.State.Status}}' "$RUN-n$i")
  case $st in paused) docker unpause "$RUN-n$i" >/dev/null ;; exited|created) docker start "$RUN-n$i" >/dev/null ;; esac
  docker run --rm "${L[@]}" --network "container:$RUN-n$i" --cap-add NET_ADMIN "$FAULT_IMAGE" sh -c \
    'iptables -F INPUT; iptables -F OUTPUT; for d in $(ip -o link | cut -d: -f2 | cut -d@ -f1); do tc qdisc del dev $d root 2>/dev/null; done; true'
done

converge_rc=0
py converge || converge_rc=$?
for i in $(seq 1 "$NODES"); do docker logs "$RUN-n$i" > "$OUT/nodes/n$i.log" 2>&1 || true; done
check_rc=0
py check || check_rc=$?
gzip -f "$OUT"/ops/*.jsonl "$OUT"/nodes/*.log "$OUT/dump-n1.json" 2>/dev/null || true

echo "soak: seed $SEED, $NODES nodes, $DURATION min; event log $OUT/events.log, check details $OUT/check-details.txt"
[ "$nemesis_rc" = 0 ] && [ "$work_rc" = 0 ] && [ "$converge_rc" = 0 ] && [ "$check_rc" = 0 ]
