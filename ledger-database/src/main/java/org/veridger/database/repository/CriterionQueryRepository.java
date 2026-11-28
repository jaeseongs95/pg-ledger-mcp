// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.veridger.database.time.UtcTime;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Milestone criterion lists ordered by {@code id DESC} in keyset pages ({@code id < ?}).
 */
@Repository
public class CriterionQueryRepository {

    /** Optional filters. */
    public record Filter(CriterionState state, String milestone, boolean archived) {
        public Filter(CriterionState state, String milestone) {
            this(state, milestone, false);
        }

        public static final Filter NONE = new Filter(null, null);
    }

    /** Columns shown in a milestone criterion list. */
    public record CriterionSummary(CriterionId id, String milestone, String goalLabel, String code,
            String criterionText, CriterionState state, long version, Instant createdAt, Instant updatedAt) {
    }

    /** The date may be absent for criteria without a history row. */
    public record Position(Instant at, CriterionId id) {
    }

    /** Per-milestone criterion counts grouped by state. */
    public record MilestoneRollup(String milestone, long total, long met, long completed, long active, long planned,
            long notMet, long unknown) {
    }

    /** Linked issue, specification, and task counts for one criterion. */
    public record LinkCounts(long issues, long specs, long tasks) {
        public static final LinkCounts ZERO = new LinkCounts(0L, 0L, 0L);
    }

    private static final int MAX_IN_CLAUSE = 1000;

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public CriterionQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<CriterionSummary> page(ProjectId project, Filter filter, MaxRows maxRows, CriterionId after) {
        return page(project, filter, maxRows, DateQuery.NONE, after == null ? null : new Position(null, after));
    }

    public KeysetPage<CriterionSummary> page(ProjectId project, Filter filter, MaxRows maxRows, DateQuery dates,
            Position after) {
        return page(project, filter, maxRows, dates, after, null);
    }

    public KeysetPage<CriterionSummary> page(ProjectId project, Filter filter, MaxRows maxRows, DateQuery dates,
            Position after, String projectVersionId) {
        StringBuilder sql = new StringBuilder("SELECT c.id, c.milestone_name, c.goal_label, c.code, c.criterion_text, "
                + "c.state, c.version, dates.created_at, dates.updated_at ");
        appendMatching(sql, filter, dates, projectVersionId);
        if (dates.sort() != null) {
            if (after != null) {
                dates.appendSeek(sql, "c.id", after.at(), true);
            }
            dates.appendOrder(sql, "c.id", true);
        } else {
            if (after != null) {
                sql.append(" AND c.id < :after");
            }
            sql.append(" ORDER BY c.id DESC");
        }
        JdbcClient.StatementSpec spec = bindMatching(jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("row_count", maxRows.value() + 1), project, filter, dates, projectVersionId);
        if (after != null) {
            spec = dates.sort() == null ? spec.param("after", after.id().value())
                    : dates.bindSeek(spec, after.at(), after.id().value());
        }
        List<CriterionSummary> fetched = spec.query((rs, n) -> new CriterionSummary(
                CriterionId.of(rs.getString("id")), rs.getString("milestone_name"), rs.getString("goal_label"),
                rs.getString("code"), rs.getString("criterion_text"), CriterionState.valueOf(rs.getString("state")),
                rs.getLong("version"), UtcTime.read(rs, "created_at"), UtcTime.read(rs, "updated_at"))).list();
        return KeysetPage.of(fetched, maxRows.value());
    }

    public long count(ProjectId project, Filter filter, DateQuery dates, String projectVersionId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) ");
        appendMatching(sql, filter, dates, projectVersionId);
        return bindMatching(jdbc.sql(sql.toString()), project, filter, dates, projectVersionId)
                .query(Long.class).single();
    }

    private static void appendMatching(StringBuilder sql, Filter filter, DateQuery dates, String projectVersionId) {
        sql.append("FROM milestone_criterion c "
                + "LEFT JOIN (SELECT h.entity_id, "
                + "MIN(CASE WHEN h.op IN ('CREATE', 'IMPORT') THEN h.occurred_at END) AS created_at, "
                + "MAX(h.occurred_at) AS updated_at "
                + "FROM history h WHERE h.entity = 'CRITERION' AND h.entity_id IN "
                + "(SELECT id FROM milestone_criterion WHERE project_id = :project) GROUP BY h.entity_id) dates "
                + "ON dates.entity_id = c.id WHERE c.project_id = :project AND ")
                .append(filter.archived() ? MilestoneArchiveRepository.ARCHIVED : MilestoneArchiveRepository.ACTIVE);
        if (filter.state() != null) {
            sql.append(" AND state = :state");
        }
        if (filter.milestone() != null) {
            sql.append(" AND milestone_name = :milestone");
        }
        ProjectVersionRepository.appendItemFilter(sql, projectVersionId, "c.id", HistoryEntity.CRITERION);
        dates.appendRange(sql);
    }

    private static JdbcClient.StatementSpec bindMatching(JdbcClient.StatementSpec spec, ProjectId project,
            Filter filter, DateQuery dates, String projectVersionId) {
        spec = spec.param("project", project.value());
        if (filter.state() != null) {
            spec = spec.param("state", filter.state().name());
        }
        if (filter.milestone() != null) {
            spec = spec.param("milestone", filter.milestone());
        }
        return dates.bindRange(ProjectVersionRepository.bindItemFilter(spec, projectVersionId));
    }

    /**
     * Per-milestone readiness rollups for a project, ordered alphabetically by milestone name.
     */
    public List<MilestoneRollup> milestoneRollups(ProjectId project) {
        return milestoneRollups(project, false, null);
    }

    public List<MilestoneRollup> milestoneRollups(ProjectId project, boolean archived, String projectVersionId) {
        Map<String, long[]> buckets = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT milestone_name, state, COUNT(*) AS cnt FROM milestone_criterion c "
                + "WHERE c.project_id = :project AND "
                + (archived ? MilestoneArchiveRepository.ARCHIVED : MilestoneArchiveRepository.ACTIVE));
        ProjectVersionRepository.appendItemFilter(sql, projectVersionId, "c.id", HistoryEntity.CRITERION);
        sql.append(" GROUP BY milestone_name, state ORDER BY milestone_name, state");
        ProjectVersionRepository.bindItemFilter(jdbc.sql(sql.toString()).param("project", project.value()), projectVersionId)
                .query(rs -> {
                    String milestone = rs.getString("milestone_name");
                    CriterionState state = CriterionState.valueOf(rs.getString("state"));
                    long count = rs.getLong("cnt");
                    long[] counts = buckets.computeIfAbsent(milestone, k -> new long[6]);
                    int idx = switch (state) {
                        case MET -> 0;
                        case COMPLETED -> 1;
                        case ACTIVE -> 2;
                        case PLANNED -> 3;
                        case NOT_MET -> 4;
                        case UNKNOWN -> 5;
                    };
                    counts[idx] += count;
                });
        List<MilestoneRollup> out = new ArrayList<>(buckets.size());
        for (Map.Entry<String, long[]> entry : buckets.entrySet()) {
            long[] c = entry.getValue();
            long total = c[0] + c[1] + c[2] + c[3] + c[4] + c[5];
            out.add(new MilestoneRollup(entry.getKey(), total, c[0], c[1], c[2], c[3], c[4], c[5]));
        }
        return out;
    }

    /**
     * Batched counts of linked issues, specifications, and tasks for a page of criterion ids.
     */
    public Map<String, LinkCounts> linkCounts(Collection<String> criterionIds) {
        if (criterionIds == null || criterionIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(criterionIds));
        Set<String> asked = new HashSet<>(distinct);
        Map<String, LinkCounts> out = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += MAX_IN_CLAUSE) {
            List<String> batch = distinct.subList(from, Math.min(from + MAX_IN_CLAUSE, distinct.size()));
            jdbc.sql("SELECT criterion_id, COUNT(issue_id) AS issue_cnt, COUNT(spec_id) AS spec_cnt, "
                    + "COUNT(task_id) AS task_cnt FROM criterion_link WHERE criterion_id IN (:ids) "
                    + "GROUP BY criterion_id")
                    .param("ids", batch)
                    .query(rs -> {
                        String id = rs.getString("criterion_id");
                        if (asked.contains(id)) {
                            out.put(id, new LinkCounts(rs.getLong("issue_cnt"), rs.getLong("spec_cnt"),
                                    rs.getLong("task_cnt")));
                        }
                    });
        }
        return out;
    }
}
