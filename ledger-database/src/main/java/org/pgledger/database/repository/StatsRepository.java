package org.pgledger.database.repository;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Counts and the attention list of a project. */
@Repository
public class StatsRepository {

    /** One item that needs a look: its kind and the id of the defect or task. */
    public record AttentionItem(AttentionKind kind, String id) {
    }

    private final JdbcClient jdbc;

    public StatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<DefectStatus, Long> defectsByStatus(ProjectId project) {
        Map<DefectStatus, Long> counts = new EnumMap<>(DefectStatus.class);
        jdbc.sql("SELECT status, COUNT(*) AS n FROM defect WHERE project_id = :p GROUP BY status")
                .param("p", project.value())
                .query((rs, n) -> Map.entry(DefectStatus.valueOf(rs.getString("status")), rs.getLong("n")))
                .list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /** Severity codes are data, so the keys are the code texts. */
    public Map<String, Long> defectsBySeverity(ProjectId project) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT severity, COUNT(*) AS n FROM defect WHERE project_id = :p GROUP BY severity "
                + "ORDER BY severity").param("p", project.value())
                .query((rs, n) -> Map.entry(rs.getString("severity"), rs.getLong("n")))
                .list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /** The five attention kinds; the ids of one kind are ordered. */
    public List<AttentionItem> attention(ProjectId project) {
        return jdbc.sql("SELECT kind, id FROM ("
                + "SELECT 'OPEN_WITH_FIX' AS kind, d.id AS id, d.project_id AS project_id FROM defect d "
                + "WHERE d.status = 'OPEN' AND EXISTS (SELECT 1 FROM defect_fix f WHERE f.defect_id = d.id) "
                + "UNION ALL SELECT 'FIXED_WITHOUT_COMMIT', d.id, d.project_id FROM defect d "
                + "WHERE d.status IN ('SOURCE_FIXED', 'VERIFIED') "
                + "AND NOT EXISTS (SELECT 1 FROM defect_fix f WHERE f.defect_id = d.id) "
                + "UNION ALL SELECT 'OPEN_NO_OWNER', d.id, d.project_id FROM defect d "
                + "WHERE d.status = 'OPEN' AND d.owner_actor_id IS NULL "
                + "UNION ALL SELECT 'TASK_UNKNOWN', t.id, t.project_id FROM task t WHERE t.state = 'UNKNOWN' "
                + "UNION ALL SELECT 'DONE_NOT_VERIFIED', t.id, t.project_id FROM task t "
                + "WHERE t.state = 'REPORTED_DONE') a WHERE a.project_id = :p ORDER BY kind, id")
                .param("p", project.value())
                .query((rs, n) -> new AttentionItem(AttentionKind.valueOf(rs.getString("kind")), rs.getString("id")))
                .list();
    }
}
