package org.pgledger.domain.value;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * JSON text kept in a long text column (history changes, before_data, receipt response). The domain does not
 * parse it: the application builds it with its JSON mapper, the database stores it as it is.
 */
public record JsonDocument(String text) {

    public static final JsonDocument EMPTY_OBJECT = new JsonDocument("{}");

    public JsonDocument {
        if (text == null || text.isBlank()) {
            throw ValidationException.invalid("json", ValidationReason.MUST_NOT_BE_BLANK);
        }
    }

    public static JsonDocument of(String text) {
        return new JsonDocument(text);
    }
}
