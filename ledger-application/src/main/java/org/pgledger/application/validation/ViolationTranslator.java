package org.pgledger.application.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * Turns the violations of Jakarta Validation into the domain's {@link ValidationException} (code VALIDATION_FAILED, the
 * wire name of the property as the path). It is the one place that does; the method validation of the services and
 * the batch both use it.
 *
 * <p>It is a plain class and not a bean on purpose: the method validation advice that uses it is created inside a
 * {@code BeanPostProcessor}, which Spring instantiates before any ordinary bean exists, so the processor makes this
 * class with {@code new} and no container can inject it there.
 */
public final class ViolationTranslator {

    /** The constraint attributes that the arguments of a reason are made of, in the order of its placeholders. */
    private static final Map<ValidationReason, List<String>> REASON_ATTRIBUTES = Map.of(
            ValidationReason.MUST_BE_AT_MOST_CHARS, List.of("max"),
            ValidationReason.MUST_HOLD_ITEMS, List.of("min", "max"),
            ValidationReason.MUST_BE_CHARS_RANGE, List.of("min", "max"));

    /** The snake case of the wire names is Jackson's own: the same rule that writes and reads the JSON of the ledger. */
    private static final class WireNames extends PropertyNamingStrategies.SnakeCaseStrategy {

        private static final long serialVersionUID = 1L;

        String of(String property) {
            return translate(property);
        }
    }

    private static final WireNames WIRE = new WireNames();

    private final String pathPrefix;

    /**
     * The wire name of a property ({@code maxRows} is {@code max_rows}), for a failure that does not come from a
     * constraint (a form field that cannot be read as its type) and still has to name its path the way the others do.
     */
    public static String wireName(String property) {
        return WIRE.of(property);
    }

    /** Translates to paths as the properties are named. */
    public ViolationTranslator() {
        this("");
    }

    /**
     * Translates to paths below {@code pathPrefix}, for a value that is one element of a request (the operation
     * {@code ops[2]} of a batch has the paths {@code ops[2].reason}).
     */
    public ViolationTranslator(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    /** As {@link #translate(Set, Throwable)} for violations that no exception carried (a value that was validated by hand). */
    public ValidationException translate(Set<? extends ConstraintViolation<?>> violations) {
        return translate(violations, null);
    }

    /**
     * One issue per violated property, in the order of their paths; the first one names the failure. The reason is
     * the one the message template of the constraint names ({@code {reason.REQUIRED}}), its arguments are attributes
     * of the constraint ({@code max} of a size); the interpolated message of the violation is never read, so the
     * result does not depend on the locale of the JVM and the sentence is the business of the reason bundles.
     */
    public ValidationException translate(Set<? extends ConstraintViolation<?>> violations, Throwable cause) {
        List<ValidationIssue> issues = violations.stream()
                .map(violation -> {
                    ValidationReason reason = ValidationReason.ofTemplate(violation.getMessageTemplate());
                    return new ValidationIssue(path(violation.getPropertyPath()), reason,
                            reasonArgs(reason, violation));
                })
                .distinct()
                .sorted(Comparator.comparing(ValidationIssue::path).thenComparing(ValidationIssue::reason))
                .toList();
        ValidationIssue first = issues.getFirst();
        return new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(first.path()), issues, Map.of(),
                cause);
    }

    /** The arguments of a reason: the constraint attributes its pattern names, in the order of its placeholders. */
    private static List<String> reasonArgs(ValidationReason reason, ConstraintViolation<?> violation) {
        Map<String, Object> attributes = violation.getConstraintDescriptor().getAttributes();
        return REASON_ATTRIBUTES.getOrDefault(reason, List.of()).stream()
                .map(name -> String.valueOf(attributes.get(name))).toList();
    }

    /** The wire name of a property path: the property nodes only, in snake case ({@code maxRows} is max_rows). */
    private String path(Path propertyPath) {
        StringBuilder wire = new StringBuilder(pathPrefix);
        for (Path.Node node : propertyPath) {
            if (node.getKind() == ElementKind.PROPERTY && node.getName() != null) {
                if (!wire.isEmpty()) {
                    wire.append('.');
                }
                wire.append(WIRE.of(node.getName()));
            }
        }
        return wire.toString();
    }
}
