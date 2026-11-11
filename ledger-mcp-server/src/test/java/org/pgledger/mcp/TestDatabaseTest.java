package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.support.McpServerTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Section 1.4a: the tests of this module run on the embedded H2 in its default mode, on the real schema files. */
@McpServerTest
class TestDatabaseTest {

    @Test
    @DisplayName("1.4a: the test database is the embedded H2 in its default mode (no compatibility mode)")
    void theDatabaseIsH2InTheDefaultMode(@Autowired DataSource dataSource) {
        JdbcClient database = JdbcClient.create(dataSource);

        String mode = database.sql("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'")
                .query(String.class).single();
        String product = database.sql("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS "
                + "WHERE SETTING_NAME = 'info.VERSION'").query(String.class).single();

        assertThat(mode).isEqualTo("REGULAR");
        assertThat(product).isNotBlank();
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
