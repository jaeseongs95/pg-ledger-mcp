package org.pgledger.application.write;

/** Links (or unlinks) two entities; the kind is defect_task, condition_task, condition_defect or a relation code. */
public record LinkCommand(String requestId, String kind, String a, String b, String reason, Boolean remove)
        implements WriteCommand {
}
