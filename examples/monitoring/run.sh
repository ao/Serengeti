#!/usr/bin/env bash
# Tests examples/monitoring as its comments say, with a built image: the alert rules' own unit tests, then
# the three nodes, Prometheus and Grafana together. Prometheus must scrape every node over HTTPS with the
# metrics account, load the rules and raise an alert for a stopped node; Grafana must need its password,
# have the dashboard and get answers for its queries; the published ports must be on 127.0.0.1 only.
# Everything it makes is removed at the end.
#
#   IMAGE=serengeti:monitoring-example examples/monitoring/run.sh     (builds the image unless SKIP_BUILD=1)
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=${IMAGE:-serengeti:monitoring-example}
PROM_IMAGE=prom/prometheus:v3.5.0
PROJECT=sgm$$
passed=0
failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi; }
contains() { case "$3" in *"$2"*) ok "$1" ;; *) bad "$1" "'$2' not in: $(printf %s "$3" | head -c 600)" ;; esac; }

work=$(mktemp -d)
cleanup() {
  (cd "$work/monitoring" && docker compose -p "$PROJECT" --profile setup down -v --remove-orphans >/dev/null 2>&1) || true
  rm -rf "$work"
}
trap cleanup EXIT

echo "# the alert rules"
out=$(docker run --rm -v "$PWD:/m:ro" -w /m --entrypoint promtool "$PROM_IMAGE" check rules alerts.yml 2>&1) && rc=0 || rc=$?
expect "promtool accepts alerts.yml" 0 "$rc"
contains "with every rule" "SUCCESS: 14 rules found" "$out"
out=$(docker run --rm -v "$PWD:/m:ro" -w /m --entrypoint promtool "$PROM_IMAGE" test rules alerts.test.yml 2>&1) && rc=0 || rc=$?
expect "the rules pass their unit tests" 0 "$rc"
[ "$rc" = 0 ] || echo "$out" | tail -30
expect "every rule has a test" 0 "$(sed -n 's/^ *- alert: //p' alerts.yml | while read -r a; do
  grep -q "alertname: $a\$" alerts.test.yml || echo "$a"; done | wc -l | tr -d ' ')"

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" ../.. >/dev/null
fi
mkdir -p "$work/cluster"
cp ../cluster/compose.yaml "$work/cluster/"
cp -R . "$work/monitoring"
cd "$work/monitoring"
rm -f run.sh
PW="$(head -c 18 /dev/urandom | base64 | tr -d '/+=')-admin"
MPW="$(head -c 18 /dev/urandom | base64 | tr -d '/+=')-metrics"
GPW="$(head -c 18 /dev/urandom | base64 | tr -d '/+=')-grafana"
printf 'SERENGETI_ADMIN_PASSWORD=%s\nSERENGETI_CLUSTER_KEY=%s\nSERENGETI_METRICS_PASSWORD=%s\nGRAFANA_ADMIN_PASSWORD=%s\nSERENGETI_IMAGE=%s\n' \
  "$PW" "$(head -c 48 /dev/urandom | base64 | tr -d '/+=')" "$MPW" "$GPW" "$IMAGE" > .env
dc() { docker compose -p "$PROJECT" "$@"; }

echo "# the cluster, Prometheus and Grafana"
expect "the compose file is valid" 0 "$(dc config -q >/dev/null 2>&1; echo $?)"
expect "serengeti-certs makes the key stores" 0 "$(dc run --rm certs >/dev/null 2>&1; echo $?)"
dc run --rm --entrypoint cat certs /certs/ca.pem > ca.pem 2>/dev/null
dc up -d >/dev/null 2>&1 || { dc up -d; exit 1; }
ready() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert ca.pem "https://127.0.0.1:$1/ready" || true; }
for p in 19851 19852 19853; do
  for _ in $(seq 120); do [ "$(ready $p)" = 200 ] && break; sleep 1; done
done
expect "every node is ready" "200 200 200" "$(ready 19851) $(ready 19852) $(ready 19853)"
out=$(dc run --rm metrics-account 2>&1) && rc=0 || rc=$?
expect "metrics-account makes the account" 0 "$rc"
contains "with the metrics role" '"role":"metrics"' "$out"
expect "running it again changes nothing and still succeeds" 0 "$(dc run --rm metrics-account >/dev/null 2>&1; echo $?)"
mq() { # port user password query -> status
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cacert ca.pem -u "$2:$3" -H 'Content-Type: application/json' \
    --data-binary "{\"query\": \"$4\"}" "https://127.0.0.1:$1/v1/query" || true
}
mget() { # port path -> status, as the metrics account
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cacert ca.pem -u "metrics:$MPW" "https://127.0.0.1:$1$2" || true
}
for _ in $(seq 30); do [ "$(mget 19853 /metrics)" = 200 ] && break; sleep 1; done
expect "the account reaches n3's metrics too" 200 "$(mget 19853 /metrics)"
expect "and cannot read data" 403 "$(mq 19851 metrics "$MPW" 'SHOW DATABASES')"
expect "or write" 403 "$(mq 19851 metrics "$MPW" 'CREATE DATABASE nope')"
expect "or open the console" 403 "$(mget 19852 /)"

prom() { curl -s --max-time 10 "http://127.0.0.1:19090$1" || true; }
pq() { curl -s --max-time 10 --data-urlencode "query=$1" "http://127.0.0.1:19090/api/v1/query" || true; }
for _ in $(seq 90); do
  [ "$(prom /api/v1/targets | grep -o '"health":"up"' | wc -l | tr -d ' ')" = 3 ] && break
  sleep 2
done
targets=$(prom /api/v1/targets)
expect "Prometheus scrapes all three nodes" 3 "$(printf %s "$targets" | grep -o '"health":"up"' | wc -l | tr -d ' ')"
[ "$(printf %s "$targets" | grep -o '"health":"up"' | wc -l | tr -d ' ')" = 3 ] || printf '%s\n' "$targets" | head -c 2000
contains "every node reports three reachable" '"value":[' "$(pq 'count(serengeti_cluster_reachable_nodes == 3) == 3')"
expect "and is ready" 3 "$(pq 'sum(serengeti_ready)' | sed -n 's/.*"value":\[[0-9.]*,"\([0-9]*\)".*/\1/p')"
expect "Prometheus loaded the 14 rules" 14 "$(prom /api/v1/rules | grep -o '"type":"alerting"' | wc -l | tr -d ' ')"
expect "and no alert is active" 0 "$(prom /api/v1/alerts | grep -o '"alertname"' | wc -l | tr -d ' ')"

g() { curl -s -o /tmp/sgm-body -w '%{http_code}' --max-time 10 "$@" || true; }
for _ in $(seq 60); do [ "$(g http://127.0.0.1:13000/api/health)" = 200 ] && break; sleep 1; done
expect "Grafana answers" 200 "$(g http://127.0.0.1:13000/api/health)"
expect "the dashboard needs a login" 401 "$(g http://127.0.0.1:13000/api/dashboards/uid/serengeti)"
expect "a wrong password is refused" 401 "$(g -u admin:admin http://127.0.0.1:13000/api/dashboards/uid/serengeti)"
expect "the dashboard is there for admin" 200 "$(g -u "admin:$GPW" http://127.0.0.1:13000/api/dashboards/uid/serengeti)"
expect "with its 13 panels" 13 "$(grep -o '"gridPos"' /tmp/sgm-body | wc -l | tr -d ' ')"
expect "the data source reaches Prometheus" 200 "$(g -u "admin:$GPW" http://127.0.0.1:13000/api/datasources/uid/prometheus/health)"
contains "and says so" '"status":"OK"' "$(cat /tmp/sgm-body)"
# Every query of the dashboard, through Grafana, at the current time: each must answer without error.
# Each expression is taken as the JSON string it is in the file, escapes and all.
exprs=$(dc exec -T grafana cat /var/lib/grafana/dashboards/serengeti.json | grep -oE '"expr": "([^"\\]|\\.)*"' | sed 's/^"expr": //')
n=0; errors=0
while IFS= read -r e; do
  q=${e//\$instance/.*}
  q=${q//\$__rate_interval/1m}
  body=$(printf '{"queries":[{"refId":"A","datasource":{"uid":"prometheus"},"expr":%s,"instant":true}],"from":"now-5m","to":"now"}' "$q")
  code=$(curl -s -o /tmp/sgm-q -w '%{http_code}' --max-time 10 -u "admin:$GPW" -H 'Content-Type: application/json' \
    --data-binary "$body" http://127.0.0.1:13000/api/ds/query || true)
  n=$((n + 1))
  if [ "$code" != 200 ] || grep -q '"error"' /tmp/sgm-q; then errors=$((errors + 1)); echo "  query failed ($code): $q"; head -c 300 /tmp/sgm-q; echo; fi
done <<< "$exprs"
expect "all $n dashboard queries answer through Grafana" 0 "$errors"
rm -f /tmp/sgm-body /tmp/sgm-q

echo "# a node stops"
dc stop n3 >/dev/null 2>&1
for _ in $(seq 60); do
  case "$(pq 'ALERTS{alertname="SerengetiDown", instance="n3:1985"}')" in *'"alertstate"'*) break ;; esac
  sleep 2
done
contains "SerengetiDown is raised for n3" '"instance":"n3:1985"' "$(pq 'ALERTS{alertname="SerengetiDown"}')"
n1_reach() { pq 'serengeti_cluster_reachable_nodes{instance="n1:1985"}' | sed -n 's/.*"value":\[[0-9.]*,"\([0-9]*\)".*/\1/p'; }
for _ in $(seq 30); do [ "$(n1_reach)" = 2 ] && break; sleep 2; done
expect "n1 reports two nodes reachable" 2 "$(n1_reach)"
dc start n3 >/dev/null 2>&1

for svc in n1 prometheus grafana; do
  bound=$(docker port "$(dc ps -q "$svc")" | sed 's/.*-> //' | tr '\n' ' ')
  case $bound in 127.0.0.1:*) ok "$svc is published on 127.0.0.1 only ($bound)" ;; *) bad "publish $svc" "$bound" ;; esac
done

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
