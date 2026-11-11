package org.pgledger.database.repository;

import java.util.List;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The items that need a look, of one project or of all, optionally of one kind, in keyset pages ordered by kind and
 * id ({@code kind > ? OR (kind = ? AND id > ?)}). The five kinds are the old attention view; a verified fix
 * without CI data is never one of them.
 */
@Repository
public class AttentionQueryRepository {

    /** One item: its kind, the id of the defect or task and its project. */
    public record Item(AttentionKind kind, String id, String projectId) {
    }

    /** Position after the last row of a page. */
    public record Position(AttentionKind kind, String id) {
    }

    private static final String ITEMS = "SELECT 'OPEN_WITH_FIX' AS kind, d.id AS id, d.project_id AS project_id "
            + "FROM defect d WHERE d.status = 'OPEN' "
            + "AND EXISTS (SELECT 1 FROM defect_fix f WHERE f.defect_id = d.id) "
            + "UNION ALL SELECT 'FIXED_WITHOUT_COMMIT', d.id, d.project_id FROM defect d "
            + "WHERE d.status IN ('SOURCE_FIXED', 'VERIFIED') "
            + "AND NOT EXISTS (SELECT 1 FROM defect_fix f WHERE f.defect_id = d.id) "
            + "UNION ALL SELECT 'OPEN_NO_OWNER', d.id, d.project_id FROM defect d "
            + "WHERE d.status = 'OPEN' AND d.owner_actor_id IS NULL "
            + "UNION ALL SELECT 'TASK_UNKNOWN', t.id, t.project_id FROM task t WHERE t.state = 'UNKNOWN' "
            + "UNION ALL SELECT 'DONE_NOT_VERIFIED', t.id, t.project_id FROM task t "
            + "WHERE t.state = 'REPORTED_DONE'";

    private final JdbcClient jdbc;

    public AttentionQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KeysetPage<Item> page(ProjectId project, AttentionKind kind, MaxRows maxRows, Position after) {
        StringBuilder sql = new StringBuilder("SELECT kind, id, project_id FROM (").append(ITEMS)
                .append(") a WHERE 1 = 1");
        if (project != null) {
            sql.append(" AND project_id = :project");
        }
        if (kind != null) {
            sql.append(" AND kind = :kind");
        }
        if (after != null) {
            sql.append(" AND (kind > :afterKind OR (kind = :afterKind AND id > :afterId))");
        }
        sql.append(" ORDER BY kind, id");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).withMaxRows(maxRows.value() + 1);
        if (project != null) {
            spec = spec.param("project", project.value());
        }
        if (kind != null) {
            spec = spec.param("kind", kind.name());
        }
        if (after != null) {
            spec = spec.param("afterKind", after.kind().name()).param("afterId", after.id());
        }
        List<Item> fetched = spec.query((rs, n) -> new Item(AttentionKind.valueOf(rs.getString("kind")),
                rs.getString("id"), rs.getString("project_id"))).list();
        return KeysetPage.of(fetched, maxRows.value());
    }
}
