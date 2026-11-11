package org.pgledger.database.repository;

import java.util.List;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Condition lists. A condition has no timestamp, so the list is ordered by id, last id first (the ids of one
 * release are issued by the owner in order), in keyset pages ({@code id < ?}).
 */
@Repository
public class ConditionQueryRepository {

    /** Optional filters. */
    public record Filter(ConditionState state, String release) {
        public static final Filter NONE = new Filter(null, null);
    }

    /** The columns a condition list shows. */
    public record ConditionSummary(ConditionId id, String release, String goalLabel, String code,
            String conditionText, ConditionState state, long version) {
    }

    private final JdbcClient jdbc;

    public ConditionQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KeysetPage<ConditionSummary> page(ProjectId project, Filter filter, MaxRows maxRows, ConditionId after) {
        StringBuilder sql = new StringBuilder("SELECT id, release_name, goal_label, code, condition_text, state, "
                + "version FROM release_condition WHERE project_id = :project");
        if (filter.state() != null) {
            sql.append(" AND state = :state");
        }
        if (filter.release() != null) {
            sql.append(" AND release_name = :release");
        }
        if (after != null) {
            sql.append(" AND id < :after");
        }
        sql.append(" ORDER BY id DESC");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("project", project.value())
                .withMaxRows(maxRows.value() + 1);
        if (filter.state() != null) {
            spec = spec.param("state", filter.state().name());
        }
        if (filter.release() != null) {
            spec = spec.param("release", filter.release());
        }
        if (after != null) {
            spec = spec.param("after", after.value());
        }
        List<ConditionSummary> fetched = spec.query((rs, n) -> new ConditionSummary(
                ConditionId.of(rs.getString("id")), rs.getString("release_name"), rs.getString("goal_label"),
                rs.getString("code"), rs.getString("condition_text"), ConditionState.valueOf(rs.getString("state")),
                rs.getLong("version"))).list();
        return KeysetPage.of(fetched, maxRows.value());
    }
}
