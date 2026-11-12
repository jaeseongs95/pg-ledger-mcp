package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.OwnerAuditRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.repository.SessionKeyRepository;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.database.schema.LedgerSchemaGuardConfiguration;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.database.schema.UtcSessionCheck;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.LedgerDatabaseExtension;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.RecordingDataSource.Binding;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectCase;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-029, REPO-030, SVC-TZ-01, SVC-TZ-02, SCH-019: every time value is UTC whatever the zones around it say. */
@ExtendWith(LedgerDatabaseExtension.class)
class TimeZoneTest {

    private static final Instant I = Instant.parse("2026-11-05T10:00:00.123456Z");

    /** The settings of the check: the default schema of the account (no schema name), the way the tests run. */
    private static final org.pgledger.database.schema.SchemaProperties DEFAULT_SCHEMA =
            new org.pgledger.database.schema.SchemaProperties(
                    org.pgledger.database.schema.SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true, null,
                    java.time.Duration.parse(org.pgledger.database.schema.SchemaProperties.DEFAULT_PEER_WAIT));

    private static <T> T inJvmZone(String zone, java.util.function.Supplier<T> work) {
        TimeZone saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try {
            return work.get();
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    private static LocalDateTime wall(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** The stored wall clock of a single value, read raw (no zone conversion); the values are bind parameters. */
    private LocalDateTime rawWall(LedgerTestContext ctx, String sql, Object... values) {
        return JdbcClient.create(ctx.pool()).sql(sql).params(values)
                .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single();
    }

    /** What was read back for one stored instant: the instant the repository gives and the raw stored wall clock. */
    private record Stored(Instant instant, LocalDateTime wall) {
    }

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Seoul", "America/New_York"})
    void repo029_svcTz02_theStoredValueIsUtcAndReadsBackEqualInOtherJvmAndSessionZones(String zone, Databases databases) {
        List<Instant> instants = List.of(I, Instant.parse("2026-03-08T07:30:00Z"),
                Instant.parse("2026-11-01T06:30:00Z"));
        try (LedgerTestContext ctx = LedgerTestContext.create(databases, zone)) {
            List<Stored> stored = inJvmZone(zone, () -> {
                Seed seed = new Seed(ctx);
                Project project = seed.project();
                return IntStream.range(0, instants.size()).mapToObj(i -> {
                    Defect defect = seed.defect(project, i + 1, "zone " + i, instants.get(i));
                    Defect read = ctx.bean(DefectRepository.class).findById(defect.id()).orElseThrow();
                    return new Stored(read.createdAt(), rawWall(ctx, "SELECT created_at FROM defect WHERE id = ?",
                            defect.id().value()));
                }).toList();
            });
            assertThat(stored).isEqualTo(instants.stream().map(i -> new Stored(i, wall(i))).toList());
            assertThat(stored.getFirst().wall().toString()).isEqualTo("2026-11-05T10:00:00.123456");
        }
    }

    @Test
    void svcTz01_aSessionThatIsNotUtcStopsTheApplicationAndUtcStartsIt(Databases databases) {
        try (LedgerTestContext seoul = LedgerTestContext.create(databases, "Asia/Seoul")) {
            UtcSessionCheck check = new UtcSessionCheck(seoul.pool(), Clock.systemUTC(), DEFAULT_SCHEMA);
            // the code is the contract; the failure carries the skew and no connection detail
            assertThatThrownBy(check::verify).isInstanceOfSatisfying(InfrastructureException.class, e -> {
                assertThat(e.code()).isEqualTo(InfrastructureCode.SESSION_ZONE_NOT_UTC);
                assertThat(e.args()).hasSize(1);
                assertThat(e.getMessage()).doesNotContain("jdbc:");
            });
            assertThatThrownBy(() -> startWithGuard(seoul)).isInstanceOf(BeanCreationException.class)
                    .hasStackTraceContaining("SESSION_ZONE_NOT_UTC");
        }
        try (LedgerTestContext utc = LedgerTestContext.create(databases, "UTC")) {
            new UtcSessionCheck(utc.pool(), Clock.systemUTC(), DEFAULT_SCHEMA).verify();
            startWithGuard(utc).close();
        }
        // the JVM zone is checked as well
        UtcSessionCheck.verifyJvmZone();
        inJvmZone("Asia/Seoul", () -> assertThatThrownBy(UtcSessionCheck::verifyJvmZone)
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.JVM_ZONE_NOT_UTC)));
    }

    private AnnotationConfigApplicationContext startWithGuard(LedgerTestContext ctx) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", ctx.pool());
        context.getBeanFactory().registerSingleton("clock", Clock.systemUTC());
        context.register(LedgerSchemaGuardConfiguration.class);
        context.refresh();
        return context;
    }

    @Test
    void sch019_everyTimestampColumnIsWrittenThroughTheSingleUtcConversion(Databases databases) {
        String zone = "Asia/Seoul";
        try (LedgerTestContext ctx = LedgerTestContext.create(databases, zone)) {
            inJvmZone(zone, () -> {
                writeAllTimestampColumns(ctx);
                return null;
            });
            List<Binding> bound = ctx.spy().bindings();
            Class<?>[] forbidden = {java.sql.Timestamp.class, java.util.Date.class, Instant.class,
                    java.time.OffsetDateTime.class, java.time.ZonedDateTime.class};
            assertThat(bound).as("no timestamp is bound in any other way").noneMatch(
                    b -> b.type() != null && List.of(forbidden).contains(b.type()));
            assertThat(bound).noneMatch(b -> "setTimestamp".equals(b.setter()));
            assertThat(bound.stream().filter(b -> b.type() == LocalDateTime.class).count())
                    .isGreaterThanOrEqualTo(15);
            // the stored wall clock of every column is the UTC time that was written
            Map<String, Instant> expected = new LinkedHashMap<>();
            expected.put("defect.created_at", I);
            expected.put("defect.updated_at", I);
            expected.put("task.created_at", I);
            expected.put("task.updated_at", I);
            expected.put("defect_fix.ci_completed_at", I.plusSeconds(1));
            expected.put("defect_fix.recorded_at", I);
            expected.put("defect_case.observed_at", I.plusSeconds(2));
            expected.put("defect_case.recorded_at", I);
            expected.put("session_grant.granted_at", I);
            expected.put("session_grant.expires_at", I.plusSeconds(3600));
            expected.put("session_grant.revoked_at", I.plusSeconds(5));
            expected.put("session_grant.last_used_at", I.plusSeconds(4));
            expected.put("session_key.registered_at", I);
            expected.put("session_key.revoked_at", I.plusSeconds(6));
            expected.put("owner_audit.occurred_at", I);
            expected.put("history.occurred_at", I);
            expected.put("request_receipt.created_at", I);
            expected.put("schema_history.applied_at", LedgerTestContext.START);
            assertThat(expected.keySet()).containsExactlyInAnyOrderElementsOf(SchemaCatalogTest.TIMESTAMP_COLUMNS);
            Map<String, LocalDateTime> actual = expected.keySet().stream().collect(Collectors.toMap(
                    column -> column, column -> rawWall(ctx, "SELECT MAX(" + column.substring(column.indexOf('.') + 1)
                            + ") FROM " + column.substring(0, column.indexOf('.')))));
            assertThat(actual).isEqualTo(expected.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                    e -> wall(e.getValue()))));
        }
    }

    private void writeAllTimestampColumns(LedgerTestContext ctx) {
        Seed seed = new Seed(ctx);
        IdAllocator ids = ctx.bean(IdAllocator.class);
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "all columns", I);
        LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(TaskRepository.class).save(
                org.pgledger.domain.model.Task.plan(org.pgledger.domain.value.TaskId.issue(project.code(), 1),
                        org.pgledger.domain.model.TaskFields.of(
                                org.pgledger.domain.value.Patch.set(project.id().value()),
                                org.pgledger.domain.value.Patch.set("t"), org.pgledger.domain.value.Patch.set("p"),
                                org.pgledger.domain.value.Patch.set("s"), org.pgledger.domain.value.Patch.absent(),
                                org.pgledger.domain.value.Patch.absent(), org.pgledger.domain.value.Patch.absent()),
                        I))));
        ctx.tx(() -> ctx.bean(DefectFixRepository.class).insert(DefectFix.record(ids.next(Counter.DEFECT_FIX),
                defect.id(), "repo", CommitSha.of("f".repeat(40)), null, "ref", CiConclusion.SUCCESS,
                I.plusSeconds(1), Seed.ALICE, I)));
        ctx.tx(() -> ctx.bean(DefectCaseRepository.class).insert(DefectCase.observe(ids.next(Counter.DEFECT_CASE),
                defect.id(), "seen", null, null, I.plusSeconds(2), Seed.ALICE, I)));
        SessionGrant grant = ctx.tx(() -> ctx.bean(SessionGrantRepository.class).insert(SessionGrant.issue(
                ids.next(Counter.SESSION_GRANT), Seed.ALICE, project.id(), Scope.DEFECT, AuthorId.OWNER, I,
                I.plusSeconds(3600), null)));
        GrantStateRepository grants = ctx.bean(GrantStateRepository.class);
        ctx.tx(() -> grants.markUsed(Seed.ALICE, project.id(), Scope.DEFECT, I.plusSeconds(4)));
        ctx.tx(() -> grants.revoke(grant.id(), I.plusSeconds(5)));
        SessionPublicKey key = ctx.tx(() -> ctx.bean(SessionKeyRepository.class).insert(SessionPublicKey.register(
                ids.next(Counter.SESSION_KEY), Seed.ALICE, "MCowBQYDK2VwAyEA" + "A".repeat(43), "c".repeat(64),
                AuthorId.OWNER, I, null)));
        ctx.tx(() -> ctx.bean(SessionKeyStateRepository.class).close(key.id(), I.plusSeconds(6)));
        ctx.tx(() -> ctx.bean(OwnerAuditRepository.class).insert(new org.pgledger.domain.model.OwnerAuditEntry(
                ids.next(Counter.OWNER_AUDIT), I, "owner", org.pgledger.domain.rule.OwnerAction.GRANT_ISSUED,
                "claude:a|p", null)));
        ctx.tx(() -> ctx.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY), I,
                Seed.ALICE, Channel.AUTHOR.login(), ActorAssurance.DECLARED, HistoryEntity.DEFECT,
                defect.id().value(), HistoryOp.CREATE, null, null, JsonDocument.EMPTY_OBJECT, null,
                "a reason of enough length", null, null, null, new RequestId(java.util.UUID.randomUUID()), null)));
        ctx.tx(() -> ctx.bean(ReceiptRepository.class).insert(Receipt.reserve(new RequestId(java.util.UUID
                .randomUUID()), ReceiptOperation.SAVE, Sha256.of("a".repeat(64)), Seed.ALICE, Channel.AUTHOR, I)));
        assertThat(JdbcClient.create(ctx.pool()).sql("SELECT COUNT(*) FROM defect").query(Integer.class).single())
                .isEqualTo(1);
    }
}
