#!/usr/bin/env bash
# Runs the Python and JavaScript client tests, each in its official container, on a private network with:
#   node    a plain-HTTP node of the image
#   tls     a node serving HTTPS with a certificate from serengeti-certs (the tests trust its ca.pem)
#   silent  a socket that accepts connections and never answers, to show writes are not sent twice
#   cut     a server whose 200 answers stop part way
# Nothing is published to this machine.
#
#   clients/run.sh        (IMAGE=serengeti:clients; builds the image unless SKIP_BUILD=1)
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE=${IMAGE:-serengeti:clients}
RUN=cl$$
L=(--label "serengeti-clients=$RUN")
PW=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
cleanup() {
  docker ps -aq --filter "label=serengeti-clients=$RUN" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker network ls -q --filter "label=serengeti-clients=$RUN" | xargs -r docker network rm >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=serengeti-clients=$RUN" | xargs -r docker volume rm >/dev/null 2>&1 || true
}
trap cleanup EXIT
if [ "${SKIP_BUILD:-0}" != 1 ]; then
  docker build -q -t "$IMAGE" . >/dev/null
fi
docker network create "${L[@]}" "$RUN" >/dev/null
docker volume create "${L[@]}" "$RUN-certs" >/dev/null
docker run --rm "${L[@]}" --user 0 -v "$RUN-certs:/certs" --entrypoint serengeti-certs "$IMAGE" tls >/dev/null

docker run -d -m 512m "${L[@]}" --name "$RUN-node" --network "$RUN" --network-alias node \
  -e SERENGETI_ADMIN_PASSWORD="$PW" "$IMAGE" >/dev/null
docker run -d -m 512m "${L[@]}" --name "$RUN-tls" --network "$RUN" --network-alias tls \
  -v "$RUN-certs:/certs:ro" -e SERENGETI_ADMIN_PASSWORD="$PW" \
  -e SERENGETI_TLS_KEYSTORE=/certs/tls.p12 -e SERENGETI_TLS_KEYSTORE_PASSWORD_FILE=/certs/password "$IMAGE" >/dev/null
docker run -d -m 64m "${L[@]}" --name "$RUN-silent" --network "$RUN" --network-alias silent python:3.12-alpine \
  python -c 'import socket, time; s = socket.socket(); s.bind(("", 7999)); s.listen(64); time.sleep(3600)' >/dev/null
docker run -d -m 64m "${L[@]}" --name "$RUN-cut" --network "$RUN" --network-alias cut python:3.12-alpine \
  python -c 'import socket
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("", 7997)); s.listen(64)
while True:
    c, _ = s.accept(); c.recv(65536)
    c.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"type\":"); c.close()' >/dev/null

up() {
  for _ in $(seq 60); do
    docker run --rm "${L[@]}" --network "$RUN" -v "$RUN-certs:/certs:ro" curlimages/curl:8.11.1 \
      -sf --cacert /certs/ca.pem "$1/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "no answer from $1" >&2
  exit 1
}
up http://node:1985
up https://tls:1985

env=(-e SERENGETI_URL=http://node:1985 -e SERENGETI_TLS_URL=https://tls:1985 -e SERENGETI_CA=/certs/ca.pem
  -e SERENGETI_SILENT_URL=http://silent:7999 -e SERENGETI_CUT_URL=http://cut:7997 -e SERENGETI_PASSWORD="$PW")
common=(--rm -m 256m "${L[@]}" --network "$RUN" "${env[@]}" -v "$RUN-certs:/certs:ro")
rc=0
echo "# Python"
docker run "${common[@]}" -v "$PWD/clients/python:/c:ro" -w /c python:3.12-alpine \
  python -m unittest -v test_serengeti || rc=1
echo "# JavaScript"
docker run "${common[@]}" -v "$PWD/clients/js:/c:ro" -w /c -e NODE_EXTRA_CA_CERTS=/certs/ca.pem node:22-alpine \
  node --test --test-reporter=spec test.mjs || rc=1
exit $rc
