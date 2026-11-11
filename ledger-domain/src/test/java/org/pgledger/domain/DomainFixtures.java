package org.pgledger.domain;

import java.time.Instant;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.ProjectId;

/** Small builders shared by the domain tests. */
final class DomainFixtures {

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private DomainFixtures() {
    }

    static Defect defect(DefectStatus status, DefectId mergedInto) {
        return new Defect(DefectId.of("E2E-D-0001"), ProjectId.of("e2e"), "title", CodeValue.of("ARC"),
                CodeValue.of("P2"), status, mergedInto, "behavior", "cause", AuthorId.of("claude:me"), 0, 1, NOW, NOW,
                AuthorId.of("claude:me"));
    }
}
