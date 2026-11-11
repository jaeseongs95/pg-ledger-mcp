package org.pgledger.application.write;

/** Records one observation of a defect. */
public record RecordCaseCommand(String requestId, String defectId, String observation, String reason,
        String evidence, String userQuote, String observedAt) implements WriteCommand {
}
