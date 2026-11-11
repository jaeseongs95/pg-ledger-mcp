package org.pgledger.domain.model;

import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;

/** A task. {@code version} is 0 until the first save, which stores 1. */
@Table("task")
public record Task(@Id TaskId id, ProjectId projectId, String title, String purpose, String successCondition,
        TaskState state, ActorId ownerActorId, String originRef, String originQuote, @Version long version,
        Instant createdAt, Instant updatedAt, @CreatedBy @InsertOnlyProperty ActorId createdBy) {

    /** A new PLANNED task from complete fields. */
    public static Task plan(TaskId id, TaskFields fields, Instant now) {
        TaskFields f = fields.requireCreatable();
        return new Task(id, f.projectId().orElse(null), f.title().orElse(null), f.purpose().orElse(null),
                f.successCondition().orElse(null), TaskState.PLANNED, f.ownerActorId().orElse(null),
                f.originRef().orElse(null), f.originQuote().orElse(null), 0, now, now, null);
    }

    public Task edited(TaskFields f, Instant now) {
        return new Task(id, f.projectId().orElse(projectId), f.title().orElse(title), f.purpose().orElse(purpose),
                f.successCondition().orElse(successCondition), state, f.ownerActorId().<ActorId>map(a -> a)
                        .orElse(ownerActorId), f.originRef().orElse(originRef), f.originQuote().orElse(originQuote),
                version, createdAt, now, createdBy);
    }

    public Task transitioned(TaskState to, Instant now) {
        return new Task(id, projectId, title, purpose, successCondition, to, ownerActorId, originRef, originQuote,
                version, createdAt, now, createdBy);
    }
}
