package org.pgledger.database.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * State changes of session grants as plain ANSI statements. The conditional UPDATE of {@link #markUsed} is both the
 * check and the lock: it keeps the row locked until the writer's transaction ends, so a revocation waits for the
 * write (the write commits before the revocation) and a committed revocation makes the check fail.
 */
@Repository
public class GrantStateRepository {

    private final JdbcClient jdbc;

    public GrantStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the author holds a live grant (not revoked, not expired) for the scope; stamps its last use. */
    public boolean markUsed(AuthorId author, ProjectId project, Scope scope, Instant now) {
        return jdbc.sql("UPDATE session_grant SET last_used_at = :now "
                + "WHERE author_id = :author AND project_id = :project AND scope = :scope "
                + "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now)")
                .param("now", UtcTime.toDb(now))
                .param("author", author.value())
                .param("project", project.value())
                .param("scope", scope.name())
                .update() == 1;
    }

    /** Revokes one live grant; false when it is already revoked or does not exist. */
    public boolean revoke(long id, Instant now) {
        return jdbc.sql("UPDATE session_grant SET revoked_at = :now, active_key = :key "
                + "WHERE id = :id AND revoked_at IS NULL")
                .param("now", UtcTime.toDb(now))
                .param("key", SessionGrant.revokedKey(id))
                .param("id", id)
                .update() == 1;
    }

    /**
     * Revokes the live grants of an author, optionally narrowed to a project and a scope, and returns how many.
     * Grants that were revoked meanwhile by someone else are not counted.
     */
    public int revokeLive(AuthorId author, ProjectId project, Scope scope, Instant now) {
        String sql = "SELECT id FROM session_grant WHERE author_id = :author AND revoked_at IS NULL";
        if (project != null) {
            sql += " AND project_id = :project";
        }
        if (scope != null) {
            sql += " AND scope = :scope";
        }
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("author", author.value());
        if (project != null) {
            spec = spec.param("project", project.value());
        }
        if (scope != null) {
            spec = spec.param("scope", scope.name());
        }
        int revoked = 0;
        for (Long id : spec.query(Long.class).list()) {
            if (revoke(id, now)) {
                revoked++;
            }
        }
        return revoked;
    }

    /** The live grants of an author at {@code now}, ordered by project and scope. */
    public List<SessionGrant> findLive(AuthorId author, Instant now) {
        return new ArrayList<>(jdbc.sql(LIVE_COLUMNS
                + "WHERE author_id = :author AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now) "
                + "ORDER BY project_id, scope")
                .param("author", author.value())
                .param("now", UtcTime.toDb(now))
                .query(GRANT_ROW)
                .list());
    }

    /** The live grants of every author at {@code now}, ordered by author, project and scope (the admin list). */
    public List<SessionGrant> findAllLive(Instant now) {
        return new ArrayList<>(jdbc.sql(LIVE_COLUMNS
                + "WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now) "
                + "ORDER BY author_id, project_id, scope")
                .param("now", UtcTime.toDb(now))
                .query(GRANT_ROW)
                .list());
    }

    private static final String LIVE_COLUMNS = "SELECT id, author_id, project_id, scope, granted_by, granted_at, "
            + "expires_at, revoked_at, last_used_at, note, active_key FROM session_grant ";

    private static final RowMapper<SessionGrant> GRANT_ROW = (rs, n) -> new SessionGrant(rs.getLong("id"),
            AuthorId.of(rs.getString("author_id")), ProjectId.of(rs.getString("project_id")),
            Scope.valueOf(rs.getString("scope")), AuthorId.of(rs.getString("granted_by")),
            UtcTime.read(rs, "granted_at"), UtcTime.read(rs, "expires_at"), UtcTime.read(rs, "revoked_at"),
            UtcTime.read(rs, "last_used_at"), rs.getString("note"), rs.getString("active_key"));
}
