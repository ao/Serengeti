# Query language

Serengeti accepts one statement per query. The code is in
`src/main/java/com/ataiva/serengeti/core/query`: `Parser` turns text into a `Statement`,
`Executor` runs it against the storage engine, and `Query.run(text)` does both.

## Grammar

Keywords are not case-sensitive. Names (databases, tables, columns) are folded to lowercase.
String values keep their case. Whitespace and `--` comments (to the end of the line) are ignored.
A statement may end with one `;`.

```
statement   = ( create | drop | show | insert | select | update | delete ) [ ";" ] ;

create      = "CREATE" "DATABASE" [ "IF" "NOT" "EXISTS" ] name
            | "CREATE" "TABLE" [ "IF" "NOT" "EXISTS" ] table
            | "CREATE" "INDEX" [ "IF" "NOT" "EXISTS" ] "ON" table "(" name { "," name } ")" ;
drop        = "DROP" "DATABASE" [ "IF" "EXISTS" ] name
            | "DROP" "TABLE" [ "IF" "EXISTS" ] table
            | "DROP" "INDEX" [ "IF" "EXISTS" ] "ON" table "(" name { "," name } ")" ;
show        = "SHOW" "DATABASES"
            | "SHOW" "TABLES" "IN" name
            | "SHOW" "INDEXES" "IN" table
            | "SHOW" "CONFLICTS" "IN" table ;

insert      = "INSERT" "INTO" table "(" name { "," name } ")"
              "VALUES" tuple { "," tuple } ;
tuple       = "(" value { "," value } ")" ;

select      = "SELECT" [ "DISTINCT" ] ( "*" | item { "," item } )
              "FROM" table [ "WHERE" expr ] [ "GROUP" "BY" name { "," name } ] [ "HAVING" expr ]
              [ "ORDER" "BY" key { "," key } ] [ "LIMIT" integer ] ;
item        = name | aggregate ;
key         = item [ "ASC" | "DESC" ] ;
aggregate   = "COUNT" "(" ( "*" | [ "DISTINCT" ] name ) ")"
            | ( "SUM" | "MIN" | "MAX" | "AVG" ) "(" name ")" ;
update      = "UPDATE" table "SET" assignment { "," assignment } [ "WHERE" expr ] ;
assignment  = name "=" ( value | name ( "+" | "-" ) number ) ;
delete      = "DELETE" "FROM" table [ "WHERE" expr ] ;

expr        = and { "OR" and } ;
and         = not { "AND" not } ;
not         = "NOT" not | primary ;
primary     = "(" expr ")"
            | operand ( cmp value | "IS" [ "NOT" ] "NULL"
                      | [ "NOT" ] "IN" "(" value { "," value } ")"
                      | [ "NOT" ] "LIKE" string ) ;
operand     = name                                   (in WHERE)
            | name | aggregate                       (in HAVING) ;
cmp         = "=" | "!=" | "<>" | "<" | "<=" | ">" | ">=" ;

table       = name "." name ;
name        = ( letter | "_" ) { letter | digit | "_" } ;   (ASCII, at most 63 characters, not a keyword)
value       = string | number | "TRUE" | "FALSE" | "NULL" ;
string      = "'" { any character, with ' written as '' } "'" ;
number      = [ "-" ] digits [ "." digits ] [ ( "e" | "E" ) [ "+" | "-" ] digits ] ;
integer     = digits ;
```

The keywords are: AND, ASC, BY, COUNT, CREATE, DATABASE, DATABASES, DELETE, DESC, DROP, EXISTS,
FALSE, FROM, IF, IN, INSERT, INTO, IS, LIMIT, NOT, NULL, OR, ORDER, SELECT, SET, SHOW, TABLE, TABLES,
TRUE, UPDATE, VALUES, WHERE. They cannot be used as names. AVG, CONFLICTS, DISTINCT, GROUP, HAVING,
INDEX, INDEXES, LIKE, MAX, MIN, ON and SUM are not reserved: they have their meaning only where the
grammar puts them, so `SELECT sum, group FROM shop.items WHERE like = 1` reads three columns, and
`sum(sum)` adds up a column called `sum`. DISTINCT is the keyword right after SELECT unless a comma or
FROM follows, and right after `COUNT(` unless `)` follows: `SELECT distinct FROM t` reads a column called
distinct, `SELECT DISTINCT distinct FROM t` its distinct values, `COUNT(distinct)` counts its values and
`COUNT(DISTINCT distinct)` its distinct values. HAVING is the keyword only after the table, the WHERE
condition or the GROUP BY list.

A condition compares a column with a literal value, a list of them (IN) or a pattern (LIKE); the left
side is always a column, and in HAVING may also be an aggregate. `NOT` binds tighter than `AND`, which
binds tighter than `OR`.

Further parse rules:

- An INSERT lists each column once, and each tuple has one value per column.
- An UPDATE sets each column once. On the right of its `=` is a value, or a column (not `_id`) plus or
  minus a number; nothing else, no `*` or `/`, parentheses, two columns or functions (see "UPDATE from
  the row"). A minus glued to the number is the operator there: `n -1` and `n-1` are `n - 1`. `n--1` is
  `n` and a comment, so it is an error.
- CREATE INDEX and DROP INDEX list 1 to 4 columns, each once.
- An IN list has 1 to 1,000 values.
- In a LIKE pattern, `\` comes only before `%`, `_` or `\`.
- Only COUNT takes `*` or DISTINCT.
- Without GROUP BY, a select list with an aggregate has only aggregates, and cannot have ORDER BY or
  LIMIT: it returns one row (or none, after HAVING).
- With GROUP BY, each column of the select list is a GROUP BY column, and each ORDER BY key names a
  GROUP BY column or an aggregate of the select list written the same way (`ORDER BY count(*) DESC`;
  keywords and names are not case-sensitive there either). A GROUP BY lists each column once. `SELECT *`
  cannot have GROUP BY.
- SELECT DISTINCT lists columns, each once: not `*` and no aggregates. It cannot have GROUP BY or HAVING,
  and its ORDER BY keys name columns of its list.
- HAVING needs GROUP BY, or a select list of aggregates only. Each left side in it is a GROUP BY column or
  an aggregate; the aggregate need not be in the select list.
- ORDER BY an aggregate needs GROUP BY.
- An ORDER BY has 1 to 32 keys, none listed twice (`a` and `A`, or `count(*)` and `COUNT(*)`, are the
  same key, whatever their directions).
- The LIMIT is a non-negative whole number.

## Errors

Text that does not follow the grammar raises `ParseException`. The message ends with
`at position N`, where N is the 0-based offset where the problem was found, for example
`expected FROM but found name 'form' at position 9`. `ParseException.position()` returns the same
number. The offset counts UTF-16 code units, as Java and JavaScript strings do: a character outside the
Basic Multilingual Plane, such as an emoji, counts as two.

A statement that parses but cannot run raises `QueryException` (the parent class of
`ParseException`), for example `table 'shop.items' does not exist`. Nothing is written when a
statement fails.

## Values

A value is a string, a number, a boolean or null.

- Numbers without a fraction or exponent that fit in a signed 64-bit integer are integers. Other
  numbers are 64-bit doubles. A number too large for a double is a parse error.
- Each row has a string key in the column `_id`. INSERT may set it (it must be a non-empty
  string); otherwise a random UUID is used. Inserting an `_id` that already exists in the table is
  an error, and so is setting `_id` in an UPDATE, even to the value it has. A deleted row's `_id` can
  be used again.
- A column a row does not have reads as null. `INSERT ... VALUES (NULL)` and `SET c = NULL` store
  an explicit null, which reads the same.
- Tables have no schema: any row can have any columns.

## Comparisons

Conditions use three-valued logic: a comparison is true, false or unknown. A row matches only if
the whole condition is true.

- A comparison with null on either side is unknown. `c = NULL` never matches; use `c IS NULL`.
- `IS NULL` and `IS NOT NULL` are never unknown.
- `NOT unknown` is unknown. `AND` is false if any term is false, otherwise unknown if any term is
  unknown. `OR` is true if any term is true, otherwise unknown if any term is unknown.
- Integers and doubles compare by numeric value, exactly: `1 = 1.0` is true, and
  9007199254740993 is greater than the double 9007199254740993.0 (which is 9007199254740992).
- Strings compare with each other by UTF-16 code unit (Java `String.compareTo`), so the comparison
  is case-sensitive.
- Booleans compare with each other, false before true.
- Values of different types (for example a number and a string) are not equal: `=` is false, `!=`
  and `<>` are true, and `<`, `<=`, `>`, `>=` are false.

So `price != 3` matches a row whose price is the string `'3'`. Neither `price != 3` nor
`NOT price = 3` matches a row with no price.

`c IN (v1, v2, ...)` is exactly `c = v1 OR c = v2 OR ...`, and `c NOT IN (v1, ...)` is
`NOT (c = v1 OR ...)`. So `c IN (1, NULL)` matches a row where c is 1, but a null in the list makes
NOT IN never true: where c is none of the other values, `c = NULL` leaves the OR unknown, and its NOT
too. A row with no c matches neither IN nor NOT IN.

`c LIKE 'pattern'` matches strings: `%` stands for any run of characters (also none), `_` for exactly
one UTF-16 code unit (so an emoji, which is two, is `__`), and `\%`, `\_` and `\\` for those characters;
the rest of the pattern must match exactly, case included. A pattern without wildcards is `=`. LIKE is
unknown when c is null or missing, and false when c is not a string, so NOT LIKE is true then, as `!=`
is for values of different types. A quote in the pattern is written `''`, as in any string:
`name LIKE 'it''s%'`.

## Indexes

`CREATE INDEX ON shop.items (qty)` keeps, for each value of `qty`, the rows that hold it. SELECT (with or
without aggregates), UPDATE and DELETE then find rows through the index when the WHERE clause is one
term on an indexed column, or an AND of terms of which one is; with several, the term that matches the
fewest rows is used (and see below for indexes on several columns). The terms an index answers are:

- a comparison with a value (`=`, `<`, `<=`, `>`, `>=`);
- an IN list: the rows of each value;
- a LIKE whose pattern starts with a literal prefix (the characters before the first `%` or `_` that
  is not escaped): the strings that start with it. A pattern without wildcards is an `=` lookup.

The rest of the condition is checked on those rows, so an index changes how fast a query runs, never
its result. `!=`, OR, NOT, NOT IN, NOT LIKE, IS NULL and a LIKE that starts with a wildcard scan the
table.

An index holds the booleans, numbers and strings of its column, compared as above (`qty = 10` also
finds 10.0). Rows where the column is missing, null, an object or an array are not in it, which is right:
no comparison of those with a value is true. `_id` needs no index, and `CREATE INDEX` on it is refused:
`WHERE _id = 'x'`, ranges, IN lists and LIKE prefixes of `_id` never scan.

An index is built from the rows when it is created and kept up to date by every write. In a cluster the
definition replicates like a table's, and each node builds its own. Dropping the table drops its indexes.
`SHOW INDEXES IN db.table` returns the column `column`, one row per index: `qty`, or `kind, qty` for an
index on two columns.

### Indexes on several columns

`CREATE INDEX ON shop.orders (customer, placed)` keeps the rows in the order of their `customer`, and
among rows with the same `customer` in the order of `placed`, each column in the ORDER BY order (see
Ordering). An index lists 2 to 4 columns, none twice and not `_id`. `(customer, placed)` and
`(placed, customer)` are two indexes, and both can exist next to one on `customer` alone; `DROP INDEX ON
shop.orders (customer, placed)` drops the index on exactly those columns in that order.

It answers a WHERE whose top-level AND fixes its first columns, in order, and may limit the next one:

- each of the first columns has a term `=` a value, an IN list, or a LIKE without wildcards. Several IN
  lists stand for every combination of their values, up to 1,000 combinations; a column whose list would
  take the count past that ends the columns the index is read for;
- the next column may then have any number of `<`, `<=`, `>`, `>=` and LIKE terms with a literal prefix,
  and the index reads the rows that satisfy all of them.

So `WHERE customer = 'c1' AND placed >= '2026-01' AND placed < '2026-02'` reads the rows of one customer in
January, `WHERE customer IN ('c1', 'c2') AND placed > '2026-06'` those of two customers since June, and
`WHERE customer = 'c1'` all the rows of `c1`. The index is read only for its first columns that the
condition fixes: `WHERE placed > '2026-06'` or `customer != 'c1' AND placed = '2026-06-01'` does not use
it. The rest of the condition is checked on the rows the index finds, as for an index on one column.

A row whose first column is missing, null, an object or an array is not in the index; no lookup could
find it. Past the first column every value is kept, missing and null ones sorting first: a row with a
`customer` and no `placed` is found by `customer = 'c1'`, and not by a range on `placed`.

When more than one index can answer a condition, the read of the fewest rows is used, counting a term on
`_id` or on a column with its own index as a read too, and among reads of as many rows the one that names
the most columns of it (a range counts as one column). A range that would gather more than 100,000 rows
gives way to the next read before the table is scanned. COUNT(*), GROUP BY, UPDATE and DELETE use it as SELECT does.

Each row in an index takes an entry, estimated at 40 bytes, and each distinct combination of values about
130 more; both count towards `memory_limit_percent` (docs/CONFIGURATION.md). In the index definition that
replicates and that `indexes.jsonl` keeps, the columns are joined by commas in the one `column` field. A
name cannot hold a comma, so a 2.1 node holds such an index as an index on a column no row has: it lists
it as `customer,placed`, answers those queries by scan, and keeps the definition, so a node rolled back to
2.1 and upgraded again has the index back. 2.0 nodes are not sent index definitions.

## Ordering

Without ORDER BY, rows come back in `_id` order. ORDER BY sorts on its first key, rows equal on that by
the second, and so on. Each key sorts in this order ascending: null (and missing), false, true, numbers,
strings, then any other stored value; DESC reverses it for that key. Rows equal on every key are ordered
by `_id`, ascending whatever the directions. So `ORDER BY kind, price DESC` lists the kinds in order, and
within a kind the dearest first, and rows of the same kind and price by `_id`.

## Aggregates and GROUP BY

```
SELECT COUNT(*), SUM(qty), AVG(price) FROM shop.items WHERE qty > 0;
SELECT kind, COUNT(*), MAX(price) FROM shop.items GROUP BY kind ORDER BY count(*) DESC LIMIT 10;
```

- `COUNT(*)` counts rows, and `COUNT(c)` the rows where c is not null (a missing c counts as null).
  `COUNT(DISTINCT c)` counts the distinct values of c that are not null, equal as GROUP BY makes them
  (below): 1 and 1.0 are one value, and so are equal objects and equal arrays.
- `SUM(c)` and `AVG(c)` take the numbers of c and skip every other value: null, strings, booleans,
  objects and arrays. If the numbers are all integers, SUM is an integer, added exactly; if the total
  does not fit in 64 bits the statement fails with `SUM overflows a 64-bit integer` (400 over HTTP). Only
  the total counts, so a sum that passes the limit part way and comes back into range is fine (the
  largest integer, plus 1, minus 1, is the largest integer). Once a double is among them, SUM is a double:
  the integers are added exactly and the doubles in `_id` order, then the two are added. AVG is that
  total divided by how many numbers there are, always a double. A double total beyond the range of a
  double fails the statement (`SUM overflows a double`, `AVG overflows a double`).
- `MIN(c)` and `MAX(c)` are the least and greatest value of c that is not null, in the ORDER BY order
  above: booleans come below numbers, numbers below strings, and an object or array above all of them.
  So the MIN of a column holding `TRUE`, 1 and `'a'` is `TRUE`. Of equal values (1 and 1.0)
  they give the one from the row with the lowest `_id`.
- Over no rows, or for SUM and AVG no numbers, COUNT is 0 and the others are null.

Without GROUP BY the result is one row, also when no row matches. With GROUP BY it is one row per
distinct combination of the GROUP BY values, and none when no row matches. Values are the same as for
`=`, so 1 and 1.0 are one group, shown as the value of the row with the lowest `_id`; null is a value of
its own, and a missing column counts as null. Objects and arrays are one group when they are equal,
whatever the order of their keys.

Groups come back ordered by the GROUP BY columns ascending, in the ORDER BY order, with objects and
arrays (equal there) ordered by their JSON text with keys sorted. ORDER BY sorts on GROUP BY columns and
aggregates of the select list instead; groups equal on every key keep their GROUP BY order, in any
direction. LIMIT counts groups. A statement fails if the SUM or AVG of any group cannot be given;
when several cannot, the first in GROUP BY order is named.

## SELECT DISTINCT

```
SELECT DISTINCT kind, size FROM shop.items WHERE qty > 0 ORDER BY size DESC, kind;
```

`SELECT DISTINCT a, b FROM t WHERE ...` returns what `SELECT a, b FROM t WHERE ... GROUP BY a, b`
returns: one row per distinct combination of the values, with GROUP BY's equality (1 and 1.0 are one
value, shown as the row with the lowest `_id` has it; null and missing are one value; equal objects and
arrays are one), in GROUP BY's order unless ORDER BY says otherwise, and with its caps. ORDER BY names
columns of the list, and LIMIT counts rows.

To read the distinct values of a column called distinct, write `SELECT DISTINCT distinct FROM t`.

## HAVING

```
SELECT kind, COUNT(*) FROM shop.items GROUP BY kind HAVING COUNT(*) > 1 AND MAX(price) < 10;
SELECT kind FROM shop.items GROUP BY kind HAVING SUM(qty) IS NULL OR kind LIKE 'b%';
SELECT COUNT(*) FROM shop.items WHERE qty = 0 HAVING COUNT(*) > 100;
```

HAVING keeps the groups its condition is true for. The condition has the form of a WHERE condition
(comparisons, IN, LIKE, IS [NOT] NULL, AND, OR, NOT and parentheses, with the same three-valued logic),
except that the left side of each term is a GROUP BY column or an aggregate. An aggregate in HAVING need
not be in the select list: it is computed and not returned. It compares as the select list would show it:
COUNT as an integer, AVG as a double, SUM as an integer or a double, MIN and MAX as the value. So
`HAVING AVG(qty) = 2` holds for an average of 2.0, and `HAVING SUM(qty) > 0` is unknown, and drops the
group, when the group has no numbers and its SUM is null.

Without GROUP BY, the select list has only aggregates and the whole input is one group, also when no row
matches: `SELECT COUNT(*) FROM t WHERE x = 1 HAVING COUNT(*) = 0` returns one row, 0, when nothing
matches, and no row otherwise.

HAVING drops groups before ORDER BY and LIMIT. Every aggregate, of the select list and of HAVING, is
computed for every group first, so one that cannot be given (a SUM beyond 64 bits) fails the statement
even in a group that HAVING drops.

## Results

`Result.toJson()` returns a map that `core.json.Json.write` can serialise.

SELECT and SHOW return rows. Each row is an array with one value per column:

```
{"type":"rows","columns":["_id","name"],"rows":[["a","Tea"],["b","Coffee"]],"count":2,"truncated":false}
```

- `SELECT *` lists `_id` first, then every other column found in the returned rows, in the order
  first seen.
- `SELECT COUNT(*)` returns one column `count` and one row.
- In a select list with aggregates, a GROUP BY column keeps its name, `COUNT(*)` is `count`, and any
  other aggregate is written in lowercase with its column: `count(qty)`, `count(distinct qty)`,
  `sum(qty)`, `min(qty)`, `max(qty)`, `avg(qty)`. The columns of SELECT DISTINCT keep their names.

```
{"type":"rows","columns":["kind","count","sum(qty)"],"rows":[["bolt",2,420],["nut",1,300]],"count":2,"truncated":false}
```
- `SHOW DATABASES` returns the column `database`; `SHOW TABLES IN db` returns `table`.
- `SHOW CONFLICTS IN db.table` returns one row per version of each row that has more than one
  (writes made on nodes that had not seen each other's; see CONSISTENCY.md), with the columns `_id`,
  `current` (true for the version that is the row's value, listed first), `deleted`, `node` (the id of
  the node that wrote it, from its `node-id` file), `at` (Unix milliseconds) and `row` (the version's value, or null for a delete).

Writes return the number of databases, tables or rows changed. INSERT also lists the `_id` of each
row, in insert order:

```
{"type":"write","affected":2,"ids":["a","5f0c6f0e-..."]}
```

`CREATE ... IF NOT EXISTS` on an existing object and `DROP ... IF EXISTS` on a missing one return
`"affected":0`.

## Limits

- A SELECT returns at most 10,000 rows (`Executor.DEFAULT_MAX_ROWS`; the cap is a constructor
  argument), and with GROUP BY or DISTINCT at most 10,000 groups. A LIMIT above the cap is an error.
  Without LIMIT, if more rows or groups match (after HAVING), the first 10,000 are returned and
  `truncated` is true. COUNT(*) is not capped.
- A GROUP BY holds at most 100,000 groups while it reads the rows (`Executor.DEFAULT_MAX_GROUPS`, also
  a constructor argument). A statement that would make more fails with `GROUP BY would make more than
  100,000 groups` as soon as it gets there, LIMIT or not, and HAVING does not change that: the groups are
  counted before it drops any. SELECT DISTINCT has the same cap, and fails with `SELECT DISTINCT would
  hold more than 100,000 rows`.
- The COUNT(DISTINCT) aggregates of one statement hold at most 100,000 values in all, over every group
  and every such aggregate (`Executor.DEFAULT_MAX_DISTINCT`, also a constructor argument). A statement
  that would hold more fails with `COUNT(DISTINCT) would hold more than 100,000 values`.
- A select list has at most 256 items (`Parser.MAX_SELECT_ITEMS`), a GROUP BY at most 32 columns
  (`Parser.MAX_GROUP_BY`), an ORDER BY at most 32 keys (`Parser.MAX_ORDER_BY`), and a LIKE pattern at
  most 1,000 characters (`Parser.MAX_LIKE_PATTERN`).
- An IN list has at most 1,000 values.
- Parentheses and NOT may nest at most 64 levels deep in one condition (`Parser.MAX_DEPTH`). Long
  chains of AND or OR do not count towards this.
- Names are at most 63 characters.
- SELECT with ORDER BY sorts all matching rows in memory before applying the limit, except when its
  first key is `_id` ascending, which is the order rows are read in (see below). A statement whose WHERE
  has no term an index answers (see Indexes) scans the table.

## Reading a large table

Read a table of any size in pages, each starting after the last `_id` of the one before:

```
SELECT * FROM shop.items ORDER BY _id LIMIT 1000;
SELECT * FROM shop.items WHERE _id > 'the last _id of the page before' ORDER BY _id LIMIT 1000;
```

and so on until a page has fewer than 1,000 rows. Rows are read in `_id` order, so such a page does
not sort, does not read the rows before it, and stops after its 1,000 rows (and one more, to know
whether `truncated` is true without LIMIT). The same holds without ORDER BY. A page may have up to
10,000 rows, the cap. Other conditions can be added with AND, `WHERE _id > 'x' AND kind = 2 ORDER BY
_id LIMIT 1000`; the page then reads rows until it has 1,000 that match.

Each page sees the table as it is when it runs. A row inserted between pages is read if its `_id`
comes after the last one already read, and missed otherwise; a row deleted before its page runs is not
returned.

## UPDATE from the row

`SET c = column + number` and `SET c = column - number` compute the new value from the row:

```
UPDATE shop.items SET stock = stock - 1 WHERE _id = 'tea' AND stock > 0;
UPDATE shop.items SET total = price + 5, views = views + 1;
```

The first takes one from the stock while there is any: it affects 1 row, or 0 once the stock is 0, so its
answer says whether the item was taken.

- The column on the right is any column of the row except `_id`, and need not be the one set. Every right
  side reads the row as it was before the statement, as in SQL: `SET a = a + 1, b = a + 1` sets both to the
  old a plus one. A WHERE that reads the column set changes each row once: `SET a = a + 1 WHERE a > 0`
  adds one to every row where a was above 0.
- Two integers give an integer, exactly. A result outside the 64-bit range fails the statement
  (`the result overflows a 64-bit integer`); it does not wrap round and does not become a double.
- With a double on either side, the result is a double: the one nearest the exact result. An integer
  beyond 2^53 is not made a double first, which would round twice: 9007199254740993 + 0.5 is
  9007199254740994.0, not 9007199254740992.0. A result beyond the range of a double fails the statement
  (`the result overflows a double`). A zero has the sign double arithmetic gives it: -0.0 - 0.0 is -0.0,
  and -0.0 + 0 is 0.0.
- A missing or null column gives null, as in SQL, and the null is written: `SET n = n + 1` on a row
  without n stores n as null. Comparisons with null are never true, so `WHERE n > 0` or `WHERE n IS NOT
  NULL` leaves such rows out.
- A string, boolean, object or array in the column fails the statement (400 over HTTP), with a message
  that names the row and the column: `cannot set stock = stock - 1 in the row with _id 'tea': stock is a
  string`.
- A statement changes every row it matches or none. When one row cannot be computed, nothing is written,
  and the message names the first such row in `_id` order.
- The number keeps its sign: `n + -1`, `n - -1`. A number with a fraction or exponent, or one beyond the
  64-bit range, is a double (see Values): `n + 1.0` is a double, and so is `n - 9223372036854775808`.

On one node, the value is computed from the row as the node holds it when the statement runs, under the
write lock (see "Writes and time"), so concurrent statements on one node never lose an increment: a unit
test runs 16 threads that each send `SET n = n + 1` 500 times to one row, and n ends at 8,000.

The result is written as a normal write of the whole row, so it replicates, and conflicts, as any write
does. Two nodes that each change the row before either has received the other's write make a conflict
(CONSISTENCY.md, "Conflicts"): SHOW CONFLICTS lists both versions, the row's value is the one with the
higher version, which holds that node's increments and not the other's, and the next write to the row
resolves the conflict, computing from that value. Nothing adds the two up. To count exactly in a cluster,
send a row's increments to one node, or check SHOW CONFLICTS and write the right total. A statement sent
again after a 503 or a lost answer is applied again if the first one was written (CONSISTENCY.md,
"Writes"); `SET n = n + 1 WHERE _id = 'a' AND n = 41` changes nothing the second time.

2.1 nodes answer this form with a parse error. During a rolling upgrade, send it to 2.2 nodes; what they
write is a normal row write, which 2.1 nodes take.

## Writes and time

Each write statement is checked and applied in one step under the storage engine's write lock, so
for example two concurrent inserts of the same `_id` cannot both succeed on one node, and `SET n = n + 1`
reads the row and writes it in that one step. All ops of
one statement carry the same hybrid logical clock stamp. Before stamping, the executor moves its
clock past the newest stamp in the store, so writes after a restart or after receiving newer data
are not lost to an older stamp.

## Examples

```
CREATE DATABASE shop;
CREATE TABLE shop.items;
INSERT INTO shop.items (_id, name, price) VALUES ('tea', 'Tea', 3), ('cake', 'Cake', 2.5);
INSERT INTO shop.items (name) VALUES ('Jam');
SELECT * FROM shop.items WHERE price >= 2 AND name != 'Cake' ORDER BY price DESC LIMIT 10;
SELECT COUNT(*) FROM shop.items WHERE price IS NULL;
SELECT name FROM shop.items WHERE _id IN ('tea', 'cake') OR name LIKE 'J%';
SELECT price, COUNT(*) FROM shop.items GROUP BY price ORDER BY count(*) DESC;
SELECT DISTINCT price FROM shop.items ORDER BY price DESC;
SELECT price, COUNT(DISTINCT name) FROM shop.items GROUP BY price HAVING COUNT(*) > 1;
SELECT name, price FROM shop.items ORDER BY price DESC, name LIMIT 10;
UPDATE shop.items SET price = 4, note = 'It''s back' WHERE _id = 'tea';
UPDATE shop.items SET price = price + 0.5 WHERE price < 3;
DELETE FROM shop.items WHERE NOT (price < 3 OR price IS NULL);
SHOW TABLES IN shop;
DROP TABLE IF EXISTS shop.items;
```
