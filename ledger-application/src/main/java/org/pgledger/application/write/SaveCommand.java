package org.pgledger.application.write;

import java.util.Map;

/**
 * Create (expected version absent or 0) or edit (expected version of the row) a defect, task or condition. The
 * field values are texts; a key that is present with a null or blank value clears an optional field.
 */
public record SaveCommand(String requestId, String entity, String id, Long expectedVersion,
        Map<String, String> fields, String reason) implements WriteCommand {
}
