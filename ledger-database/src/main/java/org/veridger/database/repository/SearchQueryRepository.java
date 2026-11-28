// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.veridger.database.time.UtcTime;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Keyword search over defects, tasks and conditions, newest first. A keyword matches when one of the text columns
 * of a kind contains it, ignoring case ({@code LOWER(col) LIKE LOWER(?) ESCAPE}); {@code %}, {@code _} and the
 * backslash of a keyword are literal. The three kinds are read with one UNION ALL; a condition has no creation
 * time and is placed after everything else (its time is the epoch). Pages are keyset pages in the OR form, the
 * limit is {@code max + 1} rows in the row-limit clause of the actual product ({@link SqlBuilder}); every value is a
 * bind parameter.
 */
@Repository
public class SearchQueryRepository {

    /** What is searched. */
    public enum Kind {
        ISSUE, SPEC, TASK, CRITERION
    }

    /**
     * The criteria; {@code project} and the time limits may be null, {@code excludeStatuses} are state names. The sets are
     * kept in their natural order, so the text of a filter (the source of the digest in a cursor) is the same in every
     * start of the JVM: the order of {@code Set.copyOf} is chosen again by each start, and a cursor made before a restart
     * would be refused after it.
     */
    public record Filter(ProjectId project, Set<Kind> kinds, List<String> keywords, KeywordMatch match,
            Set<String> excludeStatuses, Instant createdFrom, Instant createdTo) {

        public Filter {
            kinds = Collections.unmodifiableSortedSet(new TreeSet<>(kinds));
            keywords = List.copyOf(keywords);
            excludeStatuses = Collections.unmodifiableSortedSet(new TreeSet<>(excludeStatuses));
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
    private final SqlBuilder sqlBuilder;

    public SearchQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<Hit> page(Filter filter, MaxRows maxRows, Position after) {
        return page(filter, maxRows, after, null);
    }

    public KeysetPage<Hit> page(Filter filter, MaxRows maxRows, Position after, String projectVersionId) {
        List<String> selects = new ArrayList<>();
        boolean hasAfter = after != null;
        if (filter.kinds().contains(Kind.ISSUE)) {
            selects.add(select(HistoryEntity.ISSUE, "title", "status", "created_at", "issue",
                    List.of("title", "symptom", "root_cause"), filter, hasAfter, projectVersionId));
        }
        if (filter.kinds().contains(Kind.SPEC)) {
            selects.add(select(HistoryEntity.SPEC, "title", "status", "created_at", "spec",
                    List.of("title", "summary", "acceptance_criteria"), filter, hasAfter, projectVersionId));
        }
        if (filter.kinds().contains(Kind.TASK)) {
            selects.add(select(HistoryEntity.TASK, "title", "state", "created_at", "task",
                    List.of("title", "purpose", "success_condition"), filter, hasAfter, projectVersionId));
        }
        boolean timed = filter.createdFrom() != null || filter.createdTo() != null;
        if (filter.kinds().contains(Kind.CRITERION) && !timed) {
            selects.add(select(HistoryEntity.CRITERION, "criterion_text", "state", EPOCH,
                    "milestone_criterion", List.of("criterion_text"), filter, hasAfter, projectVersionId));
        }
        if (selects.isEmpty()) {
            return new KeysetPage<>(List.of(), false);
        }
        StringBuilder sql = new StringBuilder("SELECT kind, id, project_id, title, state, created_at FROM (")
                .append(String.join(" UNION ALL ", selects)).append(") hits");
        sql.append(" ORDER BY created_at DESC, id DESC, kind DESC");
        JdbcClient.StatementSpec spec = jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("row_count", maxRows.value() + 1);
        if (filter.project() != null) {
            spec = spec.param("project", filter.project().value());
        }
        spec = ProjectVersionRepository.bindItemFilter(spec, projectVersionId);
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
                    rs.getString("state"), kind == Kind.CRITERION ? null : createdAt);
        }).list();
        return KeysetPage.of(fetched, maxRows.value());
    }

    private static String select(HistoryEntity entity, String title, String state, String created, String table,
            List<String> textColumns, Filter filter, boolean withAfter, String projectVersionId) {
        String kind = switch (entity) {
            case ISSUE -> "'ISSUE'";
            case SPEC -> "'SPEC'";
            case TASK -> "'TASK'";
            case CRITERION -> "'CRITERION'";
            default -> throw new IllegalArgumentException("Unsupported search entity");
        };
        StringBuilder sql = new StringBuilder("SELECT ").append(kind).append(" AS kind, id, project_id, ")
                .append(title).append(" AS title, ").append(state).append(" AS state, ")
                .append(created).append(" AS created_at FROM ").append(table)
                .append(" c WHERE 1 = 1");
        if (entity == HistoryEntity.CRITERION) {
            sql.append(" AND ").append(MilestoneArchiveRepository.ACTIVE);
        }
        if (filter.project() != null) {
            sql.append(" AND project_id = :project");
        }
        ProjectVersionRepository.appendItemFilter(sql, projectVersionId, "id", entity);
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
            sql.append(" AND ").append(state).append(" <> :x").append(i);
        }
        if (filter.createdFrom() != null) {
            sql.append(" AND created_at >= :from");
        }
        if (filter.createdTo() != null) {
            sql.append(" AND created_at <= :to");
        }
        if (withAfter) {
            sql.append(" AND (").append(created).append(" < :after OR (").append(created).append(" = :after AND (")
                    .append("id < :afterId OR (id = :afterId AND ").append(kind)
                    .append(" < :afterKind))))");
        }
        return sql.toString();
    }
}
