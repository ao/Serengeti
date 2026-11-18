package com.ataiva.serengeti.core.server;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The HTTP server's threads, and how long a client may hold one.
 *
 * <p>The JDK server reads a request's headers on the thread its executor runs the connection on, and the handler
 * reads the body and writes the answer on that thread too, so a client that sends or reads slowly holds it. Here up
 * to {@code connections} threads read and answer requests, and at most {@code workers} of them at once do the rest
 * (authenticate, run the request, build its answer): a request takes a permit once its body is in and gives it
 * back when its answer starts to go out. A thread waits on its client only in these phases, each with a limit:
 *
 * <ul>
 *   <li>{@link #HEADERS}: the JDK's own limit on sending a request ({@code sun.net.httpserver.maxReqTime}, 30 s,
 *       set in Serengeti.main);
 *   <li>{@link #BODY}: once {@code bodyGrace} has passed, every {@code bodyRate} bytes received buy one more second;
 *   <li>{@link #WRITE}: the client must take each write (at most {@link HttpApi#WRITE_SLICE} bytes) within
 *       {@code writeTimeout}.
 * </ul>
 *
 * <p>When every thread is busy, a new connection takes the thread of the client that has been in one of those
 * phases longest, provided that is {@code evictAfter} or more; headers first, then bodies, then writes.
 *
 * <p>Large answers (from {@link #SMALL_ANSWER}) count against {@code unsentBudget} from before they are built until
 * they are written: three times their estimated size while being built, their size while being written. A request
 * whose answer does not fit first cuts writes blocked for {@code evictAfter} or more, longest first, then waits
 * for room, and gets 503 if none comes within {@code permitWaitMillis}.
 *
 * <p>A cut interrupts the thread. The JDK server's sockets are interruptible channels, so the blocked read or write
 * ends at once and the connection closes. A thread is only interrupted in one of the phases above, under its
 * slot's lock, and the flag is cleared when it leaves the phase: a request that runs is never interrupted, since
 * an interrupt would also close the file channels it uses.
 */
final class ClientLimits implements Executor, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ClientLimits.class.getName());

    static final int IDLE = 0;
    /** Inside the JDK server: TLS handshake, request line and headers. */
    static final int HEADERS = 1;
    /** Reading the request body. */
    static final int BODY = 2;
    /** Running the request; never interrupted. */
    static final int WORK = 3;
    /** Inside one write to the client, or the exchange's close. */
    static final int WRITE = 4;

    /** Why connections were cut, in the order of {@link #cuts()}. */
    static final String[] CUT_REASONS = {"slow_body", "slow_write", "busy", "unsent"};
    static final int CUT_BODY = 0;
    static final int CUT_WRITE = 1;
    static final int CUT_BUSY = 2;
    static final int CUT_UNSENT = 3;

    /** Defaults for {@link Settings}. */
    static final long BODY_GRACE_MILLIS = 5_000;
    static final long BODY_RATE = 16 * 1024;
    static final long WRITE_TIMEOUT_MILLIS = 30_000;
    static final long EVICT_AFTER_MILLIS = 1_000;
    static final long PERMIT_WAIT_MILLIS = 10_000;
    /** Answers smaller than this are not counted against the unsent budget: the socket's buffers take them. */
    static final int SMALL_ANSWER = 256 * 1024;
    /** Bytes of a body that are never counted against the body budget. */
    static final int SMALL_BODY = 64 * 1024;
    /** Stack reserved for each thread. Pages are committed only as a thread reaches them. */
    static final long STACK_BYTES = 1 << 20;
    static final long TICK_MILLIS = 200;
    static final long IDLE_SECONDS = 60;

    /** The limits, as one value so that tests can shorten them. */
    record Settings(long bodyGraceMillis, long bodyRate, long writeTimeoutMillis, long evictAfterMillis,
            long permitWaitMillis, long unsentBudget, long bodyBudget, long tickMillis) {
        static Settings defaults() {
            long heap = Runtime.getRuntime().maxMemory();
            return new Settings(BODY_GRACE_MILLIS, BODY_RATE, WRITE_TIMEOUT_MILLIS, EVICT_AFTER_MILLIS,
                    PERMIT_WAIT_MILLIS, Math.max(48L << 20, heap / 4), Math.max(16L << 20, heap / 16), TICK_MILLIS);
        }
    }

    /** One per thread: what the thread is waiting on, since when. */
    static final class Slot {
        final Thread thread;
        final ClientLimits owner;
        volatile int phase;
        /** {@link System#nanoTime} (or the test clock) when the phase began. */
        volatile long since;
        /** In {@link #BODY}: bytes received so far. */
        volatile long bytes;
        /** Bytes of the answer being written that count against the unsent budget. */
        volatile long unsent;
        /** Set with the interrupt, under the lock; cleared, with the interrupt, by the thread itself. */
        private boolean cut;
        /** Only the thread itself touches these. */
        private boolean permit;
        private long bodyReserved;

        Slot(Thread thread, ClientLimits owner) {
            this.thread = thread;
            this.owner = owner;
        }
    }

    /** An I/O exception for a connection that a limit cut. */
    static final class Cut extends IOException {
        private static final long serialVersionUID = 1L;

        Cut() {
            super("connection cut by a limit on slow clients", null);
        }
    }

    final class RequestThread extends Thread {
        final Slot slot = new Slot(this, ClientLimits.this);

        RequestThread(Runnable r) {
            super(null, r, "http", STACK_BYTES);
            setDaemon(true);
        }

        @Override
        public void run() {
            slots.add(slot);
            try {
                super.run();
            } finally {
                slots.remove(slot);
            }
        }
    }

    /** A task from the JDK server; with a null task, one that only runs what is pending. */
    private final class Task implements Runnable {
        final Runnable task;

        Task(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            Slot s = ((RequestThread) Thread.currentThread()).slot;
            Runnable next = task;
            while (true) {
                if (next != null) {
                    begin(s);
                    try {
                        next.run();
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "HTTP exchange failed", e);
                    } finally {
                        end(s);
                    }
                }
                Task p = pending.poll();
                if (p == null) {
                    return;
                }
                pendingCount.decrementAndGet();
                next = p.task;
            }
        }
    }

    private final ThreadPoolExecutor pool;
    private final Semaphore permits;
    private final int connections;
    private final Settings settings;
    private final LongSupplier nanos;
    private final Set<Slot> slots = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Task> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicLong unsentBytes = new AtomicLong();
    /** Waited on for room in the budget for answers. */
    private final Object room = new Object();
    private final AtomicLong bodyBytes = new AtomicLong();
    private final AtomicLongArray cuts = new AtomicLongArray(CUT_REASONS.length);
    private final Thread watchdog;
    private volatile boolean closed;

    ClientLimits(int connections, int workers) {
        this(connections, workers, Settings.defaults(), System::nanoTime, true);
    }

    /** With {@code watch} false nothing checks the deadlines but {@link #tick()}, for tests with a clock. */
    ClientLimits(int connections, int workers, Settings settings, LongSupplier nanos, boolean watch) {
        if (workers < 1 || connections < workers) {
            throw new IllegalArgumentException("need 1 <= workers <= connections");
        }
        this.connections = connections;
        this.settings = settings;
        this.nanos = nanos;
        this.permits = new Semaphore(workers, true);
        // No core threads, and a hand-off queue: an idle thread takes a new task if there is one (the most recently
        // idle first), a new thread starts otherwise, and threads idle for a minute stop.
        this.pool = new ThreadPoolExecutor(0, connections, IDLE_SECONDS, TimeUnit.SECONDS, new SynchronousQueue<>(),
                RequestThread::new, (r, p) -> overflow((Task) r));
        if (watch) {
            watchdog = new Thread(() -> {
                while (!closed) {
                    try {
                        Thread.sleep(settings.tickMillis());
                        tick();
                    } catch (InterruptedException e) {
                        return;
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "checking HTTP client limits failed", e);
                    }
                }
            }, "http-limits");
            watchdog.setDaemon(true);
            watchdog.start();
        } else {
            watchdog = null;
        }
    }

    /** The slot of the calling thread, or null off the server's threads. */
    static Slot slot() {
        return Thread.currentThread() instanceof RequestThread t ? t.slot : null;
    }

    @Override
    public void execute(Runnable task) {
        if (closed) {
            throw new RejectedExecutionException("stopped");
        }
        pool.execute(new Task(task));
    }

    /**
     * Every thread is busy: cut the client that has waited longest, and queue the task for the first thread that
     * comes free. Runs on the JDK server's dispatcher thread, so it never waits. Past {@code connections} queued
     * tasks, the JDK server closes the connection.
     */
    private void overflow(Task t) {
        if (t.task == null) {
            return;
        }
        if (closed) {
            throw new RejectedExecutionException("stopped");
        }
        evictOne();
        if (pendingCount.incrementAndGet() > connections) {
            pendingCount.decrementAndGet();
            throw new RejectedExecutionException("every HTTP thread is busy");
        }
        pending.add(t);
    }

    /** Checks every deadline once; the watchdog calls it every {@code tickMillis}. */
    void tick() {
        long now = nanos.getAsLong();
        long grace = settings.bodyGraceMillis() * 1_000_000;
        long write = settings.writeTimeoutMillis() * 1_000_000;
        for (Slot s : slots) {
            int p = s.phase;
            long since = s.since;
            if (p == BODY && now - since > grace + s.bytes * 1_000_000_000L / settings.bodyRate()) {
                cut(s, p, since, CUT_BODY);
            } else if (p == WRITE && now - since > write) {
                cut(s, p, since, CUT_WRITE);
            }
        }
        if (unsentBytes.get() > settings.unsentBudget()) {
            cutStalledWriters(0);
        }
        // A task queued just as a thread went idle waits for the next tick at most.
        if (!pending.isEmpty() && pool.getActiveCount() < pool.getPoolSize()) {
            try {
                pool.execute(new Task(null));
            } catch (RejectedExecutionException ignored) {
                // closed
            }
        }
    }

    private void evictOne() {
        long now = nanos.getAsLong();
        long after = settings.evictAfterMillis() * 1_000_000;
        Slot best = null;
        int bestRank = 0;
        long bestSince = 0;
        for (Slot s : slots) {
            int p = s.phase;
            long since = s.since;
            int rank = p == HEADERS ? 3 : p == BODY ? 2 : p == WRITE ? 1 : 0;
            if (rank == 0 || now - since < after) {
                continue;
            }
            if (best == null || rank > bestRank || rank == bestRank && since - bestSince < 0) {
                best = s;
                bestRank = rank;
                bestSince = since;
            }
        }
        if (best != null) {
            cut(best, bestRank == 3 ? HEADERS : bestRank == 2 ? BODY : WRITE, bestSince, CUT_BUSY);
        }
    }

    private void cutStalledWriters(long extra) {
        long now = nanos.getAsLong();
        long after = settings.evictAfterMillis() * 1_000_000;
        List<Slot> stalled = new ArrayList<>();
        for (Slot s : slots) {
            if (s.phase == WRITE && s.unsent > 0 && now - s.since >= after) {
                stalled.add(s);
            }
        }
        stalled.sort(Comparator.comparingLong(s -> s.since));
        long over = unsentBytes.get() + extra - settings.unsentBudget();
        for (Slot s : stalled) {
            if (over <= 0) {
                break;
            }
            long n = s.unsent;
            if (cut(s, WRITE, s.since, CUT_UNSENT)) {
                over -= n;
            }
        }
    }

    /** Interrupts the slot's thread if it is still in {@code phase} since {@code since}. */
    private boolean cut(Slot s, int phase, long since, int reason) {
        long freed;
        synchronized (s) {
            if (s.cut || s.phase != phase || s.since != since) {
                return false;
            }
            s.cut = true;
            s.thread.interrupt();
            // Its answer is as good as gone: no longer counted, so the next check does not cut another for it.
            freed = s.unsent;
            unsentBytes.addAndGet(-freed);
            s.unsent = 0;
        }
        if (freed > 0) {
            freedRoom();
        }
        cuts.incrementAndGet(reason);
        LOG.fine(() -> "cut a slow HTTP client (" + CUT_REASONS[reason] + ")");
        return true;
    }

    private void begin(Slot s) {
        synchronized (s) {
            s.cut = false;
            Thread.interrupted();
            s.bytes = 0;
            s.since = nanos.getAsLong();
            s.phase = HEADERS;
        }
    }

    private void end(Slot s) {
        release(s);
        sent(s);
        unreserveBody(s);
        synchronized (s) {
            s.phase = IDLE;
            s.cut = false;
            Thread.interrupted();
        }
    }

    /** Enters a phase in which the thread waits on its client. */
    void enter(Slot s, int phase) throws Cut {
        if (s == null) {
            return;
        }
        synchronized (s) {
            if (s.cut) {
                throw cleared(s);
            }
            s.bytes = 0;
            s.since = nanos.getAsLong();
            s.phase = phase;
        }
    }

    /** Leaves a client phase for {@link #WORK}; throws if the phase was cut, with the interrupt cleared. */
    void leave(Slot s) throws Cut {
        if (s == null) {
            return;
        }
        synchronized (s) {
            s.phase = WORK;
            if (s.cut) {
                throw cleared(s);
            }
        }
    }

    private static Cut cleared(Slot s) {
        // The thread's channel may not have been in a call when the interrupt came, and then is still open; the
        // caller fails the exchange, and the JDK server closes the connection.
        s.cut = false;
        s.phase = WORK;
        Thread.interrupted();
        return new Cut();
    }

    /** Counts {@code n} more bytes of the body received. */
    static void received(Slot s, long n) {
        if (s != null) {
            s.bytes += n;
        }
    }

    /**
     * Takes room for body bytes past {@link #SMALL_BODY}; false when the bodies being read already hold the
     * budget.
     */
    boolean reserveBody(Slot s, long n) {
        if (s == null) {
            return true;
        }
        long now = bodyBytes.addAndGet(n);
        if (now > settings.bodyBudget()) {
            bodyBytes.addAndGet(-n);
            return false;
        }
        s.bodyReserved += n;
        return true;
    }

    /** Gives back the body's room, once the request has run. */
    void unreserveBody(Slot s) {
        if (s != null && s.bodyReserved > 0) {
            bodyBytes.addAndGet(-s.bodyReserved);
            s.bodyReserved = 0;
        }
    }

    /**
     * Takes a permit to run the request. False if none came free within {@code permitWaitMillis}. A thread
     * holds at most one; off the server's threads there is no limit.
     */
    boolean acquire(Slot s) {
        if (s == null || s.permit) {
            return true;
        }
        try {
            if (!permits.tryAcquire(settings.permitWaitMillis(), TimeUnit.MILLISECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        s.permit = true;
        return true;
    }

    /** Gives the permit back, if the thread holds one: its answer is about to go out, or it is done. */
    void release(Slot s) {
        if (s != null && s.permit) {
            s.permit = false;
            permits.release();
        }
    }

    /**
     * Takes room in the budget for answers in memory before an answer of about {@code n} bytes is built (building
     * one takes about three times its size for a moment). Over the budget, stalled writes are cut first; then the
     * request gives back its permit while it waits up to {@code permitWaitMillis} for room, and takes one again
     * once it has room. False if either did not come. When nothing else is held, an answer larger than the whole
     * budget still gets it.
     */
    boolean reserveAnswer(Slot s, long n) {
        if (s == null || n < SMALL_ANSWER) {
            return true;
        }
        sent(s);
        if (tryReserve(s, n)) {
            return true;
        }
        // As if this answer were already held, so that the stalled writes cut make room for it.
        cutStalledWriters(n);
        if (tryReserve(s, n)) {
            return true;
        }
        // Waiting holds no permit: requests that need none of this room go on meanwhile.
        boolean hadPermit = s.permit;
        release(s);
        long end = nanos.getAsLong() + settings.permitWaitMillis() * 1_000_000;
        while (!tryReserve(s, n)) {
            long left = end - nanos.getAsLong();
            if (left <= 0) {
                return false;
            }
            synchronized (room) {
                try {
                    room.wait(Math.max(1, Math.min(20, left / 1_000_000)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        if (hadPermit && !acquire(s)) {
            sent(s);
            return false;
        }
        return true;
    }

    private boolean tryReserve(Slot s, long n) {
        while (true) {
            long cur = unsentBytes.get();
            if (cur != 0 && cur + n > settings.unsentBudget()) {
                return false;
            }
            synchronized (s) {
                if (unsentBytes.compareAndSet(cur, cur + n)) {
                    s.unsent = n;
                    return true;
                }
            }
        }
    }

    /**
     * An answer of {@code n} bytes is built and about to be written. In place of its reservation, a large one
     * counts against the budget until {@link #sent}; over it, stalled writes are cut.
     */
    void unsent(Slot s, long n) {
        if (s == null) {
            return;
        }
        sent(s);
        if (n < SMALL_ANSWER) {
            return;
        }
        long now;
        synchronized (s) {
            s.unsent = n;
            now = unsentBytes.addAndGet(n);
        }
        if (now > settings.unsentBudget()) {
            cutStalledWriters(0);
        }
    }

    void sent(Slot s) {
        if (s == null) {
            return;
        }
        long freed;
        synchronized (s) {
            freed = s.unsent;
            unsentBytes.addAndGet(-freed);
            s.unsent = 0;
        }
        if (freed > 0) {
            freedRoom();
        }
    }

    private void freedRoom() {
        synchronized (room) {
            room.notifyAll();
        }
    }

    /**
     * Sends the response headers: gives back the calling thread's permit first, and waits on the client as a
     * {@link #WRITE}. Off the server's threads, only sends them.
     */
    static void sendHeaders(com.sun.net.httpserver.HttpExchange ex, int status, long length) throws IOException {
        Slot s = slot();
        if (s == null) {
            ex.sendResponseHeaders(status, length);
            return;
        }
        ClientLimits l = s.owner;
        l.release(s);
        l.enter(s, WRITE);
        try {
            ex.sendResponseHeaders(status, length);
        } finally {
            l.leave(s);
        }
    }

    /**
     * Closes the exchange as a {@link #WRITE}: the JDK may still flush the answer, or read what is left of the
     * body. Always closes it, also after a cut.
     */
    void close(Slot s, com.sun.net.httpserver.HttpExchange ex) {
        if (s != null) {
            synchronized (s) {
                if (s.cut) {
                    s.cut = false;
                    Thread.interrupted();
                }
                s.since = nanos.getAsLong();
                s.phase = WRITE;
            }
        }
        try {
            ex.close();
        } finally {
            try {
                leave(s);
            } catch (Cut ignored) {
                // closed either way
            }
        }
    }

    /** An output stream whose every write is a {@link #WRITE} phase of the calling thread. */
    OutputStream guard(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                Slot s = slot();
                enter(s, WRITE);
                try {
                    out.write(b, off, len);
                } finally {
                    leave(s);
                }
            }

            @Override
            public void flush() throws IOException {
                Slot s = slot();
                enter(s, WRITE);
                try {
                    out.flush();
                } finally {
                    leave(s);
                }
            }

            @Override
            public void close() throws IOException {
                Slot s = slot();
                enter(s, WRITE);
                try {
                    out.close();
                } finally {
                    leave(s);
                }
            }
        };
    }

    /** Connections cut so far, by reason ({@link #CUT_REASONS}). */
    long[] cuts() {
        long[] out = new long[CUT_REASONS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = cuts.get(i);
        }
        return out;
    }

    /** Threads reading or answering a request now. */
    int busy() {
        return pool.getActiveCount();
    }

    int threads() {
        return pool.getPoolSize();
    }

    int freePermits() {
        return permits.availablePermits();
    }

    long unsentBytes() {
        return unsentBytes.get();
    }

    long bodyBytes() {
        return bodyBytes.get();
    }

    int connections() {
        return connections;
    }

    /** Stops the threads; a request still running gets an interrupt, as before. */
    @Override
    public void close() {
        closed = true;
        if (watchdog != null) {
            watchdog.interrupt();
        }
        pool.shutdownNow();
    }
}
