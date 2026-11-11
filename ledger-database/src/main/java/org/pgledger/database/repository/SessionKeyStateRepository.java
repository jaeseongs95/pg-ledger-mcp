package org.pgledger.database.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.AuthorId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and closes the session keys as plain ANSI statements. A key is never deleted: closing sets {@code revoked_at}
 * and frees both unique keys ({@code revoked:<id>}), so the author can register another key and the same key can be
 * registered again later.
 */
@Repository
public class SessionKeyStateRepository {

    private static final String COLUMNS = "id, author_id, public_key, fingerprint, registered_by, registered_at, "
            + "revoked_at, note, active_key, fingerprint_key";

    private final JdbcClient jdbc;

    public SessionKeyStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The live key of the author, if it has one. */
    public Optional<SessionPublicKey> findLive(AuthorId author) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM session_key WHERE author_id = :author AND revoked_at IS NULL")
                .param("author", author.value())
                .query((rs, n) -> read(rs))
                .list().stream().findFirst();
    }

    /**
     * Checks that the key is still live and keeps it that way until the transaction ends: a conditional UPDATE that
     * changes nothing ({@code note = note}) but takes the row lock, exactly as {@link GrantStateRepository#markUsed}
     * does for a grant. {@link #close} of the same key then waits for the write that holds it (the write commits first),
     * and a close that committed before makes this return false, so a write that verified the key before a close
     * committed cannot commit after it. A plain SELECT would not do that: under snapshot reads the close would not wait
     * and the write would commit with a key that was already closed.
     *
     * <p>Must be called inside the transaction of the write.
     *
     * @return true when the key is live (and now locked), false when it is closed or does not exist
     */
    public boolean holdLive(long id) {
        return jdbc.sql("UPDATE session_key SET note = note WHERE id = :id AND revoked_at IS NULL")
                .param("id", id)
                .update() == 1;
    }

    /** The author that holds the key with this fingerprint now, if any. */
    public Optional<SessionPublicKey> findLiveByFingerprint(String fingerprint) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM session_key WHERE fingerprint = :fingerprint "
                + "AND revoked_at IS NULL")
                .param("fingerprint", fingerprint)
                .query((rs, n) -> read(rs))
                .list().stream().findFirst();
    }

    /** Every key row of the author, oldest first: the live one and the closed ones. */
    public List<SessionPublicKey> findHistory(AuthorId author) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM session_key WHERE author_id = :author ORDER BY id")
                .param("author", author.value())
                .query((rs, n) -> read(rs))
                .list();
    }

    /** Closes one live key; false when it is already closed or does not exist. */
    public boolean close(long id, Instant now) {
        return jdbc.sql("UPDATE session_key SET revoked_at = :now, active_key = :closed, fingerprint_key = :closed "
                + "WHERE id = :id AND revoked_at IS NULL")
                .param("now", UtcTime.toDb(now))
                .param("closed", SessionPublicKey.closedKey(id))
                .param("id", id)
                .update() == 1;
    }

    private static SessionPublicKey read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionPublicKey(rs.getLong("id"), AuthorId.of(rs.getString("author_id")),
                rs.getString("public_key"), rs.getString("fingerprint"), AuthorId.of(rs.getString("registered_by")),
                UtcTime.read(rs, "registered_at"), UtcTime.read(rs, "revoked_at"), rs.getString("note"),
                rs.getString("active_key"), rs.getString("fingerprint_key"));
    }
}
