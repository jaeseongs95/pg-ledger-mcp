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
import org.pgledger.database.driver.DriverRegistrar;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.schema.SchemaVerifier;
import org.pgledger.domain.error.InfrastructureCode;
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
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
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
 * <p>When the ledger database can be reached and its schema is up to date the registration is also recorded in the
 * owner's audit trail as {@code cli:<process user>}; otherwise (no database yet, a schema that is not migrated or was dropped,
 * a pending upgrade) it is logged with the actor and the digest, the command says that the row is still to come, and
 * {@code migrate} and {@code setup} write it when the schema is ready ({@link PendingDriverAudit}). A database that cannot be
 * reached is DATABASE_FAILED. The file is
 * never written inside the transaction of the audit row ({@link DriverRegistrationFile#register}): the checks and the new
 * file come first, outside any transaction, the audit row is committed, and the old file is replaced after the commit. A
 * transaction that does not commit leaves the file as it was (DATABASE_FAILED); a replacement that fails after the commit
 * is DRIVER_FILE_FAILED, and the committed row documents the attempt. The option values are written {@code --name=value}
 * (see {@link CliOptions}); the option {@code class} names the driver class, it is no Spring property.
 */
@Component
public class DriverRegisterCommand implements Command {

    static final String JAR = "jar";
    static final String CLASS = "class";

    private static final Logger LOG = LoggerFactory.getLogger(DriverRegisterCommand.class);

    private final DriverRegistrar registrar;
    private final DriverRegistrationFile file;
    private final Clock clock;
    private final CliActor actor;
    private final ObjectProvider<OwnerAuditWriter> audit;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ObjectProvider<SchemaVerifier> schema;
    private final CliOutput output;
    private final CliMessages messages;

    DriverRegisterCommand(DriverRegistrar registrar, DriverRegistrationFile file, Clock clock, CliActor actor,
            ObjectProvider<OwnerAuditWriter> audit, ObjectProvider<PlatformTransactionManager> transactions,
            ObjectProvider<SchemaVerifier> schema, CliOutput output, CliMessages messages) {
        this.registrar = registrar;
        this.file = file;
        this.clock = clock;
        this.actor = actor;
        this.audit = audit;
        this.transactions = transactions;
        this.schema = schema;
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
            registration = registrar.check(jar, className, clock.instant());
        } catch (InfrastructureException refused) {
            throw new FailureException(ErrorCode.DRIVER_INVALID, refused, refused.args().getFirst());
        }
        boolean rowWritten;
        try {
            rowWritten = register(registration, owner);
        } catch (InfrastructureException failure) {
            throw new FailureException(ErrorCode.DRIVER_FILE_FAILED, failure, file.path().toString());
        } catch (DataAccessException | TransactionException failure) {
            throw new FailureException(ErrorCode.DATABASE_FAILED, failure);
        }
        output.info(messages.get("driver-register.registered", registration.className(),
                registration.jar().toString(), registration.sha256(), file.path().toString()));
        if (!rowWritten) {
            output.info(messages.get("driver-register.pending"));
        }
    }

    /**
     * The audit row and the file, in the order of {@link DriverRegistrationFile#register}; this runs in no transaction of its
     * own (the call opens it). Without a database, or while the schema of the database is not up to date (not migrated, dropped,
     * an upgrade pending: the audit table is not there or is not the one of this version), there is no row to write yet: a log
     * line stands in for it, the file is replaced at once, and {@code migrate} and {@code setup} write the row when the schema is
     * ready ({@link PendingDriverAudit}). A database that cannot be reached is no such case: it is DATABASE_FAILED.
     *
     * @return whether the row of the audit trail was written
     */
    private boolean register(DriverRegistration registration, OwnerIdentity owner) {
        OwnerAuditWriter writer = audit.getIfAvailable();
        PlatformTransactionManager manager = transactions.getIfAvailable();
        String detail = PendingDriverAudit.detail(registration.jar().toString(), registration.sha256());
        if (writer == null || manager == null) {
            file.register(registration, TransactionOperations.withoutTransaction(), () -> LOG.info(
                    "driver registered by {}: {} {} (no ledger database is configured, so there is no row of the owner's "
                            + "audit trail; migrate or setup records it)", owner.value(), registration.className(),
                    DriverRegistrar.printable(detail)));
            return false;
        }
        SchemaVerifier verifier = schema.getIfAvailable();
        if (verifier != null) {
            try {
                verifier.verify();
            } catch (InfrastructureException notReady) {
                // only a schema that is not there yet (not migrated, dropped) or that waits for a file is the case that
                // migrate and setup repair; a database that cannot be reached is a failure, and so is a schema that is
                // broken (a changed file, a failed attempt): the refusal says which, as every other command does
                if (notReady.code() == InfrastructureCode.SCHEMA_DATABASE_UNREACHABLE
                        || notReady.code() == InfrastructureCode.INTERNAL_ERROR) {
                    throw new FailureException(ErrorCode.DATABASE_FAILED, notReady);
                }
                if (notReady.code() != InfrastructureCode.SCHEMA_HISTORY_MISSING
                        && notReady.code() != InfrastructureCode.SCHEMA_PENDING) {
                    throw new FailureException(ErrorCode.SCHEMA_NOT_READY, notReady, notReady.getMessage());
                }
                file.register(registration, TransactionOperations.withoutTransaction(), () -> LOG.info(
                        "driver registered by {}: {} {} (the schema of the ledger database is not up to date, {}; migrate or "
                                + "setup records the row of the owner's audit trail)", owner.value(),
                        registration.className(), DriverRegistrar.printable(detail), notReady.code()));
                return false;
            }
        }
        file.register(registration, new TransactionTemplate(manager),
                () -> writer.record(owner, OwnerAction.DRIVER_REGISTERED, registration.className(), detail));
        return true;
    }
}
