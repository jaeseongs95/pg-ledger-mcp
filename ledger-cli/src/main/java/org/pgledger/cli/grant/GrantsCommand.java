package org.pgledger.cli.grant;

import java.util.List;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.domain.model.SessionGrant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code grants [<author>]}: lists the live grants (not revoked, not expired) of one author or of every author, ordered by
 * author, project and scope: one line each with the author, the project, the scope, the expiry (UTC, ending in
 * {@code Z}, or "never") and the note.
 */
@Component
public class GrantsCommand implements Command {

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    GrantsCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.GRANTS;
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
        List<SessionGrant> live = target.call(() -> operands.isEmpty() ? target.grants().liveAll()
                : target.grants().live(operands.getFirst()));
        if (live.isEmpty()) {
            output.info(messages.get("grants.none"));
            return;
        }
        for (SessionGrant grant : live) {
            output.info(messages.get("grants.line", grant.authorId().value(), grant.projectId().value(),
                    grant.scope().token(), grant.expiresAt() == null ? messages.get("grant.never")
                            : grant.expiresAt().toString(),
                    grant.note() == null ? "" : grant.note()));
        }
    }
}
