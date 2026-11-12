package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.support.McpServerTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Section 1.4a: the tests of this module run on the PostgreSQL of the test PC, on the real schema files. */
@McpServerTest
class TestDatabaseTest {

    @Test
    @DisplayName("TDB-001: the tests really run on PostgreSQL 18 or later, in the database ledger_test, session in UTC")
    void theDatabaseIsPostgreSql(@Autowired DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();

            assertThat(meta.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(meta.getDatabaseMajorVersion()).isGreaterThanOrEqualTo(18);
            assertThat(connection.getCatalog()).isEqualTo("ledger_test");
        }
        String zone = JdbcClient.create(dataSource).sql("SHOW TIME ZONE").query(String.class).single();
        assertThat(zone).isEqualTo("UTC");
    }

    @Test
    @DisplayName("1.4a: the schema was built by the real V files, every attempt succeeded")
    void theSchemaIsBuiltByTheRealFiles(@Autowired DataSource dataSource) {
        long failed = JdbcClient.create(dataSource).sql("SELECT COUNT(*) FROM schema_history WHERE outcome <> 'SUCCESS'")
                .query(Long.class).single();
        long built = JdbcClient.create(dataSource).sql("SELECT COUNT(*) FROM schema_history WHERE outcome = 'SUCCESS'")
                .query(Long.class).single();

        assertThat(failed).isZero();
        assertThat(built).isPositive();
    }
}
