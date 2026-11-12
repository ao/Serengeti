package com.ataiva.serengeti.core.server;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Writes files that only the owner of the process may read (mode 0600 where the file system has modes). */
final class PrivateFiles {
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private PrivateFiles() {}

    /** Writes {@code text} to a temporary file, forces it, and renames it over {@code target}. */
    static void writeAtomically(Path target, String text) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (POSIX) {
            FileAttribute<Set<PosixFilePermission>> mode =
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            Files.createFile(tmp, mode);
        } else {
            Files.createFile(tmp);
        }
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (POSIX) {
            try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
                d.force(true);
            } catch (IOException ignored) {
                // Some file systems do not allow opening a directory; the rename is still done.
            }
        }
    }

    static boolean isPrivate(Path p) throws IOException {
        if (!POSIX) {
            return true;
        }
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
        return perms.equals(PosixFilePermissions.fromString("rw-------"));
    }
}
