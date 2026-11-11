package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.apptest.LedgerTestRunners;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.ProjectId;

/** SVC-TZ-02 .. SVC-TZ-05 (SVC-TZ-01 is the startup guard of the database module; the read side of -03 is in the read test). */
@LedgerServiceTest
class TimeZoneServiceTest {

    private static final Instant NOON = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    @DisplayName("SVC-TZ-02: with the JVM and the database session in Seoul the stored and returned instants are UTC")
    void tz02_otherZones() {
        TimeZone saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        try {
            LedgerTestRunners.inZone("Asia/Seoul", seoul -> {
                Scenario s = new Scenario(seoul);
                SaveResult d = s.newDefect("d");
                s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, new RecordFixCommand(null, d.id(), "repo/e2e",
                        Scenario.commit('a'), Scenario.REASON, null, "ci", "success", "2026-11-05T19:00:00+09:00",
                        null));
                assertThat(s.defect(d.id()).createdAt()).isEqualTo(NOON);
                assertThat(s.bean(DefectFixRepository.class).findByDefectIdAndRepoAndCommitSha(DefectId.of(d.id()),
                        "repo/e2e", CommitSha.of(Scenario.commit('a'))).orElseThrow().ciCompletedAt()).isEqualTo(NOON);
                LocalDateTime wall = seoul.jdbc().sql("SELECT created_at FROM defect WHERE id = ?").param(d.id())
                        .query((rs, n) -> rs.getObject(1, LocalDateTime.class)).single();
                assertThat(wall).isEqualTo(LocalDateTime.of(2026, 11, 5, 10, 0, 0));
            });
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    /** A write and the instant it stamped, at a given reading of the UTC clock. */
    static Stream<String> clockReadings() {
        return Stream.of("2026-11-05T14:59:59Z", "2026-11-05T15:00:00Z", "2026-11-05T23:59:59Z",
                "2026-11-06T00:00:00Z");
    }

    @ParameterizedTest
    @MethodSource("clockReadings")
    @DisplayName("SVC-TZ-03: rows are stamped with the UTC clock, a local midnight changes nothing")
    void tz03_stamping(String reading, Scenario s) {
        Instant now = Instant.parse(reading);
        s.ctx().clock().set(now);
        SaveResult d = s.newDefect("d");
        assertThat(s.defect(d.id()).createdAt()).isEqualTo(now);
        assertThat(s.defect(d.id()).updatedAt()).isEqualTo(now);
        assertThat(s.historyRow(d.historyId()).at()).isEqualTo(now);
    }

    @ParameterizedTest
    @MethodSource("clockReadings")
    @DisplayName("SVC-TZ-03: a grant that expires at a reading of the clock is expired exactly then")
    void tz03_grantExpiry(String reading, Scenario s) {
        String actor = "claude:tz03";
        s.ctx().clock().set(Instant.parse(reading).minusSeconds(1));
        s.bean(GrantService.class).grant(new GrantCommand(actor, s.projectId(), List.of("defect"), reading, null), TestOwners.OWNER);
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(actor, s.create("defect", null,
                s.defectFields("before"))))).isEmpty();
        s.ctx().clock().set(Instant.parse(reading));
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(actor, s.create("defect", null,
                s.defectFields("at"))))).contains(org.pgledger.domain.error.TrustCode.SESSION_NOT_GRANTED);
    }

    /** A write that takes a time, and how to read the stored instant back. */
    record TimedField(String name, BiFunction<Scenario, String, Instant> write) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<TimedField> timedFields() {
        return List.of(
                new TimedField("observed_at", (s, text) -> {
                    SaveResult d = s.newDefect("d");
                    s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(null, d.id(),
                            "seen", Scenario.REASON, null, null, text));
                    return s.bean(DefectCaseRepository.class).findByDefectIdOrderById(DefectId.of(d.id())).getFirst()
                            .observedAt();
                }),
                new TimedField("ci_completed_at", (s, text) -> {
                    SaveResult d = s.newDefect("d");
                    s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, new RecordFixCommand(null, d.id(),
                            "repo/e2e", Scenario.commit('a'), Scenario.REASON, null, "ci", "success", text, null));
                    return s.bean(DefectFixRepository.class).findByDefectIdAndRepoAndCommitSha(DefectId.of(d.id()),
                            "repo/e2e", CommitSha.of(Scenario.commit('a'))).orElseThrow().ciCompletedAt();
                }),
                new TimedField("grant expires_at", (s, text) -> {
                    String actor = s.actorWithoutGrants();
                    s.bean(GrantService.class).grant(new GrantCommand(actor, s.projectId(), List.of("defect"), text,
                            null), TestOwners.OWNER);
                    return s.bean(GrantStateRepository.class).findLive(AuthorId.of(actor), NOON.minusSeconds(60))
                            .getFirst().expiresAt();
                }));
    }

    static Stream<TimedField> everyTimedField() {
        return timedFields().stream();
    }

    @ParameterizedTest
    @MethodSource("everyTimedField")
    @DisplayName("SVC-TZ-04: a time with Z and the same time with an offset are one instant")
    void tz04_sameInstant(TimedField field, Scenario s) {
        s.ctx().clock().set(NOON.minusSeconds(3600));
        Instant utc = field.write().apply(s, "2026-11-05T10:00:00Z");
        Instant offset = field.write().apply(s, "2026-11-05T19:00:00+09:00");
        assertThat(utc).isEqualTo(NOON);
        assertThat(offset).isEqualTo(NOON);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> refusedTimes() {
        return timedFields().stream().flatMap(f -> Stream.of("2026-11-05T10:00:00", "2026-11-05")
                .map(text -> org.junit.jupiter.params.provider.Arguments.of(f, text)));
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("refusedTimes")
    @DisplayName("SVC-TZ-04: a time without a zone, and a date alone, are refused")
    void tz04_refused(TimedField field, String text, Scenario s) {
        s.ctx().clock().set(NOON.minusSeconds(3600));
        Optional<ErrorCode> outcome = Expect.outcome(() -> field.write().apply(s, text));
        assertThat(outcome).contains(ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-TZ-05: instants inside before_data end in Z")
    void tz05_beforeDataInstants(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", d.id(), 1,
                java.util.Map.of("title", "x")));
        String json = s.historyRow(edited.historyId()).beforeData().text();
        assertThat(json).contains("\"created_at\":\"2026-11-05T10:00:00Z\"").contains(
                "\"updated_at\":\"2026-11-05T10:00:00Z\"");
        assertThat(s.before(edited.historyId(), Snapshot.DefectRow.class).createdAt()).isEqualTo(NOON);
    }
}
