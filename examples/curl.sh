#!/bin/sh
# Creates a database and a table, inserts two rows, selects them and prints the result as JSON.
# Needs curl and a POSIX shell. Each run adds two rows to examples.curl_items.
#
#   SERENGETI_URL=http://127.0.0.1:1985 SERENGETI_PASSWORD='...' sh examples/curl.sh
#
# SERENGETI_URL defaults to http://127.0.0.1:1985. A password is required; there is no default.
set -eu
SERENGETI_URL=${SERENGETI_URL:-http://127.0.0.1:1985}
: "${SERENGETI_PASSWORD:?set SERENGETI_PASSWORD to the admin password}"

# query SQL: prints the response body on success. On an error status it prints the server's "error"
# message to stderr and exits 1.
query() {
  out=$(curl -s -w '\n%{http_code}' -u "admin:$SERENGETI_PASSWORD" \
    -H 'Content-Type: application/json' --data "{\"query\": \"$1\"}" "$SERENGETI_URL/v1/query") || {
    echo "serengeti: cannot reach $SERENGETI_URL" >&2
    exit 1
  }
  code=$(printf '%s\n' "$out" | tail -n 1)
  body=$(printf '%s\n' "$out" | sed '$d')
  case $code in
    2??) printf '%s\n' "$body" ;;
    *)
      msg=$(printf '%s\n' "$body" | sed -n 's/.*"error":"\([^"]*\)".*/\1/p')
      echo "serengeti: HTTP $code: ${msg:-no error message from the server}" >&2
      exit 1
      ;;
  esac
}

query "CREATE DATABASE IF NOT EXISTS examples" >/dev/null
query "CREATE TABLE IF NOT EXISTS examples.curl_items" >/dev/null
query "INSERT INTO examples.curl_items (name, qty) VALUES ('bolt', 120), ('nut', 300)" >/dev/null
query "SELECT name, qty FROM examples.curl_items WHERE qty > 100 ORDER BY name"
