package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;

/** SVC-BAT-03: a batch is all or nothing, on the PostgreSQL of the test PC. */
@LedgerServiceTest
class BatchRollbackTest {

    @Test
    @DisplayName("SVC-BAT-03: a failing operation names itself and rolls back defects, history, receipts and numbers")
    void bat03_allOrNothing(Scenario s) {
        SaveResult existing = s.newDefect("existing");
        String actor = s.actorWith("defect");
        String requestId = Scenario.uuid();
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.Save("defect", Fields.of(s.defectFields("created by the batch")), Scenario.REASON, null, null,
                        null),
                new BatchOpInput.Transition("defect", existing.id(), 9L, "DISPUTED", Scenario.REASON,
                        Scenario.EVIDENCE, null, null, null));
        LedgerException e = Expect.failure(() -> s.bean(BatchService.class).batch(actor, requestId, ops),
                ConflictCode.VERSION_CONFLICT);
        assertThat(e.details()).containsAllEntriesOf(Map.of("op_index", List.of("2"), "op", List.of("transition")));
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE author_id = ?", actor)).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE author_id = ?", actor)).isZero();
        assertThat(s.newDefect("next").id()).as("the number of the rolled-back op is reused")
                .isEqualTo(s.defectId(2));
    }
}
