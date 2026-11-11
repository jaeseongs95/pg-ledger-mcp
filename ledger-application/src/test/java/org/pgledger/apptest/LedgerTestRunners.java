package org.pgledger.apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.TestApplication;
import org.pgledger.database.repository.LegacySourceRepository;
import org.pgledger.testing.legacy.LegacyLedger;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

/**
 * The application contexts that differ from the shared context of {@code @LedgerServiceTest}: another time zone of
 * the database session, a fresh installation without categories, and a restart of the application on the database of
 * a running one. Each is a combination of configurations and properties of Spring Boot's
 * {@link ApplicationContextRunner}; the context is closed when the test body returns.
 */
public final class LedgerTestRunners {

    private LedgerTestRunners() {
    }

    private static ApplicationContextRunner application(String... properties) {
        return new ApplicationContextRunner().withUserConfiguration(TestApplication.class)
                .withPropertyValues(LedgerTestSettings.POOL, LedgerTestSettings.QUIET_BANNER,
                        LedgerTestSettings.QUIET_LOG, LedgerTestSettings.NO_JMX)
                .withPropertyValues(properties);
    }

    private static void run(ApplicationContextRunner runner, Consumer<LedgerAppContext> test) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            test.accept(new LedgerAppContext(context));
        });
    }

    /** A new database, migrated, with the categories, whose sessions run in {@code zone}. */
    public static void inZone(String zone, Consumer<LedgerAppContext> test) {
        run(application(LedgerTestSettings.newUrl(), LedgerTestSettings.session(zone))
                .withUserConfiguration(LedgerTestInfrastructure.class, LedgerTestCategories.class), test);
    }

    /** A fresh installation: a new, migrated database whose category list is still empty. */
    public static void withoutCategories(Consumer<LedgerAppContext> test) {
        run(application(LedgerTestSettings.newUrl(), LedgerTestSettings.session("UTC"))
                .withUserConfiguration(LedgerTestInfrastructure.class), test);
    }

    /**
     * A fresh installation (a new, migrated database, no categories: the state the import needs) whose legacy source is
     * read through {@code source} (the read-only account of a {@code LegacyLedger}, or a wrapper of it). The extra
     * configurations replace beans of the context (a test that injects a failure into the write side of the import).
     */
    public static void importing(DataSource source, Consumer<LedgerAppContext> test, Class<?>... configurations) {
        run(application(LedgerTestSettings.newUrl(), LedgerTestSettings.session("UTC"))
                .withUserConfiguration(LedgerTestInfrastructure.class)
                .withUserConfiguration(configurations)
                // the import service exists only in the command line tool, which sets this in its application.properties
                .withPropertyValues("ledger.cli.enabled=true")
                .withBean(LegacySourceRepository.class,
                        () -> new LegacySourceRepository(source, LegacyLedger.SCHEMA)), test);
    }

    /** One more application on the database and the clock of {@code running} (a restart of the server). */
    public static void restartOf(LedgerAppContext running, Consumer<ApplicationContext> test) {
        application().withBean("dataSource", DataSource.class, () -> running.bean(DataSource.class))
                .withBean("clock", Clock.class, running::clock)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    test.accept(context);
                });
    }
}
