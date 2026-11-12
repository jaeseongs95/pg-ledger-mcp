package org.pgledger.domain.model;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.ProjectId;

/**
 * A release condition (a goal that is met or not met). It has no timestamps. The table is named
 * {@code release_condition} and the release column {@code release_name} because CONDITION and RELEASE are SQL
 * reserved words. {@code version} is 0 until the first save, which stores 1.
 */
@Table("release_condition")
public record Condition(@Id ConditionId id, ProjectId projectId, @Column("release_name") String release,
        String goalLabel, String code, String conditionText, ConditionState state, @Version long version,
        @CreatedBy @InsertOnlyProperty ActorId createdBy) {

    /** A new UNKNOWN condition from complete fields. */
    public static Condition open(ConditionId id, ConditionFields fields) {
        ConditionFields f = fields.requireCreatable();
        return new Condition(id, f.projectId().orNull(), f.release().orNull(), f.goalLabel().orNull(),
                f.code().orNull(), f.conditionText().orNull(), ConditionState.UNKNOWN, 0, null);
    }

    public Condition edited(ConditionFields f) {
        return new Condition(id, f.projectId().orElse(projectId), f.release().orElse(release),
                f.goalLabel().orElse(goalLabel), f.code().orElse(code), f.conditionText().orElse(conditionText),
                state, version, createdBy);
    }

    public Condition transitioned(ConditionState to) {
        return new Condition(id, projectId, release, goalLabel, code, conditionText, to, version, createdBy);
    }
}
