package org.pgledger.cli.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.database.LedgerRefusals.Context;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
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
import org.pgledger.domain.error.ValidationReason;

/**
 * CLI-PRN-07 (plan 2.5): the one translation of a refusal of the ledger into the refusal of the CLI, for every kind of
 * {@code LedgerException} in each context of the commands that translate. What the owner needs to continue is in the
 * arguments of the CLI refusal; the arguments of the ledger are read by name, and the cause is kept.
 */
class LedgerRefusalsTest {

    private static FailureException translate(LedgerException refused, Context context) {
        FailureException failure = LedgerRefusals.translate(refused, context);
        assertThat(failure.getCause()).as("the refusal of the ledger is the cause").isSameAs(refused);
        return failure;
    }

    private static void assertRefusedAs(LedgerException refused, Context context, ErrorCode code, String... arguments) {
        FailureException failure = translate(refused, context);

        assertThat(failure.code()).as("%s in %s", refused.code(), context).isEqualTo(code);
        assertThat(failure.arguments()).as("%s in %s", refused.code(), context).containsExactly(arguments);
    }

    private static ValidationException authorIsInvalid() {
        return ValidationException.invalid("author", ValidationReason.REQUIRED);
    }

    @Test
    @DisplayName("CLI-PRN-07 generic: every kind of refusal is LEDGER_REFUSED with its code; a validation failure adds the first path and reason and never a value")
    void generic() {
        assertRefusedAs(new ConflictException(ConflictCode.VERSION_CONFLICT, List.of("defect", "D-1", "1", "2")),
                Context.GENERIC, ErrorCode.LEDGER_REFUSED, "VERSION_CONFLICT");
        assertRefusedAs(new LookupException(LookupCode.NOT_FOUND, List.of("project", "x")), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "NOT_FOUND");
        assertRefusedAs(new TrustException(TrustCode.SESSION_NOT_GRANTED, List.of()), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "SESSION_NOT_GRANTED");
        assertRefusedAs(new TransitionException(TransitionCode.TRANSITION_INVALID, List.of()), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "TRANSITION_INVALID");
        assertRefusedAs(new AdminException(AdminCode.UNAUTHORIZED, List.of()), Context.GENERIC, ErrorCode.LEDGER_REFUSED,
                "UNAUTHORIZED");
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of()), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "INTERNAL_ERROR");
        assertRefusedAs(ValidationException.invalid("title", ValidationReason.REQUIRED), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "VALIDATION_FAILED: title REQUIRED");
        // a validation failure without an issue is only its code
        assertRefusedAs(new ValidationException(ValidationCode.CODE_REQUIRED, List.of(), List.<ValidationIssue>of(), Map.of()),
                Context.GENERIC, ErrorCode.LEDGER_REFUSED, "CODE_REQUIRED");
    }

    @Test
    @DisplayName("CLI-PRN-07 register and replace: KEY_REPLACEMENT_NOT_CONFIRMED shows the live fingerprint and its time (REPLACE_FINGERPRINT_STALE when --replace was given), a duplicate key is KEY_IN_USE, an author that is not valid is INVALID_AUTHOR (format code, or a validation failure of author), anything else is LEDGER_REFUSED")
    void register() {
        ConflictException live = new ConflictException(ConflictCode.KEY_REPLACEMENT_NOT_CONFIRMED,
                List.of("claude:s1", "abcd", "2026-11-12T00:00:00Z"));

        assertRefusedAs(live, Context.REGISTER, ErrorCode.KEY_REPLACEMENT_NOT_CONFIRMED, "abcd", "2026-11-12T00:00:00Z");
        assertRefusedAs(live, Context.REPLACE, ErrorCode.REPLACE_FINGERPRINT_STALE, "abcd", "2026-11-12T00:00:00Z");
        for (Context context : List.of(Context.REGISTER, Context.REPLACE)) {
            assertRefusedAs(new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("x")), context, ErrorCode.KEY_IN_USE);
            assertRefusedAs(new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of("x")), context, ErrorCode.INVALID_AUTHOR);
            assertRefusedAs(authorIsInvalid(), context, ErrorCode.INVALID_AUTHOR);
            assertRefusedAs(ValidationException.invalid("publicKey", ValidationReason.REQUIRED), context,
                    ErrorCode.LEDGER_REFUSED, "VALIDATION_FAILED: publicKey REQUIRED");
            assertRefusedAs(new LookupException(LookupCode.NOT_FOUND, List.of("a", "b")), context, ErrorCode.LEDGER_REFUSED,
                    "NOT_FOUND");
        }
        // the same refusals mean nothing special outside a key command
        assertRefusedAs(live, Context.REVOKE, ErrorCode.LEDGER_REFUSED, "KEY_REPLACEMENT_NOT_CONFIRMED");
        assertRefusedAs(new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("x")), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "DUPLICATE_KEY");
    }

    @Test
    @DisplayName("CLI-PRN-07 revoke: NOT_FOUND is NO_LIVE_KEY, a confirmation that does not match is REVOKE_FINGERPRINT_MISMATCH with the live fingerprint, an author that is not valid is INVALID_AUTHOR")
    void revoke() {
        assertRefusedAs(new LookupException(LookupCode.NOT_FOUND, List.of("claude:s1")), Context.REVOKE,
                ErrorCode.NO_LIVE_KEY);
        assertRefusedAs(ValidationException.invalid("fingerprint", ValidationReason.MUST_MATCH, "1234"), Context.REVOKE,
                ErrorCode.REVOKE_FINGERPRINT_MISMATCH, "1234");
        assertRefusedAs(authorIsInvalid(), Context.REVOKE, ErrorCode.INVALID_AUTHOR);
        assertRefusedAs(new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of("x")), Context.REVOKE, ErrorCode.INVALID_AUTHOR);
        assertRefusedAs(new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("x")), Context.REVOKE,
                ErrorCode.LEDGER_REFUSED, "DUPLICATE_KEY");
        // a MUST_MATCH of another path in another command is only a validation failure
        assertRefusedAs(ValidationException.invalid("fingerprint", ValidationReason.MUST_MATCH, "1234"), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "VALIDATION_FAILED: fingerprint MUST_MATCH");
    }

    @Test
    @DisplayName("CLI-PRN-07 import: a duplicate key is IMPORT_TARGET_NOT_EMPTY naming the tables, a legacy ledger that is not complete is IMPORT_SOURCE_INCOMPLETE naming the missing steps, data that cannot be imported is IMPORT_DATA_REFUSED with the number of issues, a failed comparison is IMPORT_VERIFY_FAILED with its first table; a NOT_FOUND with one argument and an integrity failure that is no comparison are LEDGER_REFUSED")
    void importLegacy() {
        assertRefusedAs(new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("defect", "history")), Context.IMPORT,
                ErrorCode.IMPORT_TARGET_NOT_EMPTY, "defect, history");
        assertRefusedAs(new LookupException(LookupCode.NOT_FOUND, List.of("missing", "step 1, step 2")), Context.IMPORT,
                ErrorCode.IMPORT_SOURCE_INCOMPLETE, "step 1, step 2");
        assertRefusedAs(new LookupException(LookupCode.NOT_FOUND, List.of("project")), Context.IMPORT,
                ErrorCode.LEDGER_REFUSED, "NOT_FOUND");
        ValidationException two = new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(), List.of(
                new ValidationIssue("a[1].x", ValidationReason.REQUIRED, List.of()),
                new ValidationIssue("a[2].x", ValidationReason.REQUIRED, List.of())), Map.of());
        assertRefusedAs(two, Context.IMPORT, ErrorCode.IMPORT_DATA_REFUSED, "2");
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                List.of("defect", "3", "2", "aaaa", "bbbb", "task", "1", "1", "cccc", "dddd")), Context.IMPORT,
                ErrorCode.IMPORT_VERIFY_FAILED, "defect", "3", "2", "aaaa", "bbbb");
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, List.of("receipt")), Context.IMPORT,
                ErrorCode.LEDGER_REFUSED, "INTEGRITY_VIOLATION");
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of()), Context.IMPORT,
                ErrorCode.LEDGER_REFUSED, "INTERNAL_ERROR");
        assertRefusedAs(new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of("x")), Context.IMPORT,
                ErrorCode.LEDGER_REFUSED, "AUTHOR_ID_FORMAT");
    }

    @Test
    @DisplayName("CLI-PRN-07 the groups of a failed comparison are read by name: five arguments per table, and none when there are fewer than five")
    void tableMismatches() {
        List<LedgerRefusals.TableMismatch> groups = LedgerRefusals.TableMismatch
                .of(List.of("defect", "3", "2", "aaaa", "bbbb", "task", "1", "1", "cccc", "dddd", "stray"));

        assertThat(groups).extracting(LedgerRefusals.TableMismatch::table).containsExactly("defect", "task");
        assertThat(groups.getFirst().texts()).containsExactly("defect", "3", "2", "aaaa", "bbbb");
        assertThat(groups.getFirst().legacyRows()).isEqualTo("3");
        assertThat(groups.getFirst().targetDigest()).isEqualTo("bbbb");
        assertThat(LedgerRefusals.TableMismatch.of(List.of("a", "b"))).isEmpty();
    }
}
