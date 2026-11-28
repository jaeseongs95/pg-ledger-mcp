// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of a write operation, persisted in {@code request_receipt} and replayed idempotently.
 */
public sealed interface WriteResult {

    record SaveResult(WriteStatus status, String entity, String id, long version, long historyId, String author,
            String login) implements WriteResult {
    }

    record TransitionResult(WriteStatus status, String entity, String id, String from, String to, long version,
            long historyId, String author, String login,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean reviewReturn) implements WriteResult {
    }

    record RecordDeliverableResult(WriteStatus status, String targetEntity, String targetId, long deliverableId,
            String targetState, String ownerActorId, long version, long historyId, Long transitionHistoryId,
            String author, String login) implements WriteResult {
    }

    record RecordCaseResult(WriteStatus status, String entity, String id, long caseId, String issue,
            long historyId, String author, String login) implements WriteResult {
    }

    record LinkResult(WriteStatus status, String entity, String id, boolean removed, long historyId,
            String author, String login) implements WriteResult {
    }

    record CorrectResult(WriteStatus status, long corrects, long historyId, String author, String login)
            implements WriteResult {
    }

    record ProjectVersionResult(String projectId, String versionId, String name, boolean created, int linked,
            int unlinked, String author, String reason) implements WriteResult {
    }
}
