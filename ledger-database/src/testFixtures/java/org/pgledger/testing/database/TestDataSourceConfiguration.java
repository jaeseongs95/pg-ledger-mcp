package org.pgledger.testing.database;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The {@link DataSource} of a Spring test context: the test database of this JVM, every connection in the schema
 * {@code ledger.schema.name} ({@link TestSchemaInitializer}). The connections come from the one small pool of the test
 * JVM, so cached contexts do not each open a pool of their own. A context whose database sessions must run in another
 * time zone ({@value #ZONE_PROPERTY}) gets a pool of its own with that zone, closed with the context. This class lives
 * outside {@code org.pgledger.application} and the other component scan roots on purpose: a context takes it only
 * when a test imports it.
 */
@Configuration(proxyBeanMethods = false)
public class TestDataSourceConfiguration {

    /** The time zone of the database sessions of a context; UTC when absent. */
    public static final String ZONE_PROPERTY = "ledger.test.session-zone";

    @Bean
    DataSource dataSource(Environment environment) {
        String schema = environment.getRequiredProperty(TestSchemaInitializer.SCHEMA_PROPERTY);
        String zone = environment.getProperty(ZONE_PROPERTY, "UTC");
        TestDatabase database = TestDatabase.shared();
        if ("UTC".equals(zone)) {
            return TestDatabase.scoped(database.pool(), schema);
        }
        HikariDataSource own = database.newPool(zone);
        return new ScopedDataSource(own, schema, own);
    }
}
