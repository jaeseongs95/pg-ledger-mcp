package org.pgledger.domain.model;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.ProjectId;

/**
 * The editable fields of a defect, task or condition as they arrive in a save: each field is absent, set, or set
 * to nothing. A create must set every required field; an edit must mention at least one field.
 */
public sealed interface EntityFields permits DefectFields, TaskFields, ConditionFields {

    HistoryEntity entity();

    /** The project of the row: every kind of row has one, and a save may mention it. */
    Patch<ProjectId> projectId();

    /** Fails with VALIDATION_FAILED when a required field is not set to a value. */
    EntityFields requireCreatable();

    /** Fails with VALIDATION_FAILED when no field is mentioned. */
    EntityFields requireEditable();

    static void requireValue(String path, Patch<?> patch) {
        if (!patch.hasValue()) {
            throw ValidationException.invalid(path, ValidationReason.REQUIRED);
        }
    }

    static void requireAnyMentioned(Patch<?>... patches) {
        for (Patch<?> patch : patches) {
            if (patch.mentioned()) {
                return;
            }
        }
        throw ValidationException.invalid("fields", ValidationReason.MUST_MENTION_A_FIELD);
    }
}
