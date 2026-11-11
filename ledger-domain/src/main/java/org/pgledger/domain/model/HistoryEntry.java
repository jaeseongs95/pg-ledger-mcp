package org.pgledger.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;
import java.util.Map;

/**
 * One row of the append-only history. {@code changes} is the diff of the changed fields, {@code beforeData} the
 * full row as it was just before an EDIT, TRANSITION or UNLINK (absent for every other operation and for imported
 * rows). The record is immutable and the invariants of the old CHECK constraints are enforced on construction.
 */
@Table("history")
public record HistoryEntry(@Id Long id, Instant at, ActorId authorId, String login, ActorAssurance actorAssurance,
        HistoryEntity entity, String entityId, HistoryOp op, String fromState, String toState, JsonDocument changes,
        JsonDocument beforeData, String reason, String evidenceUri, Sha256 evidenceSha256, Long correctsId,
        RequestId requestId, String legacyRef) {

    private static final Set<String> VERIFICATION_STATES = Set.of("VERIFIED", "MET", "NOT_MET");
    private static final Set<HistoryOp> OPS_WITH_BEFORE_DATA = Set.of(HistoryOp.EDIT, HistoryOp.TRANSITION,
            HistoryOp.UNLINK);

    public HistoryEntry {
        if (changes == null) {
            changes = JsonDocument.EMPTY_OBJECT;
        }
        require((op == HistoryOp.TRANSITION) == (toState != null), "to_state", "is set exactly for TRANSITION");
        require((op == HistoryOp.CORRECT) == (correctsId != null), "corrects_id", "is set exactly for CORRECT");
        require(!op.legacyOnly() || legacyRef != null, "legacy_ref", "is required for imported operations");
        require(beforeData == null || OPS_WITH_BEFORE_DATA.contains(op), "before_data",
                "exists only for EDIT, TRANSITION and UNLINK");
        require(entityId != null && !entityId.isBlank() && entityId.length() <= 300, "entity_id",
                "must be 1..300 characters");
        require(reason != null && !reason.isBlank(), "reason", "is required");
        if (legacyRef == null) {
            int length = reason.strip().codePointCount(0, reason.strip().length());
            if (length < 10 || length > 1000) {
                throw new ValidationException(ValidationCode.REASON_INVALID, List.of(),
                        List.of(new ValidationIssue("reason", "must be 10..1000 characters", List.of())), Map.of());
            }
            require(actorAssurance == ActorAssurance.DECLARED && authorId instanceof AuthorId, "actor_assurance",
                    "a native entry carries a declared author");
            require(op != HistoryOp.TRANSITION || !VERIFICATION_STATES.contains(toState)
                    || Channel.REVIEWER.login().equals(login), "login", "a verification needs the reviewer channel");
        }
    }

    private static void require(boolean condition, String path, String reason) {
        if (!condition) {
            throw ValidationException.invalid(path, reason);
        }
    }
}
