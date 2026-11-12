package org.pgledger.database.driver;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Driver;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The driver boundary: the only place of the product that loads classes from a path and uses reflection. It checks
 * the registered jar (path, owner, permissions, digest, archive), loads the named class in an isolated class loader,
 * and creates the driver. The user supplies the jar; nothing of any DBMS is on the product's class path.
 *
 * <p>The jar is opened once, the file that was opened and its directory are checked ({@link FileTrust}: owner, and nobody
 * but the owner and the administrative accounts may write, by POSIX permissions or by ACL), and it is read once into
 * memory from that open channel. The digest is taken of exactly those bytes, the bytes are written to a private
 * copy (a temporary file; on a file system with POSIX permissions it is created with {@code rw-------} through
 * {@link PosixFilePermissions#asFileAttribute}, elsewhere with the default of {@link Files#createTempFile}, which the
 * documentation says may be more restrictive than {@code File.createTempFile} but does not fix), and the class loader
 * opens the copy. A swap of the
 * registered file between the check and the load (a time-of-check to time-of-use race) therefore cannot change what
 * runs. The copy is deleted when the {@link RegisteredDriver} is closed (and, as a fallback, when the JVM ends). A
 * {@link URLClassLoader} opens an URL, not a byte array, so the copy is the file it opens.
 *
 * <p>Standard API used: {@link Files#newByteChannel(Path, Set, java.nio.file.attribute.FileAttribute[])}, {@link Channels#newInputStream} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/Channels.html), {@link LinkOption#NOFOLLOW_LINKS} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/LinkOption.html), {@link Files#createTempFile(String, String,
 * java.nio.file.attribute.FileAttribute[])} and {@link Files#getOwner} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html),
 * {@link PosixFilePermissions} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/PosixFilePermissions.html),
 * {@link java.nio.file.FileSystem#supportedFileAttributeViews()} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/FileSystem.html)
 * and {@link URLClassLoader} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/URLClassLoader.html).
 *
 * <p>Why this is code of ours and not Spring Boot's (principle 8.2, "a reflection that must be written is confined to
 * one place"): Boot's {@code DataSource} auto-configuration creates the driver by class name from the application class
 * path ({@code spring.datasource.driver-class-name}), and the product ships no driver on that class path. The driver is
 * a jar that the user registers at run time, which has to be opened in a class loader of its own so that nothing of the
 * DBMS is visible to the rest of the product. Loading a class from a path ({@link URLClassLoader}), asking whether it is
 * a {@code java.sql.Driver} and creating it ({@code Class.forName}, {@code Constructor.newInstance}) are therefore all
 * done here, and nowhere else. The class is a {@code @Component} so that the registration checks and the data source are
 * made with an injected loader; a {@code Condition}, which runs before any bean exists, makes one with {@code new}.
 */
@Component
public final class DriverLoader {

    private static final Logger LOG = LoggerFactory.getLogger(DriverLoader.class);

    /** The name of the private copy; the jar suffix is what a class path entry looks like. */
    private static final String COPY_PREFIX = "pg-ledger-driver-";
    private static final String COPY_SUFFIX = ".jar";

    /** A Spring property placeholder: it would be resolved when the registration file is read back. */
    private static final String PLACEHOLDER_START = "${";

    private static final String POSIX = "posix";

    /** The one open of the jar: read only, a link is not followed. */
    private static final Set<OpenOption> READ_WITHOUT_FOLLOWING_LINKS =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    /** The loaded driver and the digest of the bytes it was loaded from. */
    private record Opened(RegisteredDriver driver, Sha256 digest) {
    }

    /**
     * Loads the registered driver.
     *
     * @throws InfrastructureException DRIVER_NOT_CONFIGURED when nothing is registered, DRIVER_INVALID when what is
     *         registered cannot be used (missing or changed jar, not an archive, wrong class)
     */
    public RegisteredDriver load(DriverRegistration registration) {
        boolean hasJar = registration.jar() != null;
        boolean hasClass = registration.className() != null && !registration.className().isBlank();
        if (!hasJar && !hasClass) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_NOT_CONFIGURED,
                    List.of("no ledger.jdbc.driver.jar and ledger.jdbc.driver.class-name are set; "
                            + "register a driver with the driver-register command"));
        }
        if (!hasJar || !hasClass) {
            throw invalid((hasJar ? "ledger.jdbc.driver.class-name" : "ledger.jdbc.driver.jar") + " is missing");
        }
        if (registration.sha256() == null) {
            throw invalid("ledger.jdbc.driver.sha256 is missing");
        }
        return open(registration.jar(), registration.className(), registration.sha256()).driver();
    }

    /**
     * Checks a jar and a class for registration (before anything is stored) and returns the registration with the
     * digest of the jar. It loads the class exactly as {@link #load} does and closes the loader again.
     */
    public DriverRegistration describe(Path jar, String className, String url, Instant registeredAt) {
        Opened opened = open(jar, className, null);
        try (RegisteredDriver driver = opened.driver()) {
            driver.requireAccepts(url);
        }
        return new DriverRegistration(jar, className, opened.digest().hex(), registeredAt);
    }

    /**
     * The one way a jar is opened: path checks, a private copy made first (its owner is the process user), the jar opened
     * once and checked and read ({@link #readTrusted}), digest (against {@code expectedDigest} when it is given), load.
     * Whatever fails after the copy was made removes it.
     */
    private Opened open(Path jar, String className, String expectedDigest) {
        requireUsablePath(jar);
        Path copy = createPrivateCopy();
        boolean handedOver = false;
        try {
            byte[] bytes = readTrusted(jar, copy);
            Sha256 actual = Sha256.ofBytes(bytes);
            if (expectedDigest != null && !Objects.equals(actual.hex(), expectedDigest)) {
                throw invalid("digest mismatch for " + jar + ": registered " + expectedDigest + " but the file is "
                        + actual.hex());
            }
            writeBytes(copy, bytes);
            RegisteredDriver driver = instantiate(copy, jar, className);
            handedOver = true;
            return new Opened(driver, actual);
        } finally {
            if (!handedOver) {
                deleteCopy(copy);
            }
        }
    }

    private static void requireUsablePath(Path jar) {
        if (!jar.isAbsolute()) {
            throw invalid("the jar path must be absolute: " + jar);
        }
        if (!jar.equals(jar.normalize())) {
            throw invalid("the jar path must not contain '..' or '.' segments: " + jar);
        }
        if (jar.toString().contains(PLACEHOLDER_START)) {
            throw invalid("the jar path must not contain '" + PLACEHOLDER_START
                    + "' (a configuration placeholder would be resolved when the registration is read): " + jar);
        }
        if (Files.isDirectory(jar)) {
            throw invalid("the jar path is a directory: " + jar);
        }
        if (!Files.isRegularFile(jar)) {
            throw invalid("the jar does not exist: " + jar);
        }
        if (!Files.isReadable(jar)) {
            throw invalid("the jar is not readable: " + jar);
        }
    }

    /**
     * Opens the jar once, without following a link, checks the file that was opened and the directory it is in
     * ({@link FileTrust}: owner, and nobody but the owner and the administrative accounts may write), and reads the bytes
     * from that open channel ({@link Files#newByteChannel(Path, Set, java.nio.file.attribute.FileAttribute[])},
     * {@link Channels#newInputStream}). There is no second open of the path, so a file that is swapped for another after
     * the check is not the one that is read.
     *
     * <p>The JDK gives the attributes of a path and not of an open channel, so the checks read them by path right after the
     * open, before a byte is read, and again after the read: the file must still be the same one (the file key where the
     * file system has one, the size and the modification time), or the jar is refused as changed while it was read. A
     * process that could replace the file in that short time can already write to the directory, which is refused unless
     * it belongs to trusted accounts alone. Only the directory the jar is in is checked, not the ones above it: the
     * registration confines the jar to the configured driver directory, which is where the owner puts the jars.
     *
     * @param privateCopy the empty private copy: its owner is the process user
     */
    private static byte[] readTrusted(Path jar, Path privateCopy) {
        try (SeekableByteChannel channel = Files.newByteChannel(jar, READ_WITHOUT_FOLLOWING_LINKS)) {
            BasicFileAttributes opened = Files.readAttributes(jar, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!opened.isRegularFile()) {
                throw invalid("the jar is not a regular file (a link is not followed): " + jar);
            }
            FileTrust trust = new FileTrust(jar.getFileSystem(), Files.getOwner(privateCopy));
            trust.require(jar, "the jar");
            trust.require(jar.getParent(), "the directory of the jar");
            byte[] bytes;
            try (InputStream in = Channels.newInputStream(channel)) {
                bytes = in.readAllBytes();
            }
            BasicFileAttributes after = Files.readAttributes(jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean same = Objects.equals(opened.fileKey(), after.fileKey()) && opened.size() == after.size()
                    && bytes.length == after.size() && Objects.equals(opened.lastModifiedTime(), after.lastModifiedTime());
            if (!same) {
                throw invalid("the jar changed while it was read: " + jar);
            }
            return bytes;
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of("cannot read " + jar), e);
        }
    }

    /**
     * An empty temporary file that only its owner can read and write (on a file system with POSIX permissions the
     * permissions are given at creation, so the file never exists with wider ones). The bytes are written later,
     * after the checks; the file is created first because its owner is the process user.
     */
    private static Path createPrivateCopy() {
        try {
            Path copy;
            if (FileSystems.getDefault().supportedFileAttributeViews().contains(POSIX)) {
                copy = Files.createTempFile(COPY_PREFIX, COPY_SUFFIX,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                copy = Files.createTempFile(COPY_PREFIX, COPY_SUFFIX);
            }
            copy.toFile().deleteOnExit();
            return copy;
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("cannot make a private copy of the jar"), e);
        }
    }

    private static void writeBytes(Path copy, byte[] bytes) {
        try {
            Files.write(copy, bytes);
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("cannot write the private copy of the jar"), e);
        }
    }

    /** Loads {@code className} from the private copy; {@code registered} is the jar the messages name. */
    private static RegisteredDriver instantiate(Path copy, Path registered, String className) {
        try (ZipFile archive = new ZipFile(copy.toFile())) {
            if (archive.size() == 0) {
                throw invalid("the file is an empty archive: " + registered);
            }
        } catch (ZipException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("the file is not a jar archive: " + registered), e);
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("cannot read " + registered), e);
        }
        URLClassLoader loader = null;
        try {
            URL url = copy.toUri().toURL();
            // an isolated loader whose parent is the platform loader: the driver sees the JDK and its own jar, never the
            // classes of the product (and the product never sees the driver's classes except through java.sql.Driver)
            loader = new URLClassLoader(new URL[] {url}, ClassLoader.getPlatformClassLoader());
            Class<?> type = Class.forName(className, false, loader);
            if (!Driver.class.isAssignableFrom(type)) {
                throw invalid("class " + className + " does not implement java.sql.Driver");
            }
            Constructor<?> constructor = type.getConstructor();
            return new RegisteredDriver((Driver) constructor.newInstance(), loader, copy);
        } catch (ClassNotFoundException e) {
            closeQuietly(loader);
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("class " + className + " is not in " + registered), e);
        } catch (NoSuchMethodException e) {
            closeQuietly(loader);
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("class " + className + " has no public no-argument constructor"), e);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException
                | LinkageError | MalformedURLException e) {
            closeQuietly(loader);
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("class " + className + " cannot be instantiated: " + e), e);
        } catch (InfrastructureException e) {
            closeQuietly(loader);
            throw e;
        }
    }

    /** Deletes the private copy of a jar (the class loader that opened it must be closed first: Windows holds it). */
    static void deleteCopy(Path copy) {
        try {
            Files.deleteIfExists(copy);
        } catch (IOException e) {
            LOG.warn("cannot delete the private copy {} of a driver jar; it is removed when the process ends", copy,
                    e);
        }
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException e) {
            LOG.warn("cannot close the class loader of the driver jar; the original failure follows", e);
        }
    }

    private static InfrastructureException invalid(String reason) {
        return new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of(reason));
    }
}
