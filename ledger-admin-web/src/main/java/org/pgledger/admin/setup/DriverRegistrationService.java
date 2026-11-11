package org.pgledger.admin.setup;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registers the JDBC driver the user supplies. Checking a jar runs code of it ({@link DriverLoader#describe} loads the
 * class and creates the driver), so the jar may come from one directory only, {@code ledger.admin.driver-directory}:
 * the path is resolved with {@link Path#toRealPath} (symbolic links and {@code ..} resolved) and must be inside the real
 * allowed directory ({@link Path#startsWith}) before any class is loaded. Then the jar and the driver class are checked
 * exactly as the products load them (absolute path, readable archive, not writable by others, the class is a
 * {@code java.sql.Driver} that accepts the configured JDBC URL), the SHA-256 of the jar is taken, and only then the
 * registration file is replaced. A refused registration leaves the file as it was. The running pool does not change:
 * the new driver is used after the next start.
 *
 * <p>A jar path that contains a dollar sign followed by a left brace is refused: Spring would resolve it as a property
 * placeholder when the registration file is read back through the configuration.
 *
 * <p>Every refusal about the jar or the class (not in the allowed directory, missing, unreadable, not an archive, wrong
 * class, any failure of the loader) is the one {@link #REFUSED_REASON}, so that the web client learns nothing about
 * which files exist or why one is refused. The specific reason goes to the server log only.
 *
 * <p>Standard API: {@link Path#toRealPath} and {@link Path#startsWith}
 * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Path.html).
 */
@Service
public class DriverRegistrationService {

    /** The one reason the client gets for a jar or class that cannot be used, whatever the cause is. */
    public static final String REFUSED_REASON = "the jar or the class cannot be used for a driver";

    private static final Logger LOG = LoggerFactory.getLogger(DriverRegistrationService.class);

    /** A Spring property placeholder start. */
    private static final String PLACEHOLDER_START = "${";

    /** Control characters of user input are not written to the log as they are (they would forge log lines). */
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cntrl}");

    /** The driver in use, the registered one, and whether they differ (the products have to be restarted). */
    public record State(DriverRegistration effective, Optional<DriverRegistrationFile.Entry> registered,
            boolean restartRequired) {
    }

    private final DriverRegistrationFile file;
    private final LedgerJdbcProperties jdbc;
    private final Clock clock;
    private final LedgerAdminProperties admin;

    public DriverRegistrationService(DriverRegistrationFile file, LedgerJdbcProperties jdbc, Clock clock,
            LedgerAdminProperties admin) {
        this.file = file;
        this.jdbc = jdbc;
        this.clock = clock;
        this.admin = admin;
    }

    /**
     * @throws InfrastructureException DRIVER_INVALID with the jar or the class missing from the form (naming the
     *         field), or with {@link #REFUSED_REASON} when the jar or the class cannot be used
     */
    public DriverRegistration register(String jar, String className) {
        if (jar == null || jar.isBlank()) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of("the jar path is missing"));
        }
        if (className == null || className.isBlank()) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("the driver class name is missing"));
        }
        Path real = insideTheAllowedDirectory(jar.strip());
        DriverRegistration registration;
        try {
            registration = DriverLoader.describe(real, className.strip(), jdbc.url(), clock.instant());
        } catch (InfrastructureException e) {
            LOG.warn("the driver registration of {} was refused by the loader", printable(real.toString()), e);
            throw refused();
        }
        file.write(registration);
        return registration;
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
            Path allowed = admin.driverDirectory().toRealPath();
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

    private static String printable(String text) {
        return CONTROL_CHARACTER.matcher(text).replaceAll("?");
    }

    public State state() {
        Optional<DriverRegistrationFile.Entry> registered = file.read();
        DriverRegistration effective = jdbc.driver();
        boolean restartRequired = registered.isPresent() && !sameDriver(registered.get(), effective);
        return new State(effective, registered, restartRequired);
    }

    private static boolean sameDriver(DriverRegistrationFile.Entry registered, DriverRegistration effective) {
        return effective.jar() != null && Objects.equals(registered.jar(), effective.jar().toString())
                && Objects.equals(registered.className(), effective.className())
                && Objects.equals(registered.sha256(), effective.sha256());
    }
}
