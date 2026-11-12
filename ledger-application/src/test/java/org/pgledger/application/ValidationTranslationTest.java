package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.application.validation.ViolationTranslator;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.BatchOpKind;

/**
 * SVC-PRN-13 (the translation of violations into the domain's failure) and the required keys of the batch operations
 * (SVC-PRN-10, the part that needs no database): the constraints of the records are the required keys of
 * {@link BatchOpKind}.
 */
class ValidationTranslationTest {

    /** A validator that needs no expression language: the message templates are never read, only their keys. */
    private static final Validator VALIDATOR = Validation.byDefaultProvider().configure()
            .messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory().getValidator();

    record Probe(@Size(max = 3, message = "{reason.MUST_BE_AT_MOST_CHARS}") String maxRows,
            @NotNull(message = "{reason.REQUIRED}") String title) {
    }

    @Test
    @DisplayName("SVC-PRN-13: the path of a violation is the property in snake case, the issues are in the order of the paths, the first names the failure, the arguments are the constraint attributes")
    void prn13_translation() {
        var violations = VALIDATOR.validate(new Probe("abcd", null));

        ValidationException failure = new ViolationTranslator().translate(violations);

        assertThat(failure.code()).isEqualTo(ValidationCode.VALIDATION_FAILED);
        assertThat(failure.args()).containsExactly("max_rows");
        assertThat(failure.issues()).extracting(ValidationIssue::path).containsExactly("max_rows", "title");
        assertThat(failure.issues().get(0).reason()).isEqualTo(ValidationReason.MUST_BE_AT_MOST_CHARS);
        assertThat(failure.issues().get(0).reasonArgs()).containsExactly("3");
        assertThat(failure.issues().get(1).reason()).isEqualTo(ValidationReason.REQUIRED);
        assertThat(failure.issues().get(1).reasonArgs()).isEmpty();
    }

    @Test
    @DisplayName("SVC-PRN-13: with a prefix the paths are those of the element of a request (ops[2].max_rows)")
    void prn13_prefix() {
        var violations = VALIDATOR.validate(new Probe("abcd", null));

        ValidationException failure = new ViolationTranslator("ops[2]").translate(violations);

        assertThat(failure.args()).containsExactly("ops[2].max_rows");
        assertThat(failure.issues()).extracting(ValidationIssue::path).containsExactly("ops[2].max_rows", "ops[2].title");
    }

    @ParameterizedTest
    @EnumSource(BatchOpKind.class)
    @DisplayName("SVC-PRN-10: an operation of a batch without any key violates exactly the required keys that BatchOpKind lists for it (in snake case), each as REQUIRED")
    void prn10_requiredKeys(BatchOpKind kind) {
        BatchOpInput empty = switch (kind) {
            case SAVE -> new BatchOpInput.Save(null, null, null, null, null, null);
            case TRANSITION -> new BatchOpInput.Transition(null, null, null, null, null, null, null, null, null);
            case RECORD_FIX -> new BatchOpInput.RecordFix(null, null, null, null, null, null, null, null, null, null);
            case RECORD_CASE -> new BatchOpInput.RecordCase(null, null, null, null, null, null, null);
            case LINK -> new BatchOpInput.Link(null, null, null, null, null, null);
            case CORRECT -> new BatchOpInput.Correct(null, null, null);
        };

        ValidationException failure = new ViolationTranslator("ops[0]").translate(VALIDATOR.validate(empty));

        Set<String> keys = failure.issues().stream().map(issue -> issue.path().substring("ops[0].".length()))
                .collect(Collectors.toSet());
        assertThat(keys).isEqualTo(kind.requiredKeys());
        assertThat(failure.issues()).extracting(ValidationIssue::reason).containsOnly(ValidationReason.REQUIRED);
        assertThat(failure.details()).isEmpty();
    }
}
