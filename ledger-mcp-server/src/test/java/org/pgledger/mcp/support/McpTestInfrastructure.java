package org.pgledger.mcp.support;

import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * What the MCP tests add to the server under test: the schema, built by the real {@code db/V*.sql} files and the
 * real migrator before the server registers its tools, and the defect categories of an installation that has been
 * set up (one active, one retired). The database itself is the embedded H2 that Spring Boot configures from
 * {@code spring.datasource.*}; there is no compatibility mode.
 */
@TestConfiguration(proxyBeanMethods = false)
public class McpTestInfrastructure {

    /** The long text type of the schema files on H2, the only per-DBMS difference of the DDL. */
    private static final String LONG_TEXT = "CLOB";

    /** Present once the schema is built. */
    public record SchemaMigrated(List<Integer> versions) {
    }

    /** Present once the categories are in the database. */
    public record CategoriesSeeded() {
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

    @Bean
    CategoriesSeeded categoriesSeeded(CommonCodeService codes, SchemaMigrated schema) {
        codes.set(new CodeCommand("DEFECT_CATEGORY", "ARC", "구조", "Architecture", null, null, 1, "ACTIVE"));
        codes.set(new CodeCommand("DEFECT_CATEGORY", "RETIRED", "폐기", "Retired", null, null, 9, "INACTIVE"));
        return new CategoriesSeeded();
    }
}
