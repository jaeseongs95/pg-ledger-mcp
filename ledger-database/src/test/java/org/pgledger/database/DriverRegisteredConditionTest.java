package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.driver.DriverRegisteredCondition;
import org.pgledger.domain.value.Sha256;
import org.pgledger.testing.OwnerOnlyFiles;
import org.pgledger.testing.database.PostgresDriver;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * DRV-050 .. DRV-054: the one condition of the admin web and the command line tool, tested with the Spring Boot
 * {@code ApplicationContextRunner} (principle 7.2: the condition of a bean is tested with the runner): a bean behind it
 * exists only with a registered driver that the loader accepts.
 */
class DriverRegisteredConditionTest {

    /** The marker of the database: the bean that exists only when the condition holds. */
    @Configuration(proxyBeanMethods = false)
    @Conditional(DriverRegisteredCondition.class)
    static class Guarded {
        @Bean
        String database() {
            return "database";
        }
    }

    /** The condition of a program that uses the database only for some commands: the hook of the subclass. */
    static final class NeverNeedsTheDatabase extends DriverRegisteredCondition {
        @Override
        protected boolean needsDatabase(ConditionContext context) {
            return false;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(NeverNeedsTheDatabase.class)
    static class GuardedByAProgramThatDoesNotNeedIt {
        @Bean
        String database() {
            return "database";
        }
    }

    private static final Path DRIVER_JAR = OwnerOnlyFiles.copyToNewDirectory(PostgresDriver.jar());

    private static Path locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha() {
        try {
            return Sha256.ofBytes(Files.readAllBytes(DRIVER_JAR)).hex();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String slashed(Path path) {
        return path.toString().replace('\\', '/');
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Guarded.class);

    @Test
    @DisplayName("DRV-050 nothing registered: the database is not made (a new installation is a normal state)")
    void nothingRegistered() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("database"));
    }

    @Test
    @DisplayName("DRV-052 a registered driver that the loader accepts: the database is made")
    void aUsableRegistration() {
        runner.withPropertyValues("ledger.jdbc.driver.jar=" + slashed(DRIVER_JAR),
                "ledger.jdbc.driver.class-name=" + PostgresDriver.CLASS_NAME, "ledger.jdbc.driver.sha256=" + sha())
                .run(context -> assertThat(context).hasNotFailed().hasBean("database"));
    }

    @Test
    @DisplayName("DRV-053 a registration the loader refuses (the jar is not the one registered, or is missing, or the class is not in it): the database is not made, and the context still starts")
    void anUnusableRegistration() {
        runner.withPropertyValues("ledger.jdbc.driver.jar=" + slashed(DRIVER_JAR),
                "ledger.jdbc.driver.class-name=" + PostgresDriver.CLASS_NAME, "ledger.jdbc.driver.sha256=" + "0".repeat(64))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("database"));
        runner.withPropertyValues("ledger.jdbc.driver.jar=" + slashed(DRIVER_JAR.resolveSibling("missing.jar")),
                "ledger.jdbc.driver.class-name=" + PostgresDriver.CLASS_NAME, "ledger.jdbc.driver.sha256=" + sha())
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("database"));
        runner.withPropertyValues("ledger.jdbc.driver.jar=" + slashed(DRIVER_JAR),
                "ledger.jdbc.driver.class-name=org.NoSuch", "ledger.jdbc.driver.sha256=" + sha())
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("database"));
    }

    @Test
    @DisplayName("DRV-054 a program that does not need the database for what it runs gets none, even with a usable registration")
    void theProgramMayNotNeedTheDatabase() {
        ApplicationContextRunner program = new ApplicationContextRunner()
                .withUserConfiguration(GuardedByAProgramThatDoesNotNeedIt.class);
        program.withPropertyValues("ledger.jdbc.driver.jar=" + slashed(DRIVER_JAR),
                "ledger.jdbc.driver.class-name=" + PostgresDriver.CLASS_NAME, "ledger.jdbc.driver.sha256=" + sha())
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("database"));
    }
}
