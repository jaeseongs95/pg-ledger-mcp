package org.pgledger.application.write;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/** Points at a wrong history row with a CORRECT row. */
public record CorrectCommand(String requestId, Long historyId, String reason) implements WriteCommand {

    /**
     * The id of the history row to correct.
     *
     * @throws ValidationException when it is missing
     */
    public long requireHistoryId() {
        if (historyId == null) {
            throw ValidationException.invalid("history_id", ValidationReason.REQUIRED);
        }
        return historyId;
    }
}
