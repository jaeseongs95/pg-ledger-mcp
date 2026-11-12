package org.pgledger.cli.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.admin.CommonCodeService.CodeResult;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.OperatorStepException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.cli.database.DatabaseBeans;
import org.pgledger.cli.schema.SchemaMigration;
import org.pgledger.cli.seed.PackagedSeed;
import org.pgledger.database.schema.ApplicationAccountAccess;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.value.Tokens;
import org.pgledger.seed.SeedCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code setup [--owner-account=<account>] [--app-account=<account>] [--stdin]}: the first setup of the ledger database, and
 * what an operator runs after every upgrade as well. In this order:
 *
 * <ol>
 * <li>the migration of the schema, the path of {@code migrate} with its three options ({@link SchemaMigration}: the schema
 * files and the history, the grants of the application account);</li>
 * <li>the check that the application account can use the schema ({@link ApplicationAccountAccess}). The privilege that lets an
 * account use a schema is a statement of the database system, not ANSI SQL, so it is not in the product SQL: when it is
 * missing the command says in a generic sentence that an operator has to give it, and ends with exit code 3
 * ({@link OperatorStepException}) before anything is added;</li>
 * <li>the codes of the seed bundle ({@link PackagedSeed}) that the ledger does not have, added through the service of
 * {@code code-set} and of the admin web, so the code revision and the owner's audit trail ({@code cli:<process user>}) are kept;
 * a code that exists is never changed (a code the owner changed or retired stays as he left it);</li>
 * <li>the comparison of the seed with the database by counts and by digest ({@link SeedReport}).</li>
 * </ol>
 *
 * Every step can be repeated: a run that stopped at the grants, at the check or in the middle of the codes is finished by the
 * next one, and a run on a complete installation changes nothing and prints the same report.
 */
@Component
public class SetupCommand implements Command {

    private final SchemaMigration migration;
    private final PackagedSeed seed;
    private final ObjectProvider<ApplicationAccountAccess> applicationAccount;
    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    SetupCommand(SchemaMigration migration, PackagedSeed seed, ObjectProvider<ApplicationAccountAccess> applicationAccount,
            ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.migration = migration;
        this.seed = seed;
        this.applicationAccount = applicationAccount;
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.SETUP;
    }

    @Override
    public Set<String> options() {
        return SchemaMigration.OPTIONS;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        Optional<String> appAccount = migration.appAccount(arguments);
        // a seed that cannot be read is found before the database is touched
        List<SeedCode> wanted = seed.codes();
        migration.migrate(appAccount);
        requireApplicationAccount();
        CliLedger target = DatabaseBeans.require(ledger);
        int added = 0;
        for (SeedCode code : wanted) {
            CodeCommand command = new CodeCommand(code.group(), code.code(), code.nameKo(), code.nameEn(),
                    code.descriptionKo(), code.descriptionEn(), code.sortOrder(), code.state());
            Optional<CodeResult> result = target.call(() -> target.codes().addIfAbsent(command, target.actor()));
            if (result.isPresent()) {
                added++;
            }
        }
        output.info(messages.get("setup.seeded", String.valueOf(added)));
        SeedReport report = SeedReport.compare(wanted, current(target, wanted));
        output.info(messages.get("setup.report", String.valueOf(report.seed()), String.valueOf(report.present()),
                String.valueOf(report.same()), String.valueOf(report.changed()), report.seedDigest(),
                report.databaseDigest()));
    }

    /** The check of the second step; the schema is migrated and nothing else has been done when it stops here. */
    private void requireApplicationAccount() {
        ApplicationAccountAccess access = DatabaseBeans.require(applicationAccount);
        if (!access.canUseSchema()) {
            throw new OperatorStepException(ErrorCode.APP_ACCOUNT_NO_ACCESS, access.account());
        }
    }

    /** The codes of the database in the groups the seed names. */
    private static List<SeedCode> current(CliLedger target, List<SeedCode> wanted) {
        List<SeedCode> database = new ArrayList<>();
        for (String name : wanted.stream().map(SeedCode::group).distinct().toList()) {
            CodeGroup group = target.call(() -> Tokens.parse("group", name, CodeGroup.values()));
            for (CommonCode code : target.call(() -> target.codes().list(group))) {
                database.add(SeedReport.of(code));
            }
        }
        return database;
    }
}
