package org.pgledger.admin.support;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.pgledger.testing.TestOwners;
import org.pgledger.testing.database.TestDataSourceConfiguration;
import org.pgledger.testing.database.TestSchemaInitializer;

/**
 * What the admin tests add to the application under test: the PostgreSQL of the test PC (the {@code DataSource} of
 * {@link TestDataSourceConfiguration}: the shared pool of the test JVM, every connection in the schema
 * {@code ledger.schema.name} that a {@code TestSchemaInitializer} gave the context), its schema built by the real
 * {@code db/V*.sql} files and the real migrator, a clock that tests move, and the defect categories of an
 * installation that has been set up. The product ships no driver; the test class path has the PostgreSQL driver.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestDataSourceConfiguration.class)
public class AdminTestInfrastructure {

    /** Present once the schema is built. */
    public record SchemaMigrated(List<Integer> versions) {
    }

    /** Present once the categories are in the database. */
    public record CategoriesSeeded() {
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    /** The monotonic time source of the lock after failed attempts: it moves with the test clock, not with the system. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "admin.test.moving-ticker", havingValue = "true", matchIfMissing = true)
    com.github.benmanes.caffeine.cache.Ticker testTicker(MutableClock clock) {
        return clock.ticker();
    }

    @Bean
    SchemaMigrated schemaMigrated(DataSource dataSource, Clock clock, Environment environment) {
        return new SchemaMigrated(new SchemaMigrator(dataSource, new PathMatchingResourcePatternResolver(),
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, SchemaProperties.DEFAULT_LONG_TEXT_TYPE, true,
                        environment.getRequiredProperty(TestSchemaInitializer.SCHEMA_PROPERTY),
                        Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), clock).migrate());
    }

    @Bean
    CategoriesSeeded categoriesSeeded(CommonCodeService codes, SchemaMigrated schema) {
        codes.set(new CodeCommand("DEFECT_CATEGORY", "ARC", "구조", "Architecture", null, null, 1, "ACTIVE"), TestOwners.OWNER);
        codes.set(new CodeCommand("DEFECT_CATEGORY", "RETIRED", "폐기", "Retired", null, null, 9, "INACTIVE"), TestOwners.OWNER);
        return new CategoriesSeeded();
    }
}
