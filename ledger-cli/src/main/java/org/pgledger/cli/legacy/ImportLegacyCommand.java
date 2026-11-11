package org.pgledger.cli.legacy;

import java.util.List;
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
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
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
 * never printed). The command is refused when the new schema already holds ledger data; the grants are copied without a
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

    /** The arguments of a failed comparison come in groups: table, legacy rows, new rows, legacy digest, new digest. */
    private static final int MISMATCH_GROUP = 5;

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
        return Set.of(VERIFY_ONLY, OWNER_ACCOUNT);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        boolean verifyOnly = CliOptions.flag(arguments, VERIFY_ONLY);
        // the account itself is read before the context starts (OwnerAccountEnvironmentPostProcessor); a blank one is refused here
        CliOptions.value(arguments, OWNER_ACCOUNT);
        CliLedger target = ledger.getIfAvailable();
        if (target == null) {
            throw new FailureException(ErrorCode.DRIVER_NOT_CONFIGURED);
        }
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

    /** Runs the service; each refusal it knows becomes the refusal of this command, any other one is left to the door. */
    private ImportReport execute(CliLedger target, LegacyImportService importer, boolean verifyOnly) {
        try {
            return verifyOnly ? importer.verify() : importer.importLegacy(target.actor());
        } catch (ConflictException refused) {
            if (refused.code() == ConflictCode.DUPLICATE_KEY) {
                throw new FailureException(ErrorCode.IMPORT_TARGET_NOT_EMPTY, refused, String.join(", ",
                        refused.args()));
            }
            throw refused;
        } catch (LookupException refused) {
            if (refused.code() == LookupCode.NOT_FOUND && refused.args().size() > 1) {
                throw new FailureException(ErrorCode.IMPORT_SOURCE_INCOMPLETE, refused, refused.args().get(1));
            }
            throw refused;
        } catch (ValidationException refused) {
            for (ValidationIssue issue : refused.issues()) {
                output.info(messages.get("import-legacy.violation", issue.path(), reasonText(issue)));
            }
            throw new FailureException(ErrorCode.IMPORT_DATA_REFUSED, refused, String.valueOf(refused.issues().size()));
        } catch (InfrastructureException refused) {
            if (refused.code() == InfrastructureCode.INTEGRITY_VIOLATION && refused.args().size() >= MISMATCH_GROUP) {
                List<String> all = refused.args();
                for (int from = 0; from + MISMATCH_GROUP <= all.size(); from += MISMATCH_GROUP) {
                    output.info(messages.get("import-legacy.mismatch",
                            all.subList(from, from + MISMATCH_GROUP).toArray(String[]::new)));
                }
                throw new FailureException(ErrorCode.IMPORT_VERIFY_FAILED, refused,
                        all.subList(0, MISMATCH_GROUP).toArray(String[]::new));
            }
            throw refused;
        }
    }

    /** The reason of the issue by name, with its arguments (a limit, a format): enough to find and correct the row. */
    private static String reasonText(ValidationIssue issue) {
        return issue.reasonArgs().isEmpty() ? issue.reason().name()
                : issue.reason().name() + " " + String.join(" ", issue.reasonArgs());
    }
}
