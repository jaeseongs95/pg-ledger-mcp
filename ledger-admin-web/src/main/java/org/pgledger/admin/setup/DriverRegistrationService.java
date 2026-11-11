package org.pgledger.admin.setup;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.stereotype.Service;

/**
 * Registers the JDBC driver the user supplies: the jar and the driver class are checked exactly as the products load
 * them ({@link DriverLoader#describe}: absolute path, readable archive, not writable by others, the class is a
 * {@code java.sql.Driver} that accepts the configured JDBC URL), the SHA-256 of the jar is taken, and only then the
 * registration file is replaced. A refused registration leaves the file as it was. The running pool does not change:
 * the new driver is used after the next start.
 */
@Service
public class DriverRegistrationService {

    /** The driver in use, the registered one, and whether they differ (the products have to be restarted). */
    public record State(DriverRegistration effective, Optional<DriverRegistrationFile.Entry> registered,
            boolean restartRequired) {
    }

    private final DriverRegistrationFile file;
    private final LedgerJdbcProperties jdbc;
    private final Clock clock;

    public DriverRegistrationService(DriverRegistrationFile file, LedgerJdbcProperties jdbc, Clock clock) {
        this.file = file;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @throws InfrastructureException DRIVER_INVALID, naming the reason, when the jar or the class cannot be used
     */
    public DriverRegistration register(String jar, String className) {
        if (jar == null || jar.isBlank()) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of("the jar path is missing"));
        }
        if (className == null || className.isBlank()) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("the driver class name is missing"));
        }
        Path path;
        try {
            path = Path.of(jar.strip());
        } catch (InvalidPathException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("the jar path is not a path"), e);
        }
        DriverRegistration registration = DriverLoader.describe(path, className.strip(), jdbc.url(), clock.instant());
        file.write(registration);
        return registration;
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
