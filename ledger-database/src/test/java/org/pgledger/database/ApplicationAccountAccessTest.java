package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.schema.ApplicationAccountAccess;
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.database.TestSchema;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * SET-021: whether the application account can use the schema, asked over a connection of that account (the login of
 * {@code ledger.test.app-user} of the test PostgreSQL) in a schema of its own that the owner account migrated. True only when
 * the account can read the schema history table as the servers do at their start; false, without an exception, in every other
 * case: no right to use the schema (the step of the operator), no table grants, no connection, no account.
 */
@DatabaseTest
class ApplicationAccountAccessTest {

    private static final ResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();
    private static final SchemaProperties PROPERTIES = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION,
            Databases.LONG_TEXT, true, null, Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT));

    /** A migrated schema of its own; the application account has no right on it yet. */
    private static TestSchema migrated(Databases databases) {
        TestSchema schema = databases.newSchema();
        new SchemaMigrator(schema.dataSource(), RESOLVER, PROPERTIES, Clock.systemUTC()).migrate();
        return schema;
    }

    private static String appAccount() {
        return TestDatabase.shared().settings().appUser();
    }

    private static DataSource applicationConnections(TestSchema schema) {
        return TestDatabase.scoped(TestDatabase.shared().appPool(), schema.name());
    }

    private static ApplicationAccountAccess access(TestSchema schema) {
        return new ApplicationAccountAccess(appAccount(), applicationConnections(schema), RESOLVER, PROPERTIES);
    }

    @Test
    @DisplayName("SET-021 without the right to use the schema, or with it but without the table grants, the answer is no; with both it is yes")
    void theSchemaAndTheGrantsAreBothNeeded(Databases databases) {
        TestSchema schema = migrated(databases);

        assertThat(access(schema).canUseSchema()).as("nothing was done for the account").isFalse();

        schema.allowUsage(appAccount());
        assertThat(access(schema).canUseSchema()).as("it may use the schema but read no table").isFalse();

        new GrantApplier(schema.dataSource(), PROPERTIES).apply(appAccount());
        assertThat(access(schema).canUseSchema()).as("the step of the operator and the grants are done").isTrue();
        assertThat(access(schema).account()).isEqualTo(appAccount());
    }

    @Test
    @DisplayName("SET-021 an account that cannot connect, and no account at all, are no (not an exception)")
    void noConnectionAndNoAccount(Databases databases) {
        TestSchema schema = migrated(databases);
        DataSource refusing = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("authentication failed");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("authentication failed");
            }
        };

        assertThat(new ApplicationAccountAccess("ledger_app", refusing, RESOLVER, PROPERTIES).canUseSchema()).isFalse();
        ApplicationAccountAccess unconfigured = ApplicationAccountAccess.notConfigured(RESOLVER, PROPERTIES);
        assertThat(unconfigured.canUseSchema()).isFalse();
        assertThat(unconfigured.account()).isEqualTo("(not configured)");
        assertThat(new ApplicationAccountAccess(" ", applicationConnections(schema), RESOLVER, PROPERTIES).canUseSchema()).isFalse();
    }
}
