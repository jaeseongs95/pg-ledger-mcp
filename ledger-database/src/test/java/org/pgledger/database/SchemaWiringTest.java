package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.database.schema.LedgerSchemaConfiguration;
import org.pgledger.database.schema.LedgerSchemaGuardConfiguration;
import org.pgledger.database.schema.SchemaGuard;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaVerifier;
import org.pgledger.database.schema.UtcSessionCheck;
import org.pgledger.database.support.Databases;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * SCH-031: the schema services are components found by the scan of their configuration (not {@code @Bean} methods), and
 * the startup guard exists only for an application that imports it: the command line tool imports the services and checks
 * on demand, the web applications import the guard and refuse to start on a schema that is not up to date. Tested with the
 * Spring Boot {@code ApplicationContextRunner} on the embedded H2.
 */
class SchemaWiringTest {

    private static ApplicationContextRunner runner(DataSource pool, Class<?> configuration) {
        return new ApplicationContextRunner().withUserConfiguration(configuration)
                .withBean(DataSource.class, () -> pool).withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("ledger.schema.long-text-type=" + Databases.LONG_TEXT,
                        "ledger.schema.require-utc=false");
    }

    @Test
    @DisplayName("SCH-031 the schema configuration makes the four services and no guard")
    void theServicesAreComponentsAndThereIsNoGuard() {
        try (HikariDataSource pool = new Databases().newDataSource()) {
            runner(pool, LedgerSchemaConfiguration.class).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(SchemaMigrator.class)
                        .hasSingleBean(GrantApplier.class).hasSingleBean(SchemaVerifier.class)
                        .hasSingleBean(UtcSessionCheck.class).doesNotHaveBean(SchemaGuard.class);
            });
        }
    }

    @Test
    @DisplayName("SCH-031 the guard configuration adds the guard: it stops the start on a database that was never migrated (SCHEMA_HISTORY_MISSING) and lets an up-to-date schema start")
    void theGuardStopsOrLetsTheApplicationStart() {
        try (HikariDataSource pool = new Databases().newDataSource()) {
            runner(pool, LedgerSchemaGuardConfiguration.class).run(context -> assertThat(context).hasFailed()
                    .getFailure().hasStackTraceContaining("SCHEMA_HISTORY_MISSING"));

            runner(pool, LedgerSchemaConfiguration.class).run(context -> context.getBean(SchemaMigrator.class).migrate());

            runner(pool, LedgerSchemaGuardConfiguration.class).run(context -> assertThat(context).hasNotFailed()
                    .hasSingleBean(SchemaGuard.class).hasSingleBean(SchemaVerifier.class));
        }
    }
}
