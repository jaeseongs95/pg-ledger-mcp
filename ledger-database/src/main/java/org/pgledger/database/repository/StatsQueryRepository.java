package org.pgledger.database.repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Counts for the statistics tool, of one project or (with a null project) of the whole ledger. Plain
 * {@code COUNT ... GROUP BY}; the keys are the stored texts. Days are cut from the UTC wall clock that the columns
 * hold ({@code CAST(created_at AS DATE)}), so a local midnight never moves a row to another day.
 */
@Repository
public class StatsQueryRepository {

    private final JdbcClient jdbc;

    public StatsQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Long> defectsByStatus(ProjectId project) {
        return counts("defect", "status", project);
    }

    public Map<String, Long> defectsBySeverity(ProjectId project) {
        return counts("defect", "severity", project);
    }

    public Map<String, Long> tasksByState(ProjectId project) {
        return counts("task", "state", project);
    }

    public Map<String, Long> conditionsByState(ProjectId project) {
        return counts("release_condition", "state", project);
    }

    /** New defects per UTC day, oldest day first. */
    public Map<LocalDate, Long> defectsCreatedPerDay(ProjectId project) {
        return perDay("defect", project);
    }

    /** New tasks per UTC day, oldest day first. */
    public Map<LocalDate, Long> tasksCreatedPerDay(ProjectId project) {
        return perDay("task", project);
    }

    private Map<String, Long> counts(String table, String column, ProjectId project) {
        String sql = "SELECT " + column + " AS bucket, COUNT(*) AS n FROM " + table
                + (project == null ? "" : " WHERE project_id = :project") + " GROUP BY " + column + " ORDER BY "
                + column;
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        if (project != null) {
            spec = spec.param("project", project.value());
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        spec.query((rs, n) -> Map.entry(rs.getString("bucket"), rs.getLong("n"))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    private Map<LocalDate, Long> perDay(String table, ProjectId project) {
        String sql = "SELECT CAST(created_at AS DATE) AS bucket, COUNT(*) AS n FROM " + table
                + (project == null ? "" : " WHERE project_id = :project")
                + " GROUP BY CAST(created_at AS DATE) ORDER BY CAST(created_at AS DATE)";
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        if (project != null) {
            spec = spec.param("project", project.value());
        }
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        spec.query((rs, n) -> Map.entry(rs.getObject("bucket", LocalDate.class), rs.getLong("n"))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }
}
