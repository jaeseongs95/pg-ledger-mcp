package org.pgledger.mcp.support;

import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The schema of the test database, built by the real {@code db/V*.sql} files and the real migrator before the server
 * registers its tools. The database itself is the embedded H2 that Spring Boot configures from
 * {@code spring.datasource.*}; there is no compatibility mode. The installation of the categories is not part of
 * it ({@link McpTestInfrastructure}), so a context can start the way a fresh installation does.
 */
@TestConfiguration(proxyBeanMethods = false)
public class McpTestSchema {

    /** The long text type of the schema files on H2, the only per-DBMS difference of the DDL. */
    private static final String LONG_TEXT = "CLOB";

    /** Present once the schema is built. */
    public record SchemaMigrated(List<Integer> versions) {
    }

    @Bean
    ProjectNumbers projectNumbers() {
        return new ProjectNumbers();
    }

    @Bean
    SchemaMigrated schemaMigrated(DataSource dataSource, Clock clock) {
        return new SchemaMigrated(new SchemaMigrator(dataSource, new PathMatchingResourcePatternResolver(),
                new SchemaProperties(null, LONG_TEXT, true, null), clock).migrate());
    }
}
