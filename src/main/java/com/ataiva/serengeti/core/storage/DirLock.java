package com.ataiva.serengeti.core.storage;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/**
 * An exclusive lock on a data directory, held by the node (or restore) using it, so that a second process
 * started on the same directory, or a second node in this one, stops instead of writing the same log.
 * The operating system releases it when the process exits, also after a crash or a kill.
 */
public final class DirLock implements Closeable {
    /** The file the lock is taken on. It holds nothing; its presence alone means nothing. */
    public static final String FILE = "lock";
    private static final Logger LOG = Logger.getLogger(DirLock.class.getName());

    private final FileChannel channel;
    private final FileLock lock;

    private DirLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Takes the lock on {@code dir}, which must exist, or throws an IOException saying it is in use. */
    public static DirLock acquire(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock l;
        try {
            l = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            l = null;
        } catch (IOException e) {
            // Some network file systems have no locks. Refusing to start there would leave no way to run.
            LOG.warning("cannot lock " + file + " (" + e.getMessage() + "); nothing stops a second node from "
                    + "opening " + dir + " while this one runs");
            return new DirLock(ch, null);
        }
        if (l == null) {
            ch.close();
            throw new IOException("the data directory " + dir.toAbsolutePath() + " is in use by another node"
                    + " (it holds the lock on " + FILE + "); stop that node first");
        }
        return new DirLock(ch, l);
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}
