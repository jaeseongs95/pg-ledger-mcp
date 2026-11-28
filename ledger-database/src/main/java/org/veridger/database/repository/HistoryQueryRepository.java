// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.veridger.database.time.UtcTime;
import org.veridger.domain.rule.ActorAssurance;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * History lists. The ids come in order from a keyset query ({@code id > ?}, ascending); the rows are read through
 * {@link HistoryRepository}. A history row has no project column: the project of a row is the project of the entity
 * it is about (a fix, case or link row belongs to the defect or condition its id starts with).
 */
@Repository
public class HistoryQueryRepository {

    /** Optional criteria, all combined with AND; null (or an empty id list) means no limit. */
    public record Filter(List<Long> ids, Long idFrom, Long idTo, HistoryEntity entity, String entityId,
            ProjectId project, HistoryOp op, String author, Instant atFrom, Instant atTo) {

        public static final Filter NONE = new Filter(List.of(), null, null, null, null, null, null, null, null,
                null);

        public Filter {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }

    /** Rows whose entity id starts with the id of the entity {@code alias}, then a separator (the id of a case or link row). */
    private static String childOf(String alias) {
        return "(SUBSTRING(h.entity_id FROM 1 FOR CHAR_LENGTH(" + alias + ".id)) = " + alias + ".id "
                + "AND SUBSTRING(h.entity_id FROM CHAR_LENGTH(" + alias + ".id) + 1 FOR 1) IN ('@', '|'))";
    }

    /**
     * The history row belongs to an entity of {@code table} in the project, and to no other entity of the table that has the
     * longer id. The id of a spec, task or criterion is free text (it may hold {@code |} and {@code @}), so the id of one entity
     * can continue the id of another and the row of a link or deliverable of the longer one also starts with the shorter one:
     * the row is the one of the entity whose id is the longest that it starts with.
     *
     * @param owns the condition that the row of the entity named by the alias given belongs to it
     */
    private static String ownedInProject(String table, UnaryOperator<String> owns) {
        return ownedBy(table, "p.project_id = :project", owns);
    }

    private static String ownedBy(String table, String scope, UnaryOperator<String> owns) {
        return "EXISTS (SELECT 1 FROM " + table + " p WHERE " + scope + " AND " + owns.apply("p")
                + ("issue".equals(table) ? "" : " AND NOT EXISTS (SELECT 1 FROM " + table
                        + " q WHERE CHAR_LENGTH(q.id) > CHAR_LENGTH(p.id) AND " + owns.apply("q") + ")") + ")";
    }

    /**
     * Issue IDs have fixed {@code <CODE>-<TYPE>-<NUM>} syntax and never contain {@code @} or {@code |}, so an issue ID
     * cannot be a proper prefix of another issue ID followed by a separator; no nested longest-prefix anti-join is needed.
     */
    private static String ownedIssueInProject(UnaryOperator<String> owns) {
        return "EXISTS (SELECT 1 FROM issue p WHERE p.project_id = :project AND " + owns.apply("p") + ")";
    }

    /** Prefixed deliverable condition gated on the literal prefix before evaluating the table EXISTS subquery. */
    private static String deliverableOf(String prefixLiteral, String existsPredicate) {
        int length = prefixLiteral.length() - 2;
        return "(SUBSTRING(h.entity_id FROM 1 FOR " + length + ") = " + prefixLiteral + " AND " + existsPredicate + ")";
    }

    private static String deliverableMatch(String alias, String prefixLiteral) {
        int length = prefixLiteral.length() - 2;
        int from = length + 1;
        return "(SUBSTRING(h.entity_id FROM " + from + " FOR CHAR_LENGTH(" + alias + ".id)) = " + alias + ".id "
                + "AND SUBSTRING(h.entity_id FROM " + from + " + CHAR_LENGTH(" + alias + ".id) FOR 1) = '@')";
    }

    private static String childOrDeliverableOf(String alias, String prefixLiteral) {
        int length = prefixLiteral.length() - 2;
        return "(" + childOf(alias) + " OR (SUBSTRING(h.entity_id FROM 1 FOR " + length + ") = " + prefixLiteral
                + " AND " + deliverableMatch(alias, prefixLiteral) + "))";
    }

    private static final String ISSUE_DIRECT = "h.entity_id IN (SELECT i.id FROM issue i WHERE i.project_id = :project)";
    private static final String SPEC_DIRECT = "h.entity_id IN (SELECT s.id FROM spec s WHERE s.project_id = :project)";
    private static final String TASK_DIRECT = "h.entity_id IN (SELECT t.id FROM task t WHERE t.project_id = :project)";
    private static final String CRITERION_DIRECT = "h.entity_id IN (SELECT c.id FROM milestone_criterion c "
            + "WHERE c.project_id = :project)";
    private static final String ISSUE_CHILD = ownedIssueInProject(HistoryQueryRepository::childOf);
    private static final String SPEC_CHILD = ownedInProject("spec", HistoryQueryRepository::childOf);
    private static final String CRITERION_CHILD = ownedInProject("milestone_criterion", HistoryQueryRepository::childOf);
    private static final String DELIVERABLE_IN_PROJECT = "("
            + deliverableOf("'issue:'", ownedIssueInProject(alias -> deliverableMatch(alias, "'issue:'"))) + " "
            + "OR " + ownedIssueInProject(HistoryQueryRepository::childOf) + " "
            + "OR " + ownedInProject("spec", alias -> childOrDeliverableOf(alias, "'spec:'")) + " "
            + "OR " + ownedInProject("task", alias -> childOrDeliverableOf(alias, "'task:'")) + ")";

    private static final String IN_PROJECT = "("
            + "(h.entity = 'ISSUE' AND " + ISSUE_DIRECT + ") "
            + "OR (h.entity = 'SPEC' AND " + SPEC_DIRECT + ") "
            + "OR (h.entity = 'TASK' AND " + TASK_DIRECT + ") "
            + "OR (h.entity = 'CRITERION' AND " + CRITERION_DIRECT + ") "
            + "OR (h.entity IN ('ISSUE_CASE', 'ISSUE_TASK', 'ISSUE_RELATION', 'ISSUE_SPEC') AND " + ISSUE_CHILD + ") "
            + "OR (h.entity = 'SPEC_TASK' AND " + SPEC_CHILD + ") "
            + "OR (h.entity = 'DELIVERABLE' AND " + DELIVERABLE_IN_PROJECT + ") "
            + "OR (h.entity = 'CRITERION_LINK' AND " + CRITERION_CHILD + "))";

    private static String linkedChild(String table, UnaryOperator<String> owns) {
        return ownedBy(table, "p.project_id = ref.project_id AND p.id = ref.item_id", owns);
    }

    private static final String ISSUE_VERSION = "(ref.item_kind = 'issue' AND ("
            + "(h.entity = 'ISSUE' AND h.entity_id = ref.item_id) OR "
            + "(h.entity IN ('ISSUE_CASE', 'ISSUE_TASK', 'ISSUE_RELATION', 'ISSUE_SPEC') AND "
            + linkedChild("issue", HistoryQueryRepository::childOf) + ") OR (h.entity = 'DELIVERABLE' AND ("
            + deliverableOf("'issue:'", linkedChild("issue", alias -> deliverableMatch(alias, "'issue:'")))
            + " OR " + linkedChild("issue", HistoryQueryRepository::childOf) + "))))";
    private static final String SPEC_VERSION = "(ref.item_kind = 'spec' AND ("
            + "(h.entity = 'SPEC' AND h.entity_id = ref.item_id) OR (h.entity = 'SPEC_TASK' AND "
            + linkedChild("spec", HistoryQueryRepository::childOf) + ") OR (h.entity = 'DELIVERABLE' AND "
            + linkedChild("spec", alias -> childOrDeliverableOf(alias, "'spec:'")) + ")))";
    private static final String TASK_VERSION = "(ref.item_kind = 'task' AND ("
            + "(h.entity = 'TASK' AND h.entity_id = ref.item_id) OR (h.entity = 'DELIVERABLE' AND "
            + linkedChild("task", alias -> childOrDeliverableOf(alias, "'task:'")) + ")))";
    private static final String CRITERION_VERSION = "(ref.item_kind = 'criterion' AND ("
            + "(h.entity = 'CRITERION' AND h.entity_id = ref.item_id) OR (h.entity = 'CRITERION_LINK' AND "
            + linkedChild("milestone_criterion", HistoryQueryRepository::childOf) + ")))";
    private static final String VERSION_OWNER = "(" + String.join(" OR ", ISSUE_VERSION, SPEC_VERSION,
            TASK_VERSION, CRITERION_VERSION) + ")";

    private static final String PROJECT_VERSIONS = "SELECT DISTINCT h.id, pv.name FROM history h "
            + "JOIN project_version_item ref ON " + VERSION_OWNER
            + " JOIN project_version pv ON pv.project_id = ref.project_id AND pv.id = ref.version_id"
            + " WHERE h.id IN (:ids) ORDER BY h.id, pv.name";

    /** Current versions of a page of history targets, including the owners of related records. */
    public Map<Long, List<String>> projectVersions(List<Long> historyIds) {
        if (historyIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(PROJECT_VERSIONS).param("ids", historyIds)
                .query((rs, row) -> Map.entry(rs.getLong(1), rs.getString(2))).list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue,
                        Collectors.toList())));
    }

    /** Narrows the project ownership predicate to the single requested entity branch when {@code entity} is fixed. */
    private static String inProjectFor(HistoryEntity entity) {
        if (entity == null) {
            return IN_PROJECT;
        }
        return switch (entity) {
            case ISSUE -> ISSUE_DIRECT;
            case SPEC -> SPEC_DIRECT;
            case TASK -> TASK_DIRECT;
            case CRITERION -> CRITERION_DIRECT;
            case ISSUE_CASE, ISSUE_TASK, ISSUE_RELATION, ISSUE_SPEC -> ISSUE_CHILD;
            case SPEC_TASK -> SPEC_CHILD;
            case DELIVERABLE -> DELIVERABLE_IN_PROJECT;
            case CRITERION_LINK -> CRITERION_CHILD;
        };
    }

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public HistoryQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    /** History ids of one entity in id order, after {@code afterId} (use 0 for the first page). */
    public KeysetPage<Long> idsOfEntity(HistoryEntity entity, String entityId, long afterId, MaxRows maxRows) {
        return KeysetPage.of(jdbc.sql(sqlBuilder.applyLimit("SELECT id FROM history WHERE entity = :entity "
                + "AND entity_id = :entityId AND id > :after ORDER BY id", ":row_count"))
                .param("entity", entity.name())
                .param("entityId", entityId)
                .param("after", afterId)
                .param("row_count", maxRows.value() + 1)
                .query(Long.class).list(), maxRows.value());
    }

    /** The ids matching the filter in id order, after {@code afterId} (use 0 for the first page). */
    public KeysetPage<Long> page(Filter filter, long afterId, MaxRows maxRows) {
        var page = positions(filter, maxRows, DateQuery.NONE, new Position(afterId, null));
        return new KeysetPage<>(page.rows().stream().map(Position::id).toList(), page.truncated());
    }

    public record Position(long id, Instant at) {
    }

    /** Ordered row keys. The service loads the full history rows only after applying the page limit. */
    public KeysetPage<Position> positions(Filter filter, MaxRows maxRows, DateQuery dates, Position after) {
        return positions(filter, maxRows, dates, after, null);
    }

    public KeysetPage<Position> positions(Filter filter, MaxRows maxRows, DateQuery dates, Position after,
            String projectVersionId) {
        StringBuilder sql = new StringBuilder("SELECT h.id, h.occurred_at ");
        appendMatching(sql, filter, dates, projectVersionId);
        if (dates.sort() != null) {
            if (after != null) {
                dates.appendSeek(sql, "h.id", after.at(), false);
            }
            dates.appendOrder(sql, "h.id", false);
        } else {
            if (after != null) {
                sql.append(" AND h.id > :after");
            }
            sql.append(" ORDER BY h.id");
        }
        JdbcClient.StatementSpec spec = bindMatching(jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("row_count", maxRows.value() + 1), filter, dates, projectVersionId);
        if (after != null) {
            spec = dates.sort() == null ? spec.param("after", after.id())
                    : dates.bindSeek(spec, after.at(), after.id());
        }
        return KeysetPage.of(spec.query((rs, n) -> new Position(rs.getLong("id"),
                UtcTime.read(rs, "occurred_at"))).list(), maxRows.value());
    }

    public long count(Filter filter, DateQuery dates, String projectVersionId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) ");
        appendMatching(sql, filter, dates, projectVersionId);
        return bindMatching(jdbc.sql(sql.toString()), filter, dates, projectVersionId)
                .query(Long.class).single();
    }

    private static void appendMatching(StringBuilder sql, Filter filter, DateQuery dates, String projectVersionId) {
        sql.append("FROM history h WHERE h.id > 0");
        if (!filter.ids().isEmpty()) {
            sql.append(" AND h.id IN (:ids)");
        }
        if (filter.idFrom() != null) {
            sql.append(" AND h.id >= :idFrom");
        }
        if (filter.idTo() != null) {
            sql.append(" AND h.id <= :idTo");
        }
        if (filter.entity() != null) {
            sql.append(" AND h.entity = :entity");
        }
        if (filter.entityId() != null) {
            sql.append(" AND h.entity_id = :entityId");
        }
        if (projectVersionId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM project_version_item ref WHERE ref.project_id = :project "
                    + "AND ref.version_id = :projectVersionId AND ").append(VERSION_OWNER).append(")");
        } else if (filter.project() != null) {
            sql.append(" AND ").append(inProjectFor(filter.entity()));
        }
        if (filter.op() != null) {
            sql.append(" AND h.op = :op");
        }
        if (filter.author() != null) {
            sql.append(" AND h.author_id = :author");
        }
        if (filter.atFrom() != null) {
            sql.append(" AND h.occurred_at >= :atFrom");
        }
        if (filter.atTo() != null) {
            sql.append(" AND h.occurred_at <= :atTo");
        }
        dates.appendRange(sql);
    }

    private static JdbcClient.StatementSpec bindMatching(JdbcClient.StatementSpec spec, Filter filter, DateQuery dates,
            String projectVersionId) {
        if (!filter.ids().isEmpty()) {
            spec = spec.param("ids", filter.ids());
        }
        if (filter.idFrom() != null) {
            spec = spec.param("idFrom", filter.idFrom());
        }
        if (filter.idTo() != null) {
            spec = spec.param("idTo", filter.idTo());
        }
        if (filter.entity() != null) {
            spec = spec.param("entity", filter.entity().name());
        }
        if (filter.entityId() != null) {
            spec = spec.param("entityId", filter.entityId());
        }
        if (filter.project() != null) {
            spec = spec.param("project", filter.project().value());
        }
        spec = ProjectVersionRepository.bindItemFilter(spec, projectVersionId);
        if (filter.op() != null) {
            spec = spec.param("op", filter.op().name());
        }
        if (filter.author() != null) {
            spec = spec.param("author", filter.author());
        }
        if (filter.atFrom() != null) {
            spec = spec.param("atFrom", UtcTime.toDb(filter.atFrom()));
        }
        if (filter.atTo() != null) {
            spec = spec.param("atTo", UtcTime.toDb(filter.atTo()));
        }
        return dates.bindRange(spec);
    }

    /** Lightweight transition projection (omitting {@code before_data} and {@code changes} blobs), oldest first. */
    public record TransitionRow(String fromState, String toState, ActorId authorId) {
    }

    /** Lightweight history metadata projection (omitting {@code before_data} and {@code changes} blobs). */
    public record SummaryRow(long id, Instant at, ActorId authorId, String login, ActorAssurance actorAssurance,
            HistoryOp op, String reason) {
    }

    /**
     * The newest history ids of one entity, newest first, without the rows imported from another ledger when
     * {@code withoutImport} is set; at most {@code count} ids.
     */
    public List<Long> newestOfEntity(HistoryEntity entity, String entityId, boolean withoutImport, int count) {
        return jdbc.sql(sqlBuilder.applyLimit("SELECT id FROM history WHERE entity = :entity AND entity_id = :entityId"
                + (withoutImport ? " AND op <> 'IMPORT'" : "") + " ORDER BY occurred_at DESC, id DESC", ":row_count"))
                .param("entity", entity.name())
                .param("entityId", entityId)
                .param("row_count", count)
                .query(Long.class).list();
    }

    /**
     * Transition metadata of one entity in chronological id order without reading {@code before_data} or
     * {@code changes} JSON payloads into heap.
     */
    public List<TransitionRow> transitionsOfEntity(HistoryEntity entity, String entityId) {
        return jdbc.sql("SELECT from_state, to_state, author_id FROM history "
                + "WHERE entity = :entity AND entity_id = :entityId AND op = 'TRANSITION' ORDER BY id")
                .param("entity", entity.name())
                .param("entityId", entityId)
                .query((rs, n) -> new TransitionRow(rs.getString("from_state"), rs.getString("to_state"),
                        ActorId.ofStored(rs.getString("author_id"))))
                .list();
    }

    /**
     * The newest history metadata summaries of one entity, newest first, without reading {@code before_data} or
     * {@code changes} JSON payloads into heap.
     */
    public List<SummaryRow> newestSummariesOfEntity(HistoryEntity entity, String entityId, boolean withoutImport,
            int count) {
        return jdbc.sql(sqlBuilder.applyLimit("SELECT id, occurred_at, author_id, login, actor_assurance, op, reason "
                + "FROM history WHERE entity = :entity AND entity_id = :entityId"
                + (withoutImport ? " AND op <> 'IMPORT'" : "") + " ORDER BY occurred_at DESC, id DESC", ":row_count"))
                .param("entity", entity.name())
                .param("entityId", entityId)
                .param("row_count", count)
                .query((rs, n) -> new SummaryRow(rs.getLong("id"), UtcTime.read(rs, "occurred_at"),
                        ActorId.ofStored(rs.getString("author_id")), rs.getString("login"),
                        ActorAssurance.valueOf(rs.getString("actor_assurance")),
                        HistoryOp.valueOf(rs.getString("op")), rs.getString("reason")))
                .list();
    }
}
