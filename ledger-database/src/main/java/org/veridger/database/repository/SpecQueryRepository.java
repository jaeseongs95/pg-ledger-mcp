// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import java.util.List;
import org.veridger.database.time.UtcTime;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Specification lists, newest first, in keyset pages ({@code created_at < ? OR (created_at = ? AND id < ?)}).
 */
@Repository
public class SpecQueryRepository {

    /** Optional filters; {@code owner} is the exact owner id, {@code attention} a spec attention kind. */
    public record Filter(SpecStatus status, SpecId parentId, String priority, String owner, AttentionKind attention) {
        public static final Filter NONE = new Filter(null, null, null, null, null);
    }

    /** Position after the last row of a page. */
    public record Position(Instant at, SpecId id) {
    }

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public SpecQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<SpecSummary> page(ProjectId project, Filter filter, MaxRows maxRows, Position after) {
        return page(project, filter, maxRows, DateQuery.NONE, after);
    }

    public KeysetPage<SpecSummary> page(ProjectId project, Filter filter, MaxRows maxRows, DateQuery dates,
            Position after) {
        return page(project, filter, maxRows, dates, after, null);
    }

    public KeysetPage<SpecSummary> page(ProjectId project, Filter filter, MaxRows maxRows, DateQuery dates,
            Position after, String projectVersionId) {
        StringBuilder sql = new StringBuilder("SELECT id, parent_id, title, status, priority, owner_actor_id, "
                + "version, created_at, updated_at ");
        appendMatching(sql, filter, dates, projectVersionId);
        DateQuery order = dates.defaultOrder(DateQuery.Column.CREATED, false);
        if (after != null) {
            order.appendSeek(sql, "id", after.at(), false);
        }
        order.appendOrder(sql, "id", false);
        JdbcClient.StatementSpec stmt = bindMatching(jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("row_count", maxRows.value() + 1), project, filter, dates, projectVersionId);
        if (after != null) {
            stmt = order.bindSeek(stmt, after.at(), after.id().value());
        }
        List<SpecSummary> fetched = stmt.query((rs, n) -> {
            String rawParent = rs.getString("parent_id");
            return new SpecSummary(SpecId.of(rs.getString("id")), rawParent == null ? null : SpecId.of(rawParent),
                    rs.getString("title"), SpecStatus.valueOf(rs.getString("status")), rs.getString("priority"),
                    rs.getString("owner_actor_id"), rs.getLong("version"), UtcTime.read(rs, "created_at"),
                    UtcTime.read(rs, "updated_at"));
        }).list();
        return KeysetPage.of(fetched, maxRows.value());
    }

    public long count(ProjectId project, Filter filter, DateQuery dates, String projectVersionId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) ");
        appendMatching(sql, filter, dates, projectVersionId);
        return bindMatching(jdbc.sql(sql.toString()), project, filter, dates, projectVersionId)
                .query(Long.class).single();
    }

    private static void appendMatching(StringBuilder sql, Filter filter, DateQuery dates, String projectVersionId) {
        sql.append("FROM spec WHERE project_id = :project");
        if (filter.status() != null) {
            sql.append(" AND status = :status");
        }
        if (filter.parentId() != null) {
            sql.append(" AND parent_id = :parentId");
        }
        if (filter.priority() != null) {
            sql.append(" AND priority = :priority");
        }
        if (filter.owner() != null) {
            sql.append(" AND owner_actor_id = :owner");
        }
        if (filter.attention() != null) {
            sql.append(" AND ").append(AttentionSql.condition(filter.attention(), HistoryEntity.SPEC));
        }
        ProjectVersionRepository.appendItemFilter(sql, projectVersionId, "id", HistoryEntity.SPEC);
        dates.appendRange(sql);
    }

    private static JdbcClient.StatementSpec bindMatching(JdbcClient.StatementSpec stmt, ProjectId project,
            Filter filter, DateQuery dates, String projectVersionId) {
        stmt = stmt.param("project", project.value());
        if (filter.status() != null) {
            stmt = stmt.param("status", filter.status().name());
        }
        if (filter.parentId() != null) {
            stmt = stmt.param("parentId", filter.parentId().value());
        }
        if (filter.priority() != null) {
            stmt = stmt.param("priority", filter.priority());
        }
        if (filter.owner() != null) {
            stmt = stmt.param("owner", filter.owner());
        }
        return dates.bindRange(ProjectVersionRepository.bindItemFilter(stmt, projectVersionId));
    }

}
