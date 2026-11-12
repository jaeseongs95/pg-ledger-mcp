package org.pgledger.domain.rule;

import java.util.Arrays;
import java.util.List;

/** The kinds of item that need a look; each is about defects or about tasks. */
public enum AttentionKind {
    OPEN_WITH_FIX(HistoryEntity.DEFECT),
    FIXED_WITHOUT_COMMIT(HistoryEntity.DEFECT),
    OPEN_NO_OWNER(HistoryEntity.DEFECT),
    TASK_UNKNOWN(HistoryEntity.TASK),
    DONE_NOT_VERIFIED(HistoryEntity.TASK);

    private final HistoryEntity entity;

    AttentionKind(HistoryEntity entity) {
        this.entity = entity;
    }

    /** The kind of row the item is: {@link HistoryEntity#DEFECT} or {@link HistoryEntity#TASK}. */
    public HistoryEntity entity() {
        return entity;
    }

    /** The kinds that are about {@code entity}, in declaration order; none for an entity that has no such kind. */
    public static List<AttentionKind> of(HistoryEntity entity) {
        return Arrays.stream(values()).filter(kind -> kind.entity == entity).toList();
    }
}
