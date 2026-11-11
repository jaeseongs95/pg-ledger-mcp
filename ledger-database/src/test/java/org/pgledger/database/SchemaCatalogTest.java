package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Task;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-001 .. REPO-003, REPO-005, SCH-012, SCH-016 .. SCH-018 and the catalog half of ANSI-004 / ANSI-014. */
@DatabaseTest
class SchemaCatalogTest {

    private static final java.util.regex.Pattern TIME_DEFAULT =
            java.util.regex.Pattern.compile("CURRENT_|LOCALTIME|NOW|SYSDATE");

    static final Set<String> TABLES = Set.of("project", "task", "defect", "defect_fix", "defect_case", "defect_task",
            "defect_relation", "release_condition", "condition_link", "session_grant", "history", "request_receipt",
            "common_code", "code_revision", "id_counter", "schema_history");

    /** The timestamp columns of the schema, exactly as the catalogue lists them (SCH-018). */
    static final Set<String> TIMESTAMP_COLUMNS = Set.of("defect.created_at", "defect.updated_at", "task.created_at",
            "task.updated_at", "defect_fix.ci_completed_at", "defect_fix.recorded_at", "defect_case.observed_at",
            "defect_case.recorded_at", "session_grant.granted_at", "session_grant.expires_at",
            "session_grant.revoked_at", "session_grant.last_used_at", "history.at", "request_receipt.created_at",
            "schema_history.applied_at");

    private LedgerTestContext ctx;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        jdbc = JdbcClient.create(ctx.pool());
    }

    private <T> T withConnection(ConnectionWork<T> work) {
        try (Connection c = ctx.pool().getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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
        assertThat(columns().stream().filter(c -> c.table().equals("defect")).map(Column::name)).contains("case_seq");
        assertThat(columns().stream().filter(c -> c.table().equals("request_receipt")).map(Column::name))
                .contains("origin");
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
    @CsvSource({"common_code.state", "defect_fix.origin", "request_receipt.origin"})
    void repo002_anEnumColumnIsText(String column) {
        String table = column.substring(0, column.indexOf('.'));
        String name = column.substring(column.indexOf('.') + 1);
        assertThat(columns()).filteredOn(c -> c.table().equals(table) && c.name().equals(name)).singleElement()
                .satisfies(c -> assertThat(c.type()).isEqualTo(Types.VARCHAR));
    }

    /** SQL that must be refused with SQLState class 23; {@code :x} names the rows seeded for the test. */
    static Stream<String> valuesOutsideTheFixedSets() {
        return Stream.of(
                "INSERT INTO common_code (code_group, code, name_ko, name_en, sort_order, state, created_by) "
                        + "VALUES ('RELATION_KIND', 'zz', 'a', 'a', 1, 'MAYBE', 'user:owner')",
                "INSERT INTO common_code (code_group, code, name_ko, name_en, sort_order, state, created_by) "
                        + "VALUES ('OTHER', 'zz', 'a', 'a', 1, 'ACTIVE', 'user:owner')",
                "INSERT INTO id_counter (name, issued_no) VALUES ('OTHER', 1)",
                "UPDATE defect SET status = 'FIXED' WHERE id = :defect",
                "UPDATE task SET state = 'DONE' WHERE id = :task",
                "UPDATE release_condition SET state = 'PASSED' WHERE id = :condition",
                "INSERT INTO defect_fix (id, defect_id, repo, commit_sha, ci_ref, ci_conclusion, recorded_by, "
                        + "recorded_at, origin) VALUES (900001, :defect, 'r', 'a', 'ref', 'PASSED', 'claude:a', "
                        + "TIMESTAMP '2026-11-05 10:00:00', 'NATIVE')",
                "INSERT INTO defect_fix (id, defect_id, repo, commit_sha, recorded_by, recorded_at, origin) VALUES "
                        + "(900003, :defect, 'r', 'b', 'claude:a', TIMESTAMP '2026-11-05 10:00:00', 'OTHER')",
                "INSERT INTO request_receipt (request_id, operation, payload_sha256, author_id, login, origin, "
                        + "created_at) VALUES ('22222222-2222-2222-2222-222222222222', 'SAVE', 'aa', 'claude:a', "
                        + "'ledger_author', 'OTHER', TIMESTAMP '2026-11-05 10:00:00')",
                "INSERT INTO session_grant (id, author_id, project_id, scope, granted_by, granted_at, active_key) "
                        + "VALUES (900002, 'claude:a', :project, 'ADMIN', 'user:owner', "
                        + "TIMESTAMP '2026-11-05 10:00:00', 'k900002')",
                "INSERT INTO schema_history (version, file_name, sha256, applied_at, outcome) VALUES (99, 'V99__x.sql', "
                        + "'aa', TIMESTAMP '2026-11-05 10:00:00', 'MAYBE')");
    }

    @ParameterizedTest
    @MethodSource("valuesOutsideTheFixedSets")
    void repo003_012_valuesOutsideTheFixedSetsAreRefusedWithSqlStateClass23(String sql) {
        Seed seed = new Seed(ctx);
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "check me", LedgerTestContext.START);
        Task task = seed.task(project, 1, "check task");
        Condition condition = seed.condition(project, project.code().value() + "-C-1", "1.0", "C1");
        Throwable thrown = catchThrowable(() -> jdbc.sql(sql).param("project", project.id().value())
                .param("defect", defect.id().value()).param("task", task.id().value())
                .param("condition", condition.id().value()).update());
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sqlState(thrown)).startsWith("23");
    }

    private static String sqlState(Throwable e) {
        return ((SQLException) NestedExceptionUtils.getMostSpecificCause(e)).getSQLState();
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "task", "defect", "release_condition", "defect_task", "defect_relation",
            "condition_link", "common_code"})
    void repo005_createdByIsNotNull(String table) {
        assertThat(columns()).filteredOn(c -> c.table().equals(table) && c.name().equals("created_by"))
                .singleElement().satisfies(c -> assertThat(c.nullable()).isFalse());
    }

    @Test
    void sch012_schemaHistoryHasExactlyItsColumns() {
        assertThat(columns().stream().filter(c -> c.table().equals("schema_history")).map(Column::name))
                .containsExactlyInAnyOrder("version", "file_name", "sha256", "applied_at", "outcome");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schema_history WHERE outcome = 'SUCCESS'").query(Integer.class)
                .single()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void sch016_seedCodes() {
        assertThat(jdbc.sql("SELECT code FROM common_code WHERE code_group = 'DEFECT_SEVERITY' AND state = 'ACTIVE' "
                + "ORDER BY sort_order").query(String.class).list()).containsExactly("P1", "P2", "P3");
        assertThat(jdbc.sql("SELECT code FROM common_code WHERE code_group = 'DEFECT_SEVERITY' AND state = "
                + "'INACTIVE'").query(String.class).list()).containsExactly("UNRATED");
        assertThat(jdbc.sql("SELECT code FROM common_code WHERE code_group = 'RELATION_KIND' AND state = 'ACTIVE' "
                + "ORDER BY sort_order").query(String.class).list()).containsExactly("same_site", "caused_by",
                "blocks", "same_family", "same_fix_unit", "regression_of");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM common_code WHERE code_group = 'DEFECT_CATEGORY'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT DISTINCT code_group FROM common_code").query(String.class).list())
                .containsExactlyInAnyOrder("DEFECT_SEVERITY", "RELATION_KIND");
    }

    @Test
    void sch017_countersAndRevisionRow() {
        assertThat(jdbc.sql("SELECT name FROM id_counter ORDER BY name").query(String.class).list())
                .containsExactly("DEFECT_CASE", "DEFECT_FIX", "HISTORY", "SESSION_GRANT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM code_revision").query(Integer.class).single()).isEqualTo(1);
        Project fresh = new Seed(ctx).project();
        assertThat(jdbc.sql("SELECT next_defect_no + next_task_no FROM project WHERE id = :id")
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
}
