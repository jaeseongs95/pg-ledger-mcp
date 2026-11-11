package org.pgledger.database.repository;

import java.time.Instant;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.DefectId;

/**
 * The columns a defect list shows. {@code owner} is the owner of the defect, or the session of the latest fix when
 * the defect has no owner of its own (the owner as the old defect view showed it).
 */
public record DefectSummary(DefectId id, String title, CodeValue category, CodeValue severity, DefectStatus status,
        DefectId mergedInto, String owner, long version, Instant createdAt, Instant updatedAt) {
}
