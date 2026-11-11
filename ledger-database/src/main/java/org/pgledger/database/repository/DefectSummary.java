package org.pgledger.database.repository;

import java.time.Instant;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.DefectId;

/** The columns a defect list shows. */
public record DefectSummary(DefectId id, String title, CodeValue category, CodeValue severity, DefectStatus status,
        long version, Instant createdAt, Instant updatedAt) {
}
