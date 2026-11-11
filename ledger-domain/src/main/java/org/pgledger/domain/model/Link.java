package org.pgledger.domain.model;

import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.value.ActorId;

/**
 * A link between two entities. Every variant has a canonical, unambiguous history id and the structural kind;
 * links are the only rows that are deleted (an UNLINK), and the history keeps the story.
 */
public sealed interface Link permits DefectTaskLink, DefectRelation, ConditionLink {

    LinkKind kind();

    HistoryEntity historyEntity();

    /** The {@code entity_id} of the history rows of this link. */
    String historyId();

    ActorId createdBy();
}
