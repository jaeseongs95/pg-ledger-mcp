package org.pgledger.database.repository;

import java.util.Collection;
import java.util.List;
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

    public List<Found> tasks(Collection<String> ids) {
        return jdbc.sql("SELECT id, project_id, state, version FROM task WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids)
                .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                        rs.getLong("version"), null))
                .list();
    }

    public List<Found> conditions(Collection<String> ids) {
        return jdbc.sql("SELECT id, project_id, state, version FROM release_condition WHERE id IN (:ids) "
                + "ORDER BY id").param("ids", ids)
                .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                        rs.getLong("version"), null))
                .list();
    }
}
