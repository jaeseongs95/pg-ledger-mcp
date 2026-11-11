package org.pgledger.database.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The single {@code code_revision} row: it changes whenever the common codes change, so other processes notice. */
@Repository
public class CodeRevisionRepository {

    private final JdbcClient jdbc;

    public CodeRevisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long current() {
        return jdbc.sql("SELECT revision FROM code_revision WHERE id = 1").query(Long.class).single();
    }

    /** Adds one to the revision (inside the transaction that changed the codes). */
    public void bump() {
        if (jdbc.sql("UPDATE code_revision SET revision = revision + 1 WHERE id = 1").update() != 1) {
            throw new IllegalStateException("code_revision row missing");
        }
    }
}
