package org.pgledger.cli.code;

import java.util.Arrays;
import java.util.List;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.value.Tokens;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code codes [<group>]}: lists the common codes of one group, or of every group, one line each: group, code, state, sort
 * order and the names in both languages (the texts as stored).
 */
@Component
public class CodesCommand implements Command {

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    CodesCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.CODES;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        CliLedger target = ledger.getIfAvailable();
        if (target == null) {
            throw new FailureException(ErrorCode.DRIVER_NOT_CONFIGURED);
        }
        List<CodeGroup> groups = target.call(() -> operands.isEmpty() ? Arrays.asList(CodeGroup.values())
                : List.of(Tokens.parse("group", operands.getFirst(), CodeGroup.values())));
        for (CodeGroup group : groups) {
            List<CommonCode> codes = target.call(() -> target.codes().list(group));
            for (CommonCode code : codes) {
                output.info(messages.get("codes.line", group.name(), code.code().value(), code.state().name(),
                        String.valueOf(code.sortOrder()), code.nameKo(), code.nameEn()));
            }
        }
    }
}
