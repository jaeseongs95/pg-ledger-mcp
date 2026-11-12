package org.pgledger.domain.model;

import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TextRule;

/** Editable fields of a release condition. */
public record ConditionFields(Patch<ProjectId> projectId, Patch<String> release, Patch<String> goalLabel,
        Patch<String> code, Patch<String> conditionText) implements EntityFields {

    public static ConditionFields of(Patch<String> projectId, Patch<String> release, Patch<String> goalLabel,
            Patch<String> code, Patch<String> conditionText) {
        return new ConditionFields(projectId.map(ProjectId::of), release.map(TextRule.RELEASE::required),
                goalLabel.map(TextRule.GOAL_LABEL::optional), code.map(TextRule.CONDITION_CODE::required),
                conditionText.map(TextRule.CONDITION_TEXT::givenOrEmpty));
    }

    @Override
    public HistoryEntity entity() {
        return HistoryEntity.CONDITION;
    }

    @Override
    public ConditionFields requireCreatable() {
        EntityFields.requireValue("project_id", projectId);
        EntityFields.requireValue("release", release);
        EntityFields.requireValue("code", code);
        EntityFields.requireValue("condition_text", conditionText);
        return this;
    }

    @Override
    public ConditionFields requireEditable() {
        EntityFields.requireAnyMentioned(projectId, release, goalLabel, code, conditionText);
        return this;
    }
}
