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
import org.pgledger.domain.value.TextRule;

/**
 * A release condition (a goal that is met or not met). It has no timestamps. The table is named
 * {@code release_condition} and the release column {@code release_name} because CONDITION and RELEASE are SQL
 * reserved words. {@code version} is 0 until the first save, which stores 1.
 */
@Table("release_condition")
public record Condition(@Id ConditionId id, ProjectId projectId, @Column("release_name") String release,
        String goalLabel, String code, String conditionText, ConditionState state, @Version long version,
        @CreatedBy @InsertOnlyProperty ActorId createdBy, @InsertOnlyProperty String idKey) {

    /**
     * {@code conditionText} may be empty: the row holds NULL then (the 1.x column allowed {@code ''}) and the tools show
     * {@code ""}. {@code idKey} is the id in upper case, the column under the UNIQUE constraint that refuses ids that differ
     * only in case; it is derived from the id, whatever is passed.
     */
    public Condition {
        conditionText = TextRule.emptyToNull(conditionText);
        idKey = id == null ? null : id.key();
    }

    /** A new UNKNOWN condition from complete fields. */
    public static Condition open(ConditionId id, ConditionFields fields) {
        ConditionFields f = fields.requireCreatable();
        return new Condition(id, f.projectId().orNull(), f.release().orNull(), f.goalLabel().orNull(),
                f.code().orNull(), f.conditionText().orNull(), ConditionState.UNKNOWN, 0, null, null);
    }

    public Condition edited(ConditionFields f) {
        return new Condition(id, f.projectId().orElse(projectId), f.release().orElse(release),
                f.goalLabel().orElse(goalLabel), f.code().orElse(code), f.conditionText().orElse(conditionText),
                state, version, createdBy, idKey);
    }

    public Condition transitioned(ConditionState to) {
        return new Condition(id, projectId, release, goalLabel, code, conditionText, to, version, createdBy, idKey);
    }
}
