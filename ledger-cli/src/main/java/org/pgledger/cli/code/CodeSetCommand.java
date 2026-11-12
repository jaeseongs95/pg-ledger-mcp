package org.pgledger.cli.code;

import java.util.List;
import java.util.Set;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.admin.CommonCodeService.CodeResult;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.cli.database.DatabaseBeans;
import org.pgledger.domain.rule.CodeState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>]
 * [--inactive]}: adds a common code or updates it through {@code CommonCodeService}, the service the admin web uses. The
 * texts are taken exactly as given (quotes, line breaks, backslashes and text that looks like a placeholder are data). The
 * English texts fall back to the Korean ones; without {@code --sort} an existing code keeps its position and a new one gets
 * the default; the code is ACTIVE unless {@code --inactive} is given. Setting a code to what it already is changes nothing.
 * A code is never deleted or renamed. The owner's audit trail records {@code cli:<process user>}.
 */
@Component
public class CodeSetCommand implements Command {

    static final String NAME_EN = "name-en";
    static final String DESCRIPTION_EN = "description-en";
    static final String SORT = "sort";
    static final String INACTIVE = "inactive";

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    CodeSetCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.CODE_SET;
    }

    @Override
    public Set<String> options() {
        return Set.of(NAME_EN, DESCRIPTION_EN, SORT, INACTIVE);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.size() < 3) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 4) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        String descriptionKo = operands.size() == 4 ? operands.get(3) : null;
        String nameEn = CliOptions.valueOrNull(arguments, NAME_EN);
        String descriptionEn = CliOptions.valueOrNull(arguments, DESCRIPTION_EN);
        // an absent --sort is null for the service (an existing code keeps its position, a new one gets the default); a
        // value that is no number is INVALID_OPTION_VALUE with the number format failure as its cause
        Integer sort = CliOptions.integerOrNull(arguments, SORT);
        CodeState state = CliOptions.flag(arguments, INACTIVE) ? CodeState.INACTIVE : CodeState.ACTIVE;
        CliLedger target = DatabaseBeans.require(ledger);
        CodeCommand command = new CodeCommand(operands.get(0), operands.get(1), operands.get(2), nameEn, descriptionKo,
                descriptionEn, sort, state.name());
        CodeResult result = target.call(() -> target.codes().set(command, target.actor()));
        output.info(messages.get("code-set.set", result.code().group().name(), result.code().code().value(),
                result.code().state().name(), String.valueOf(result.code().sortOrder()),
                String.valueOf(result.revision())));
    }
}
