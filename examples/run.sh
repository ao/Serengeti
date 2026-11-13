#!/usr/bin/env bash
# Runs each example in its own official container against one node on a private network, and checks
# what each one prints and how each one fails. Nothing is published to this machine.
#
#   IMAGE=serengeti:examples examples/run.sh           builds the image first
#   SKIP_BUILD=1 IMAGE=serengeti:examples examples/run.sh   uses an image that already exists
#
# Everything created has the label serengeti-examples=<run id> and is removed on exit. The image is kept.
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=${IMAGE:-serengeti:examples}
CURL_IMAGE=curlimages/curl:8.11.1
PYTHON_IMAGE=python:3.12-alpine
NODE_IMAGE=node:22-alpine
RUN=ex$$
LABEL="serengeti-examples=$RUN"
NET=$RUN-net
TMP=$(mktemp -d)
PW=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
passed=0
failed=0

cleanup() {
  # Containers, then volumes, then networks, each selected by this run's label. Errors are ignored: an
  # empty list makes xargs run the command with no arguments, which fails harmlessly.
  docker ps -aq --filter "label=$LABEL" | xargs docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=$LABEL" | xargs docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=$LABEL" | xargs docker network rm >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1: $2"; }

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi

docker network create --label "$LABEL" "$NET" >/dev/null
docker volume create --label "$LABEL" "$RUN-data" >/dev/null
export SERENGETI_ADMIN_PASSWORD="$PW"
docker run -d --label "$LABEL" --name "$RUN-serengeti" --network "$NET" --network-alias serengeti \
  -v "$RUN-data:/data" -e SERENGETI_ADMIN_PASSWORD "$IMAGE" >/dev/null
docker run -d --label "$LABEL" --name "$RUN-client" --network "$NET" \
  --entrypoint sleep "$CURL_IMAGE" 3600 >/dev/null

health() { docker exec "$RUN-client" curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://serengeti:1985/health; }
up=0
for _ in $(seq 60); do
  if [ "$(health || true)" = 200 ]; then up=1; break; fi
  sleep 1
done
if [ "$up" = 1 ]; then ok "the node answers /health"; else bad "the node answers /health" "no 200 in 60 s"; exit 1; fi

# example PASSWORD IMAGE-AND-COMMAND...  runs one example with SERENGETI_URL and SERENGETI_PASSWORD set.
# Its combined output and exit status are left in $TMP/out and $status.
example() {
  local password=$1
  shift
  set +e
  docker run --rm --label "$LABEL" --network "$NET" \
    -e SERENGETI_URL=http://serengeti:1985 -e SERENGETI_PASSWORD="$password" \
    -v "$PWD/examples:/examples:ro" "$@" > "$TMP/out" 2>&1
  status=$?
  set -e
}

# The three examples, each run in its own image with the password from this run.
check_rows() { # name
  if [ "$status" = 0 ] && grep -Eq 'bolt.*120' "$TMP/out" && grep -Eq 'nut.*300' "$TMP/out"; then
    ok "$1 prints the rows"
  else
    bad "$1 prints the rows" "exit $status: $(tail -n 3 "$TMP/out")"
  fi
}
check_refused() { # name, expected message
  if [ "$status" != 0 ] && grep -Fq -- "$2" "$TMP/out"; then
    ok "$1 with a wrong password exits $status and prints the server's message"
  else
    bad "$1 with a wrong password" "exit $status: $(tail -n 3 "$TMP/out")"
  fi
}
check_unset() { # name
  if [ "$status" != 0 ] && grep -Fq SERENGETI_PASSWORD "$TMP/out"; then
    ok "$1 without a password exits $status and says what is missing"
  else
    bad "$1 without a password" "exit $status: $(tail -n 3 "$TMP/out")"
  fi
}

example "$PW" --entrypoint sh "$CURL_IMAGE" /examples/curl.sh
check_rows curl
example "$PW" "$PYTHON_IMAGE" python3 /examples/python/client.py
check_rows python
example "$PW" "$NODE_IMAGE" node /examples/javascript/client.mjs
check_rows javascript

example "wrong-password" --entrypoint sh "$CURL_IMAGE" /examples/curl.sh
check_refused curl "wrong user name or password"
example "wrong-password" "$PYTHON_IMAGE" python3 /examples/python/client.py
check_refused python "wrong user name or password"
example "wrong-password" "$NODE_IMAGE" node /examples/javascript/client.mjs
check_refused javascript "wrong user name or password"

example "" --entrypoint sh "$CURL_IMAGE" /examples/curl.sh
check_unset curl
example "" "$PYTHON_IMAGE" python3 /examples/python/client.py
check_unset python
example "" "$NODE_IMAGE" node /examples/javascript/client.mjs
check_unset javascript

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
