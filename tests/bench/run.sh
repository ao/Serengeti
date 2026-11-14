#!/usr/bin/env bash
# Benchmarks for the real image, in Docker. Every container runs on a private network made for this run
# (no outside access, nothing published to the host). A load-generator container on the same network
# drives the nodes with oha; see tests/bench/Dockerfile.tools and docs/BENCHMARKS.md.
#
#   tests/bench/run.sh                         builds serengeti:bench, then runs everything (about 40 minutes)
#   QUICK=1 tests/bench/run.sh                 a tiny version of every scenario (about 3 minutes)
#   IMAGE=repo/name:tag tests/bench/run.sh     uses that image as it is and builds nothing
#
# Other settings:
#   SKIP_BUILD=1       reuse the local serengeti:bench
#   SCENARIOS="a b c"  run only these (a, a-tls, b, c, d, e; the default is all, and a-tls is left out of QUICK)
#   NODE_ARGS="..."    extra docker run arguments for every node, split on spaces, e.g.
#                      NODE_ARGS="-e JDK_JAVA_OPTIONS=-Dsome.property=1"; they are printed in the report
#   OUT=dir            where the markdown report, the per-run results (TSV) and oha's raw JSON go
#                      (default tests/bench/out)
#   UPDATE_DOC=1       put the report between the bench markers in docs/BENCHMARKS.md
#   MAX_BUSY_PCT, QUIET_WAIT_SECS, SCENARIO_WAIT_SECS
#                      the run starts once the Docker VM CPUs are at most this busy (default 25 percent),
#                      or after waiting this long (default 900 s; 0 in QUICK), and each scenario waits up to
#                      SCENARIO_WAIT_SECS (default 300 s; 0 in QUICK) the same way; the report says how busy it was
#   REPS WARM SECS READ_SIZES E_SIZES E_IDLE E_SECS E_RATE WRITE_CONCURRENCY READ_CONCURRENCY NODE_MEM
#                      sizes and durations; see the defaults below
#
# The exit status is non-zero only when the harness fails (a node does not start, a statement is refused,
# a count is wrong). Slow numbers and request errors are results, not failures.
set -euo pipefail
cd "$(dirname "$0")/../.."

QUICK=${QUICK:-0}
if [ "$QUICK" = 1 ]; then
  REPS=${REPS:-1}; WARM=${WARM:-1}; SECS=${SECS:-2}
  READ_SIZES=${READ_SIZES:-"200 1000 5000"}
  E_SIZES=${E_SIZES:-"2000"}; E_IDLE=${E_IDLE:-4}; E_SECS=${E_SECS:-8}; E_SETTLE=${E_SETTLE:-2}
  DD_COUNT=${DD_COUNT:-100}
  SCENARIOS=${SCENARIOS:-"a b c d e"}
  QUIET_WAIT_SECS=${QUIET_WAIT_SECS:-0}; SCENARIO_WAIT_SECS=${SCENARIO_WAIT_SECS:-0}
else
  REPS=${REPS:-3}; WARM=${WARM:-5}; SECS=${SECS:-15}
  READ_SIZES=${READ_SIZES:-"1000 10000 100000"}
  E_SIZES=${E_SIZES:-"10000 100000"}; E_IDLE=${E_IDLE:-30}; E_SECS=${E_SECS:-60}; E_SETTLE=${E_SETTLE:-15}
  DD_COUNT=${DD_COUNT:-500}
  SCENARIOS=${SCENARIOS:-"a a-tls b c d e"}
  QUIET_WAIT_SECS=${QUIET_WAIT_SECS:-900}; SCENARIO_WAIT_SECS=${SCENARIO_WAIT_SECS:-300}
fi
MAX_BUSY_PCT=${MAX_BUSY_PCT:-25}
NODE_ARGS=${NODE_ARGS:-}
E_RATE=${E_RATE:-20}
E_CONCURRENCY=${E_CONCURRENCY:-32}
want() { case " $SCENARIOS " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
WRITE_CONCURRENCY=${WRITE_CONCURRENCY:-"1 8 32"}
READ_CONCURRENCY=${READ_CONCURRENCY:-8}
NODE_MEM=${NODE_MEM:-1g}
TOOLS_MEM=${TOOLS_MEM:-384m}
OUT=${OUT:-tests/bench/out}
DOC=docs/BENCHMARKS.md

RUN=bn$$
NET=$RUN-net
L=(--label "serengeti-bench=$RUN")
TOOLS=$RUN-tools
random() { LC_ALL=C tr -dc 'a-zA-Z0-9' </dev/urandom | head -c "$1" || true; }
PW=$(random 24)
CLUSTER_KEY=$(random 40)
STORE_PW=$(random 20)

if [ "$QUICK" = 1 ] && [ "${UPDATE_DOC:-0}" = 1 ]; then
  echo "refusing UPDATE_DOC=1 with QUICK=1: the quick numbers are not measurements to publish" >&2
  exit 2
fi

cleanup() {
  docker ps -aq --filter "label=serengeti-bench=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-bench=$RUN" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=serengeti-bench=$RUN" | xargs -r docker network rm >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT TERM

T0=$(date +%s)
say() { printf '%s\n' "$*"; }
step() { printf '\n# [%ss] %s\n' "$(($(date +%s) - T0))" "$*"; }

mkdir -p "$OUT/raw"
TSV=$OUT/results.tsv
: > "$TSV"

# ---- images ----

if [ -z "${IMAGE:-}" ]; then
  IMAGE=serengeti:bench
  if [ "${SKIP_BUILD:-0}" != 1 ]; then docker build -q -t "$IMAGE" . >/dev/null; fi
fi
docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo "no such image: $IMAGE" >&2; exit 1; }
OHA_IMAGE=$(sed -n 's/^ARG OHA_IMAGE=//p' tests/bench/Dockerfile.tools)
docker build -q -t serengeti-bench-tools -f tests/bench/Dockerfile.tools tests/bench >/dev/null

# ---- containers ----

docker network create --internal "${L[@]}" "$NET" >/dev/null

certs=$RUN-certs
docker volume create "${L[@]}" "$certs" >/dev/null
docker run --rm "${L[@]}" -v "$certs:/certs" --user 0 --entrypoint sh -e P="$STORE_PW" "$IMAGE" -c '
  set -e; cd /certs
  k() { keytool -J-XX:TieredStopAtLevel=1 "$@" >/dev/null 2>&1; }
  k -genkeypair -alias ca -keyalg EC -groupname secp256r1 -dname "CN=bench CA" -ext bc:c -validity 3 \
    -storetype PKCS12 -keystore ca.p12 -storepass "$P"
  k -exportcert -rfc -alias ca -keystore ca.p12 -storepass "$P" -file ca.pem
  k -importcert -noprompt -alias ca -file ca.pem -storetype PKCS12 -keystore trust.p12 -storepass "$P"
  pids=
  for i in 1 2 3; do
    (
    k -genkeypair -alias node -keyalg EC -groupname secp256r1 -dname "CN=n$i" -validity 3 \
      -storetype PKCS12 -keystore n$i.p12 -storepass "$P"
    k -certreq -alias node -keystore n$i.p12 -storepass "$P" -file n$i.csr
    k -gencert -rfc -alias ca -keystore ca.p12 -storepass "$P" -infile n$i.csr -outfile n$i.pem -validity 3 \
      -ext san=dns:n$i -ext eku=serverAuth,clientAuth
    k -importcert -noprompt -alias ca -file ca.pem -keystore n$i.p12 -storepass "$P"
    k -importcert -noprompt -alias node -file n$i.pem -keystore n$i.p12 -storepass "$P"
    ) &
    pids="$pids $!"
  done
  for p in $pids; do wait "$p"; done
  printf %s "$P" > pw
  chown 10001:10001 *.p12 pw; chmod 600 *.p12 pw; chmod 644 ca.pem'

docker run -d -m "$TOOLS_MEM" "${L[@]}" --name "$TOOLS" --network "$NET" -v "$certs:/certs:ro" \
  -v "$PWD/tests/bench:/bench:ro" -e BENCH_AUTH="admin:$PW" -e BENCH_CACERT=/certs/ca.pem \
  -e CLUSTER_KEY="$CLUSTER_KEY" --entrypoint sleep serengeti-bench-tools 86400 >/dev/null
docker exec "$TOOLS" mkdir -p /work

# drv SUBCOMMAND ARGS...: run the driver in the load-generator container; DENV holds "-e NAME=value" pairs.
DENV=()
drv() { docker exec ${DENV[@]+"${DENV[@]}"} "$TOOLS" /bench/driver.sh "$@"; }
sql() { drv sql "$1" "$2"; }   # base_url statement -> JSON answer

node_n=0
start_node() { # name network-alias fsync [extra docker run args]: one node; the data volume is made here
  local name=$1 host=$2 fsync=$3
  shift 3
  docker volume create "${L[@]}" "$RUN-$name" >/dev/null
  docker run -d -m "$NODE_MEM" --no-healthcheck "${L[@]}" --name "$RUN-$name" --network "$NET" \
    --network-alias "$host" -v "$RUN-$name:/data" -e SERENGETI_FSYNC="$fsync" \
    -e SERENGETI_ADMIN_PASSWORD="$PW" ${NODE_ARGS:+$NODE_ARGS} "$@" "$IMAGE" >/dev/null
}

drop_nodes() { # removes every node and data volume; the network, certificates and tools stay
  docker ps -aq --filter "label=serengeti-bench=$RUN" --filter "name=$RUN-node" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-bench=$RUN" --filter "name=$RUN-node" | xargs -r docker volume rm >/dev/null 2>&1 || true
}

TLS_ARGS=(-v "$certs:/certs:ro" -e SERENGETI_TLS_KEYSTORE_PASSWORD_FILE=/certs/pw
  -e SERENGETI_TLS_TRUSTSTORE=/certs/trust.p12 -e SERENGETI_TLS_TRUSTSTORE_PASSWORD_FILE=/certs/pw)

wait_health() { # container base_url
  for _ in $(seq 1 120); do
    if [ "$(drv health "$2")" = 200 ]; then return 0; fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$1")" != true ]; then
      echo "$1 exited:"; docker logs "$1" 2>&1 | tail -8; return 1
    fi
    sleep 0.5
  done
  echo "$1 did not answer /health"; docker logs "$1" 2>&1 | tail -8
  return 1
}

start_single() { # fsync tls(0|1) -> a node reachable as n1 at $BASE, with database bench
  node_n=$((node_n + 1))
  if [ "$2" = 1 ]; then
    start_node "node$node_n" n1 "$1" "${TLS_ARGS[@]}" -e SERENGETI_TLS_KEYSTORE=/certs/n1.p12
    BASE=https://n1:1985
  else
    start_node "node$node_n" n1 "$1"
    BASE=http://n1:1985
  fi
  NODE1=$RUN-node$node_n
  wait_health "$NODE1" "$BASE"
  sql "$BASE" "CREATE DATABASE bench" >/dev/null
}

peers_of() { case $1 in 1) echo n2:1985,n3:1985 ;; 2) echo n1:1985,n3:1985 ;; 3) echo n1:1985,n2:1985 ;; esac; }

start_cluster() { # three TLS nodes n1..n3, all writable; $BASE is n1
  for i in 1 2 3; do
    node_n=$((node_n + 1))
    start_node "node$node_n-n$i" "n$i" always "${TLS_ARGS[@]}" -e SERENGETI_TLS_KEYSTORE="/certs/n$i.p12" \
      -e SERENGETI_PEERS="$(peers_of "$i")" -e SERENGETI_CLUSTER_KEY="$CLUSTER_KEY"
  done
  BASE=https://n1:1985
  CNODES=("$RUN-node$((node_n - 2))-n1" "$RUN-node$((node_n - 1))-n2" "$RUN-node$node_n-n3")
  for i in 1 2 3; do
    wait_health "${CNODES[$((i - 1))]}" "https://n$i:1985"
    local ready=0
    for _ in $(seq 1 120); do
      st=$(drv status "https://n$i:1985" || true)
      case "$st" in *'"caught_up":true'*'"reachable":3,'*|*'"reachable":3,'*'"caught_up":true'*) ready=1; break ;; esac
      sleep 0.5
    done
    if [ "$ready" != 1 ]; then echo "node n$i never became writable: $st"; docker logs "${CNODES[$((i - 1))]}" 2>&1 | tail -8; return 1; fi
  done
  sql "$BASE" "CREATE DATABASE bench" >/dev/null
}

wait_converged() { # table [count]: the three nodes report the same row count (and the given one)
  local a b c
  for _ in $(seq 1 240); do
    a=$(drv count https://n1:1985 "$1" 2>/dev/null || true)
    b=$(drv count https://n2:1985 "$1" 2>/dev/null || true)
    c=$(drv count https://n3:1985 "$1" 2>/dev/null || true)
    if [ -n "$a" ] && [ "$a" = "$b" ] && [ "$b" = "$c" ] && { [ -z "${2:-}" ] || [ "$a" = "$2" ]; }; then return 0; fi
    sleep 1
  done
  echo "counts of $1 on n1, n2, n3: $a $b $c (wanted ${2:-equal})"
  return 1
}

# ---- results ----

record() { local IFS=$'\t'; printf '%s\n' "$*" >> "$TSV"; }

result() { # scenario config key rep ok errors rps p50 p95 p99 max fsyncs fsync_s check detail
  record L "$@"
  local fs=""
  if [ "${12}" -gt 0 ] 2>/dev/null; then
    fs=$(awk -v n="${12}" -v s="${13}" 'BEGIN { printf ", fsync mean %.2f ms", s / n * 1000 }')
  fi
  printf 'RESULT %s | %s | %s | run %s | %s req/s | p50 %s ms | p95 %s ms | p99 %s ms | errors %s of %s%s%s\n' \
    "$1" "$2" "$3" "$4" "$7" "$8" "$9" "${10}" "$6" "$(($5 + $6))" "$fs" "${15:+ | ${15}}"
}

# measure_writes scenario config conc rep base_url [metrics_url]: INSERT without _id, new table per run
measure_writes() {
  local sc=$1 cfg=$2 c=$3 rep=$4 base=$5
  local tbl="bench.t_${sc//-/_}_c${c}_r${rep}" label="$1-$2-c$3-r$4"
  label=${label//[^a-zA-Z0-9_.-]/_}
  sql "$base" "CREATE TABLE $tbl" >/dev/null
  local line ok err rps p50 p95 p99 max warm_ok f s detail
  DENV=(-e "URL=$base" -e "CONC=$c" -e "SECS=$SECS" -e "WARM=$WARM" -e "METRICS_URL=$base"
    -e "BODY={\"query\": \"INSERT INTO $tbl (n, s) VALUES (1, 'row-0000001-0123456789abcdef')\"}")
  line=$(drv run "$label")
  IFS=$'\t' read -r ok err rps p50 p95 p99 max warm_ok f s detail <<< "$line"
  docker exec "$TOOLS" cat "/work/$label.json" > "$OUT/raw/$label.json"
  local check="rows ok" got want=$((ok + warm_ok))
  got=$(drv count "$base" "$tbl")
  if [ "$err" != 0 ]; then check="rows $got of $want acknowledged"
  elif [ "$got" != "$want" ]; then echo "harness: $tbl holds $got rows, $want inserts were acknowledged" >&2; exit 1; fi
  DENV=()
  result "$sc" "$cfg" "$c" "$rep" "$ok" "$err" "$rps" "$p50" "$p95" "$p99" "$max" "$f" "$s" "$check" "${detail:-}"
}

# ---- machine and method ----

host_os=$(uname -s)
if [ "$host_os" = Darwin ]; then
  HOST_CPU=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)
  HOST_CORES=$(sysctl -n hw.ncpu 2>/dev/null || echo "?")
  HOST_MEM_GB=$(sysctl -n hw.memsize 2>/dev/null | awk '{ printf "%d", $1 / 1073741824 }' || true)
  HOST_NAME="macOS $(sw_vers -productVersion 2>/dev/null || true) ($(uname -m))"
else
  HOST_CPU=$(awk -F': ' '/model name/ { print $2; exit }' /proc/cpuinfo 2>/dev/null || echo unknown)
  HOST_CORES=$(nproc 2>/dev/null || echo "?")
  HOST_MEM_GB=$(awk '/MemTotal/ { printf "%d", $2 / 1048576 }' /proc/meminfo 2>/dev/null || true)
  HOST_NAME="$host_os $(uname -r) ($(uname -m))"
fi
DOCKER_INFO=$(docker info --format '{{.OperatingSystem}}; Docker {{.ServerVersion}}; kernel {{.KernelVersion}}; {{.NCPU}} CPUs; {{.MemTotal}} bytes; {{.Architecture}}')
ORB=$(orbctl version 2>/dev/null | tr '\n' ' ' | sed 's/ *$//' || true)
IMAGE_ID=$(docker image inspect "$IMAGE" --format '{{.Id}}')
IMAGE_REPO_DIGEST=$(docker image inspect "$IMAGE" --format '{{join .RepoDigests ", "}}')
IMAGE_ARCH=$(docker image inspect "$IMAGE" --format '{{.Os}}/{{.Architecture}}')
docker volume create "${L[@]}" "$RUN-fstype" >/dev/null
docker volume create "${L[@]}" "$RUN-ddvol" >/dev/null
# One container for the facts about the image: version, Java, heap under the node memory cap, CPUs, file system.
IMAGE_FACTS=$(docker run --rm "${L[@]}" -m "$NODE_MEM" -v "$RUN-fstype:/data" --entrypoint sh "$IMAGE" -c '
  echo "version=$(java -jar /opt/serengeti/serengeti.jar --version 2>/dev/null | sed -n 1p)"
  echo "java=$(java -version 2>&1 | grep -v "^Picked up" | sed -n "1p;2p" | tr "\n" ";" | sed "s/;$//")"
  echo "heap=$(java -XX:+PrintFlagsFinal -version 2>/dev/null | awk "/ MaxHeapSize /{printf \"%d\", \$4 / 1048576}")"
  m=$(awk -F": " "/model name/{print \$2; exit}" /proc/cpuinfo)
  if [ -z "$m" ]; then m="no model name in /proc/cpuinfo (CPU implementer $(awk -F": " "/CPU implementer/{print \$2; exit}" /proc/cpuinfo))"; fi
  echo "cpus=$(nproc) CPUs; $m"
  echo "fs=$(stat -f -c %T /data)"')
fact() { printf '%s\n' "$IMAGE_FACTS" | sed -n "s/^$1=//p"; }
SERENGETI_VERSION=$(fact version)
JAVA_VERSION=$(fact java)
HEAP_MAX=$(fact heap)
IN_DOCKER=$(fact cpus)
FS_TYPE=$(fact fs)
TOOL_VERSION=$(docker exec "$TOOLS" oha --version)
OHA_DIGEST=$(docker image inspect "$OHA_IMAGE" --format '{{join .RepoDigests ", "}}' 2>/dev/null || true)
GIT_COMMIT=$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)
GIT_SRC=$(git rev-parse --short=12 HEAD:src 2>/dev/null || echo unknown)
GIT_DIRTY=""
if [ -n "$(git status --porcelain -- . ":(exclude)$OUT" 2>/dev/null)" ]; then GIT_DIRTY=" (working tree has uncommitted changes)"; fi
START_UTC=$(date -u +%Y-%m-%dT%H:%MZ)
OTHER_CONTAINERS=$(( $(docker ps -q | wc -l) - $(docker ps -q --filter "label=serengeti-bench=$RUN" | wc -l) ))
LOAD_START=$(docker exec "$TOOLS" cut -d' ' -f1-3 /proc/loadavg)

say "benchmark of $IMAGE ($SERENGETI_VERSION), $START_UTC, QUICK=$QUICK"
say "host: $HOST_CPU, $HOST_CORES cores, ${HOST_MEM_GB} GB; Docker sees: $IN_DOCKER; $DOCKER_INFO"
say "image $IMAGE_ID ($IMAGE_ARCH); $JAVA_VERSION; $FS_TYPE data volume; $TOOL_VERSION; commit $GIT_COMMIT$GIT_DIRTY"

# ---- other load on the machine ----

# busy_pct SECONDS: how busy the Docker VM's CPUs were over that time, all containers together (the
# counters in /proc/stat belong to the VM, not to the container that reads them).
busy_pct() {
  docker exec "$TOOLS" sh -c 'a=$(head -1 /proc/stat); sleep "$1"; b=$(head -1 /proc/stat); echo "$a $b"' sh "$1" |
    awk '{ for (i = 2; i <= 9; i++) { t0 += $i; t1 += $(i + 11) }
           printf "%.0f", 100 * (1 - (($16 + $17) - ($5 + $6)) / (t1 - t0)) }'
}

# ambient SCENARIO: with none of the benchmark's nodes running, how busy is the VM with other work? Waits
# up to SCENARIO_WAIT_SECS for it to drop to MAX_BUSY_PCT, then records what it found and goes on.
ambient() {
  local b waited=0
  while :; do
    b=$(busy_pct 5)
    if [ "$b" -le "$MAX_BUSY_PCT" ] || [ "$waited" -ge "$SCENARIO_WAIT_SECS" ]; then break; fi
    sleep 25
    waited=$((waited + 30))
  done
  record A "$1" "$b" "$waited"
  say "RESULT ambient | before $1 | the Docker VM's CPUs were $b% busy with no benchmark node running (waited $waited s)"
}

waited=0
while :; do
  b=$(busy_pct 10)
  if [ "$b" -le "$MAX_BUSY_PCT" ]; then break; fi
  if [ "$waited" -ge "$QUIET_WAIT_SECS" ]; then
    say "the Docker VM stayed $b% busy for $waited s; starting anyway, the numbers are affected by that load"
    break
  fi
  say "waiting for the Docker VM to quiet down: $b% busy (starts at $MAX_BUSY_PCT%)"
  sleep 20
  waited=$((waited + 30))
done
record Q "$b" "$waited"

# ---- disk baseline ----

step "disk: one 4 KiB O_DSYNC write at a time, in a data volume of the image ($DD_COUNT writes)"
dd_out=$(docker run --rm "${L[@]}" --user 0 -v "$RUN-ddvol:/data" --entrypoint sh "$IMAGE" -c \
  "dd if=/dev/zero of=/data/dd.tmp bs=4k count=$DD_COUNT oflag=dsync 2>&1 | tail -1")
DD_MS=$(printf '%s' "$dd_out" | sed -n 's/.*copied, \([0-9.]*\) s.*/\1/p' | awk -v n="$DD_COUNT" '{ printf "%.3f", $1 / n * 1000 }')
[ -n "$DD_MS" ] || { echo "harness: cannot read dd output: $dd_out" >&2; exit 1; }
record D "$DD_COUNT" "$DD_MS"
say "RESULT disk | O_DSYNC 4 KiB write | mean $DD_MS ms each ($DD_COUNT writes)"

# ---- a, b: single node writes ----

single_writes() { # scenario fsync tls label
  local sc=$1 fsync=$2 tls=$3 cfg=$4
  step "$sc: single node, fsync=$fsync$([ "$tls" = 1 ] && echo ', TLS'), INSERT concurrency: $WRITE_CONCURRENCY"
  start_single "$fsync" "$tls"
  # One throwaway run so the JVM has compiled the hot paths before run 1 of the first cell.
  local c rep
  DENV=(-e "URL=$BASE" -e "CONC=8" -e "SECS=$((WARM * 2))" -e "WARM=0")
  sql "$BASE" "CREATE TABLE bench.warmup" >/dev/null
  DENV+=(-e "BODY={\"query\": \"INSERT INTO bench.warmup (n) VALUES (1)\"}")
  drv run "$sc-warmup" >/dev/null
  DENV=()
  for rep in $(seq 1 "$REPS"); do
    for c in $WRITE_CONCURRENCY; do
      measure_writes "$sc" "$cfg" "$c" "$rep" "$BASE"
    done
  done
  drop_nodes
}

if want a; then ambient a; single_writes a always 0 "fsync=always"; fi
if want a-tls; then ambient a-tls; single_writes a-tls always 1 "fsync=always, TLS"; fi
if want b; then
  ambient b
  single_writes b interval 0 "fsync=interval"
  single_writes b off 0 "fsync=off"
fi

# ---- c: single node reads ----

if want c; then
ambient c
step "c: single node reads, concurrency $READ_CONCURRENCY, tables of $READ_SIZES rows"
start_single always 0
for size in $READ_SIZES; do
  tbl=bench.r$size
  sql "$BASE" "CREATE TABLE $tbl" >/dev/null
  drv preload "$BASE" "$tbl" 0 "$size"
  [ "$(drv count "$BASE" "$tbl")" = "$size" ] || { echo "harness: preload of $tbl is short" >&2; exit 1; }
  docker exec "$TOOLS" sh -c "/bench/driver.sh gen-select $tbl $size 500 > /work/select-$size.txt"
done
for size in $READ_SIZES; do
  tbl=bench.r$size
  for rep in $(seq 1 "$REPS"); do
    for kind in select count; do
      if [ "$kind" = select ]; then
        DENV=(-e "BODY_FILE=/work/select-$size.txt"); cfg="SELECT * WHERE n = x"
      else
        DENV=(-e "BODY={\"query\": \"SELECT COUNT(*) FROM $tbl\"}"); cfg="SELECT COUNT(*)"
      fi
      label="c-$kind-$size-r$rep"
      DENV+=(-e "URL=$BASE" -e "CONC=$READ_CONCURRENCY" -e "SECS=$SECS" -e "WARM=$WARM")
      line=$(drv run "$label")
      IFS=$'\t' read -r ok err rps p50 p95 p99 max warm_ok f s detail <<< "$line"
      docker exec "$TOOLS" cat "/work/$label.json" > "$OUT/raw/$label.json"
      DENV=()
      result c "$cfg" "$size" "$rep" "$ok" "$err" "$rps" "$p50" "$p95" "$p99" "$max" 0 0 "" "${detail:-}"
    done
  done
done
drop_nodes
fi

# ---- d: three-node TLS cluster writes ----

if want d; then
ambient d
step "d: 3-node TLS cluster, majority writes through n1, INSERT concurrency: $WRITE_CONCURRENCY"
start_cluster
sql "$BASE" "CREATE TABLE bench.warmup" >/dev/null
DENV=(-e "URL=$BASE" -e "CONC=8" -e "SECS=$((WARM * 2))" -e "WARM=0"
  -e "BODY={\"query\": \"INSERT INTO bench.warmup (n) VALUES (1)\"}")
drv run d-warmup >/dev/null
DENV=()
for rep in $(seq 1 "$REPS"); do
  for c in $WRITE_CONCURRENCY; do
    measure_writes d "3 nodes, TLS, fsync=always" "$c" "$rep" "$BASE"
    wait_converged "bench.t_d_c${c}_r${rep}"
  done
done
drop_nodes
fi

# ---- e: what the sync costs ----

# snap CONTAINER: "uptime-seconds cpu-microseconds rx-bytes tx-bytes", read inside the container so that
# the counters and the clock come from the same moment. The node images have sh, awk and tr.
snap() {
  docker exec "$1" sh -c '
    read -r up _ < /proc/uptime
    cpu=$(awk "/^usage_usec/ { print \$2 }" /sys/fs/cgroup/cpu.stat)
    set -- $(tr ":" " " < /proc/net/dev | awk "\$1 != \"lo\" && \$2 ~ /^[0-9]+\$/ { rx += \$2; tx += \$10 } END { print rx + 0, tx + 0 }")
    echo "$up $cpu $1 $2"'
}

# window SIZE PHASE: snapshots before and after, then records per-node CPU and network rates
e_window_start() { SNAP0=("$(snap "${CNODES[0]}")" "$(snap "${CNODES[1]}")" "$(snap "${CNODES[2]}")" "$(snap "$TOOLS")"); }
e_window_end() { # size phase
  local size=$1 phase=$2 i before after client

  local -a after_snaps=("$(snap "${CNODES[0]}")" "$(snap "${CNODES[1]}")" "$(snap "${CNODES[2]}")" "$(snap "$TOOLS")")
  for i in 1 2 3; do
    before=${SNAP0[$((i - 1))]}; after=${after_snaps[$((i - 1))]}
    # Node 1 also carries the client's traffic: take the load generator's counters off its totals.
    client="0 0"
    if [ "$i" = 1 ]; then client=$(awk -v b="${SNAP0[3]}" -v a="${after_snaps[3]}" 'BEGIN { split(b, x, " "); split(a, y, " "); print y[4] - x[4], y[3] - x[3] }'); fi
    awk -v size="$size" -v phase="$phase" -v node="n$i" -v b="$before" -v a="$after" -v c="$client" 'BEGIN {
      split(b, x, " "); split(a, y, " "); split(c, z, " ")
      secs = y[1] - x[1]
      printf "E\t%s\t%s\t%s\t%.3f\t%.1f\t%.1f\t%.1f\n", size, phase, node, (y[2] - x[2]) / 1e6 / secs,
        ((y[3] - x[3]) - z[1]) / secs, ((y[4] - x[4]) - z[2]) / secs, secs }' | tee -a "$TSV" | awk -F'\t' '{
      printf "RESULT e | %s rows | %s | %s | cpu %.2f cores | peer traffic in %.1f KB/s, out %.1f KB/s | over %.1f s\n",
        $2, $3, $4, $5, $6 / 1000, $7 / 1000, $8 }'
  done
}

if want e; then
ambient e
for size in $E_SIZES; do
  step "e: 3-node TLS cluster, table of $size rows, then $E_RATE writes/s for $E_SECS s"
  start_cluster
  sql "$BASE" "CREATE TABLE bench.e" >/dev/null
  drv preload "$BASE" bench.e 0 "$size"
  wait_converged bench.e "$size" || { echo "harness: the nodes did not converge after the preload" >&2; exit 1; }
  sleep "$E_SETTLE"
  pull_bytes=$(drv state-size https://n1:1985 bench)
  record EM "$size" "$pull_bytes"
  say "RESULT e | $size rows | one full /internal/state answer from n1: $pull_bytes bytes"

  say "# idle: no client requests for $E_IDLE s"
  e_window_start
  sleep "$E_IDLE"
  e_window_end "$size" idle

  say "# $E_RATE writes/s for $E_SECS s through n1"
  DENV=(-e "URL=$BASE" -e "CONC=$E_CONCURRENCY" -e "SECS=$E_SECS" -e "WARM=0" -e "QPS=$E_RATE"
    -e "BODY={\"query\": \"INSERT INTO bench.e (n, s) VALUES (0, 'row-0000000-0123456789abcdef')\"}")
  e_window_start
  line=$(drv run "e-$size")
  e_window_end "$size" "write"
  DENV=()
  IFS=$'\t' read -r ok err rps p50 p95 p99 max warm_ok f s detail <<< "$line"
  docker exec "$TOOLS" cat "/work/e-$size.json" > "$OUT/raw/e-$size.json"
  record EW "$size" "$ok" "$err" "$rps" "$p50" "$p95" "$p99"
  say "RESULT e | $size rows | writes: $ok ok, $err errors, $rps/s, p50 $p50 ms, p95 $p95 ms, p99 $p99 ms ${detail:+| $detail}"
  mem=$(docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' "${CNODES[@]}" | sort |
    awk '{ sub(/.*-/, "", $1); printf "%s%s %s", (NR > 1 ? ", " : ""), $1, $2 }')
  record EM2 "$size" "$mem"
  say "RESULT e | $size rows | memory in use at the end: $mem"
  wait_converged bench.e || { echo "harness: the nodes did not converge after the writes" >&2; exit 1; }
  drop_nodes
done
fi

LOAD_END=$(docker exec "$TOOLS" cut -d' ' -f1-3 /proc/loadavg)
END_UTC=$(date -u +%Y-%m-%dT%H:%MZ)

# ---- report ----

# latency_table SCENARIO KEYHEADER MODE [CONFIGHEADER]: one row per (config, key); the run with the median
# rate stands for the cell. MODE 0: key only; 1: config, key and the mean fsync; 2: config and key.
latency_table() {
  local sc=$1 keyname=$2 showcfg=$3 cfgname=${4:-Config}
  if [ "$showcfg" = 1 ]; then
    printf '| %s | %s | Requests/s | Range over runs | p50 ms | p95 ms | p99 ms | Errors | Mean fsync ms |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|\n' "$cfgname" "$keyname"
  elif [ "$showcfg" = 2 ]; then
    printf '| %s | %s | Requests/s | Range over runs | p50 ms | p95 ms | p99 ms | Errors |\n|---|---:|---:|---:|---:|---:|---:|---:|\n' "$cfgname" "$keyname"
  else
    printf '| %s | Requests/s | Range over runs | p50 ms | p95 ms | p99 ms | Errors |\n|---|---:|---:|---:|---:|---:|---:|\n' "$keyname"
  fi
  awk -F'\t' -v sc="$sc" '$1 == "L" && $2 == sc { print $3 "\t" $4 }' "$TSV" | awk '!seen[$0]++' |
  while IFS=$'\t' read -r cfg key; do
    awk -F'\t' -v sc="$sc" -v cfg="$cfg" -v key="$key" -v showcfg="$showcfg" '
      $1 == "L" && $2 == sc && $3 == cfg && $4 == key { rows[++n] = $0; rate[n] = $8; ok += $6; err += $7 }
      END {
        # order the runs by rate, then take the middle one
        for (i = 1; i <= n; i++) idx[i] = i
        for (i = 2; i <= n; i++) { v = idx[i]; j = i - 1; while (j >= 1 && rate[idx[j]] > rate[v]) { idx[j + 1] = idx[j]; j-- } idx[j + 1] = v }
        m = idx[int((n + 1) / 2)]
        split(rows[m], f, "\t")
        lo = rate[idx[1]]; hi = rate[idx[n]]
        range = (n > 1) ? sprintf("%.0f to %.0f", lo, hi) : "1 run"
        fsync = (f[13] > 0) ? sprintf("%.2f", f[14] / f[13] * 1000) : "0 forces"
        if (showcfg == 1) printf "| %s | %s | %.0f | %s | %.1f | %.1f | %.1f | %d of %d | %s |\n", cfg, key, f[8], range, f[9], f[10], f[11], err, ok + err, fsync
        else if (showcfg == 2) printf "| %s | %s | %.0f | %s | %.1f | %.1f | %.1f | %d of %d |\n", cfg, key, f[8], range, f[9], f[10], f[11], err, ok + err
        else printf "| %s | %.0f | %s | %.1f | %.1f | %.1f | %d of %d |\n", key, f[8], range, f[9], f[10], f[11], err, ok + err
      }' "$TSV"
  done
}

has() { awk -F'\t' -v s="$1" '($1 == "L" && $2 == s) || ($1 == "E" && s == "e") { f = 1 } END { exit !f }' "$TSV"; }
report() {
  say "### Machine and method"
  say
  say "- Date: $START_UTC to $END_UTC (UTC)."
  say "- Host: $HOST_CPU, $HOST_CORES cores, ${HOST_MEM_GB} GB, $HOST_NAME."
  say "- Docker: $DOCKER_INFO${ORB:+; OrbStack $ORB}. Containers see $IN_DOCKER."
  say "- Other load: the machine is shared with other work that the benchmark neither limits nor stops. $OTHER_CONTAINERS other containers were running at the start. Before the first scenario the Docker VM's CPUs were $(awk -F'\t' '$1 == "Q" { print $2 }' "$TSV")% busy, after waiting $(awk -F'\t' '$1 == "Q" { print $3 }' "$TSV") s for at most $MAX_BUSY_PCT%. Busy percentage over 5 s before each scenario, with none of the benchmark's nodes running: $(awk -F'\t' '$1 == "A" { printf "%s%s %s%%", (n++ ? ", " : ""), $2, $3 }' "$TSV"). Load averages (1, 5, 15 minutes) at the start: $LOAD_START; at the end: $LOAD_END."
  say "- Memory caps: $NODE_MEM per Serengeti node (the JVM's maximum heap is then $HEAP_MAX MiB), $TOOLS_MEM for the load generator. No CPU limits."
  say "- Image: $IMAGE ($IMAGE_ARCH), id $IMAGE_ID${IMAGE_REPO_DIGEST:+, digest $IMAGE_REPO_DIGEST}. $SERENGETI_VERSION. Java: $JAVA_VERSION."
  say "- Source: commit $GIT_COMMIT$GIT_DIRTY, tree of src/ $GIT_SRC."
  say "- Load generator: $TOOL_VERSION from \`$OHA_IMAGE\`${OHA_DIGEST:+ ($OHA_DIGEST)}, in a container on the same private Docker network as the nodes. HTTP/1.1 with keep-alive, Basic auth, one connection per concurrent request."
  say "- Data directory: a Docker volume on $FS_TYPE inside the Docker VM. One 4 KiB O_DSYNC write there takes $(awk -F'\t' '$1 == "D" { print $3 }' "$TSV") ms on average ($(awk -F'\t' '$1 == "D" { print $2 }' "$TSV") writes, \`dd oflag=dsync\`), the cost of one \`fsync=always\` write at best. Whether the VM passes the flush on to the Mac's SSD was not tested."
  say "- Runs: each cell is $REPS run(s) of $SECS s after a $WARM s warm-up that is not counted, each on a new table. The table shows the run with the median request rate, with the lowest and highest rate of the runs. Latency is the time from sending a request to the end of its response, as the load generator saw it. Errors are non-200 answers and failed requests, over all runs of the cell, with the number of requests in the cell."
  say "- Rows: \`{\"_id\": <uuid>, \"n\": <integer>, \"s\": <28-character string>}\`. After each insert run the table's row count is checked against the number of acknowledged inserts."
  say "- Settings: defaults except \`SERENGETI_FSYNC\`, the cluster settings and TLS. The server keeps a verified password for 60 s, so a run may include one bcrypt check; the server answers on 16 threads, so concurrency 32 queues."
  if [ -n "$NODE_ARGS" ]; then say "- Extra arguments for every node (not the image default): \`$NODE_ARGS\`."; fi
  say "- Reproduce: \`tests/bench/run.sh\` (\`QUICK=1\` for a short run; \`IMAGE=name:tag\` for another image)."
  say
  if has a; then
  say "### a. Single node, plain HTTP, fsync=always: INSERT"
  say
  latency_table a "Concurrency" 0
  fi
  say
  if has a-tls; then
  say "### a2. Single node, TLS, fsync=always: INSERT"
  say
  latency_table a-tls "Concurrency" 0
  fi
  say
  if has b; then
  say "### b. Single node, plain HTTP: INSERT with other fsync settings"
  say
  latency_table b "Concurrency" 1
  fi
  say
  if has c; then
  say "### c. Single node reads, concurrency $READ_CONCURRENCY"
  say
  say "\`SELECT * FROM t WHERE n = x\` scans the whole table (there are no indexes) and returns one row; x is a random value, the same sequence in every run."
  say
  latency_table c "Rows in table" 2 Query
  fi
  say
  if has d; then
  say "### d. Three-node TLS cluster, fsync=always, majority writes: INSERT through n1"
  say
  latency_table d "Concurrency" 0
  fi
  say
  if has e; then
  say "### e. Sync cost, three-node TLS cluster, table preloaded, $E_RATE writes/s for $E_SECS s through n1"
  say
  say "Each size is a new cluster. The idle window ($E_IDLE s) has no client requests. Rates are per node over the window, from the container's cgroup CPU counter and its network counters; for n1 the load generator's own traffic is subtracted, so the network columns are node-to-node traffic only."
  say
  printf '| Rows | Window | Node | CPU (cores) | Peer traffic in KB/s | Peer traffic out KB/s |\n|---:|---|---|---:|---:|---:|\n'
  awk -F'\t' '$1 == "E" { printf "| %s | %s | %s | %.2f | %.1f | %.1f |\n", $2, ($3 == "idle" ? "idle" : "writes"), $4, $5, $6 / 1000, $7 / 1000 }' "$TSV"
  say
  printf '| Rows | One full state answer from n1 | Writes ok / errors | Writes/s | Write p50 ms | p95 ms | p99 ms | Memory in use at the end |\n|---:|---:|---:|---:|---:|---:|---:|---|\n'
  for size in $E_SIZES; do
    awk -F'\t' -v size="$size" '
      $1 == "EM" && $2 == size { pull = $3 }
      $1 == "EW" && $2 == size { w = sprintf("%d / %d | %.1f | %.1f | %.1f | %.1f", $3, $4, $5, $6, $7, $8) }
      $1 == "EM2" && $2 == size { mem = $3 }
      END { printf "| %s | %.2f MB | %s | %s |\n", size, pull / 1e6, w, mem }' "$TSV"
  done
  fi
}

report > "$OUT/results.md"
step "report ($OUT/results.md)"
cat "$OUT/results.md"

if [ "${UPDATE_DOC:-0}" = 1 ]; then
  if ! grep -q '<!-- bench:begin -->' "$DOC" || ! grep -q '<!-- bench:end -->' "$DOC"; then
    echo "harness: $DOC has no bench markers" >&2; exit 1
  fi
  awk -v report="$OUT/results.md" '
    /<!-- bench:begin -->/ { print; while ((getline line < report) > 0) print line; skip = 1; next }
    /<!-- bench:end -->/ { skip = 0 }
    !skip { print }' "$DOC" > "$OUT/doc.new" && cp "$OUT/doc.new" "$DOC"
  say "updated $DOC"
fi
say
say "done: $START_UTC to $END_UTC"
