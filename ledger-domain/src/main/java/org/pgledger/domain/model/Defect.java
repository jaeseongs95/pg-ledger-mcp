package org.pgledger.domain.model;

import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.ProjectId;

/**
 * A defect. {@code caseSeq} is a lock counter bumped by recording a case (it serialises with a merge of the same
 * row) and is never written by saving this record; it does not change {@code version}. {@code version} is 0 until
 * the first save, which stores 1.
 */
@Table("defect")
public record Defect(@Id DefectId id, ProjectId projectId, String title, CodeValue category, CodeValue severity,
        DefectStatus status, DefectId mergedInto, String behavior, String rootCause, ActorId ownerActorId,
        @ReadOnlyProperty long caseSeq, @Version long version, Instant createdAt, Instant updatedAt,
        @CreatedBy @InsertOnlyProperty ActorId createdBy) {

    public Defect {
        if ((status == DefectStatus.MERGED) != (mergedInto != null)) {
            throw ValidationException.invalid("merged_into", ValidationReason.SET_EXACTLY_WHEN_STATUS_IS, "MERGED");
        }
        if (id.equals(mergedInto)) {
            throw ValidationException.invalid("merged_into", ValidationReason.MUST_NOT_BE_THE_DEFECT_ITSELF);
        }
    }

    /** A new OPEN defect from complete fields. */
    public static Defect open(DefectId id, DefectFields fields, Instant now) {
        DefectFields f = fields.requireCreatable();
        return new Defect(id, f.projectId().orElse(null), f.title().orElse(null), f.category().orElse(null),
                f.severity().orElse(null), DefectStatus.OPEN, null, f.behavior().orElse(null),
                f.rootCause().orElse(null), f.ownerActorId().orElse(null), 0, 0, now, now, null);
    }

    public Defect edited(DefectFields f, Instant now) {
        return new Defect(id, projectId, f.title().orElse(title), f.category().orElse(category),
                f.severity().orElse(severity), status, mergedInto, f.behavior().orElse(behavior),
                f.rootCause().orElse(rootCause), f.ownerActorId().<ActorId>map(a -> a).orElse(ownerActorId),
                caseSeq, version, createdAt, now, createdBy);
    }

    /** The defect after a state change; {@code mergedInto} must be set exactly for MERGED. */
    public Defect transitioned(DefectStatus to, DefectId newMergedInto, Instant now) {
        return new Defect(id, projectId, title, category, severity, to, newMergedInto, behavior, rootCause,
                ownerActorId, caseSeq, version, createdAt, now, createdBy);
    }

    public Defect ownedBy(ActorId owner, Instant now) {
        return new Defect(id, projectId, title, category, severity, status, mergedInto, behavior, rootCause, owner,
                caseSeq, version, createdAt, now, createdBy);
    }
}
