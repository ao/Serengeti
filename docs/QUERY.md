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
            | "CREATE" "TABLE" [ "IF" "NOT" "EXISTS" ] table ;
drop        = "DROP" "DATABASE" [ "IF" "EXISTS" ] name
            | "DROP" "TABLE" [ "IF" "EXISTS" ] table ;
show        = "SHOW" "DATABASES"
            | "SHOW" "TABLES" "IN" name ;

insert      = "INSERT" "INTO" table "(" name { "," name } ")"
              "VALUES" tuple { "," tuple } ;
tuple       = "(" value { "," value } ")" ;

select      = "SELECT" ( "*" | "COUNT" "(" "*" ")" | name { "," name } )
              "FROM" table [ "WHERE" expr ]
              [ "ORDER" "BY" name [ "ASC" | "DESC" ] ] [ "LIMIT" integer ] ;
update      = "UPDATE" table "SET" name "=" value { "," name "=" value } [ "WHERE" expr ] ;
delete      = "DELETE" "FROM" table [ "WHERE" expr ] ;

expr        = and { "OR" and } ;
and         = not { "AND" not } ;
not         = "NOT" not | primary ;
primary     = "(" expr ")"
            | name ( cmp value | "IS" [ "NOT" ] "NULL" ) ;
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
TRUE, UPDATE, VALUES, WHERE. They cannot be used as names.

A condition compares a column with a literal value; the left side is always a column. `NOT`
binds tighter than `AND`, which binds tighter than `OR`.

Further parse rules:

- An INSERT lists each column once, and each tuple has one value per column.
- An UPDATE sets each column once.
- `COUNT(*)` cannot be combined with ORDER BY or LIMIT.
- The LIMIT is a non-negative whole number.

## Errors

Text that does not follow the grammar raises `ParseException`. The message ends with
`at position N`, where N is the 0-based character offset where the problem was found, for example
`expected FROM but found name 'form' at position 9`. `ParseException.position()` returns the same
number.

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

## Ordering

Without ORDER BY, rows come back in `_id` order. ORDER BY sorts on one column, in this order
ascending: null (and missing), false, true, numbers, strings, then any other stored value. DESC
reverses it. Rows with equal values are ordered by `_id`, ascending in both directions.

## Results

`Result.toJson()` returns a map that `core.json.Json.write` can serialise.

SELECT and SHOW return rows. Each row is an array with one value per column:

```
{"type":"rows","columns":["_id","name"],"rows":[["a","Tea"],["b","Coffee"]],"count":2,"truncated":false}
```

- `SELECT *` lists `_id` first, then every other column found in the returned rows, in the order
  first seen.
- `SELECT COUNT(*)` returns one column `count` and one row.
- `SHOW DATABASES` returns the column `database`; `SHOW TABLES IN db` returns `table`.

Writes return the number of databases, tables or rows changed. INSERT also lists the `_id` of each
row, in insert order:

```
{"type":"write","affected":2,"ids":["a","5f0c6f0e-..."]}
```

`CREATE ... IF NOT EXISTS` on an existing object and `DROP ... IF EXISTS` on a missing one return
`"affected":0`.

## Limits

- A SELECT returns at most 10,000 rows (`Executor.DEFAULT_MAX_ROWS`; the cap is a constructor
  argument). A LIMIT above the cap is an error. Without LIMIT, if more rows match, the first 10,000
  are returned and `truncated` is true. COUNT(*) is not capped.
- Parentheses and NOT may nest at most 64 levels deep in one condition (`Parser.MAX_DEPTH`). Long
  chains of AND or OR do not count towards this.
- Names are at most 63 characters.
- SELECT with ORDER BY sorts all matching rows in memory before applying the limit. There are no
  indexes; every statement with WHERE scans the table.

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
UPDATE shop.items SET price = 4, note = 'It''s back' WHERE _id = 'tea';
DELETE FROM shop.items WHERE NOT (price < 3 OR price IS NULL);
SHOW TABLES IN shop;
DROP TABLE IF EXISTS shop.items;
```
