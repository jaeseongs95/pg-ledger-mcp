package org.pgledger.mcp.error;

import java.util.List;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The one place that turns a failure of a tool call into the error of the tool result: the business failures of
 * the sealed {@link LedgerException} hierarchy (a complete {@code switch}) and, for everything else, an
 * INTEGRITY_VIOLATION or INTERNAL_ERROR that carries no text of the original failure (no SQL, no class names).
 * The English message comes from the message bundles. A failure that is a defect or an outage is logged here,
 * with its cause, and nowhere else.
 */
@Component
public class ToolErrorMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ToolErrorMapper.class);

    private final MessageSourceAccessor messages;

    public ToolErrorMapper(MessageSourceAccessor modelMessages) {
        this.messages = modelMessages;
    }

    public ToolError map(RuntimeException failure) {
        return switch (failure) {
            case LedgerException ledger -> ledger(ledger);
            case DataIntegrityViolationException integrity ->
                ledger(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, List.of(), integrity));
            default -> ledger(new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), failure));
        };
    }

    private ToolError ledger(LedgerException failure) {
        if (!(failure instanceof InfrastructureException)) {
            LOG.debug("a tool call was refused with {}", failure.code().token(), failure);
        }
        return switch (failure) {
            case ValidationException invalid -> validation(invalid);
            case TrustException trust -> plain(trust, trust.args());
            case TransitionException transition -> plain(transition, transition.args());
            case ConflictException conflict -> plain(conflict, conflict.args());
            case LookupException lookup -> plain(lookup, lookup.args());
            case AdminException admin -> plain(admin, admin.args());
            case InfrastructureException infrastructure -> infrastructure(infrastructure);
        };
    }

    private ToolError validation(ValidationException failure) {
        List<ToolError.Issue> issues = failure.issues().stream()
                .map(issue -> new ToolError.Issue(issue.path(), reason(issue), issue.allowed())).toList();
        List<String> args = failure.code() == ValidationCode.VALIDATION_FAILED
                ? List.of(failure.issues().getFirst().path(), reason(failure.issues().getFirst()))
                : failure.args();
        return plain(failure, args, issues);
    }

    private ToolError infrastructure(InfrastructureException failure) {
        LOG.error("a tool call failed with {}", failure.code().token(), failure);
        return plain(failure, failure.args());
    }

    private ToolError plain(LedgerException failure, List<String> args) {
        return plain(failure, args, List.of());
    }

    private ToolError plain(LedgerException failure, List<String> args, List<ToolError.Issue> issues) {
        MessageSourceResolvable resolvable = new DefaultMessageSourceResolvable(
                new String[] {failure.code().messageKey()}, args.toArray(), failure.code().token());
        return new ToolError(failure.code().token(), messages.getMessage(resolvable), failure.details(), issues);
    }

    private String reason(ValidationIssue issue) {
        MessageSourceResolvable resolvable = new DefaultMessageSourceResolvable(
                new String[] {issue.reason().messageKey()}, issue.reasonArgs().toArray(), issue.reason().name());
        return messages.getMessage(resolvable);
    }
}
