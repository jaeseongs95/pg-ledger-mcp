package org.pgledger.apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.TestApplication;
import org.pgledger.database.repository.LegacySourceRepository;
import org.pgledger.testing.database.TestDataSourceConfiguration;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.pgledger.testing.legacy.LegacyLedger;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * The application contexts that differ from the shared context of {@code @LedgerServiceTest}: another time zone of
 * the database session, a fresh installation without categories, and a restart of the application on the schema of
 * a running one. Each is a combination of configurations and properties of Spring Boot's
 * {@link ApplicationContextRunner}; the context is closed when the test body returns, and a context that got a schema
 * of its own ({@link TestSchemaInitializer}) drops it then.
 */
public final class LedgerTestRunners {

    private LedgerTestRunners() {
    }

    private static ApplicationContextRunner application(String... properties) {
        return new ApplicationContextRunner().withUserConfiguration(TestApplication.class)
                .withPropertyValues(LedgerTestSettings.QUIET_BANNER, LedgerTestSettings.QUIET_LOG,
                        LedgerTestSettings.NO_JMX)
                .withPropertyValues(properties);
    }

    /** A context on a schema of its own: the runner of a fresh installation. */
    private static ApplicationContextRunner freshSchema(String... properties) {
        return application(properties).withInitializer(new TestSchemaInitializer());
    }

    private static void run(ApplicationContextRunner runner, Consumer<LedgerAppContext> test) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            test.accept(new LedgerAppContext(context));
        });
    }

    /** A new schema, migrated, with the categories, whose database sessions run in {@code zone}. */
    public static void inZone(String zone, Consumer<LedgerAppContext> test) {
        run(freshSchema(TestDataSourceConfiguration.ZONE_PROPERTY + "=" + zone)
                .withUserConfiguration(LedgerTestInfrastructure.class, LedgerTestCategories.class), test);
    }

    /** A fresh installation: a new, migrated schema whose category list is still empty. */
    public static void withoutCategories(Consumer<LedgerAppContext> test) {
        run(freshSchema().withUserConfiguration(LedgerTestInfrastructure.class), test);
    }

    /**
     * A fresh installation (a new, migrated schema, no categories: the state the import needs) whose legacy source is
     * read through {@code source} (the read-only account of {@code legacy}, or a wrapper of it) in the schema of
     * {@code legacy}. The extra configurations replace beans of the context (a test that injects a failure into the write
     * side of the import).
     */
    public static void importing(LegacyLedger legacy, DataSource source, Consumer<LedgerAppContext> test,
            Class<?>... configurations) {
        run(freshSchema()
                .withUserConfiguration(LedgerTestInfrastructure.class)
                .withUserConfiguration(configurations)
                // the import service exists only in the command line tool, which sets this in its application.properties
                .withPropertyValues("ledger.cli.enabled=true")
                .withBean(LegacySourceRepository.class,
                        () -> new LegacySourceRepository(source, legacy.schema())), test);
    }

    /**
     * One more application on the data source, the schema and the clock of {@code running} (a restart of the server). The
     * schema is the one of the running context ({@code ledger.schema.name}); the restart gets no schema of its own, and
     * the schema is dropped with the running context.
     */
    public static void restartOf(LedgerAppContext running, Consumer<ApplicationContext> test) {
        String schema = running.bean(Environment.class)
                .getRequiredProperty(TestSchemaInitializer.SCHEMA_PROPERTY);
        application(TestSchemaInitializer.SCHEMA_PROPERTY + "=" + schema)
                // the data source belongs to the running context: the restart must not close it with itself
                .withBean("dataSource", DataSource.class, () -> running.bean(DataSource.class),
                        definition -> definition.setDestroyMethodName(""))
                .withBean("clock", Clock.class, running::clock)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    test.accept(context);
                });
    }
}
