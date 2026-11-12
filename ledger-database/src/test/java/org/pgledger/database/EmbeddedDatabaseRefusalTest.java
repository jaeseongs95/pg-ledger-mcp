package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.database.driver.DriverDataSources;
import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistrar;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.EmbeddedDatabaseRefusal;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.pgledger.database.driver.RegisteredDriver;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.testing.OwnerOnlyFiles;
import org.pgledger.testing.database.PostgresDriver;

/**
 * SET-030 and SET-032: the embedded database H2 is never the ledger database (user: 「dbms 설치가 전제라니까?」). The refusal
 * sits in the check that the admin web and the command line tool share ({@link DriverRegistrar}), before any jar is opened,
 * and again where the pool is made ({@link DriverDataSources}), so a registration written by hand does not get around it.
 */
class EmbeddedDatabaseRefusalTest {

    private static final DriverLoader LOADER = new DriverLoader();
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private static void assertRefused(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InfrastructureException.class, refused -> {
            assertThat(refused.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
            assertThat(refused.args()).containsExactly(EmbeddedDatabaseRefusal.REASON);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "jdbc:h2:mem:ledger", "JDBC:H2:file:/tmp/ledger", " jdbc:h2:tcp://localhost/ledger;MODE=PostgreSQL" })
    @DisplayName("SET-030 a JDBC URL of H2 (any case, any mode, white space around) is refused with the one reason")
    void anH2UrlIsRefused(String url) {
        assertRefused(() -> EmbeddedDatabaseRefusal.require(url, null));
        // the shared check of the admin web and the CLI decides before it looks at the jar: this jar does not exist
        DriverRegistrar registrar = new DriverRegistrar(LOADER, url, Path.of("no-such-directory"));
        assertRefused(() -> registrar.check("/no/such/driver.jar", PostgresDriver.CLASS_NAME, NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = { "org.h2.Driver", "org.h2.jdbcx.JdbcDataSource", " org.h2.Driver " })
    @DisplayName("SET-030 a driver class of the H2 package is refused with the one reason, before the jar is opened")
    void anH2ClassIsRefused(String className) {
        assertRefused(() -> EmbeddedDatabaseRefusal.require("jdbc:postgresql://db.example.org/ledger", className));
        DriverRegistrar registrar = new DriverRegistrar(LOADER, "jdbc:postgresql://db.example.org/ledger", Path.of("no-such-directory"));
        assertRefused(() -> registrar.check("/no/such/driver.jar", className, NOW));
    }

    @Test
    @DisplayName("SET-030 other URLs and classes, and absent values, are not refused here (the other checks decide as before)")
    void otherValuesPass() {
        assertThatCode(() -> EmbeddedDatabaseRefusal.require("jdbc:postgresql://db.example.org/ledger", PostgresDriver.CLASS_NAME))
                .doesNotThrowAnyException();
        assertThatCode(() -> EmbeddedDatabaseRefusal.require(null, null)).doesNotThrowAnyException();
        // a jar that is not there is still the refusal of the jar, not the one of H2
        DriverRegistrar registrar = new DriverRegistrar(LOADER, "jdbc:postgresql://db.example.org/ledger", Path.of("no-such-directory"));
        assertThatThrownBy(() -> registrar.check("/no/such/driver.jar", PostgresDriver.CLASS_NAME, NOW))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        refused -> assertThat(refused.args()).containsExactly(DriverRegistrar.REFUSED_REASON));
    }

    @Test
    @DisplayName("SET-032 the pool is not made for an H2 URL either: a registration written by hand cannot make H2 the ledger database")
    void thePoolIsNotMadeForAnH2Url() {
        Path jar = OwnerOnlyFiles.copyToNewDirectory(PostgresDriver.jar());
        DriverRegistration registration = LOADER.describe(jar, PostgresDriver.CLASS_NAME, "jdbc:postgresql://127.0.0.1:1/x", NOW);
        LedgerJdbcProperties properties = new LedgerJdbcProperties("jdbc:h2:mem:ledger", "sa", null, 1, registration);

        try (RegisteredDriver registered = LOADER.load(registration)) {
            assertRefused(() -> DriverDataSources.create(registered, properties, null));
        }
    }
}
