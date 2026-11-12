package org.pgledger.database.repository;

import java.util.List;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.value.ProjectCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counter based id allocation. The UPDATE takes the row lock and keeps it until the caller's transaction ends, so
 * the SELECT that follows reads the number this transaction issued and a concurrent allocation waits. A rolled
 * back transaction gives its number back. Every method must run inside a transaction of the caller:
 * {@link Propagation#MANDATORY} makes Spring refuse a call without one (IllegalTransactionStateException), instead of
 * the allocator checking for it by hand.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class IdAllocator {

    private final JdbcClient jdbc;

    public IdAllocator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The next defect number of a project (the first is 1). */
    public long nextDefectNo(ProjectCode code) {
        advance("UPDATE project SET next_defect_no = next_defect_no + 1 WHERE code = :code", code);
        return jdbc.sql("SELECT next_defect_no FROM project WHERE code = :code").param("code", code.value())
                .query(Long.class).single();
    }

    /** The next task number of a project (the first is 1). */
    public long nextTaskNo(ProjectCode code) {
        advance("UPDATE project SET next_task_no = next_task_no + 1 WHERE code = :code", code);
        return jdbc.sql("SELECT next_task_no FROM project WHERE code = :code").param("code", code.value())
                .query(Long.class).single();
    }

    /** After an explicit defect id was used: the counter never lags behind the highest number in use. */
    public void raiseDefectNo(ProjectCode code, long atLeast) {
        jdbc.sql("UPDATE project SET next_defect_no = :n WHERE code = :code AND next_defect_no < :n")
                .param("n", atLeast).param("code", code.value()).update();
    }

    /** After an explicit task id was used: the counter never lags behind the highest number in use. */
    public void raiseTaskNo(ProjectCode code, long atLeast) {
        jdbc.sql("UPDATE project SET next_task_no = :n WHERE code = :code AND next_task_no < :n")
                .param("n", atLeast).param("code", code.value()).update();
    }

    /** The next number of a surrogate key sequence (history, fix, case, grant). */
    public long next(Counter counter) {
        int rows = jdbc.sql("UPDATE id_counter SET issued_no = issued_no + 1 WHERE name = :name")
                .param("name", counter.name()).update();
        if (rows != 1) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("counter row missing: " + counter));
        }
        return jdbc.sql("SELECT issued_no FROM id_counter WHERE name = :name").param("name", counter.name())
                .query(Long.class).single();
    }

    /**
     * After rows with explicit surrogate ids were copied in (the legacy import): the counter is at least {@code atLeast}, so
     * the next number issued is above every id in use. A counter that is already higher is left alone.
     */
    public void raise(Counter counter, long atLeast) {
        int rows = jdbc.sql("UPDATE id_counter SET issued_no = :n WHERE name = :name AND issued_no < :n")
                .param("n", atLeast).param("name", counter.name()).update();
        if (rows > 1) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("counter row duplicated: " + counter));
        }
    }

    private void advance(String sql, ProjectCode code) {
        if (jdbc.sql(sql).param("code", code.value()).update() != 1) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of("project", code.value()));
        }
    }
}
