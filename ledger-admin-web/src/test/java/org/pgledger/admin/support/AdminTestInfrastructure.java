package org.pgledger.admin.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.pgledger.testing.TestOwners;

/**
 * What the admin tests add to the application under test: the embedded H2 database in its default mode (no
 * compatibility mode; the product ships no driver, the test classpath has H2), its schema built by the real
 * {@code db/V*.sql} files and the real migrator, a clock that tests move, and the defect categories of an
 * installation that has been set up.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AdminTestInfrastructure {

    /** The long text type of the schema files on H2, the only per-DBMS difference of the DDL. */
    private static final String LONG_TEXT = "CLOB";

    /** Present once the schema is built. */
    public record SchemaMigrated(List<Integer> versions) {
    }

    /** Present once the categories are in the database. */
    public record CategoriesSeeded() {
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000");
        config.setMaximumPoolSize(10);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return new HikariDataSource(config);
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
    SchemaMigrated schemaMigrated(DataSource dataSource, Clock clock) {
        return new SchemaMigrated(new SchemaMigrator(dataSource, new PathMatchingResourcePatternResolver(),
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, LONG_TEXT, true, null,
                        Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)), clock).migrate());
    }

    @Bean
    CategoriesSeeded categoriesSeeded(CommonCodeService codes, SchemaMigrated schema) {
        codes.set(new CodeCommand("DEFECT_CATEGORY", "ARC", "구조", "Architecture", null, null, 1, "ACTIVE"), TestOwners.OWNER);
        codes.set(new CodeCommand("DEFECT_CATEGORY", "RETIRED", "폐기", "Retired", null, null, 9, "INACTIVE"), TestOwners.OWNER);
        return new CategoriesSeeded();
    }
}
