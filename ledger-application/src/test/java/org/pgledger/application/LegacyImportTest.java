package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.legacy.ImportReport;
import org.pgledger.application.legacy.LegacyImportService;
import org.pgledger.application.legacy.TableCheck;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.HistoryService.HistoryQuery;
import org.pgledger.application.support.ConnectionLog;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WhoamiService;
import org.pgledger.application.write.WhoamiService.GrantView;
import org.pgledger.application.write.WriteResult.RecordCaseResult;
import org.pgledger.application.write.WriteResult.RecordFixResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.apptest.LedgerTestRunners;
import org.pgledger.apptest.MisbehavingTarget;
import org.pgledger.apptest.TestKeyRing;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.LegacyRow;
import org.pgledger.database.repository.LegacySourceRepository;
import org.pgledger.database.repository.LegacyTable;
import org.pgledger.database.repository.LegacyTargetRepository;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.context.ToolScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.testing.TestOwners;
import org.pgledger.testing.legacy.LegacyFixtures;
import org.pgledger.testing.legacy.LegacyFixtures.Category;
import org.pgledger.testing.legacy.LegacyLedger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * MIG-001 .. MIG-024 and MIG-027 .. MIG-033 on the PostgreSQL of the test PC: the legacy import service in the real
 * application context (a freshly migrated schema, the real repositories and services, the real migrator) with the legacy
 * ledger of {@link LegacyFixtures} (a schema of its own, {@code timestamptz}, {@code uuid} and {@code jsonb} columns as in the
 * real 1.x) as its source, read by the read-only account. Every test reads the tables of both sides with its own queries.
 * MIG-025 is test server stage work and is not here.
 */
class LegacyImportTest {

    private static final String SEED = LegacyFixtures.SEED;
    private static final String RELINK = LegacyFixtures.RELINK;
    /** Reads JSON text: the legacy columns are jsonb, which PostgreSQL prints in its own normalised form. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The legacy ledger and the application context that imports it. */
    private record Rig(LegacyLedger legacy, LedgerAppContext ctx) {

        LegacyImportService service() {
            return ctx.bean(LegacyImportService.class);
        }

        ImportReport importAll() {
            return service().importLegacy(TestOwners.OWNER);
        }

        long count(String sql, Object... arguments) {
            return ctx.jdbc().sql(sql).params(Arrays.asList(arguments)).query(Long.class).single();
        }

        List<Map<String, Object>> rows(String sql) {
            return ctx.jdbc().sql(sql).query().listOfRows();
        }

        Instant instant(String sql, Object... arguments) {
            return ctx.jdbc().sql(sql).params(Arrays.asList(arguments)).query((rs, n) -> UtcTime.read(rs, "t")).list().getFirst();
        }

        /** The number of rows of every table of the new schema that is copied, plus the tables only it has. */
        long ledgerRows() {
            long rows = 0;
            for (LegacyTable table : LegacyTable.values()) {
                rows += table.targetMayHoldOtherRows() ? 0 : count("SELECT COUNT(*) FROM " + table.targetName());
            }
            return rows + count("SELECT COUNT(*) FROM session_key") + count("SELECT COUNT(*) FROM owner_audit");
        }
    }

    private static void withRig(LegacyLedger legacy, DataSource source, Consumer<Rig> body, Class<?>... configurations) {
        try (legacy) {
            LedgerTestRunners.importing(legacy, source, ctx -> body.accept(new Rig(legacy, ctx)), configurations);
        }
    }

    private static void withRig(LegacyLedger legacy, Consumer<Rig> body, Class<?>... configurations) {
        withRig(legacy, legacy.reader(), body, configurations);
    }

    private static void withRich(Consumer<Rig> body, Class<?>... configurations) {
        withRig(LegacyFixtures.rich(), body, configurations);
    }

    private static String text(Map<String, Object> row, String column) {
        return String.valueOf(row.get(column));
    }

    /** The two texts are the same JSON value (whitespace and the order of the keys do not matter). */
    private static void assertSameJson(String actual, String expected) {
        assertThat((Object) JSON.readTree(actual)).isEqualTo(JSON.readTree(expected));
    }

    private static <T> T asTool(String tool, Supplier<T> call) {
        return ScopedValue.where(ToolScope.CURRENT, tool).where(ToolScope.ARGUMENTS_DIGEST, TestKeyRing.DIRECT_DIGEST)
                .call(call::get);
    }

    private static Map<String, String> defectFields(String project, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", project);
        fields.put("title", title);
        fields.put("category", "OLDBUG");
        fields.put("severity", "P2");
        fields.put("behavior", "behavior of " + title);
        fields.put("root_cause", "cause of " + title);
        return fields;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The self-delimiting text of text values that the digest of the import documents: {@code s<length>:<text>;}. */
    private static String serialised(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            out.append('s').append(value.length()).append(':').append(value).append(';');
        }
        return out.toString();
    }

    /** The digest of a table of text columns, written from the documentation alone: the key is the first two columns. */
    private static String documentedDigest(List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        for (List<String> row : rows) {
            lines.add(serialised(row.subList(0, 2)) + "\t" + sha256(serialised(row)) + "\n");
        }
        Collections.sort(lines);
        return sha256(String.join("", lines));
    }

    private static BigDecimal epoch(Instant instant) {
        return new BigDecimal(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano(), 9));
    }

    /** Everything a legacy table holds, as text, for the check that the import changed nothing. */
    private static String contents(LegacyLedger legacy) {
        StringBuilder all = new StringBuilder();
        for (String table : LegacyLedger.TABLES) {
            all.append(table).append(legacy.rows("SELECT * FROM " + legacy.schema() + "." + table + " ORDER BY 1, 2"));
        }
        return sha256(all.toString());
    }

    // ---------------------------------------------------------------------------------------------- MIG-001 .. 004

    @Test
    @DisplayName("MIG-001 every table of the legacy ledger arrives with its row count, no extra rows; the report has one line per table")
    void mig001_everyRowIsCopied() {
        withRich(rig -> {
            ImportReport report = rig.importAll();

            long total = 0;
            for (LegacyTable table : LegacyTable.values()) {
                long legacyRows = rig.legacy().count(table.sourceName());
                assertThat(rig.count("SELECT COUNT(*) FROM " + table.targetName())).as(table.name()).isEqualTo(legacyRows);
                total += legacyRows;
            }
            assertThat(report.tables()).hasSize(LegacyTable.values().length).allMatch(TableCheck::matches);
            assertThat(report.rows()).isEqualTo(total);
            assertThat(report.schema()).isEqualTo(rig.legacy().schema());
        });
    }

    @Test
    @DisplayName("MIG-002 the digest of every table is equal on both sides, follows the documented format (checked for defect_task by an independent computation), and the ids of history, fix, case and grant are preserved")
    void mig002_digestsAndIds() {
        withRich(rig -> {
            ImportReport report = rig.importAll();

            for (TableCheck check : report.tables()) {
                assertThat(check.sourceDigest()).as(check.table().name()).isEqualTo(check.targetDigest());
            }
            List<List<String>> legacyRows = rig.legacy().rows("SELECT defect_id, task_id, created_by FROM "
                    + rig.legacy().schema() + ".defect_task")
                    .stream().map(row -> List.of(text(row, "defect_id"), text(row, "task_id"), text(row, "created_by"))).toList();
            TableCheck defectTask = report.tables().stream().filter(check -> check.table() == LegacyTable.DEFECT_TASK)
                    .findFirst().orElseThrow();
            assertThat(defectTask.sourceDigest().hex()).isEqualTo(documentedDigest(legacyRows));
            assertThat(defectTask.targetDigest().hex()).isEqualTo(documentedDigest(legacyRows));
            for (String table : List.of("history", "defect_fix", "defect_case", "session_grant")) {
                Set<Long> legacyIds = new HashSet<>(rig.legacy().rows("SELECT id FROM " + rig.legacy().schema() + "." + table)
                        .stream().map(row -> ((Number) row.get("id")).longValue()).toList());
                Set<Long> ids = new HashSet<>(rig.ctx().jdbc().sql("SELECT id FROM " + table).query(Long.class).list());
                assertThat(ids).as(table).isEqualTo(legacyIds);
            }
        });
    }

    @Test
    @DisplayName("MIG-003 every field of every history row is equal (the JSON text of changes, legacy_ref, the legacy operations and assurances, login, request id, corrects_id), the entity is the upper-case name, and before_data is NULL on every row")
    void mig003_historyFields() {
        withRich(rig -> {
            rig.importAll();

            List<Map<String, Object>> legacyRows = rig.legacy().rows("SELECT id, author_id, login, actor_assurance, entity, "
                    + "entity_id, op, from_state, to_state, changes, reason, evidence_uri, evidence_sha256, corrects_id, "
                    + "request_id, legacy_ref FROM " + rig.legacy().schema() + ".history ORDER BY id");
            List<Map<String, Object>> rows = rig.rows("SELECT id, author_id, login, actor_assurance, entity, entity_id, op, "
                    + "from_state, to_state, changes, before_data, reason, evidence_uri, evidence_sha256, corrects_id, "
                    + "request_id, legacy_ref FROM history ORDER BY id");

            assertThat(rows).hasSameSizeAs(legacyRows).hasSize(10);
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> legacy = legacyRows.get(i);
                Map<String, Object> row = rows.get(i);
                for (String column : List.of("id", "author_id", "login", "actor_assurance", "entity_id", "op", "from_state",
                        "to_state", "changes", "reason", "evidence_uri", "evidence_sha256", "corrects_id", "request_id",
                        "legacy_ref")) {
                    assertThat(text(row, column)).as("history %s of row %d", column, i).isEqualTo(text(legacy, column));
                }
                assertThat(text(row, "entity")).isEqualTo(text(legacy, "entity").toUpperCase(Locale.ROOT));
                assertThat(row.get("before_data")).as("before_data of row %d", i).isNull();
            }
            assertThat(rows.stream().map(row -> text(row, "op")).toList()).contains("IMPORT", "LEGACY_NOTE", "LEGACY_CHANGE",
                    "CORRECT");
            assertThat(rows.stream().map(row -> text(row, "actor_assurance")).distinct().toList()).contains("LEGACY_LOGIN",
                    "LEGACY_UNKNOWN", "LEGACY_SHARED");
        });
    }

    @Test
    @DisplayName("MIG-004 every receipt arrives with its stored response and payload digest, flagged LEGACY; a replay with an imported request id is REQUEST_ID_LEGACY and neither returns the stored response nor writes")
    void mig004_receipts() {
        withRich(rig -> {
            rig.importAll();

            List<Map<String, Object>> receipts = rig.rows("SELECT request_id, operation, payload_sha256, author_id, login, "
                    + "response, origin FROM request_receipt ORDER BY request_id");
            assertThat(receipts).hasSize(3).allSatisfy(receipt -> assertThat(receipt.get("origin")).isEqualTo("LEGACY"));
            assertThat(receipts.stream().map(receipt -> text(receipt, "operation")).toList())
                    .containsExactlyInAnyOrder("SAVE", "TRANSITION", "RECORD_FIX");
            String imported = UUID.nameUUIDFromBytes("request-1".getBytes(StandardCharsets.UTF_8)).toString();
            // the legacy column is jsonb: the text the import reads is PostgreSQL's normalised form of the JSON, and it is
            // copied as it is
            String stored = rig.ctx().jdbc().sql("SELECT response FROM request_receipt WHERE request_id = ?").param(imported)
                    .query(String.class).single();
            assertThat(stored).isEqualTo(rig.legacy().rows("SELECT response FROM " + rig.legacy().schema()
                    + ".request_receipt WHERE request_id = ?", UUID.fromString(imported)).getFirst().get("response").toString());
            assertSameJson(stored, "{\"id\":\"OLD-D-0001\",\"version\":1}");
            assertThat(rig.ctx().jdbc().sql("SELECT payload_sha256 FROM request_receipt WHERE request_id = ?").param(imported)
                    .query(String.class).single()).isEqualTo("c".repeat(64));

            long defects = rig.count("SELECT COUNT(*) FROM defect");
            long history = rig.count("SELECT COUNT(*) FROM history");
            for (String requestId : List.of(imported, UUID.nameUUIDFromBytes("request-pending".getBytes(StandardCharsets.UTF_8))
                    .toString())) {
                LedgerException refused = Expect.failure(() -> rig.ctx().bean(SaveService.class).save(SEED,
                        new SaveCommand(requestId, "defect", null, 0L, Fields.of(defectFields("old", "replay")), Scenario.REASON)),
                        ConflictCode.REQUEST_ID_LEGACY);
                assertThat(refused.getMessage()).doesNotContain("OLD-D-0001");
            }
            assertThat(rig.count("SELECT COUNT(*) FROM defect")).isEqualTo(defects);
            assertThat(rig.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
            assertThat(rig.ctx().jdbc().sql("SELECT response FROM request_receipt WHERE request_id = ?").param(imported)
                    .query(String.class).single()).isEqualTo(stored);
        });
    }

    // ---------------------------------------------------------------------------------------------- MIG-005 .. 007

    @Test
    @DisplayName("MIG-005 (changed by import: no session key is imported) live grants arrive with the active_key of the new schema (author|project|scope), the revoked one with revoked:<id>, the expired one is still live-keyed but not live; counts equal, last_used_at and session_key empty; whoami shows only the live grants once the key is registered")
    void mig005_grants() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM session_grant")).isEqualTo(rig.legacy().count("session_grant")).isEqualTo(5);
            assertThat(rig.ctx().jdbc().sql("SELECT active_key FROM session_grant ORDER BY id").query(String.class).list())
                    .containsExactly("claude:e2e-seed|old|defect", "claude:e2e-seed|old|task", "revoked:4",
                            "gemini:expired|old|verify", "claude:e2e-seed|e2e|defect");
            assertThat(rig.ctx().jdbc().sql("SELECT grant_scope FROM session_grant ORDER BY id").query(String.class).list())
                    .containsExactly("DEFECT", "TASK", "FIX", "VERIFY", "DEFECT");
            assertThat(rig.count("SELECT COUNT(*) FROM session_grant WHERE last_used_at IS NOT NULL")).isZero();
            assertThat(rig.count("SELECT COUNT(*) FROM session_grant WHERE granted_by <> 'user:owner'")).isZero();
            assertThat(rig.count("SELECT COUNT(*) FROM session_key")).isZero();

            rig.ctx().enroll(SEED);
            WhoamiService whoami = rig.ctx().bean(WhoamiService.class);
            assertThat(whoami.whoami(SEED, Channel.AUTHOR).grants()).extracting(GrantView::project, GrantView::scope)
                    .containsExactlyInAnyOrder(tuple("old", "defect"), tuple("old", "task"), tuple("e2e", "defect"));
            rig.ctx().enroll(RELINK);
            assertThat(whoami.whoami(RELINK, Channel.AUTHOR).grants()).isEmpty();
            rig.ctx().enroll("gemini:expired");
            assertThat(whoami.whoami("gemini:expired", Channel.AUTHOR).grants()).isEmpty();
        });
    }

    @Test
    @DisplayName("MIG-030 after the import a granted author that has no registered key is refused with ATTESTATION_SIGNATURE_INVALID (nothing written); after its key is registered (what key-register does) the same grant writes")
    void mig030_grantsNeedTheKey() {
        withRich(rig -> {
            rig.importAll();
            SaveService save = rig.ctx().bean(SaveService.class);
            String requestId = UUID.randomUUID().toString();
            SaveCommand command = new SaveCommand(requestId, "defect", null, 0L, Fields.of(defectFields("old", "after the import")),
                    Scenario.REASON);
            String token = TestKeyRing.token(TestKeyRing.newPair(), SEED, "ledger_save", requestId,
                    rig.ctx().clock().instant());

            LedgerException refused = Expect.failure(() -> asTool("ledger_save", () -> save.save(token, command)),
                    TrustCode.ATTESTATION_SIGNATURE_INVALID);

            assertThat(refused.args()).isEmpty();
            assertThat(rig.count("SELECT COUNT(*) FROM defect WHERE title = ?", "after the import")).isZero();
            assertThat(rig.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", requestId)).isZero();

            rig.ctx().enroll(SEED);
            SaveResult saved = save.save(SEED, new SaveCommand(null, "defect", null, 0L, Fields.of(defectFields("old", "after the key")),
                    Scenario.REASON));
            assertThat(saved.author()).isEqualTo(SEED);
            assertThat(saved.id()).isEqualTo("OLD-D-0004");
        });
    }

    @Test
    @DisplayName("MIG-006 created_by is copied as it is in every table that has it (creators of defects, tasks, conditions and links, user:owner, unknown:legacy, the creator of a re-linked row)")
    void mig006_createdBy() {
        withRich(rig -> {
            rig.importAll();

            for (LegacyTable table : List.of(LegacyTable.PROJECT, LegacyTable.COMMON_CODE, LegacyTable.TASK,
                    LegacyTable.DEFECT, LegacyTable.DEFECT_TASK, LegacyTable.DEFECT_RELATION, LegacyTable.RELEASE_CONDITION,
                    LegacyTable.CONDITION_LINK)) {
                List<String> legacy = rig.legacy().rows("SELECT created_by FROM " + rig.legacy().schema() + "."
                        + table.sourceName()).stream().map(row -> text(row, "created_by")).toList();
                List<String> imported = rig.ctx().jdbc().sql("SELECT created_by FROM " + table.targetName())
                        .query(String.class).list();
                assertThat(imported).as(table.name()).containsExactlyInAnyOrderElementsOf(legacy);
            }
            assertThat(rig.count("SELECT COUNT(*) FROM release_condition WHERE id = 'OLD-C-3' AND created_by = 'unknown:legacy'"))
                    .isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM condition_link WHERE condition_id = 'OLD-C-2' AND "
                    + "created_by = 'codex:e2e-relink'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("MIG-007 the counters continue after the highest legacy numbers: project defect counter = highest <CODE>-D-NNNN, task counter = highest <CODE>-T-NNNN (0 when none), history, fix, case and grant counters = highest id; the next numbers issued collide with nothing")
    void mig007_counters() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT next_defect_no FROM project WHERE id = 'old'")).isEqualTo(3);
            assertThat(rig.count("SELECT next_task_no FROM project WHERE id = 'old'")).isZero();
            assertThat(rig.count("SELECT next_defect_no FROM project WHERE id = 'e2e'")).isEqualTo(100);
            assertThat(rig.count("SELECT next_task_no FROM project WHERE id = 'e2e'")).isEqualTo(3);
            for (Map.Entry<String, Long> counter : Map.of("HISTORY", 57L, "DEFECT_FIX", 9L, "DEFECT_CASE", 5L,
                    "SESSION_GRANT", 8L, "SESSION_KEY", 0L, "OWNER_AUDIT", 1L).entrySet()) {
                assertThat(rig.count("SELECT issued_no FROM id_counter WHERE name = ?", counter.getKey()))
                        .as(counter.getKey()).isEqualTo(counter.getValue());
            }
            IdAllocator ids = rig.ctx().bean(IdAllocator.class);
            assertThat(rig.ctx().tx(() -> ids.nextDefectNo(ProjectCode.of("OLD")))).isEqualTo(4);
            assertThat(rig.ctx().tx(() -> ids.nextDefectNo(ProjectCode.of("E2E")))).isEqualTo(101);
            assertThat(rig.ctx().tx(() -> ids.nextTaskNo(ProjectCode.of("E2E")))).isEqualTo(4);
            assertThat(rig.ctx().tx(() -> ids.nextTaskNo(ProjectCode.of("OLD")))).isEqualTo(1);
            assertThat(rig.ctx().tx(() -> ids.next(Counter.HISTORY))).isEqualTo(58);
            assertThat(rig.ctx().tx(() -> ids.next(Counter.DEFECT_FIX))).isEqualTo(10);
        });
    }

    // ---------------------------------------------------------------------------------------------- MIG-008 .. 010

    @Test
    @DisplayName("MIG-008 the legacy ledger is untouched: the content digest of every table, the object list and the _migration rows are equal before and after; the import reads in a read-only transaction of isolation REPEATABLE READ; a write through the read-only account fails")
    void mig008_sourceUntouched() {
        LegacyLedger legacy = LegacyFixtures.rich();
        ConnectionLog log = new ConnectionLog(legacy.reader());
        String before = contents(legacy);
        List<String> objectsBefore = legacy.objects();
        withRig(legacy, log, rig -> {
            rig.importAll();
            rig.service().verify();
            assertThat(contents(legacy)).isEqualTo(before);
            assertThat(legacy.objects()).isEqualTo(objectsBefore);
            assertThat(legacy.rows("SELECT step FROM " + legacy.schema() + "._migration ORDER BY step").stream()
                    .map(row -> text(row, "step")).toList()).containsExactlyInAnyOrderElementsOf(LegacyLedger.ALL_STEPS);
            assertThat(log.calls()).contains("setReadOnly[true]", "setTransactionIsolation[4]", "commit[]");
            assertThat(log.calls().indexOf("setReadOnly[true]")).isLessThan(log.calls().indexOf("setTransactionIsolation[4]"));
            // the read-only account has SELECT only: PostgreSQL refuses the write with SQLState 42501 (insufficient privilege)
            assertThatThrownBy(() -> JdbcClient.create(legacy.reader())
                    .sql("UPDATE " + legacy.schema() + ".project SET title = 'x'").update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().isInstanceOfSatisfying(SQLException.class,
                            cause -> assertThat(cause.getSQLState()).isEqualTo("42501"));
            assertThat(contents(legacy)).isEqualTo(before);
        });
    }

    @Test
    @DisplayName("MIG-009 a second run is refused with DUPLICATE_KEY naming the tables that hold data, writes nothing and leaves no duplicates; a new schema that already holds a project of its own is refused as well")
    void mig009_refusesANonEmptyTarget() {
        withRich(rig -> {
            ImportReport first = rig.importAll();
            long rows = rig.ledgerRows();

            LedgerException refused = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(refused.args().getFirst()).contains("project").contains("history");
            assertThat(rig.ledgerRows()).isEqualTo(rows);
            assertThat(first.tables()).allMatch(TableCheck::matches);
        });
        withRich(rig -> {
            rig.ctx().bean(ProjectService.class).add(new ProjectCommand("own", "OWN", "A project of its own"), TestOwners.OWNER);

            Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(rig.count("SELECT COUNT(*) FROM project")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM history")).isZero();
        });
    }

    private static final String REVISION = "SELECT revision FROM code_revision WHERE id = 1";

    @Test
    @DisplayName("REH2-MIG-02 a new schema that holds a code set through the code service (a CODE_SET row in owner_audit, what migrate and setup make) is accepted: the comparison of every table is equal, and the code revision is one higher than after the code was set")
    void reh2Mig02_acceptsWhatSetupMade() {
        withRich(rig -> {
            rig.ctx().bean(CommonCodeService.class).set(new CodeCommand("DEFECT_CATEGORY", "MINE", "내 코드", "My code", null, null, 3,
                    "ACTIVE"), TestOwners.OWNER);
            assertThat(rig.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'CODE_SET'")).isEqualTo(1);
            long revision = rig.count(REVISION);

            ImportReport report = rig.importAll();

            assertThat(report.tables()).allMatch(TableCheck::matches);
            assertThat(rig.count(REVISION)).isEqualTo(revision + 1);
            assertThat(rig.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'IMPORT'")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM history")).isEqualTo(rig.legacy().count("history"));
        });
    }

    @Test
    @DisplayName("REH2-MIG-03 a code of the new schema that the legacy ledger also has is overwritten with the legacy values (the row of the same key), a code only the new schema has stays as it is, the comparison of common_code is equal and verify passes")
    void reh2Mig03_aSeedCodeOfTheLegacyLedger() {
        withRich(rig -> {
            CommonCodeService codes = rig.ctx().bean(CommonCodeService.class);
            codes.set(new CodeCommand("DEFECT_CATEGORY", "CON", "내 이름", "My name", "내 설명", "My description", 7, "INACTIVE"),
                    TestOwners.OWNER);
            codes.set(new CodeCommand("DEFECT_CATEGORY", "MINE", "내 코드", "My code", null, null, 3, "ACTIVE"), TestOwners.OWNER);

            ImportReport report = rig.importAll();

            Category con = LegacyFixtures.categories().stream().filter(category -> "CON".equals(category.code())).findFirst()
                    .orElseThrow();
            Map<String, Object> row = rig.rows("SELECT name_ko, name_en, description_ko, sort_order, state FROM common_code "
                    + "WHERE code_group = 'DEFECT_CATEGORY' AND code = 'CON'").getFirst();
            assertThat(text(row, "name_ko")).isEqualTo(con.name());
            assertThat(text(row, "name_en")).isEqualTo("Concurrency and atomicity");
            assertThat(text(row, "description_ko")).isEqualTo(con.definition());
            assertThat(text(row, "sort_order")).isEqualTo("0");
            assertThat(text(row, "state")).isEqualTo("ACTIVE");
            Map<String, Object> mine = rig.rows("SELECT name_ko, sort_order FROM common_code WHERE code = 'MINE'").getFirst();
            assertThat(text(mine, "name_ko")).isEqualTo("내 코드");
            assertThat(text(mine, "sort_order")).isEqualTo("3");
            assertThat(report.tables()).filteredOn(check -> check.table() == LegacyTable.COMMON_CODE).singleElement()
                    .matches(TableCheck::matches);
            assertThat(rig.service().verify().tables()).allMatch(TableCheck::matches);
        });
    }

    @Test
    @DisplayName("REH2-MIG-04 a project of its own (PROJECT_ADDED in the audit trail) and a grant on it (GRANT_ISSUED) refuse the import with DUPLICATE_KEY naming the tables that hold the rows (project; project, session_key and session_grant) and never owner_audit; nothing is written")
    void reh2Mig04_ledgerDataRefuses() {
        withRich(rig -> {
            rig.ctx().bean(ProjectService.class).add(new ProjectCommand("own", "OWN", "A project of its own"), TestOwners.OWNER);
            long rows = rig.ledgerRows();

            LedgerException project = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(project.args().getFirst()).contains("project").doesNotContain("session_grant").doesNotContain("owner_audit");
            assertThat(rig.ledgerRows()).isEqualTo(rows);

            // a grant needs the registered key of its author (what key-register does)
            rig.ctx().enroll(SEED);
            rig.ctx().bean(GrantService.class).grant(new GrantCommand(SEED, "own", List.of("fix"), null, null), TestOwners.OWNER);
            long withGrant = rig.ledgerRows();

            LedgerException grant = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(grant.args().getFirst()).contains("project").contains("session_key").contains("session_grant")
                    .doesNotContain("owner_audit");
            assertThat(rig.ledgerRows()).isEqualTo(withGrant);
        });
    }

    @Test
    @DisplayName("REH2-MIG-05 a row of session_key refuses the import (naming session_key), and so does an owner_audit row of the operation IMPORT alone, an earlier import of an empty legacy ledger (naming owner_audit); nothing is written")
    void reh2Mig05_aSessionKeyAndAnEarlierImportRefuse() {
        withRich(rig -> {
            rig.ctx().jdbc().sql("INSERT INTO session_key (id, author_id, public_key, fingerprint, registered_by, registered_at, "
                    + "active_key, fingerprint_key) VALUES (1, 'claude:x', 'AAAA', 'ffff', 'cli:alice', "
                    + "TIMESTAMP '2026-11-01 00:00:00', 'claude:x', 'ffff')").update();
            long rows = rig.ledgerRows();

            LedgerException key = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(key.args().getFirst()).isEqualTo("ledger data in session_key");
            assertThat(rig.ledgerRows()).isEqualTo(rows);
        });
        withRich(rig -> {
            rig.ctx().jdbc().sql("INSERT INTO owner_audit (id, occurred_at, actor, operation, subject) VALUES (1, "
                    + "TIMESTAMP '2026-11-01 00:00:00', 'cli:alice', 'IMPORT', 'legacy schema ledger')").update();
            long rows = rig.ledgerRows();

            LedgerException earlier = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(earlier.args().getFirst()).isEqualTo("ledger data in owner_audit");
            assertThat(rig.ledgerRows()).isEqualTo(rows);
            assertThat(rig.count("SELECT COUNT(*) FROM project")).isZero();
        });
    }

    @Test
    @DisplayName("MIG-010 a failure after table k leaves no imported row (one transaction): not a row in any table, the counters, the code revision and the audit trail as before; a rerun succeeds")
    void mig010_oneTransaction() {
        withRich(rig -> {
            MisbehavingTarget target = rig.ctx().bean(MisbehavingTarget.class);
            target.failAt(LegacyTable.DEFECT_RELATION);

            assertThatThrownBy(rig::importAll).isInstanceOf(IllegalStateException.class).hasMessageContaining("DEFECT_RELATION");

            assertThat(target.inserted()).as("rows were written before the failure").isPositive();
            assertThat(rig.ledgerRows()).isZero();
            assertThat(rig.count("SELECT COUNT(*) FROM common_code")).isEqualTo(10);
            assertThat(rig.count("SELECT SUM(issued_no) FROM id_counter")).isZero();
            assertThat(rig.count("SELECT revision FROM code_revision")).isZero();

            target.failAt(null);
            ImportReport report = rig.importAll();

            assertThat(report.tables()).allMatch(TableCheck::matches);
            assertThat(rig.count("SELECT COUNT(*) FROM history")).isEqualTo(10);
        }, MisbehavingTarget.Replacement.class);
    }

    // ---------------------------------------------------------------------------------------------- MIG-011 .. 017

    @Test
    @DisplayName("MIG-011 every common code is kept with state, sort order, Korean and English text; CON and EXC have their English names; the category only the old installation has falls back to the Korean text; the 14 known categories have English text that differs from the Korean; severities and relation kinds as seeded")
    void mig011_commonCodes() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM common_code")).isEqualTo(25);
            List<Category> categories = LegacyFixtures.categories();
            assertThat(categories).hasSize(14);
            assertThat(rig.count("SELECT COUNT(*) FROM common_code WHERE code_group = 'DEFECT_CATEGORY'")).isEqualTo(15);
            for (Category category : categories) {
                Map<String, Object> row = rig.rows("SELECT name_ko, name_en, description_ko, description_en, sort_order, state "
                        + "FROM common_code WHERE code_group = 'DEFECT_CATEGORY' AND code = '" + category.code() + "'").getFirst();
                assertThat(text(row, "name_ko")).as(category.code()).isEqualTo(category.name());
                assertThat(text(row, "description_ko")).isEqualTo(category.definition());
                assertThat(text(row, "name_en")).isNotEqualTo(category.name());
                assertThat(text(row, "description_en")).isNotEqualTo(category.definition());
                assertThat(text(row, "state")).isEqualTo("ACTIVE");
                assertThat(text(row, "sort_order")).isEqualTo("0");
            }
            assertThat(rig.ctx().jdbc().sql("SELECT name_en FROM common_code WHERE code = 'CON'").query(String.class).single())
                    .isEqualTo("Concurrency and atomicity");
            assertThat(rig.ctx().jdbc().sql("SELECT name_en FROM common_code WHERE code = 'EXC'").query(String.class).single())
                    .isEqualTo("Error cause suppression or substitution");
            Map<String, Object> oldBug = rig.rows("SELECT name_ko, name_en, description_ko, description_en FROM common_code "
                    + "WHERE code = 'OLDBUG'").getFirst();
            assertThat(text(oldBug, "name_en")).isEqualTo(text(oldBug, "name_ko"));
            assertThat(text(oldBug, "description_en")).isEqualTo(text(oldBug, "description_ko"));
            assertThat(rig.ctx().jdbc().sql("SELECT state FROM common_code WHERE code = 'UNRATED'").query(String.class).single())
                    .isEqualTo("INACTIVE");
            assertThat(rig.count("SELECT COUNT(*) FROM common_code WHERE code_group = 'RELATION_KIND'")).isEqualTo(6);
            assertThat(rig.count("SELECT COUNT(*) FROM common_code WHERE code_group = 'DEFECT_SEVERITY' AND state = 'ACTIVE'"))
                    .isEqualTo(3);
        });
    }

    @Test
    @DisplayName("MIG-012 a defect imported with severity UNRATED stays UNRATED until it is written: an edit without a rating is CODE_INVALID, the same edit with P3 is accepted at version 2")
    void mig012_unrated() {
        withRich(rig -> {
            rig.importAll();
            SaveService save = rig.ctx().bean(SaveService.class);

            assertThat(rig.ctx().jdbc().sql("SELECT severity FROM defect WHERE id = 'OLD-D-0003'").query(String.class).single())
                    .isEqualTo("UNRATED");
            ValidationException refused = (ValidationException) Expect.failure(() -> save.save(SEED, new SaveCommand(null,
                    "defect", "OLD-D-0003", 1L, Fields.of("title", "unrated, edited"), Scenario.REASON)),
                    ValidationCode.CODE_INVALID);
            assertThat(refused.args()).startsWith("defect.severity", "UNRATED");

            SaveResult edited = save.save(SEED, new SaveCommand(null, "defect", "OLD-D-0003", 1L,
                    Fields.of(Map.of("title", "unrated, edited", "severity", "P3")), Scenario.REASON));
            assertThat(edited.version()).isEqualTo(2);
            assertThat(rig.ctx().jdbc().sql("SELECT severity FROM defect WHERE id = 'OLD-D-0003'").query(String.class).single())
                    .isEqualTo("P3");
        });
    }

    @Test
    @DisplayName("MIG-013 the detail of a defect shows the behavior of a defect that was merged into it before the import as a merged_behavior case with its observation text, next to the cases")
    void mig013_mergedBeforeTheImport() {
        withRich(rig -> {
            rig.importAll();

            DetailService.DefectDetail detail = rig.ctx().bean(DetailService.class).defect("OLD-D-0001");

            assertThat(detail.cases()).anySatisfy(entry -> {
                assertThat(entry.source()).isEqualTo("merged_behavior");
                assertThat(entry.defect()).isEqualTo("OLD-D-0002");
                assertThat(entry.observation()).isEqualTo("old merged behaviour");
            });
            assertThat(detail.cases()).extracting(DetailService.CaseView::caseId).contains(2L, 5L);
        });
    }

    @Test
    @DisplayName("MIG-014 links with the same target text in the task and the defect table, ids that contain |, re-linked rows: every link arrives and the link keys keep the kinds and the separator apart")
    void mig014_links() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM condition_link")).isEqualTo(7).isEqualTo(rig.legacy().count("condition_link"));
            assertThat(rig.ctx().jdbc().sql("SELECT link_key FROM condition_link").query(String.class).list())
                    .containsExactlyInAnyOrder("7:OLD-C-1|T|OLD-T-1", "7:OLD-C-1|T|OLD-D-0001", "7:OLD-C-1|D|OLD-D-0001",
                            "7:OLD-C-2|D|OLD-D-0001", "7:OLD-C-3|T|OLD-T-1", "10:OLD-PIPE-C|T|A|T", "12:OLD-PIPE-C|A|T|T");
            assertThat(rig.count("SELECT COUNT(*) FROM release_condition WHERE id = 'OLD-PIPE-C|A'")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = 'A|T'")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM defect_task")).isEqualTo(2);
            assertThat(rig.count("SELECT COUNT(*) FROM defect_relation")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("MIG-015 4000 characters of Korean text with both kinds of quotes, backslashes and line feeds arrive unchanged (a defect behavior and a case observation)")
    void mig015_longText() {
        withRich(rig -> {
            rig.importAll();

            String expected = LegacyFixtures.koreanText(4000);
            assertThat(expected).hasSize(4000).contains("\n").contains("\"").contains("'");
            assertThat(rig.ctx().jdbc().sql("SELECT behavior FROM defect WHERE id = 'E2E-D-0100'").query(String.class).single())
                    .isEqualTo(expected);
            assertThat(rig.ctx().jdbc().sql("SELECT observation FROM defect_case WHERE id = 5").query(String.class).single())
                    .isEqualTo(expected);
        });
    }

    @Test
    @DisplayName("MIG-016 every time value is the same instant on both sides (microseconds kept, mixed offsets of the legacy values), stored as UTC")
    void mig016_instants() {
        withRich(rig -> {
            rig.importAll();

            for (String[] column : new String[][] {{"history", "at"}, {"task", "created_at"}, {"task", "updated_at"},
                    {"defect", "created_at"}, {"defect", "updated_at"}, {"defect_fix", "ci_completed_at"},
                    {"defect_fix", "recorded_at"}, {"defect_case", "recorded_at"}, {"session_grant", "granted_at"},
                    {"session_grant", "expires_at"}, {"session_grant", "revoked_at"}, {"request_receipt", "created_at"}}) {
                String key = "request_receipt".equals(column[0]) ? "request_id" : "id";
                List<Map<String, Object>> legacy = rig.legacy().rows("SELECT " + key + " AS k, EXTRACT(EPOCH FROM " + column[1]
                        + ") AS e FROM " + rig.legacy().schema() + "." + column[0] + " ORDER BY " + key);
                for (Map<String, Object> row : legacy) {
                    // the new schema names the time of a history row occurred_at (the legacy column is at)
                    String target = "history".equals(column[0]) && "at".equals(column[1]) ? "occurred_at" : column[1];
                    Instant instant = rig.instant("SELECT " + target + " AS t FROM " + column[0] + " WHERE " + key + " = ?",
                            row.get("k") instanceof UUID ? row.get("k").toString() : row.get("k"));
                    if (row.get("e") == null) {
                        assertThat(instant).as("%s.%s %s", column[0], column[1], row.get("k")).isNull();
                    } else {
                        assertThat(epoch(instant)).as("%s.%s %s", column[0], column[1], row.get("k"))
                                .isEqualByComparingTo(new BigDecimal(row.get("e").toString()));
                    }
                }
            }
            assertThat(rig.instant("SELECT occurred_at AS t FROM history WHERE id = 1")).isEqualTo(Instant.parse("2026-11-05T10:00:00.123456Z"));
            assertThat(rig.instant("SELECT occurred_at AS t FROM history WHERE id = 2")).isEqualTo(Instant.parse("2026-11-05T10:00:00.654321Z"));
            assertThat(rig.instant("SELECT occurred_at AS t FROM history WHERE id = 3")).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
        });
    }

    @Test
    @DisplayName("MIG-017 a merge chain whose child has a lower id than its target imports (the target is inserted first, no DBMS-specific syntax): merged_into is intact")
    void mig017_mergeTargetAfterChild() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.ctx().jdbc().sql("SELECT merged_into FROM defect WHERE id = 'E2E-D-0004'").query(String.class).single())
                    .isEqualTo("E2E-D-0100");
            assertThat(rig.ctx().jdbc().sql("SELECT merged_into FROM defect WHERE id = 'OLD-D-0002'").query(String.class).single())
                    .isEqualTo("OLD-D-0001");
            assertThat(rig.count("SELECT COUNT(*) FROM defect WHERE status = 'MERGED'")).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("MIG-029 defects that are merged into each other in a loop cannot be ordered: they are reported (MERGE_CHAIN_LOOPS) and nothing is written")
    void mig029_mergeLoop() {
        LegacyLedger legacy = LegacyFixtures.rich();
        legacy.update("UPDATE " + legacy.schema() + ".defect SET merged_into = 'OLD-D-0002', status = 'MERGED' "
                + "WHERE id = 'OLD-D-0001'");
        withRig(legacy, rig -> {
            ValidationException refused = (ValidationException) Expect.failure(rig::importAll, ValidationCode.VALIDATION_FAILED);

            assertThat(refused.issues()).extracting(ValidationIssue::path, ValidationIssue::reason).containsExactlyInAnyOrder(
                    tuple("defect[OLD-D-0001].merged_into", ValidationReason.MERGE_CHAIN_LOOPS),
                    tuple("defect[OLD-D-0002].merged_into", ValidationReason.MERGE_CHAIN_LOOPS));
            assertThat(rig.ledgerRows()).isZero();
        });
    }

    // ---------------------------------------------------------------------------------------------- MIG-018 .. 019

    @Test
    @DisplayName("MIG-018 after the import the ledger works on the imported data: create (ids continue), edit with the imported version, record a fix and a case, move to SOURCE_FIXED and verify; versions continue, new history, fix and case ids exceed every imported id, no key collides")
    void mig018_writesContinue() {
        withRich(rig -> {
            rig.importAll();
            LedgerAppContext ctx = rig.ctx();
            String reviewer = "codex:mig-reviewer";
            ctx.enroll(reviewer);
            ctx.enroll(SEED);
            ctx.bean(GrantService.class).grant(new GrantCommand(reviewer, "old", List.of("fix", "verify"), null, null),
                    TestOwners.OWNER);
            ctx.bean(GrantService.class).grant(new GrantCommand(SEED, "old", List.of("fix"), null, null), TestOwners.OWNER);

            SaveResult created = ctx.bean(SaveService.class).save(SEED, new SaveCommand(null, "defect", null, 0L,
                    Fields.of(defectFields("old", "after the import")), Scenario.REASON));
            assertThat(created.id()).isEqualTo("OLD-D-0004");
            assertThat(created.historyId()).isGreaterThan(57);
            SaveResult task = ctx.bean(SaveService.class).save(SEED, new SaveCommand(null, "task", null, 0L,
                    Fields.of(Map.of("project_id", "old", "title", "new task", "purpose", "p", "success_condition", "s")),
                    Scenario.REASON));
            assertThat(task.id()).isEqualTo("OLD-T-0001");

            SaveResult edited = ctx.bean(SaveService.class).save(SEED, new SaveCommand(null, "defect", "OLD-D-0001", 1L,
                    Fields.of("title", "edited after the import"), Scenario.REASON));
            assertThat(edited.version()).isEqualTo(2);
            assertThat(edited.historyId()).isGreaterThan(created.historyId());

            RecordCaseResult observed = ctx.bean(RecordCaseService.class).recordCase(SEED, new RecordCaseCommand(null,
                    "OLD-D-0001", "seen again after the import", Scenario.REASON, null, null, null));
            assertThat(observed.caseId()).isEqualTo(6);

            RecordFixResult fixed = ctx.bean(RecordFixService.class).recordFix(SEED, new RecordFixCommand(null, "OLD-D-0001",
                    "repo/old", "d".repeat(40), Scenario.REASON, null, null, null, null, null));
            assertThat(fixed.fixId()).isEqualTo(10);
            assertThat(fixed.version()).isEqualTo(3);
            assertThat(fixed.defectStatus()).isEqualTo("SOURCE_FIXED");

            TransitionResult verified = ctx.bean(ReviewService.class).review(reviewer, new TransitionCommand(null, "defect",
                    "OLD-D-0001", fixed.version(), "VERIFIED", Scenario.REASON, Scenario.EVIDENCE, null, null));
            assertThat(verified.version()).isEqualTo(4);
            assertThat(verified.historyId()).isGreaterThan(fixed.historyId());

            TransitionResult moved = ctx.bean(TransitionService.class).transition(SEED, new TransitionCommand(null, "task",
                    "OLD-T-1", 1L, "ACTIVE", Scenario.REASON, null, null, null));
            assertThat(moved.version()).isEqualTo(2);
            // every history row written after the import has an id above the imported ones, the ids continue without a gap
            // or a collision, and they are exactly the ones the calls returned (the fix also moved the defect to
            // SOURCE_FIXED, which is a second history row of its own)
            List<Long> returned = List.of(created.historyId(), task.historyId(), edited.historyId(), observed.historyId(),
                    fixed.historyId(), fixed.transitionHistoryId(), verified.historyId(), moved.historyId());
            assertThat(rig.ctx().jdbc().sql("SELECT id FROM history WHERE id > 57 ORDER BY id").query(Long.class).list())
                    .containsExactlyInAnyOrderElementsOf(returned).containsExactly(58L, 59L, 60L, 61L, 62L, 63L, 64L, 65L);
            assertThat(rig.count("SELECT COUNT(*) FROM history")).isEqualTo(10 + returned.size());
        });
    }

    @Test
    @DisplayName("MIG-019 a row of the new schema that was changed after the import is found by the verification: INTEGRITY_VIOLATION naming the table, both counts and both digests; the verification of an untouched import passes and writes nothing")
    void mig019_verification() {
        withRich(rig -> {
            ImportReport report = rig.importAll();
            long rows = rig.ledgerRows();
            ImportReport again = rig.service().verify();
            assertThat(again.tables()).isEqualTo(report.tables());
            assertThat(rig.ledgerRows()).isEqualTo(rows);

            rig.ctx().jdbc().sql("UPDATE defect SET title = 'tampered' WHERE id = 'OLD-D-0001'").update();

            InfrastructureException failure = (InfrastructureException) Expect.failure(() -> rig.service().verify(),
                    InfrastructureCode.INTEGRITY_VIOLATION);
            TableCheck expected = report.tables().stream().filter(check -> check.table() == LegacyTable.DEFECT).findFirst()
                    .orElseThrow();
            assertThat(failure.args()).hasSize(5);
            assertThat(failure.args().get(0)).isEqualTo("defect");
            assertThat(failure.args().get(1)).isEqualTo(String.valueOf(expected.sourceRows()));
            assertThat(failure.args().get(2)).isEqualTo(String.valueOf(expected.targetRows()));
            assertThat(failure.args().get(3)).isEqualTo(expected.sourceDigest().hex());
            assertThat(failure.args().get(4)).isNotEqualTo(expected.targetDigest().hex()).hasSize(64);
        });
    }

    @Test
    @DisplayName("MIG-019 the comparison inside the import: a value that the write side changed makes the import fail with INTEGRITY_VIOLATION and roll back completely")
    void mig019_comparisonInsideTheImport() {
        withRich(rig -> {
            MisbehavingTarget target = rig.ctx().bean(MisbehavingTarget.class);
            target.tamper(true);

            InfrastructureException failure = (InfrastructureException) Expect.failure(rig::importAll,
                    InfrastructureCode.INTEGRITY_VIOLATION);

            assertThat(failure.args().get(0)).isEqualTo("defect");
            assertThat(failure.args().get(3)).isNotEqualTo(failure.args().get(4));
            assertThat(rig.ledgerRows()).isZero();
            target.tamper(false);
            assertThat(rig.importAll().tables()).allMatch(TableCheck::matches);
        }, MisbehavingTarget.Replacement.class);
    }

    // ---------------------------------------------------------------------------------------------- MIG-021 .. 024

    @Test
    @DisplayName("MIG-021 a legacy database without the installer steps defect_case, common_code or created_by, or without the schema, is refused with NOT_FOUND naming the missing steps; nothing is written")
    void mig021_incompleteSource() {
        LegacyLedger older = LegacyLedger.create(Set.of("schema", "schema_v2"));
        withRig(older, rig -> {
            LedgerException refused = Expect.failure(rig::importAll, LookupCode.NOT_FOUND);

            assertThat(refused.args().get(1)).contains("defect_case").contains("common_code").contains("created_by")
                    .doesNotContain("schema");
            assertThat(rig.ledgerRows()).isZero();
        });
        LegacyLedger none = LegacyLedger.withoutSchema();
        withRig(none, none.admin(), rig -> {
            LedgerException refused = Expect.failure(rig::importAll, LookupCode.NOT_FOUND);

            assertThat(refused.args().get(1)).contains("schema").contains("defect_case");
            assertThat(rig.ledgerRows()).isZero();
        });
    }

    @Test
    @DisplayName("MIG-022 through the history read tool the imported rows show their changes as imported and a null before_data, also when before_data is asked for")
    void mig022_readTool() {
        withRich(rig -> {
            rig.importAll();

            var page = rig.ctx().bean(HistoryService.class).history(new HistoryQuery(null, null, null, null, null, null, null,
                    null, null, null, true, null, null));

            assertThat(page.items()).hasSize(10);
            assertThat(page.items()).allSatisfy(view -> assertThat(view.beforeData()).isNull());
            // the legacy column is jsonb: the changes are the text PostgreSQL printed for it, as imported
            String changes = page.items().stream().filter(view -> view.id() == 2).findFirst().orElseThrow().changes().text();
            assertThat(changes).isEqualTo(rig.legacy().rows("SELECT changes FROM " + rig.legacy().schema()
                    + ".history WHERE id = 2").getFirst().get("changes").toString());
            assertSameJson(changes, "{\"title\":[\"old\",\"First old defect\"],\"quote\":\"\\\"x\\\"\"}");
            assertThat(page.items().stream().filter(view -> view.id() == 13).findFirst().orElseThrow().legacyRef())
                    .isEqualTo("older:1");
        });
    }

    @Test
    @DisplayName("MIG-023 (a) an empty text in a nullable legacy column is NULL in the new schema and the digests agree; (b) an empty text in a NOT NULL column is reported with table, key and column, the import exits without writing anything and invents no value, and after the source rows are corrected it succeeds")
    void mig023_emptyStrings() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.rows("SELECT owner_actor_id, origin_ref FROM task WHERE id = 'OLD-T-1'").getFirst().values())
                    .containsOnlyNulls();
            assertThat(rig.count("SELECT COUNT(*) FROM defect_case WHERE id = 2 AND evidence IS NULL")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM defect_fix WHERE id = 9 AND branch IS NULL")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE origin_ref = '' OR owner_actor_id = ''")).isZero();
        });
        LegacyLedger legacy = LegacyFixtures.withEmptyPurpose(LegacyFixtures.rich());
        withRig(legacy, rig -> {
            ValidationException refused = (ValidationException) Expect.failure(rig::importAll, ValidationCode.VALIDATION_FAILED);

            assertThat(refused.issues()).extracting(ValidationIssue::path, ValidationIssue::reason).containsExactlyInAnyOrder(
                    tuple("task[OLD-T-UNKNOWN].purpose", ValidationReason.REQUIRED),
                    tuple("task[OLD-T-UNKNOWN].success_condition", ValidationReason.REQUIRED));
            assertThat(rig.ledgerRows()).isZero();
            assertThat(rig.count("SELECT SUM(issued_no) FROM id_counter")).isZero();

            legacy.update("UPDATE " + legacy.schema() + ".task SET purpose = 'corrected', success_condition = 'corrected' "
                    + "WHERE id = 'OLD-T-UNKNOWN'");
            ImportReport report = rig.importAll();

            assertThat(report.tables()).allMatch(TableCheck::matches);
            assertThat(rig.ctx().jdbc().sql("SELECT purpose FROM task WHERE id = 'OLD-T-UNKNOWN'").query(String.class).single())
                    .isEqualTo("corrected");
        });
    }

    @Test
    @DisplayName("MIG-024 the fixture builder fills the legacy tables from category-legacy-fixture.json and the data of the old admin: the 14 canonical categories plus OLDBUG, OLD-D-0001..3, tasks, conditions, links, re-links, cases, fixes, grants, history and receipts")
    void mig024_fixture() {
        try (LegacyLedger legacy = LegacyFixtures.rich()) {
            assertThat(legacy.objects()).containsExactlyInAnyOrderElementsOf(LegacyLedger.TABLES);
            assertThat(legacy.count("common_code")).isEqualTo(25);
            assertThat(legacy.count("project")).isEqualTo(2);
            assertThat(legacy.count("task")).isEqualTo(6);
            assertThat(legacy.count("defect")).isEqualTo(6);
            assertThat(legacy.count("defect_fix")).isEqualTo(2);
            assertThat(legacy.count("defect_case")).isEqualTo(2);
            assertThat(legacy.count("condition")).isEqualTo(5);
            assertThat(legacy.count("condition_link")).isEqualTo(7);
            assertThat(legacy.count("session_grant")).isEqualTo(5);
            assertThat(legacy.count("history")).isEqualTo(10);
            assertThat(legacy.count("request_receipt")).isEqualTo(3);
            assertThat(legacy.count("_migration")).isEqualTo(5);
        }
    }

    // ---------------------------------------------------------------------------------------------- MIG-027 .. 032

    @Test
    @DisplayName("MIG-027 the timestamptz instants of the legacy columns are read as instants, whatever the zone of the legacy database session (UTC, Asia/Seoul, America/New_York): the epoch value of every imported timestamp equals the source epoch value in all three runs and the target rows are identical across the runs")
    void mig027_sessionZones() {
        List<String> snapshots = new ArrayList<>();
        for (String zone : List.of("UTC", "Asia/Seoul", "America/New_York")) {
            // one legacy fixture per zone: its pool for the zone is closed with it, so the test JVM keeps within its
            // connection budget (the data of the fixture is the same every time)
            try (LegacyLedger legacy = LegacyFixtures.rich()) {
                DataSource source = legacy.reader(zone);
                assertThat(JdbcClient.create(source).sql("SHOW TIME ZONE").query(String.class).single()).isEqualTo(zone);
                LedgerTestRunners.importing(legacy, source, ctx -> {
                    Rig rig = new Rig(legacy, ctx);
                    rig.importAll();
                    for (Map<String, Object> row : legacy.rows("SELECT id AS k, EXTRACT(EPOCH FROM at) AS e FROM "
                            + legacy.schema() + ".history")) {
                        Instant instant = rig.instant("SELECT occurred_at AS t FROM history WHERE id = ?", row.get("k"));
                        assertThat(epoch(instant)).as("%s history %s", zone, row.get("k"))
                                .isEqualByComparingTo(new BigDecimal(row.get("e").toString()));
                    }
                    snapshots.add(rig.rows("SELECT * FROM history ORDER BY id").toString() + rig.rows(
                            "SELECT * FROM task ORDER BY id") + rig.rows("SELECT * FROM session_grant ORDER BY id"));
                });
            }
        }
        assertThat(snapshots).hasSize(3);
        assertThat(snapshots.get(1)).isEqualTo(snapshots.get(0));
        assertThat(snapshots.get(2)).isEqualTo(snapshots.get(0));
    }

    @Test
    @DisplayName("MIG-028 ids with | and other upper-case ids import unchanged; ids that violate the upper-case id format (a lower-case task or condition id) are reported with table and key, nothing is written, and they are never case-folded")
    void mig028_ids() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM release_condition WHERE id IN ('OLD-PIPE-C', 'OLD-PIPE-C|A')")).isEqualTo(2);
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = 'A|T'")).isEqualTo(1);
        });
        LegacyLedger legacy = LegacyFixtures.withLowerCaseIds(LegacyFixtures.rich());
        withRig(legacy, rig -> {
            ValidationException refused = (ValidationException) Expect.failure(rig::importAll, ValidationCode.VALIDATION_FAILED);

            assertThat(refused.issues()).extracting(ValidationIssue::path, ValidationIssue::reason).containsExactlyInAnyOrder(
                    tuple("task[old-t-lower].id", ValidationReason.MUST_BE_UPPER_CASE_ID_MATCHING),
                    tuple("release_condition[old-c-lower].id", ValidationReason.MUST_BE_UPPER_CASE_ID_MATCHING));
            assertThat(rig.ledgerRows()).isZero();
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = 'OLD-T-LOWER'")).isZero();
        });
    }

    @Test
    @DisplayName("MIG-031 the import records one owner audit row IMPORT with the actor, the legacy schema as subject and the row counts as detail, and bumps the code revision so a running server reloads the common codes")
    void mig031_auditAndRevision() {
        withRich(rig -> {
            rig.importAll();

            List<Map<String, Object>> audit = rig.rows("SELECT actor, operation, subject, detail FROM owner_audit ORDER BY id");
            assertThat(audit).hasSize(1);
            assertThat(text(audit.getFirst(), "actor")).isEqualTo(TestOwners.OWNER.value());
            assertThat(text(audit.getFirst(), "operation")).isEqualTo("IMPORT");
            assertThat(text(audit.getFirst(), "subject")).isEqualTo("legacy schema " + rig.legacy().schema());
            assertThat(text(audit.getFirst(), "detail")).startsWith("rows=").contains("history=10").contains("project=2");
            assertThat(rig.count("SELECT revision FROM code_revision")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("SVC-PRN-05 a legacy task whose id has a number of 20 digits has no number: the import and the verification are not stopped by a NumberFormatException, the counter ignores it and still continues after the 4 digit id")
    void prn05_taskIdWithAHugeNumber() {
        LegacyLedger legacy = LegacyFixtures.rich();
        String huge = "E2E-T-" + "9".repeat(20);
        legacy.update("UPDATE " + legacy.schema() + ".task SET id = ? WHERE id = 'E2E-T-1'", huge);
        withRig(legacy, rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = ?", huge)).isEqualTo(1);
            assertThat(rig.count("SELECT next_task_no FROM project WHERE id = 'e2e'")).isEqualTo(3);
            rig.service().verify();
        });
    }

    @Test
    @DisplayName("MIG-032 a text longer than its column in the new schema is reported (never cut): table, key, column and the limit")
    void mig032_tooLong() {
        LegacyLedger legacy = LegacyFixtures.rich();
        legacy.update("UPDATE " + legacy.schema() + ".task SET origin_ref = ? WHERE id = 'OLD-T-1'", "x".repeat(501));
        withRig(legacy, rig -> {
            ValidationException refused = (ValidationException) Expect.failure(rig::importAll, ValidationCode.VALIDATION_FAILED);

            assertThat(refused.issues()).hasSize(1);
            ValidationIssue issue = refused.issues().getFirst();
            assertThat(issue.path()).isEqualTo("task[OLD-T-1].origin_ref");
            assertThat(issue.reason()).isEqualTo(ValidationReason.MUST_BE_AT_MOST_CHARS);
            assertThat(issue.reasonArgs()).containsExactly("500");
            assertThat(rig.ledgerRows()).isZero();
        });
    }

    @Test
    @DisplayName("MIG-033 the service says whether the legacy connection is configured (sourceConfigured), and the legacy schema name must be an identifier")
    void sourceNotConfigured() {
        LegacyLedger legacy = LegacyFixtures.rich();
        withRig(legacy, rig -> assertThat(rig.service().sourceConfigured()).isTrue());
        try (LegacyLedger other = LegacyLedger.create()) {
            assertThat(new LegacySourceRepository(other.admin(), other.schema()).schema()).isEqualTo(other.schema());
            assertThatThrownBy(() -> new LegacySourceRepository(other.admin(), "Ledger; DROP"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
