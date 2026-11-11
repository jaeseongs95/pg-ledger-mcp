package org.pgledger.database.repository;

import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.MaxRows;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** History lists. The ids come in order from a keyset query; the rows are read through {@link HistoryRepository}. */
@Repository
public class HistoryQueryRepository {

    private final JdbcClient jdbc;

    public HistoryQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** History ids of one entity in id order, after {@code afterId} (use 0 for the first page). */
    public KeysetPage<Long> idsOfEntity(HistoryEntity entity, String entityId, long afterId, MaxRows maxRows) {
        return KeysetPage.of(jdbc.sql("SELECT id FROM history WHERE entity = :entity AND entity_id = :entityId "
                + "AND id > :after ORDER BY id")
                .param("entity", entity.name())
                .param("entityId", entityId)
                .param("after", afterId)
                .withMaxRows(maxRows.value() + 1)
                .query(Long.class).list(), maxRows.value());
    }
}
