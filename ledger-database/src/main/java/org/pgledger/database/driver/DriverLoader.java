package org.pgledger.database.driver;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Driver;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;

/**
 * The driver boundary: the only place of the product that loads classes from a path and uses reflection. It checks
 * the registered jar (path, digest, archive), loads the named class in an isolated class loader, and creates the
 * driver. The user supplies the jar; nothing of any DBMS is on the product's class path.
 */
public final class DriverLoader {

    private DriverLoader() {
    }

    /**
     * Loads the registered driver.
     *
     * @throws InfrastructureException DRIVER_NOT_CONFIGURED when nothing is registered, DRIVER_INVALID when what is
     *         registered cannot be used (missing or changed jar, not an archive, wrong class)
     */
    public static RegisteredDriver load(DriverRegistration registration) {
        boolean hasJar = registration.jar() != null && !registration.jar().isBlank();
        boolean hasClass = registration.className() != null && !registration.className().isBlank();
        if (!hasJar && !hasClass) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_NOT_CONFIGURED,
                    List.of("no ledger.jdbc.driver.jar and ledger.jdbc.driver.class-name are set; "
                            + "register a driver with the driver-register command"));
        }
        if (!hasJar || !hasClass) {
            throw invalid((hasJar ? "ledger.jdbc.driver.class-name" : "ledger.jdbc.driver.jar") + " is missing");
        }
        Path jar = requireUsableJar(registration.jar());
        if (registration.sha256() == null) {
            throw invalid("ledger.jdbc.driver.sha256 is missing");
        }
        Sha256 actual = digestOf(jar);
        if (!actual.hex().equals(registration.sha256())) {
            throw invalid("digest mismatch for " + jar + ": registered " + registration.sha256() + " but the file is "
                    + actual.hex());
        }
        return instantiate(jar, registration.className());
    }

    /**
     * Checks a jar and a class for registration (before anything is stored) and returns the registration with the
     * digest of the jar. It loads the class exactly as {@link #load} does and closes the loader again.
     */
    public static DriverRegistration describe(String jarPath, String className, String url,
            java.time.Instant registeredAt) {
        Path jar = requireUsableJar(jarPath);
        Sha256 digest = digestOf(jar);
        try (RegisteredDriver driver = instantiate(jar, className)) {
            driver.requireAccepts(url);
        }
        return new DriverRegistration(jar.toString(), className, digest.hex(), registeredAt);
    }

    private static Path requireUsableJar(String jarPath) {
        Path jar = Path.of(jarPath);
        if (!jar.isAbsolute()) {
            throw invalid("the jar path must be absolute: " + jarPath);
        }
        if (!jar.equals(jar.normalize())) {
            throw invalid("the jar path must not contain '..' or '.' segments: " + jarPath);
        }
        if (Files.isDirectory(jar)) {
            throw invalid("the jar path is a directory: " + jarPath);
        }
        if (!Files.isRegularFile(jar)) {
            throw invalid("the jar does not exist: " + jarPath);
        }
        if (!Files.isReadable(jar)) {
            throw invalid("the jar is not readable: " + jarPath);
        }
        requireNotWritableByOthers(jar);
        return jar;
    }

    /** The code of the jar runs inside the process: on a POSIX file system others must not be able to replace it. */
    private static void requireNotWritableByOthers(Path jar) {
        if (!jar.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(jar);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw invalid("the jar is writable by its group or by others: " + jar);
            }
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("cannot read the permissions of " + jar), e);
        }
    }

    private static Sha256 digestOf(Path jar) {
        try {
            return Sha256.ofBytes(Files.readAllBytes(jar));
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of("cannot read " + jar), e);
        }
    }

    private static RegisteredDriver instantiate(Path jar, String className) {
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            if (archive.size() == 0) {
                throw invalid("the file is an empty archive: " + jar);
            }
        } catch (ZipException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("the file is not a jar archive: " + jar), e);
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of("cannot read " + jar), e);
        }
        URLClassLoader loader = null;
        try {
            URL url = jar.toUri().toURL();
            loader = new URLClassLoader(new URL[] {url}, ClassLoader.getPlatformClassLoader());
            Class<?> type = Class.forName(className, false, loader);
            if (!Driver.class.isAssignableFrom(type)) {
                throw invalid("class " + className + " does not implement java.sql.Driver");
            }
            Constructor<?> constructor = type.getConstructor();
            return new RegisteredDriver((Driver) constructor.newInstance(), loader);
        } catch (ClassNotFoundException e) {
            closeQuietly(loader, e);
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("class " + className + " is not in " + jar), e);
        } catch (NoSuchMethodException e) {
            closeQuietly(loader, e);
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("class " + className + " has no public no-argument constructor"), e);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException
                | LinkageError | MalformedURLException e) {
            closeQuietly(loader, e);
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("class " + className + " cannot be instantiated: " + e), e);
        } catch (InfrastructureException e) {
            closeQuietly(loader, e);
            throw e;
        }
    }

    private static void closeQuietly(URLClassLoader loader, Throwable cause) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException e) {
            cause.addSuppressed(e);
        }
    }

    private static InfrastructureException invalid(String reason) {
        return new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of(reason));
    }
}
