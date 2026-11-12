package org.pgledger.hook.keys;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Creates a key file that does not exist yet, atomically with respect to other hook processes: the file is created with
 * {@code CREATE_NEW}, so two processes that race for the same key never overwrite each other. The one that finds the
 * file already there gets {@code false} and reads the winner's file.
 *
 * <p>ceiling: a process that reads the file in the moment between the winner's create and its write (a few hundred
 * bytes) sees an empty file and refuses with {@link SessionKeyException.Reason#CORRUPT}; the hook call is denied once and
 * the next call works. A temporary file plus an atomic link would close the window, at the cost of a file system without
 * links.
 *
 * @param protection how the key folder and the key files are protected on this file system
 */
record KeyFileWriter(Protection protection) {

    private static final Set<OpenOption> CREATE_NEW_FOR_WRITE =
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    /**
     * Creates the file with {@code content}; the protection is certain before the first byte of key material is written.
     * An incomplete file that this process created and could not finish is deleted (it holds no complete key).
     *
     * @return {@code true} if this process created the file, {@code false} if it already existed (another process won)
     * @throws SessionKeyException if the protection of the new file is refused
     * @throws IOException         if the file cannot be created or written
     */
    boolean createNew(Path file, byte[] content) throws IOException {
        boolean created = false;
        try (SeekableByteChannel channel = Files.newByteChannel(file, CREATE_NEW_FOR_WRITE, protection.fileAttributes())) {
            created = true;
            protection.settle(file);
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            return true;
        } catch (FileAlreadyExistsException winner) {
            // the CREATE_NEW race of the class comment, not a failure: the other process created the file first
            return false;
        } catch (IOException | SessionKeyException failure) {
            if (created) {
                deletePartialFile(file, failure);
            }
            throw failure;
        }
    }

    /** Removes the file this process created and could not finish writing; it holds no complete key. */
    private static void deletePartialFile(Path file, Exception failure) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }
}
