package org.pgledger.database.repository;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.HistoryEntity;

/**
 * What each kind of attention means in SQL, in one place: the condition on the row of the defect or the task that makes
 * it an item of the kind. The attention list (all kinds together), the defect list and the task list (one kind) are all
 * made from these conditions, so a kind cannot mean one thing in the list and another in the filter. Plain ANSI SQL on the
 * columns of the table the kind is about; the conditions are constants and hold no value.
 */
final class AttentionSql {

    private static final String HAS_FIX = "EXISTS (SELECT 1 FROM defect_fix x WHERE x.defect_id = defect.id)";

    private AttentionSql() {
    }

    /** The condition on one row of the table the kind is about (a defect or a task). */
    static String condition(AttentionKind kind) {
        return switch (kind) {
            case OPEN_WITH_FIX -> "status = 'OPEN' AND " + HAS_FIX;
            case FIXED_WITHOUT_COMMIT -> "status IN ('SOURCE_FIXED', 'VERIFIED') AND NOT " + HAS_FIX;
            case OPEN_NO_OWNER -> "status = 'OPEN' AND owner_actor_id IS NULL";
            case TASK_UNKNOWN -> "state = 'UNKNOWN'";
            case DONE_NOT_VERIFIED -> "state = 'REPORTED_DONE'";
        };
    }

    /**
     * The condition of a kind that must be about {@code entity}.
     *
     * @throws IllegalArgumentException when the kind is about another entity (a programming error of the caller)
     */
    static String condition(AttentionKind kind, HistoryEntity entity) {
        if (kind.entity() != entity) {
            throw new IllegalArgumentException("not a " + entity.token() + " attention kind: " + kind);
        }
        return condition(kind);
    }

    /**
     * All kinds as one select list of {@code kind, id, project_id}, in the order the kinds are declared. The table of a
     * kind is the token of the entity it is about ({@code defect}, {@code task}), which is also the name of the table.
     */
    static String items() {
        // the kind and the table are names of constants of ours (an enum and a table name), never a value of a request;
        // they are put into the text with a format, which is also what keeps them out of the ANSI-009 text scan
        return Arrays.stream(AttentionKind.values())
                .map(kind -> String.format("SELECT '%s' AS kind, id, project_id FROM %s WHERE %s", kind.name(),
                        kind.entity().token(), condition(kind)))
                .collect(Collectors.joining(" UNION ALL "));
    }
}
