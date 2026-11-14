# Benchmarks

Serengeti 2.0.0 and the 2.1 development branch, measured on one dedicated EC2 c7g.2xlarge (8 Graviton3
vCPUs, 16 GiB, a gp3 EBS volume at 3000 IOPS and 125 MB/s). The nodes and the load generator run in Docker
on that machine, on a private Docker network, and nothing else ran on it. The scripts are in `tests/bench/`;
the tables below are the output of `tests/bench/report.sh` for the runs they name, unedited. Every 2.1 table
comes from engine commit 17ad3ab.

## TCP_NODELAY

Serengeti serves HTTP with the JDK's `com.sun.net.httpserver`, which leaves Nagle's algorithm on unless the
system property `sun.net.httpserver.nodelay` is `true`. In 2.0.0 a response on a kept-alive connection then
waits for the client's delayed ACK, about 40 ms on Linux, so one connection does about 24 requests per
second whatever the request; see [As shipped](#as-shipped). 2.1 sets the property itself unless the
operator does (engine commit 51d3e23). The main tables give both versions the property
(`-Dsun.net.httpserver.nodelay=true` through `JDK_JAVA_OPTIONS` on every node), so that they compare the
storage and replication code; the as-shipped tables run each image with its defaults.

## What these numbers mean

Each figure in scenarios a to d is the median of 3 runs of 15 s, and each in e the median of 3 runs on new
clusters, with the lowest and highest run in brackets. Most ranges are within 3% of the median; b varies by
up to 9%, d at concurrency 32 by up to 5%, and the traffic and p99 latency under writes in e by up to 30%.
Separate runs of the same code vary more than the runs within one: the 2.1 scans of 100k rows were 6 to 10%
faster in the as-shipped run than in the main one.

With `fsync=always` a write is acknowledged once its log record is forced to disk, and one 4 KiB O_DSYNC
write on this volume takes about 2.8 ms. 2.0.0 forces the log once per write, so a node takes about 350
writes/s at any concurrency. 2.1 forces it once for all the writes waiting at that moment (4.0 writes per
fsync at concurrency 8, 7.8 at 32) and takes 1431 and 2661 writes/s on the same disk; at concurrency 1 the
two are the same, and TLS changes none of this. With `fsync=interval` or `off` the disk is not the limit:
both take 13 to 14.5 thousand inserts/s at concurrency 8 and 32, 2.1 3 to 6% fewer.

Table scans run at about the same speed in both, from 2.1 3% faster at 10k rows to 7 to 8% slower at 100k.
A lookup by `_id` scans the table in 2.0.0 (612 requests/s at 100k rows) and not in 2.1 (about 16 thousand
at every size), and the same holds for reads through an index on n, which 2.0.0 does not have. 16 thousand
is also the rate of the cheapest query here (COUNT(*) on 1000 rows), so at concurrency 8 these lookups run
as fast as the server answers any request.

In the 3-node cluster, majority writes run at the same rate in both at concurrency 1 (about 158/s, p50
6.2 ms) and 2.8 and 4.9 times as fast in 2.1 at concurrency 8 and 32. `"ack": "local"` in 2.1 takes 302
writes/s at concurrency 1, and 63% and 54% more than majority writes at 8 and 32. After every run, in both
versions and both modes, all three nodes held every row at the first count after the run.

In e, with the table preloaded and 20 writes/s through n1, the 2.0.0 nodes answered 1.8 (10k rows) and 1.1
(100k rows) whole-database requests per second and sent each other 3.8 and 21.6 MB/s. 2.1 answered none
and sent 66 and 64 KB/s. With no client requests, 2.0.0's three nodes used 0.36 cores at 10k rows and 2.1
cores at 100k rows, against 0.03 to 0.04 for 2.1. At 100k rows under the writes, 2.0.0's write latency was
211 ms at p50 and 1.1 s at p99 (2.1: 7.2 and 15.6 ms), and its nodes ended with 811 to 951 MiB in use under
a 1 GiB cap (2.1: 443 to 559 MiB).

## Results

<!-- bench:begin -->
### Machine and method

- Machine: EC2 c7g.2xlarge: Neoverse-V1, 8 cores, 15.3 GiB memory, Ubuntu 24.04.5 LTS, Linux 7.0.0-1014-aws (aarch64). Docker: Ubuntu 24.04.5 LTS; Docker 29.9.3; kernel 7.0.0-1014-aws; 8 CPUs; 16425984000 bytes; aarch64. Containers see 8 CPUs; no model name in /proc/cpuinfo (CPU implementer 0x41).
- Disk: gp3 EBS, 120 GiB, 3000 IOPS, 125 MB/s (as provisioned); device Amazon Elastic Block Store, 120 GiB. Data volumes are on ext4 under /var/lib/docker. The mean time of one 4 KiB O_DSYNC write there (`dd oflag=dsync`, one write at a time, in a data volume of the image, at the start of each run), the least one `fsync=always` write can take: 2.0.0: 2.852 ms (500 writes); 2.1-dev: 2.806 ms (500 writes).
- 2.0.0: image `serengeti:2.0.0` (linux/arm64, id sha256:7b257a5eba32), version 2.0.0, built from commit abf086d82ec21fa6bfefa6c53da8748fc6fd3b9b. Run from 2026-11-14T11:44Z to 2026-11-14T12:22Z (UTC) with the harness at commit f2c57195e931.
- 2.1-dev: image `serengeti:2.1.0-dev-17ad3ab` (linux/arm64, id sha256:0970c00675a9), version 2.1.0-dev, built from commit 17ad3ab6dfa2dc41b09fb84522c49316c5f71031. Run from 2026-11-14T13:04Z to 2026-11-14T13:52Z (UTC) with the harness at commit 7d8256924088.
- Other load: 2.0.0: 0 other containers at the start; the host's CPUs 0% busy before the first scenario (after waiting 0 s) and at most 0% before any scenario, with no benchmark node running; load averages 0.38 1.07 3.22 at the start, 2.59 2.57 3.62 at the end; 2.1-dev: 0 other containers at the start; the host's CPUs 0% busy before the first scenario (after waiting 0 s) and at most 0% before any scenario, with no benchmark node running; load averages 1.65 4.69 3.93 at the start, 0.25 1.14 3.34 at the end.
- Memory caps: 1g per Serengeti node (the JVM's maximum heap is then 768 MiB), 384m for the load generator. No CPU limits. Java: openjdk version "21.0.13" 2026-10-20 LTS;OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS).
- Load generator: oha 1.16.0 from `ghcr.io/hatoo/oha:1.16.0`, in a container on the same private Docker network as the nodes. HTTP/1.1 with keep-alive, Basic auth, one connection per concurrent request.
- Runs: each figure in scenarios a to d is from 3 run(s) of 15 s, each after a 5 s warm-up that is not counted, each on a new table. Requests/s is the median of the runs, with the lowest and highest in brackets; the latencies and fsync figures are those of the run with the median rate. Latency is the time from sending a request to the end of its response, as the load generator saw it. Errors (non-200 answers and failed requests) are listed under a table when there were any.
- Scenario e: 3 run(s) for each table size, each on a new cluster; each figure is the median of the runs, with the lowest and highest in brackets.
- Rows: `{"_id": <uuid>, "n": <integer>, "s": <28-character string>}`. After each insert run the table's row count is checked against the number of acknowledged inserts, and in a cluster every node must reach that count; then the table is dropped, so every run starts with the same rows in memory.
- Settings: defaults except `SERENGETI_FSYNC`, the cluster settings and TLS. The server keeps a verified password for 60 s, so a run may include one bcrypt check; the server answers on 16 threads, so concurrency 32 queues.
- Extra arguments for every node (not the image default): `-e JDK_JAVA_OPTIONS=-Dsun.net.httpserver.nodelay=true`.
- Reproduce: `tests/bench/run.sh` (`QUICK=1` for a short run; `IMAGE=name:tag` for another image), then `tests/bench/report.sh NAME=OUT ...` to put runs side by side.

### a. Single node, plain HTTP, fsync=always: INSERT

| Concurrency | Requests/s, 2.0.0 | Requests/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev | Writes per fsync (mean fsync), 2.0.0 | Writes per fsync (mean fsync), 2.1-dev |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 343 (341-345) | 344 (339-345) | 2.89 | 2.89 | 3.66 | 3.08 | 1.0 (2.69 ms) | 1.0 (2.69 ms) |
| 8 | 363 (363-364) | 1431 (1425-1431) | 22.0 | 5.54 | 23.7 | 7.33 | 1.0 (2.70 ms) | 4.0 (2.74 ms) |
| 32 | 351 (351-352) | 2661 (2658-2663) | 88.0 | 11.4 | 101 | 15.4 | 1.0 (2.72 ms) | 7.8 (2.83 ms) |

### a2. Single node, TLS, fsync=always: INSERT

| Concurrency | Requests/s, 2.0.0 | Requests/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev | Writes per fsync (mean fsync), 2.0.0 | Writes per fsync (mean fsync), 2.1-dev |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 339 (334-340) | 339 (335-342) | 2.92 | 2.92 | 3.25 | 3.14 | 1.0 (2.70 ms) | 1.0 (2.70 ms) |
| 8 | 362 (362-363) | 1427 (1427-1428) | 22.0 | 5.55 | 23.5 | 7.75 | 1.0 (2.70 ms) | 4.0 (2.75 ms) |
| 32 | 350 (349-351) | 2649 (2644-2649) | 88.0 | 11.4 | 113 | 15.2 | 1.0 (2.73 ms) | 7.8 (2.83 ms) |

### b. Single node, plain HTTP: INSERT with other fsync settings

| Config | Concurrency | Requests/s, 2.0.0 | Requests/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev | Writes per fsync (mean fsync), 2.0.0 | Writes per fsync (mean fsync), 2.1-dev |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| fsync=interval | 1 | 5999 (5926-6080) | 5797 (5691-5832) | 0.15 | 0.16 | 0.20 | 0.21 | 5999.9 (6.47 ms) | 5797.5 (6.47 ms) |
| fsync=interval | 8 | 14535 (13560-14600) | 13660 (12899-13871) | 0.47 | 0.50 | 0.82 | 0.83 | 14536.9 (7.67 ms) | 13662.1 (9.07 ms) |
| fsync=interval | 32 | 13696 (13172-13848) | 13102 (12699-14066) | 1.90 | 2.00 | 17.2 | 17.2 | 13699.9 (6.73 ms) | 13106.1 (6.80 ms) |
| fsync=off | 1 | 5977 (5918-6064) | 5736 (5710-5745) | 0.16 | 0.16 | 0.20 | 0.21 | no fsync | no fsync |
| fsync=off | 8 | 13941 (13498-14475) | 13487 (12956-13797) | 0.48 | 0.51 | 0.87 | 0.83 | no fsync | no fsync |
| fsync=off | 32 | 13478 (13376-13937) | 13076 (12548-14187) | 1.91 | 2.00 | 13.9 | 17.2 | no fsync | no fsync |

### c. Single node reads, concurrency 8

No indexes. `SELECT * FROM t WHERE n = x` returns one row; x is a random value, the same sequence in every run. `WHERE _id = x` looks up a random id of the table, also the same sequence in every run.

| Query | Rows in table | Requests/s, 2.0.0 | Requests/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev |
|---|---:|---:|---:|---:|---:|---:|---:|
| SELECT * WHERE n = x | 1000 | 15057 (15045-15077) | 15185 (15028-15319) | 0.50 | 0.50 | 1.10 | 1.08 |
| SELECT COUNT(*) | 1000 | 16381 (16370-16402) | 16212 (16208-16232) | 0.47 | 0.48 | 0.98 | 0.99 |
| SELECT * WHERE _id = x | 1000 | 15231 (15223-15299) | 16288 (16252-16323) | 0.49 | 0.46 | 1.04 | 0.97 |
| SELECT * WHERE n = x | 10000 | 4465 (4446-4516) | 4577 (4576-4596) | 1.58 | 1.54 | 3.63 | 3.57 |
| SELECT COUNT(*) | 10000 | 10837 (10813-10875) | 11181 (11176-11216) | 0.68 | 0.66 | 1.57 | 1.53 |
| SELECT * WHERE _id = x | 10000 | 6772 (6766-6782) | 16246 (16243-16299) | 1.02 | 0.47 | 2.35 | 0.97 |
| SELECT * WHERE n = x | 100000 | 246 (241-251) | 229 (229-230) | 32.4 | 34.8 | 41.2 | 46.4 |
| SELECT COUNT(*) | 100000 | 1399 (1394-1432) | 1285 (1259-1301) | 5.36 | 5.89 | 9.95 | 10.8 |
| SELECT * WHERE _id = x | 100000 | 612 (605-620) | 16216 (16165-16227) | 12.3 | 0.47 | 20.5 | 0.98 |

### c-index. Single node reads through an index on n, concurrency 8

The same node and tables as c, after `CREATE INDEX ON t (n)` on each. Both queries compare n with a random value, the same sequence in every run. Time each CREATE INDEX took: 2.0.0: not run; 2.1-dev: 1000 rows 0.11 s, 10000 rows 0.10 s, 100000 rows 0.55 s.

| Query | Rows in table | Requests/s, 2.0.0 | Requests/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev |
|---|---:|---:|---:|---:|---:|---:|---:|
| SELECT * WHERE n = x | 1000 | not run | 16801 (16484-16819) | not run | 0.46 | not run | 0.90 |
| SELECT COUNT(*) WHERE n = x | 1000 | not run | 16960 (16562-16981) | not run | 0.46 | not run | 0.87 |
| SELECT * WHERE n = x | 10000 | not run | 16783 (16370-16838) | not run | 0.46 | not run | 0.89 |
| SELECT COUNT(*) WHERE n = x | 10000 | not run | 16914 (16533-16920) | not run | 0.46 | not run | 0.89 |
| SELECT * WHERE n = x | 100000 | not run | 16505 (16277-16661) | not run | 0.47 | not run | 0.94 |
| SELECT COUNT(*) WHERE n = x | 100000 | not run | 16695 (16168-16731) | not run | 0.47 | not run | 0.92 |

2.0.0: not run: the image does not accept CREATE INDEX: HTTP 400, {"error":"expected DATABASE or TABLE but found name 'index' at position 7"}.

### d. Three-node TLS cluster, fsync=always, majority writes: INSERT through n1

The fsync figures are n1's. The last column is the time from the end of a run until all three nodes held its rows, with the counts read one node after the other about four times a second.

| Concurrency | Requests/s, 2.0.0 | Requests/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev | Writes per fsync (mean fsync), 2.0.0 | Writes per fsync (mean fsync), 2.1-dev | All nodes equal after, s, 2.0.0 | All nodes equal after, s, 2.1-dev |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 157 (150-158) | 159 (156-160) | 6.17 | 6.22 | 10.7 | 7.99 | 1.0 (2.75 ms) | 1.0 (2.75 ms) | 0 (0-0) | 0 (0-0) |
| 8 | 264 (262-265) | 733 (732-741) | 30.0 | 11.2 | 63.0 | 15.4 | 1.0 (3.56 ms) | 2.3 (2.89 ms) | 0 (0-0) | 0 (0-0) |
| 32 | 263 (259-269) | 1298 (1234-1303) | 120 | 25.1 | 170 | 32.4 | 1.0 (3.57 ms) | 4.2 (2.93 ms) | 0 (0-0) | 0 (0-0) |

### d-local. Three-node TLS cluster, fsync=always, `"ack": "local"` writes: INSERT through n1

Each write is acknowledged once it is in n1's log; n2 and n3 get it through sync. Otherwise as d.

| Concurrency | Requests/s, 2.0.0 | Requests/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev | Writes per fsync (mean fsync), 2.0.0 | Writes per fsync (mean fsync), 2.1-dev | All nodes equal after, s, 2.0.0 | All nodes equal after, s, 2.1-dev |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | not run | 302 (300-304) | not run | 3.10 | not run | 5.12 | not run | 1.0 (2.91 ms) | not run | 0 (0-0) |
| 8 | not run | 1192 (1180-1197) | not run | 6.59 | not run | 12.0 | not run | 4.0 (3.27 ms) | not run | 0 (0-0) |
| 32 | not run | 2000 (1936-2035) | not run | 15.6 | not run | 23.9 | not run | 7.7 (3.77 ms) | not run | 0 (0-0) |

2.0.0: not run: the image ignores the "ack" field: a query with "ack": "probe" was answered 200, so "ack": "local" would measure majority writes.

### e. Sync cost, three-node TLS cluster, table preloaded, 20 writes/s for 60 s through n1

Each run is a new cluster: the table is preloaded through n1 (1000 rows per INSERT), all three nodes must hold every row, then 15 s pass. The idle window (30 s) has no client requests; the write window is 20 INSERTs per second, majority-acknowledged, with up to 32 in flight. Rates are over the window, from each container's cgroup CPU counter, its network counters and the node's `/metrics`. For n1 the load generator's own traffic is subtracted, so the traffic columns are node-to-node only.

CPU and node-to-node traffic, sum of the three nodes:

| Rows | Window | CPU, cores, 2.0.0 | CPU, cores, 2.1-dev | Peer traffic, KB/s, 2.0.0 | Peer traffic, KB/s, 2.1-dev |
|---:|---|---:|---:|---:|---:|
| 10000 | idle | 0.36 (0.35-0.36) | 0.04 (0.04-0.04) | 5.30 (5.29-5.32) | 5.69 (5.65-5.70) |
| 10000 | writes | 0.79 (0.78-0.81) | 0.26 (0.26-0.26) | 3774 (3762-4624) | 66.4 (61.3-71.3) |
| 100000 | idle | 2.10 (2.07-2.11) | 0.03 (0.03-0.03) | 3.24 (3.21-3.25) | 5.65 (5.54-5.67) |
| 100000 | writes | 2.56 (2.47-2.56) | 0.22 (0.22-0.23) | 21630 (20375-21716) | 63.9 (59.1-80.2) |

Sync requests answered per second, sum of the three nodes. A whole-database answer is one `/internal/state` response; other sync requests are `/internal/digests` and, in 2.1, `/internal/v2/*`. Write replication (`/internal/apply`) is not counted here.

| Rows | Window | Whole-database answers/s, 2.0.0 | Whole-database answers/s, 2.1-dev | Other sync requests/s, 2.0.0 | Other sync requests/s, 2.1-dev |
|---:|---|---:|---:|---:|---:|
| 10000 | idle | 0 (0-0) | 0 (0-0) | 5.67 (5.61-5.67) | 6.10 (6.06-6.10) |
| 10000 | writes | 1.79 (1.77-2.19) | 0 (0-0) | 5.31 (5.19-5.33) | 6.70 (6.32-7.02) |
| 100000 | idle | 0 (0-0) | 0 (0-0) | 3.15 (3.11-3.21) | 6.06 (6.00-6.10) |
| 100000 | writes | 1.10 (1.09-1.11) | 0 (0-0) | 1.66 (1.60-1.69) | 6.36 (6.17-7.07) |

Per node, median over the runs:

| Rows | Window | Node | CPU, cores, 2.0.0 | CPU, cores, 2.1-dev | In, KB/s, 2.0.0 | In, KB/s, 2.1-dev | Out, KB/s, 2.0.0 | Out, KB/s, 2.1-dev |
|---:|---|---|---:|---:|---:|---:|---:|---:|
| 10000 | idle | n1 | 0.10 | 0.01 | 1.79 | 1.87 | 1.70 | 1.88 |
| 10000 | idle | n2 | 0.12 | 0.01 | 1.76 | 1.96 | 1.81 | 1.88 |
| 10000 | idle | n3 | 0.12 | 0.01 | 1.78 | 1.89 | 1.79 | 1.92 |
| 10000 | writes | n1 | 0.35 | 0.16 | 2650 | 28.7 | 67.3 | 33.2 |
| 10000 | writes | n2 | 0.21 | 0.05 | 569 | 18.6 | 2010 | 16.3 |
| 10000 | writes | n3 | 0.22 | 0.05 | 704 | 19.1 | 1810 | 16.1 |
| 100000 | idle | n1 | 0.60 | 0.01 | 1.10 | 1.84 | 1.07 | 1.87 |
| 100000 | idle | n2 | 0.73 | 0.01 | 1.08 | 1.88 | 1.07 | 1.87 |
| 100000 | idle | n3 | 0.75 | 0.01 | 1.07 | 1.90 | 1.08 | 1.85 |
| 100000 | writes | n1 | 0.88 | 0.14 | 10363 | 26.7 | 1980 | 35.2 |
| 100000 | writes | n2 | 0.81 | 0.04 | 5190 | 20.3 | 9823 | 14.3 |
| 100000 | writes | n3 | 0.84 | 0.04 | 6164 | 16.9 | 9707 | 14.6 |

The writes, through n1, and what followed them:

| Rows | Writes/s, 2.0.0 | Writes/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev | All nodes equal after, s, 2.0.0 | All nodes equal after, s, 2.1-dev |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10000 | 20.0 (20.0-20.0) | 20.0 (20.0-20.0) | 7.38 (7.35-7.41) | 7.45 (7.44-7.46) | 90.5 (79.8-90.7) | 12.9 (12.8-13.3) | 0 (0-0) | 0 (0-0) |
| 100000 | 19.9 (19.7-20.0) | 20.0 (20.0-20.0) | 211 (196-251) | 7.18 (7.18-7.20) | 1116 (1004-1210) | 15.6 (11.3-18.1) | 0 (0-0) | 0 (0-0) |

| Rows | One whole-database answer, MB, 2.0.0 | One whole-database answer, MB, 2.1-dev | Preload until all nodes equal, s, 2.0.0 | Preload until all nodes equal, s, 2.1-dev | Memory at the end, MiB (n1 / n2 / n3), 2.0.0 | Memory at the end, MiB (n1 / n2 / n3), 2.1-dev |
|---:|---:|---:|---:|---:|---:|---:|
| 10000 | 1.94 (1.94-1.94) | 1.94 (1.94-1.94) | 1.00 (1.00-1.00) | 1.20 (1.10-1.20) | 291 / 247 / 254 | 228 / 208 / 204 |
| 100000 | 19.5 (19.5-19.5) | 19.5 (19.5-19.5) | 11.3 (10.2-11.6) | 7.80 (7.30-8.00) | 951 / 811 / 838 | 559 / 443 / 478 |

<!-- bench:end -->

## As shipped

Scenarios a and c with each image's defaults: no `JDK_JAVA_OPTIONS`, so 2.0.0 runs with Nagle's algorithm on
and 2.1 with it off. Otherwise the same method and machine as above. Every 2.0.0 request waits about 40 ms, so
writes at concurrency 1 and 8 and reads of tables up to 10k rows run at about 24 per connection; at
concurrency 32 2.0.0's writes reach the disk's limit (351/s) either way.

<!-- bench-shipped:begin -->
- 2.0.0: image `serengeti:2.0.0` (linux/arm64, id sha256:7b257a5eba32), version 2.0.0, built from commit abf086d82ec21fa6bfefa6c53da8748fc6fd3b9b. Run from 2026-11-14T12:22Z to 2026-11-14T12:35Z (UTC) with the harness at commit f2c57195e931.
- 2.1-dev: image `serengeti:2.1.0-dev-17ad3ab` (linux/arm64, id sha256:0970c00675a9), version 2.1.0-dev, built from commit 17ad3ab6dfa2dc41b09fb84522c49316c5f71031. Run from 2026-11-14T13:52Z to 2026-11-14T14:05Z (UTC) with the harness at commit 7d8256924088.

### a. Single node, plain HTTP, fsync=always: INSERT

| Concurrency | Requests/s, 2.0.0 | Requests/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev | Writes per fsync (mean fsync), 2.0.0 | Writes per fsync (mean fsync), 2.1-dev |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 23.0 (22.8-23.0) | 345 (341-345) | 44.0 | 2.89 | 44.0 | 3.09 | 1.0 (2.74 ms) | 1.0 (2.69 ms) |
| 8 | 183 (182-183) | 1432 (1429-1432) | 44.0 | 5.54 | 45.0 | 8.81 | 1.0 (2.71 ms) | 4.0 (2.75 ms) |
| 32 | 351 (351-352) | 2660 (2659-2670) | 88.0 | 11.3 | 129 | 15.3 | 1.0 (2.71 ms) | 7.8 (2.83 ms) |

### c. Single node reads, concurrency 8

No indexes. `SELECT * FROM t WHERE n = x` returns one row; x is a random value, the same sequence in every run. `WHERE _id = x` looks up a random id of the table, also the same sequence in every run.

| Query | Rows in table | Requests/s, 2.0.0 | Requests/s, 2.1-dev | p50 ms, 2.0.0 | p50 ms, 2.1-dev | p99 ms, 2.0.0 | p99 ms, 2.1-dev |
|---|---:|---:|---:|---:|---:|---:|---:|
| SELECT * WHERE n = x | 1000 | 195 (195-195) | 15128 (14980-15131) | 41.0 | 0.50 | 42.0 | 1.09 |
| SELECT COUNT(*) | 1000 | 196 (196-196) | 16077 (16045-16101) | 41.0 | 0.48 | 42.0 | 1.00 |
| SELECT * WHERE _id = x | 1000 | 191 (191-191) | 16034 (16020-16038) | 41.0 | 0.47 | 42.0 | 0.98 |
| SELECT * WHERE n = x | 10000 | 187 (187-187) | 4474 (4457-4505) | 43.0 | 1.59 | 43.1 | 3.58 |
| SELECT COUNT(*) | 10000 | 195 (195-196) | 10832 (10793-10865) | 41.0 | 0.68 | 41.3 | 1.60 |
| SELECT * WHERE _id = x | 10000 | 186 (186-187) | 16017 (15983-16037) | 42.0 | 0.47 | 42.1 | 0.98 |
| SELECT * WHERE n = x | 100000 | 108 (107-109) | 243 (241-246) | 74.0 | 32.8 | 79.0 | 42.2 |
| SELECT COUNT(*) | 100000 | 166 (165-166) | 1414 (1403-1419) | 48.0 | 5.31 | 50.0 | 9.63 |
| SELECT * WHERE _id = x | 100000 | 137 (136-138) | 16015 (16007-16019) | 57.0 | 0.47 | 61.0 | 0.98 |

<!-- bench-shipped:end -->

## Running it again

On a Linux machine with Docker, from a checkout of this repository (the harness) and a checkout of the
engine at the commit to measure:

```
# the image, labelled with the commit it is built from
mkdir /tmp/src-2.1 && git -C ../serengeti-2.1 archive 17ad3ab | tar -x -C /tmp/src-2.1
docker build -t serengeti:2.1.0-dev --build-arg VERSION=2.1.0-dev \
  --label org.opencontainers.image.revision=$(git -C ../serengeti-2.1 rev-parse 17ad3ab) /tmp/src-2.1

# the main run (40 to 50 minutes), then the as-shipped one (13 minutes)
export DISK="gp3 EBS, 120 GiB, 3000 IOPS, 125 MB/s (as provisioned)"
NODE_ARGS="-e JDK_JAVA_OPTIONS=-Dsun.net.httpserver.nodelay=true" \
  IMAGE=serengeti:2.1.0-dev OUT=out/2.1 tests/bench/run.sh
SCENARIOS="a c" IMAGE=serengeti:2.1.0-dev OUT=out/2.1-shipped tests/bench/run.sh

# the same two runs for the other image (OUT=out/2.0, out/2.0-shipped), then this file
UPDATE_DOC=1 tests/bench/report.sh 2.0.0=out/2.0 2.1-dev=out/2.1
UPDATE_DOC=1 DOC_MARK=bench-shipped METHOD=0 tests/bench/report.sh 2.0.0=out/2.0-shipped 2.1-dev=out/2.1-shipped
```

`QUICK=1 tests/bench/run.sh` runs every scenario briefly (about 3 minutes) to check the harness; its
numbers are not measurements, and `report.sh` refuses to put them in this file. On EC2 the instance type is
read from the instance metadata service; elsewhere set `INSTANCE_TYPE` or leave it out.
