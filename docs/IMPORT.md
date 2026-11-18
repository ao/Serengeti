# Import and export

`serengeti import` loads rows into a table from NDJSON or CSV, and `serengeti export` writes a table out
in `_id` order. Both are clients of the HTTP API: import sends INSERT statements to `POST /v1/query`, and
export reads pages of SELECT. They work against any node the account can reach, with its role: import
needs a write account, export a read one.

```sh
serengeti import --url https://db1:1985 --ca ca.pem --create shop.items items.ndjson
serengeti export --url https://db1:1985 --ca ca.pem -o items.ndjson shop.items
serengeti export --csv --where 'qty > 100' shop.items > big.csv
serengeti import --csv --csv-types --on-conflict update shop.items big.csv
```

The node, the account and the password are found as for `serengeti shell`: `--url` (default: this
machine's node, from its settings), `--user` (default `SERENGETI_USER`, else `admin`), `--ca`, `--config`,
and the password from `SERENGETI_PASSWORD`, the node's own admin password beside it, or the terminal. In a
node's container, `docker exec -i NAME serengeti import shop.items < items.ndjson` needs nothing more.

## Import

```
serengeti import [--csv [--csv-types]] [--on-conflict fail|skip|update] [--generate-ids] [--create]
                 [--batch N] [--retries N] DB.TABLE [FILE]
```

The rows come from FILE, or from standard input without it (or with `-`), as UTF-8. A byte order mark
at the start is skipped; bytes that are not UTF-8 stop the import at their line. `--create` creates the
database and the table first if they are not there; without it, a missing table stops the import before
any row is read.

### NDJSON

One JSON object per line; blank lines are skipped.

- Keys are column names. They are folded to lowercase, as names in a statement are, and must be names of
  the query language: ASCII letters, digits and `_`, not starting with a digit, at most 63 characters, not
  a keyword (QUERY.md, Grammar). A key that is not a name, and two keys that fold to one name (`Qty` and
  `qty`), stop the import at their line.
- `_id` is a non-empty string. A line without one stops the import, unless `--generate-ids` is given: then
  the row gets a random UUID, made by import rather than by the node, so a batch sent again after a lost
  answer does not insert its rows twice.
- Values are any JSON values: strings, numbers, `true`, `false`, `null`, objects and arrays, nested at
  most 64 deep. An integer is stored as a 64-bit integer, exactly, and a number with a fraction or an
  exponent as a double, so `1` and `1.0` stay different, `-0.0` keeps its sign, and 9007199254740993
  (2^53 + 1) is not rounded. `null` stores a null, which reads as a missing column does.
- What could not be stored exactly stops the import at its line: an integer outside the 64-bit range, a
  number beyond the range of a double, NaN and Infinity (which are not JSON), and a string holding half
  of a surrogate pair.

### CSV

With `--csv`, the rows are CSV as RFC 4180 describes it: the first record is a header of column names
(the same rules as NDJSON keys), and each record after it has one field per column. A field may be quoted
with `"`, and a quoted field may hold commas, line breaks and quotes written as `""`. Lines end with CRLF
or LF, and empty lines are skipped. A record with a different number of fields, a quote inside an
unquoted field, text after a closing quote, and a quote never closed stop the import at the line where
the record starts.

The type of each field:

- An empty unquoted field leaves the column out of the row; it reads as null. `""` is the empty string.
- A quoted field is a string.
- Without `--csv-types`, every other field is a string too.
- With `--csv-types`, an unquoted `true` or `false` (in any case) is a boolean, and an unquoted JSON
  number is a number, by the NDJSON rules above: `3` an integer, `2.5` and `1e2` doubles, `-0.0` a
  negative zero. `007`, `+1` and ` 12` are not JSON numbers and stay strings. An unquoted `NaN`, `inf` or
  `Infinity`, and a number that could not be stored exactly, stop the import at their line.
- `_id` is always a string, also with `--csv-types`.

This is the rule PostgreSQL's `COPY ... CSV` uses for null, and Python's `csv.QUOTE_NONNUMERIC` for
strings. A tool that quotes every field gives only strings; `serengeti export --csv` quotes strings and
nothing else, so its output reads back with `--csv-types` as the same values.

### Batches

Rows are sent in INSERT statements of at most `--batch` rows (default 1000, at most 10,000), and every
request body stays within the node's limit of 1 MiB: the size is counted in the bytes the request will
have, escapes included. An INSERT has one column list, so rows wait in groups, one per column list, and
every group is sent once one is full (or 32 groups, or 4 MiB, are waiting). A line whose INSERT alone
would be over 1 MiB stops the import at that line.

`--on-conflict` says what a row whose `_id` is already in the table does:

| Value | Statement | The row in the table |
|---|---|---|
| `fail` (default) | `INSERT` | stops the import |
| `skip` | `INSERT ... ON CONFLICT DO NOTHING` | is kept as it is |
| `update` | `ON CONFLICT DO NOTHING`, then for each row that was there `INSERT ... ON CONFLICT DO UPDATE SET` | gets each column the line has, except `_id`; its other columns keep their values |

With `update`, a row that was there takes one statement of its own. A line with only `_id` changes
nothing and counts as skipped. An `_id` repeated in the input is sent after the line before it: with
`skip` the first line stays, with `update` the last one wins, and with `fail` the second line stops the
import.

### Failures

At the end, also after a failure, import prints the rows read, inserted, updated and skipped on standard
output. When a line is refused, by the rules above or by the node (a 400, or a 403 for an account that
may not write), the rows not yet written are sent again one at a time, in line order, so the message
names the first line the node refuses, and every line before it is written. Ten rows of which the fifth
is already in the table:

```
serengeti import: line 5: 400: a row with _id 'e' already exists in 'shop.items'
serengeti import: the lines before line 5 are written; with line 5 put right, a rerun with --on-conflict skip finishes the job
read 10 rows: 4 inserted, 0 updated, 0 skipped (0.1 s)
```

Some lines after it may be written too: those in groups sent before the one refused. A rerun with
`--on-conflict skip` skips every row already there, so it finishes the job either way.

A 503 (the node is at its memory limit, is stopping, or cannot reach a majority), a 429 (too many failed
logins from the address), a proxy's 502 or 504, or a connection that fails, is not a refusal: the
statement is sent again, up to `--retries` times (default 8), after 0.5 s, 1 s, 2 s, 4 s and then 8 s
each time; a 429 waits as long as its `Retry-After` says, up to 60 s. If the node is still unavailable,
the import stops, naming the first line not written.

A statement whose answer was lost may have been written the first time. Sent again, its rows count as
skipped with `skip`, and as updated with `update`; with `fail`, the node refuses the rows as already there,
and the message says the statement had been sent again. Rows with ids from `--generate-ids` are sent with
`ON CONFLICT DO NOTHING` whatever `--on-conflict` says, and count as inserted. CONSISTENCY.md, "Writes",
says when a 503 can follow a write that was made.

Exit status: 0 when every row was imported; 1 when a line was refused; 2 when the arguments are wrong,
the file cannot be read, or the node cannot be reached at the start or refuses the account; 3 when the
node stayed unavailable through the retries.

## Export

```
serengeti export [--csv] [--where CONDITION] [--page N] [--retries N] [-o FILE] DB.TABLE
```

Export reads the table in keyset pages, as QUERY.md ("Reading a large table") describes:

```
SELECT * FROM shop.items ORDER BY _id LIMIT 100
SELECT * FROM shop.items WHERE _id > 'the last _id read' ORDER BY _id LIMIT 1000
```

Each page reads only its own rows and no page uses OFFSET, so a table of any size is read in time
proportional to its size. `--page` is the most rows in a page (default 1000, at most 10,000); the first
page has 100 rows, and later pages are sized to answers of about 4 MB, so large rows make smaller pages.
`--where` takes a WHERE condition of QUERY.md; it is parsed first, so it cannot reach past the page's own
condition (`_id > 'x' AND (CONDITION)`), and GROUP BY, HAVING, ORDER BY, LIMIT and OFFSET in it are
refused.

An export is not a snapshot. Each page sees the table as it is when that page runs: a row inserted
during the export is in it if its `_id` comes after the last `_id` read at that moment, and not
otherwise; a row changed after its page was read is exported as it was; a row deleted before its page is
read is not exported. `serengeti backup` takes a consistent copy of every table.

The output goes to FILE, written as `FILE.tmp` with mode 0600 and renamed once complete (nothing is kept
when the export fails), or to standard output.

- NDJSON, the default: one JSON object per row, with the columns in the order `SELECT *` gives them
  (`_id` first) and null values left out, since a missing column and a null read the same. Numbers are
  written as the node holds them: integers without a fraction, doubles always with one or with an exponent
  (`1.0`, `-0.0`, `1.0E21`), so import reads each back as the same type.
- `--csv`: a header of every column found in the rows, `_id` first, in the order first seen, then one
  record per row. Strings are always quoted, numbers and booleans are bare, null is empty, and objects and
  arrays are written as their JSON text, quoted. Lines end with CRLF. The header is known only once every
  row is read, so the rows wait meanwhile in a file of mode 0600, deleted at the end: next to FILE, or in
  the system's temporary directory when the output is standard output.

On standard error, export says how many rows it wrote. The exit status is that of import: 0 for a
complete export, 1 when the node refused a page (a table that does not exist, a `--where` naming a
database the account may not use) or the output could not be written, 2 for wrong arguments or a node
that cannot be reached at the start or refuses the account, and 3 when the node stayed unavailable
through the retries.

## Round trip

Exported NDJSON imported into an empty table gives the same rows: every value with its type, including
`-0.0`, integers beyond 2^53, strings with any Unicode (surrogate pairs included), and nested objects and
arrays. Exporting that table again gives the same text. Explicit nulls are the exception in a narrow
sense: they are not exported, so they come back as missing columns, which read the same.

CSV keeps what its type rule keeps. With `--csv-types`, strings, integers, doubles (also `-0.0`) and
booleans come back as they were; objects and arrays come back as strings holding their JSON text; null
and missing columns both come back missing. Without `--csv-types`, every value comes back a string.

`ImportExportTest` checks both round trips, every refusal and error path, retries through a proxy that
answers 503, 429 or drops connections, and 100,000 rows: on an AWS c7g.4xlarge (JDK 21, the node in the
same JVM, `fsync` `always`, plain HTTP on loopback), 100,000 rows of five columns (7.8 MB of NDJSON)
import in 2.1 s and export in 0.55 s, or 0.72 s as CSV. From the image, with the command and the node
each in its own container on one c7g.4xlarge (plain HTTP on a Docker network, the image's settings for
the command line: 96 MB of heap, no optimising compiler), the same 8.2 MB of rows imported in 2.9 s and
3.6 s in two runs, and exported in 0.6 s and 0.7 s, or 1.0 s and 1.1 s as CSV, as the commands report;
starting each container added 0.4 to 0.8 s. A rerun with `--on-conflict skip` took 2.1 s, and the CSV
imported back with `--csv-types` in 2.5 s and exported as the same NDJSON. `tests/container/run.sh` runs
both commands from the image against a TLS node with `--ca`.
