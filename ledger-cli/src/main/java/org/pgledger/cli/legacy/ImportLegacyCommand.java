package org.pgledger.cli.legacy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.pgledger.application.legacy.ImportReport;
import org.pgledger.application.legacy.LegacyImportService;
import org.pgledger.application.legacy.TableCheck;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.cli.database.DatabaseBeans;
import org.pgledger.cli.database.LedgerRefusals;
import org.pgledger.cli.database.LedgerRefusals.Context;
import org.pgledger.cli.database.LedgerRefusals.TableMismatch;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code import-legacy [--verify-only]}: copies the legacy ledger (the old schema {@code ledger}, read only) into the new
 * schema through {@link LegacyImportService} and prints, per table, the number of rows and the SHA-256 digest that the
 * legacy table and the new table share. The legacy connection is the settings {@code ledger.import.source.*} (the
 * password is the secret {@code ledger.import.source.password}, read from the secret store like every other secret and
 * never printed). The command is refused when the new schema holds more than {@code migrate} and {@code setup} made (ledger data, a session
 * key or the audit row of an earlier import); the grants are copied without a
 * session key, so each session has to have its key registered ({@code key-register}) before its grant works. The audit row
 * names {@code cli:<process user>}.
 *
 * <p>Every refusal of the service is a CLI refusal with its own code (exit 1): the data that cannot be imported as it is is
 * printed one value per line first ({@code table[key].column} and the reason), the differing tables of a failed comparison
 * as well. The work of the service is one transaction, so every refusal leaves nothing written. {@code --verify-only}
 * writes nothing at all: it repeats the validation and the comparison against what the new schema holds now.
 */
@Component
public class ImportLegacyCommand implements Command {

    static final String VERIFY_ONLY = "verify-only";
    /** The account the whole ledger is written as: the owner account, not the account of an application (INS-012). */
    static final String OWNER_ACCOUNT = "owner-account";
    /** Read by {@code OwnerAccountEnvironmentPostProcessor}: the password of the owner account is the next line of standard input. */
    static final String STDIN = "stdin";

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    ImportLegacyCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.IMPORT_LEGACY;
    }

    @Override
    public Set<String> options() {
        return Set.of(VERIFY_ONLY, OWNER_ACCOUNT, STDIN);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        boolean verifyOnly = CliOptions.flag(arguments, VERIFY_ONLY);
        // the account itself is read before the context starts (OwnerAccountEnvironmentPostProcessor); a blank one is refused here
        Optional<String> ownerAccount = CliOptions.value(arguments, OWNER_ACCOUNT);
        // --stdin gives the password of the owner account: with no such account it has no use
        if (CliOptions.flag(arguments, STDIN) && ownerAccount.isEmpty()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        CliLedger target = DatabaseBeans.require(ledger);
        LegacyImportService importer = target.importer();
        if (!importer.sourceConfigured()) {
            throw new FailureException(ErrorCode.IMPORT_SOURCE_NOT_CONFIGURED);
        }
        ImportReport report = target.call(() -> execute(target, importer, verifyOnly));
        for (TableCheck check : report.tables()) {
            output.info(messages.get("import-legacy.table", check.table().targetName(),
                    String.valueOf(check.targetRows()), check.targetDigest().hex()));
        }
        output.info(messages.get(verifyOnly ? "import-legacy.verified" : "import-legacy.imported",
                String.valueOf(report.rows()), String.valueOf(report.tables().size()), report.schema()));
    }

    /**
     * Runs the service; a refusal of the ledger becomes the refusal of this command ({@link LedgerRefusals}, context IMPORT),
     * after what the owner needs to find the rows is printed: the values that cannot be imported, one per line, or the tables
     * of a failed comparison.
     */
    private ImportReport execute(CliLedger target, LegacyImportService importer, boolean verifyOnly) {
        try {
            return verifyOnly ? importer.verify() : importer.importLegacy(target.actor());
        } catch (LedgerException refused) {
            report(refused);
            throw LedgerRefusals.translate(refused, Context.IMPORT);
        }
    }

    private void report(LedgerException refused) {
        switch (refused) {
            case ValidationException invalid -> {
                for (ValidationIssue issue : invalid.issues()) {
                    output.info(messages.get("import-legacy.violation", issue.path(), reasonText(issue)));
                }
            }
            case InfrastructureException failed -> {
                if (failed.code() == InfrastructureCode.INTEGRITY_VIOLATION) {
                    for (TableMismatch mismatch : TableMismatch.of(failed.args())) {
                        output.info(messages.get("import-legacy.mismatch", mismatch.texts()));
                    }
                }
            }
            case ConflictException _, LookupException _, TrustException _, TransitionException _, AdminException _ -> {
                // nothing more to show than the refusal itself
            }
        }
    }

    /** The reason of the issue by name, with its arguments (a limit, a format): enough to find and correct the row. */
    private static String reasonText(ValidationIssue issue) {
        return issue.reasonArgs().isEmpty() ? issue.reason().name()
                : issue.reason().name() + " " + String.join(" ", issue.reasonArgs());
    }
}
