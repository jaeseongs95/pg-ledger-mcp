package org.pgledger.cli.tls;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import org.pgledger.cli.secret.SecretProtection;

/**
 * Replaces a file the way the secret store does: the new content is written to a temporary file in the directory of the
 * target and flushed to disk, then an atomic move puts it over the target. A reader (nginx, a person) sees the old file or the
 * new one, never half of a file, and a failure before the move leaves the old file as it was and the temporary file removed.
 * The protection of the file is given when the temporary file is created and never changed afterwards: a private file (a key)
 * gets the protection of the secret store ({@link SecretProtection}: owner only, by POSIX permissions or by an ACL, chosen by
 * what the file system supports), a shared file (the certificate chain, a challenge token) is {@code rw-r--r--} where the file
 * system has modes, because the web server reads it as another account. Several files that belong together (a certificate
 * and its key) are first {@linkplain #stage staged}, so that the failures a disk can have (no room, no permission) happen
 * before the first of them is replaced. Ceiling: the directory is not fsynced, a crash right after a move can still lose the
 * rename, and a failure of the second move after the first one succeeded is not undone.
 */
final class AtomicFile {

    /** The prefix of the temporary files; no certificate file or token starts with a dot. */
    static final String TEMPORARY_PREFIX = "..tls-";
    static final String TEMPORARY_SUFFIX = ".tmp";

    private static final FileAttribute<?> SHARED = PosixFilePermissions
            .asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"));

    private AtomicFile() {
    }

    /** What writes the content of a file. */
    @FunctionalInterface
    interface Content {

        void writeTo(OutputStream out) throws IOException;
    }

    /** Something that must hold before the first byte is written. */
    @FunctionalInterface
    private interface Settle {

        void run() throws IOException;
    }

    /** A file that is written and flushed under a temporary name and not yet in its place; closed, it is removed unless committed. */
    static final class Staged implements AutoCloseable {

        private final Path temporary;
        private final Path target;
        private boolean committed;

        private Staged(Path temporary, Path target) {
            this.temporary = temporary;
            this.target = target;
        }

        /** The atomic move over the target. */
        void commit() throws IOException {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            committed = true;
        }

        @Override
        public void close() throws IOException {
            if (!committed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** A file only its owner can use, written and flushed, not yet in its place. */
    static Staged stagePrivate(Path target, SecretProtection protection, Content content) throws IOException {
        Path temporary = Files.createTempFile(parent(target), TEMPORARY_PREFIX, TEMPORARY_SUFFIX, protection.fileAttributes());
        return stage(target, temporary, content, () -> protection.settle(temporary));
    }

    /** A file the web server may read ({@code rw-r--r--} where the file system has modes), written and flushed, not yet in its place. */
    static Staged stageShared(Path target, Content content) throws IOException {
        Path directory = parent(target);
        Path temporary = directory.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX, SHARED)
                : Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
        return stage(target, temporary, content, () -> {
        });
    }

    /** One shared file, replaced at once. */
    static void replaceShared(Path target, Content content) throws IOException {
        try (Staged staged = stageShared(target, content)) {
            staged.commit();
        }
    }

    private static Path parent(Path target) {
        return target.toAbsolutePath().getParent();
    }

    private static Staged stage(Path target, Path temporary, Content content, Settle settle) throws IOException {
        boolean written = false;
        try {
            // the protection is certain before the first byte is written
            settle.run();
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                    OutputStream out = Channels.newOutputStream(channel)) {
                content.writeTo(out);
                out.flush();
                channel.force(true);
            }
            written = true;
            return new Staged(temporary, target);
        } finally {
            if (!written) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
