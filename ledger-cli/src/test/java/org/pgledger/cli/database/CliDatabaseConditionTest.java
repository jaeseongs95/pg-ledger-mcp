package org.pgledger.cli.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.support.PostgresDriverJar;
import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistrar;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.testing.OwnerOnlyFiles;
import org.pgledger.testing.database.TestDatabaseSettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.CommandLinePropertySource;

/**
 * CLI-PRN-08 (plan 5): the condition that gives the CLI its database beans, in an {@code ApplicationContextRunner} with a bean
 * under the condition (the three configurations of the tool that hold database beans carry the same annotation). The
 * registration is a real one: the PostgreSQL driver jar of the test database, checked by the registrar of the product.
 * The first operand is what Boot's command line property source calls {@code nonOptionArgs}.
 */
class CliDatabaseConditionTest {

    @Configuration(proxyBeanMethods = false)
    @Conditional(CliDatabaseCondition.class)
    static class UnderTheCondition {

        @Bean
        String databaseBean() {
            return "made";
        }
    }

    @TempDir
    Path directory;

    private Path jar;
    private DriverRegistration registration;

    @BeforeEach
    void register() throws Exception {
        Path drivers = OwnerOnlyFiles.restrict(Files.createDirectories(directory.toRealPath().resolve("drivers"))).toRealPath();
        jar = OwnerOnlyFiles.copy(PostgresDriverJar.path(), drivers.resolve("postgresql.jar")).toRealPath();
        registration = new DriverRegistrar(new DriverLoader(), TestDatabaseSettings.fromSystemProperties().url(), drivers)
                .check(jar.toString(), PostgresDriverJar.DRIVER_CLASS, Instant.now());
    }

    private ApplicationContextRunner runner(String command) {
        return new ApplicationContextRunner().withUserConfiguration(UnderTheCondition.class)
                .withPropertyValues(CommandLinePropertySource.DEFAULT_NON_OPTION_ARGS_PROPERTY_NAME + "=" + command);
    }

    private ApplicationContextRunner registered(String command) {
        return runner(command).withPropertyValues("ledger.jdbc.driver.jar=" + registration.jar().toString().replace('\\', '/'),
                "ledger.jdbc.driver.class-name=" + registration.className(), "ledger.jdbc.driver.sha256=" + registration.sha256(),
                "ledger.jdbc.driver.registered-at=" + registration.registeredAt());
    }

    @Test
    @DisplayName("CLI-PRN-08 no driver registered: no database bean, whichever command is run")
    void noDriver() {
        runner("migrate").run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("databaseBean"));
        runner("project-add").run(context -> assertThat(context).doesNotHaveBean("databaseBean"));
    }

    @Test
    @DisplayName("CLI-PRN-08 the embedded profile (a test brings its own DataSource): the beans exist without a registration")
    void embeddedProfile() {
        runner("migrate").withPropertyValues("spring.profiles.active=embedded")
                .run(context -> assertThat(context).hasNotFailed().hasBean("databaseBean"));
    }

    @Test
    @DisplayName("CLI-PRN-08 a usable registration and a command that uses the database: the bean exists")
    void registeredAndDatabaseCommand() {
        registered("migrate").run(context -> assertThat(context).hasNotFailed().hasBean("databaseBean"));
        registered("grant").run(context -> assertThat(context).hasBean("databaseBean"));
    }

    @Test
    @DisplayName("CLI-PRN-08 a usable registration and a command that does not use the database (secret-set, token-generate, render-deploy ...): no bean, so such a command runs when the database is down")
    void registeredAndCommandWithoutDatabase() {
        registered("secret-set").run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("databaseBean"));
        registered("token-generate").run(context -> assertThat(context).doesNotHaveBean("databaseBean"));
        registered("render-deploy").run(context -> assertThat(context).doesNotHaveBean("databaseBean"));
        registered("seal-host").run(context -> assertThat(context).doesNotHaveBean("databaseBean"));
    }

    @Test
    @DisplayName("CLI-PRN-08 a registered jar that was changed after the registration (another digest): no bean and the context still starts (the warning names the problem)")
    void changedJar() throws Exception {
        Files.write(jar, new byte[] {0}, StandardOpenOption.APPEND);

        registered("migrate").run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("databaseBean"));
    }
}
