package org.pgledger.cli.database;

import java.util.ArrayList;
import java.util.List;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;

/**
 * The one place where a refusal of the ledger becomes the refusal of the CLI. The kind of refusal is an exhaustive switch
 * over the sealed {@link LedgerException} (a new kind does not compile until it is dealt with here), the code is a switch
 * over the code enum of that kind, and what the owner asked for ({@link Context}) decides which CLI code it becomes. The
 * arguments of a ledger refusal are positional in the domain; they are read by name here ({@link LiveKey},
 * {@link TableMismatch}) and never echoed except what the owner needs to go on (the fingerprint and the registration time
 * of a live key; the tables of a failed comparison). No value the owner typed is part of a message.
 */
public final class LedgerRefusals {

    /** What the command was doing when the ledger refused. */
    public enum Context {
        /** Any other command: the refusal is LEDGER_REFUSED with its code. */
        GENERIC,
        /** {@code key-register} without {@code --replace}. */
        REGISTER,
        /** {@code key-register --replace=<fingerprint>}: the owner has already given the fingerprint of the live key. */
        REPLACE,
        /** {@code key-revoke}. */
        REVOKE,
        /** {@code import-legacy}. */
        IMPORT
    }

    /** The live key that a registration would replace: the arguments of KEY_REPLACEMENT_NOT_CONFIRMED. */
    record LiveKey(String author, String fingerprint, String registeredAt) {

        static LiveKey of(List<String> arguments) {
            return new LiveKey(arguments.get(0), arguments.get(1), arguments.get(2));
        }
    }

    /**
     * One table of a failed comparison of the legacy ledger and the new one: the arguments of INTEGRITY_VIOLATION come in
     * groups of these five.
     */
    public record TableMismatch(String table, String legacyRows, String targetRows, String legacyDigest,
            String targetDigest) {

        private static final int GROUP = 5;

        /** The groups of the arguments, empty when they are not groups of five (another integrity failure). */
        public static List<TableMismatch> of(List<String> arguments) {
            List<TableMismatch> groups = new ArrayList<>();
            if (arguments.size() < GROUP) {
                return groups;
            }
            for (int from = 0; from + GROUP <= arguments.size(); from += GROUP) {
                groups.add(new TableMismatch(arguments.get(from), arguments.get(from + 1), arguments.get(from + 2),
                        arguments.get(from + 3), arguments.get(from + 4)));
            }
            return groups;
        }

        /** The five texts in the order of the message {@code import-legacy.mismatch}. */
        public String[] texts() {
            return new String[] {table, legacyRows, targetRows, legacyDigest, targetDigest};
        }
    }

    private LedgerRefusals() {
    }

    public static FailureException translate(LedgerException refused, Context context) {
        return switch (refused) {
            case ConflictException conflict -> conflict(conflict, context);
            case LookupException lookup -> lookup(lookup, context);
            case ValidationException invalid -> validation(invalid, context);
            case TrustException trust -> trust(trust, context);
            case InfrastructureException infrastructure -> infrastructure(infrastructure, context);
            case TransitionException transition -> generic(transition);
            case AdminException admin -> generic(admin);
        };
    }

    private static FailureException conflict(ConflictException refused, Context context) {
        return switch ((ConflictCode) refused.code()) {
            case DUPLICATE_KEY -> switch (context) {
                case REGISTER, REPLACE -> new FailureException(ErrorCode.KEY_IN_USE, refused);
                case IMPORT -> new FailureException(ErrorCode.IMPORT_TARGET_NOT_EMPTY, refused,
                        String.join(", ", refused.args()));
                case GENERIC, REVOKE -> generic(refused);
            };
            case KEY_REPLACEMENT_NOT_CONFIRMED -> switch (context) {
                case REGISTER, REPLACE -> {
                    LiveKey live = LiveKey.of(refused.args());
                    yield new FailureException(context == Context.REPLACE ? ErrorCode.REPLACE_FINGERPRINT_STALE
                            : ErrorCode.KEY_REPLACEMENT_NOT_CONFIRMED, refused, live.fingerprint(), live.registeredAt());
                }
                case GENERIC, REVOKE, IMPORT -> generic(refused);
            };
            // the one place of the rule is the service; whatever command asked, the owner is told how to register the key
            case SESSION_KEY_REQUIRED -> new FailureException(ErrorCode.NO_SESSION_KEY, refused,
                    refused.args().getFirst());
            case VERSION_CONFLICT, REQUEST_CONFLICT, REQUEST_ID_LEGACY -> generic(refused);
        };
    }

    private static FailureException lookup(LookupException refused, Context context) {
        return switch ((LookupCode) refused.code()) {
            case NOT_FOUND -> switch (context) {
                case REVOKE -> new FailureException(ErrorCode.NO_LIVE_KEY, refused);
                // the arguments of the legacy source that is not complete: what was looked for, then the missing steps
                case IMPORT -> refused.args().size() > 1
                        ? new FailureException(ErrorCode.IMPORT_SOURCE_INCOMPLETE, refused, refused.args().get(1))
                        : generic(refused);
                case GENERIC, REGISTER, REPLACE -> generic(refused);
            };
        };
    }

    private static FailureException validation(ValidationException refused, Context context) {
        return switch (context) {
            case IMPORT -> new FailureException(ErrorCode.IMPORT_DATA_REFUSED, refused,
                    String.valueOf(refused.issues().size()));
            case REVOKE -> revokeValidation(refused);
            case REGISTER, REPLACE -> namesTheAuthor(refused)
                    ? new FailureException(ErrorCode.INVALID_AUTHOR, refused) : generic(refused);
            case GENERIC -> generic(refused);
        };
    }

    private static FailureException revokeValidation(ValidationException refused) {
        if ((ValidationCode) refused.code() == ValidationCode.VALIDATION_FAILED && !refused.issues().isEmpty()
                && refused.issues().getFirst().reason() == ValidationReason.MUST_MATCH) {
            // the confirmation does not match: the issue names the fingerprint of the key that is live now
            return new FailureException(ErrorCode.REVOKE_FINGERPRINT_MISMATCH, refused,
                    refused.issues().getFirst().reasonArgs().getFirst());
        }
        return namesTheAuthor(refused) ? new FailureException(ErrorCode.INVALID_AUTHOR, refused) : generic(refused);
    }

    private static boolean namesTheAuthor(ValidationException refused) {
        return (ValidationCode) refused.code() == ValidationCode.VALIDATION_FAILED && refused.args().contains("author");
    }

    private static FailureException trust(TrustException refused, Context context) {
        return switch ((TrustCode) refused.code()) {
            case AUTHOR_ID_FORMAT -> switch (context) {
                case REGISTER, REPLACE, REVOKE -> new FailureException(ErrorCode.INVALID_AUTHOR, refused);
                case GENERIC, IMPORT -> generic(refused);
            };
            case HOST_ATTESTATION_REQUIRED, SESSION_NOT_GRANTED, VERIFY_NEEDS_REVIEWER_LOGIN, ATTESTATION_UNSIGNED,
                    ATTESTATION_MALFORMED, ATTESTATION_VERSION_UNSUPPORTED, ATTESTATION_SIGNATURE_INVALID,
                    ATTESTATION_BINDING_MISMATCH, ATTESTATION_TIME_WINDOW -> generic(refused);
        };
    }

    /** Of the infrastructure codes only the failed comparison of the import means anything else to the owner. */
    private static FailureException infrastructure(InfrastructureException refused, Context context) {
        if (context == Context.IMPORT && (InfrastructureCode) refused.code() == InfrastructureCode.INTEGRITY_VIOLATION) {
            List<TableMismatch> groups = TableMismatch.of(refused.args());
            if (!groups.isEmpty()) {
                return new FailureException(ErrorCode.IMPORT_VERIFY_FAILED, refused, groups.getFirst().texts());
            }
        }
        return generic(refused);
    }

    /** LEDGER_REFUSED with the code and, for a validation failure, the first path and reason (never an input value). */
    private static FailureException generic(LedgerException refused) {
        String detail = refused.code().token();
        if (refused instanceof ValidationException invalid && !invalid.issues().isEmpty()) {
            ValidationIssue first = invalid.issues().getFirst();
            detail = detail + ": " + first.path() + " " + first.reason().name();
        }
        return new FailureException(ErrorCode.LEDGER_REFUSED, refused, detail);
    }
}
