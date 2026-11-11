package org.pgledger.domain.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TextRule;

/** One observation of a defect (symptom, log, user report). Insert-only; the id comes from the id counter. */
@Table("defect_case")
public record DefectCase(@Id Long id, DefectId defectId, String observation, String evidence, String userQuote,
        Instant observedAt, ActorId recordedBy, Instant recordedAt) {

    public static DefectCase observe(long id, DefectId defectId, String observation, String evidence,
            String userQuote, Instant observedAt, ActorId by, Instant now) {
        return new DefectCase(id, defectId, TextRule.OBSERVATION.required(observation),
                TextRule.EVIDENCE.optional(evidence), TextRule.USER_QUOTE.optional(userQuote), observedAt, by, now);
    }
}
