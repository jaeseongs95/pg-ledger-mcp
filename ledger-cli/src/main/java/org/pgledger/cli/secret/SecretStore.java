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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;

import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.springframework.stereotype.Component;

/**
 * Stores one secret per file in an owner-only directory owned by the current process user. The protection is given at
 * creation and chosen by the file system, never by the name of the operating system ({@link SecretProtection}): POSIX
 * permissions 0700 (directory) and 0600 (file), or on a file system with ACLs an ACL with one entry for the owner and
 * nothing inherited. An existing directory or file with wider access, a link, or another owner is refused, not repaired.
 * A file system with neither is refused (NON_POSIX_FILESYSTEM): nothing is stored with the default protection.
 */
@Component
public class SecretStore {

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
        SecretProtection protection = protection();
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                requireOwnerOnly(directory, true, protection);
                Path target = directory.resolve(name.value());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    requireOwnerOnly(target, false, protection);
                }
            } else {
                requireParent();
            }
        } catch (IOException e) {
            throw new FailureException(ErrorCode.IO_FAILED, e, directory.toString(), reason(e));
        }
    }

    /**
     * Whether the secret is stored already. Refuses like {@link #check} when the directory or the file cannot be used (a
     * secret of the wrong owner or with wider access is never reported as present and never used).
     */
    public boolean exists(SecretName name) {
        check(name);
        return Files.exists(directory.resolve(name.value()), LinkOption.NOFOLLOW_LINKS);
    }

    /** Writes the value (UTF-8, no trailing newline) and returns the file. The caller wipes {@code value}. */
    public Path store(SecretName name, char[] value) {
        SecretProtection protection = protection();
        ByteBuffer bytes = encode(value);
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                requireParent();
                Files.createDirectory(directory, protection.directoryAttributes());
                protection.settle(directory);
            }
            requireOwnerOnly(directory, true, protection);
            Path target = directory.resolve(name.value());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireOwnerOnly(target, false, protection);
            }
            replace(target, bytes, protection);
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

    /** The protection of this file system; a file system with neither POSIX permissions nor ACLs is refused. */
    private SecretProtection protection() {
        try {
            return SecretProtection.of(directory.getFileSystem(), processUser)
                    .orElseThrow(() -> new FailureException(ErrorCode.NON_POSIX_FILESYSTEM, directory.toString()));
        } catch (IOException e) {
            throw new FailureException(ErrorCode.IO_FAILED, e, directory.toString(), reason(e));
        }
    }

    private void requireOwnerOnly(Path path, boolean expectDirectory, SecretProtection protection) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (expectDirectory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
            throw new FailureException(ErrorCode.UNEXPECTED_FILE_TYPE, path.toString());
        }
        // The service reads the secrets with its own account: a path owned by anyone else (for example root) is useless.
        UserPrincipal actual = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal current = processUser.current(path.getFileSystem());
        if (!actual.equals(current)) {
            throw new FailureException(ErrorCode.FOREIGN_OWNER, path.toString(), actual.getName(), current.getName());
        }
        protection.requireOwnerOnlyAccess(path);
    }

    /** Temporary owner-only file in the same directory, flushed to disk, then an atomic move over the target. */
    private void replace(Path target, ByteBuffer bytes, SecretProtection protection) throws IOException {
        Path temporary = Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX, protection.fileAttributes());
        // try-with-resources, no catch: unless the move happened the temporary file is deleted again, and a failure of
        // that delete is added to the original failure as a suppressed exception.
        try (TemporaryFile guard = new TemporaryFile(temporary)) {
            // the protection is certain before the first byte of the secret is written
            protection.settle(temporary);
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
