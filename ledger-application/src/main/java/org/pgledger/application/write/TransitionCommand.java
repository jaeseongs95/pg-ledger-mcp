package org.pgledger.application.write;

/** Moves a defect, task or condition to another state. */
public record TransitionCommand(String requestId, String entity, String id, Long expectedVersion, String to,
        String reason, String evidenceUri, String evidenceSha256, String mergedInto) implements WriteCommand {
}
