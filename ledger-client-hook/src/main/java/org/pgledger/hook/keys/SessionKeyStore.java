package org.pgledger.hook.keys;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
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
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.hook.keys.SessionKeyException.Reason;

/**
 * The Ed25519 key pairs of the authors, one file per (MCP server, author) in one folder. This package is the only place
 * of the hook that uses file system APIs.
 *
 * <p>Why per server: the hook signs for the MCP server named in the host's tool name, and the owner registers the
 * public key of each server separately, so a signature made for one server (the test server, a look-alike server) never
 * verifies on another: a key registered on one server is simply another key than the one of the other.
 *
 * <p>File name: the lower-case hex SHA-256 of {@code <server> LF <author>} plus {@code .key} (portable, and nothing in
 * the names can climb out of the folder; neither value can hold a line feed). Content, UTF-8 text with line feeds:
 * <pre>
 * server=&lt;server&gt;
 * author=&lt;author&gt;
 * private-key=&lt;PKCS#8 DER, standard base64&gt;
 * public-key=&lt;X.509 DER, standard base64&gt;
 * </pre>
 *
 * <p>An existing file is read and checked (server and author lines, both keys parse, both keys are a pair: a fixed
 * message signed with the private key verifies with the public key); it is never overwritten, repaired or deleted. A
 * missing file is created with {@code CREATE_NEW}, so two processes that race for the same key never overwrite each
 * other: the one that finds the file already there reads the winner's file.
 *
 * <p>Reading (F8): the key file is opened once, with {@code NOFOLLOW_LINKS}, and read from that opened channel; its
 * attributes and protection are checked right after the open, before a byte is read. There is no second open of the
 * path. ({@link Files#newByteChannel(Path, java.util.Set, FileAttribute[])}, the JDK API for exactly this.) The channel
 * cannot give its own attributes in the JDK, so the attributes are read by path just after the open; a process that can
 * replace the file in that moment can already write into the key folder, which is refused unless it belongs to the
 * owner alone.
 *
 * <p>Protection (F6), chosen by the capability of the default file system and never by the operating system name:
 * <ul>
 * <li>POSIX attribute view: a folder this class creates gets {@code rwx------} and a file {@code rw-------}, both given
 *     at creation and never widened; an existing folder or file with any group or other permission bit is refused
 *     ({@link Reason#TOO_OPEN}).
 * <li>ACL attribute view ({@link AclFileAttributeView}, the Windows file systems;
 *     https://docs.oracle.com/en/java/javase/25/docs/api/java/nio/file/attribute/AclFileAttributeView.html): a folder
 *     or file this class creates is given, at creation (the {@code acl:acl} attribute of
 *     {@link Files#createDirectories(Path, FileAttribute[])} and {@link Files#newByteChannel(Path, java.util.Set,
 *     FileAttribute[])}), an ACL with one ALLOW entry that grants the current user everything and nothing else; the ACL
 *     is read back before any key material is written, and replaced by that ACL if the system added inherited entries.
 *     An existing folder or file whose ACL allows anything beyond reading its attributes and its ACL to a principal
 *     other than the current user is refused ({@link Reason#TOO_OPEN}). SYSTEM and Administrators are not exempt: an
 *     administrator can take ownership of any file whatever its ACL, so this protects against the other accounts of the
 *     machine that are not administrators, and an entry for them would add nothing but exposure.
 * <li>Both: the owner of the folder and of the file must be the current user ({@link Reason#WRONG_OWNER}); the current
 *     user is the account name that {@link Main} reads from {@code user.name}, resolved by the file system's
 *     {@code UserPrincipalLookupService}. The group Administrators is not accepted as owner: Microsoft documents that
 *     for objects such as files created by members of Domain Admins "the default owner is the Administrators group"
 *     (https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/manage/understand-security-groups, "Domain
 *     Admins"). That page says nothing about local administrators or an elevated token, and the policy page of
 *     "System objects: Default owner for objects created by members of the Administrators group" could not be opened
 *     (the guessed Learn URLs answered 404), so no claim beyond that sentence is made. The consequence is on the safe
 *     side: a hook that an administrator runs with a token whose files are owned by that group is refused
 *     ({@link Reason#WRONG_OWNER}); the host starts hooks as the signed-in user, so run it that way.
 * <li>Neither view: nothing is set, the default protection of the user profile applies.
 * </ul>
 *
 * <p>ceiling: a process that reads the file in the moment between the winner's create and its write (a few hundred
 * bytes) sees an empty file and refuses with {@link Reason#CORRUPT}; the hook call is denied once and the next call
 * works. A temporary file plus an atomic link would close the window, at the cost of a file system without links.
 */
public final class SessionKeyStore {

    private static final String ALGORITHM = "Ed25519";
    private static final String SERVER_PREFIX = "server=";
    private static final String AUTHOR_PREFIX = "author=";
    private static final String PRIVATE_PREFIX = "private-key=";
    private static final String PUBLIC_PREFIX = "public-key=";
    private static final String FILE_SUFFIX = ".key";
    private static final String ACL_ATTRIBUTE = "acl:acl";
    private static final int MAX_FILE_BYTES = 16 * 1024;
    private static final byte[] PROBE = "pg-ledger-mcp/session-key/probe".getBytes(StandardCharsets.US_ASCII);
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> GROUP_AND_OTHERS = PosixFilePermissions.fromString("---rwxrwx");
    /** What another principal may hold without reaching the key: looking at the attributes and the ACL, waiting for the handle. */
    private static final Set<AclEntryPermission> HARMLESS = EnumSet.of(AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE);
    private static final Set<OpenOption> CREATE_NEW_FOR_WRITE =
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    private static final Set<OpenOption> READ_WITHOUT_FOLLOWING_LINKS =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    /** The key folder; {@code null} if the configured folder is not usable (then {@link #unusable} says why). */
    private final Path directory;
    private final Throwable unusable;
    private final String userName;

    /**
     * A store in this folder, for the key files of the operating system account {@code userName}; nothing is created
     * before the first {@link #load}. A folder that is not an absolute path is not usable: every {@link #load} of that
     * store refuses with {@link Reason#FOLDER_UNAVAILABLE} (F7: a relative path would put the keys wherever the host
     * happens to start the hook).
     */
    public SessionKeyStore(Path directory, String userName) {
        this(absoluteOrNull(directory), userName,
                directory.isAbsolute() ? null : new IllegalArgumentException("the key folder must be an absolute path"));
    }

    private SessionKeyStore(Path directory, String userName, Throwable unusable) {
        this.directory = directory;
        this.userName = userName;
        this.unusable = unusable;
    }

    private static Path absoluteOrNull(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return directory.isAbsolute() ? directory : null;
    }

    /**
     * A store in the folder given as path text (the arguments of {@link Path#of(String, String...)}), for the caller
     * that has no file system API of its own. A blank, invalid or relative text does not fail here: every {@link #load}
     * of that store refuses with {@link Reason#FOLDER_UNAVAILABLE}, so the hook can answer the host.
     */
    public static SessionKeyStore at(String first, List<String> more, String userName) {
        if (first == null || first.isBlank()) {
            return new SessionKeyStore(null, userName, null);
        }
        try {
            return new SessionKeyStore(Path.of(first, more.toArray(String[]::new)), userName);
        } catch (InvalidPathException invalid) {
            return new SessionKeyStore(null, userName, invalid);
        }
    }

    /**
     * The key pair of the author for the server: the one in the key folder, or a new one that is stored there first.
     *
     * @throws SessionKeyException when the folder or the file cannot be used; the message holds no key material
     */
    public SessionKey load(String server, String author) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(author, "author");
        if (server.isEmpty() || hasLineBreak(server) || author.isEmpty() || hasLineBreak(author)) {
            throw new SessionKeyException(Reason.AUTHOR_INVALID);
        }
        if (directory == null) {
            throw new SessionKeyException(Reason.FOLDER_UNAVAILABLE, unusable);
        }
        try {
            Protection protection = protection();
            prepareDirectory(protection);
            Path file = directory.resolve(fileName(server, author));
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return read(file, server, author, protection);
            }
            return create(file, server, author, protection);
        } catch (IOException failure) {
            throw new SessionKeyException(Reason.IO_FAILED, failure);
        }
    }

    private static boolean hasLineBreak(String text) {
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }

    /** The name of the key file of a server and an author inside the folder. */
    static String fileName(String server, String author) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((server + "\n" + author).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + FILE_SUFFIX;
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", failure);
        }
    }

    // ---------------------------------------------------------------- protection

    /** How a folder and a key file are protected on this file system, and how that is checked. */
    private sealed interface Protection permits Posix, Acl, Unprotected {

        FileAttribute<?>[] folderAttributes();

        FileAttribute<?>[] fileAttributes();

        /** Refuses a path that another principal can use or that does not belong to the current user; changes nothing. */
        void requireOwnerOnly(Path path, LinkOption... options) throws IOException;

        /** For a path this class has just created: the same check, after the protection was made certain. */
        default void settle(Path path) throws IOException {
            requireOwnerOnly(path);
        }
    }

    private Protection protection() throws IOException {
        FileSystem system = directory.getFileSystem();
        Set<String> views = system.supportedFileAttributeViews();
        boolean posix = views.contains("posix");
        if (!posix && !views.contains("acl")) {
            return new Unprotected();
        }
        if (userName == null || userName.isBlank()) {
            throw new SessionKeyException(Reason.OWNER_UNKNOWN);
        }
        UserPrincipal current = system.getUserPrincipalLookupService().lookupPrincipalByName(userName);
        if (posix) {
            return new Posix(current);
        }
        return new Acl(current);
    }

    /** Refuses a path whose owner is not the current user (the group Administrators is not accepted, see the class comment). */
    private static void requireOwner(Path path, List<UserPrincipal> accepted, LinkOption... options) throws IOException {
        if (!accepted.contains(Files.getOwner(path, options))) {
            throw new SessionKeyException(Reason.WRONG_OWNER);
        }
    }

    private record Unprotected() implements Protection {

        @Override
        public FileAttribute<?>[] folderAttributes() {
            return new FileAttribute<?>[0];
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[0];
        }

        @Override
        public void requireOwnerOnly(Path path, LinkOption... options) {
            // no POSIX and no ACL view: the default protection of the user profile applies
        }
    }

    private record Posix(UserPrincipal current) implements Protection {

        @Override
        public FileAttribute<?>[] folderAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(DIRECTORY_MODE)};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_MODE)};
        }

        @Override
        public void requireOwnerOnly(Path path, LinkOption... options) throws IOException {
            requireOwner(path, List.of(current), options);
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, options);
            if (!Collections.disjoint(mode, GROUP_AND_OTHERS)) {
                throw new SessionKeyException(Reason.TOO_OPEN);
            }
        }
    }

    /** The {@code acl:acl} attribute of the Windows file system provider: the DACL a new file or folder gets. */
    private record AclAttribute(List<AclEntry> value) implements FileAttribute<List<AclEntry>> {

        @Override
        public String name() {
            return ACL_ATTRIBUTE;
        }
    }

    private record Acl(UserPrincipal current) implements Protection {

        /** One ALLOW entry that grants the current user everything; a folder's entry is inherited by what it holds. */
        private List<AclEntry> ownerOnly(boolean folder) {
            Set<AclEntryFlag> flags = folder
                    ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    : EnumSet.noneOf(AclEntryFlag.class);
            return List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build());
        }

        @Override
        public FileAttribute<?>[] folderAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(ownerOnly(true))};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(ownerOnly(false))};
        }

        @Override
        public void requireOwnerOnly(Path path, LinkOption... options) throws IOException {
            requireOwner(path, List.of(current), options);
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, options);
            for (AclEntry entry : view.getAcl()) {
                // only an ALLOW entry grants anything; DENY, AUDIT and ALARM entries do not
                boolean granting = entry.type() == AclEntryType.ALLOW && !entry.principal().equals(current)
                        && !HARMLESS.containsAll(entry.permissions());
                if (granting) {
                    throw new SessionKeyException(Reason.TOO_OPEN);
                }
            }
        }

        /** The ACL given at creation may have been merged with entries inherited from the parent: make it certain. */
        @Override
        public void settle(Path path) throws IOException {
            try {
                requireOwnerOnly(path);
            } catch (SessionKeyException tooOpen) {
                if (tooOpen.reason() != Reason.TOO_OPEN) {
                    throw tooOpen;
                }
                boolean folder = Files.isDirectory(path);
                Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(ownerOnly(folder));
                requireOwnerOnly(path);
            }
        }
    }

    // ---------------------------------------------------------------- folder

    private void prepareDirectory(Protection protection) throws IOException {
        if (!Files.exists(directory)) {
            // parents that are created here get the same owner-only protection, given at creation
            Files.createDirectories(directory, protection.folderAttributes());
            protection.settle(directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new SessionKeyException(Reason.FOLDER_UNAVAILABLE);
        }
        protection.requireOwnerOnly(directory);
    }

    // ---------------------------------------------------------------- read

    private SessionKey read(Path file, String server, String author, Protection protection) throws IOException {
        byte[] content;
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
            content = readAll(channel, (int) attributes.size());
        }
        try {
            return parse(content, server, author);
        } finally {
            Arrays.fill(content, (byte) 0);
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

    private static SessionKey parse(byte[] content, String server, String author) {
        List<String> lines = decode(content).lines().toList();
        if (lines.size() != 4) {
            throw new SessionKeyException(Reason.CORRUPT);
        }
        String storedServer = value(lines.get(0), SERVER_PREFIX);
        String storedAuthor = value(lines.get(1), AUTHOR_PREFIX);
        String privateText = value(lines.get(2), PRIVATE_PREFIX);
        String publicText = value(lines.get(3), PUBLIC_PREFIX);
        if (!server.equals(storedServer)) {
            throw new SessionKeyException(Reason.SERVER_MISMATCH);
        }
        if (!author.equals(storedAuthor)) {
            throw new SessionKeyException(Reason.AUTHOR_MISMATCH);
        }
        PrivateKey privateKey = privateKey(privateText);
        PublicKey publicKey = publicKey(publicText);
        requirePair(privateKey, publicKey);
        return new SessionKey(server, author, privateKey, publicKey);
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

    // ---------------------------------------------------------------- create

    private SessionKey create(Path file, String server, String author, Protection protection) throws IOException {
        KeyPair pair = generate();
        byte[] content = encode(server, author, pair);
        boolean created = false;
        try (SeekableByteChannel channel = Files.newByteChannel(file, CREATE_NEW_FOR_WRITE,
                protection.fileAttributes())) {
            created = true;
            // the protection is certain before the first byte of key material is written
            protection.settle(file);
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (FileAlreadyExistsException winner) {
            // another hook process created the file after the check above: its key is the key of the author
            return read(file, server, author, protection);
        } catch (IOException | SessionKeyException failure) {
            if (created) {
                deletePartialFile(file, failure);
            }
            throw failure;
        } finally {
            Arrays.fill(content, (byte) 0);
        }
        return new SessionKey(server, author, pair.getPrivate(), pair.getPublic());
    }

    /** Removes the file this process created and could not finish writing; it holds no complete key. */
    private static void deletePartialFile(Path file, Exception failure) {
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

    private static byte[] encode(String server, String author, KeyPair pair) {
        String text = SERVER_PREFIX + server + "\n" + AUTHOR_PREFIX + author + "\n"
                + PRIVATE_PREFIX + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n"
                + PUBLIC_PREFIX + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
