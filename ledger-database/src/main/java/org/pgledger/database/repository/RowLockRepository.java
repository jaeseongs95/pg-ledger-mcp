package org.pgledger.database.repository;

import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Locks the row of a defect, task or condition until the caller's transaction ends, with a plain ANSI UPDATE that
 * writes the version column back to itself (no {@code FOR UPDATE}). The row read after the lock is exactly the row
 * the following version-checked update replaces, which is what {@code before_data} records; a concurrent writer
 * waits here and sees the changed row afterwards. The counters {@code case_seq} and {@code version} stay as they
 * are.
 */
@Repository
public class RowLockRepository {

    private final JdbcClient jdbc;

    public RowLockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the defect exists (and is now locked). */
    public boolean lock(DefectId id) {
        return jdbc.sql("UPDATE defect SET version = version WHERE id = :id").param("id", id.value()).update() == 1;
    }

    /** True when the task exists (and is now locked). */
    public boolean lock(TaskId id) {
        return jdbc.sql("UPDATE task SET version = version WHERE id = :id").param("id", id.value()).update() == 1;
    }

    /** True when the condition exists (and is now locked). */
    public boolean lock(ConditionId id) {
        return jdbc.sql("UPDATE release_condition SET version = version WHERE id = :id").param("id", id.value())
                .update() == 1;
    }
}
