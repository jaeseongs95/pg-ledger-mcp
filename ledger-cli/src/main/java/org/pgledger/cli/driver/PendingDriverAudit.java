package org.pgledger.cli.driver;

import java.util.Optional;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.cli.database.CliActor;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.repository.OwnerAuditRepository;
import org.pgledger.domain.rule.OwnerAction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit row of a driver registration that was made while the ledger schema was not up to date (the first registration of an
 * installation, which has no database yet, or a registration on a database whose schema was dropped or not migrated yet):
 * {@code driver-register} writes the registration file and leaves the row to {@code migrate} and {@code setup}, which call
 * {@link #recordIfMissing()} once the schema is ready. The registration file names the driver (class, jar, digest, time), and the
 * audit trail tells whether a row for exactly that registration is there, so a repeated call writes nothing. The row names the
 * actor of the run that records it (the account the installer runs as, {@code cli:<user>}) and the time of the registration
 * in its detail.
 *
 * <p>It exists only when the CLI has its database, like {@code CliLedger} (see {@code CliLedgerConfiguration}).
 */
public final class PendingDriverAudit {

    private final DriverRegistrationFile file;
    private final OwnerAuditWriter writer;
    private final OwnerAuditRepository trail;
    private final PlatformTransactionManager transactions;
    private final CliActor actor;

    public PendingDriverAudit(DriverRegistrationFile file, OwnerAuditWriter writer, OwnerAuditRepository trail,
            PlatformTransactionManager transactions, CliActor actor) {
        this.file = file;
        this.writer = writer;
        this.trail = trail;
        this.transactions = transactions;
        this.actor = actor;
    }

    /**
     * Writes the row of the registered driver when the audit trail has none for it.
     *
     * @return the class of the driver when a row was written, empty when there is no registration or its row is there
     */
    public Optional<String> recordIfMissing() {
        return file.read().flatMap(entry -> {
            String known = detail(entry.jar(), entry.sha256());
            Boolean written = new TransactionTemplate(transactions).execute(status -> {
                boolean present = trail.findBySubjectOrderById(entry.className()).stream()
                        .anyMatch(row -> row.operation() == OwnerAction.DRIVER_REGISTERED && row.detail() != null
                                && row.detail().startsWith(known));
                if (present) {
                    return false;
                }
                writer.record(actor.current(), OwnerAction.DRIVER_REGISTERED, entry.className(),
                        known + " registered_at=" + entry.registeredAt());
                return true;
            });
            return Boolean.TRUE.equals(written) ? Optional.of(entry.className()) : Optional.empty();
        });
    }

    /** The detail every row of a registration starts with: the jar and the digest (the time follows when it is recorded later). */
    static String detail(String jar, String sha256) {
        return "jar=" + jar + " sha256=" + sha256;
    }
}
