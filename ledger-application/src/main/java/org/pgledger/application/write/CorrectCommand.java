package org.pgledger.application.write;

/** Points at a wrong history row with a CORRECT row. */
public record CorrectCommand(String requestId, Long historyId, String reason) implements WriteCommand {
}
