package org.pgledger.domain.rule;

import org.pgledger.domain.value.DefectId;

/** The defect a MERGED transition points at, with the status it has now. */
public record MergeTarget(DefectId id, DefectStatus status) {
}
