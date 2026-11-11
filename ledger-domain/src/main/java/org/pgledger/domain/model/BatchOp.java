package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.LifecycleState;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.EntityId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.RequestId;

/**
 * One operation of a batch. The records hold the typed, validated arguments; reading the JSON form (the
 * {@code op} discriminator and the key sets of {@link BatchOpKind}) is the job of the boundary. An operation
 * without its own request id gets one derived from the batch id and its position.
 */
public sealed interface BatchOp {

    BatchOpKind kind();

    /** The operation's own request id, or {@code null}. */
    RequestId requestId();

    record SaveOp(EntityFields fields, String id, long expectedVersion, Reason reason, RequestId requestId)
            implements BatchOp {
        @Override
        public BatchOpKind kind() {
            return BatchOpKind.SAVE;
        }
    }

    record TransitionOp(HistoryEntity entity, EntityId id, long expectedVersion, LifecycleState to, Reason reason,
            Evidence evidence, DefectId mergedInto, RequestId requestId) implements BatchOp {
        @Override
        public BatchOpKind kind() {
            return BatchOpKind.TRANSITION;
        }
    }

    record RecordFixOp(DefectId defectId, String repo, CommitSha commit, Reason reason, String branch, String ciRef,
            CiConclusion ciConclusion, Instant ciCompletedAt, boolean markSourceFixed, RequestId requestId)
            implements BatchOp {
        @Override
        public BatchOpKind kind() {
            return BatchOpKind.RECORD_FIX;
        }
    }

    record RecordCaseOp(DefectId defectId, String observation, Reason reason, String evidence, String userQuote,
            Instant observedAt, RequestId requestId) implements BatchOp {
        @Override
        public BatchOpKind kind() {
            return BatchOpKind.RECORD_CASE;
        }
    }

    record LinkOp(LinkKind linkKind, CodeValue relation, EntityId a, EntityId b, boolean remove, Reason reason,
            RequestId requestId) implements BatchOp {
        @Override
        public BatchOpKind kind() {
            return BatchOpKind.LINK;
        }
    }

    record CorrectOp(long historyId, Reason reason, RequestId requestId) implements BatchOp {
        @Override
        public BatchOpKind kind() {
            return BatchOpKind.CORRECT;
        }
    }
}
