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

    /** Two different defects in canonical order: {@code first} sorts before {@code second} as text. */
    public record Pair(DefectId first, DefectId second) {

        /**
         * The two defects in canonical order: the one rule of the order, for the relation and for a caller that looks
         * a relation up by its pair.
         *
         * @throws ValidationException when both are the same defect
         */
        public static Pair ordered(DefectId x, DefectId y) {
            int order = x.value().compareTo(y.value());
            if (order == 0) {
                throw ValidationException.invalid("b", ValidationReason.MUST_DIFFER_FROM, "a");
            }
            return order < 0 ? new Pair(x, y) : new Pair(y, x);
        }
    }

    public DefectRelation {
        Pair pair = Pair.ordered(a, b);
        a = pair.first();
        b = pair.second();
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
