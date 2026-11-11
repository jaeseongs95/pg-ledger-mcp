package org.pgledger.hook.keys;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.hook.keys.SessionKeyException.Reason;

/**
 * The Ed25519 key pairs of the authors, one file per author in one folder. This package is the only place of the hook
 * that uses file system APIs.
 *
 * <p>File name: the lower-case hex SHA-256 of the author text plus {@code .key} (portable, and no author text can climb
 * out of the folder). Content, UTF-8 text with line feeds:
 * <pre>
 * author=&lt;author&gt;
 * private-key=&lt;PKCS#8 DER, standard base64&gt;
 * public-key=&lt;X.509 DER, standard base64&gt;
 * </pre>
 *
 * <p>An existing file is read and checked (author line, both keys parse, both keys are a pair: a fixed message signed
 * with the private key verifies with the public key); it is never overwritten, repaired or deleted. A missing file is
 * created with {@code CREATE_NEW}, so two processes that race for the same author never overwrite each other: the one
 * that finds the file already there reads the winner's file.
 *
 * <p>Protection: where the default file system has the POSIX attribute view, a folder this class creates gets
 * {@code rwx------} and a file {@code rw-------}, both given at creation and never widened; an existing folder or file
 * with any group or other permission bit is refused ({@link Reason#TOO_OPEN}), and so is a key file that is a link.
 * Where there is no POSIX view nothing is set: the default protection of the user profile applies.
 *
 * <p>ceiling: a process that reads the file in the moment between the winner's create and its write (a few hundred
 * bytes) sees an empty file and refuses with {@link Reason#CORRUPT}; the hook call is denied once and the next call
 * works. A temporary file plus an atomic link would close the window, at the cost of a file system without links.
 */
public final class SessionKeyStore {

    private static final String ALGORITHM = "Ed25519";
    private static final String AUTHOR_PREFIX = "author=";
    private static final String PRIVATE_PREFIX = "private-key=";
    private static final String PUBLIC_PREFIX = "public-key=";
    private static final String FILE_SUFFIX = ".key";
    private static final int MAX_FILE_BYTES = 16 * 1024;
    private static final byte[] PROBE = "pg-ledger-mcp/session-key/probe".getBytes(StandardCharsets.US_ASCII);
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> GROUP_AND_OTHERS = PosixFilePermissions.fromString("---rwxrwx");
    private static final Set<StandardOpenOption> CREATE_NEW_FOR_WRITE =
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    /** The key folder; {@code null} if the configured folder is not usable (then {@link #unusable} says why). */
    private final Path directory;
    private final Throwable unusable;

    /** A store in this folder; nothing is created before the first {@link #load}. */
    public SessionKeyStore(Path directory) {
        this(Objects.requireNonNull(directory, "directory"), null);
    }

    private SessionKeyStore(Path directory, Throwable unusable) {
        this.directory = directory;
        this.unusable = unusable;
    }

    /**
     * A store in the folder given as path text (the arguments of {@link Path#of(String, String...)}), for the caller
     * that has no file system API of its own. A blank or invalid text does not fail here: every {@link #load} of that
     * store refuses with {@link Reason#FOLDER_UNAVAILABLE}, so the hook can answer the host.
     */
    public static SessionKeyStore at(String first, List<String> more) {
        if (first == null || first.isBlank()) {
            return new SessionKeyStore(null, null);
        }
        try {
            return new SessionKeyStore(Path.of(first, more.toArray(String[]::new)), null);
        } catch (InvalidPathException invalid) {
            return new SessionKeyStore(null, invalid);
        }
    }

    /**
     * The key pair of the author: the one in the key folder, or a new one that is stored there first.
     *
     * @throws SessionKeyException when the folder or the file cannot be used; the message holds no key material
     */
    public SessionKey load(String author) {
        Objects.requireNonNull(author, "author");
        if (author.isEmpty() || author.indexOf('\n') >= 0 || author.indexOf('\r') >= 0) {
            throw new SessionKeyException(Reason.AUTHOR_INVALID);
        }
        if (directory == null) {
            throw new SessionKeyException(Reason.FOLDER_UNAVAILABLE, unusable);
        }
        try {
            boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
            prepareDirectory(posix);
            Path file = directory.resolve(fileName(author));
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return read(file, author, posix);
            }
            return create(file, author, posix);
        } catch (IOException failure) {
            throw new SessionKeyException(Reason.IO_FAILED, failure);
        }
    }

    /** The name of the key file of an author inside the folder. */
    static String fileName(String author) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(author.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + FILE_SUFFIX;
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", failure);
        }
    }

    private void prepareDirectory(boolean posix) throws IOException {
        if (!Files.exists(directory)) {
            // parents that are created here get the same owner-only mode, given at creation
            FileAttribute<?>[] attributes = posix
                    ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(DIRECTORY_MODE)}
                    : new FileAttribute<?>[0];
            Files.createDirectories(directory, attributes);
        }
        if (!Files.isDirectory(directory)) {
            throw new SessionKeyException(Reason.FOLDER_UNAVAILABLE);
        }
        if (posix) {
            requireOwnerOnly(directory);
        }
    }

    /** Refuses a path that group or others can use; the mode is never changed. */
    private static void requireOwnerOnly(Path path, LinkOption... options) throws IOException {
        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, options);
        if (!Collections.disjoint(mode, GROUP_AND_OTHERS)) {
            throw new SessionKeyException(Reason.TOO_OPEN);
        }
    }

    private SessionKey read(Path file, String author, boolean posix) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new SessionKeyException(Reason.NOT_A_REGULAR_FILE);
        }
        if (attributes.size() > MAX_FILE_BYTES) {
            throw new SessionKeyException(Reason.CORRUPT);
        }
        if (posix) {
            requireOwnerOnly(file, LinkOption.NOFOLLOW_LINKS);
        }
        byte[] content = Files.readAllBytes(file);
        try {
            return parse(content, author);
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    private static SessionKey parse(byte[] content, String author) {
        List<String> lines = decode(content).lines().toList();
        if (lines.size() != 3) {
            throw new SessionKeyException(Reason.CORRUPT);
        }
        String storedAuthor = value(lines.get(0), AUTHOR_PREFIX);
        String privateText = value(lines.get(1), PRIVATE_PREFIX);
        String publicText = value(lines.get(2), PUBLIC_PREFIX);
        if (!author.equals(storedAuthor)) {
            throw new SessionKeyException(Reason.AUTHOR_MISMATCH);
        }
        PrivateKey privateKey = privateKey(privateText);
        PublicKey publicKey = publicKey(publicText);
        requirePair(privateKey, publicKey);
        return new SessionKey(author, privateKey, publicKey);
    }

    private static String decode(byte[] content) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException failure) {
            throw new SessionKeyException(Reason.CORRUPT, failure);
        }
    }

    private static String value(String line, String prefix) {
        if (!line.startsWith(prefix)) {
            throw new SessionKeyException(Reason.CORRUPT);
        }
        return line.substring(prefix.length());
    }

    private static PrivateKey privateKey(String base64) {
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException notBase64) {
            // the cause is not kept: its message names a character of the key text
            throw new SessionKeyException(Reason.CORRUPT);
        }
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key factory", failure);
        } catch (InvalidKeySpecException failure) {
            throw new SessionKeyException(Reason.CORRUPT, failure);
        } finally {
            Arrays.fill(der, (byte) 0);
        }
    }

    private static PublicKey publicKey(String base64) {
        try {
            return PublicKeys.decode(base64);
        } catch (AttestationException failure) {
            throw new SessionKeyException(Reason.CORRUPT, failure);
        }
    }

    /** A fixed message signed with the private key must verify with the public key: the two belong together. */
    private static void requirePair(PrivateKey privateKey, PublicKey publicKey) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(privateKey);
            signer.update(PROBE);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(PROBE);
            if (!verifier.verify(signature)) {
                throw new SessionKeyException(Reason.KEY_MISMATCH);
            }
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 signature", failure);
        } catch (InvalidKeyException | SignatureException failure) {
            throw new SessionKeyException(Reason.KEY_MISMATCH, failure);
        }
    }

    private SessionKey create(Path file, String author, boolean posix) throws IOException {
        KeyPair pair = generate();
        byte[] content = encode(author, pair);
        FileAttribute<?>[] attributes = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_MODE)}
                : new FileAttribute<?>[0];
        boolean created = false;
        try (SeekableByteChannel channel = Files.newByteChannel(file, CREATE_NEW_FOR_WRITE, attributes)) {
            created = true;
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (FileAlreadyExistsException winner) {
            // another hook process created the file after the check above: its key is the key of the author
            return read(file, author, posix);
        } catch (IOException failure) {
            if (created) {
                deletePartialFile(file, failure);
            }
            throw failure;
        } finally {
            Arrays.fill(content, (byte) 0);
        }
        return new SessionKey(author, pair.getPrivate(), pair.getPublic());
    }

    /** Removes the file this process created and could not finish writing; it holds no complete key. */
    private static void deletePartialFile(Path file, IOException failure) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key pair generator", failure);
        }
    }

    private static byte[] encode(String author, KeyPair pair) {
        String text = AUTHOR_PREFIX + author + "\n"
                + PRIVATE_PREFIX + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n"
                + PUBLIC_PREFIX + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
