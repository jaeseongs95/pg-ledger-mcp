package org.pgledger.cli.secret;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.springframework.stereotype.Component;

/**
 * Stores one secret per file in an owner-only directory owned by the current process user. Directory 0700 and file 0600 are given at creation and never
 * widened and narrowed afterwards; an existing directory or file with wider access is refused, not repaired.
 */
@Component
public class SecretStore {

    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> GROUP_AND_OTHERS = PosixFilePermissions.fromString("---rwxrwx");

    /** Temporary files start with two dots: configtree ignores those (SEC-015), and no valid secret name starts with a dot. */
    static final String TEMPORARY_PREFIX = "..secret-";
    static final String TEMPORARY_SUFFIX = ".tmp";

    private final Path directory;
    private final ProcessUser processUser;

    SecretStore(SecretsProperties properties, ProcessUser processUser) {
        this.processUser = processUser;
        this.directory = properties.directory();
    }

    /** Refuses early (before the user types anything) when the store cannot be used; creates nothing. */
    public void check(SecretName name) {
        requirePosix();
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                requireOwnerOnly(directory, true);
                Path target = directory.resolve(name.value());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    requireOwnerOnly(target, false);
                }
            } else {
                requireParent();
            }
        } catch (IOException e) {
            throw new FailureException(ErrorCode.IO_FAILED, e, directory.toString(), reason(e));
        }
    }

    /** Writes the value (UTF-8, no trailing newline) and returns the file. The caller wipes {@code value}. */
    public Path store(SecretName name, char[] value) {
        requirePosix();
        ByteBuffer bytes = encode(value);
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                requireParent();
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            }
            requireOwnerOnly(directory, true);
            Path target = directory.resolve(name.value());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireOwnerOnly(target, false);
            }
            replace(target, bytes);
            return target;
        } catch (IOException e) {
            throw new FailureException(ErrorCode.IO_FAILED, e, directory.toString(), reason(e));
        } finally {
            Arrays.fill(bytes.array(), (byte) 0);
        }
    }

    /** The parent (for example /etc/pg-ledger-mcp) belongs to the installation; only the secrets directory is created here. */
    private void requireParent() {
        Path parent = directory.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            throw new FailureException(ErrorCode.PARENT_MISSING, parent.toString());
        }
    }

    private void requirePosix() {
        if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            throw new FailureException(ErrorCode.NON_POSIX_FILESYSTEM, directory.toString());
        }
    }

    private void requireOwnerOnly(Path path, boolean expectDirectory) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (expectDirectory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
            throw new FailureException(ErrorCode.UNEXPECTED_FILE_TYPE, path.toString());
        }
        // The service reads the secrets with its own account: a path owned by anyone else (for example root) is useless.
        UserPrincipal owner = attributes.owner();
        UserPrincipal current = processUser.current(path.getFileSystem());
        if (!owner.equals(current)) {
            throw new FailureException(ErrorCode.FOREIGN_OWNER, path.toString(), owner.getName(), current.getName());
        }
        Set<PosixFilePermission> mode = attributes.permissions();
        if (!Collections.disjoint(mode, GROUP_AND_OTHERS)) {
            throw new FailureException(ErrorCode.LOOSE_PERMISSIONS, path.toString(), PosixFilePermissions.toString(mode));
        }
    }

    /** Temporary 0600 file in the same directory, flushed to disk, then an atomic move over the target. */
    private void replace(Path target, ByteBuffer bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX, PosixFilePermissions.asFileAttribute(FILE_MODE));
        // try-with-resources, no catch: unless the move happened the temporary file is deleted again, and a failure of
        // that delete is added to the original failure as a suppressed exception.
        try (TemporaryFile guard = new TemporaryFile(temporary)) {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            // ceiling: the directory itself is not fsynced; a crash right after the move can still lose the rename.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            guard.moved();
        }
    }

    /** Deletes the temporary file on close unless it was moved to its place. */
    private static final class TemporaryFile implements AutoCloseable {

        private final Path path;
        private boolean moved;

        TemporaryFile(Path path) {
            this.path = path;
        }

        void moved() {
            moved = true;
        }

        @Override
        public void close() throws IOException {
            if (!moved) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** UTF-8 into one buffer that the caller wipes; unpaired surrogates are rejected, never replaced. */
    private static ByteBuffer encode(char[] value) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer bytes = ByteBuffer.allocate((int) (value.length * (double) encoder.maxBytesPerChar()));
        try {
            CoderResult result = encoder.encode(CharBuffer.wrap(value), bytes, true);
            if (result.isUnderflow()) {
                result = encoder.flush(bytes);
            }
            if (!result.isUnderflow()) {
                result.throwException();
            }
        } catch (CharacterCodingException e) {
            Arrays.fill(bytes.array(), (byte) 0);
            throw new FailureException(ErrorCode.INVALID_ENCODING, e);
        }
        bytes.flip();
        return bytes;
    }

    private static String reason(IOException e) {
        return e.getClass().getSimpleName();
    }
}
