package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.PostgresDriverJar;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.database.driver.EmbeddedDatabaseRefusal;
import org.pgledger.testing.OwnerOnlyFiles;
import org.pgledger.testing.database.TestDatabaseSettings;

/**
 * SET-031: {@code driver-register} refuses the embedded database H2 as the ledger database (user: 「dbms 설치가 전제라니까?」),
 * through the check the admin web shares ({@code DriverRegistrar}): a JDBC URL {@code jdbc:h2:} or a driver class of the H2
 * package is {@code DRIVER_INVALID} with the one reason, and the registration file is not written. The same command for the
 * driver and the URL of the test database is accepted, so the refusal is not a refusal of everything.
 */
@ExtendWith(CliExtension.class)
class EmbeddedRefusalTest {

    @TempDir
    Path directory;

    private Path root;
    private Path drivers;
    private Path file;
    private Path jar;

    @BeforeEach
    void layout() throws Exception {
        root = directory.toRealPath();
        drivers = OwnerOnlyFiles.restrict(Files.createDirectories(root.resolve("drivers"))).toRealPath();
        file = root.resolve("config").resolve("jdbc-driver.yaml");
        jar = OwnerOnlyFiles.copy(PostgresDriverJar.path(), drivers.resolve("postgresql.jar")).toRealPath();
    }

    private static String slashed(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private Outcome register(Cli cli, String url, String className) {
        return cli.withArguments("--ledger.cli.driver.file=" + slashed(file), "--ledger.cli.driver.directory=" + slashed(drivers),
                "--ledger.jdbc.url=" + url, "--ledger.jdbc.password=unused").asProcessUser("alice").run(root.resolve("secrets"), null,
                        new RecordingOutput(), "driver-register", "--jar=" + jar, "--class=" + className);
    }

    @ParameterizedTest
    @ValueSource(strings = { "jdbc:h2:mem:ledger", "JDBC:H2:file:/tmp/ledger", "jdbc:h2:tcp://localhost/ledger;MODE=PostgreSQL" })
    @DisplayName("SET-031 a JDBC URL of H2, in any case and with any mode, is refused (DRIVER_INVALID, exit 1, the one reason) and no registration is written")
    void anH2UrlIsRefused(String url, Cli cli) {
        Outcome result = register(cli, url, PostgresDriverJar.DRIVER_CLASS);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.DRIVER_INVALID);
        assertThat(result.refusal().orElseThrow().arguments()).containsExactly(EmbeddedDatabaseRefusal.REASON);
        assertThat(file).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = { "org.h2.Driver", "org.h2.jdbcx.JdbcDataSource" })
    @DisplayName("SET-031 a driver class of the H2 package is refused before the jar is opened (DRIVER_INVALID, exit 1, the one reason) and no registration is written")
    void anH2ClassIsRefused(String className, Cli cli) {
        Outcome result = register(cli, TestDatabaseSettings.fromSystemProperties().url(), className);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.DRIVER_INVALID);
        assertThat(result.refusal().orElseThrow().arguments()).containsExactly(EmbeddedDatabaseRefusal.REASON);
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("SET-031 the driver and the URL of the test database are accepted as before: the refusal is the refusal of H2 only")
    void theTestDatabaseIsAccepted(Cli cli) {
        Outcome result = register(cli, TestDatabaseSettings.fromSystemProperties().url(), PostgresDriverJar.DRIVER_CLASS);

        assertThat(result.exitCode()).isZero();
        assertThat(List.of(file)).allSatisfy(registration -> assertThat(registration).exists());
    }
}
