package org.pgledger.database.driver;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The checks of a driver registration that the admin web and the CLI share, so that both refuse exactly the same jars.
 * Checking a jar runs code of it ({@link DriverLoader#describe} loads the class and creates the driver), so the jar may
 * come from one allowed directory only: the path is resolved with {@link Path#toRealPath} (symbolic links and {@code ..}
 * resolved) and must be inside the real allowed directory ({@link Path#startsWith}) before any class is loaded. Then the
 * jar and the driver class are checked exactly as the products load them (absolute path, readable archive, not writable by
 * others, the class is a {@code java.sql.Driver} that accepts the configured JDBC URL) and the SHA-256 of the jar is taken.
 * Writing the registration (and the audit row of the owner's trail) is the business of the caller; this class changes
 * nothing.
 *
 * <p>A jar path that contains a dollar sign followed by a left brace is refused: Spring would resolve it as a property
 * placeholder when the registration file is read back through the configuration.
 *
 * <p>Every refusal about the jar or the class (not in the allowed directory, missing, unreadable, not an archive, wrong
 * class, any failure of the loader) is the one {@link #REFUSED_REASON}, so that a web client learns nothing about which
 * files exist or why one is refused. The specific reason goes to the server log only.
 *
 * <p>Standard API: {@link Path#toRealPath} and {@link Path#startsWith}
 * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Path.html).
 */
public final class DriverRegistrar {

    /** The one reason a client gets for a jar or class that cannot be used, whatever the cause is. */
    public static final String REFUSED_REASON = "the jar or the class cannot be used for a driver";

    private static final Logger LOG = LoggerFactory.getLogger(DriverRegistrar.class);

    /** A Spring property placeholder start. */
    private static final String PLACEHOLDER_START = "${";

    /** Control characters of user input are not written to the log as they are (they would forge log lines). */
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cntrl}");

    private final DriverLoader loader;
    private final String jdbcUrl;
    private final Path allowedDirectory;

    /**
     * @param loader           the loader that loads the class of a jar to check it
     * @param jdbcUrl          the configured JDBC URL the driver has to accept (may be absent when none is configured)
     * @param allowedDirectory the one directory a driver jar must be in
     */
    public DriverRegistrar(DriverLoader loader, String jdbcUrl, Path allowedDirectory) {
        this.loader = loader;
        this.jdbcUrl = jdbcUrl;
        this.allowedDirectory = allowedDirectory;
    }

    /**
     * Checks the jar and the class and describes the registration (nothing is stored).
     *
     * @throws InfrastructureException DRIVER_INVALID with the jar or the class missing (naming the field), or with
     *         {@link #REFUSED_REASON} when the jar or the class cannot be used
     */
    public DriverRegistration check(String jar, String className, Instant registeredAt) {
        if (jar == null || jar.isBlank()) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of("the jar path is missing"));
        }
        if (className == null || className.isBlank()) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("the driver class name is missing"));
        }
        Path real = insideTheAllowedDirectory(jar.strip());
        try {
            return loader.describe(real, className.strip(), jdbcUrl, registeredAt);
        } catch (InfrastructureException e) {
            LOG.warn("the driver registration of {} was refused by the loader", printable(real.toString()), e);
            throw refused();
        }
    }

    /** Text that is safe for a log line (control characters replaced). */
    public static String printable(String text) {
        return CONTROL_CHARACTER.matcher(text).replaceAll("?");
    }

    /**
     * The real path of the jar, which is inside the real allowed directory.
     *
     * @throws InfrastructureException DRIVER_INVALID with {@link #REFUSED_REASON} otherwise
     */
    private Path insideTheAllowedDirectory(String jar) {
        if (jar.contains(PLACEHOLDER_START)) {
            LOG.warn("the driver registration of {} was refused: the jar path contains a placeholder",
                    printable(jar));
            throw refused();
        }
        try {
            Path path = Path.of(jar);
            if (!path.isAbsolute()) {
                LOG.warn("the driver registration of {} was refused: the jar path is not absolute", printable(jar));
                throw refused();
            }
            Path real = path.toRealPath();
            Path allowed = allowedDirectory.toRealPath();
            if (!real.startsWith(allowed)) {
                LOG.warn("the driver registration of {} was refused: the jar is not inside the allowed directory {}",
                        printable(real.toString()), printable(allowed.toString()));
                throw refused();
            }
            if (real.toString().contains(PLACEHOLDER_START)) {
                LOG.warn("the driver registration of {} was refused: the real jar path contains a placeholder",
                        printable(real.toString()));
                throw refused();
            }
            return real;
        } catch (InvalidPathException | IOException e) {
            LOG.warn("the driver registration of {} was refused: the jar or the allowed directory cannot be resolved",
                    printable(jar), e);
            throw refused();
        }
    }

    private static InfrastructureException refused() {
        return new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of(REFUSED_REASON));
    }
}
