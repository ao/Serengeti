# Replication and consistency

A cluster is a fixed set of 1 to 7 nodes, and every node holds every database. Peers come from
`SERENGETI_PEERS`, or from opt-in discovery in a range of at most 256 addresses (`discovery_cidr`).
Accounts are not replicated: give every node the same `SERENGETI_ADMIN_PASSWORD`.

## Writes

A write goes to whichever node the client calls. That node:

1. refuses it with 503 if fewer than a majority of the cluster (itself included) answered in the last 3
   seconds, or if it has not yet synced with a majority since it started;
2. commits it to its own log (fsynced first under the default `fsync=always`);
3. sends it to every peer, each of which logs it before answering;
4. answers 200 once a majority, itself included, has it, or 503 after 5 seconds without a majority.

A 503 from step 4 means the write is in this node's log but may not be on a majority. It may still
reach the other nodes later, through the sync below. Retrying the same statement is safe for UPDATE and
DELETE; an INSERT with an explicit `_id` then fails with "already exists" if the first attempt did spread.

With 3 nodes, writes need 2; with 5, they need 3. A cluster with 2 nodes needs both, so either one being
down stops writes.

## Sync

Every second each node compares a SHA-256 digest of each database with each peer and pulls the full
state of any database whose digest differs. The state is a CRDT: rows, tables and databases keep their
newest version (a value or a tombstone), merging is idempotent and order-independent, and the merge only
logs what is new. This is how a node that was down or partitioned catches up, and how a write that missed
a peer gets there.

Pulling a whole database when one row differs is simple and correct, but costs bandwidth that grows with
the database. Serengeti is meant for databases that fit comfortably in memory on each node.

## Conflicts

Each change carries a version from a hybrid logical clock (wall time, a counter, and the node id). When
two nodes change the same row at about the same time, the higher version wins on every node, and the
other change is lost without an error. Schema changes resolve the same way; a table dropped at version D
removes every row older than D.

## Reads

By default a read is answered from the local node and may miss writes that it has not received yet.
With `"consistency": "majority"` in the request, the node first syncs with enough peers to form a
majority, so the read sees every write that was acknowledged before it began. That read fails with 503
when no majority is reachable.

## What this is not

It is not linearizable and has no transactions across statements. Insert-unless-exists is checked on the
receiving node only: two nodes accepting an insert with the same `_id` at the same time both succeed, and
the higher version wins. Clocks are not trusted for correctness, but a node whose clock runs far ahead
wins conflicts it should lose.

## Node-to-node security

`/internal/*` routes need the cluster key (`SERENGETI_CLUSTER_KEY`, at least 32 characters) and are only
served by nodes with peers configured. A node with peers refuses to start without TLS unless
`SERENGETI_CLUSTER_PLAINTEXT=allow` is set. With TLS, a node calls its peers over HTTPS and checks their
certificate against `tls_truststore` (or the JDK's CAs) and their host name against the peer address, so
each node's certificate must name the host (or IP address) its peers use for it.

## Tested

`tests/cluster/run.sh` runs three nodes of the image over TLS in Docker and checks, while a writer
inserts rows through all three: one node killed (writes continue), two killed (writes 503, majority
reads 503), both restarted (they catch up), one node partitioned by dropping its packets to and from the
peers with iptables in its network namespace (the suite checks the node sees no peer; writes on its
side 503, on the majority side 200, it catches up after healing), one node's fsync slowed by 1 second
(writes through the others are not slowed), and all three restarted. Every insert that got a 200 must be
on every node at the end. Three runs on an M-series Mac under OrbStack: 27 of 27 checks each time, with
262, 228 and 223 acknowledged inserts and none lost.
