// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.ActorAssurance;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.LifecycleState;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.JsonDocument;
import org.veridger.domain.value.Reason;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.Sha256;

/**
 * One row of the append-only history. {@code changes} is the diff of the changed fields, {@code beforeData} the
 * full row as it was just before an EDIT, TRANSITION or UNLINK (absent for every other operation and for imported
 * rows). The record is immutable and the invariants of the CHECK constraints of the table are enforced on construction.
 */
@Table("history")
public record HistoryEntry(@Id Long id, @Column("occurred_at") Instant at, ActorId authorId, String login, ActorAssurance actorAssurance,
        HistoryEntity entity, String entityId, HistoryOp op, String fromState, String toState, JsonDocument changes,
        JsonDocument beforeData, String reason, String evidenceUri, Sha256 evidenceSha256, Long correctsId,
        OperationKey operationKey, String sourceRef, String attachmentId) {

    /** The longest {@code entity_id}, in characters (code points): the length of the column {@code history.entity_id}. */
    public static final int MAX_ENTITY_ID_LENGTH = 300;

    private static final Set<HistoryOp> OPS_WITH_BEFORE_DATA = EnumSet.of(HistoryOp.EDIT, HistoryOp.TRANSITION,
            HistoryOp.UNLINK);
    private static final String OPS_WITH_BEFORE_DATA_TEXT = OPS_WITH_BEFORE_DATA.stream().map(HistoryOp::name)
            .collect(Collectors.joining(", "));

    /**
     * A row without an attachment (an imported row and every row written before attachments existed). {@code attachmentId} is the
     * id of a row of {@code attachment}, a plain column without a foreign key: the write that makes the row checks it.
     */
    public HistoryEntry(Long id, Instant at, ActorId authorId, String login, ActorAssurance actorAssurance,
            HistoryEntity entity, String entityId, HistoryOp op, String fromState, String toState,
            JsonDocument changes, JsonDocument beforeData, String reason, String evidenceUri, Sha256 evidenceSha256,
            Long correctsId, OperationKey operationKey, String sourceRef) {
        this(id, at, authorId, login, actorAssurance, entity, entityId, op, fromState, toState, changes, beforeData,
                reason, evidenceUri, evidenceSha256, correctsId, operationKey, sourceRef, null);
    }

    @PersistenceCreator
    public HistoryEntry {
        if (changes == null) {
            changes = JsonDocument.EMPTY_OBJECT;
        }
        require((op == HistoryOp.TRANSITION) == (toState != null), "to_state", ValidationReason.SET_EXACTLY_FOR,
                "TRANSITION");
        require((op == HistoryOp.CORRECT) == (correctsId != null), "corrects_id", ValidationReason.SET_EXACTLY_FOR,
                "CORRECT");
        require(!op.importedOnly() || sourceRef != null, "source_ref",
                ValidationReason.REQUIRED_FOR_IMPORTED_OPERATIONS);
        require(beforeData == null || OPS_WITH_BEFORE_DATA.contains(op), "before_data",
                ValidationReason.EXISTS_ONLY_FOR, OPS_WITH_BEFORE_DATA_TEXT);
        require(entityId != null && !entityId.isBlank()
                && entityId.codePointCount(0, entityId.length()) <= MAX_ENTITY_ID_LENGTH, "entity_id",
                ValidationReason.MUST_BE_CHARS_RANGE, "1", Integer.toString(MAX_ENTITY_ID_LENGTH));
        require(reason != null && !reason.isBlank(), "reason", ValidationReason.REQUIRED);
        if (sourceRef == null) {
            Reason.validated(reason);
            require(actorAssurance == ActorAssurance.DECLARED && authorId instanceof AuthorId, "actor_assurance",
                    ValidationReason.NATIVE_ENTRY_NEEDS_DECLARED_AUTHOR);
            require(op != HistoryOp.TRANSITION || !LifecycleState.isVerification(entity, toState)
                    || Objects.equals(Channel.REVIEWER.login(), login), "login",
                    ValidationReason.VERIFICATION_NEEDS_REVIEWER_CHANNEL);
        }
    }

    /** Whether this transition records a review decision or withdraws a previous decision. */
    public boolean involvesVerification() {
        return op == HistoryOp.TRANSITION
                && (LifecycleState.isVerification(entity, toState) || LifecycleState.isVerification(entity, fromState)
                    || (Objects.equals(Channel.REVIEWER.login(), login)
                        && LifecycleState.isReviewReturn(fromState, toState)));
    }

    private static void require(boolean condition, String path, ValidationReason reason, String... reasonArgs) {
        if (!condition) {
            throw ValidationException.invalid(path, reason, reasonArgs);
        }
    }
}
