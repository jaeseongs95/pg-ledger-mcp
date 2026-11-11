package org.pgledger.database.repository;

import org.pgledger.domain.value.DefectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Serialises recording a case with merging the same defect. The UPDATE locks the defect row until the caller's
 * transaction ends and affects no row when the defect is MERGED (the case is then refused). The counter is a
 * lock device: it does not touch {@code version} or {@code updated_at}, so a concurrent edit with an expected
 * version still succeeds.
 */
@Repository
public class DefectCaseLockRepository {

    private final JdbcClient jdbc;

    public DefectCaseLockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the defect exists and is not merged; its row is locked until the transaction ends. */
    public boolean lockForCase(DefectId defect) {
        return jdbc.sql("UPDATE defect SET case_seq = case_seq + 1 WHERE id = :id AND status <> 'MERGED'")
                .param("id", defect.value()).update() == 1;
    }
}
