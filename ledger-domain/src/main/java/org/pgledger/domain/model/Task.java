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
import org.pgledger.domain.value.TextRule;

/**
 * A task. {@code version} is 0 until the first save, which stores 1. {@code purpose} and {@code successCondition} may be empty:
 * the row holds NULL then (the 1.x columns allowed {@code ''}; no column of this schema holds it) and the tools show
 * {@code ""}. {@code idKey} is the id in upper case, the column under the UNIQUE constraint that refuses ids that differ only
 * in case; it is derived from the id, whatever is passed.
 */
@Table("task")
public record Task(@Id TaskId id, ProjectId projectId, String title, String purpose, String successCondition,
        TaskState state, ActorId ownerActorId, String originRef, String originQuote, @Version long version,
        Instant createdAt, Instant updatedAt, @CreatedBy @InsertOnlyProperty ActorId createdBy,
        @InsertOnlyProperty String idKey) {

    public Task {
        purpose = TextRule.emptyToNull(purpose);
        successCondition = TextRule.emptyToNull(successCondition);
        idKey = id == null ? null : id.key();
    }

    /** A new PLANNED task from complete fields. */
    public static Task plan(TaskId id, TaskFields fields, Instant now) {
        TaskFields f = fields.requireCreatable();
        return new Task(id, f.projectId().orNull(), f.title().orNull(), f.purpose().orNull(),
                f.successCondition().orNull(), TaskState.PLANNED, f.ownerActorId().orNull(),
                f.originRef().orNull(), f.originQuote().orNull(), 0, now, now, null, null);
    }

    public Task edited(TaskFields f, Instant now) {
        return new Task(id, f.projectId().orElse(projectId), f.title().orElse(title), f.purpose().orElse(purpose),
                f.successCondition().orElse(successCondition), state, f.ownerActorId().<ActorId>map(a -> a)
                        .orElse(ownerActorId), f.originRef().orElse(originRef), f.originQuote().orElse(originQuote),
                version, createdAt, now, createdBy, idKey);
    }

    public Task transitioned(TaskState to, Instant now) {
        return new Task(id, projectId, title, purpose, successCondition, to, ownerActorId, originRef, originQuote,
                version, createdAt, now, createdBy, idKey);
    }
}
