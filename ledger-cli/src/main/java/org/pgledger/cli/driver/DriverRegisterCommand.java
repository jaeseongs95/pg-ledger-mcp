package org.pgledger.cli.driver;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliActor;
import org.pgledger.database.driver.DriverLoader;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code driver-register --jar=<path> --class=<class>}: registers the JDBC driver the owner supplies. It is the one command
 * that needs no database (a new installation has no driver). The checks are those of the admin web driver page
 * ({@link DriverRegistrar}): the jar must be inside the allowed directory ({@code ledger.cli.driver.directory}), readable,
 * owned and writable only by trusted accounts, and the class must be a {@code java.sql.Driver} that accepts the configured
 * JDBC URL ({@code ledger.jdbc.url}); then the SHA-256 of the jar is taken and the registration file is replaced
 * atomically. A refused registration leaves the file as it was. As in the admin web, every refusal about the jar or the
 * class has the one fixed text and the specific reason goes to the log. The running products keep their pool: the new
 * driver is used after their next start.
 *
 * <p>When the ledger database can be reached (a driver was registered before) the registration is also recorded in the
 * owner's audit trail as {@code cli:<process user>}, in the transaction that replaces the file; otherwise it is logged
 * with the actor and the digest. The option values are written {@code --name=value} (see {@link CliOptions}); the option
 * {@code class} names the driver class, it is no Spring property.
 */
@Component
public class DriverRegisterCommand implements Command {

    static final String JAR = "jar";
    static final String CLASS = "class";

    private static final Logger LOG = LoggerFactory.getLogger(DriverRegisterCommand.class);

    private final DriverProperties properties;
    private final LedgerJdbcProperties jdbc;
    private final DriverRegistrationFile file;
    private final Clock clock;
    private final CliActor actor;
    private final ObjectProvider<OwnerAuditWriter> audit;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final CliOutput output;
    private final CliMessages messages;

    DriverRegisterCommand(DriverProperties properties, LedgerJdbcProperties jdbc, DriverRegistrationFile file,
            ObjectProvider<Clock> clock, CliActor actor, ObjectProvider<OwnerAuditWriter> audit,
            ObjectProvider<PlatformTransactionManager> transactions, CliOutput output, CliMessages messages) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.file = file;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.actor = actor;
        this.audit = audit;
        this.transactions = transactions;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.DRIVER_REGISTER;
    }

    @Override
    public Set<String> options() {
        return Set.of(JAR, CLASS);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        String jar = CliOptions.required(arguments, JAR);
        String className = CliOptions.required(arguments, CLASS);
        OwnerIdentity owner = actor.current();
        DriverRegistration registration;
        try {
            // the loader has no state and no dependency; it is made here until the registrar becomes a bean (unit W4)
            registration = new DriverRegistrar(new DriverLoader(), jdbc.url(), properties.directory()).check(jar,
                    className, clock.instant());
        } catch (InfrastructureException refused) {
            throw new FailureException(ErrorCode.DRIVER_INVALID, refused, refused.args().getFirst());
        }
        try {
            write(registration, owner);
        } catch (InfrastructureException failure) {
            throw new FailureException(ErrorCode.DRIVER_FILE_FAILED, failure, file.path().toString());
        } catch (DataAccessException failure) {
            throw new FailureException(ErrorCode.DATABASE_FAILED, failure);
        }
        output.info(messages.get("driver-register.registered", registration.className(),
                registration.jar().toString(), registration.sha256(), file.path().toString()));
    }

    /** The audit row and the file in one transaction when there is a database; the file and a log line otherwise. */
    private void write(DriverRegistration registration, OwnerIdentity owner) {
        OwnerAuditWriter writer = audit.getIfAvailable();
        PlatformTransactionManager manager = transactions.getIfAvailable();
        String detail = "jar=" + registration.jar() + " sha256=" + registration.sha256();
        if (writer == null || manager == null) {
            LOG.info("driver registered by {}: {} {} (no ledger database is configured, so there is no row of the owner's "
                    + "audit trail)", owner.value(), registration.className(), DriverRegistrar.printable(detail));
            file.write(registration);
            return;
        }
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            writer.record(owner, OwnerAction.DRIVER_REGISTERED, registration.className(), detail);
            file.write(registration);
        });
    }
}
