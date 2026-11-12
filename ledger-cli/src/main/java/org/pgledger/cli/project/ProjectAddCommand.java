package org.pgledger.cli.project;

import java.util.List;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.cli.database.DatabaseBeans;
import org.pgledger.domain.model.Project;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code project-add <id> <code> <title>}: adds a project through {@code ProjectService}, the service the admin web uses; the
 * row is stamped {@code user:owner} and the owner's audit trail records {@code cli:<process user>}. The ledger validates the
 * id, the code and the title and refuses a duplicate.
 */
@Component
public class ProjectAddCommand implements Command {

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    ProjectAddCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.PROJECT_ADD;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.size() < 3) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 3) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        CliLedger target = DatabaseBeans.require(ledger);
        Project added = target.call(() -> target.projects()
                .add(new ProjectCommand(operands.get(0), operands.get(1), operands.get(2)), target.actor()));
        output.info(messages.get("project-add.added", added.id().value(), added.code().value(), added.title()));
    }
}
