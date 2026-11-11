package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.pgledger.application.history.Snapshot;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The {@code before_data} of a history row as it goes on the wire: the row as it was before the change, with
 * snake_case keys. The records of the application ({@link Snapshot}) have camel case components and get their
 * snake_case keys from the mapper of the ledger when they are stored; the mapper that writes a tool result is not
 * that mapper, so the wire form is declared here, on the type, where every mapper finds it. The components are those
 * of the snapshot, one record for one.
 */
public sealed interface BeforeData {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record DefectRow(String id, String projectId, String title, String category, String severity, String status,
            String mergedInto, String behavior, String rootCause, String ownerActorId, long caseSeq, long version,
            Instant createdAt, Instant updatedAt, String createdBy) implements BeforeData {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record TaskRow(String id, String projectId, String title, String purpose, String successCondition, String state,
            String ownerActorId, String originRef, String originQuote, long version, Instant createdAt,
            Instant updatedAt, String createdBy) implements BeforeData {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ConditionRow(String id, String projectId, String releaseName, String goalLabel, String code,
            String conditionText, String state, long version, String createdBy) implements BeforeData {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record FixRow(long id, String defectId, String repo, String commitSha, String branch, String ciRef,
            String ciConclusion, Instant ciCompletedAt, String recordedBy, Instant recordedAt, String origin)
            implements BeforeData {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record DefectTaskRow(String defectId, String taskId, String createdBy) implements BeforeData {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record DefectRelationRow(String a, String b, String kind, String createdBy) implements BeforeData {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ConditionLinkRow(String linkKey, String conditionId, String taskId, String defectId, String createdBy)
            implements BeforeData {
    }

    /** The wire form of a snapshot (a complete {@code switch}: a new kind of snapshot does not compile here). */
    static BeforeData of(Snapshot snapshot) {
        return switch (snapshot) {
            case Snapshot.DefectRow r -> new DefectRow(r.id(), r.projectId(), r.title(), r.category(), r.severity(),
                    r.status(), r.mergedInto(), r.behavior(), r.rootCause(), r.ownerActorId(), r.caseSeq(),
                    r.version(), r.createdAt(), r.updatedAt(), r.createdBy());
            case Snapshot.TaskRow r -> new TaskRow(r.id(), r.projectId(), r.title(), r.purpose(),
                    r.successCondition(), r.state(), r.ownerActorId(), r.originRef(), r.originQuote(), r.version(),
                    r.createdAt(), r.updatedAt(), r.createdBy());
            case Snapshot.ConditionRow r -> new ConditionRow(r.id(), r.projectId(), r.releaseName(), r.goalLabel(),
                    r.code(), r.conditionText(), r.state(), r.version(), r.createdBy());
            case Snapshot.FixRow r -> new FixRow(r.id(), r.defectId(), r.repo(), r.commitSha(), r.branch(),
                    r.ciRef(), r.ciConclusion(), r.ciCompletedAt(), r.recordedBy(), r.recordedAt(), r.origin());
            case Snapshot.DefectTaskRow r -> new DefectTaskRow(r.defectId(), r.taskId(), r.createdBy());
            case Snapshot.DefectRelationRow r -> new DefectRelationRow(r.a(), r.b(), r.kind(), r.createdBy());
            case Snapshot.ConditionLinkRow r -> new ConditionLinkRow(r.linkKey(), r.conditionId(), r.taskId(),
                    r.defectId(), r.createdBy());
        };
    }
}
