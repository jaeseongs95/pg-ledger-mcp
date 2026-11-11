package org.pgledger.domain.value;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;

/** Idempotency key of a write request: a UUID in its canonical 36 character form. */
public record RequestId(UUID value) {

    private static final Pattern CANONICAL = Pattern
            .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public RequestId {
        if (value == null) {
            throw ValidationException.invalid("request_id", "is required");
        }
    }

    public static RequestId parse(String text) {
        if (text == null || !CANONICAL.matcher(text).matches()) {
            throw ValidationException.invalid("request_id", "must be a UUID");
        }
        return new RequestId(UUID.fromString(text));
    }

    /** The id of operation {@code index} of a batch: deterministic, different per index and per batch id. */
    public RequestId derive(int index) {
        return new RequestId(UUID.nameUUIDFromBytes((value + ":" + index).getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
