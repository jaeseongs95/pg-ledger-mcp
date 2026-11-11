package org.pgledger.database.driver;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
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

/**
 * The driver boundary: the only place of the product that loads classes from a path and uses reflection. It checks
 * the registered jar (path, owner, permissions, digest, archive), loads the named class in an isolated class loader,
 * and creates the driver. The user supplies the jar; nothing of any DBMS is on the product's class path.
 *
 * <p>The jar is read once into memory. The digest is taken of exactly those bytes, the bytes are written to a private
 * copy (a temporary file that only the owner can read and write), and the class loader opens the copy. A swap of the
 * registered file between the check and the load (a time-of-check to time-of-use race) therefore cannot change what
 * runs. The copy is deleted when the {@link RegisteredDriver} is closed (and, as a fallback, when the JVM ends). A
 * {@link URLClassLoader} opens an URL, not a byte array, so the copy is the file it opens.
 *
 * <p>Standard API used: {@link Files#readAllBytes}, {@link Files#createTempFile(String, String,
 * java.nio.file.attribute.FileAttribute[])} and {@link Files#getOwner} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html),
 * {@link PosixFilePermissions} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/PosixFilePermissions.html),
 * {@link java.nio.file.FileSystem#supportedFileAttributeViews()} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/FileSystem.html)
 * and {@link URLClassLoader} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/URLClassLoader.html).
 */
public final class DriverLoader {

    private static final Logger LOG = LoggerFactory.getLogger(DriverLoader.class);

    /** The name of the private copy; the jar suffix is what a class path entry looks like. */
    private static final String COPY_PREFIX = "pg-ledger-driver-";
    private static final String COPY_SUFFIX = ".jar";

    /** A Spring property placeholder: it would be resolved when the registration file is read back. */
    private static final String PLACEHOLDER_START = "${";

    private static final String POSIX = "posix";

    private DriverLoader() {
    }

    /** The loaded driver and the digest of the bytes it was loaded from. */
    private record Opened(RegisteredDriver driver, Sha256 digest) {
    }

    /**
     * Loads the registered driver.
     *
     * @throws InfrastructureException DRIVER_NOT_CONFIGURED when nothing is registered, DRIVER_INVALID when what is
     *         registered cannot be used (missing or changed jar, not an archive, wrong class)
     */
    public static RegisteredDriver load(DriverRegistration registration) {
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
    public static DriverRegistration describe(Path jar, String className, String url, Instant registeredAt) {
        Opened opened = open(jar, className, null);
        try (RegisteredDriver driver = opened.driver()) {
            driver.requireAccepts(url);
        }
        return new DriverRegistration(jar, className, opened.digest().hex(), registeredAt);
    }

    /**
     * The one way a jar is opened: path checks, read the bytes once, attributes, digest (against
     * {@code expectedDigest} when it is given), private copy, load. Whatever fails after the copy was made removes it.
     */
    private static Opened open(Path jar, String className, String expectedDigest) {
        requireUsablePath(jar);
        byte[] bytes = readOnce(jar);
        Path copy = createPrivateCopy();
        boolean handedOver = false;
        try {
            requireTrustedFile(jar, copy);
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

    private static byte[] readOnce(Path jar) {
        try {
            return Files.readAllBytes(jar);
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

    /**
     * The code of the jar runs inside the process. On a POSIX file system others must not be able to replace it
     * (neither the group nor others may write it), and its owner is the process user or the owner of the directory
     * it is in (an installer, for example root, owns both). The process user is the owner of the private copy that the
     * process just created: no system property is read for it.
     */
    private static void requireTrustedFile(Path jar, Path privateCopy) {
        if (!jar.getFileSystem().supportedFileAttributeViews().contains(POSIX)) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(jar);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw invalid("the jar is writable by its group or by others: " + jar);
            }
            UserPrincipal owner = Files.getOwner(jar);
            UserPrincipal processUser = Files.getOwner(privateCopy);
            if (!owner.equals(processUser) && !owner.equals(Files.getOwner(jar.getParent()))) {
                throw invalid("the jar is owned by " + owner.getName() + ", who is neither the process user nor the "
                        + "owner of its directory: " + jar);
            }
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("cannot read the owner and the permissions of " + jar), e);
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
