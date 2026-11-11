package org.pgledger.database.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Defect lists for the read tools. Pages are keyset pages positioned by the last row of the previous page with
 * {@code created_at < ? OR (created_at = ? AND id < ?)} (or {@code >} for the oldest-first order); a row-value
 * comparison is not understood by every DBMS. The row limit is JDBC {@code setMaxRows(max + 1)}, never a vendor
 * clause: the extra row only tells that the list continues. SQL text is built from fixed fragments; every value
 * is a bind parameter.
 */
@Repository
public class DefectQueryRepository {

    /** Optional filters of a defect list; the creation times are inclusive limits (UTC instants). */
    public record Filter(DefectStatus status, CodeValue severity, CodeValue category, Instant createdFrom,
            Instant createdTo) {
        public static final Filter NONE = new Filter(null, null, null, null, null);
    }

    /** Which end of the list comes first. */
    public enum Order {
        NEWEST_FIRST, OLDEST_FIRST
    }

    /** Position after the last row of a page. */
    public record Position(Instant createdAt, DefectId id) {
    }

    private static final String SELECT = "SELECT id, title, category, severity, status, version, created_at, "
            + "updated_at FROM defect WHERE project_id = :project";

    private final JdbcClient jdbc;

    public DefectQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KeysetPage<DefectSummary> page(ProjectId project, Filter filter, MaxRows maxRows, Order order,
            Position after) {
        boolean newestFirst = order == Order.NEWEST_FIRST;
        StringBuilder sql = new StringBuilder(SELECT);
        if (filter.status() != null) {
            sql.append(" AND status = :status");
        }
        if (filter.severity() != null) {
            sql.append(" AND severity = :severity");
        }
        if (filter.category() != null) {
            sql.append(" AND category = :category");
        }
        if (filter.createdFrom() != null && filter.createdTo() != null) {
            sql.append(" AND created_at BETWEEN :from AND :to");
        } else if (filter.createdFrom() != null) {
            sql.append(" AND created_at >= :from");
        } else if (filter.createdTo() != null) {
            sql.append(" AND created_at <= :to");
        }
        if (after != null) {
            sql.append(newestFirst
                    ? " AND (created_at < :after OR (created_at = :after AND id < :afterId))"
                    : " AND (created_at > :after OR (created_at = :after AND id > :afterId))");
        }
        sql.append(newestFirst ? " ORDER BY created_at DESC, id DESC" : " ORDER BY created_at ASC, id ASC");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("project", project.value())
                .withMaxRows(maxRows.value() + 1);
        if (filter.status() != null) {
            spec = spec.param("status", filter.status().name());
        }
        if (filter.severity() != null) {
            spec = spec.param("severity", filter.severity().value());
        }
        if (filter.category() != null) {
            spec = spec.param("category", filter.category().value());
        }
        if (filter.createdFrom() != null) {
            spec = spec.param("from", UtcTime.toDb(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            spec = spec.param("to", UtcTime.toDb(filter.createdTo()));
        }
        if (after != null) {
            spec = spec.param("after", UtcTime.toDb(after.createdAt())).param("afterId", after.id().value());
        }
        return KeysetPage.of(spec.query(DefectQueryRepository::summary).list(), maxRows.value());
    }

    /**
     * Defects whose title, behavior or root cause contain the keywords, case-insensitively. {@code %}, {@code _} and
     * the backslash in a keyword are literal. The newest {@code maxRows} are returned.
     */
    public KeysetPage<DefectSummary> search(ProjectId project, List<String> keywords, KeywordMatch match,
            MaxRows maxRows) {
        if (keywords.isEmpty()) {
            throw new IllegalArgumentException("at least one keyword is needed");
        }
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < keywords.size(); i++) {
            terms.add("(LOWER(title) LIKE LOWER(:k" + i + ") ESCAPE '\\' OR LOWER(behavior) LIKE LOWER(:k" + i
                    + ") ESCAPE '\\' OR LOWER(root_cause) LIKE LOWER(:k" + i + ") ESCAPE '\\')");
        }
        String joiner = match == KeywordMatch.ALL ? " AND " : " OR ";
        String sql = SELECT + " AND (" + String.join(joiner, terms) + ") ORDER BY created_at DESC, id DESC";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("project", project.value())
                .withMaxRows(maxRows.value() + 1);
        for (int i = 0; i < keywords.size(); i++) {
            spec = spec.param("k" + i, "%" + escapeLike(keywords.get(i)) + "%");
        }
        return KeysetPage.of(spec.query(DefectQueryRepository::summary).list(), maxRows.value());
    }

    private static DefectSummary summary(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new DefectSummary(DefectId.of(rs.getString("id")), rs.getString("title"),
                CodeValue.of(rs.getString("category")), CodeValue.of(rs.getString("severity")),
                DefectStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                UtcTime.read(rs, "created_at"), UtcTime.read(rs, "updated_at"));
    }

    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
