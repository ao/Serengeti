#!/usr/bin/env bash
# Runs examples/kubernetes/serengeti.yaml on a throwaway kind cluster (Kubernetes in Docker) and checks it:
# three pods become ready, a write through the Service is read on every pod, a deleted pod comes back with
# its data, the shell works in a pod, and nothing is exposed outside the cluster.
#
#   IMAGE=serengeti:k8s examples/kubernetes/run.sh      (builds the image unless SKIP_BUILD=1)
# Needs docker, kind and kubectl. The kind cluster and everything in it are deleted on exit.
set -euo pipefail
cd "$(dirname "$0")/../.."
IMAGE=${IMAGE:-serengeti:k8s}
CLUSTER=serengeti-k8s-$$
TMP=$(mktemp -d)
passed=0
failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$2', got '$3'"; fi; }
contains() { case "$3" in *"$2"*) ok "$1" ;; *) bad "$1" "no '$2' in: $(echo "$3" | head -c 300)" ;; esac; }
cleanup() {
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi
kind create cluster --name "$CLUSTER" --wait 120s >/dev/null 2>&1
K=(kubectl --context "kind-$CLUSTER")
kind load docker-image "$IMAGE" --name "$CLUSTER" >/dev/null

# Step 1 of the manifest's instructions, as a user would run it (as themselves, not root).
mkdir "$TMP/certs"
docker run --rm --user "$(id -u)" -v "$TMP/certs:/certs" --entrypoint serengeti-certs "$IMAGE" \
  serengeti-0=serengeti-0.serengeti,serengeti-client \
  serengeti-1=serengeti-1.serengeti,serengeti-client \
  serengeti-2=serengeti-2.serengeti,serengeti-client >/dev/null
"${K[@]}" create secret generic serengeti-certs --from-file="$TMP/certs/" >/dev/null
# Step 2: secrets from files.
PW=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
printf '%s' "$PW" > "$TMP/admin-password"
od -An -N24 -tx1 /dev/urandom | tr -d ' \n' > "$TMP/cluster-key"
"${K[@]}" create secret generic serengeti-secrets --from-file=admin-password="$TMP/admin-password" \
  --from-file=cluster-key="$TMP/cluster-key" >/dev/null
rm -f "$TMP/admin-password" "$TMP/cluster-key"
# Step 3, with this build's image.
sed "s|image: ataiva/serengeti:latest|image: $IMAGE|" examples/kubernetes/serengeti.yaml | "${K[@]}" apply -f - >/dev/null

if "${K[@]}" rollout status statefulset/serengeti --timeout=300s >/dev/null 2>&1 \
   && "${K[@]}" wait --for=condition=Ready pod -l app=serengeti --timeout=120s >/dev/null 2>&1; then
  ok "three pods become ready (a majority reachable, caught up)"
else
  bad "three pods become ready" "$("${K[@]}" get pods -o wide 2>&1; "${K[@]}" logs serengeti-0 2>&1 | tail -5)"
fi

# A client pod with the CA, inside the cluster; the API is reached through the Service over TLS.
"${K[@]}" create configmap serengeti-ca --from-file=ca.pem="$TMP/certs/ca.pem" >/dev/null
"${K[@]}" run client --image=curlimages/curl:8.11.1 --restart=Never \
  --overrides='{"spec":{"containers":[{"name":"client","image":"curlimages/curl:8.11.1","command":["sleep","600"],
    "volumeMounts":[{"name":"ca","mountPath":"/ca"}]}],"volumes":[{"name":"ca","configMap":{"name":"serengeti-ca"}}]}}' \
  >/dev/null
"${K[@]}" wait --for=condition=Ready pod/client --timeout=120s >/dev/null
q() { # host query -> body
  "${K[@]}" exec client -- curl -s --max-time 10 --cacert /ca/ca.pem -u "admin:$PW" -H 'Content-Type: application/json' \
    --data "{\"query\": \"$2\"}" "https://$1:1985/v1/query"
}
# By pod name, retried: the cluster's DNS caches answers (also "no such name", from before a pod had an
# address) for up to 30 s.
by_name() { # host query needle -> the last answer
  local got=""
  for _ in $(seq 30); do
    got=$(q "$1" "$2" 2>/dev/null || true)
    case "$got" in *"$3"*) break ;; esac
    sleep 3
  done
  echo "$got"
}
contains "a database through the Service" '"type":"write"' "$(q serengeti-client 'CREATE DATABASE shop')"
q serengeti-client 'CREATE TABLE shop.items' >/dev/null
contains "a write through the Service" '"affected":2' \
  "$(q serengeti-client "INSERT INTO shop.items (_id, qty) VALUES ('bolt', 120), ('nut', 300)")"
for i in 0 1 2; do
  contains "serengeti-$i has both rows (majority write)" '"rows":[[2]]' \
    "$(by_name "serengeti-$i.serengeti" "SELECT COUNT(*) FROM shop.items" "\"rows\":[[2]]")"
done

"${K[@]}" delete pod serengeti-1 --wait=true >/dev/null
q serengeti-client "INSERT INTO shop.items (_id, qty) VALUES ('washer', 50)" >/dev/null
if "${K[@]}" wait --for=condition=Ready pod/serengeti-1 --timeout=180s >/dev/null 2>&1; then
  ok "a deleted pod comes back"
else
  bad "a deleted pod comes back" "$("${K[@]}" get pods 2>&1)"
fi
contains "with its data and the write it missed" '"rows":[[3]]' \
  "$(by_name serengeti-1.serengeti 'SELECT COUNT(*) FROM shop.items' '"rows":[[3]]')"

contains "serengeti shell in a pod, with the admin password from its secret file" "washer" \
  "$("${K[@]}" exec serengeti-0 -- serengeti shell --format tsv -c 'SELECT _id FROM shop.items ORDER BY _id' 2>&1)"
contains "serengeti backup in a pod is complete" '"end":true' \
  "$("${K[@]}" exec serengeti-0 -- serengeti backup 2>/dev/null | tail -c 300)"
expect "the client Service is ClusterIP only" "ClusterIP" \
  "$("${K[@]}" get service serengeti-client -o jsonpath='{.spec.type}')"
expect "no NodePort or LoadBalancer anywhere" "" \
  "$("${K[@]}" get services -o jsonpath='{range .items[*]}{.spec.type}{"\n"}{end}' | grep -E 'NodePort|LoadBalancer' || true)"
expect "pods run as uid 10001 with a read-only root" "10001 true" \
  "$("${K[@]}" get pod serengeti-0 -o jsonpath='{.spec.securityContext.runAsUser} {.spec.containers[0].securityContext.readOnlyRootFilesystem}')"

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
