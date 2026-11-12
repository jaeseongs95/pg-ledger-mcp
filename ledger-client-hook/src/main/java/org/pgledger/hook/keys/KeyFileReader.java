package org.pgledger.hook.keys;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Set;
import org.pgledger.hook.keys.SessionKeyException.Reason;

/**
 * Opens an existing key file safely and reads its bytes (F8): the file is opened once, with {@code NOFOLLOW_LINKS}, and
 * read from that opened channel; its attributes and protection are checked right after the open, before a byte is read.
 * There is no second open of the path ({@link Files#newByteChannel(Path, Set, java.nio.file.attribute.FileAttribute[])},
 * the JDK API for exactly this). The channel cannot give its own attributes in the JDK, so the attributes are read by
 * path just after the open; a process that can replace the file in that moment can already write into the key folder,
 * which is refused unless it belongs to the owner alone.
 *
 * @param protection how the key folder and the key files are protected on this file system
 */
record KeyFileReader(Protection protection) {

    private static final int MAX_FILE_BYTES = 16 * 1024;
    private static final Set<OpenOption> READ_WITHOUT_FOLLOWING_LINKS =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    /**
     * The bytes of the key file; the caller zeroes them when it has parsed them.
     *
     * @throws SessionKeyException {@link Reason#NOT_A_REGULAR_FILE}, {@link Reason#CORRUPT} (too large), or the refusal
     *                             of the protection ({@link Reason#TOO_OPEN}, {@link Reason#WRONG_OWNER})
     * @throws IOException         if the file cannot be opened or read
     */
    byte[] read(Path file) throws IOException {
        try (SeekableByteChannel channel = openWithoutFollowingLinks(file)) {
            // the attributes of the file that was just opened (see the class comment), before a byte is read
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new SessionKeyException(Reason.NOT_A_REGULAR_FILE);
            }
            if (attributes.size() > MAX_FILE_BYTES) {
                throw new SessionKeyException(Reason.CORRUPT);
            }
            protection.requireOwnerOnly(file, LinkOption.NOFOLLOW_LINKS);
            return readAll(channel, (int) attributes.size());
        }
    }

    /** The one open of the key file: a link is not followed (a link is refused as not a regular file). */
    private static SeekableByteChannel openWithoutFollowingLinks(Path file) throws IOException {
        try {
            return Files.newByteChannel(file, READ_WITHOUT_FOLLOWING_LINKS);
        } catch (FileSystemException failure) {
            if (Files.isSymbolicLink(file)) {
                throw new SessionKeyException(Reason.NOT_A_REGULAR_FILE, failure);
            }
            throw failure;
        }
    }

    private static byte[] readAll(SeekableByteChannel channel, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        int read = 0;
        // a read may return fewer bytes than asked for; -1 is the end of the file
        while (buffer.hasRemaining() && read >= 0) {
            read = channel.read(buffer);
        }
        byte[] content = Arrays.copyOf(buffer.array(), buffer.position());
        Arrays.fill(buffer.array(), (byte) 0);
        return content;
    }
}
