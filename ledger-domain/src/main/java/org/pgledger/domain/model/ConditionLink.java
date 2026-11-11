package org.pgledger.domain.model;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TaskId;

/**
 * A release condition linked to exactly one task or exactly one defect. {@code linkKey} is the never-null
 * normalised key under the UNIQUE constraint: the length of the condition id, the condition id, a marker for the
 * target kind and the target id, so ids that contain the separator cannot collide.
 */
public record ConditionLink(ConditionId conditionId, TaskId taskId, DefectId defectId, ActorId createdBy)
        implements Link {

    public ConditionLink {
        if ((taskId == null) == (defectId == null)) {
            throw ValidationException.invalid("target", ValidationReason.MUST_BE_EXACTLY_ONE_OF, "task", "defect");
        }
    }

    public static ConditionLink toTask(ConditionId condition, TaskId task, ActorId by) {
        return new ConditionLink(condition, task, null, by);
    }

    public static ConditionLink toDefect(ConditionId condition, DefectId defect, ActorId by) {
        return new ConditionLink(condition, null, defect, by);
    }

    public String linkKey() {
        String marker = taskId != null ? "T" : "D";
        String target = taskId != null ? taskId.value() : defectId.value();
        return conditionId.value().length() + ":" + conditionId.value() + "|" + marker + "|" + target;
    }

    @Override
    public LinkKind kind() {
        return taskId != null ? LinkKind.CONDITION_TASK : LinkKind.CONDITION_DEFECT;
    }

    @Override
    public HistoryEntity historyEntity() {
        return HistoryEntity.CONDITION_LINK;
    }

    @Override
    public String historyId() {
        return conditionId.value() + "|" + (taskId != null ? taskId.value() : defectId.value());
    }
}
