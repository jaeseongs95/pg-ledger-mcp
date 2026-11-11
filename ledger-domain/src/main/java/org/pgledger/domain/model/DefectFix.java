package org.pgledger.domain.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.FixOrigin;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TextRule;

/** A fix commit of a defect, with optional CI data. The id comes from the id counter. */
@Table("defect_fix")
public record DefectFix(@Id Long id, DefectId defectId, String repo, CommitSha commitSha, String branch,
        String ciRef, CiConclusion ciConclusion, Instant ciCompletedAt, ActorId recordedBy, Instant recordedAt,
        FixOrigin origin) {

    public DefectFix {
        if (ciConclusion != null && ciRef == null) {
            throw ValidationException.invalid("ci_ref", "is required when ci_conclusion is set");
        }
    }

    /** A fix recorded now by this ledger. */
    public static DefectFix record(long id, DefectId defectId, String repo, CommitSha commit, String branch,
            String ciRef, CiConclusion conclusion, Instant ciCompletedAt, ActorId by, Instant now) {
        return new DefectFix(id, defectId, TextRule.REPO.required(repo), commit, TextRule.BRANCH.optional(branch),
                TextRule.CI_REF.optional(ciRef), conclusion, ciCompletedAt, by, now, FixOrigin.NATIVE);
    }

    /** The same fix with new CI data. */
    public DefectFix withCi(String newCiRef, CiConclusion conclusion, Instant completedAt) {
        return new DefectFix(id, defectId, repo, commitSha, branch, TextRule.CI_REF.optional(newCiRef), conclusion,
                completedAt, recordedBy, recordedAt, origin);
    }
}
