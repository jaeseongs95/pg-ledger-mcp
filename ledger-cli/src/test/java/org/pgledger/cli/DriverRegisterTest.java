package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresPosixPermissions;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.testing.OwnerOnlyFiles;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-014 (the CLI part of DRV-001 .. DRV-005, DRV-017, DRV-020, DRV-023) and CLI-043: {@code driver-register}. The checks are
 * those of the admin web driver page (the shared {@code DriverRegistrar}); the test driver is the H2 jar of the test class path,
 * copied into the allowed directory of the test. Without a registered driver (the state of a new installation) the command
 * works: it needs no database.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class DriverRegisterTest {

    private static final String H2_URL = "jdbc:h2:mem:cli-driver-register";
    private static final String PASSWORD = "Db-Password-4711-unique";

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
        jar = OwnerOnlyFiles.copy(h2Jar(), drivers.resolve("h2.jar")).toRealPath();
    }

    private static Path h2Jar() throws URISyntaxException {
        return Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String slashed(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private Cli configured(Cli cli, String url) {
        return cli.withArguments("--ledger.cli.driver.file=" + slashed(file), "--ledger.cli.driver.directory=" + slashed(drivers),
                "--ledger.jdbc.url=" + url, "--ledger.jdbc.password=" + PASSWORD).asProcessUser("alice");
    }

    private Outcome register(Cli cli, RecordingOutput output, String jarPath, String className) {
        return configured(cli, H2_URL).run(root.resolve("secrets"), null, output, "driver-register", "--jar=" + jarPath,
                "--class=" + className);
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private Optional<DriverRegistrationFile.Entry> registered() {
        return new DriverRegistrationFile(new DriverRegistrationFile.Location(file)).read();
    }

    @Test
    @DisplayName("CLI-014/DRV-001 driver-register works without any registered driver: the registration holds the real path, the class, the SHA-256 of the jar (equal to an independent digest) and the time in UTC (ending in Z); the output shows path, class and digest and never the database password")
    void registersADriver(Cli cli, CapturedOutput captured) throws Exception {
        RecordingOutput output = new RecordingOutput();
        Instant before = Instant.now().minus(Duration.ofMinutes(1));

        Outcome result = register(cli, output, jar.toString(), "org.h2.Driver");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        DriverRegistrationFile.Entry entry = registered().orElseThrow();
        assertThat(entry.jar()).isEqualTo(jar.toString());
        assertThat(entry.className()).isEqualTo("org.h2.Driver");
        assertThat(entry.sha256()).isEqualTo(sha256(jar));
        assertThat(entry.registeredAt()).isBetween(before, Instant.now().plus(Duration.ofMinutes(1)));
        assertThat(Files.readString(file)).containsPattern("registered-at: \"[0-9T:.-]+Z\"");
        assertThat(output.info()).contains(jar.toString()).contains("org.h2.Driver").contains(sha256(jar));
        assertThat(Files.readString(file)).doesNotContain(PASSWORD);
        assertThat(output.info()).doesNotContain(PASSWORD);
        assertThat(captured.getAll()).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("CLI-014/DRV-002 a jar that does not exist, a directory, a relative path, or a path outside the allowed directory is DRIVER_INVALID (exit 1) and nothing is stored; a path with .. that leaves the allowed directory is outside, one that stays inside is its real path (changed by cli2: the CLI resolves .. and links as the admin web does, ADM-060)")
    void refusesUnusablePaths(Cli cli) throws Exception {
        Path outside = OwnerOnlyFiles.copy(h2Jar(), OwnerOnlyFiles.restrict(Files.createDirectories(root.resolve("outside")))
                .resolve("h2.jar"));
        Files.createDirectories(drivers.resolve("sub"));
        for (String path : List.of(drivers.resolve("missing.jar").toString(), drivers.toString(), "h2.jar",
                outside.toString(), drivers.resolve("..").resolve("outside").resolve("h2.jar").toString())) {
            Outcome result = register(cli, new RecordingOutput(), path, "org.h2.Driver");

            assertThat(result.exitCode()).as(path).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).as(path).contains(ErrorCode.DRIVER_INVALID);
            assertThat(file).as(path).doesNotExist();
        }

        Outcome inside = register(cli, new RecordingOutput(), drivers.resolve("sub").resolve("..").resolve("h2.jar").toString(),
                "org.h2.Driver");

        assertThat(inside.exitCode()).isZero();
        assertThat(registered().orElseThrow().jar()).isEqualTo(jar.toString());
    }

    @Test
    @DisplayName("CLI-014/DRV-003 a text file renamed .jar, an empty file and a truncated jar are DRIVER_INVALID (exit 1) and nothing is stored")
    void refusesFilesThatAreNoArchives(Cli cli) throws Exception {
        byte[] whole = Files.readAllBytes(jar);
        Path text = OwnerOnlyFiles.write(drivers.resolve("text.jar"), "this is not a zip".getBytes(StandardCharsets.UTF_8));
        Path empty = OwnerOnlyFiles.write(drivers.resolve("empty.jar"), new byte[0]);
        Path truncated = OwnerOnlyFiles.write(drivers.resolve("truncated.jar"), Arrays.copyOf(whole, 1000));
        for (Path bad : List.of(text, empty, truncated)) {
            Outcome result = register(cli, new RecordingOutput(), bad.toString(), "org.h2.Driver");

            assertThat(result.exitCode()).as(bad.toString()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).as(bad.toString()).contains(ErrorCode.DRIVER_INVALID);
            assertThat(file).as(bad.toString()).doesNotExist();
        }
    }

    @Test
    @DisplayName("CLI-014/DRV-004 a class that is not in the jar, a class that is no java.sql.Driver, and a blank class are refused (exit 1; the blank one as a usage error) and nothing is stored; the reason is in the log, the text is the fixed one of the admin web (changed by cli2: one text for the three cases, ADM-060)")
    void refusesWrongClasses(Cli cli, CapturedOutput captured) {
        for (String className : List.of("org.h2.NoSuchDriver", "org.h2.util.StringUtils", "java.lang.String")) {
            Outcome result = register(cli, new RecordingOutput(), jar.toString(), className);

            assertThat(result.exitCode()).as(className).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).as(className).contains(ErrorCode.DRIVER_INVALID);
            assertThat(file).as(className).doesNotExist();
        }
        assertThat(captured.getAll()).contains("was refused by the loader");

        Outcome blank = register(cli, new RecordingOutput(), jar.toString(), " ");

        assertThat(blank.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(blank.code()).contains(ErrorCode.MISSING_OPTION_VALUE);
    }

    @Test
    @DisplayName("CLI-014/DRV-005 a driver that does not accept the configured JDBC URL is DRIVER_INVALID (exit 1); the password in the URL appears in no output, no log and no file")
    void refusesADriverThatDoesNotAcceptTheUrl(Cli cli, CapturedOutput captured) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = configured(cli, "jdbc:postgresql://db.example/ledger?password=secret-value")
                .run(root.resolve("secrets"), null, output, "driver-register", "--jar=" + jar, "--class=org.h2.Driver");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.DRIVER_INVALID);
        assertThat(file).doesNotExist();
        assertThat(captured.getAll()).doesNotContain("secret-value").doesNotContain(PASSWORD);
        assertThat(output.info()).doesNotContain("secret-value");
    }

    @Test
    @DisplayName("CLI-014/DRV-023 a refused registration leaves an existing valid registration byte-identical")
    void aRefusalLeavesTheFileAsItWas(Cli cli) throws Exception {
        assertThat(register(cli, new RecordingOutput(), jar.toString(), "org.h2.Driver").exitCode()).isZero();
        byte[] before = Files.readAllBytes(file);

        Outcome result = register(cli, new RecordingOutput(), jar.toString(), "org.h2.NoSuchDriver");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("CLI-014/DRV-020 a second registration replaces the first (no temporary file is left in the directory of the registration)")
    void aSecondRegistrationReplacesTheFirst(Cli cli) throws Exception {
        Path other = OwnerOnlyFiles.copy(h2Jar(), drivers.resolve("h2-copy.jar")).toRealPath();
        assertThat(register(cli, new RecordingOutput(), jar.toString(), "org.h2.Driver").exitCode()).isZero();

        Outcome second = register(cli, new RecordingOutput(), other.toString(), "org.h2.Driver");

        assertThat(second.exitCode()).isZero();
        assertThat(registered().orElseThrow().jar()).isEqualTo(other.toString());
        try (var entries = Files.list(file.getParent())) {
            assertThat(entries.toList()).containsExactly(file);
        }
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("CLI-014/DRV-020 on a POSIX file system the registration file is readable by the owner and the group only (rw-r-----)")
    void theFileIsNotWiderThan0640(Cli cli) throws Exception {
        Outcome result = register(cli, new RecordingOutput(), jar.toString(), "org.h2.Driver");

        assertThat(result.exitCode()).isZero();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r-----");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("CLI-014/DRV-017 a jar in a directory that the group can write is refused (DRIVER_INVALID); the same jar in an owner-only directory is accepted")
    void refusesALooseDirectory(Cli cli) throws Exception {
        Path loose = Files.createDirectories(drivers.resolve("loose"));
        Path inLoose = OwnerOnlyFiles.copy(h2Jar(), loose.resolve("h2.jar")).toRealPath();
        Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rwxrwx---"));

        Outcome refused = register(cli, new RecordingOutput(), inLoose.toString(), "org.h2.Driver");

        assertThat(refused.code()).contains(ErrorCode.DRIVER_INVALID);
        assertThat(file).doesNotExist();
        Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rwx------"));
        assertThat(register(cli, new RecordingOutput(), inLoose.toString(), "org.h2.Driver").exitCode()).isZero();
    }

    @Test
    @DisplayName("CLI-043 when the ledger database is reachable the registration is recorded in the owner audit trail as DRIVER_REGISTERED with the actor cli:<user>, the class as subject and the jar and the digest in the detail")
    void auditsTheRegistration(Cli cli) throws Exception {
        try (TestLedgerDatabase database = TestLedgerDatabase.migrated()) {
            Outcome result = configured(cli, H2_URL).withDatabase(database.dataSource())
                    .run(root.resolve("secrets"), null, new RecordingOutput(), "driver-register", "--jar=" + jar,
                            "--class=org.h2.Driver");

            assertThat(result.exitCode()).isZero();
            List<Map<String, Object>> audit = database.rows("SELECT actor, operation, subject, detail FROM owner_audit");
            assertThat(audit).hasSize(1);
            assertThat(audit.getFirst().get("actor")).isEqualTo("cli:alice");
            assertThat(audit.getFirst().get("operation")).isEqualTo("DRIVER_REGISTERED");
            assertThat(audit.getFirst().get("subject")).isEqualTo("org.h2.Driver");
            assertThat(audit.getFirst().get("detail").toString()).contains(jar.toString()).contains(sha256(jar));
            assertThat(registered()).isPresent();
        }
    }
}
