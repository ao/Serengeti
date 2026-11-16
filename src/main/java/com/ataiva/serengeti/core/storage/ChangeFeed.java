package com.ataiva.serengeti.core.storage;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The recent changes to this node's data, in the order this node applied them, for clients that follow a
 * table ({@code GET /v1/changes}). A change is a row whose visible value changed (written here or merged from
 * a peer), or a table or database dropped. The feed is a ring of the last {@code capacity} changes in memory;
 * it starts empty when the node starts.
 *
 * <p>A cursor is {@code EPOCH:SEQ}: the feed's random epoch, which changes when the node restarts, and the
 * sequence number of the last change the reader has seen. A cursor from another epoch, or older than the
 * oldest change still held, cannot be continued: the reader has to read the table again (see {@link Gone}).
 */
public final class ChangeFeed {
    /** One change. {@code row} is null for a deleted row; {@code kind} is "row", "drop_table" or "drop_db". */
    public record Change(long seq, String kind, String db, String table, String id, Map<String, Object> row,
            Stamp stamp) {}

    /** A page of changes and the cursor to continue from. */
    public record Page(List<Change> changes, String cursor) {}

    /** The cursor cannot be continued: it is from before a restart, or the changes after it are gone. */
    public static final class Gone extends Exception {
        private static final long serialVersionUID = 1L;

        Gone(String message) {
            super(message, null, false, false);
        }
    }

    private final String epoch;
    private final Change[] ring;
    /** Sequence number of the next change; the first is 1. */
    private long next = 1;

    public ChangeFeed(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        this.epoch = HexFormat.of().formatHex(b);
        this.ring = new Change[capacity];
    }

    synchronized void add(String kind, String db, String table, String id, Map<String, Object> row, Stamp stamp) {
        ring[(int) (next % ring.length)] = new Change(next, kind, db, table, id, row, stamp);
        next++;
        notifyAll();
    }

    /** The cursor of the newest change: following from it returns only changes made after now. */
    public synchronized String now() {
        return epoch + ":" + (next - 1);
    }

    /**
     * Up to {@code limit} changes after {@code cursor} to {@code db} (and {@code table}, if not null). With none
     * yet, waits up to {@code waitMillis} for one. The returned cursor is past every change looked at, matching
     * or not, so a filtered reader does not scan the same changes again.
     */
    public Page read(String cursor, String db, String table, int limit, long waitMillis)
            throws Gone, InterruptedException {
        long after = parse(cursor);
        long deadline = System.currentTimeMillis() + Math.max(0, waitMillis);
        synchronized (this) {
            while (true) {
                long oldest = Math.max(1, next - ring.length);
                if (after + 1 < oldest) {
                    throw new Gone("the changes after this cursor are no longer held (this node keeps the last "
                            + ring.length + "); read the table again and follow from after=now");
                }
                if (after >= next) {
                    throw new Gone("this cursor is ahead of this node; it may come from another node");
                }
                List<Change> out = new ArrayList<>();
                long seen = after;
                for (long s = after + 1; s < next && out.size() < limit; s++) {
                    Change c = ring[(int) (s % ring.length)];
                    seen = s;
                    if (c.db().equals(db) && (table == null || c.table() == null || c.table().equals(table))) {
                        out.add(c);
                    }
                }
                long left = deadline - System.currentTimeMillis();
                if (!out.isEmpty() || left <= 0) {
                    return new Page(out, epoch + ":" + seen);
                }
                after = seen; // nothing matched: wait for newer changes only
                wait(left);
            }
        }
    }

    private long parse(String cursor) throws Gone {
        int colon = cursor == null ? -1 : cursor.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("a cursor looks like EPOCH:NUMBER; get one with after=now");
        }
        if (!cursor.substring(0, colon).equals(epoch)) {
            throw new Gone("this cursor is from before this node restarted, or from another node; read the table "
                    + "again and follow from after=now");
        }
        try {
            long seq = Long.parseLong(cursor.substring(colon + 1));
            if (seq < 0) {
                throw new NumberFormatException();
            }
            return seq;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("a cursor looks like EPOCH:NUMBER; get one with after=now");
        }
    }
}
