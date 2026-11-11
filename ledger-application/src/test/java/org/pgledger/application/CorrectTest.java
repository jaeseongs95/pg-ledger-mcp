package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.WriteResult.CorrectResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import tools.jackson.databind.json.JsonMapper;

/** SVC-COR-01 .. SVC-COR-05: corrections. */
@LedgerServiceTest
class CorrectTest {

    private CorrectResult correct(Scenario s, long historyId) {
        return s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(null, historyId,
                Scenario.REASON));
    }

    @Test
    @DisplayName("SVC-COR-01: a correction is a new row that points at the wrong one, which stays as it was")
    void cor01_correction(Scenario s) {
        SaveResult d = s.newDefect("d");
        HistoryEntry before = s.historyRow(d.historyId());
        CorrectResult result = correct(s, d.historyId());
        assertThat(result.corrects()).isEqualTo(d.historyId());
        HistoryEntry row = s.historyRow(result.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.CORRECT);
        assertThat(row.correctsId()).isEqualTo(d.historyId());
        assertThat(row.beforeData()).isNull();
        assertThat(s.historyRow(d.historyId())).isEqualTo(before);
        Map<?, ?> keys = new JsonMapper().readValue(s.bean(LedgerJson.class).write(result).text(), Map.class);
        assertThat(keys.keySet()).isEqualTo(Set.of("status", "corrects", "history_id", "author", "login"));
    }

    @Test
    @DisplayName("SVC-COR-02: an unknown history id")
    void cor02_unknown(Scenario s) {
        Expect.failure(() -> correct(s, 999_999_999L), LookupCode.NOT_FOUND);
    }

    /** A history row of one kind of entity, made through the services. */
    record Row(HistoryEntity entity, Function<Scenario, Long> make) {
        @Override
        public String toString() {
            return entity.token();
        }
    }

    static Stream<Row> everyEntity() {
        return Stream.of(
                new Row(HistoryEntity.DEFECT, s -> s.newDefect("d").historyId()),
                new Row(HistoryEntity.DEFECT_FIX, s -> s.fix(Scenario.AUTHOR, s.newDefect("d").id(),
                        Scenario.commit('a')).historyId()),
                new Row(HistoryEntity.DEFECT_CASE, s -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR,
                        new RecordCaseCommand(null, s.newDefect("d").id(), "seen", Scenario.REASON, null, null, null))
                        .historyId()),
                new Row(HistoryEntity.DEFECT_TASK, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(null, "defect_task", s.newDefect("d").id(), s.newTask("t").id(),
                                Scenario.REASON, null)).historyId()),
                new Row(HistoryEntity.DEFECT_RELATION, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(null, "blocks", s.newDefect("a").id(), s.newDefect("b").id(),
                                Scenario.REASON, null)).historyId()),
                new Row(HistoryEntity.TASK, s -> s.newTask("t").historyId()),
                new Row(HistoryEntity.CONDITION, s -> s.newCondition("1", "r", "c").historyId()),
                new Row(HistoryEntity.CONDITION_LINK, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(null, "condition_task", s.newCondition("1", "r", "c").id(),
                                s.newTask("t").id(), Scenario.REASON, null)).historyId()));
    }

    @ParameterizedTest
    @MethodSource("everyEntity")
    @DisplayName("SVC-COR-03: every kind of entity can be corrected, the project is found from its id")
    void cor03_everyEntity(Row row, Scenario s) {
        long wrong = row.make().apply(s);
        CorrectResult result = correct(s, wrong);
        HistoryEntry original = s.historyRow(wrong);
        HistoryEntry correction = s.historyRow(result.historyId());
        assertThat(correction.entity()).isEqualTo(row.entity());
        assertThat(correction.entityId()).isEqualTo(original.entityId());
    }

    @Test
    @DisplayName("SVC-COR-04: a verification is corrected through the reviewer channel only")
    void cor04_verification(Scenario s) {
        SaveResult d = s.verifiedDefect("d");
        long verification = s.history(HistoryEntity.DEFECT, d.id()).stream()
                .filter(h -> h.op() == HistoryOp.TRANSITION && "VERIFIED".equals(h.toState())).findFirst()
                .orElseThrow().id();
        Expect.failure(() -> correct(s, verification), TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
        CorrectResult result = s.bean(ReviewService.class).reviewCorrect(Scenario.REVIEWER, new CorrectCommand(null,
                verification, Scenario.REASON));
        assertThat(s.historyRow(result.historyId()).login()).isEqualTo("ledger_reviewer");
    }

    @Test
    @DisplayName("SVC-COR-05: a correction can itself be corrected (unchanged by design)")
    void cor05_correctACorrection(Scenario s) {
        SaveResult d = s.newDefect("d");
        CorrectResult first = correct(s, d.historyId());
        assertThat(correct(s, first.historyId()).corrects()).isEqualTo(first.historyId());
    }

    @Test
    @DisplayName("SVC-COR-05: a replay returns the stored correction")
    void cor05_replay(Scenario s) {
        SaveResult d = s.newDefect("d");
        CorrectCommand command = new CorrectCommand(Scenario.uuid(), d.historyId(), Scenario.REASON);
        CorrectResult first = s.bean(CorrectService.class).correct(Scenario.AUTHOR, command);
        assertThat(s.bean(CorrectService.class).correct(Scenario.AUTHOR, command)).isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-COR-05: a short reason")
    void cor05_shortReason(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(null,
                d.historyId(), "short")), ValidationCode.REASON_INVALID);
    }
}
