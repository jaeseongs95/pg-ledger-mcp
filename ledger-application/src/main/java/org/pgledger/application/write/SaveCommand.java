package org.pgledger.application.write;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * Create (expected version absent or 0) or edit (expected version of the row) a defect, task or condition. The
 * field values are texts; a key that is written with a null or blank value clears an optional field, a key that is
 * left out is not touched ({@link FieldsInput}).
 */
public record SaveCommand(String requestId, String entity, String id, Long expectedVersion,
        FieldsInput fields, String reason) implements WriteCommand {

    /**
     * Whether the request creates a row (no expected version, or 0) rather than edits one.
     *
     * @throws ValidationException when the expected version is negative
     */
    public boolean isCreate() {
        if (expectedVersion != null && expectedVersion < 0) {
            throw ValidationException.invalid("expected_version", ValidationReason.MUST_NOT_BE_NEGATIVE);
        }
        return expectedVersion == null || expectedVersion == 0;
    }

    /**
     * The id of the row to edit.
     *
     * @throws ValidationException when the id is missing or blank
     */
    public String requireId() {
        if (id == null || id.isBlank()) {
            throw ValidationException.invalid("id", ValidationReason.REQUIRED_TO_EDIT);
        }
        return id;
    }

    /** The project the request names in {@code fields}, as text, or null when it names none. */
    String mentionedProject() {
        return fields == null ? null : fields.projectId().orNull();
    }
}
