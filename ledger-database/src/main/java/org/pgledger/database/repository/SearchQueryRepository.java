package org.pgledger.database.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Keyword search over defects, tasks and conditions, newest first. A keyword matches when one of the text columns
 * of a kind contains it, ignoring case ({@code LOWER(col) LIKE LOWER(?) ESCAPE}); {@code %}, {@code _} and the
 * backslash of a keyword are literal. The three kinds are read with one UNION ALL; a condition has no creation
 * time and is placed after everything else (its time is the epoch). Pages are keyset pages in the OR form, the
 * limit is JDBC {@code setMaxRows(max + 1)}; every value is a bind parameter.
 */
@Repository
public class SearchQueryRepository {

    /** What is searched. */
    public enum Kind {
        DEFECT, TASK, CONDITION
    }

    /** The criteria; {@code project} and the time limits may be null, {@code excludeStatuses} are state names. */
    public record Filter(ProjectId project, Set<Kind> kinds, List<String> keywords, KeywordMatch match,
            Set<String> excludeStatuses, Instant createdFrom, Instant createdTo) {

        public Filter {
            kinds = Set.copyOf(kinds);
            keywords = List.copyOf(keywords);
            excludeStatuses = Set.copyOf(excludeStatuses);
        }
    }

    /** One found row. {@code createdAt} is null for a condition. */
    public record Hit(Kind kind, String id, String projectId, String title, String state, Instant createdAt) {
    }

    /** Position after the last row of a page. */
    public record Position(Instant createdAt, Kind kind, String id) {
    }

    private static final String EPOCH = "TIMESTAMP '1970-01-01 00:00:00'";

    private final JdbcClient jdbc;

    public SearchQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KeysetPage<Hit> page(Filter filter, MaxRows maxRows, Position after) {
        List<String> selects = new ArrayList<>();
        if (filter.kinds().contains(Kind.DEFECT)) {
            selects.add(select("'DEFECT'", "id", "project_id", "title", "status", "created_at", "defect", "status",
                    List.of("title", "behavior", "root_cause"), filter));
        }
        if (filter.kinds().contains(Kind.TASK)) {
            selects.add(select("'TASK'", "id", "project_id", "title", "state", "created_at", "task", "state",
                    List.of("title", "purpose"), filter));
        }
        boolean timed = filter.createdFrom() != null || filter.createdTo() != null;
        if (filter.kinds().contains(Kind.CONDITION) && !timed) {
            selects.add(select("'CONDITION'", "id", "project_id", "condition_text", "state", EPOCH,
                    "release_condition", "state", List.of("condition_text"), filter));
        }
        if (selects.isEmpty()) {
            return new KeysetPage<>(List.of(), false);
        }
        StringBuilder sql = new StringBuilder("SELECT kind, id, project_id, title, state, created_at FROM (")
                .append(String.join(" UNION ALL ", selects)).append(") hits");
        if (after != null) {
            sql.append(" WHERE created_at < :after OR (created_at = :after AND (id < :afterId OR "
                    + "(id = :afterId AND kind < :afterKind)))");
        }
        sql.append(" ORDER BY created_at DESC, id DESC, kind DESC");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).withMaxRows(maxRows.value() + 1);
        if (filter.project() != null) {
            spec = spec.param("project", filter.project().value());
        }
        for (int i = 0; i < filter.keywords().size(); i++) {
            spec = spec.param("k" + i, LikePattern.contains(filter.keywords().get(i)));
        }
        // the parameter names are positions (x0, x1, ...), never a part of the value: a status text cannot become SQL
        int excluded = 0;
        for (String status : filter.excludeStatuses()) {
            spec = spec.param("x" + excluded++, status);
        }
        if (filter.createdFrom() != null) {
            spec = spec.param("from", UtcTime.toDb(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            spec = spec.param("to", UtcTime.toDb(filter.createdTo()));
        }
        if (after != null) {
            spec = spec.param("after", UtcTime.toDb(after.createdAt())).param("afterId", after.id())
                    .param("afterKind", after.kind().name());
        }
        List<Hit> fetched = spec.query((rs, n) -> {
            Kind kind = Kind.valueOf(rs.getString("kind"));
            Instant createdAt = UtcTime.read(rs, "created_at");
            return new Hit(kind, rs.getString("id"), rs.getString("project_id"), rs.getString("title"),
                    rs.getString("state"), kind == Kind.CONDITION ? null : createdAt);
        }).list();
        return KeysetPage.of(fetched, maxRows.value());
    }

    private static String select(String kind, String id, String project, String title, String state, String created,
            String table, String stateColumn, List<String> textColumns, Filter filter) {
        StringBuilder sql = new StringBuilder("SELECT ").append(kind).append(" AS kind, ").append(id)
                .append(" AS id, ").append(project).append(" AS project_id, ").append(title).append(" AS title, ")
                .append(state).append(" AS state, ").append(created).append(" AS created_at FROM ").append(table)
                .append(" WHERE 1 = 1");
        if (filter.project() != null) {
            sql.append(" AND project_id = :project");
        }
        if (!filter.keywords().isEmpty()) {
            List<String> terms = new ArrayList<>();
            for (int i = 0; i < filter.keywords().size(); i++) {
                List<String> columns = new ArrayList<>();
                for (String column : textColumns) {
                    columns.add("LOWER(" + column + ") LIKE LOWER(:k" + i + ") ESCAPE '\\'");
                }
                terms.add("(" + String.join(" OR ", columns) + ")");
            }
            sql.append(" AND (").append(String.join(filter.match() == KeywordMatch.ALL ? " AND " : " OR ", terms))
                    .append(")");
        }
        // one condition per excluded status, named by position; all are joined with AND, so which value gets which
        // position does not matter
        for (int i = 0; i < filter.excludeStatuses().size(); i++) {
            sql.append(" AND ").append(stateColumn).append(" <> :x").append(i);
        }
        if (filter.createdFrom() != null) {
            sql.append(" AND created_at >= :from");
        }
        if (filter.createdTo() != null) {
            sql.append(" AND created_at <= :to");
        }
        return sql.toString();
    }
}
