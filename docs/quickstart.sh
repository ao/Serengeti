#!/usr/bin/env bash
# Runs the quick start in README.md against an image and checks the output.
#
# The code blocks of README's "Quick start" section are kept here too, and the script stops if the two
# differ, so the README and this file cannot drift apart. The blocks run as written, with three changes
# that keep them off this machine's ports and names:
#   - the image tag is IMAGE, and the container, volume and network names carry this run's id;
#   - the port is not published; the node is reached as serengeti:1985 on a private network;
#   - curl runs in a curlimages/curl container on that network, not on this machine.
#
# Everything the script creates has the label serengeti-quickstart=<run id> and is removed on exit. The
# image is kept. Needs Docker and bash; nothing else runs on this machine.
#
#   IMAGE=serengeti:quickstart docs/quickstart.sh
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=${IMAGE:-serengeti:quickstart}
CLIENT_IMAGE=curlimages/curl:8.11.1
RUN=qs$$
LABEL="serengeti-quickstart=$RUN"
NET=$RUN-net
TMP=$(mktemp -d)
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

# The README's Quick start code blocks, one "#----" line before each, exactly as README.md has them.
canonical() {
  echo '#----'
  cat <<'EOF'
docker build -t serengeti .
docker run -d --name serengeti -p 127.0.0.1:1985:1985 -v serengeti-data:/data serengeti
EOF
  echo '#----'
  cat <<'EOF'
# Wait until the node answers, then set the admin password with the one-time setup token.
for _ in $(seq 60); do curl -sf http://127.0.0.1:1985/health >/dev/null && break; sleep 1; done
export SERENGETI_PASSWORD='choose a long password'
token=$(docker exec serengeti cat /data/setup-token)
curl -s -H 'Content-Type: application/json' \
  --data "{\"token\": \"$token\", \"password\": \"$SERENGETI_PASSWORD\"}" http://127.0.0.1:1985/v1/setup; echo
# {"user":"admin"}
EOF
  echo '#----'
  cat <<'EOF'
q() { curl -s -u "admin:$SERENGETI_PASSWORD" -H 'Content-Type: application/json' \
        --data "{\"query\": \"$1\"}" http://127.0.0.1:1985/v1/query; echo; }

q "CREATE DATABASE shop"
q "CREATE TABLE shop.items"
q "INSERT INTO shop.items (name, qty) VALUES ('bolt', 120), ('nut', 300)"
q "SELECT name, qty FROM shop.items WHERE qty > 200"
# {"type":"rows","columns":["name","qty"],"rows":[["nut",300]],"count":1,"truncated":false}
EOF
}

# The same blocks as README.md's Quick start section, extracted the same way.
readme_blocks() {
  awk '
    /^## Quick start/ { in_section = 1; next }
    /^## /            { in_section = 0 }
    in_section && /^```sh$/ { print "#----"; in_block = 1; next }
    in_section && /^```$/   { in_block = 0; next }
    in_block { print }
  ' README.md
}

canonical > "$TMP/canonical"
readme_blocks > "$TMP/readme"
if ! diff -u "$TMP/readme" "$TMP/canonical" > "$TMP/diff"; then
  echo "README.md's Quick start differs from docs/quickstart.sh:"
  cat "$TMP/diff"
  exit 1
fi
ok "README quick start is the same text as this script"

# Runnable copy: host-specific parts replaced, as listed in the header.
sed \
  -e "s|^docker build -t serengeti \\.\$|docker build -t $IMAGE .|" \
  -e "s|^docker run -d --name serengeti -p 127\\.0\\.0\\.1:1985:1985 -v serengeti-data:/data serengeti\$|docker run -d --label $LABEL --name $RUN-serengeti --network $NET --network-alias serengeti -v $RUN-data:/data $IMAGE|" \
  -e "s|docker exec serengeti |docker exec $RUN-serengeti |" \
  -e "s|127\\.0\\.0\\.1:1985|serengeti:1985|g" \
  "$TMP/canonical" > "$TMP/run.body"
# Every host-specific form must have been replaced; a README edit that the sed rules miss would otherwise
# publish a port or reuse a name on this machine.
if grep -Eq -- '-p 127|--name serengeti |docker build -t serengeti |127\.0\.0\.1' "$TMP/run.body"; then
  echo "the sed rules do not match README.md any more; update them in docs/quickstart.sh"
  exit 1
fi
{
  echo "curl() { docker exec $RUN-client curl \"\$@\"; }"
  cat "$TMP/run.body"
} > "$TMP/run.sh"

# Labelled resources, created before the commands use them.
docker network create --label "$LABEL" "$NET" >/dev/null
docker volume create --label "$LABEL" "$RUN-data" >/dev/null
docker run -d --label "$LABEL" --name "$RUN-client" --network "$NET" \
  --entrypoint sleep "$CLIENT_IMAGE" 3600 >/dev/null

# The blocks run from the repository root, where "docker build ." expects the build context.
set +e
bash -e "$TMP/run.sh" > "$TMP/log" 2>&1
status=$?
set -e
if [ "$status" = 0 ]; then
  ok "the quick start commands run to the end (image $IMAGE)"
else
  bad "the quick start commands" "exit status $status"
  sed 's/^/    | /' "$TMP/log" | tail -n 40
fi

# Each "# {...}" line in the README is the output of the command above it.
expected=0
while IFS= read -r line; do
  expected=$((expected + 1))
  want=${line#\# }
  if grep -Fxq -- "$want" "$TMP/log"; then
    ok "output matches the README: ${want:0:60}"
  else
    bad "output matches the README" "no line equal to: $want"
  fi
done < <(grep '^# {' "$TMP/canonical")
[ "$expected" -gt 0 ] || bad "README shows expected output" "no '# {' lines found"

# The node refuses requests without credentials, and answers its health check.
code() { docker exec "$RUN-client" curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"; }
if [ "$(code http://serengeti:1985/v1/query)" = 401 ]; then ok "a query without credentials is 401"; else bad "a query without credentials" "not 401"; fi
if [ "$(code http://serengeti:1985/health)" = 200 ]; then ok "GET /health answers 200 without credentials"; else bad "GET /health" "not 200"; fi

echo
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
