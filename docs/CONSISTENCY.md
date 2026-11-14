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

## Writes that do not wait for a majority

With `"ack": "local"` in the request, or `SERENGETI_WRITE_ACK=local` as a node's default, steps 1 and 4
change: the node accepts the write whatever it can reach, answers 200 once the write is in its own log,
and sends it to the peers it can reach without waiting for them. Peers it cannot reach get it through sync
when the network comes back. A node cut off from the others keeps taking writes, and so does the rest of
the cluster; when they meet again, every node ends up with both sides' writes.

The cost is the usual one for this kind of system: a write acknowledged this way is on one disk until it
has spread, and two sides that change the same row while apart keep only the later change (see
Conflicts). Use it where staying writable matters more than that, such as a site whose uplink drops, and
keep the default where it does not. A request can still ask for `"ack": "majority"` on a node whose
default is local.

## Sync

The state is a CRDT: rows, tables and databases keep their newest version (a value or a tombstone),
merging is idempotent and order-independent, and the merge only logs what is new. This is how a node
that was down or partitioned catches up, and how a write that missed a peer gets there.

Each table spreads its rows over 1024 buckets by a hash of their id, and keeps, per bucket, a sum of
hashes of its live rows' versions, updated on every change. Every second each node asks each peer for one
root value per table, compares, asks for the 1024 bucket sums of any table that differs, and pulls only
the rows of the buckets that differ. A cluster where nothing changed exchanges the roots and nothing else;
catching up after one row changed pulls that row's bucket. Nodes running 2.0 do not have these routes,
and are synced the 2.0 way (one digest per database, whole databases pulled), so a cluster can be upgraded
one node at a time.

## Deletes and how long a node may be away

A delete leaves a tombstone so that an older copy of the row cannot come back from a node that missed the
delete. Tombstones are kept for `tombstone_ttl_hours` (default 168, one week) and then dropped. A node
that has not synced with a majority for longer than that might hold rows deleted meanwhile whose
tombstones are already gone, so it refuses to start, and a running node that crosses the limit stops
syncing and taking writes. To bring it back, stop it, empty its data directory except `users.json` and
`node-id`, and start it; it resyncs from its peers. The time of its last sync is kept in `last-sync`.

## Conflicts

Each change carries a version from a hybrid logical clock (wall time, a counter, and the node id). A row
write also records which writes to that row its node had already seen (a version vector: the newest
version seen from each node). That tells a later write from a concurrent one.

A write that had seen the row's current version replaces it. Two writes that had not seen each other, for
example on both sides of a partition with `"ack": "local"`, are both kept. The row's value is the one with
the higher version, on every node, so reads give the same answer everywhere. The other version is not
lost: `SHOW CONFLICTS IN db.table` lists every version of every such row, and the metric
`serengeti_conflicts` counts those rows.

```
SHOW CONFLICTS IN shop.items
{"columns":["_id","current","deleted","node","at","row"],
 "rows":[["bolt",true,false,"0b6f3c2e-5d1a-4e8b-9a7c-2f4d6e8a1b3c",1731400012000,{"qty":90}],
         ["bolt",false,false,"9e2d7a41-c3b8-4f06-8d5e-7a1c3b9f0e24",1731400011000,{"qty":150}]], ...}
```

The next write to the row resolves the conflict: the node writing it has seen every version, so its
write replaces all of them. To keep the current value, write it again; to keep another version, write
that one. A row whose current version is a delete is resolved by inserting its `_id` again.

Merging versions is order-independent, so every node ends up with the same versions; a property test
checks this against the true causal history of random writes and syncs on three replicas
(`ConflictProperties`). Writes from 2.0 nodes carry no record of what they had seen. They are ordered by
version among themselves, as 2.0 did, and a 2.1 write covers the older ones; during a rolling upgrade, a
2.0 node's write to a row last written by a 2.1 node is kept as a conflict even when it was not concurrent.

Schema changes resolve by version alone; a table dropped at version D removes every row version older
than D.

## Reads

By default a read is answered from the node's own data and may miss writes that it has not received yet.
With `"consistency": "majority"` in the request, the node first syncs with enough peers to form a
majority, so the read sees every write that was acknowledged before it began. That read fails with 503
when no majority is reachable.

## What this is not

It is not linearizable and has no transactions across statements. Insert-unless-exists is checked on the
receiving node only: two nodes accepting an insert with the same `_id` at the same time both succeed, and
the row is then a conflict (above). Versions follow wall clocks, so a node whose clock runs ahead has its
version chosen as the current one when it should not be; changes stamped more than 60 seconds ahead of the receiving node's clock are refused (and
logged with the sending node), which bounds how far one bad clock can reach. Keep clocks synced with NTP.

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
(writes through the others are not slowed), local writes on both sides of a partition (both kept, the
conflict listed on every node, then resolved by one write), and all three restarted. Every insert that
got a 200 must be on every node at the end. Three runs of 2.1 on an EC2 c7g.4xlarge (Ubuntu 24.04, Docker
29): 37 of 37 checks each time, with 228, 237 and 229 acknowledged inserts and none lost.

`tests/upgrade/run.sh` opens a 2.0.0 node's data directory with this build, and upgrades a 3-node 2.0.0
cluster one node at a time while a writer inserts through all three; every acknowledged insert must be on
every node afterwards, with no conflicts listed. It also creates an index while 2.0.0 nodes remain, and
rolls a node that has an index back to 2.0.0: every row must still be there. 44 of 44 on the same machine.
