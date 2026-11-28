// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.database.support.DatabaseTest;
import org.veridger.database.support.LedgerTestContext;
import org.veridger.database.support.Seed;
import org.veridger.domain.model.Criterion;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.Project;
import org.veridger.domain.model.Task;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-001 .. REPO-003, REPO-005, SCH-012, SCH-016 .. SCH-018 and the catalog half of ANSI-004 / ANSI-014. */
@DatabaseTest
class SchemaCatalogTest {

    private static final java.util.regex.Pattern TIME_DEFAULT =
            java.util.regex.Pattern.compile("CURRENT_|LOCALTIME|NOW|SYSDATE");

    static final Set<String> TABLES = Set.of("project", "task", "spec", "issue", "deliverable", "issue_case",
            "issue_task", "issue_spec", "spec_task", "issue_relation", "milestone_criterion", "criterion_link",
            "session_grant", "session_key", "session_token", "owner_audit", "history", "request_receipt", "common_code",
            "code_revision", "id_counter", "schema_history", "driver_audit_key", "owner_passkey_user",
            "owner_passkey", "owner_passkey_counter", "owner_passkey_revocation", "owner_passkey_code",
            "upload_staging", "upload_quota_gate", "attachment", "attachment_file", "attachment_storage",
            "project_version", "issue_project_version", "project_version_item", "milestone_archive");

    /** The timestamp columns of the schema, exactly as the catalogue lists them (SCH-018). */
    static final Set<String> TIMESTAMP_COLUMNS = Set.of("issue.created_at", "issue.updated_at", "spec.created_at",
            "spec.updated_at", "task.created_at", "task.updated_at", "deliverable.checked_at",
            "deliverable.recorded_at", "issue_case.observed_at", "issue_case.recorded_at",
            "session_grant.granted_at", "session_grant.expires_at", "session_grant.revoked_at",
            "session_grant.last_used_at", "session_key.registered_at", "session_key.revoked_at",
            "session_token.issued_at", "session_token.expires_at", "session_token.revoked_at",
            "session_token.last_used_at", "owner_audit.occurred_at", "history.occurred_at",
            "request_receipt.created_at", "schema_history.applied_at", "owner_passkey.registered_at",
            "owner_passkey_counter.updated_at", "owner_passkey_revocation.revoked_at", "owner_passkey_code.issued_at",
            "owner_passkey_code.expires_at", "owner_passkey_code.used_at", "upload_staging.expires_at",
            "upload_staging.created_at", "upload_staging.updated_at", "attachment.created_at",
            "attachment_storage.created_at");

    private LedgerTestContext ctx;
    private JdbcClient jdbc;

    /** A storage key that no other test of the shared test schema uses (32 hex characters; the column is unique). */
    private static String uniqueKey() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        jdbc = JdbcClient.create(ctx.pool());
    }

    private <T> T withConnection(ConnectionWork<T> work) {
        // Spring gets and releases the connection and turns a SQLException into a DataAccessException
        return new JdbcTemplate(ctx.pool()).execute((ConnectionCallback<T>) work::run);
    }

    interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private Set<String> tableNames(String type) {
        return withConnection(c -> {
            Set<String> names = new TreeSet<>();
            try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), null, new String[] {type})) {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
            return names;
        });
    }

    /** SCH-032 (F10): the three columns whose natural names are reserved words of SQL:2003 are named without them. */
    @Test
    void sch032_noColumnIsNamedWithAWordSql2003Reserves() {
        Set<String> names = columns().stream().map(Column::key).collect(java.util.stream.Collectors.toSet());
        assertThat(names).contains("history.occurred_at", "owner_audit.occurred_at", "session_grant.grant_scope")
                .doesNotContain("history.at", "owner_audit.at", "session_grant.scope");
    }

    private record Column(String table, String name, int type, boolean nullable, String defaultValue) {
        String key() {
            return table + "." + name;
        }
    }

    private List<Column> columns() {
        return withConnection(c -> {
            List<Column> columns = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), c.getSchema(), null, null)) {
                while (rs.next()) {
                    columns.add(new Column(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT),
                            rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getInt("DATA_TYPE"),
                            "YES".equals(rs.getString("IS_NULLABLE")), rs.getString("COLUMN_DEF")));
                }
            }
            return columns;
        });
    }

    @Test
    void repo001_theTablesAreExactlyTheDesignedOnes() {
        assertThat(tableNames("TABLE")).isEqualTo(new TreeSet<>(TABLES));
        assertThat(tableNames("VIEW")).as("no views").isEmpty();
        assertThat(columns().stream().filter(c -> "issue".equals(c.table())).map(Column::name)).contains("case_seq");
        assertThat(columns().stream().filter(c -> "history".equals(c.table())).map(Column::name))
                .contains("source_ref");
    }

    @ParameterizedTest
    @CsvSource({"triggers,event_object_schema", "routines,routine_schema", "sequences,sequence_schema"})
    void ansi004_theSchemaOwnsNoTriggersRoutinesOrSequences(String view, String schemaColumn) {
        String schema = withConnection(Connection::getSchema);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema." + view + " WHERE " + schemaColumn
                + " = :schema").param("schema", schema).query(Integer.class).single()).isZero();
    }

    @Test
    void repo002_noBooleanColumns() {
        assertThat(columns()).noneMatch(c -> c.type() == Types.BOOLEAN || c.type() == Types.BIT);
    }

    /** The enum-valued columns of REPO-002 are text; the CHECK that closes their value set is proved by REPO-003. */
    @ParameterizedTest
    @CsvSource({"common_code.state", "deliverable.origin", "request_receipt.operation"})
    void repo002_anEnumColumnIsText(String column) {
        String table = column.substring(0, column.indexOf('.'));
        String name = column.substring(column.indexOf('.') + 1);
        assertThat(columns()).filteredOn(c -> Objects.equals(c.table(), table) && Objects.equals(c.name(), name)).singleElement()
                .satisfies(c -> assertThat(c.type()).isEqualTo(Types.VARCHAR));
    }

    /** SQL that must be refused with SQLState class 23; {@code :x} names the rows seeded for the test. */
    static Stream<String> valuesOutsideTheFixedSets() {
        return Stream.of(
                "INSERT INTO common_code (code_group, code, name_ko, name_en, sort_order, state, created_by) "
                        + "VALUES ('RELATION_KIND', 'zz', 'a', 'a', 1, 'MAYBE', 'user:owner')",
                "INSERT INTO common_code (code_group, code, name_ko, name_en, sort_order, state, created_by) "
                        + "VALUES ('OTHER', 'zz', 'a', 'a', 1, 'ACTIVE', 'user:owner')",
                // V4: the direction is GIVEN or CANONICAL, and only a relation kind can be GIVEN
                "INSERT INTO common_code (code_group, code, name_ko, name_en, sort_order, state, direction, created_by) "
                        + "VALUES ('RELATION_KIND', 'zz', 'a', 'a', 1, 'ACTIVE', 'MAYBE', 'user:owner')",
                "INSERT INTO common_code (code_group, code, name_ko, name_en, sort_order, state, direction, created_by) "
                        + "VALUES ('ISSUE_SEVERITY', 'zz', 'a', 'a', 1, 'ACTIVE', 'GIVEN', 'user:owner')",
                "INSERT INTO issue_relation (a, b, kind, created_by) VALUES (:issue, :issue, 'blocks', 'user:owner')",
                "INSERT INTO id_counter (name, issued_no) VALUES ('OTHER', 1)",
                "UPDATE issue SET status = 'FIXED' WHERE id = :issue",
                "UPDATE spec SET status = 'DONE' WHERE id = :spec",
                "UPDATE task SET state = 'DONE' WHERE id = :task",
                "UPDATE milestone_criterion SET state = 'INVALID' WHERE id = :criterion",
                "INSERT INTO deliverable (id, target_entity, target_id, kind, deliverable_ref, summary, check_ref, "
                        + "check_result, recorded_by, recorded_at, origin) VALUES (900001, 'ISSUE', :issue, "
                        + "'code_change', 'ref-1', 'sum', 'ref', 'SUCCESS', 'claude:a', "
                        + "TIMESTAMP '2026-11-05 10:00:00', 'NATIVE')",
                "INSERT INTO deliverable (id, target_entity, target_id, kind, deliverable_ref, summary, recorded_by, "
                        + "recorded_at, origin) VALUES (900003, 'ISSUE', :issue, 'code_change', 'ref-2', 'sum', "
                        + "'claude:a', TIMESTAMP '2026-11-05 10:00:00', 'OTHER')",
                "INSERT INTO request_receipt (operation_key, operation, payload_sha256, author_id, login, "
                        + "created_at) VALUES ('22222222-2222-2222-2222-222222222222', 'OTHER', 'aa', 'claude:a', "
                        + "'ledger_author', TIMESTAMP '2026-11-05 10:00:00')",
                "INSERT INTO session_grant (id, author_id, project_id, grant_scope, granted_by, granted_at, active_key) "
                        + "VALUES (900002, 'claude:a', :project, 'ADMIN', 'user:owner', "
                        + "TIMESTAMP '2026-11-05 10:00:00', 'k900002')",
                "INSERT INTO owner_audit (id, occurred_at, actor, operation, subject) VALUES (900004, "
                        + "TIMESTAMP '2026-11-05 10:00:00', 'owner', 'OTHER', 'claude:a|p')",
                "INSERT INTO schema_history (version, attempt, script, digest, applied_at, outcome) VALUES (99, 1, "
                        + "'V99__x.sql', '" + "a".repeat(64) + "', TIMESTAMP '2026-11-05 10:00:00', 'MAYBE')");
    }

    @ParameterizedTest
    @MethodSource("valuesOutsideTheFixedSets")
    void repo003_012_valuesOutsideTheFixedSetsAreRefusedWithSqlStateClass23(String sql) {
        Seed seed = new Seed(ctx);
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "check me", LedgerTestContext.START);
        var spec = seed.spec(project, 1, "check spec", LedgerTestContext.START);
        Task task = seed.task(project, 1, "check task");
        Criterion criterion = seed.criterion(project, project.code().value() + "-C-1", "1.0", "C1");
        Throwable thrown = catchThrowable(() -> jdbc.sql(sql).param("project", project.id().value())
                .param("issue", issue.id().value()).param("spec", spec.id().value())
                .param("task", task.id().value()).param("criterion", criterion.id().value()).update());
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sqlState(thrown)).startsWith("23");
    }

    private static String sqlState(Throwable e) {
        return ((SQLException) NestedExceptionUtils.getMostSpecificCause(e)).getSQLState();
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "task", "spec", "issue", "milestone_criterion", "issue_task", "issue_spec",
            "spec_task", "issue_relation", "criterion_link", "common_code"})
    void repo005_createdByIsNotNull(String table) {
        assertThat(columns()).filteredOn(c -> Objects.equals(c.table(), table) && "created_by".equals(c.name()))
                .singleElement().satisfies(c -> assertThat(c.nullable()).isFalse());
    }

    /** RUN-010 (SCH-012): the history table in the common form of the three products (format 2). */
    @Test
    void sch012_run010_schemaHistoryHasExactlyTheColumnsOfTheCommonForm() {
        assertThat(columns().stream().filter(c -> "schema_history".equals(c.table())).map(Column::name))
                .containsExactlyInAnyOrder("version", "attempt", "script", "digest", "applied_at", "outcome");
        record Shape(String name, String type, Integer length, String nullable) {
        }
        assertThat(jdbc.sql("SELECT column_name, data_type, character_maximum_length, is_nullable FROM "
                + "information_schema.columns WHERE table_schema = CURRENT_SCHEMA AND table_name = 'schema_history' "
                + "ORDER BY column_name").query((rs, n) -> new Shape(rs.getString(1), rs.getString(2),
                        (Integer) rs.getObject(3), rs.getString(4))).list()).containsExactly(
                new Shape("applied_at", "timestamp without time zone", null, "NO"),
                new Shape("attempt", "integer", null, "NO"), new Shape("digest", "character", 64, "NO"),
                new Shape("outcome", "character varying", 10, "NO"), new Shape("script", "character varying", 255, "NO"),
                new Shape("version", "bigint", null, "NO"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schema_history WHERE outcome = 'SUCCEEDED'").query(Integer.class)
                .single()).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schema_history WHERE outcome <> 'SUCCEEDED'").query(Integer.class)
                .single()).as("a migrated schema has no failed attempt").isZero();
    }

    /**
     * OPK-013: the idempotency key column is operation_key VARCHAR(128) NOT NULL in history and request_receipt
     * (the table name stays); the column request_id is gone.
     */
    @ParameterizedTest
    @ValueSource(strings = {"history", "request_receipt"})
    void opk013_operationKeyIsAVarchar128NotNullAndRequestIdIsGone(String table) {
        assertThat(jdbc.sql("SELECT data_type, character_maximum_length, is_nullable FROM information_schema.columns "
                + "WHERE table_schema = CURRENT_SCHEMA AND table_name = :table AND column_name = 'operation_key'")
                .param("table", table).query((rs, n) -> rs.getString(1) + "/" + rs.getInt(2) + "/"
                        + rs.getString(3)).list()).containsExactly("character varying/128/NO");
        assertThat(columns().stream().filter(c -> table.equals(c.table())).map(Column::name))
                .contains("operation_key").doesNotContain("request_id");
    }

    /** OPK-013: the primary key pk_request_receipt covers author_id and operation_key, in this order (a key per author). */
    @Test
    void opk013_theReceiptPrimaryKeyCoversAuthorAndOperationKey() {
        assertThat(jdbc.sql("SELECT kcu.column_name FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name "
                + "AND kcu.constraint_schema = tc.constraint_schema AND kcu.table_name = tc.table_name "
                + "WHERE tc.table_schema = CURRENT_SCHEMA AND tc.table_name = 'request_receipt' "
                + "AND tc.constraint_type = 'PRIMARY KEY' AND tc.constraint_name = 'pk_request_receipt' "
                + "ORDER BY kcu.ordinal_position").query(String.class).list())
                .containsExactly("author_id", "operation_key");
    }

    /** RUN-010, RUN-016: the key is (version, attempt); the outcome is SUCCEEDED or FAILED and nothing else. */
    @Test
    void run016_theHistoryKeyAndTheOutcomeCheck() {
        String insert = "INSERT INTO schema_history (version, attempt, script, digest, applied_at, outcome) VALUES "
                + "(98, :attempt, 'V98__x.sql', :digest, TIMESTAMP '2026-11-05 10:00:00', :outcome)";
        String digest = "a".repeat(64);
        try {
            jdbc.sql(insert).param("attempt", 1).param("digest", digest).param("outcome", "FAILED").update();
            jdbc.sql(insert).param("attempt", 2).param("digest", digest).param("outcome", "SUCCEEDED").update();

            assertThat(catchThrowable(() -> jdbc.sql(insert).param("attempt", 2).param("digest", digest)
                    .param("outcome", "FAILED").update())).as("the same (version, attempt)")
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            for (String outcome : List.of("SUCCESS", "MAYBE", "succeeded", "")) {
                Throwable refused = catchThrowable(() -> jdbc.sql(insert).param("attempt", 3).param("digest", digest)
                        .param("outcome", outcome).update());
                assertThat(refused).as("outcome '%s'", outcome).isInstanceOf(DataIntegrityViolationException.class);
                assertThat(sqlState(refused)).startsWith("23");
            }
            assertThat(jdbc.sql("SELECT attempt FROM schema_history WHERE version = 98 ORDER BY attempt")
                    .query(Integer.class).list()).containsExactly(1, 2);
        } finally {
            // the schema of the class is shared by its tests (SCH-012 counts the failed attempts of a migrated schema): leave it as found
            jdbc.sql("DELETE FROM schema_history WHERE version = 98").update();
        }
    }

    @Test
    void sch016_seedCodes() {
        assertThat(jdbc.sql("SELECT code FROM common_code WHERE code_group = 'ISSUE_SEVERITY' AND state = 'ACTIVE' "
                + "ORDER BY sort_order").query(String.class).list()).containsExactly("P1", "P2", "P3");
        assertThat(jdbc.sql("SELECT code FROM common_code WHERE code_group = 'ISSUE_SEVERITY' AND state = "
                + "'INACTIVE'").query(String.class).list()).containsExactly("UNRATED");
        assertThat(jdbc.sql("SELECT code FROM common_code WHERE code_group = 'RELATION_KIND' AND state = 'ACTIVE' "
                + "ORDER BY sort_order").query(String.class).list()).containsExactly("same_site", "caused_by",
                "blocks", "same_family", "same_work_unit", "regression_of");
        assertThat(jdbc.sql("SELECT code FROM common_code WHERE code_group = 'DELIVERABLE_KIND' AND state = 'ACTIVE' "
                + "ORDER BY sort_order").query(String.class).list()).containsExactly("code_change", "document",
                "media", "design", "dataset", "specification", "artifact", "other");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM common_code WHERE code_group = 'ISSUE_CATEGORY'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT DISTINCT code_group FROM common_code").query(String.class).list())
                .containsExactlyInAnyOrder("ISSUE_SEVERITY", "DELIVERABLE_KIND", "RELATION_KIND");
    }

    /** SCH-035: V4 adds the direction to every code; only caused_by and blocks of the seed are GIVEN. */
    @Test
    void sch035_theDirectionOfTheRelationKinds() {
        assertThat(columns()).filteredOn(c -> Objects.equals(c.table(), "common_code") && "direction".equals(c.name()))
                .singleElement().satisfies(c -> {
                    assertThat(c.type()).isEqualTo(Types.VARCHAR);
                    assertThat(c.nullable()).isFalse();
                });
        assertThat(jdbc.sql("SELECT code FROM common_code WHERE direction = 'GIVEN' ORDER BY code")
                .query(String.class).list()).containsExactly("blocks", "caused_by");
        assertThat(jdbc.sql("SELECT code FROM common_code WHERE code_group = 'RELATION_KIND' AND direction = "
                + "'CANONICAL' ORDER BY sort_order").query(String.class).list())
                .containsExactly("same_site", "same_family", "same_work_unit", "regression_of");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM common_code WHERE code_group <> 'RELATION_KIND' AND direction <> "
                + "'CANONICAL'").query(Integer.class).single()).isZero();
    }

    /** SCH-036 (TX-04): V5 adds the version of a common code; every code that exists starts at 0. */
    @Test
    void sch036_theVersionOfTheCommonCodes() {
        assertThat(columns()).filteredOn(c -> Objects.equals(c.table(), "common_code") && "version".equals(c.name()))
                .singleElement().satisfies(c -> {
                    assertThat(c.type()).isEqualTo(Types.BIGINT);
                    assertThat(c.nullable()).isFalse();
                });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM common_code WHERE code_group = 'ISSUE_SEVERITY' AND code = 'P1' "
                + "AND version = 0").query(Integer.class).single())
                .as("the seed codes start at version 0").isEqualTo(1);
    }

    /** SCH-037 (TX-03): V6 makes the key of a driver registration unique, so that a database refuses the second claim. */
    @Test
    void sch037_theKeyOfADriverRegistrationIsUnique() {
        assertThat(columns()).filteredOn(c -> Objects.equals(c.table(), "driver_audit_key")).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("registration_key");
            assertThat(c.type()).isEqualTo(Types.VARCHAR);
            assertThat(c.nullable()).isFalse();
        });
        String key = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.sql("INSERT INTO driver_audit_key (registration_key) VALUES (:key)").param("key", key).update();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> jdbc
                .sql("INSERT INTO driver_audit_key (registration_key) VALUES (:key)").param("key", key).update());

        assertThat(thrown).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(sqlState(thrown)).startsWith("23");
    }

    /**
     * SCH-038 (PSK-DB-01): V7 adds the passkey tables (the credential id is the key, the three flags are 0 or 1, a revocation and a
     * code hash are unique, no column is a boolean or a BLOB) and lets the owner's audit trail carry the four passkey operations.
     */
    @Test
    void sch038_thePasskeyTablesAndTheirAuditOperations() {
        String credential = "cred-" + java.util.UUID.randomUUID();
        String insert = "INSERT INTO owner_passkey (credential_id, user_id, public_key, signature_count, uv_initialized, "
                + "backup_eligible, backup_state, attestation_object, label, fingerprint, registered_by, registered_at) "
                + "VALUES (:id, 'u', 'AAAA', 0, 1, :flag, 0, 'BBBB', 'k', '0000-0000-0000-0000', 'cli:owner', "
                + "TIMESTAMP '2026-11-15 10:00:00')";
        jdbc.sql(insert).param("id", credential).param("flag", 0).update();

        assertThat(sqlState(org.assertj.core.api.Assertions.catchThrowable(
                () -> jdbc.sql(insert).param("id", credential).param("flag", 0).update()))).startsWith("23");
        assertThat(sqlState(org.assertj.core.api.Assertions.catchThrowable(
                () -> jdbc.sql(insert).param("id", credential + "-b").param("flag", 2).update()))).startsWith("23");
        assertThat(columns()).filteredOn(c -> c.table().startsWith("owner_passkey"))
                .noneMatch(c -> c.type() == Types.BLOB || c.type() == Types.BINARY || c.type() == Types.VARBINARY
                        || c.type() == Types.LONGVARBINARY || c.type() == Types.BOOLEAN || c.type() == Types.BIT);
        String revoke = "INSERT INTO owner_passkey_revocation (credential_id, revoked_by, revoked_at) VALUES (:id, "
                + "'cli:owner', TIMESTAMP '2026-11-15 10:00:00')";
        jdbc.sql(revoke).param("id", credential).update();
        assertThat(sqlState(org.assertj.core.api.Assertions.catchThrowable(
                () -> jdbc.sql(revoke).param("id", credential).update()))).startsWith("23");

        String hash = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2);
        String code = "INSERT INTO owner_passkey_code (code_hash, issued_by, issued_at, expires_at) VALUES (:hash, "
                + "'cli:owner', TIMESTAMP '2026-11-15 10:00:00', TIMESTAMP '2026-11-15 10:15:00')";
        jdbc.sql(code).param("hash", hash).update();
        assertThat(sqlState(org.assertj.core.api.Assertions.catchThrowable(
                () -> jdbc.sql(code).param("hash", hash).update()))).startsWith("23");

        long id = 910_000L;
        for (String operation : List.of("PASSKEY_CODE_ISSUED", "PASSKEY_REGISTERED", "PASSKEY_REVOKED",
                "PASSKEY_RESET")) {
            jdbc.sql("INSERT INTO owner_audit (id, occurred_at, actor, operation, subject) VALUES (:id, "
                    + "TIMESTAMP '2026-11-15 10:00:00', 'cli:owner', :operation, 'passkey')")
                    .param("id", id++).param("operation", operation).update();
        }
    }

    /**
     * ATA-015 (design 5): V8 holds the metadata of the attachments and no body: no column of a BLOB, BYTEA, CLOB or long text
     * type, no foreign key on the two tables or on history.attachment_id, the key of an operation is unique per owner, the state
     * is checked, and the attachment column of history is a nullable VARCHAR.
     */
    @Test
    void ata015_theAttachmentTablesHoldMetadataOnly() {
        List<Column> own = columns().stream().filter(c -> Objects.equals(c.table(), "upload_staging")
                || Objects.equals(c.table(), "attachment") || Objects.equals(c.table(), "attachment_file")
                || Objects.equals(c.table(), "attachment_storage")).toList();
        assertThat(own.stream().map(Column::key)).contains("upload_staging.attachment_id", "upload_staging.seq",
                "upload_staging.owner", "upload_staging.operation_key", "upload_staging.declared_sha256",
                "upload_staging.stored_bytes", "upload_staging.next_sequence", "upload_staging.last_chunk_sha256",
                "upload_staging.storage_key", "upload_staging.key_id", "upload_staging.state",
                "attachment.id", "attachment.created_by", "attachment.created_at",
                "attachment_file.attachment_id", "attachment_file.seq", "attachment_file.file_name",
                "attachment_file.media_type", "attachment_file.size_bytes", "attachment_file.content_sha256",
                "attachment_storage.attachment_id", "attachment_storage.seq", "attachment_storage.rev",
                "attachment_storage.storage_key", "attachment_storage.key_id", "attachment_storage.created_at");
        assertThat(own.stream().map(Column::key)).as("the key of a file is (attachment_id, seq): no id column")
                .doesNotContain("attachment_file.id", "attachment_storage.id");
        // primary keys: attachment_file (attachment_id, seq) and attachment_storage (attachment_id, seq, rev)
        assertThat(primaryKeyColumns("attachment_file")).containsExactly("1:attachment_id", "2:seq");
        assertThat(primaryKeyColumns("attachment_storage")).containsExactly("1:attachment_id", "2:seq", "3:rev");
        assertThat(own.stream().map(Column::key)).as("the location and the key live in attachment_storage")
                .doesNotContain("attachment.storage_key", "attachment.key_id",
                        "attachment_file.storage_key", "attachment_file.key_id");
        assertThat(own).as("no body column of any kind")
                .noneMatch(c -> c.type() == Types.BLOB || c.type() == Types.CLOB || c.type() == Types.BINARY
                        || c.type() == Types.VARBINARY || c.type() == Types.LONGVARBINARY || c.type() == Types.LONGVARCHAR
                        || c.type() == Types.BOOLEAN || c.type() == Types.BIT || c.type() == Types.OTHER);
        assertThat(columns()).filteredOn(c -> Objects.equals(c.key(), "history.attachment_id")).singleElement()
                .satisfies(c -> {
                    assertThat(c.type()).isEqualTo(Types.VARCHAR);
                    assertThat(c.nullable()).isTrue();
                });
        // no foreign key: none of the four tables nor history.attachment_id has one
        long foreignKeys = withConnection(c -> {
            long count = 0;
            for (String table : List.of("upload_staging", "attachment", "attachment_file", "attachment_storage")) {
                try (ResultSet rs = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), table)) {
                    while (rs.next()) {
                        count++;
                    }
                }
                try (ResultSet rs = c.getMetaData().getExportedKeys(c.getCatalog(), c.getSchema(), table)) {
                    while (rs.next()) {
                        count++;
                    }
                }
            }
            try (ResultSet rs = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), "history")) {
                while (rs.next()) {
                    if (Objects.equals(rs.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT), "attachment_id")) {
                        count++;
                    }
                }
            }
            return count;
        });
        assertThat(foreignKeys).isZero();
        // (owner, operation_key, seq) is unique and state is checked
        String insert = "INSERT INTO upload_staging (id, attachment_id, seq, owner, operation_key, file_name, media_type, "
                + "declared_size, declared_sha256, received_bytes, stored_bytes, next_sequence, storage_key, key_id, state, "
                + "expires_at, created_at, updated_at) VALUES (:id, :att, 0, 'claude:a', 'op-1', 'f.txt', 'text/plain', 1, "
                + ":sha, 0, 0, 0, :sk, 'k1', :state, TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 10:00:00', "
                + "TIMESTAMP '2026-11-05 10:00:00')";
        String sha = "a".repeat(64);
        try {
            jdbc.sql(insert).param("id", "11111111-1111-1111-1111-111111111111")
                    .param("att", "aaaaaaaa-1111-1111-1111-111111111111").param("sha", sha)
                    .param("sk", uniqueKey()).param("state", "STAGING").update();
            Throwable sameOperation = catchThrowable(() -> jdbc.sql(insert)
                    .param("id", "22222222-2222-2222-2222-222222222222")
                    .param("att", "bbbbbbbb-2222-2222-2222-222222222222")
                    .param("sha", sha).param("sk", uniqueKey()).param("state", "STAGING").update());
            assertThat(sameOperation).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            Throwable badState = catchThrowable(() -> jdbc.sql(insert.replace("'op-1'", "'op-2'"))
                    .param("id", "33333333-3333-3333-3333-333333333333")
                    .param("att", "cccccccc-3333-3333-3333-333333333333").param("sha", sha).param("sk", uniqueKey())
                    .param("state", "DELETING").update());
            assertThat(badState).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(sqlState(badState)).startsWith("23");
        } finally {
            jdbc.sql("DELETE FROM upload_staging WHERE id = '11111111-1111-1111-1111-111111111111'").update();
        }
    }

    private List<String> primaryKeyColumns(String table) {
        return withConnection(c -> {
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getPrimaryKeys(c.getCatalog(), c.getSchema(), table)) {
                while (rs.next()) {
                    columns.add(rs.getInt("KEY_SEQ") + ":" + rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            return columns.stream().sorted().toList();
        });
    }

    @Test
    void sch017_countersAndRevisionRow() {
        assertThat(jdbc.sql("SELECT name FROM id_counter ORDER BY name").query(String.class).list())
                .containsExactly("DELIVERABLE", "HISTORY", "ISSUE_CASE", "OWNER_AUDIT", "SESSION_GRANT",
                        "SESSION_KEY", "SESSION_TOKEN");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM code_revision").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM upload_quota_gate").query(Integer.class).single()).isEqualTo(1);
        Project fresh = new Seed(ctx).project();
        assertThat(jdbc.sql("SELECT next_issue_no + next_spec_no + next_task_no FROM project WHERE id = :id")
                .param("id", fresh.id().value()).query(Long.class).single()).isZero();
    }

    @Test
    void sch018_timestampColumnsAreExactlyTheListedOnesWithoutDefaults() {
        List<Column> timestamps = columns().stream().filter(c -> c.type() == Types.TIMESTAMP).toList();
        assertThat(timestamps.stream().map(Column::key)).containsExactlyInAnyOrderElementsOf(TIMESTAMP_COLUMNS);
        assertThat(timestamps).allSatisfy(c -> assertThat(c.defaultValue()).as(c.key()).isNull());
        assertThat(columns()).noneMatch(c -> c.type() == Types.TIMESTAMP_WITH_TIMEZONE
                || c.type() == Types.TIME_WITH_TIMEZONE);
        // ANSI-014 (catalog): no column of any type has a time default
        assertThat(columns()).noneMatch(c -> c.defaultValue() != null
                && TIME_DEFAULT.matcher(c.defaultValue().toUpperCase(Locale.ROOT)).find());
    }

    @Test
    void r4F1_specParentCompositeFkRefusesCrossProjectParent() {
        Seed seed = new Seed(ctx);
        Project p1 = seed.project();
        Project p2 = seed.project();
        String insert = "INSERT INTO spec (id, id_key, project_id, parent_id, title, status, version, created_at, "
                + "updated_at, created_by) VALUES (:id, :key, :project, :parent, 't', 'DRAFT', 1, "
                + "TIMESTAMP '2026-11-19 10:00:00', TIMESTAMP '2026-11-19 10:00:00', 'claude:a')";
        jdbc.sql(insert).param("id", "SP-P1-ROOT").param("key", "SP-P1-ROOT")
                .param("project", p1.id().value()).param("parent", null).update();
        Throwable crossProject = catchThrowable(() -> jdbc.sql(insert)
                .param("id", "SP-P2-CHILD").param("key", "SP-P2-CHILD")
                .param("project", p2.id().value()).param("parent", "SP-P1-ROOT").update());
        assertThat(crossProject).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sqlState(crossProject)).startsWith("23");
    }
}
