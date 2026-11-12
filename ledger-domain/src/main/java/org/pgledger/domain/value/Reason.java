package org.pgledger.domain.value;

import java.util.List;
import java.util.Map;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;

/** Why a write was made: 10..1000 characters after trimming (characters, not bytes). Stored trimmed. */
public record Reason(String text) {

    public static final int MIN = 10;
    public static final int MAX = 1000;

    public Reason {
        text = validated(text);
    }

    public static Reason of(String text) {
        return new Reason(text);
    }

    /**
     * The reason trimmed, when its length is within {@link #MIN}..{@link #MAX} characters: the one place that knows
     * the bounds, for the value and for a stored history entry alike.
     *
     * @throws ValidationException REASON_INVALID when the text is missing, too short or too long
     */
    public static String validated(String text) {
        String trimmed = text == null ? "" : text.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN || length > MAX) {
            throw new ValidationException(ValidationCode.REASON_INVALID, List.of(),
                    List.of(new ValidationIssue("reason", ValidationReason.MUST_BE_CHARS_RANGE,
                            List.of(String.valueOf(MIN), String.valueOf(MAX)))),
                    Map.of());
        }
        return trimmed;
    }
}
