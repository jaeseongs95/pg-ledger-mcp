package org.pgledger.admin.web;

import jakarta.validation.ConstraintViolation;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pgledger.application.validation.ViolationTranslator;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.value.Token;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

/**
 * Turns what the binding of a form found wrong (a constraint of the form record that is violated, a field that cannot be
 * read as its type) into the one failure of the ledger for invalid input, {@code VALIDATION_FAILED} with an issue per
 * field, which the error advice renders. A controller method takes the form with its {@code BindingResult} and calls
 * {@link #raiseIfAny} where the order of its checks wants the failure: Spring's own failure of a form (a
 * {@code MethodArgumentNotValidException}) would be answered as a plain 400 without the path of the field.
 *
 * <p>The constraints of the form records use the message template of the reason ({@link #REQUIRED}); the sentence comes
 * from the reason bundles, as for every other failure of input, and the path is the wire name of the field.
 */
@Component
public class FormErrors {

    /** The message template of a required field: the reason REQUIRED. */
    public static final String REQUIRED = "{reason.REQUIRED}";

    /**
     * @throws ValidationException VALIDATION_FAILED naming every field that failed, the first in the order of the paths
     */
    public void raiseIfAny(BindingResult errors) {
        if (!errors.hasErrors()) {
            return;
        }
        List<ValidationIssue> issues = errors.getFieldErrors().stream().map(error -> issue(error, errors))
                .sorted(Comparator.comparing(ValidationIssue::path).thenComparing(ValidationIssue::reason)).toList();
        throw new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(issues.getFirst().path()), issues,
                Map.of());
    }

    private static ValidationIssue issue(FieldError error, BindingResult errors) {
        if (error.contains(ConstraintViolation.class)) {
            ConstraintViolation<?> violation = error.unwrap(ConstraintViolation.class);
            return new ViolationTranslator().translate(Set.of(violation)).issues().getFirst();
        }
        String path = ViolationTranslator.wireName(error.getField());
        String given = String.valueOf(error.getRejectedValue());
        Class<?> type = errors.getFieldType(error.getField());
        if (type != null && type.isEnum()) {
            List<String> allowed = Arrays.stream(type.getEnumConstants())
                    .map(constant -> constant instanceof Token token ? token.token() : ((Enum<?>) constant).name())
                    .toList();
            return new ValidationIssue(path, ValidationReason.UNKNOWN_VALUE, List.of(given), allowed);
        }
        return new ValidationIssue(path, ValidationReason.NOT_AN_INTEGER, List.of());
    }
}
