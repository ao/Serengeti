# Query language

Serengeti 2.0 accepts one statement per query. The code is in
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
            | "CREATE" "INDEX" [ "IF" "NOT" "EXISTS" ] "ON" table "(" name ")" ;
drop        = "DROP" "DATABASE" [ "IF" "EXISTS" ] name
            | "DROP" "TABLE" [ "IF" "EXISTS" ] table
            | "DROP" "INDEX" [ "IF" "EXISTS" ] "ON" table "(" name ")" ;
show        = "SHOW" "DATABASES"
            | "SHOW" "TABLES" "IN" name
            | "SHOW" "INDEXES" "IN" table
            | "SHOW" "CONFLICTS" "IN" table ;

insert      = "INSERT" "INTO" table "(" name { "," name } ")"
              "VALUES" tuple { "," tuple } ;
tuple       = "(" value { "," value } ")" ;

select      = "SELECT" ( "*" | item { "," item } )
              "FROM" table [ "WHERE" expr ] [ "GROUP" "BY" name { "," name } ]
              [ "ORDER" "BY" item [ "ASC" | "DESC" ] ] [ "LIMIT" integer ] ;
item        = name | aggregate ;
aggregate   = "COUNT" "(" ( "*" | name ) ")"
            | ( "SUM" | "MIN" | "MAX" | "AVG" ) "(" name ")" ;
update      = "UPDATE" table "SET" name "=" value { "," name "=" value } [ "WHERE" expr ] ;
delete      = "DELETE" "FROM" table [ "WHERE" expr ] ;

expr        = and { "OR" and } ;
and         = not { "AND" not } ;
not         = "NOT" not | primary ;
primary     = "(" expr ")"
            | name ( cmp value | "IS" [ "NOT" ] "NULL"
                   | [ "NOT" ] "IN" "(" value { "," value } ")"
                   | [ "NOT" ] "LIKE" string ) ;
cmp         = "=" | "!=" | "<>" | "<" | "<=" | ">" | ">=" ;

table       = name "." name ;
name        = ( letter | "_" ) { letter | digit | "_" } ;   (at most 63 characters, not a keyword)
value       = string | number | "TRUE" | "FALSE" | "NULL" ;
string      = "'" { any character, with ' written as '' } "'" ;
number      = [ "-" ] digits [ "." digits ] [ ( "e" | "E" ) [ "+" | "-" ] digits ] ;
integer     = digits ;
```

The keywords are: AND, ASC, BY, COUNT, CREATE, DATABASE, DATABASES, DELETE, DESC, DROP, EXISTS,
FALSE, FROM, IF, IN, INSERT, INTO, IS, LIMIT, NOT, NULL, OR, ORDER, SELECT, SET, SHOW, TABLE, TABLES,
TRUE, UPDATE, VALUES, WHERE. They cannot be used as names. AVG, CONFLICTS, GROUP, INDEX, INDEXES,
LIKE, MAX, MIN, ON and SUM are not reserved: they have their meaning only where the grammar puts them,
so `SELECT sum, group FROM shop.items WHERE like = 1` reads three columns, and `sum(sum)` adds up a
column called `sum`.

A condition compares a column with a literal value, a list of them (IN) or a pattern (LIKE); the left
side is always a column. `NOT` binds tighter than `AND`, which binds tighter than `OR`.

Further parse rules:

- An INSERT lists each column once, and each tuple has one value per column.
- An UPDATE sets each column once.
- An IN list has 1 to 1,000 values.
- In a LIKE pattern, `\` comes only before `%`, `_` or `\`.
- Only COUNT takes `*`.
- Without GROUP BY, a select list with an aggregate has only aggregates, and cannot have ORDER BY or
  LIMIT: it returns one row.
- With GROUP BY, each column of the select list is a GROUP BY column, and ORDER BY names a GROUP BY
  column or an aggregate of the select list written the same way (`ORDER BY count(*) DESC`; keywords
  and names are not case-sensitive there either). A GROUP BY lists each column once. `SELECT *` cannot
  have GROUP BY.
- ORDER BY an aggregate needs GROUP BY.
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
  an error, and so is changing `_id` with UPDATE. A deleted row's `_id` can be used again.
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
fewest rows is used. The terms an index answers are:

- a comparison with a value (`=`, `<`, `<=`, `>`, `>=`);
- an IN list: the rows of each value;
- a LIKE whose pattern starts with a literal prefix (the characters before the first `%` or `_` that
  is not escaped): the strings that start with it. A pattern without wildcards is an `=` lookup.

The rest of the condition is checked on those rows, so an index changes how fast a query runs, never
its result. `!=`, OR, NOT, NOT IN, NOT LIKE, IS NULL and a LIKE that starts with a wildcard scan the
table.

An index holds the booleans, numbers and strings of its column, compared as above (`qty = 10` also
finds 10.0). Rows where the column is missing, null, an object or an array are not in it, which is right:
no comparison of those with a value is true. `_id` needs no index: `WHERE _id = 'x'`, ranges, IN lists
and LIKE prefixes of `_id` never scan.

An index is built from the rows when it is created and kept up to date by every write. In a cluster the
definition replicates like a table's, and each node builds its own. Dropping the table drops its indexes.
`SHOW INDEXES IN db.table` returns the column `column`.

## Ordering

Without ORDER BY, rows come back in `_id` order. ORDER BY sorts on one column, in this order
ascending: null (and missing), false, true, numbers, strings, then any other stored value. DESC
reverses it. Rows with equal values are ordered by `_id`, ascending in both directions.

## Aggregates and GROUP BY

```
SELECT COUNT(*), SUM(qty), AVG(price) FROM shop.items WHERE qty > 0;
SELECT kind, COUNT(*), MAX(price) FROM shop.items GROUP BY kind ORDER BY count(*) DESC LIMIT 10;
```

- `COUNT(*)` counts rows, and `COUNT(c)` the rows where c is not null (a missing c counts as null).
- `SUM(c)` and `AVG(c)` take the numbers of c and skip every other value: null, strings, booleans,
  objects and arrays. If the numbers are all integers, SUM is an integer, added exactly; if the total
  does not fit in 64 bits the statement fails with `SUM overflows a 64-bit integer` (400 over HTTP). Only
  the total counts, so a sum that passes the limit part way and comes back into range is fine (the
  largest integer, plus 1, minus 1, is the largest integer). Once a double is among them, SUM is a double:
  the integers are added exactly and the doubles in `_id` order, then the two are added. AVG is that
  total divided by how many numbers there are, always a double. A double total beyond the range of a
  double fails the statement (`SUM overflows a double`, `AVG overflows a double`).
- `MIN(c)` and `MAX(c)` are the least and greatest value of c that is not null, in the ORDER BY order
  above, so numbers come below strings, and an object or array above both. Of equal values (1 and 1.0)
  they give the one from the row with the lowest `_id`.
- Over no rows, or for SUM and AVG no numbers, COUNT is 0 and the others are null.

Without GROUP BY the result is one row, also when no row matches. With GROUP BY it is one row per
distinct combination of the GROUP BY values, and none when no row matches. Values are the same as for
`=`, so 1 and 1.0 are one group, shown as the value of the row with the lowest `_id`; null is a value of
its own, and a missing column counts as null. Objects and arrays are one group when they are equal,
whatever the order of their keys.

Groups come back ordered by the GROUP BY columns ascending, in the ORDER BY order, with objects and
arrays (equal there) ordered by their JSON text with keys sorted. ORDER BY sorts on one GROUP BY column
or one aggregate of the select list instead; groups with equal values keep their GROUP BY order, in
both directions. LIMIT counts groups. A statement fails if the SUM or AVG of any group cannot be given;
when several cannot, the first in GROUP BY order is named.

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
  other aggregate is written in lowercase with its column: `count(qty)`, `sum(qty)`, `min(qty)`,
  `max(qty)`, `avg(qty)`.

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
  argument), and with GROUP BY at most 10,000 groups. A LIMIT above the cap is an error. Without
  LIMIT, if more rows or groups match, the first 10,000 are returned and `truncated` is true. COUNT(*)
  is not capped.
- A GROUP BY holds at most 100,000 groups while it reads the rows (`Executor.DEFAULT_MAX_GROUPS`, also
  a constructor argument). A statement that would make more fails with `GROUP BY would make more than
  100,000 groups` as soon as it gets there, LIMIT or not.
- An IN list has at most 1,000 values.
- Parentheses and NOT may nest at most 64 levels deep in one condition (`Parser.MAX_DEPTH`). Long
  chains of AND or OR do not count towards this.
- Names are at most 63 characters.
- SELECT with ORDER BY sorts all matching rows in memory before applying the limit, except with
  `ORDER BY _id` ascending, which is the order rows are read in (see below). A statement whose WHERE
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

## Writes and time

Each write statement is checked and applied in one step under the storage engine's write lock, so
for example two concurrent inserts of the same `_id` cannot both succeed on one node. All ops of
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
UPDATE shop.items SET price = 4, note = 'It''s back' WHERE _id = 'tea';
DELETE FROM shop.items WHERE NOT (price < 3 OR price IS NULL);
SHOW TABLES IN shop;
DROP TABLE IF EXISTS shop.items;
```
