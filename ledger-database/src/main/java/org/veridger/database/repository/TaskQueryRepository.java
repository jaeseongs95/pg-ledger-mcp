// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import java.util.List;
import org.veridger.database.time.UtcTime;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TaskId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Task lists, newest first, in keyset pages ({@code created_at < ? OR (created_at = ? AND id < ?)}); the limit is
 * {@code max + 1} rows in the row-limit clause of the actual product ({@link SqlBuilder}). Values are bind parameters,
 * the SQL is built from fixed fragments.
 */
@Repository
public class TaskQueryRepository {

    /** Optional filters; {@code owner} is the exact owner id, {@code attention} one of the two task kinds. */
    public record Filter(TaskState state, String owner, AttentionKind attention) {
        public static final Filter NONE = new Filter(null, null, null);
    }

    /** The columns a task list shows. */
    public record TaskSummary(TaskId id, String title, TaskState state, String ownerActorId, long version,
            Instant createdAt, Instant updatedAt) {
    }

    /** Position after the last row of a page. */
    public record Position(Instant at, TaskId id) {
    }

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public TaskQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<TaskSummary> page(ProjectId project, Filter filter, MaxRows maxRows, Position after) {
        return page(project, filter, maxRows, DateQuery.NONE, after);
    }

    public KeysetPage<TaskSummary> page(ProjectId project, Filter filter, MaxRows maxRows, DateQuery dates,
            Position after) {
        return page(project, filter, maxRows, dates, after, null);
    }

    public KeysetPage<TaskSummary> page(ProjectId project, Filter filter, MaxRows maxRows, DateQuery dates,
            Position after, String projectVersionId) {
        StringBuilder sql = new StringBuilder("SELECT id, title, state, owner_actor_id, version, created_at, "
                + "updated_at ");
        appendMatching(sql, filter, dates, projectVersionId);
        DateQuery order = dates.defaultOrder(DateQuery.Column.CREATED, false);
        if (after != null) {
            order.appendSeek(sql, "id", after.at(), false);
        }
        order.appendOrder(sql, "id", false);
        JdbcClient.StatementSpec spec = bindMatching(jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("row_count", maxRows.value() + 1), project, filter, dates, projectVersionId);
        if (after != null) {
            spec = order.bindSeek(spec, after.at(), after.id().value());
        }
        List<TaskSummary> fetched = spec.query((rs, n) -> new TaskSummary(TaskId.of(rs.getString("id")),
                rs.getString("title"), TaskState.valueOf(rs.getString("state")), rs.getString("owner_actor_id"),
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
        sql.append("FROM task WHERE project_id = :project");
        if (filter.state() != null) {
            sql.append(" AND state = :state");
        }
        if (filter.owner() != null) {
            sql.append(" AND owner_actor_id = :owner");
        }
        if (filter.attention() != null) {
            sql.append(" AND ").append(AttentionSql.condition(filter.attention(), HistoryEntity.TASK));
        }
        ProjectVersionRepository.appendItemFilter(sql, projectVersionId, "id", HistoryEntity.TASK);
        dates.appendRange(sql);
    }

    private static JdbcClient.StatementSpec bindMatching(JdbcClient.StatementSpec spec, ProjectId project,
            Filter filter, DateQuery dates, String projectVersionId) {
        spec = spec.param("project", project.value());
        if (filter.state() != null) {
            spec = spec.param("state", filter.state().name());
        }
        if (filter.owner() != null) {
            spec = spec.param("owner", filter.owner());
        }
        return dates.bindRange(ProjectVersionRepository.bindItemFilter(spec, projectVersionId));
    }

}
