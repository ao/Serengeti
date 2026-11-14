#!/usr/bin/env bash
# Benchmarks for the real image, in Docker. Every container runs on a private network made for this run
# (no outside access, nothing published to the host). A load-generator container on the same network
# drives the nodes with oha; see tests/bench/Dockerfile.tools and docs/BENCHMARKS.md.
#
#   tests/bench/run.sh                         builds serengeti:bench, then runs everything (about 50 minutes)
#   QUICK=1 tests/bench/run.sh                 a tiny version of every scenario (about 3 minutes)
#   IMAGE=repo/name:tag tests/bench/run.sh     uses that image as it is and builds nothing
#   tests/bench/report.sh A=dir B=dir          the results of two runs side by side (see report.sh)
#
# The report names the commit the image was built from: IMAGE_COMMIT if set, else the image's
# org.opencontainers.image.revision label. A build by this script sets that label to HEAD. For another
# image, build it with --label org.opencontainers.image.revision=<commit> or pass IMAGE_COMMIT.
#
# Machine description, for the report:
#   INSTANCE_TYPE      the EC2 instance type; read from the instance metadata service (IMDSv2, from a
#                      container on the host network) when not set, and left out off EC2
#   DISK="..."         what the Docker data root is on, as provisioned, e.g. "gp3 EBS, 3000 IOPS, 125 MB/s";
#                      the device model and size are added from /sys on Linux
#
# Other settings:
#   SKIP_BUILD=1       reuse the local serengeti:bench
#   SCENARIOS="a b c"  run only these (a, a-tls, b, c, d, d-local, e; the default is all, and a-tls is left
#                      out of QUICK). d-local needs an image that accepts "ack": "local" (2.1 and later); with
#                      an older one it is skipped and the report says so
#   NODE_ARGS="..."    extra docker run arguments for every node, split on spaces, e.g.
#                      NODE_ARGS="-e JDK_JAVA_OPTIONS=-Dsome.property=1"; they are printed in the report
#   OUT=dir            where the markdown report (results.md), the per-run results (results.tsv), the
#                      facts about the machine and image (meta.tsv) and oha's raw JSON go (default
#                      tests/bench/out). tests/bench/report.sh renders one or more of these directories.
#   MAX_BUSY_PCT, QUIET_WAIT_SECS, SCENARIO_WAIT_SECS
#                      the run starts once the CPUs Docker runs on (the Docker VM's on macOS) are at most
#                      this busy (default 25 percent), or after waiting this long (default 900 s; 0 in QUICK),
#                      and each scenario waits up to SCENARIO_WAIT_SECS (default 300 s; 0 in QUICK) the same
#                      way; the report says how busy it was
#   REPS WARM SECS READ_SIZES E_SIZES E_REPS E_IDLE E_SECS E_RATE WRITE_CONCURRENCY READ_CONCURRENCY NODE_MEM
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
  E_SIZES=${E_SIZES:-"2000"}; E_REPS=${E_REPS:-1}; E_IDLE=${E_IDLE:-4}; E_SECS=${E_SECS:-8}; E_SETTLE=${E_SETTLE:-2}
  DD_COUNT=${DD_COUNT:-100}
  SCENARIOS=${SCENARIOS:-"a b c d d-local e"}
  QUIET_WAIT_SECS=${QUIET_WAIT_SECS:-0}; SCENARIO_WAIT_SECS=${SCENARIO_WAIT_SECS:-0}
else
  REPS=${REPS:-3}; WARM=${WARM:-5}; SECS=${SECS:-15}
  READ_SIZES=${READ_SIZES:-"1000 10000 100000"}
  E_SIZES=${E_SIZES:-"10000 100000"}; E_REPS=${E_REPS:-3}; E_IDLE=${E_IDLE:-30}; E_SECS=${E_SECS:-60}; E_SETTLE=${E_SETTLE:-15}
  DD_COUNT=${DD_COUNT:-500}
  SCENARIOS=${SCENARIOS:-"a a-tls b c d d-local e"}
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

RUN=bn$$
NET=$RUN-net
L=(--label "serengeti-bench=$RUN")
TOOLS=$RUN-tools
random() { LC_ALL=C tr -dc 'a-zA-Z0-9' </dev/urandom | head -c "$1" || true; }
PW=$(random 24)
CLUSTER_KEY=$(random 40)
STORE_PW=$(random 20)

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

REVISION_LABEL=org.opencontainers.image.revision
if [ -z "${IMAGE:-}" ]; then
  IMAGE=serengeti:bench
  if [ "${SKIP_BUILD:-0}" != 1 ]; then
    rev=$(git rev-parse HEAD 2>/dev/null || echo unknown)
    if [ -n "$(git status --porcelain -- src pom.xml Dockerfile 2>/dev/null)" ]; then rev="$rev+uncommitted"; fi
    docker build -q -t "$IMAGE" --label "$REVISION_LABEL=$rev" . >/dev/null
  fi
fi
docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo "no such image: $IMAGE" >&2; exit 1; }
IMAGE_COMMIT=${IMAGE_COMMIT:-$(docker image inspect "$IMAGE" --format "{{index .Config.Labels \"$REVISION_LABEL\"}}" | sed 's/^<no value>$//')}
IMAGE_COMMIT=${IMAGE_COMMIT:-"not recorded (no $REVISION_LABEL label and no IMAGE_COMMIT)"}
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

now() { printf '%s' "${EPOCHREALTIME:-$(date +%s)}"; }
since() { awk -v a="$1" -v b="$(now)" 'BEGIN { printf "%.1f", b - a }'; }

# wait_converged table [count]: the three nodes report the same row count (and the given one), read one
# node after the other about four times a second, for up to 240 s. CONVERGED_SECS is left with the time
# from the first read to the start of the read that found them equal: 0 when they already matched.
wait_converged() {
  local a b c t0 tc end=$((SECONDS + 240))
  t0=$(now)
  while [ "$SECONDS" -lt "$end" ]; do
    tc=$(now)
    a=$(drv count https://n1:1985 "$1" 2>/dev/null || true)
    b=$(drv count https://n2:1985 "$1" 2>/dev/null || true)
    c=$(drv count https://n3:1985 "$1" 2>/dev/null || true)
    if [ -n "$a" ] && [ "$a" = "$b" ] && [ "$b" = "$c" ] && { [ -z "${2:-}" ] || [ "$a" = "$2" ]; }; then
      CONVERGED_SECS=$(awk -v a="$t0" -v b="$tc" 'BEGIN { printf "%.1f", b - a }')
      return 0
    fi
    sleep 0.1
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

# measure_writes scenario config conc rep base_url: INSERT without _id, new table per run. BODY_EXTRA is
# added to the request body (e.g. ', "ack": "local"'); the table's row count is left in LAST_ROWS.
measure_writes() {
  local sc=$1 cfg=$2 c=$3 rep=$4 base=$5
  local tbl="bench.t_${sc//-/_}_c${c}_r${rep}" label="$1-$2-c$3-r$4"
  label=${label//[^a-zA-Z0-9_.-]/_}
  sql "$base" "CREATE TABLE $tbl" >/dev/null
  local line ok err rps p50 p95 p99 max warm_ok f s detail
  DENV=(-e "URL=$base" -e "CONC=$c" -e "SECS=$SECS" -e "WARM=$WARM" -e "METRICS_URL=$base"
    -e "BODY={\"query\": \"INSERT INTO $tbl (n, s) VALUES (1, 'row-0000001-0123456789abcdef')\"${BODY_EXTRA:-}}")
  line=$(drv run "$label")
  IFS=$'\t' read -r ok err rps p50 p95 p99 max warm_ok f s detail <<< "$line"
  docker exec "$TOOLS" cat "/work/$label.json" > "$OUT/raw/$label.json"
  local check="rows ok" got want=$((ok + warm_ok))
  got=$(drv count "$base" "$tbl")
  if [ "$err" != 0 ]; then check="rows $got of $want acknowledged"
  elif [ "$got" != "$want" ]; then echo "harness: $tbl holds $got rows, $want inserts were acknowledged" >&2; exit 1; fi
  LAST_ROWS=$got
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
  HOST_CPU=$( (lscpu 2>/dev/null || true) | sed -n 's/^Model name: *//p' | head -1)
  HOST_CPU=${HOST_CPU:-$(awk -F': ' '/model name/ { print $2; exit }' /proc/cpuinfo 2>/dev/null || true)}
  HOST_CPU=${HOST_CPU:-unknown CPU}
  HOST_CORES=$(nproc 2>/dev/null || echo "?")
  HOST_MEM_GB=$(awk '/MemTotal/ { printf "%.1f", $2 / 1048576 }' /proc/meminfo 2>/dev/null || true)
  HOST_NAME="$( (. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME, ") || true)$host_os $(uname -r) ($(uname -m))"
fi
DOCKER_INFO=$(docker info --format '{{.OperatingSystem}}; Docker {{.ServerVersion}}; kernel {{.KernelVersion}}; {{.NCPU}} CPUs; {{.MemTotal}} bytes; {{.Architecture}}')
ORB=$(orbctl version 2>/dev/null | tr '\n' ' ' | sed 's/ *$//' || true)

# The EC2 instance type, from IMDSv2. The metadata service answers one network hop away, so the request
# goes from a container on the host's network; off EC2 it times out after 2 s and stays empty.
if [ -z "${INSTANCE_TYPE:-}" ] && [ "$host_os" = Linux ]; then
  INSTANCE_TYPE=$(docker run --rm "${L[@]}" -m 64m --network host --entrypoint sh serengeti-bench-tools -c '
    m=http://169.254.169.254/latest
    t=$(curl -sf -m 2 -X PUT -H "X-aws-ec2-metadata-token-ttl-seconds: 60" $m/api/token) || exit 0
    curl -sf -m 2 -H "X-aws-ec2-metadata-token: $t" $m/meta-data/instance-type' 2>/dev/null || true)
fi
INSTANCE_TYPE=${INSTANCE_TYPE:-}

# What the Docker data root (and so every data volume) is on: file system, device model and size from /sys.
DOCKER_ROOT=$(docker info --format '{{.DockerRootDir}}')
DISK_DEV=""
ROOT_FS=""
if [ "$host_os" = Linux ] && [ -d "$DOCKER_ROOT" ]; then
  ROOT_FS=$(findmnt -no FSTYPE --target "$DOCKER_ROOT" 2>/dev/null || true)
  DISK_DEV=$(
    mm=$(mountpoint -d "$(stat -c %m "$DOCKER_ROOT")") || exit 0
    d=$(readlink -f "/sys/dev/block/$mm") || exit 0
    if [ -e "$d/partition" ]; then d=$(dirname "$d"); fi
    model=$(sed 's/ *$//' "$d/device/model" 2>/dev/null || true)
    printf '%s%s GiB' "${model:+$model, }" "$(awk '{ printf "%d", $1 * 512 / 1073741824 }' "$d/size")") 2>/dev/null || true
fi
DISK=${DISK:-}
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
FS_TYPE=${ROOT_FS:-$(fact fs)}
TOOL_VERSION=$(docker exec "$TOOLS" oha --version)
OHA_DIGEST=$(docker image inspect "$OHA_IMAGE" --format '{{join .RepoDigests ", "}}' 2>/dev/null || true)
GIT_COMMIT=$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)
GIT_DIRTY=""
if [ -n "$(git status --porcelain -- . ':(exclude)tests/bench/out' 2>/dev/null)" ]; then GIT_DIRTY=" (working tree has uncommitted changes)"; fi
START_UTC=$(date -u +%Y-%m-%dT%H:%MZ)
OTHER_CONTAINERS=$(( $(docker ps -q | wc -l) - $(docker ps -q --filter "label=serengeti-bench=$RUN" | wc -l) ))
LOAD_START=$(docker exec "$TOOLS" cut -d' ' -f1-3 /proc/loadavg)
CPU_WHO="the host's CPUs"
if [ "$host_os" = Darwin ]; then CPU_WHO="the Docker VM's CPUs"; fi
MACHINE="$HOST_CPU, $HOST_CORES cores, ${HOST_MEM_GB} GiB memory, $HOST_NAME"
if [ -n "$INSTANCE_TYPE" ]; then MACHINE="EC2 $INSTANCE_TYPE: $MACHINE"; fi
DISK_TEXT=$(printf '%s' "${DISK:+$DISK; }${DISK_DEV:+device $DISK_DEV}" | sed 's/; $//')

say "benchmark of $IMAGE ($SERENGETI_VERSION, built from $IMAGE_COMMIT), $START_UTC, QUICK=$QUICK"
say "host: $MACHINE; Docker sees: $IN_DOCKER; $DOCKER_INFO"
say "disk: ${DISK_TEXT:-not described}; $FS_TYPE under $DOCKER_ROOT"
say "image $IMAGE_ID ($IMAGE_ARCH); $JAVA_VERSION; $TOOL_VERSION; harness commit $GIT_COMMIT$GIT_DIRTY"
# The facts tests/bench/report.sh puts in the machine and method block, one "key<TAB>value" per line.
META=$OUT/meta.tsv
meta() { printf '%s\t%s\n' "$1" "$2" >> "$META"; }
: > "$META"
meta image "$IMAGE"; meta version "$SERENGETI_VERSION"; meta image_commit "$IMAGE_COMMIT"
meta image_id "$IMAGE_ID"; meta image_arch "$IMAGE_ARCH"; meta image_digest "$IMAGE_REPO_DIGEST"
meta java "$JAVA_VERSION"; meta heap "$HEAP_MAX"; meta machine "$MACHINE"; meta host_os "$host_os"
meta docker "$DOCKER_INFO${ORB:+; OrbStack $ORB}"; meta in_docker "$IN_DOCKER"; meta disk "$DISK_TEXT"
meta fs "$FS_TYPE"; meta docker_root "$DOCKER_ROOT"; meta tool "$TOOL_VERSION"; meta oha_image "$OHA_IMAGE"
meta oha_digest "$OHA_DIGEST"; meta harness_commit "$GIT_COMMIT$GIT_DIRTY"; meta start "$START_UTC"
meta load_start "$LOAD_START"; meta other_containers "$OTHER_CONTAINERS"; meta cpu_who "$CPU_WHO"
meta quick "$QUICK"; meta reps "$REPS"; meta secs "$SECS"; meta warm "$WARM"; meta max_busy "$MAX_BUSY_PCT"
meta read_conc "$READ_CONCURRENCY"; meta node_mem "$NODE_MEM"; meta tools_mem "$TOOLS_MEM"
meta e_rate "$E_RATE"; meta e_secs "$E_SECS"; meta e_idle "$E_IDLE"; meta e_conc "$E_CONCURRENCY"
meta e_reps "$E_REPS"; meta e_settle "$E_SETTLE"; meta node_args "$NODE_ARGS"

# ---- other load on the machine ----

# busy_pct SECONDS: how busy the CPUs Docker runs on were over that time, all containers together (the
# counters in /proc/stat belong to the kernel, the Docker VM's on macOS, not to the container that reads them).
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
  say "RESULT ambient | before $1 | $CPU_WHO were $b% busy with no benchmark node running (waited $waited s)"
}

waited=0
while :; do
  b=$(busy_pct 10)
  if [ "$b" -le "$MAX_BUSY_PCT" ]; then break; fi
  if [ "$waited" -ge "$QUIET_WAIT_SECS" ]; then
    say "$CPU_WHO stayed $b% busy for $waited s; starting anyway, the numbers are affected by that load"
    break
  fi
  say "waiting for $CPU_WHO to quiet down: $b% busy (starts at $MAX_BUSY_PCT%)"
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

# cluster_writes scenario config: on the running cluster, INSERT through n1 at each concurrency. After each
# run, every node must reach n1's row count; the time that takes is recorded.
cluster_writes() {
  local sc=$1 cfg=$2 c rep
  sql "$BASE" "CREATE TABLE bench.warmup" >/dev/null
  DENV=(-e "URL=$BASE" -e "CONC=8" -e "SECS=$((WARM * 2))" -e "WARM=0"
    -e "BODY={\"query\": \"INSERT INTO bench.warmup (n) VALUES (1)\"${BODY_EXTRA:-}}")
  drv run "$sc-warmup" >/dev/null
  DENV=()
  for rep in $(seq 1 "$REPS"); do
    for c in $WRITE_CONCURRENCY; do
      measure_writes "$sc" "$cfg" "$c" "$rep" "$BASE"
      wait_converged "bench.t_${sc//-/_}_c${c}_r${rep}" "$LAST_ROWS" ||
        { echo "harness: the nodes did not converge after $sc, concurrency $c, run $rep" >&2; exit 1; }
      record C "$sc" "$c" "$rep" "$CONVERGED_SECS"
      say "RESULT $sc | $c | run $rep | all three nodes held the $LAST_ROWS rows $CONVERGED_SECS s after the run"
    done
  done
}

# ack_support URL: prints "yes" when the node reads the "ack" field of a query (2.1 and later), otherwise
# why d-local is skipped. A node that reads the field must refuse an invalid value with 400.
ack_support() {
  local out code body
  out=$(drv post "$1" '{"query": "SHOW DATABASES", "ack": "probe"}')
  code=${out##*$'\n'}; body=${out%$'\n'*}
  body=$(printf '%s' "$body" | tr -d '\n' | cut -c1-160)
  case $code in
    400) case $body in *ack*) echo yes ;; *) echo "the image refused a query with an \"ack\" field: HTTP 400, $body" ;; esac ;;
    200) echo "the image ignores the \"ack\" field: a query with \"ack\": \"probe\" was answered 200, so \"ack\": \"local\" would measure majority writes" ;;
    *) echo "the image refused a query with an \"ack\" field: HTTP $code, $body" ;;
  esac
}

if want d; then
  ambient d
  step "d: 3-node TLS cluster, majority writes through n1, INSERT concurrency: $WRITE_CONCURRENCY"
  start_cluster
  cluster_writes d "3 nodes, TLS, fsync=always"
  drop_nodes
fi

if want d-local; then
  ambient d-local
  step "d-local: 3-node TLS cluster, \"ack\": \"local\" writes through n1, INSERT concurrency: $WRITE_CONCURRENCY"
  start_cluster
  ACK=$(ack_support "$BASE")
  if [ "$ACK" = yes ]; then
    BODY_EXTRA=', "ack": "local"'
    cluster_writes d-local "3 nodes, TLS, fsync=always, ack=local"
    BODY_EXTRA=
  else
    record S d-local "$ACK"
    say "RESULT d-local | not run: $ACK"
  fi
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

# e_window_start / e_window_end SIZE PHASE REP: counters of every node before and after the window, then
# one E line per node: CPU in cores, peer traffic in and out in bytes/s, the window's length, and the
# requests per second the node answered on /internal/state (a whole database), /internal/apply,
# /internal/digests and other routes (2.1's /internal/v2/* sync routes are counted there).
# mem_mib CONTAINER: memory in use in MiB, counted as docker stats does (the cgroup's usage less inactive
# file pages).
mem_mib() {
  docker exec "$1" sh -c 'awk -v c="$(cat /sys/fs/cgroup/memory.current)" "/^inactive_file / { printf \"%.0f\\n\", (c - \$2) / 1048576 }" /sys/fs/cgroup/memory.stat'
}

# The /metrics requests go from the load generator, so they are made outside the traffic counters' window
# (before it starts and after it ends); otherwise n1's correction for client traffic would include them.
lines_to() { IFS=$'\n' read -r -d '' -a "$1" || true; }   # NAME < lines: an array, one element per line
e_snaps() { snap "${CNODES[0]}"; snap "${CNODES[1]}"; snap "${CNODES[2]}"; snap "$TOOLS"; }
e_routes() { local i; for i in 1 2 3; do drv routes "https://n$i:1985"; done; }
e_window_start() { lines_to ROUTES0 < <(e_routes); lines_to SNAP0 < <(e_snaps); }
e_window_end() {
  local size=$1 phase=$2 rep=$3 i client
  local -a snap1 routes1
  lines_to snap1 < <(e_snaps)
  lines_to routes1 < <(e_routes)
  for i in 1 2 3; do
    # Node 1 also carries the client's traffic: take the load generator's counters off its totals.
    client="0 0"
    if [ "$i" = 1 ]; then client=$(awk -v b="${SNAP0[3]}" -v a="${snap1[3]}" 'BEGIN { split(b, x, " "); split(a, y, " "); print y[4] - x[4], y[3] - x[3] }'); fi
    awk -v size="$size" -v phase="$phase" -v node="n$i" -v rep="$rep" -v b="${SNAP0[$((i - 1))]} ${ROUTES0[$((i - 1))]}" \
      -v a="${snap1[$((i - 1))]} ${routes1[$((i - 1))]}" -v c="$client" 'BEGIN {
      split(b, x, " "); split(a, y, " "); split(c, z, " ")
      secs = y[1] - x[1]
      printf "E\t%s\t%s\t%s\t%s\t%.3f\t%.1f\t%.1f\t%.1f", size, phase, node, rep, (y[2] - x[2]) / 1e6 / secs,
        ((y[3] - x[3]) - z[1]) / secs, ((y[4] - x[4]) - z[2]) / secs, secs
      for (k = 5; k <= 8; k++) printf "\t%.2f", (y[k] - x[k]) / secs
      printf "\n" }' | tee -a "$TSV" | awk -F'\t' '{
      printf "RESULT e | %s rows | run %s | %s | %s | cpu %.2f cores | peer traffic in %.1f KB/s, out %.1f KB/s | answered per s: state %s, apply %s, digests %s, other %s | over %.1f s\n",
        $2, $5, $3, $4, $6, $7 / 1000, $8 / 1000, $10, $11, $12, $13, $9 }'
  done
}

if want e; then
ambient e
for size in $E_SIZES; do
  for rep in $(seq 1 "$E_REPS"); do
  step "e: 3-node TLS cluster, table of $size rows, then $E_RATE writes/s for $E_SECS s (run $rep of $E_REPS)"
  start_cluster
  sql "$BASE" "CREATE TABLE bench.e" >/dev/null
  t0=$(now)
  drv preload "$BASE" bench.e 0 "$size"
  wait_converged bench.e "$size" || { echo "harness: the nodes did not converge after the preload" >&2; exit 1; }
  took=$(since "$t0")
  record EP "$size" "$rep" "$took"
  say "RESULT e | $size rows | run $rep | preload and convergence took $took s"
  sleep "$E_SETTLE"
  pull_bytes=$(drv state-size https://n1:1985 bench)
  record EM "$size" "$rep" "$pull_bytes"
  say "RESULT e | $size rows | run $rep | one full /internal/state answer from n1: $pull_bytes bytes"

  say "# idle: no client requests for $E_IDLE s"
  e_window_start
  sleep "$E_IDLE"
  e_window_end "$size" idle "$rep"

  say "# $E_RATE writes/s for $E_SECS s through n1"
  label="e-$size-r$rep"
  DENV=(-e "URL=$BASE" -e "CONC=$E_CONCURRENCY" -e "SECS=$E_SECS" -e "WARM=0" -e "QPS=$E_RATE"
    -e "BODY={\"query\": \"INSERT INTO bench.e (n, s) VALUES (0, 'row-0000000-0123456789abcdef')\"}")
  e_window_start
  line=$(drv run "$label")
  e_window_end "$size" "write" "$rep"
  DENV=()
  IFS=$'\t' read -r ok err rps p50 p95 p99 max warm_ok f s detail <<< "$line"
  docker exec "$TOOLS" cat "/work/$label.json" > "$OUT/raw/$label.json"
  record EW "$size" "$rep" "$ok" "$err" "$rps" "$p50" "$p95" "$p99"
  say "RESULT e | $size rows | run $rep | writes: $ok ok, $err errors, $rps/s, p50 $p50 ms, p95 $p95 ms, p99 $p99 ms ${detail:+| $detail}"
  mem=$(for n in "${CNODES[@]}"; do mem_mib "$n"; done)
  record EM2 "$size" "$rep" $mem
  say "RESULT e | $size rows | run $rep | memory in use at the end, n1 n2 n3: $mem MiB"
  wait_converged bench.e || { echo "harness: the nodes did not converge after the writes" >&2; exit 1; }
  record EC "$size" "$rep" "$CONVERGED_SECS"
  say "RESULT e | $size rows | run $rep | all three nodes held the same rows $CONVERGED_SECS s after the writes"
  drop_nodes
  done
done
fi

LOAD_END=$(docker exec "$TOOLS" cut -d' ' -f1-3 /proc/loadavg)
END_UTC=$(date -u +%Y-%m-%dT%H:%MZ)

meta end "$END_UTC"; meta load_end "$LOAD_END"

# ---- report ----

tests/bench/report.sh "$OUT" > "$OUT/results.md"
step "report ($OUT/results.md)"
cat "$OUT/results.md"
say
say "done: $START_UTC to $END_UTC"
