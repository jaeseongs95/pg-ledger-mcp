package org.pgledger.application.write;

/**
 * The result of a write, stored in the receipt and returned again on a replay. Keys are the external contract
 * (written in snake_case); {@code login} is the channel's login name.
 */
public sealed interface WriteResult {

    record SaveResult(WriteStatus status, String entity, String id, long version, long historyId, String author,
            String login) implements WriteResult {
    }

    record TransitionResult(WriteStatus status, String entity, String id, String from, String to, long version,
            long historyId, String author, String login) implements WriteResult {
    }

    record RecordFixResult(WriteStatus status, String defect, long fixId, String defectStatus, String ownerActorId,
            long version, long historyId, Long transitionHistoryId, String author, String login)
            implements WriteResult {
    }

    record RecordCaseResult(WriteStatus status, String entity, String id, long caseId, String defect,
            long historyId, String author, String login) implements WriteResult {
    }

    record LinkResult(WriteStatus status, String entity, String id, boolean removed, long historyId,
            String author, String login) implements WriteResult {
    }

    record CorrectResult(WriteStatus status, long corrects, long historyId, String author, String login)
            implements WriteResult {
    }
}
