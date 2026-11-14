#!/bin/bash
# Runs inside the load-generator container (see Dockerfile.tools); tests/bench/run.sh calls it with
# "docker exec". Settings come from the environment:
#   BENCH_AUTH     user:password for Basic auth (set when the container starts)
#   BENCH_CACERT   CA certificate file, for https URLs
#
# Subcommands:
#   sql URL STATEMENT            print the node's JSON answer
#   count URL TABLE              print COUNT(*) of db.table
#   health URL                   print the HTTP status of GET /health (000 if no answer)
#   status URL                   print GET /v1/status
#   preload URL TABLE FROM TO    insert rows n=FROM+1..TO, 1000 rows per statement
#   gen-select TABLE MAX COUNT   print COUNT request bodies, one per line, for WHERE n = <random 1..MAX>
#   state-size URL DB            print the bytes of one /internal/state answer (needs CLUSTER_KEY)
#   run LABEL                    warm up, then measure with oha; prints one tab-separated result line
#       env: URL CONC SECS WARM and BODY or BODY_FILE (one body per line); optional QPS, METRICS_URL
set -euo pipefail
AUTH=${BENCH_AUTH:?BENCH_AUTH is not set}
CACERT=${BENCH_CACERT:-}

cu() { curl -s --max-time "${MAXTIME:-120}" ${CACERT:+--cacert "$CACERT"} "$@"; }

sql() { cu -u "$AUTH" -H 'Content-Type: application/json' --data-binary "{\"query\": \"$2\"}" "$1/v1/query"; }

# The server's WAL counters: "<forces> <seconds spent in them>", or "0 0" when no METRICS_URL is given.
wal_counters() {
  if [ -z "${METRICS_URL:-}" ]; then echo "0 0"; return; fi
  cu -u "$AUTH" "$METRICS_URL/metrics" | awk '/^serengeti_wal_fsyncs_total /{n=$2} /^serengeti_wal_fsync_seconds_total /{s=$2} END{print n+0, s+0}'
}

oha_args() { # collects the arguments shared by the warm-up and the measured run into OHA
  OHA=(--no-tui -w -m POST -T application/json -a "$AUTH" -c "$CONC")
  if [ -n "$CACERT" ]; then OHA+=(--cacert "$CACERT"); fi
  if [ -n "${BODY_FILE:-}" ]; then OHA+=(-Z "$BODY_FILE"); else OHA+=(-d "$BODY"); fi
}

ok_count() { jq -r '.statusCodeDistribution["200"] // 0' "$1"; }

cmd=${1:?subcommand needed}
shift
case $cmd in
  sql) sql "$@" ;;
  count) sql "$1" "SELECT COUNT(*) FROM $2" | sed -n 's/.*"rows":\[\[\([0-9]*\)\]\].*/\1/p' ;;
  health) cu -o /dev/null -w '%{http_code}' "$1/health" || true ;;
  status) cu -u "$AUTH" "$1/v1/status" ;;
  state-size) cu -o /dev/null -w '%{size_download}' -H "X-Serengeti-Cluster-Key: ${CLUSTER_KEY:?}" "$1/internal/state?db=$2" ;;
  preload)
    url=$1 table=$2 from=$3 to=$4
    while [ "$from" -lt "$to" ]; do
      end=$((from + 1000))
      if [ "$end" -gt "$to" ]; then end=$to; fi
      awk -v t="$table" -v a=$((from + 1)) -v b="$end" 'BEGIN {
        printf "{\"query\": \"INSERT INTO %s (n, s) VALUES ", t
        for (i = a; i <= b; i++) printf "%s(%d, '"'"'row-%07d-0123456789abcdef'"'"')", (i > a ? ", " : ""), i, i
        printf "\"}" }' > /tmp/preload.json
      code=$(cu -o /tmp/preload.out -w '%{http_code}' -u "$AUTH" -H 'Content-Type: application/json' \
        --data-binary @/tmp/preload.json "$url/v1/query")
      if [ "$code" != 200 ]; then echo "preload: HTTP $code: $(head -c 200 /tmp/preload.out)" >&2; exit 1; fi
      from=$end
    done ;;
  gen-select)
    awk -v t="$1" -v max="$2" -v count="$3" 'BEGIN {
      srand(42)
      for (i = 0; i < count; i++) printf "{\"query\": \"SELECT * FROM %s WHERE n = %d\"}\n", t, 1 + int(rand() * max) }' ;;
  run)
    label=$1
    : "${URL:?}" "${CONC:?}" "${SECS:?}"
    WARM=${WARM:-0}
    mkdir -p /work
    oha_args
    if [ -n "${QPS:-}" ]; then OHA+=(-q "$QPS"); fi
    warm_ok=0
    if [ "$WARM" -gt 0 ]; then
      oha "${OHA[@]}" -z "${WARM}s" --output-format json "$URL/v1/query" > "/work/$label.warm.json"
      warm_ok=$(ok_count "/work/$label.warm.json")
    fi
    read -r f0 s0 < <(wal_counters)
    oha "${OHA[@]}" -z "${SECS}s" --output-format json "$URL/v1/query" > "/work/$label.json"
    read -r f1 s1 < <(wal_counters)
    # ok, errors (non-200 answers and failed connections), ok per second, latencies in ms,
    # then the warm-up's ok count, forces and seconds in forces during the run, and any error detail.
    jq -r --arg warm "$warm_ok" --argjson f "$((f1 - f0))" --argjson s "$(awk -v a="$s0" -v b="$s1" 'BEGIN{print b-a}')" '
      ([.statusCodeDistribution[]?] | add // 0) as $resp
      | ([.errorDistribution[]?] | add // 0) as $err
      | (.statusCodeDistribution["200"] // 0) as $ok
      | def ms: ((. // 0) * 100000 | round) / 100;
        ([(.statusCodeDistribution // {}) | to_entries[] | select(.key != "200") | "HTTP \(.key) x\(.value)"]
         + [(.errorDistribution // {}) | to_entries[] | "\(.key) x\(.value)"] | join("; ")) as $detail
      | [$ok, ($resp - $ok + $err), (($ok / .summary.total) * 100 | round / 100),
         (.latencyPercentiles.p50 | ms), (.latencyPercentiles.p95 | ms), (.latencyPercentiles.p99 | ms),
         (.summary.slowest | ms), ($warm | tonumber), $f, ($s * 1000 | round / 1000), $detail] | @tsv' "/work/$label.json" ;;
  *) echo "driver: unknown subcommand $cmd" >&2; exit 2 ;;
esac
