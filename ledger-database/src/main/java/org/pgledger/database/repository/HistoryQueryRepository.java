package org.pgledger.database.repository;

import java.time.Instant;
import java.util.List;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.ProjectId;
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

    /** Rows whose entity id starts with the id of a parent row, then a separator (the id of a fix or case row). */
    private static final String CHILD_OF = "SUBSTRING(h.entity_id FROM 1 FOR CHAR_LENGTH(p.id)) = p.id "
            + "AND SUBSTRING(h.entity_id FROM CHAR_LENGTH(p.id) + 1 FOR 1) IN ('@', '|')";

    private static final String IN_PROJECT = "("
            + "(h.entity = 'DEFECT' AND h.entity_id IN (SELECT d.id FROM defect d WHERE d.project_id = :project)) "
            + "OR (h.entity = 'TASK' AND h.entity_id IN (SELECT t.id FROM task t WHERE t.project_id = :project)) "
            + "OR (h.entity = 'CONDITION' AND h.entity_id IN (SELECT c.id FROM release_condition c "
            + "WHERE c.project_id = :project)) "
            + "OR (h.entity IN ('DEFECT_FIX', 'DEFECT_CASE', 'DEFECT_TASK', 'DEFECT_RELATION') AND EXISTS "
            + "(SELECT 1 FROM defect p WHERE p.project_id = :project AND " + CHILD_OF + ")) "
            + "OR (h.entity = 'CONDITION_LINK' AND EXISTS "
            + "(SELECT 1 FROM release_condition p WHERE p.project_id = :project AND " + CHILD_OF + ")))";

    private final JdbcClient jdbc;

    public HistoryQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** History ids of one entity in id order, after {@code afterId} (use 0 for the first page). */
    public KeysetPage<Long> idsOfEntity(HistoryEntity entity, String entityId, long afterId, MaxRows maxRows) {
        return KeysetPage.of(jdbc.sql("SELECT id FROM history WHERE entity = :entity AND entity_id = :entityId "
                + "AND id > :after ORDER BY id")
                .param("entity", entity.name())
                .param("entityId", entityId)
                .param("after", afterId)
                .withMaxRows(maxRows.value() + 1)
                .query(Long.class).list(), maxRows.value());
    }

    /** The ids matching the filter in id order, after {@code afterId} (use 0 for the first page). */
    public KeysetPage<Long> page(Filter filter, long afterId, MaxRows maxRows) {
        StringBuilder sql = new StringBuilder("SELECT h.id FROM history h WHERE h.id > :after");
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
        if (filter.project() != null) {
            sql.append(" AND ").append(IN_PROJECT);
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
        sql.append(" ORDER BY h.id");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("after", afterId)
                .withMaxRows(maxRows.value() + 1);
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
        return KeysetPage.of(spec.query(Long.class).list(), maxRows.value());
    }

    /**
     * The newest history ids of one entity, newest first, without the rows imported from an older ledger when
     * {@code withoutImport} is set; at most {@code count} ids.
     */
    public List<Long> newestOfEntity(HistoryEntity entity, String entityId, boolean withoutImport, int count) {
        return jdbc.sql("SELECT id FROM history WHERE entity = :entity AND entity_id = :entityId"
                + (withoutImport ? " AND op <> 'IMPORT'" : "") + " ORDER BY occurred_at DESC, id DESC")
                .param("entity", entity.name())
                .param("entityId", entityId)
                .withMaxRows(count)
                .query(Long.class).list();
    }
}
