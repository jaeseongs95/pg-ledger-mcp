package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.ErrorCategory;
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
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.Batch;
import org.pgledger.domain.model.BatchOp;
import org.pgledger.domain.model.BatchOpKind;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Tokens;

/** DOM-025 .. DOM-027: batch operations, the exception hierarchy and the message bundles. */
class BatchAndErrorsTest {

    @Test
    void dom025_batchOperationKindsCarryTheOldKeySets() {
        assertThat(Tokens.allowed(BatchOpKind.values())).containsExactly("save", "transition", "record_fix",
                "record_case", "link", "correct");
        assertThat(BatchOpKind.SAVE.requiredKeys()).containsExactlyInAnyOrder("entity", "fields", "reason");
        assertThat(BatchOpKind.SAVE.allowedKeys()).containsExactlyInAnyOrder("entity", "fields", "reason", "id",
                "expected_version", "op", "request_id");
        assertThat(BatchOpKind.TRANSITION.requiredKeys()).containsExactlyInAnyOrder("entity", "id",
                "expected_version", "to", "reason");
        assertThat(BatchOpKind.RECORD_FIX.allowedKeys()).contains("branch", "ci_ref", "ci_conclusion",
                "ci_completed_at", "mark_source_fixed");
        assertThat(BatchOpKind.RECORD_CASE.requiredKeys()).containsExactlyInAnyOrder("defect_id", "observation",
                "reason");
        assertThat(BatchOpKind.LINK.allowedKeys()).contains("remove");
        assertThat(BatchOpKind.CORRECT.allowedKeys()).containsExactlyInAnyOrder("history_id", "reason", "op",
                "request_id");
        // review is not a batch operation; nothing and null are not either
        Expect.invalid(() -> Tokens.parse("op", "review", BatchOpKind.values()));
        Expect.invalid(() -> Tokens.parse("op", null, BatchOpKind.values()));
    }

    @Test
    void dom025_batchSizeAndDerivedRequestIds() {
        RequestId batchId = new RequestId(UUID.randomUUID());
        RequestId own = new RequestId(UUID.randomUUID());
        BatchOp withId = new BatchOp.CorrectOp(1, Reason.of("a reason of enough length"), own);
        BatchOp withoutId = new BatchOp.CorrectOp(2, Reason.of("a reason of enough length"), null);
        Batch batch = new Batch(batchId, List.of(withId, withoutId));
        assertThat(batch.requestIdOf(0)).isEqualTo(own);
        assertThat(batch.requestIdOf(1)).isEqualTo(batchId.derive(1));
        assertThat(batch.ops().get(0).kind()).isEqualTo(BatchOpKind.CORRECT);

        Expect.invalid(() -> new Batch(batchId, List.of()));
        Expect.invalid(() -> new Batch(batchId, null));
        List<BatchOp> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add(withoutId);
        }
        new Batch(batchId, many.subList(0, 200));
        Expect.invalid(() -> new Batch(batchId, many));
        assertThat(new BatchOp.RecordCaseOp(DefectId.of("E2E-D-0001"), "x", Reason.of("a reason of enough length"),
                null, null, null, null).kind()).isEqualTo(BatchOpKind.RECORD_CASE);
    }

    static List<ErrorCode> allCodes() {
        List<ErrorCode> all = new ArrayList<>();
        for (Class<? extends ErrorCode> type : List.of(TrustCode.class, ValidationCode.class, TransitionCode.class,
                ConflictCode.class, LookupCode.class, AdminCode.class, InfrastructureCode.class)) {
            all.addAll(List.of(type.getEnumConstants()));
        }
        return all;
    }

    /** Compile-time exhaustive: adding a subtype of LedgerException without a case here does not compile. */
    private static ErrorCategory categoryOf(LedgerException e) {
        return switch (e) {
            case TrustException t -> ErrorCategory.TRUST;
            case ValidationException v -> ErrorCategory.VALIDATION;
            case TransitionException t -> ErrorCategory.TRANSITION;
            case ConflictException c -> ErrorCategory.CONFLICT;
            case LookupException l -> ErrorCategory.LOOKUP;
            case AdminException a -> ErrorCategory.ADMIN;
            case InfrastructureException i -> ErrorCategory.INFRASTRUCTURE;
        };
    }

    private static LedgerException exceptionFor(ErrorCode code) {
        return switch (code) {
            case TrustCode c -> new TrustException(c, List.of());
            case ValidationCode c -> new ValidationException(c, List.of(), List.of(), java.util.Map.of());
            case TransitionCode c -> new TransitionException(c, List.of());
            case ConflictCode c -> new ConflictException(c, List.of());
            case LookupCode c -> new LookupException(c, List.of());
            case AdminCode c -> new AdminException(c, List.of());
            case InfrastructureCode c -> new InfrastructureException(c, List.of());
        };
    }

    @Test
    void dom026_everyCodeMapsToOneExceptionSubtypeOfItsCategory() {
        List<ErrorCode> codes = allCodes();
        assertThat(codes).hasSize(41);
        assertThat(codes.stream().map(ErrorCode::token)).doesNotHaveDuplicates();
        assertThat(codes.stream().map(ErrorCode::token)).contains("AUTHOR_ID_FORMAT", "SESSION_NOT_GRANTED",
                "HOST_ATTESTATION_REQUIRED", "VERSION_CONFLICT", "SAME_SESSION", "MERGED_DEFECT", "CODE_REQUIRED",
                "CODE_INVALID", "REQUEST_ID_LEGACY", "ATTESTATION_UNSIGNED", "ATTESTATION_MALFORMED",
                "ATTESTATION_VERSION_UNSUPPORTED", "ATTESTATION_SIGNATURE_INVALID", "ATTESTATION_BINDING_MISMATCH",
                "ATTESTATION_TIME_WINDOW", "KEY_REPLACEMENT_NOT_CONFIRMED");
        for (ErrorCode code : codes) {
            LedgerException e = exceptionFor(code);
            assertThat(e.code()).isEqualTo(code);
            assertThat(categoryOf(e)).as(code.token()).isEqualTo(code.category());
            assertThat(e.category()).isEqualTo(code.category());
        }
        assertThat(ErrorCode.class.getPermittedSubclasses()).hasSize(7);
        assertThat(LedgerException.class.getPermittedSubclasses()).hasSize(7);
    }

    @Test
    void dom027_bundlesHaveTheSameKeysAndCoverEveryCode() {
        ResourceBundle en = ResourceBundle.getBundle("messages/ledger-errors", Locale.ROOT);
        ResourceBundle ko = ResourceBundle.getBundle("messages/ledger-errors", Locale.of("ko"));
        assertThat(Set.copyOf(en.keySet())).isEqualTo(Set.copyOf(ko.keySet()));
        Set<String> expected = new HashSet<>();
        for (ErrorCode code : allCodes()) {
            expected.add(code.messageKey());
            assertThat(en.getString(code.messageKey())).as(code.messageKey()).isNotBlank();
            assertThat(ko.getString(code.messageKey())).as(code.messageKey()).isNotBlank();
            // the patterns must be valid MessageFormat patterns
            new MessageFormat(en.getString(code.messageKey())).format(new Object[] {"a", "b", "c", "d"});
            new MessageFormat(ko.getString(code.messageKey())).format(new Object[] {"a", "b", "c", "d"});
        }
        assertThat(Set.copyOf(en.keySet())).as("no unused key").isEqualTo(expected);
        assertThat(ko.getString("error.SESSION_NOT_GRANTED")).contains("부여");
    }

    @Test
    void dom027_reasonBundlesHaveAPatternForEveryValidationReason() {
        ResourceBundle en = ResourceBundle.getBundle("messages/ledger-reasons", Locale.ROOT);
        ResourceBundle ko = ResourceBundle.getBundle("messages/ledger-reasons", Locale.of("ko"));
        assertThat(Set.copyOf(en.keySet())).isEqualTo(Set.copyOf(ko.keySet()));
        Set<String> expected = new HashSet<>();
        for (ValidationReason reason : ValidationReason.values()) {
            assertThat(reason.messageKey()).isEqualTo("reason." + reason.name());
            expected.add(reason.messageKey());
            assertThat(en.getString(reason.messageKey())).as(reason.messageKey()).isNotBlank();
            assertThat(ko.getString(reason.messageKey())).as(reason.messageKey()).isNotBlank();
            // the patterns parse, and both languages use the same number of arguments
            MessageFormat english = new MessageFormat(en.getString(reason.messageKey()));
            MessageFormat korean = new MessageFormat(ko.getString(reason.messageKey()));
            assertThat(korean.getFormatsByArgumentIndex()).as(reason.messageKey()).hasSameSizeAs(
                    english.getFormatsByArgumentIndex());
            assertThat(english.format(new Object[] {"a", "b", "c"})).as(reason.messageKey()).doesNotContain("{");
        }
        assertThat(Set.copyOf(en.keySet())).as("no unused key").isEqualTo(expected);
        assertThat(new MessageFormat(en.getString("reason.MUST_BE_AT_MOST_CHARS")).format(new Object[] {"200"}))
                .isEqualTo("must be at most 200 characters");
        assertThat(new MessageFormat(ko.getString("reason.REQUIRED")).format(new Object[0])).contains("필수");
    }

    @Test
    void dom027_aJakartaTemplateNamesOneReason() {
        for (ValidationReason reason : ValidationReason.values()) {
            assertThat(ValidationReason.ofTemplate("{" + reason.messageKey() + "}")).isSameAs(reason);
        }
        assertThatThrownBy(() -> ValidationReason.ofTemplate("{jakarta.validation.constraints.NotNull.message}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationReason.ofTemplate("is required"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dom027_aValidationFailureCarriesThePathAndTheReasonSeparately() {
        ValidationException e = ValidationException.invalid("ops", ValidationReason.MUST_HOLD_ITEMS, "1", "100");
        assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED);
        assertThat(e.args()).containsExactly("ops");
        assertThat(e.issues()).hasSize(1);
        assertThat(e.issues().get(0).reason()).isEqualTo(ValidationReason.MUST_HOLD_ITEMS);
        assertThat(e.issues().get(0).reasonArgs()).containsExactly("1", "100");
        assertThat(e.issues().get(0).allowed()).isEmpty();
        ValidationException closed = ValidationException.invalid("kind", ValidationReason.UNKNOWN_VALUE,
                List.of("a", "b"), "x");
        assertThat(closed.issues().get(0).allowed()).containsExactly("a", "b");
        assertThat(closed.issues().get(0).reasonArgs()).containsExactly("x");
    }

    @ParameterizedTest
    @MethodSource("allCodes")
    void dom026_withDetailsKeepsTypeCodeAndArguments(ErrorCode code) {
        LedgerException original = exceptionFor(code);
        LedgerException wrapped = original.withDetails(java.util.Map.of("op_index", List.of("2")));
        assertThat(wrapped).isNotSameAs(original).hasSameClassAs(original);
        assertThat(wrapped.code()).isEqualTo(code);
        assertThat(wrapped.args()).isEqualTo(original.args());
        assertThat(wrapped.details()).containsEntry("op_index", List.of("2"));
        assertThat(original.details()).doesNotContainKey("op_index");
    }
}
