package org.pgledger.domain.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.LifecycleState;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;

/**
 * One row of the append-only history. {@code changes} is the diff of the changed fields, {@code beforeData} the
 * full row as it was just before an EDIT, TRANSITION or UNLINK (absent for every other operation and for imported
 * rows). The record is immutable and the invariants of the old CHECK constraints are enforced on construction.
 */
@Table("history")
public record HistoryEntry(@Id Long id, @Column("occurred_at") Instant at, ActorId authorId, String login, ActorAssurance actorAssurance,
        HistoryEntity entity, String entityId, HistoryOp op, String fromState, String toState, JsonDocument changes,
        JsonDocument beforeData, String reason, String evidenceUri, Sha256 evidenceSha256, Long correctsId,
        RequestId requestId, String legacyRef) {

    private static final Set<HistoryOp> OPS_WITH_BEFORE_DATA = EnumSet.of(HistoryOp.EDIT, HistoryOp.TRANSITION,
            HistoryOp.UNLINK);
    private static final String OPS_WITH_BEFORE_DATA_TEXT = OPS_WITH_BEFORE_DATA.stream().map(HistoryOp::name)
            .collect(Collectors.joining(", "));

    public HistoryEntry {
        if (changes == null) {
            changes = JsonDocument.EMPTY_OBJECT;
        }
        require((op == HistoryOp.TRANSITION) == (toState != null), "to_state", ValidationReason.SET_EXACTLY_FOR,
                "TRANSITION");
        require((op == HistoryOp.CORRECT) == (correctsId != null), "corrects_id", ValidationReason.SET_EXACTLY_FOR,
                "CORRECT");
        require(!op.legacyOnly() || legacyRef != null, "legacy_ref",
                ValidationReason.REQUIRED_FOR_IMPORTED_OPERATIONS);
        require(beforeData == null || OPS_WITH_BEFORE_DATA.contains(op), "before_data",
                ValidationReason.EXISTS_ONLY_FOR, OPS_WITH_BEFORE_DATA_TEXT);
        require(entityId != null && !entityId.isBlank() && entityId.length() <= 300, "entity_id",
                ValidationReason.MUST_BE_CHARS_RANGE, "1", "300");
        require(reason != null && !reason.isBlank(), "reason", ValidationReason.REQUIRED);
        if (legacyRef == null) {
            Reason.validated(reason);
            require(actorAssurance == ActorAssurance.DECLARED && authorId instanceof AuthorId, "actor_assurance",
                    ValidationReason.NATIVE_ENTRY_NEEDS_DECLARED_AUTHOR);
            require(op != HistoryOp.TRANSITION || !LifecycleState.isVerification(entity, toState)
                    || Objects.equals(Channel.REVIEWER.login(), login), "login",
                    ValidationReason.VERIFICATION_NEEDS_REVIEWER_CHANNEL);
        }
    }

    /** Whether this entry is a transition into or out of a verification state: such a change is a verification. */
    public boolean involvesVerification() {
        return op == HistoryOp.TRANSITION
                && (LifecycleState.isVerification(entity, toState) || LifecycleState.isVerification(entity, fromState));
    }

    private static void require(boolean condition, String path, ValidationReason reason, String... reasonArgs) {
        if (!condition) {
            throw ValidationException.invalid(path, reason, reasonArgs);
        }
    }
}
