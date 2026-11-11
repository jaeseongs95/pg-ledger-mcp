package org.pgledger.database.repository;

import static org.pgledger.database.repository.LegacyColumn.flag;
import static org.pgledger.database.repository.LegacyColumn.instant;
import static org.pgledger.database.repository.LegacyColumn.number;
import static org.pgledger.database.repository.LegacyColumn.text;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The tables of the legacy import, in the order in which they are copied (a table comes after the tables its foreign keys
 * point to): for each the table of the legacy schema {@code ledger} that is read, the table of the new schema that is
 * written, the columns on both sides and the key. The legacy side is the schema of {@code sql/10_tables.sql} with the
 * later migrations {@code 15_defect_case.sql}, {@code 16_common_code.sql} and {@code 17_created_by.sql} applied. The
 * statements that read and write the tables are built here from the column lists and are plain ANSI SQL: a select list, a
 * table, an {@code ORDER BY}, an {@code INSERT} with one placeholder per column. The schema of the legacy side is an
 * identifier and so cannot be a bind value; the caller passes a validated name.
 *
 * <p>The tables that are not copied: {@code _migration} (bookkeeping of the legacy installer, read only to check its
 * steps), {@code common_code_use} (the legacy common-code bookkeeping) and the view {@code category} (the categories are
 * the rows of {@code common_code}). {@code session_key} and {@code owner_audit} are new in this schema; the keys are
 * registered by the owner after the import and the import writes its own audit row.
 */
public enum LegacyTable {

    PROJECT("project", "project", List.of("id"), List.of("id"),
            List.of(text("id"), text("code"), text("title"), text("created_by")),
            List.of(text("id"), text("code"), text("title"), number("next_defect_no"), number("next_task_no"),
                    number("version"), text("created_by")), false),

    COMMON_CODE("common_code", "common_code", List.of("code_group", "code"), List.of("code_group", "code"),
            List.of(text("code_group"), text("code"), text("name_ko"), text("name_en"), text("description_ko"),
                    text("description_en"), number("sort_order"), flag("active"), text("created_by")),
            List.of(text("code_group"), text("code"), text("name_ko"), text("name_en"), text("description_ko"),
                    text("description_en"), number("sort_order"), text("state"), text("created_by")), true),

    TASK("task", "task", List.of("id"), List.of("id"),
            List.of(text("id"), text("project_id"), text("title"), text("purpose"), text("success_condition"),
                    text("state"), text("owner_actor_id"), text("origin_ref"), text("origin_quote"), number("version"),
                    instant("created_at"), instant("updated_at"), text("created_by")),
            List.of(text("id"), text("project_id"), text("title"), text("purpose"), text("success_condition"),
                    text("state"), text("owner_actor_id"), text("origin_ref"), text("origin_quote"), number("version"),
                    instant("created_at"), instant("updated_at"), text("created_by")), false),

    DEFECT("defect", "defect", List.of("id"), List.of("id"),
            List.of(text("id"), text("project_id"), text("title"), text("category"), text("severity"), text("status"),
                    text("merged_into"), text("behavior"), text("root_cause"), text("owner_actor_id"), number("version"),
                    instant("created_at"), instant("updated_at"), text("created_by")),
            List.of(text("id"), text("project_id"), text("title"), text("category"), text("severity"), text("status"),
                    text("merged_into"), text("behavior"), text("root_cause"), text("owner_actor_id"), number("case_seq"),
                    number("version"), instant("created_at"), instant("updated_at"), text("created_by")), false),

    DEFECT_FIX("defect_fix", "defect_fix", List.of("id"), List.of("id"),
            List.of(number("id"), text("defect_id"), text("repo"), text("commit_sha"), text("branch"), text("ci_ref"),
                    text("ci_conclusion"), instant("ci_completed_at"), text("recorded_by"), instant("recorded_at"),
                    flag("imported")),
            List.of(number("id"), text("defect_id"), text("repo"), text("commit_sha"), text("branch"), text("ci_ref"),
                    text("ci_conclusion"), instant("ci_completed_at"), text("recorded_by"), instant("recorded_at"),
                    text("origin")), false),

    DEFECT_CASE("defect_case", "defect_case", List.of("id"), List.of("id"),
            List.of(number("id"), text("defect_id"), text("observation"), text("evidence"), text("user_quote"),
                    instant("observed_at"), text("recorded_by"), instant("recorded_at")),
            List.of(number("id"), text("defect_id"), text("observation"), text("evidence"), text("user_quote"),
                    instant("observed_at"), text("recorded_by"), instant("recorded_at")), false),

    DEFECT_TASK("defect_task", "defect_task", List.of("defect_id", "task_id"), List.of("defect_id", "task_id"),
            List.of(text("defect_id"), text("task_id"), text("created_by")),
            List.of(text("defect_id"), text("task_id"), text("created_by")), false),

    DEFECT_RELATION("defect_relation", "defect_relation", List.of("a", "b", "kind"), List.of("a", "b", "kind"),
            List.of(text("a"), text("b"), text("kind"), text("created_by")),
            List.of(text("a"), text("b"), text("kind"), text("created_by")), false),

    /** The legacy table is named {@code condition} and its release column {@code release}; both are reserved words. */
    RELEASE_CONDITION("condition", "release_condition", List.of("id"), List.of("id"),
            List.of(text("id"), text("project_id"), text("release"), text("goal_label"), text("code"),
                    text("condition_text"), text("state"), number("version"), text("created_by")),
            List.of(text("id"), text("project_id"), text("release_name"), text("goal_label"), text("code"),
                    text("condition_text"), text("state"), number("version"), text("created_by")), false),

    /** The legacy table has no key column: the new schema's {@code link_key} is made from the three id columns. */
    CONDITION_LINK("condition_link", "condition_link", List.of("condition_id"), List.of("link_key"),
            List.of(text("condition_id"), text("task_id"), text("defect_id"), text("created_by")),
            List.of(text("link_key"), text("condition_id"), text("task_id"), text("defect_id"), text("created_by")), false),

    SESSION_GRANT("session_grant", "session_grant", List.of("id"), List.of("id"),
            List.of(number("id"), text("author_id"), text("project"), text("scope"), text("granted_by"),
                    instant("granted_at"), instant("expires_at"), instant("revoked_at"), text("note")),
            List.of(number("id"), text("author_id"), text("project_id"), text("scope"), text("granted_by"),
                    instant("granted_at"), instant("expires_at"), instant("revoked_at"), instant("last_used_at"),
                    text("note"), text("active_key")), false),

    HISTORY("history", "history", List.of("id"), List.of("id"),
            List.of(number("id"), instant("at"), text("author_id"), text("login"), text("actor_assurance"),
                    text("entity"), text("entity_id"), text("op"), text("from_state"), text("to_state"), text("changes"),
                    text("reason"), text("evidence_uri"), text("evidence_sha256"), number("corrects_id"),
                    text("request_id"), text("legacy_ref")),
            List.of(number("id"), instant("at"), text("author_id"), text("login"), text("actor_assurance"),
                    text("entity"), text("entity_id"), text("op"), text("from_state"), text("to_state"), text("changes"),
                    text("before_data"), text("reason"), text("evidence_uri"), text("evidence_sha256"),
                    number("corrects_id"), text("request_id"), text("legacy_ref")), false),

    REQUEST_RECEIPT("request_receipt", "request_receipt", List.of("request_id"), List.of("request_id"),
            List.of(text("request_id"), text("operation"), text("payload_sha256"), text("author_id"), text("login"),
                    text("response"), instant("created_at")),
            List.of(text("request_id"), text("operation"), text("payload_sha256"), text("author_id"), text("login"),
                    text("response"), text("origin"), instant("created_at")), false);

    private final String sourceName;
    private final String targetName;
    private final List<String> sourceKey;
    private final List<String> targetKey;
    private final List<LegacyColumn> sourceColumns;
    private final List<LegacyColumn> targetColumns;
    private final boolean targetMayHoldOtherRows;

    LegacyTable(String sourceName, String targetName, List<String> sourceKey, List<String> targetKey,
            List<LegacyColumn> sourceColumns, List<LegacyColumn> targetColumns, boolean targetMayHoldOtherRows) {
        this.sourceName = sourceName;
        this.targetName = targetName;
        this.sourceKey = sourceKey;
        this.targetKey = targetKey;
        this.sourceColumns = sourceColumns;
        this.targetColumns = targetColumns;
        this.targetMayHoldOtherRows = targetMayHoldOtherRows;
    }

    /** The table of the legacy schema. */
    public String sourceName() {
        return sourceName;
    }

    /** The table of the new schema. */
    public String targetName() {
        return targetName;
    }

    /** The key columns of the legacy table (the order of the rows read, and what names a row in a refusal). */
    public List<String> sourceKey() {
        return sourceKey;
    }

    /** The key columns of the new table (what orders the rows of a digest). */
    public List<String> targetKey() {
        return targetKey;
    }

    public List<LegacyColumn> sourceColumns() {
        return sourceColumns;
    }

    public List<LegacyColumn> targetColumns() {
        return targetColumns;
    }

    /**
     * True when the new table already holds rows of a fresh installation that are not copied from the legacy table (the
     * common codes the schema seeds): the copy then updates them, and the comparison looks at the copied keys only.
     */
    public boolean targetMayHoldOtherRows() {
        return targetMayHoldOtherRows;
    }

    /** The statement that reads the legacy table in key order; {@code schema} is a validated identifier. */
    public String sourceSelect(String schema) {
        return "SELECT " + names(sourceColumns) + " FROM " + schema + "." + sourceName + " ORDER BY "
                + String.join(", ", sourceKey);
    }

    /** The statement that reads the new table (no order: the digest orders the rows itself). */
    public String targetSelect() {
        return "SELECT " + names(targetColumns) + " FROM " + targetName;
    }

    /** The statement that inserts one row of the new table, one positional placeholder per column. */
    public String targetInsert() {
        return "INSERT INTO " + targetName + " (" + names(targetColumns) + ") VALUES ("
                + targetColumns.stream().map(column -> "?").collect(Collectors.joining(", ")) + ")";
    }

    private static String names(List<LegacyColumn> columns) {
        return columns.stream().map(LegacyColumn::name).collect(Collectors.joining(", "));
    }
}
