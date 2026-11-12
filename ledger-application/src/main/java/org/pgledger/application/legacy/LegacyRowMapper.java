package org.pgledger.application.legacy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.pgledger.database.repository.ColumnRule;
import org.pgledger.database.repository.LegacyColumn;
import org.pgledger.database.repository.LegacyRow;
import org.pgledger.database.repository.LegacyTable;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.FixOrigin;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.ReceiptOrigin;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.EntityId;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.Token;
import org.pgledger.domain.value.Tokens;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Turns a row of the legacy schema into the row of the new schema, or says why it cannot be. It is a pure function of the
 * row, the column rules of the new schema and the numbers the legacy ledger used; it touches no database.
 *
 * <p>The rules, which the comparison of the import applies to both sides because it compares the rows this class makes with
 * the rows the new schema holds:
 * <ul>
 * <li>An empty text ({@code ''}) is NULL. The legacy columns allowed {@code ''}; the new schema never holds it
 * (SVC-NORM). Where the new column is nullable (the five long texts of 1.x that allowed {@code ''}: purpose, success
 * condition, behavior, root cause, condition text; REH3-IMP-01) the value becomes NULL and the tools show {@code ""} as 1.x
 * did; where it is NOT NULL (a text that identifies a row) the row is reported (MIG-023) and nothing is written, no value is
 * invented.</li>
 * <li>A time value is cut to microseconds, the precision of the columns of the new schema; the instant is kept and is
 * stored as UTC.</li>
 * <li>The tokens that the new schema writes as constant names (the history entity, the grant scope, the CI conclusion, the
 * receipt operation) are the legacy lower-case tokens in upper case; a token that is none of them is reported.</li>
 * <li>An id that does not have the form of its kind (the id of a task or condition: 1 to 120 characters without whitespace
 * or control character, in any case; the defect id) is reported (MIG-028) and never case-folded; every id of 1.x imports
 * unchanged. The key of a task and a condition ({@code id_key}) is the id in upper case. That two ids of one kind
 * differ only in case is not a property of a row: the import service reports it (REH3-IMP-02).</li>
 * <li>A text longer than its new column is reported, never cut.</li>
 * <li>The derived columns are made by the domain's own rules: the link key of a condition link ({@link ConditionLink#linkKey},
 * the key of a new link), the id key of a task and a condition, the active key of a grant,
 * the origin of a fix ({@code imported} true is IMPORTED), the state of a common code ({@code active} true is ACTIVE),
 * every receipt is LEGACY, the counters of a project are the highest numbers the legacy ledger used, the version of a
 * project is 1, and {@code before_data} and {@code last_used_at} stay NULL.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "ledger.cli", name = "enabled", havingValue = "true")
final class LegacyRowMapper {

    /** The row of the new schema, and everything that is wrong with it (empty when it can be written). */
    record Mapped(LegacyRow row, List<LegacyViolation> violations) {
    }

    private static final Set<String> TASK_STATES = names(TaskState.values());
    private static final Set<String> DEFECT_STATUSES = names(DefectStatus.values());
    private static final Set<String> CONDITION_STATES = names(ConditionState.values());
    private static final Set<String> ASSURANCES = names(ActorAssurance.values());
    private static final Set<String> HISTORY_OPS = names(HistoryOp.values());
    private static final Set<String> RECEIPT_OPERATIONS = names(ReceiptOperation.values());
    private static final Set<String> CODE_GROUPS = names(CodeGroup.values());
    /** How the refusal of an author names the format (the domain has the rule, not a sentence for it). */
    private static final String AUTHOR_FORMAT = "<vendor>:<session-id>";

    private static <E extends Enum<E>> Set<String> names(E[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    /** Collects what is wrong with one row. */
    private static final class Violations {

        private final LegacyTable table;
        private final String key;
        private final List<LegacyViolation> found = new ArrayList<>();

        Violations(LegacyTable table, String key) {
            this.table = table;
            this.key = key;
        }

        void add(String column, ValidationReason reason, String... reasonArgs) {
            found.add(new LegacyViolation(table.targetName(), key, column, reason, List.of(reasonArgs)));
        }

        /** True when the column of this row has been reported already (a value is reported once, for its first reason). */
        boolean reported(String column) {
            return found.stream().anyMatch(violation -> Objects.equals(violation.column(), column));
        }
    }

    /**
     * @param table    the legacy table the row was read from
     * @param source   the row as read
     * @param rules    what the new table accepts, by column (from the database metadata)
     * @param counters the numbers the legacy ledger used (the counters of a project row come from here)
     */
    Mapped map(LegacyTable table, LegacyRow source, Map<String, ColumnRule> rules, ImportCounters counters) {
        Violations violations = new Violations(table, keyOf(table, source));
        LegacyRow s = normalised(source);
        Map<String, Object> t = new LinkedHashMap<>();
        switch (table) {
            case PROJECT -> project(s, t, counters, violations);
            case COMMON_CODE -> commonCode(s, t, violations);
            case TASK -> task(s, t, violations);
            case DEFECT -> defect(s, t, violations);
            case DEFECT_FIX -> defectFix(s, t, violations);
            case DEFECT_CASE -> copy(s, t, "id", "defect_id", "observation", "evidence", "user_quote", "observed_at",
                    "recorded_by", "recorded_at");
            case DEFECT_TASK -> copy(s, t, "defect_id", "task_id", "created_by");
            case DEFECT_RELATION -> defectRelation(s, t, violations);
            case RELEASE_CONDITION -> releaseCondition(s, t, violations);
            case CONDITION_LINK -> conditionLink(s, t, violations);
            case SESSION_GRANT -> sessionGrant(s, t, violations);
            case HISTORY -> history(s, t, violations);
            case REQUEST_RECEIPT -> requestReceipt(s, t, violations);
        }
        Set<String> expected = table.targetColumns().stream().map(LegacyColumn::name).collect(Collectors.toSet());
        if (!Objects.equals(expected, t.keySet())) {
            throw new IllegalStateException("the mapping of " + table + " fills " + t.keySet() + " but the table has "
                    + expected);
        }
        LegacyRow target = new LegacyRow(t);
        checkColumns(table, target, rules, violations);
        return new Mapped(target, List.copyOf(violations.found));
    }

    /** The key of the legacy row, as a refusal names it. */
    private static String keyOf(LegacyTable table, LegacyRow source) {
        return table.sourceKey().stream().map(column -> String.valueOf(source.get(column)))
                .collect(Collectors.joining("|"));
    }

    /** The row with every empty text as NULL and every time cut to microseconds. */
    private static LegacyRow normalised(LegacyRow source) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.values().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text && text.isEmpty()) {
                value = null;
            } else if (value instanceof Instant instant) {
                value = instant.truncatedTo(ChronoUnit.MICROS);
            }
            values.put(entry.getKey(), value);
        }
        return new LegacyRow(values);
    }

    /** NOT NULL and the width of a text column, from what the new schema says (MIG-023, MIG-015). */
    private static void checkColumns(LegacyTable table, LegacyRow target, Map<String, ColumnRule> rules,
            Violations violations) {
        for (LegacyColumn column : table.targetColumns()) {
            ColumnRule rule = rules.get(column.name());
            if (rule == null) {
                throw new IllegalStateException("the new schema has no column " + table.targetName() + "."
                        + column.name());
            }
            Object value = target.get(column.name());
            if (value == null) {
                if (!rule.nullable()) {
                    violations.add(column.name(), ValidationReason.REQUIRED);
                }
            } else if (value instanceof String text && rule.maxChars() > 0 && !violations.reported(column.name())
                    && text.codePointCount(0, text.length()) > rule.maxChars()) {
                violations.add(column.name(), ValidationReason.MUST_BE_AT_MOST_CHARS, String.valueOf(rule.maxChars()));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the tables

    private static void project(LegacyRow s, Map<String, Object> t, ImportCounters counters, Violations v) {
        copy(s, t, "id", "code", "title", "created_by");
        matches(v, "id", s.text("id"), ProjectId.FORMAT, ValidationReason.MUST_MATCH);
        matches(v, "code", s.text("code"), ProjectCode.FORMAT, ValidationReason.MUST_MATCH);
        t.put("next_defect_no", counters.defectNumber(s.text("id")));
        t.put("next_task_no", counters.taskNumber(s.text("id")));
        t.put("version", 1L);
    }

    private static void commonCode(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "code_group", "code", "name_ko", "name_en", "description_ko", "description_en", "sort_order",
                "created_by");
        oneOf(v, "code_group", s.text("code_group"), CODE_GROUPS);
        matches(v, "code", s.text("code"), CodeValue.FORMAT, ValidationReason.MUST_MATCH);
        t.put("state", (s.flag("active") ? CodeState.ACTIVE : CodeState.INACTIVE).name());
    }

    private static void task(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "project_id", "title", "purpose", "success_condition", "state", "owner_actor_id",
                "origin_ref", "origin_quote", "version", "created_at", "updated_at", "created_by");
        entityId(v, "id", s.text("id"));
        t.put("id_key", idKey(s.text("id")));
        oneOf(v, "state", s.text("state"), TASK_STATES);
    }

    private static void defect(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "project_id", "title", "category", "severity", "status", "merged_into", "behavior",
                "root_cause", "owner_actor_id", "version", "created_at", "updated_at", "created_by");
        String id = s.text("id");
        if (id != null && !DefectId.FORMAT.matcher(id).matches()) {
            v.add("id", ValidationReason.DEFECT_ID_FORMAT);
        }
        matches(v, "category", s.text("category"), CodeValue.FORMAT, ValidationReason.MUST_MATCH);
        matches(v, "severity", s.text("severity"), CodeValue.FORMAT, ValidationReason.MUST_MATCH);
        oneOf(v, "status", s.text("status"), DEFECT_STATUSES);
        t.put("case_seq", 0L);
    }

    private static void defectFix(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "defect_id", "repo", "commit_sha", "branch", "ci_ref", "ci_completed_at", "recorded_by",
                "recorded_at");
        t.put("ci_conclusion", nameOf(v, "ci_conclusion", s.text("ci_conclusion"), CiConclusion.values()));
        t.put("origin", (s.flag("imported") ? FixOrigin.IMPORTED : FixOrigin.NATIVE).name());
    }

    private static void defectRelation(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "a", "b", "kind", "created_by");
        matches(v, "kind", s.text("kind"), CodeValue.FORMAT, ValidationReason.MUST_MATCH);
    }

    private static void releaseCondition(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "project_id", "goal_label", "code", "condition_text", "state", "version", "created_by");
        t.put("release_name", s.text("release"));
        entityId(v, "id", s.text("id"));
        t.put("id_key", idKey(s.text("id")));
        oneOf(v, "state", s.text("state"), CONDITION_STATES);
    }

    /** An id of a task or condition that has the form of 1.x (the id of a defect is {@link DefectId}); it is never case-folded. */
    private static void entityId(Violations v, String column, String id) {
        if (id != null && !EntityId.PATTERN.matcher(id).matches()) {
            v.add(column, ValidationReason.MUST_BE_ID, Integer.toString(EntityId.MAX_LENGTH));
        }
    }

    /** The upper-case key of the id (the UNIQUE column that refuses ids which differ only in case), NULL for no id. */
    private static String idKey(String id) {
        return id == null ? null : EntityId.key(id);
    }

    private static void conditionLink(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "condition_id", "task_id", "defect_id", "created_by");
        String condition = s.text("condition_id");
        String task = s.text("task_id");
        String defect = s.text("defect_id");
        if ((task == null) == (defect == null)) {
            v.add("task_id", ValidationReason.MUST_BE_EXACTLY_ONE_OF, "task_id", "defect_id");
        }
        Optional<ConditionId> conditionId = ConditionId.parse(condition);
        if (condition != null && conditionId.isEmpty()) {
            v.add("condition_id", ValidationReason.MUST_BE_ID, Integer.toString(EntityId.MAX_LENGTH));
        }
        // a row without a valid link has no link key: the column is NULL (and the row is reported above or below)
        t.put("link_key", conditionId.flatMap(id -> linkKey(id, task, defect, v)).orElse(null));
    }

    /** The link key of a condition and exactly one of a task and a defect, reporting an id that is none. */
    private static Optional<String> linkKey(ConditionId condition, String task, String defect, Violations v) {
        if (task != null && defect == null) {
            Optional<TaskId> taskId = TaskId.parse(task);
            if (taskId.isEmpty()) {
                v.add("task_id", ValidationReason.MUST_BE_ID, Integer.toString(EntityId.MAX_LENGTH));
            }
            return taskId.map(id -> ConditionLink.toTask(condition, id, AuthorId.OWNER).linkKey());
        }
        if (defect != null && task == null) {
            Optional<DefectId> defectId = DefectId.parse(defect);
            if (defectId.isEmpty()) {
                v.add("defect_id", ValidationReason.DEFECT_ID_FORMAT);
            }
            return defectId.map(id -> ConditionLink.toDefect(condition, id, AuthorId.OWNER).linkKey());
        }
        return Optional.empty();
    }

    private static void sessionGrant(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "author_id", "granted_by", "granted_at", "expires_at", "revoked_at", "note");
        t.put("project_id", s.text("project"));
        t.put("last_used_at", null);
        String scope = nameOf(v, "scope", s.text("scope"), Scope.values());
        // the new schema names the column grant_scope (SCOPE is a reserved word of SQL:2003); the legacy one is scope
        t.put("grant_scope", scope);
        String author = s.text("author_id");
        String project = s.text("project");
        boolean authorValid = AuthorId.matches(author);
        if (author != null && !authorValid) {
            v.add("author_id", ValidationReason.MUST_MATCH, AUTHOR_FORMAT);
        }
        String grantedBy = s.text("granted_by");
        if (grantedBy != null && !AuthorId.matches(grantedBy)) {
            v.add("granted_by", ValidationReason.MUST_MATCH, AUTHOR_FORMAT);
        }
        boolean projectValid = project != null && ProjectId.FORMAT.matcher(project).matches();
        if (project != null && !projectValid) {
            v.add("project_id", ValidationReason.MUST_MATCH, ProjectId.FORMAT.pattern());
        }
        String activeKey = null;
        if (s.instant("revoked_at") != null) {
            activeKey = SessionGrant.revokedKey(s.number("id"));
        } else if (authorValid && projectValid && scope != null) {
            activeKey = SessionGrant.liveKey(new AuthorId(author), new ProjectId(project), Scope.valueOf(scope));
        }
        t.put("active_key", activeKey);
    }

    private static void history(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "author_id", "login", "actor_assurance", "entity_id", "op", "from_state", "to_state",
                "changes", "reason", "evidence_uri", "evidence_sha256", "corrects_id", "request_id", "legacy_ref");
        // the new schema names the column occurred_at (AT is a reserved word of SQL:2003); the legacy one is at
        t.put("occurred_at", s.get("at"));
        t.put("entity", nameOf(v, "entity", s.text("entity"), HistoryEntity.values()));
        t.put("before_data", null);
        oneOf(v, "actor_assurance", s.text("actor_assurance"), ASSURANCES);
        oneOf(v, "op", s.text("op"), HISTORY_OPS);
        String reason = s.text("reason");
        if (reason != null && reason.isBlank()) {
            // the history entry of the domain refuses a blank reason when it is read back
            v.add("reason", ValidationReason.REQUIRED);
        }
    }

    private static void requestReceipt(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "request_id", "payload_sha256", "author_id", "login", "response", "created_at");
        String operation = s.text("operation") == null ? null : s.text("operation").toUpperCase(Locale.ROOT);
        oneOf(v, "operation", operation, RECEIPT_OPERATIONS);
        t.put("operation", operation);
        t.put("origin", ReceiptOrigin.LEGACY.name());
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private static void copy(LegacyRow from, Map<String, Object> to, String... columns) {
        for (String column : columns) {
            to.put(column, from.get(column));
        }
    }

    private static void matches(Violations v, String column, String value, Pattern pattern, ValidationReason reason) {
        if (value != null && !pattern.matcher(value).matches()) {
            v.add(column, reason, pattern.pattern());
        }
    }

    private static void oneOf(Violations v, String column, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            v.add(column, ValidationReason.UNKNOWN_VALUE, value);
        }
    }

    /** The constant name of the token (the legacy lower-case tokens are the tokens of the domain enums), or null. */
    private static <E extends Enum<E> & Token> String nameOf(Violations v, String column, String raw, E[] values) {
        if (raw == null) {
            return null;
        }
        Optional<String> name = Tokens.find(raw, values).map(Enum::name);
        if (name.isEmpty()) {
            v.add(column, ValidationReason.UNKNOWN_VALUE, raw);
        }
        // the column is NULL for a token that is none of the constants (the row is reported)
        return name.orElse(null);
    }
}
