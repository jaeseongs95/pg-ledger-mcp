package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.PostgresDriverJar;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.testing.OwnerOnlyFiles;
import org.pgledger.testing.database.TestDatabaseSettings;

/**
 * REH3-DRV-01 .. 04: {@code driver-register} on a run of the installer that was interrupted (the registration file is there, the
 * ledger schema is not migrated or was dropped). The command used to fail with DATABASE_FAILED because it wrote its audit row
 * through the ledger schema ({@code relation "id_counter" does not exist}). Now the registration file is replaced, the command
 * says that the audit row is still to come, and {@code migrate} and {@code setup} record it.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class DriverBeforeMigrateTest {

    private static final String DRIVER = PostgresDriverJar.DRIVER_CLASS;
    private static final String DOWN = "jdbc:postgresql://127.0.0.1:1/none";
    private static final String REGISTERED = "SELECT actor, subject, detail FROM owner_audit WHERE operation = 'DRIVER_REGISTERED'";

    @TempDir
    Path directory;

    private Path root;
    private Path drivers;
    private Path file;
    private Path jar;
    private TestLedgerDatabase database;

    @BeforeEach
    void layout() throws Exception {
        root = directory.toRealPath();
        drivers = OwnerOnlyFiles.restrict(Files.createDirectories(root.resolve("drivers"))).toRealPath();
        file = root.resolve("config").resolve("jdbc-driver.yaml");
        jar = OwnerOnlyFiles.copy(PostgresDriverJar.path(), drivers.resolve("postgresql.jar")).toRealPath();
        database = TestLedgerDatabase.empty();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static String slashed(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private Cli configured(Cli cli, String url) {
        return cli.withArguments("--ledger.cli.driver.file=" + slashed(file), "--ledger.cli.driver.directory=" + slashed(drivers),
                "--ledger.jdbc.url=" + url, "--ledger.schema.long-text-type=" + TestLedgerDatabase.longTextType())
                .asProcessUser("alice");
    }

    /** The first registration of a new installation: the driver is not registered yet, so no command has a database. */
    private void registeredOnce(Cli cli) {
        Outcome first = configured(cli, TestDatabaseSettings.fromSystemProperties().url()).run(root.resolve("secrets"), null,
                new RecordingOutput(), "driver-register", "--jar=" + jar, "--class=" + DRIVER);
        assertThat(first.exitCode()).as("the first registration").isZero();
    }

    private Outcome registerAgain(Cli cli, RecordingOutput output) {
        return configured(cli, TestDatabaseSettings.fromSystemProperties().url()).withDatabase(database.dataSource())
                .run(root.resolve("secrets"), null, output, "driver-register", "--jar=" + jar, "--class=" + DRIVER);
    }

    private Outcome run(Cli cli, String... command) {
        return configured(cli, TestDatabaseSettings.fromSystemProperties().url()).withDatabase(database.dataSource())
                .withApplicationAccount(database.applicationDataSource(true)).run(root.resolve("secrets"), null,
                        new RecordingOutput(), command);
    }

    private DriverRegistrationFile.Entry registered() {
        return new DriverRegistrationFile(new DriverRegistrationFile.Location(file)).read().orElseThrow();
    }

    @Test
    @DisplayName("REH3-DRV-01 driver-register again on a database whose schema is not migrated (dropped): exit 0, the file is replaced, the output says that migrate or setup records the audit row; no error is swallowed")
    void drv01_registersBeforeTheSchemaExists(Cli cli) {
        registeredOnce(cli);
        var firstTime = registered().registeredAt();
        RecordingOutput output = new RecordingOutput();

        Outcome again = registerAgain(cli, output);

        assertThat(again.failure()).isNull();
        assertThat(again.exitCode()).isZero();
        assertThat(registered().registeredAt()).isAfterOrEqualTo(firstTime);
        assertThat(output.info()).contains(jar.toString()).contains(DRIVER).contains("migrate or setup records it");
    }

    @Test
    @DisplayName("REH3-DRV-02 setup after it (and migrate in another run) records one DRIVER_REGISTERED row for the registration (actor cli:<user>, the class, jar, digest and registered_at), a second run adds none")
    void drv02_setupAndMigrateRecordThePendingRegistration(Cli cli) {
        registeredOnce(cli);
        registerAgain(cli, new RecordingOutput());

        Outcome setup = run(cli, "setup", "--app-account=" + database.appAccount());

        assertThat(setup.exitCode()).isZero();
        List<Map<String, Object>> rows = database.rows(REGISTERED);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("actor")).isEqualTo("cli:alice");
        assertThat(rows.getFirst().get("subject")).isEqualTo(DRIVER);
        assertThat(rows.getFirst().get("detail").toString()).contains("jar=" + jar).contains("sha256=" + registered().sha256())
                .contains("registered_at=" + registered().registeredAt());
        assertThat(database.rows("SELECT COUNT(*) AS n FROM owner_audit WHERE operation = 'CODE_SET'").getFirst().get("n"))
                .isEqualTo(14L);
        assertThat(run(cli, "setup", "--app-account=" + database.appAccount()).exitCode()).isZero();
        assertThat(run(cli, "migrate").exitCode()).isZero();
        assertThat(database.rows(REGISTERED)).hasSize(1);
    }

    @Test
    @DisplayName("REH3-DRV-02 migrate alone records the pending registration too")
    void drv02_migrateRecordsIt(Cli cli) {
        registeredOnce(cli);
        registerAgain(cli, new RecordingOutput());

        Outcome migrate = run(cli, "migrate");

        assertThat(migrate.exitCode()).isZero();
        assertThat(database.rows(REGISTERED)).hasSize(1);
    }

    @Test
    @DisplayName("REH3-DRV-03 a registration whose audit row was written at once (a migrated schema) is not recorded a second time by setup")
    void drv03_noSecondRow(Cli cli) {
        assertThat(run(cli, "migrate").exitCode()).isZero();
        registeredOnce(cli);
        RecordingOutput output = new RecordingOutput();
        assertThat(registerAgain(cli, output).exitCode()).isZero();
        assertThat(output.info()).doesNotContain("migrate or setup records it");
        assertThat(database.rows(REGISTERED)).hasSize(1);

        Outcome setup = run(cli, "setup", "--app-account=" + database.appAccount());

        assertThat(setup.exitCode()).isZero();
        assertThat(database.rows(REGISTERED)).hasSize(1);
    }

    @Test
    @DisplayName("REH3-DRV-04 a database that cannot be reached is no deferral: DATABASE_FAILED (exit 1), the registration file as it was")
    void drv04_unreachableIsAFailure(Cli cli) throws Exception {
        registeredOnce(cli);
        byte[] written = Files.readAllBytes(file);

        // the registration is read as it is at the next start: the database beans exist and the database is down
        Outcome again = configured(cli, DOWN).withArguments("--spring.config.import=optional:file:" + slashed(file),
                "--ledger.jdbc.password=a-password")
                .run(root.resolve("secrets"), null, new RecordingOutput(), "driver-register", "--jar=" + jar,
                        "--class=" + DRIVER);

        assertThat(again.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(Files.readAllBytes(file)).isEqualTo(written);
    }
}
