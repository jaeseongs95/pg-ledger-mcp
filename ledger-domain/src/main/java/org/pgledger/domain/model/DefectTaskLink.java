package org.pgledger.domain.model;

import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TaskId;

/** A defect that is worked on by a task. */
public record DefectTaskLink(DefectId defectId, TaskId taskId, ActorId createdBy) implements Link {

    @Override
    public LinkKind kind() {
        return LinkKind.DEFECT_TASK;
    }

    @Override
    public HistoryEntity historyEntity() {
        return HistoryEntity.DEFECT_TASK;
    }

    @Override
    public String historyId() {
        return defectId.value() + "|" + taskId.value();
    }
}
