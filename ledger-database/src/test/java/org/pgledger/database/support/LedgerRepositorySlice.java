package org.pgledger.database.support;

import java.time.Clock;
import javax.sql.DataSource;
import org.pgledger.database.jdbc.LedgerJdbcConfiguration;
import org.pgledger.database.schema.LedgerSchemaConfiguration;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The slice of the repository tests of the database module ({@link LedgerDataJdbcTest}, principle 7.2): the ledger's Spring Data
 * JDBC configuration with its repositories, the schema services, a clock and a JDBC client, and the test database of this JVM
 * (the shared pool, every connection in the schema of the context) behind a {@link RecordingDataSource}, which a test can ask which
 * statements a call sent. The schema is built by the real {@code db/V*.sql} files when the context starts.
 */
@Configuration(proxyBeanMethods = false)
@Import({LedgerJdbcConfiguration.class, LedgerSchemaConfiguration.class})
public class LedgerRepositorySlice {

    @Bean
    RecordingDataSource dataSource(Environment environment) {
        String schema = environment.getRequiredProperty(TestSchemaInitializer.SCHEMA_PROPERTY);
        return new RecordingDataSource(TestDatabase.scoped(TestDatabase.shared().pool(), schema));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    /** Builds the schema once, when the context starts (DDL is not part of the rolled back test transaction). */
    @Bean
    SmartInitializingSingleton schemaIsMigrated(SchemaMigrator migrator) {
        return migrator::migrate;
    }
}
