package org.pgledger.database.repository;

import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.RequestId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Fills in the response of a reserved receipt, once, before the transaction of the write commits. */
@Repository
public class ReceiptCompletionRepository {

    private final JdbcClient jdbc;

    public ReceiptCompletionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the receipt existed without a response and now has one. */
    public boolean finish(RequestId requestId, JsonDocument response) {
        return jdbc.sql("UPDATE request_receipt SET response = :response WHERE request_id = :id AND response IS NULL")
                .param("response", response.text())
                .param("id", requestId.toString())
                .update() == 1;
    }
}
