package org.pgledger.admin.setup;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.database.driver.DriverRegistrar;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.OwnerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers the JDBC driver the user supplies. Checking a jar runs code of it ({@link DriverRegistrar} loads the
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
    public static final String REFUSED_REASON = DriverRegistrar.REFUSED_REASON;

    private static final Logger LOG = LoggerFactory.getLogger(DriverRegistrationService.class);

    /** The driver in use, the registered one, and whether they differ (the products have to be restarted). */
    public record State(DriverRegistration effective, Optional<DriverRegistrationFile.Entry> registered,
            boolean restartRequired) {
    }

    private final DriverRegistrationFile file;
    private final LedgerJdbcProperties jdbc;
    private final Clock clock;
    private final LedgerAdminProperties admin;
    private final ObjectProvider<OwnerAuditWriter> audit;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    /**
     * @param audit        the writer of the owner's audit trail; absent in setup mode, where there is no database
     * @param transactions the transaction manager of that database; absent in setup mode
     */
    public DriverRegistrationService(DriverRegistrationFile file, LedgerJdbcProperties jdbc, Clock clock,
            LedgerAdminProperties admin, ObjectProvider<OwnerAuditWriter> audit,
            ObjectProvider<PlatformTransactionManager> transactions) {
        this.file = file;
        this.jdbc = jdbc;
        this.clock = clock;
        this.admin = admin;
        this.audit = audit;
        this.transactions = transactions;
    }

    /**
     * Registers the driver for the signed-in owner: after the checks, the audit row of the owner's trail and the
     * replacement of the registration file are one unit (the file is written inside the transaction of the row, so a
     * file that cannot be written leaves no row; a row that cannot be written leaves the file as it was). In setup mode
     * there is no database and so no trail: the registration is logged at WARN with the owner and the digest instead.
     *
     * @param actor the owner who registers it ({@code token:owner}, or the OpenID Connect identity)
     * @throws InfrastructureException DRIVER_INVALID with the jar or the class missing from the form (naming the
     *         field), or with {@link #REFUSED_REASON} when the jar or the class cannot be used
     */
    public DriverRegistration register(String jar, String className, OwnerIdentity actor) {
        DriverRegistration registration = new DriverRegistrar(jdbc.url(), admin.driverDirectory()).check(jar, className,
                clock.instant());
        write(registration, actor);
        return registration;
    }

    /** The audit row and the file in one transaction when there is a database; the file and a log line otherwise. */
    private void write(DriverRegistration registration, OwnerIdentity actor) {
        OwnerAuditWriter writer = audit.getIfAvailable();
        PlatformTransactionManager manager = transactions.getIfAvailable();
        String detail = "jar=" + registration.jar() + " sha256=" + registration.sha256();
        if (writer == null || manager == null) {
            LOG.warn("driver registered by {}: {} {} (setup mode has no database, so there is no row of the owner's audit "
                    + "trail)", actor.value(), registration.className(), DriverRegistrar.printable(detail));
            file.write(registration);
            return;
        }
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            writer.record(actor, OwnerAction.DRIVER_REGISTERED, registration.className(), detail);
            file.write(registration);
        });
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
