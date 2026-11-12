package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.ColumnRule;
import org.pgledger.database.repository.LegacyColumn;
import org.pgledger.database.repository.LegacyTable;
import org.pgledger.database.repository.LegacyTargetRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.SqlText;
import org.pgledger.database.support.SqlText.Group;
import org.pgledger.testing.legacy.LegacyLedger;

/**
 * MIG-024 (the table shapes of the fixture against the catalogue of the import) and the catalogue of the legacy import
 * against the real schema files: every column the import writes is a column of the migrated schema and no column of the
 * schema is forgotten; the legacy side of the catalogue is the shape the fixture builds; every statement the catalogue
 * builds is ANSI SQL (the checks of ANSI-001 .. ANSI-003 and ANSI-014).
 */
@DatabaseTest
class LegacyTableTest {

    private static List<String> names(List<LegacyColumn> columns) {
        return columns.stream().map(LegacyColumn::name).toList();
    }

    @Test
    @DisplayName("MIG-024 the new side of the catalogue lists exactly the columns of the tables of the migrated schema, and the database metadata gives NOT NULL and the width of the columns the import checks")
    void theTargetColumnsAreTheColumnsOfTheSchema(LedgerTestContext ctx) {
        LegacyTargetRepository target = ctx.bean(LegacyTargetRepository.class);

        for (LegacyTable table : LegacyTable.values()) {
            Map<String, ColumnRule> rules = target.columnRules(table);
            assertThat(rules.keySet()).as(table.name()).containsExactlyInAnyOrderElementsOf(names(table.targetColumns()));
            assertThat(table.targetKey()).as(table.name() + " key").isSubsetOf(names(table.targetColumns()));
            assertThat(table.sourceKey()).as(table.name() + " source key").isSubsetOf(names(table.sourceColumns()));
        }
        Map<String, ColumnRule> task = target.columnRules(LegacyTable.TASK);
        // REH3: purpose, success_condition, behavior, root_cause and condition_text allowed '' in 1.x, so they are nullable; the
        // columns that identify a row stay NOT NULL, and so does the key of the id
        assertThat(task.get("purpose").nullable()).isTrue();
        assertThat(task.get("success_condition").nullable()).isTrue();
        assertThat(task.get("title").nullable()).isFalse();
        assertThat(task.get("id_key").nullable()).isFalse();
        assertThat(task.get("id_key").maxChars()).isEqualTo(360);
        assertThat(task.get("id").maxChars()).isEqualTo(120);
        assertThat(target.columnRules(LegacyTable.RELEASE_CONDITION).get("code").maxChars()).isEqualTo(200);
        assertThat(target.columnRules(LegacyTable.RELEASE_CONDITION).get("release_name").nullable()).isFalse();
        assertThat(target.columnRules(LegacyTable.DEFECT_FIX).get("ci_ref").maxChars()).isEqualTo(2000);
        assertThat(task.get("owner_actor_id").nullable()).isTrue();
        assertThat(task.get("title").maxChars()).isEqualTo(300);
        // REH3-LEN-02: origin_ref is a long text (text without a limit in 1.x; 9408 characters in production)
        assertThat(task.get("origin_ref").maxChars()).isIn(0, Integer.MAX_VALUE);
        // a long text column has no limit the import checks: the metadata of PostgreSQL states it as the largest int
        assertThat(target.columnRules(LegacyTable.HISTORY).get("changes").maxChars()).isIn(0, Integer.MAX_VALUE);
        assertThat(target.columnRules(LegacyTable.COMMON_CODE).get("description_ko").nullable()).isTrue();
    }

    @Test
    @DisplayName("MIG-024 the legacy side of the catalogue lists exactly the columns of the tables of the legacy schema that the fixture builds, in the order of the select list, and the statements run")
    void theSourceColumnsAreTheColumnsOfTheLegacySchema() {
        try (LegacyLedger legacy = LegacyLedger.create()) {
            for (LegacyTable table : LegacyTable.values()) {
                List<String> columns = legacy.rows("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ?", legacy.schema(), table.sourceName())
                        .stream().map(row -> String.valueOf(row.get("column_name")).toLowerCase(Locale.ROOT)).toList();
                assertThat(columns).as(table.name()).containsExactlyInAnyOrderElementsOf(names(table.sourceColumns()));
                assertThat(legacy.rows(table.sourceSelect(legacy.schema()))).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("MIG-024 the tables are listed parents first: every foreign key of the new schema points to a table that comes earlier in the catalogue")
    void parentsComeFirst() {
        List<String> order = List.of(LegacyTable.values()).stream().map(LegacyTable::targetName).toList();
        Map<String, Set<String>> parents = Map.of(
                "task", Set.of("project"), "defect", Set.of("project", "defect"),
                "defect_fix", Set.of("defect"), "defect_case", Set.of("defect"),
                "defect_task", Set.of("defect", "task"), "defect_relation", Set.of("defect"),
                "release_condition", Set.of("project"),
                "condition_link", Set.of("release_condition", "task", "defect"),
                "session_grant", Set.of("project"), "history", Set.of("history"));

        for (Map.Entry<String, Set<String>> entry : parents.entrySet()) {
            for (String parent : entry.getValue()) {
                if (!Objects.equals(parent, entry.getKey())) {
                    assertThat(order.indexOf(parent)).as(entry.getKey() + " after " + parent).isLessThan(order.indexOf(entry.getKey()));
                }
            }
        }
        assertThat(order).hasSize(13).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("MIG-024 / ANSI-001..003, ANSI-014 every statement the catalogue builds is plain ANSI SQL: a select list, a table, an ORDER BY, an INSERT with one placeholder per column; no vendor syntax, no session statement, no time function")
    void theStatementsAreAnsi() {
        for (LegacyTable table : LegacyTable.values()) {
            for (String sql : List.of(table.sourceSelect(LegacyLedger.SCHEMA), table.targetSelect(), table.targetInsert())) {
                assertThat(new SqlText(table.name(), sql).findings(Set.of(Group.values()))).as(sql).isEmpty();
            }
            assertThat(table.targetInsert().chars().filter(c -> c == '?').count()).isEqualTo(table.targetColumns().size());
            assertThat(table.sourceSelect(LegacyLedger.SCHEMA)).startsWith("SELECT ").contains(" FROM ledger.")
                    .contains(" ORDER BY ").doesNotContain("::");
        }
    }
}
