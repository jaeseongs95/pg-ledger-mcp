package org.pgledger.application.history;

import org.pgledger.domain.rule.HistoryEntity;

/**
 * What a history row is about: the kind of row and its id as the history keeps it. The row of a link, a fix or a case
 * has no id of its own that a lookup can use: its id is composed of the id of the defect or condition it hangs on and
 * the rest ({@code defect|task}, {@code defect@commit}, {@code defect|case-number}, {@code condition|link-key}), so the
 * first part is the id of the row that owns it, and this is the one place that reads the composition.
 */
public record HistoryKey(HistoryEntity entity, String id) {

    private static final char FIX_SEPARATOR = '@';
    private static final char LINK_SEPARATOR = '|';

    /** The id of the defect, task or condition the row hangs on (the id itself for the row of one of these). */
    public String ownerId() {
        return switch (entity) {
            case DEFECT, TASK, CONDITION -> id;
            case DEFECT_FIX -> before(FIX_SEPARATOR);
            case DEFECT_TASK, DEFECT_RELATION, DEFECT_CASE, CONDITION_LINK -> before(LINK_SEPARATOR);
        };
    }

    private String before(char separator) {
        int at = id.indexOf(separator);
        return at < 0 ? id : id.substring(0, at);
    }
}
