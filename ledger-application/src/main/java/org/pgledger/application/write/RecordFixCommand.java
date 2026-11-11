package org.pgledger.application.write;

/** Records a fix commit of a defect, or new CI data of a known commit. */
public record RecordFixCommand(String requestId, String defectId, String repo, String commitSha, String reason,
        String branch, String ciRef, String ciConclusion, String ciCompletedAt, Boolean markSourceFixed)
        implements WriteCommand {
}
