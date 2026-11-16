# Soak test

`tests/soak/run.sh` runs a cluster of the real image over TLS in Docker for a long time under a random mix
of client work and faults, then undoes every fault, waits for the nodes to converge and checks every node
against what the clients were told. It looks for what the fixed scenarios of `tests/cluster/run.sh` cannot
reach: faults that overlap, land in the middle of a restart or catch a node while it catches up.

```sh
tests/soak/run.sh                                  # 3 nodes, 10 minutes, a random seed
NODES=5 DURATION=60 SEED=42 tests/soak/run.sh      # the same seed gives the same faults and operations
IMAGE=ataiva/serengeti:2.0.0 tests/soak/run.sh     # an existing image instead of building this tree
```

It needs Docker and `python3` on the host. The header of `run.sh` lists the other settings. CI runs 5
minutes on 3 nodes on every push, with the run's id as the seed, and keeps the run's files as an artifact.

## The cluster

Each node is a container of the image with a 1.5 GiB memory limit, its own data volume and a fixed address
on a cluster network; the nodes talk to each other over TLS with certificates from a test CA. Each node is
also on a network of its own that only the client container joins, so a partition between nodes does not
cut the clients off. The clients and the checker run in one `python:3.12-alpine` container and use only
the Python standard library.

## The workload

Two clients per node (`CLIENTS` to change it), each sending to one node over a kept-alive HTTPS connection
and pausing 25 ms on average between operations:

- 42% inserts of rows with unique `_id`s into three insert-only tables in two databases, one row or two to
  four per statement, with an integer, a string, a column that holds integers, doubles, strings, booleans
  or null, and 0 to 2,000 characters of padding made from the row's id (so that each node's log passes
  the 64 MiB at which it is folded into the snapshot a few times an hour, and a damaged padding shows);
- 28% writes to 8 hot rows that every client hits: `UPDATE` (55%), `DELETE` (20%) and `INSERT` of a row
  that may exist (25%), each writing a value unique to the operation;
- 14% local reads and 16% majority reads: a hot row, a row this client inserted earlier, or a count
  through an index;
- now and then (1 in 2,500 operations) `CREATE INDEX IF NOT EXISTS` or `DROP INDEX IF EXISTS` on one of
  seven columns; three other indexes are made at the start and never dropped.

35% of the writes ask for `"ack": "local"`, the rest for a majority. Every operation is logged with its
start and end time, the node, the status and, for writes, the number of rows changed or the error.

Each client's operations come from its own random generator seeded from `SEED`, so the same seed sends
the same sequence; how many of them fit in the run depends on how fast the nodes answer.

## The faults

Every 1 to 5 seconds the schedule picks one of these, and undoes it after the time given:

| Fault | How | Undone after |
|---|---|---|
| kill | `docker kill -s KILL`, then `docker start` | 0.2 s (30%) or 1 to 20 s |
| stop | `docker stop -t 10` (SIGTERM), then `docker start`; the data directory is kept | 0.2 to 15 s |
| restart | `docker restart -t 10` | at once |
| pause | `docker pause`, then `docker unpause`: the node hangs, its connections stay open | 1 to 20 s |
| partition | a minority of nodes cut off from the rest, sometimes from each other too: iptables DROP rules in both directions, in each node's network namespace | 2 to 30 s |
| bridge | one pair of nodes (one to three pairs on 5 nodes) that cannot reach each other, while a majority can all still reach each other | 3 to 30 s |
| netem | `tc netem` on one node's cluster interface: 10 to 500 ms delay, up to 50% jitter, 0 to 30% loss | 5 to 40 s |

A fault is only started if a majority of nodes stays up and able to reach each other. In addition, once
in the first two minutes and then about every 5 minutes (at least a minute apart), the schedule waits
until no fault is active and takes the majority away for 5 to 25 seconds: a majority killed, a majority
paused, every node cut off into groups smaller than a majority, every node killed and restarted, or a
minority cut off and one node of the rest killed. Majority writes then get 503 and local writes go on.

The packet rules and netem are set from a helper container that joins the node's network namespace with
`NET_ADMIN`. A node that starts again gets a new namespace, so the rules for any partition in force are
set again when it starts. netem shapes only what the node sends on its cluster interface; with both ends
of a connection on one host, that delays and drops packets of both directions of every exchange that node
takes part in.

`SOAK_NODES=3 SOAK_SEED=7 SOAK_SECS=600 python3 tests/soak/soak.py plan` prints the schedule of a seed
without running anything.

## The checks

When the time is up the clients stop, every fault is undone, and the nodes get `CONVERGE_SECS` (300) to
be ready, caught up, reaching every node and holding the same per-table root hashes. Then the checker
reads every table from every node (`SELECT *` in pages of 10,000, ordered by `_id`), `SHOW CONFLICTS` and
`SHOW INDEXES`, and prints one line per check:

1. every write acknowledged by a majority is on every node with its value, or with a value from a newer
   write;
2. every acknowledged delete stays deleted unless a newer write recreated the row;
3. every write acknowledged locally is on every node, or superseded by a newer write;
4. every node returns the same rows for every table, the same `SHOW CONFLICTS` and the same `SHOW INDEXES`;
5. no node exited except those the nemesis killed; no client and no node log saw a 5xx other than 503;
   `/ready` is 200 on every node at the end;
6. for every index on every node, `COUNT(*)` with `=`, `<`, `<=`, `>` and `>=` against values of every
   type returns the same count through the index, as a scan (the same comparison written twice with `OR`,
   which is never answered through an index) and from the rows the node returned; for `=`, the same ids;
7. every row and every value held comes from a write that was sent and not refused (a 503 before the
   write was logged, a 400, or an `UPDATE` or `DELETE` that changed nothing), and an insert of several
   rows is there entirely or not at all;
8. every read that answered saw the writes it must see: a majority read every majority write
   acknowledged before it began, and any read the writes its own node acknowledged before it began;
9. `SHOW CONFLICTS` lists only versions written by clients, with the right node and a time within the
   write's request, the current version first and equal to the row; no version that a later write on
   the same node must have seen; and nothing in the insert-only tables;
10. every node that was running and not paused answered its clients: there is no stretch of more than
    30 s in which every request to it got no answer (no connection or a 12 s timeout), not counting the
    20 s after the nemesis starts, unpauses or restarts it.

During the run the nemesis also asks each running, unpaused node without netem for `GET /health` every
2 s. A node that has not answered for 15 s gets SIGQUIT, so that its log holds a JVM thread dump of the
stall, and the stall is listed under check 10. If the nodes have not converged 30 s after the faults are
undone, every node gets SIGQUIT for the same reason.

"Newer" is decided from the clients' clocks, which are the nodes' clocks: every container reads the same
kernel clock. A version's wall time lies between the start and the end of the request that wrote it, so a
write that ended before another began has the older version. Two writes whose requests overlap can come
out either way, and the checks accept both. A write that got no answer (a timeout, a node that died
mid-request) or a 503 saying "may still reach the others" may or may not be there; one that got 500 is
treated the same way.

Before the cluster starts, `soak.py selftest` runs the checker on a small made-up history and on 12
copies of it with one defect planted in each (a lost insert, a resurrected delete, a stale majority read,
a half-applied insert, a conflict between two writes one node made in turn, and so on), and stops the run
unless each copy fails exactly the checks it should.

The run's directory (`OUT`, printed at the start and the end) keeps `events.log` (every fault and its
undoing, and one line per minute with the operation counts by status and kind, and each node's estimated
data size, log size and conflict count), `plan.txt`, `check-details.txt` (every problem found), the
clients' operation logs and every node's log.

## What it cannot do

- Clock skew. Every container shares the host's kernel clock, so one node's clock cannot be set ahead of
  another's; changing the clock would change it for all of them. The 60-second limit on stamps from the
  future is tested by unit tests only. The checker also relies on the shared clock to order writes.
- Disk faults: fsync errors, a full disk, torn or lost writes. `tests/cluster/run.sh` slows fsync, and
  unit tests cover the log's checksums.
- Losing a data directory, or restoring one from a backup.
- Separate machines. Every node runs on one host, on Docker bridge networks; partitions are packets
  dropped by iptables, which looks like a cable pulled (no resets), not like a router answering.
- Ordering within a millisecond: a write that lost to one that ended in the same millisecond before it
  began would not be noticed.
- Reads other than of one row by `_id`: index counts and scans during the run are not checked against
  anything; check 6 compares them once the data is still.

## Findings

### 1. A node that is stopping answers 500 for up to a second (fixed in e840144)

On SIGTERM (`docker stop`, `docker restart`), `Node.close()` closes the cluster layer first, which shuts
its I/O executor down, and then calls `HttpServer.stop(1)`. While a request is in flight, `stop(1)` waits
up to a second, and the server goes on taking new requests on connections that are already open. Those
that need the closed cluster layer fail with a `RejectedExecutionException` from the I/O executor and are
answered `500 {"error":"internal error"}`:

- local-ack and majority writes, which are already in the node's log when the error is raised: the 65
  inserts answered 500 in the three 10-minute runs (33 local, 32 majority) are all on every node
  afterwards;
- majority reads.

Local reads are answered normally, and majority writes refused up front (no majority reachable) get their
503. The JDK's logging is usually shut down by its own shutdown hook first, so the node's log mostly
shows neither the requests nor the exceptions. It happened in every run on 21a6afe (see Runs); in the
first three, at 1 or 2 of the 6 to 11 graceful stops and restarts of each run. Kills never caused it.

A client then sees an internal error for a write that was made. A load balancer that retries 503 elsewhere
will not retry these. The expected answer is 503 (or 200 for a local write that is in the log).

To reproduce: `ClusterTest.aStoppingNodeDoesNotAnswerInternalError` closes one node's cluster layer, as
`Node.close()` did first, and sends a local write and a majority read: on 21a6afe the write gets 500 every
time. `SCENARIO=graceful-stop tests/soak/run.sh` stops one node of a healthy cluster while 12 clients
write to it and read with `"consistency": "majority"`; on 21a6afe it caught one 500 in 2 of 3 runs.

engine/2.2 e840144 stops the HTTP server first, answers what still arrives with 503 "this node is
stopping" and `Connection: close`, and has a closed cluster layer refuse writes and majority reads with
503. Against an image of e840144 the scenario passed 3 of 3 times (the stopping node answered 9 requests
with that 503 and none with 500), and three 10-minute runs had no 5xx but 503.

### 2. A running node sometimes answers nothing for 20 to 80 seconds (fixed in 5c2426f and 8970539)

Check 10 finds stretches in which a node that the nemesis had running and not paused answered none of
its clients' requests: they time out after 12 s, and new connections do not get through the TLS
handshake. Its own syncs with its peers go on meanwhile; then it answers again. On 21a6afe it happened 4
times in the hour with seed 5005 (30 to 49 s) and 3 times in the 5-node hour with seed 6006 (31 to 48
s), and not in the hour with seed 4004. On e840144 it happened in two of four runs: with seed 1001, n3
answered nothing from 50 s before the end of the run until 81 s after every fault was undone, and wrote
nothing to its log for those 83 s, so convergence took 81 s instead of 0 to 21 s (the same seed did not
stall a second time); with seed 1111, n1 did not answer `/health` for 21 s, and the nemesis took a thread
dump during it.

The dump (e840144, n1, 30-minute run with seed 1111, 28 minutes in) shows a lock convoy in the JDK's
HTTP server:

- one request thread is writing the response to an `/internal` request (`HttpApi.internal` ->
  `sendJson`) to a peer that is not reading (n3 was stopped, started and paused in those seconds); it is
  blocked in the socket write and holds that connection's TLS write lock (`SSLStreams$EngineWrapper`);
- the JDK server's `req-rsp-timeout-task`, which runs because Serengeti sets
  `sun.net.httpserver.maxReqTime` and `maxRspTime` (it has since 2.0.0), takes the server's synchronized
  connection set and, inside it, closes a connection that is over its time limit; the close sends a TLS
  close_notify and waits for that same TLS write lock;
- 62 of the other request threads wait for the connection set in `ServerImpl.requestCompleted`, so no
  request finishes and no new one starts until the stuck write gives way.

21a6afe sets the same properties, so the same convoy can happen there; its stalls have no thread dump, but
they look the same from outside and follow the same kinds of faults.

Related, and not confirmed: in the run with seed 1111, 2 of 23 `docker stop`s of n3 waited the whole 10 s
and ended with SIGKILL (exit code 137); the other stops took under 2 s. `HttpServer.stop()` closes every
connection, and would wait on a stuck write in the same way.

The fix: 5c2426f no longer sets `maxRspTime`, so the task never closes a connection whose response is
being written; `maxReqTime` (30 s to send a request) stays, for slow senders. 8970539 waits at most 3 s
for `HttpServer.stop()`, then closes the cluster layer and the storage and exits, so a stop no longer
waits for a stuck write either; `TlsTest` has a client that asks for a large backup and stops reading,
and the old close hung there. On be63206 (the first fix), two 30-minute runs on 3 and 5 nodes had no
stall; on 8970539 every one of 28 stops and restarts exited on SIGTERM within 3.5 s (on be63206, 4 of 54
stops were killed after docker's 10 s).

### 3. Catching up after a restart can run a node out of heap (fixed in 2926825)

In the hour on 5 nodes with seed 20261116 (engine 36230d2, 2 GiB per node, about 210 MiB of data by the
node's estimate), n5 exited with `java.lang.OutOfMemoryError: Java heap space` 33 s after one of its
restarts, while it caught up with the others through a slow link (netem, 100 ms and 5% loss). Every other
check passed.

A node that has missed writes compares bucket sums with each peer and pulls the rows of every bucket that
differs. A few thousand missed inserts touch most of a table's 1,024 buckets, so it asked each peer for
nearly every row it held, in pages of 200 buckets, and sent every page's request at once: with four peers
and three tables, dozens of pages of about 12 MB of JSON each, each held as text and again as parsed rows,
on the node pulling and on the peers building the answers. `tests/memory/catchup.sh` (three nodes of 768 MiB,
120,000 rows of 1 KB, one node stopped while 6,000 more are written, then started) ran the two other nodes
out of heap within 3 s of the restart, every time.

The fix (2926825): a node runs at most two row pulls at once, over every peer and table; a table's pages
are pulled one after another, the first with 16 buckets and each next one sized from the last to about
2,000 rows or 4 MiB; and a node builds at most two row pages for its peers at once, answering 503 to more
(the peer asks again at its next sync, a second later). While a sync with a peer takes longer than a
second, a hello keeps the peer counted as reachable. With it, the repro's nodes all stayed up and the
restarted one caught up in about 10 s.

The same hour on 2926825 had no unexpected exit, but its clients spent 13,900 s waiting on 503s against
8,700 s on 36230d2, most of them majority reads that waited the full 5 s: a majority read syncs with every
peer, and its pulls queued behind the background catch-up's two. A second change gives reads' pulls four
of their own, allows four row pages to be built for peers at once (waiting 1 s, not 2, before a 503), and
makes a background sync whose pages stopped for a newer one count as failed: it had counted as complete,
so a node could take itself for synced with a peer while rows were still missing.

The pages kept the heap bounded, but a node catching up still pulled nearly every row of each table from
each peer, and each peer nearly every row from it. Now the differing buckets are compared row by row
first: the node asks for each row's id and a hash of its versions (about 40 bytes a row) and pulls only the
rows whose hashes differ or that only the peer has (docs/CONSISTENCY.md, "Sync"). In `catchup.sh`, three
runs each on an EC2 c7g.4xlarge shared with other test jobs, the restarted node received 275 MB and sent
244 to 313 MB before; after, it received 21 MB and sent 1 to 7 MB, and had every row 2.6 s after its
start instead of 4.6 to 5.2 s.

## Runs

All on one EC2 c7g.2xlarge (8 Graviton3 vCPUs, 16 GiB, Ubuntu 24.04, Docker 29), nothing else running.
"Faults" counts the faults started, with the periods without a majority in brackets. "500s" are the
answers of finding 1; "stalls" are those of finding 2, found by check 10 (added after the 21a6afe runs
and run on their files afterwards; the runs with seeds 1001 to 3003 predate the record of the nemesis's
actions that it needs).

Engine 21a6afe (engine/2.2 before the fix), harness cb2b890; from seed 7007 on with the padding (d33a526):

| Seed | Nodes | Minutes | Operations | Acknowledged writes, majority / local | Faults | Checks 1-4, 6-9 | 500s | Stalls |
|---|---:|---:|---:|---|---|---|---:|---|
| 1001 | 3 | 10 | 41,045 | 14,228 / 8,192 | 75 (1) | pass | 18 | - |
| 2002 | 3 | 10 | 32,170 | 10,585 / 6,176 | 79 (2) | pass | 50 | - |
| 3003 | 3 | 10 | 39,345 | 13,244 / 7,769 | 68 (3) | pass | 58 | - |
| 7007 | 3 | 10 | 39,832 | 13,477 / 7,822 | 76 (3) | pass | 102 | none |
| 4004 | 3 | 60 | 229,362 | 75,900 / 44,026 | 447 (11) | pass | 155 | none |
| 5005 | 3 | 60 | 181,431 | 55,538 / 33,476 | 465 (10) | pass | 159 | 4 (30 to 49 s) |
| 6006 | 5 | 60 | 253,727 | 74,418 / 45,871 | 641 (13) | pass | 190 | 3 (31 to 48 s) |

The run with seed 4004 had no padding, and no node's log reached the 64 MiB checkpoint: all 149 node
starts replayed the log alone. With the padding, seed 5005 folded each node's log once and 56 of its 142
node starts loaded a snapshot first; seed 6006, 168 of 265. A further hour on 3 nodes (seed 8008) was cut
off at minute 27 when the machine's automatic OS updates restarted the test's own process, and is not
counted.

Engine e840144 (the fix for finding 1), harness a04f4ff; the scenarios and the first run with seed 1001 ran
before check 10 existed, and it was run on their files afterwards:

| Seed | Nodes | Minutes | Operations | Acknowledged writes, majority / local | Faults | Checks 1-9 | Check 10 |
|---|---:|---:|---:|---|---|---|---|
| graceful-stop 1, 2, 3 | 3 | 0.3 each | 8,920; 8,678; 8,925 | 4,033 / 2,041; 3,987 / 2,032; 3,951 / 2,116 | 1 stop each | pass | pass |
| 1001 | 3 | 10 | 38,375 | 12,678 / 7,574 | 75 (1) | pass | 1 stall (n3, from 50 s before the end until convergence, 81 s later) |
| 1001 | 3 | 10 | 40,961 | 14,025 / 8,072 | 75 (1) | pass | pass |
| 9009 | 3 | 10 | 42,661 | 14,758 / 8,377 | 75 (2) | pass | pass |
| 1111 | 3 | 30 | 106,049 | 34,173 / 19,901 | 234 (6) | pass | 1 stall (n1, 21 s, with a thread dump) |

Engine be63206 (the first fix for finding 2):

| Seed | Nodes | Minutes | Operations | Acknowledged writes, majority / local | Faults | Checks 1-9 | Check 10 |
|---|---:|---:|---:|---|---|---|---|
| 2626 | 3 | 30 | 116,288 | 37,508 / 21,824 | 229 (6) | pass | pass |
| 3737 | 5 (2 GiB each) | 30 | 153,656 | 49,989 / 28,861 | 320 (6) | pass | pass |
| graceful-stop 4 | 3 | 0.3 | 8,878 | 3,866 / 2,103 | 1 stop | pass | pass |

Of the stops in the two 30-minute runs, 4 of 54 ended in docker's SIGKILL after 10 s: the stop waited for
a stuck write, as described under finding 2.

Engine 8970539 (the stop bounded at 3 s), each stop and restart timed:

| Seed | Nodes | Minutes | Operations | Acknowledged writes, majority / local | Faults | Checks 1-9 | Check 10 |
|---|---:|---:|---:|---|---|---|---|
| graceful-stop 5, 6, 7 | 3 | 0.3 each | 8,816; 8,848; 9,000 | 3,971 / 2,055; 3,925 / 2,035; 4,062 / 2,106 | 1 stop each | pass | pass |
| 4848 | 3 | 30 | 107,195 | 34,459 / 20,016 | 225 (7) | pass | pass |

All 28 stops and restarts exited on SIGTERM (code 143), in 0.2 to 3.5 s; the three at 3.5 s are the 3 s
wait running out.

The hour on 5 nodes (2 GiB each) with seed 20261116, on each step of finding 3. "Waiting on 503" is the
clients' time from sending a request to getting a 503, summed over all ten clients:

| Engine | Operations | Acknowledged writes, majority / local | Faults | Waiting on 503 | Checks |
|---|---:|---|---|---:|---|
| 36230d2 (before) | 254,353 | 71,840 / 44,081 | 675 (9) | 8,731 s | 9 of 10: n5 out of heap at 55 min |
| 2926825 (pull limit) | 180,425 | 49,008 / 31,033 | 675 (9) | 15,577 s | 10 of 10 |
| 75aa8dd (reads' own pulls) | 224,261 | 61,727 / 38,563 | 675 (9) | 11,025 s | 10 of 10 |
| 33622c3 (a read's pulls stop with it) | 248,021 | 70,077 / 42,944 | 675 (9) | 10,142 s | 10 of 10 |
| bde4274 (only the rows that differ) | 269,585 | 77,757 / 46,959 | 675 (9) | 7,599 s | 10 of 10 |

The same seed starts the same faults at the same times; how many operations fit between them depends on
how fast the nodes answer.
