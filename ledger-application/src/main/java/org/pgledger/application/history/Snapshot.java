package org.pgledger.application.history;

import java.time.Instant;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.DefectRelation;
import org.pgledger.domain.model.DefectTaskLink;
import org.pgledger.domain.model.Task;

/**
 * The full row as it was just before a change: what the {@code before_data} column of a history row holds. The
 * component names are the column names (snake_case once written as JSON); instants are UTC.
 */
public sealed interface Snapshot {

    record DefectRow(String id, String projectId, String title, String category, String severity, String status,
            String mergedInto, String behavior, String rootCause, String ownerActorId, long caseSeq, long version,
            Instant createdAt, Instant updatedAt, String createdBy) implements Snapshot {

        public static DefectRow of(Defect d) {
            return new DefectRow(d.id().value(), d.projectId().value(), d.title(), d.category().value(),
                    d.severity().value(), d.status().name(), d.mergedInto() == null ? null : d.mergedInto().value(),
                    d.behavior(), d.rootCause(), d.ownerActorId() == null ? null : d.ownerActorId().value(),
                    d.caseSeq(), d.version(), d.createdAt(), d.updatedAt(), d.createdBy().value());
        }
    }

    record TaskRow(String id, String projectId, String title, String purpose, String successCondition, String state,
            String ownerActorId, String originRef, String originQuote, long version, Instant createdAt,
            Instant updatedAt, String createdBy) implements Snapshot {

        public static TaskRow of(Task t) {
            return new TaskRow(t.id().value(), t.projectId().value(), t.title(), t.purpose(), t.successCondition(),
                    t.state().name(), t.ownerActorId() == null ? null : t.ownerActorId().value(), t.originRef(),
                    t.originQuote(), t.version(), t.createdAt(), t.updatedAt(), t.createdBy().value());
        }
    }

    record ConditionRow(String id, String projectId, String releaseName, String goalLabel, String code,
            String conditionText, String state, long version, String createdBy) implements Snapshot {

        public static ConditionRow of(Condition c) {
            return new ConditionRow(c.id().value(), c.projectId().value(), c.release(), c.goalLabel(), c.code(),
                    c.conditionText(), c.state().name(), c.version(), c.createdBy().value());
        }
    }

    record FixRow(long id, String defectId, String repo, String commitSha, String branch, String ciRef,
            String ciConclusion, Instant ciCompletedAt, String recordedBy, Instant recordedAt, String origin)
            implements Snapshot {

        public static FixRow of(DefectFix f) {
            return new FixRow(f.id(), f.defectId().value(), f.repo(), f.commitSha().value(), f.branch(), f.ciRef(),
                    f.ciConclusion() == null ? null : f.ciConclusion().name(), f.ciCompletedAt(),
                    f.recordedBy().value(), f.recordedAt(), f.origin().name());
        }
    }

    record DefectTaskRow(String defectId, String taskId, String createdBy) implements Snapshot {

        public static DefectTaskRow of(DefectTaskLink l) {
            return new DefectTaskRow(l.defectId().value(), l.taskId().value(), l.createdBy().value());
        }
    }

    record DefectRelationRow(String a, String b, String kind, String createdBy) implements Snapshot {

        public static DefectRelationRow of(DefectRelation r) {
            return new DefectRelationRow(r.a().value(), r.b().value(), r.relation().value(), r.createdBy().value());
        }
    }

    record ConditionLinkRow(String linkKey, String conditionId, String taskId, String defectId, String createdBy)
            implements Snapshot {

        public static ConditionLinkRow of(ConditionLink l) {
            return new ConditionLinkRow(l.linkKey(), l.conditionId().value(),
                    l.taskId() == null ? null : l.taskId().value(),
                    l.defectId() == null ? null : l.defectId().value(), l.createdBy().value());
        }
    }
}
