package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;

/** SVC-RCP-09 .. SVC-RCP-11: concurrent duplicates on the PostgreSQL of the test PC, with real threads. */
@LedgerServiceTest
class ReceiptConcurrencyTest {

    private static SaveCommand create(Scenario s, String requestId, String title) {
        return new SaveCommand(requestId, "defect", null, 0L, s.defectFields(title), Scenario.REASON);
    }

    private static Optional<ErrorCode> code(Outcome<?> outcome) {
        return Optional.ofNullable(outcome.failure()).map(e -> ((LedgerException) e).code());
    }

    @Test
    @DisplayName("SVC-RCP-09: 16 threads with the same request id commit exactly once and all see one response")
    void rcp09_sameRequestSamePayload(Scenario s) {
        String id = Scenario.uuid();
        List<Callable<SaveResult>> calls = IntStream.range(0, 16)
                .<Callable<SaveResult>>mapToObj(i -> () -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        create(s, id, "same")))
                .toList();
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(outcomes.stream().map(Outcome::value).distinct()).hasSize(1);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE request_id = ?", id)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-10: 16 threads, 4 payloads: one payload wins, the others conflict")
    void rcp10_sameRequestFourPayloads(Scenario s) {
        String id = Scenario.uuid();
        List<String> titles = IntStream.range(0, 16).mapToObj(i -> "payload " + (i % 4)).toList();
        List<Callable<SaveResult>> calls = new ArrayList<>();
        for (String title : titles) {
            calls.add(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, title)));
        }
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        SaveResult winner = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value();
        String winningTitle = s.defect(winner.id()).title();
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(outcomes.stream().map(ReceiptConcurrencyTest::code).toList()).isEqualTo(titles.stream()
                .map(t -> Objects.equals(t, winningTitle) ?Optional.<ErrorCode>empty()
                        : Optional.<ErrorCode>of(ConflictCode.REQUEST_CONFLICT)).toList());
        assertThat(outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).distinct()).hasSize(1);
    }

    @Test
    @DisplayName("SVC-RCP-11: after the stress and 200 mixed writes every receipt is complete")
    void rcp11_everyReceiptIsComplete(Scenario s) {
        String actor = s.actorWith("defect", "task");
        String id = Scenario.uuid();
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int n = i % 4;
            calls.add(() -> s.bean(SaveService.class).save(actor, create(s, id, "stress " + n)));
        }
        for (int i = 0; i < 200; i++) {
            int n = i;
            calls.add(() -> n % 2 == 0
                    ? s.bean(SaveService.class).save(actor, create(s, null, "mixed " + n))
                    : s.bean(SaveService.class).save(actor, new SaveCommand(null, "task", null, 0L,
                            s.taskFields("mixed " + n), Scenario.REASON)));
        }
        List<Outcome<Object>> outcomes = Parallel.all(calls, 12);
        // a stress write may lose the race of its request id: that is a business failure, anything else is not
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).satisfiesAnyOf(
                failure -> assertThat(failure).isNull(),
                failure -> assertThat(failure).isInstanceOf(LedgerException.class)));
        String mine = "SELECT COUNT(*) FROM request_receipt WHERE author_id = ?";
        assertThat(s.count(mine, actor)).isEqualTo(201);
        assertThat(s.count(mine + " AND response IS NULL", actor)).isZero();
        assertThat(s.count(mine + " AND CHAR_LENGTH(payload_sha256) = 64", actor)).isEqualTo(201);
        assertThat(s.count(mine + " AND operation = 'SAVE' AND login = 'ledger_author'", actor)).isEqualTo(201);
        assertThat(s.count(mine + " AND created_at = TIMESTAMP '2026-11-05 10:00:00'", actor)).isEqualTo(201);
    }
}
