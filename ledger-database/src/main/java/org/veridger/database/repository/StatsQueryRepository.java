// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.veridger.domain.value.ProjectId;
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

    public Map<String, Long> issuesByStatus(ProjectId project) {
        return counts(
                "SELECT status AS bucket, COUNT(*) AS n FROM issue GROUP BY status ORDER BY status",
                "SELECT status AS bucket, COUNT(*) AS n FROM issue WHERE project_id = :project GROUP BY status ORDER BY status",
                project);
    }

    public Map<String, Long> issuesBySeverity(ProjectId project) {
        return counts(
                "SELECT severity AS bucket, COUNT(*) AS n FROM issue GROUP BY severity ORDER BY severity",
                "SELECT severity AS bucket, COUNT(*) AS n FROM issue WHERE project_id = :project GROUP BY severity ORDER BY severity",
                project);
    }

    public Map<String, Long> specsByStatus(ProjectId project) {
        return counts(
                "SELECT status AS bucket, COUNT(*) AS n FROM spec GROUP BY status ORDER BY status",
                "SELECT status AS bucket, COUNT(*) AS n FROM spec WHERE project_id = :project GROUP BY status ORDER BY status",
                project);
    }

    public Map<String, Long> tasksByState(ProjectId project) {
        return counts(
                "SELECT state AS bucket, COUNT(*) AS n FROM task GROUP BY state ORDER BY state",
                "SELECT state AS bucket, COUNT(*) AS n FROM task WHERE project_id = :project GROUP BY state ORDER BY state",
                project);
    }

    public Map<String, Long> criteriaByState(ProjectId project) {
        return counts(
                "SELECT state AS bucket, COUNT(*) AS n FROM milestone_criterion c WHERE " + MilestoneArchiveRepository.ACTIVE
                        + " GROUP BY state ORDER BY state",
                "SELECT state AS bucket, COUNT(*) AS n FROM milestone_criterion c WHERE c.project_id = :project AND "
                        + MilestoneArchiveRepository.ACTIVE + " GROUP BY state ORDER BY state",
                project);
    }

    /** New issues per UTC day, oldest day first. */
    public Map<LocalDate, Long> issuesCreatedPerDay(ProjectId project) {
        return perDay(
                "SELECT CAST(created_at AS DATE) AS bucket, COUNT(*) AS n FROM issue GROUP BY CAST(created_at AS DATE) ORDER BY CAST(created_at AS DATE)",
                "SELECT CAST(created_at AS DATE) AS bucket, COUNT(*) AS n FROM issue WHERE project_id = :project GROUP BY CAST(created_at AS DATE) ORDER BY CAST(created_at AS DATE)",
                project);
    }

    /** New specs per UTC day, oldest day first. */
    public Map<LocalDate, Long> specsCreatedPerDay(ProjectId project) {
        return perDay(
                "SELECT CAST(created_at AS DATE) AS bucket, COUNT(*) AS n FROM spec GROUP BY CAST(created_at AS DATE) ORDER BY CAST(created_at AS DATE)",
                "SELECT CAST(created_at AS DATE) AS bucket, COUNT(*) AS n FROM spec WHERE project_id = :project GROUP BY CAST(created_at AS DATE) ORDER BY CAST(created_at AS DATE)",
                project);
    }

    /** New tasks per UTC day, oldest day first. */
    public Map<LocalDate, Long> tasksCreatedPerDay(ProjectId project) {
        return perDay(
                "SELECT CAST(created_at AS DATE) AS bucket, COUNT(*) AS n FROM task GROUP BY CAST(created_at AS DATE) ORDER BY CAST(created_at AS DATE)",
                "SELECT CAST(created_at AS DATE) AS bucket, COUNT(*) AS n FROM task WHERE project_id = :project GROUP BY CAST(created_at AS DATE) ORDER BY CAST(created_at AS DATE)",
                project);
    }

    /** Single-table issue count for a project (or the whole ledger when {@code project} is null). */
    public long countIssues(ProjectId project) {
        return countTable("SELECT COUNT(*) FROM issue", "SELECT COUNT(*) FROM issue WHERE project_id = :project", project);
    }

    /** Single-table spec count for a project (or the whole ledger when {@code project} is null). */
    public long countSpecs(ProjectId project) {
        return countTable("SELECT COUNT(*) FROM spec", "SELECT COUNT(*) FROM spec WHERE project_id = :project", project);
    }

    /** Single-table task count for a project (or the whole ledger when {@code project} is null). */
    public long countTasks(ProjectId project) {
        return countTable("SELECT COUNT(*) FROM task", "SELECT COUNT(*) FROM task WHERE project_id = :project", project);
    }

    /** Single-table milestone criterion count for a project (or the whole ledger when {@code project} is null). */
    public long countCriteria(ProjectId project) {
        return countTable("SELECT COUNT(*) FROM milestone_criterion c WHERE " + MilestoneArchiveRepository.ACTIVE,
                "SELECT COUNT(*) FROM milestone_criterion c WHERE c.project_id = :project AND "
                        + MilestoneArchiveRepository.ACTIVE, project);
    }

    private long countTable(String allSql, String byProjectSql, ProjectId project) {
        JdbcClient.StatementSpec spec = project == null
                ? jdbc.sql(allSql)
                : jdbc.sql(byProjectSql).param("project", project.value());
        return spec.query(Long.class).single();
    }

    private Map<String, Long> counts(String allSql, String byProjectSql, ProjectId project) {
        JdbcClient.StatementSpec spec = project == null
                ? jdbc.sql(allSql)
                : jdbc.sql(byProjectSql).param("project", project.value());
        Map<String, Long> counts = new LinkedHashMap<>();
        spec.query((rs, n) -> Map.entry(rs.getString("bucket"), rs.getLong("n"))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    private Map<LocalDate, Long> perDay(String allSql, String byProjectSql, ProjectId project) {
        JdbcClient.StatementSpec spec = project == null
                ? jdbc.sql(allSql)
                : jdbc.sql(byProjectSql).param("project", project.value());
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        spec.query((rs, n) -> Map.entry(rs.getObject("bucket", LocalDate.class), rs.getLong("n"))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }
}
