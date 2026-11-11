package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.WriteResult.RecordCaseResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.DefectCase;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.DefectId;
import tools.jackson.databind.json.JsonMapper;

/** SVC-CAS-01 .. SVC-CAS-11, -15, -16 (the read side, -12 .. -14, is in the read test). */
@LedgerServiceTest
class CaseTest {

    private static RecordCaseCommand observe(String defect, String observation) {
        return new RecordCaseCommand(null, defect, observation, Scenario.REASON, null, null, null);
    }

    private RecordCaseResult record(Scenario s, String actor, RecordCaseCommand command) {
        return s.bean(RecordCaseService.class).recordCase(actor, command);
    }

    private List<DefectCase> cases(Scenario s, String defect) {
        return s.bean(DefectCaseRepository.class).findByDefectIdOrderById(DefectId.of(defect));
    }

    @Test
    @DisplayName("SVC-CAS-01: a case keeps every field and the result carries the contract keys")
    void cas01_fullCase(Scenario s) {
        SaveResult d = s.newDefect("d");
        RecordCaseResult result = record(s, Scenario.AUTHOR, new RecordCaseCommand(null, d.id(), "it crashed",
                Scenario.REASON, "log line", "the user said so", "2026-11-05T09:30:00+01:00"));
        assertThat(result.entity()).isEqualTo("defect_case");
        assertThat(result.id()).isEqualTo(d.id() + "|" + result.caseId());
        assertThat(result.defect()).isEqualTo(d.id());
        DefectCase stored = cases(s, d.id()).getFirst();
        assertThat(stored.observation()).isEqualTo("it crashed");
        assertThat(stored.evidence()).isEqualTo("log line");
        assertThat(stored.userQuote()).isEqualTo("the user said so");
        assertThat(stored.observedAt()).isEqualTo(Instant.parse("2026-11-05T08:30:00Z"));
        assertThat(stored.recordedBy().value()).isEqualTo(Scenario.AUTHOR);
        Map<?, ?> keys = new JsonMapper().readValue(s.bean(LedgerJson.class).write(result).text(), Map.class);
        assertThat(keys.keySet()).isEqualTo(Set.of("status", "entity", "id", "case_id", "defect", "history_id",
                "author", "login"));
    }

    @Test
    @DisplayName("SVC-CAS-02: one CREATE history row of the case, without before_data")
    void cas02_history(Scenario s) {
        SaveResult d = s.newDefect("d");
        RecordCaseResult result = record(s, Scenario.AUTHOR, observe(d.id(), "seen"));
        List<HistoryEntry> rows = s.history(HistoryEntity.DEFECT_CASE, result.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().op()).isEqualTo(HistoryOp.CREATE);
        assertThat(rows.getFirst().login()).isEqualTo("ledger_author");
        assertThat(rows.getFirst().authorId().value()).isEqualTo(Scenario.AUTHOR);
        assertThat(rows.getFirst().beforeData()).isNull();
    }

    @Test
    @DisplayName("SVC-CAS-04: a session without a grant")
    void cas04_noGrant(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> record(s, Scenario.NOGRANT, observe(d.id(), "seen")), TrustCode.SESSION_NOT_GRANTED);
    }

    @Test
    @DisplayName("SVC-CAS-05: an unknown defect")
    void cas05_unknownDefect(Scenario s) {
        Expect.failure(() -> record(s, Scenario.AUTHOR, observe(s.defectId(9), "seen")), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-CAS-06: the same request twice is one case")
    void cas06_replay(Scenario s) {
        SaveResult d = s.newDefect("d");
        RecordCaseCommand command = new RecordCaseCommand(Scenario.uuid(), d.id(), "seen", Scenario.REASON, null,
                null, null);
        RecordCaseResult first = record(s, Scenario.AUTHOR, command);
        assertThat(record(s, Scenario.AUTHOR, command)).isEqualTo(first);
        assertThat(cases(s, d.id())).hasSize(1);
    }

    @Test
    @DisplayName("SVC-CAS-07: the same request id with another observation")
    void cas07_conflict(Scenario s) {
        SaveResult d = s.newDefect("d");
        String id = Scenario.uuid();
        record(s, Scenario.AUTHOR, new RecordCaseCommand(id, d.id(), "seen", Scenario.REASON, null, null, null));
        Expect.failure(() -> record(s, Scenario.AUTHOR, new RecordCaseCommand(id, d.id(), "seen differently",
                Scenario.REASON, null, null, null)), ConflictCode.REQUEST_CONFLICT);
    }

    @Test
    @DisplayName("SVC-CAS-08: a case on a merged defect names the merge target")
    void cas08_merged(Scenario s) {
        SaveResult source = s.newDefect("source");
        SaveResult target = s.newDefect("target");
        MergeFixture.merge(s, source.id(), 1, target.id());
        LedgerException e = Expect.failure(() -> record(s, Scenario.AUTHOR, observe(source.id(), "seen")),
                TransitionCode.MERGED_DEFECT);
        assertThat(Expect.message(e)).startsWith(source.id() + " was merged into " + target.id());
        assertThat(cases(s, source.id())).isEmpty();
    }

    @Test
    @DisplayName("SVC-CAS-10: two cases in one batch")
    void cas10_batch(Scenario s) {
        SaveResult d = s.newDefect("d");
        var result = s.bean(BatchService.class).batch(Scenario.AUTHOR, null, List.of(
                new BatchOpInput.RecordCase(d.id(), "one", Scenario.REASON, null, null, null, null),
                new BatchOpInput.RecordCase(d.id(), "two", Scenario.REASON, null, null, null, null)));
        assertThat(result.count()).isEqualTo(2);
        assertThat(cases(s, d.id())).extracting(DefectCase::observation).containsExactly("one", "two");
    }

    @Test
    @DisplayName("SVC-CAS-11: a batch with a case on a merged defect persists no case and names the operation")
    void cas11_batchWithMerged(Scenario s) {
        SaveResult live = s.newDefect("live");
        SaveResult source = s.newDefect("source");
        MergeFixture.merge(s, source.id(), 1, live.id());
        LedgerException e = Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, null, List.of(
                new BatchOpInput.RecordCase(live.id(), "one", Scenario.REASON, null, null, null, null),
                new BatchOpInput.RecordCase(source.id(), "two", Scenario.REASON, null, null, null, null))),
                TransitionCode.MERGED_DEFECT);
        assertThat(e.details()).containsEntry("op_index", List.of("2")).containsEntry("op", List.of("record_case"));
        assertThat(cases(s, live.id())).isEmpty();
    }

    @Test
    @DisplayName("SVC-CAS-15: a case history row can be corrected")
    void cas15_correct(Scenario s) {
        SaveResult d = s.newDefect("d");
        RecordCaseResult result = record(s, Scenario.AUTHOR, observe(d.id(), "seen"));
        var corrected = s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(null,
                result.historyId(), Scenario.REASON));
        assertThat(corrected.corrects()).isEqualTo(result.historyId());
        assertThat(s.historyRow(corrected.historyId()).correctsId()).isEqualTo(result.historyId());
        assertThat(s.historyRow(corrected.historyId()).entityId()).isEqualTo(result.id());
    }

    /** A record_case whose field is exactly at a limit (accepted) or one over it (VALIDATION_FAILED). */
    record Limit(String name, String observation, String evidence, String quote, String observedAt,
            ErrorCode expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Limit> limits() {
        ErrorCode refused = ValidationCode.VALIDATION_FAILED;
        return Stream.of(
                new Limit("observation 4000", "o".repeat(4000), null, null, null, null),
                new Limit("observation 4001", "o".repeat(4001), null, null, null, refused),
                new Limit("evidence 2000", "o", "e".repeat(2000), null, null, null),
                new Limit("evidence 2001", "o", "e".repeat(2001), null, null, refused),
                new Limit("user_quote 2000", "o", null, "q".repeat(2000), null, null),
                new Limit("user_quote 2001", "o", null, "q".repeat(2001), null, refused),
                new Limit("observed_at absent", "o", null, null, null, null),
                new Limit("observed_at with a zone", "o", null, null, "2026-11-05T10:00:00Z", null),
                new Limit("observed_at without a zone", "o", null, null, "2026-11-05T10:00:00", refused));
    }

    @ParameterizedTest
    @MethodSource("limits")
    @DisplayName("SVC-CAS-16: field limits of a case")
    void cas16_limits(Limit limit, Scenario s) {
        SaveResult d = s.newDefect("d");
        assertThat(Expect.outcome(() -> record(s, Scenario.AUTHOR, new RecordCaseCommand(null, d.id(),
                limit.observation(), Scenario.REASON, limit.evidence(), limit.quote(), limit.observedAt()))))
                .isEqualTo(Optional.ofNullable(limit.expected()));
    }
}
