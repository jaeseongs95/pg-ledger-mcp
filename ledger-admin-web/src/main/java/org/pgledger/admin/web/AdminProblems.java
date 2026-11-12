package org.pgledger.admin.web;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * The one place that turns a failure of the admin web into a problem detail (RFC 9457): the business failures of the
 * sealed {@link LedgerException} hierarchy by a complete {@code switch} (status by code, sentence from the message
 * bundles in the language of the request), and the HTTP-only refusal of an identity that is not the owner. A problem
 * carries the {@code code} token of the external contract; a server failure carries a {@code traceId} (also logged)
 * and never the text of the original failure.
 */
@Component
public class AdminProblems {

    /** The token of a signed-in identity that is not the owner (the owner list of the OIDC sign-in). */
    public static final String FORBIDDEN = "FORBIDDEN";

    private final MessageSource messages;

    public AdminProblems(MessageSource messages) {
        this.messages = messages;
    }

    public ProblemDetail problem(LedgerException failure, Locale locale) {
        HttpStatus status = status(failure.code());
        ProblemDetail problem = switch (failure) {
            case ValidationException invalid -> validation(invalid, locale);
            case TrustException trust -> plain(trust, trust.args(), locale);
            case TransitionException transition -> plain(transition, transition.args(), locale);
            case ConflictException conflict -> plain(conflict, conflict.args(), locale);
            case LookupException lookup -> plain(lookup, lookup.args(), locale);
            case AdminException admin -> plain(admin, admin.args(), locale);
            case InfrastructureException infrastructure -> plain(infrastructure, infrastructure.args(), locale);
        };
        problem.setStatus(status);
        problem.setProperty("code", failure.code().token());
        if (!failure.details().isEmpty()) {
            problem.setProperty("details", failure.details());
        }
        return problem;
    }

    /** A problem for a status that has no business code: the sentence comes from the bundle key {@code error.<token>}. */
    public ProblemDetail problem(HttpStatus status, String token, Locale locale) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                messages.getMessage("error." + token, new Object[0], token, locale));
        problem.setProperty("code", token);
        return problem;
    }

    /** The trace id of a server failure: it is in the problem and in the log line of the failure. */
    public String newTraceId() {
        return UUID.randomUUID().toString();
    }

    /** The status of a code, by a complete switch over the code enums. */
    static HttpStatus status(ErrorCode code) {
        return switch (code) {
            case TrustCode trust -> HttpStatus.FORBIDDEN;
            case ValidationCode validation -> HttpStatus.BAD_REQUEST;
            case TransitionCode transition -> HttpStatus.CONFLICT;
            case ConflictCode conflict -> HttpStatus.CONFLICT;
            case LookupCode lookup -> HttpStatus.NOT_FOUND;
            case AdminCode admin -> switch (admin) {
                case AGENT_TOKEN_FORBIDDEN, UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
                case DNS_REBINDING_BLOCKED, CSRF_REJECTED -> HttpStatus.FORBIDDEN;
                case TOO_MANY_AUTH_FAILURES -> HttpStatus.TOO_MANY_REQUESTS;
                case COMMON_CODE_DELETE_FORBIDDEN -> HttpStatus.METHOD_NOT_ALLOWED;
                case PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            };
            case InfrastructureCode infrastructure -> switch (infrastructure) {
                case DRIVER_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
                case DRIVER_INVALID -> HttpStatus.BAD_REQUEST;
                case INTEGRITY_VIOLATION -> HttpStatus.CONFLICT;
                case INTERNAL_ERROR, SCHEMA_FILE_NAME_INVALID, SCHEMA_VERSION_DUPLICATE, SCHEMA_VERSION_GAP,
                    SCHEMA_FILES_UNREADABLE, SCHEMA_DATABASE_UNREACHABLE, SCHEMA_HISTORY_MISSING,
                    SCHEMA_HISTORY_NOT_CREATED, SCHEMA_APPLIED_FILE_MISSING, SCHEMA_DIGEST_MISMATCH,
                    SCHEMA_RECORDED_FAILED, SCHEMA_OUT_OF_ORDER, SCHEMA_PENDING, SCHEMA_MIGRATION_FAILED,
                    SCHEMA_MIGRATION_INTERRUPTED, SCHEMA_GRANT_FAILED, JVM_ZONE_NOT_UTC, SESSION_ZONE_NOT_UTC,
                    SESSION_CLOCK_UNREADABLE -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        };
    }

    private ProblemDetail validation(ValidationException failure, Locale locale) {
        List<ValidationIssue> issues = failure.issues();
        ProblemDetail problem;
        if (failure.code() == ValidationCode.VALIDATION_FAILED && !issues.isEmpty()) {
            ValidationIssue first = issues.getFirst();
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    first.path() + ": " + reason(first, locale));
            problem.setProperty("errors", issues.stream()
                    .map(issue -> new Issue(issue.path(), reason(issue, locale), issue.allowed())).toList());
        } else {
            problem = plain(failure, failure.args(), locale);
        }
        return problem;
    }

    /** One failed input of a form: where, why, and the allowed values of a closed set. */
    public record Issue(String path, String reason, List<String> allowed) {
    }

    private ProblemDetail plain(LedgerException failure, List<String> args, Locale locale) {
        MessageSourceResolvable resolvable = new DefaultMessageSourceResolvable(
                new String[] {failure.code().messageKey()}, args.toArray(), failure.code().token());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, messages.getMessage(resolvable, locale));
    }

    private String reason(ValidationIssue issue, Locale locale) {
        MessageSourceResolvable resolvable = new DefaultMessageSourceResolvable(
                new String[] {issue.reason().messageKey()}, issue.reasonArgs().toArray(), issue.reason().name());
        return messages.getMessage(resolvable, locale);
    }
}
