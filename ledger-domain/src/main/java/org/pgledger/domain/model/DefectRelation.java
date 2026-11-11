package org.pgledger.domain.model;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.DefectId;

/**
 * A relation between two different defects. The pair is stored in canonical order ({@code a} sorts before
 * {@code b}), so (b, a) and (a, b) are the same relation. The relation kind is a RELATION_KIND common code.
 */
public record DefectRelation(DefectId a, DefectId b, CodeValue relation, ActorId createdBy) implements Link {

    public DefectRelation {
        int order = a.value().compareTo(b.value());
        if (order == 0) {
            throw ValidationException.invalid("b", ValidationReason.MUST_DIFFER_FROM, "a");
        }
        if (order > 0) {
            DefectId first = b;
            b = a;
            a = first;
        }
    }

    @Override
    public LinkKind kind() {
        return LinkKind.RELATION;
    }

    @Override
    public HistoryEntity historyEntity() {
        return HistoryEntity.DEFECT_RELATION;
    }

    @Override
    public String historyId() {
        return a.value() + "|" + b.value() + "|" + relation.value();
    }
}
