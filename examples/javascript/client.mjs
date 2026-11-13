// Creates a database and a table, inserts two rows, selects them and prints them, tab-separated.
//
// Uses the built-in fetch (Node.js 18 or later) and nothing else. Each run adds two rows to
// examples.javascript_items.
//
//   SERENGETI_URL=http://127.0.0.1:1985 SERENGETI_PASSWORD='...' node client.mjs
//
// SERENGETI_URL defaults to http://127.0.0.1:1985. A password is required; there is no default.
// Exit status is 0 on success, 2 when SERENGETI_PASSWORD is not set, and 1 when the node refuses a
// request or cannot be reached. Errors print the node's "error" message to stderr.

const url = (process.env.SERENGETI_URL ?? "http://127.0.0.1:1985").replace(/\/+$/, "");
const password = process.env.SERENGETI_PASSWORD;

async function query(sql) {
  let reply;
  try {
    reply = await fetch(`${url}/v1/query`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Basic " + Buffer.from(`admin:${password}`).toString("base64"),
      },
      body: JSON.stringify({ query: sql }),
    });
  } catch (err) {
    throw new Error(`cannot reach ${url}: ${err.cause?.message ?? err.message}`);
  }
  const text = await reply.text();
  let data = null;
  try {
    data = JSON.parse(text);
  } catch {
    // Not JSON: the node sends no body for some refusals (for example 401 without credentials).
  }
  if (!reply.ok) {
    throw new Error(`HTTP ${reply.status}: ${data?.error ?? "no error message from the server"}`);
  }
  return data;
}

async function main() {
  if (!password) {
    console.error("set SERENGETI_PASSWORD to the admin password");
    return 2;
  }
  try {
    await query("CREATE DATABASE IF NOT EXISTS examples");
    await query("CREATE TABLE IF NOT EXISTS examples.javascript_items");
    await query("INSERT INTO examples.javascript_items (name, qty) VALUES ('bolt', 120), ('nut', 300)");
    const result = await query("SELECT name, qty FROM examples.javascript_items WHERE qty > 100 ORDER BY name");
    console.log(result.columns.join("\t"));
    for (const row of result.rows) {
      console.log(row.map((value) => (value === null ? "" : String(value))).join("\t"));
    }
    return 0;
  } catch (err) {
    console.error(`serengeti: ${err.message}`);
    return 1;
  }
}

process.exitCode = await main();
