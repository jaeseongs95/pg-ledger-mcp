package org.pgledger.mcp.support;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.testing.database.TestDataSourceConfiguration;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The schema of the test database, built by the real {@code db/V*.sql} files and the real migrator before the server
 * registers its tools. The database itself is the PostgreSQL of the test PC: the {@code DataSource} comes from
 * {@link TestDataSourceConfiguration} (the shared pool, every connection in the schema {@code ledger.schema.name} that
 * a {@code TestSchemaInitializer} gave the context), and the product migrator creates that schema and its tables. The
 * installation of the categories is not part of it ({@link McpTestInfrastructure}), so a context can start the way a
 * fresh installation does.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestDataSourceConfiguration.class)
public class McpTestSchema {

    /** Present once the schema is built. */
    public record SchemaMigrated(List<Integer> versions) {
    }

    @Bean
    ProjectNumbers projectNumbers() {
        return new ProjectNumbers();
    }

    /** The key pairs of the test authors (the hook and the owner of the tests, see {@link KeyRing}). */
    @Bean
    KeyRing keyRing(SessionKeyService owner, Clock clock) {
        return new KeyRing(owner, clock);
    }

    @Bean
    SchemaMigrated schemaMigrated(DataSource dataSource, Clock clock, Environment environment) {
        return new SchemaMigrated(new SchemaMigrator(dataSource, new PathMatchingResourcePatternResolver(),
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, SchemaProperties.DEFAULT_LONG_TEXT_TYPE, true,
                        environment.getRequiredProperty(TestSchemaInitializer.SCHEMA_PROPERTY),
                        Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), clock).migrate());
    }
}
