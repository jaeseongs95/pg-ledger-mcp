package org.pgledger.database.repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Finds defects, tasks and conditions by id text. The ids are bound as they come: an id that is no valid id of a
 * kind simply finds nothing, so a caller may ask for a mixed list without parsing it first.
 */
@Repository
public class EntityLookupRepository {

    /** The columns every kind has in common; {@code mergedInto} is only set for a merged defect. */
    public record Found(String id, String projectId, String state, long version, String mergedInto) {
    }

    private final JdbcClient jdbc;

    public EntityLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Found> defects(Collection<String> ids) {
        return jdbc.sql("SELECT id, project_id, status, version, merged_into FROM defect WHERE id IN (:ids) "
                + "ORDER BY id").param("ids", ids)
                .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("status"),
                        rs.getLong("version"), rs.getString("merged_into")))
                .list();
    }

    /**
     * The tasks of these ids. The id of a task is compared exactly: a DBMS that ignores case in {@code IN} also returns the task
     * {@code AGS:A07-A} for {@code ags:a07-a}, so the rows are kept only when their id is one of the ids asked for.
     */
    public List<Found> tasks(Collection<String> ids) {
        return exactly(ids, jdbc.sql("SELECT id, project_id, state, version FROM task WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids)
                .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                        rs.getLong("version"), null))
                .list());
    }

    /** The conditions of these ids, the id compared exactly as for {@link #tasks}. */
    public List<Found> conditions(Collection<String> ids) {
        return exactly(ids, jdbc.sql("SELECT id, project_id, state, version FROM release_condition WHERE id IN (:ids) "
                + "ORDER BY id").param("ids", ids)
                .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                        rs.getLong("version"), null))
                .list());
    }

    private static List<Found> exactly(Collection<String> ids, List<Found> fetched) {
        Set<String> asked = new HashSet<>(ids);
        return fetched.stream().filter(found -> asked.contains(found.id())).toList();
    }
}
