package org.pgledger.domain.value;

import java.util.List;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import java.util.Map;

/** Why a write was made: 10..1000 characters after trimming (characters, not bytes). Stored trimmed. */
public record Reason(String text) {

    public static final int MIN = 10;
    public static final int MAX = 1000;

    public Reason {
        String trimmed = text == null ? "" : text.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN || length > MAX) {
            throw new ValidationException(ValidationCode.REASON_INVALID, List.of(),
                    List.of(new ValidationIssue("reason", ValidationReason.MUST_BE_CHARS_RANGE,
                            List.of(String.valueOf(MIN), String.valueOf(MAX)))),
                    Map.of());
        }
        text = trimmed;
    }

    public static Reason of(String text) {
        return new Reason(text);
    }
}
