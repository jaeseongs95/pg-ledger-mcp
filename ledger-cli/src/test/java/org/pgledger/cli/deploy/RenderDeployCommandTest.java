package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresPosixPermissions;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** INS-020 (and INS-032 for the files of Windows): {@code render-deploy} writes the files below a root, with no root and no Linux. */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class RenderDeployCommandTest {

    private static final String[] LINUX = { "--java=/usr/lib/jvm/java-25/bin/java",
            "--jdbc-url=jdbc:postgresql://db.example.org:5432/ledger", "--app-account=ledger_app",
            "--mcp-host=mcp.example.org", "--admin-host=admin.example.org",
            "--nginx-conf=/etc/nginx/conf.d/pg-ledger-mcp.conf", "--tls-certificate=/etc/ssl/ledger/fullchain.pem",
            "--tls-key=/etc/ssl/ledger/privkey.pem" };
    private static final String[] WINDOWS = { "--platform=windows", "--home=C:/ProgramData/pg-ledger-mcp",
            "--java=C:/Program Files/Java/jdk-25/bin/java.exe", "--jdbc-url=jdbc:postgresql://localhost:5432/ledger",
            "--app-account=ledger_app" };

    @TempDir
    Path tmp;

    private Outcome run(Cli cli, RecordingOutput output, Path root, String[] options, String... more) {
        List<String> args = new ArrayList<>(List.of("render-deploy"));
        args.addAll(List.of(options));
        args.addAll(List.of(more));
        if (root != null) {
            args.add("--root=" + root);
        }
        return cli.run(tmp.resolve("secrets"), null, output, args.toArray(String[]::new));
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    @Test
    @DisplayName("INS-020 a Linux server: the files are written below the root with the content the renderer gives, and the output lists them")
    void ins020_writesTheLinuxFiles(Cli cli) throws IOException {
        RecordingOutput output = new RecordingOutput();
        Path root = tmp.resolve("root");

        Outcome result = run(cli, output, root, LINUX);

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        List<RenderedFile> expected = DeployRenderer.render(DeployParameters.of(java.util.Map.of(
                DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java", DeployOptions.JDBC_URL,
                "jdbc:postgresql://db.example.org:5432/ledger", DeployOptions.APP_ACCOUNT, "ledger_app",
                DeployOptions.MCP_HOST, "mcp.example.org", DeployOptions.ADMIN_HOST, "admin.example.org",
                DeployOptions.NGINX_CONF, "/etc/nginx/conf.d/pg-ledger-mcp.conf", DeployOptions.TLS_CERTIFICATE,
                "/etc/ssl/ledger/fullchain.pem", DeployOptions.TLS_KEY, "/etc/ssl/ledger/privkey.pem")));
        assertThat(files(root)).hasSize(expected.size());
        for (RenderedFile file : expected) {
            assertThat(Files.readString(root.resolve(file.path().substring(1)), StandardCharsets.UTF_8))
                    .as(file.path()).isEqualTo(file.content());
            assertThat(output.info()).contains(root.resolve(file.path().substring(1)).toString());
        }
        assertThat(files(root).stream().map(p -> root.relativize(p).getFileName().toString()))
                .noneMatch(name -> name.startsWith(".."));
        assertThat(Files.exists(tmp.resolve("secrets"))).as("no secret directory is made").isFalse();
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("INS-020 the files are readable by all (0644): the service user and nginx have to read them, and they hold no secret")
    void ins020_theFilesAreReadable(Cli cli) throws IOException {
        Path root = tmp.resolve("root");

        run(cli, new RecordingOutput(), root, LINUX);

        for (Path file : files(root)) {
            assertThat(Files.getPosixFilePermissions(file)).as(file.toString()).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    @DisplayName("INS-020 a second run replaces the files with the same bytes and leaves no temporary file")
    void ins020_aRerunIsIdentical(Cli cli) throws IOException {
        Path first = tmp.resolve("first");
        Path second = tmp.resolve("second");
        run(cli, new RecordingOutput(), first, LINUX);
        run(cli, new RecordingOutput(), first, LINUX);
        run(cli, new RecordingOutput(), second, LINUX);

        List<Path> files = files(first);
        assertThat(files).hasSameSizeAs(files(second));
        for (Path file : files) {
            assertThat(Files.readAllBytes(file)).isEqualTo(Files.readAllBytes(second.resolve(first.relativize(file))));
            assertThat(file.getFileName().toString()).doesNotStartWith("..");
        }
    }

    @Test
    @DisplayName("INS-032 a personal PC or a Windows server: the WinSW configurations and the settings are written below the root, a drive letter becomes a directory")
    void ins032_writesTheWindowsFiles(Cli cli) throws IOException {
        Path root = tmp.resolve("root");

        Outcome result = run(cli, new RecordingOutput(), root, WINDOWS);

        assertThat(result.exitCode()).isZero();
        Path home = root.resolve("C").resolve("ProgramData").resolve("pg-ledger-mcp");
        assertThat(files(root).stream().map(p -> home.relativize(p).toString().replace('\\', '/'))).containsExactly(
                "etc/admin.yaml", "etc/application.yaml", "etc/mcp.yaml", "lib/pg-ledger-mcp-admin.xml", "lib/pg-ledger-mcp.xml");
        assertThat(Files.readString(home.resolve("lib").resolve("pg-ledger-mcp.xml"))).contains("<id>pg-ledger-mcp</id>");
    }

    @Test
    @DisplayName("INS-040 --name writes the files of that instance: the units and the settings carry the name and no file or path names the default instance")
    void ins040_aNamedInstanceIsWrittenUnderItsName(Cli cli) throws IOException {
        Path root = tmp.resolve("root");

        Outcome result = run(cli, new RecordingOutput(), root, new String[] { "--platform=windows",
                "--java=C:/Program Files/Java/jdk-25/bin/java.exe", "--jdbc-url=jdbc:postgresql://localhost:5432/ledger",
                "--app-account=ledger_app", "--name=acme-test", "--home=C:/ProgramData/acme-test" });

        assertThat(result.code()).isEmpty();
        assertThat(files(root).stream().map(p -> root.relativize(p).toString().replace('\\', '/'))).containsExactly(
                "C/ProgramData/acme-test/etc/admin.yaml", "C/ProgramData/acme-test/etc/application.yaml",
                "C/ProgramData/acme-test/etc/mcp.yaml", "C/ProgramData/acme-test/lib/acme-test-admin.xml",
                "C/ProgramData/acme-test/lib/acme-test.xml");
    }

    @Test
    @DisplayName("INS-040 --check validates every option and writes nothing: exit 0 for good values, the same refusal as a run for a bad one, and no value for a flag")
    void ins040_checkWritesNothing(Cli cli) {
        Path root = tmp.resolve("root");

        Outcome good = run(cli, new RecordingOutput(), root, LINUX, "--check");
        Outcome badName = run(cli, new RecordingOutput(), root, LINUX, "--check", "--name=Bad_Name");
        Outcome withValue = run(cli, new RecordingOutput(), root, LINUX, "--check=yes");

        assertThat(good.exitCode()).isZero();
        assertThat(good.failure()).isNull();
        assertThat(badName.code()).contains(ErrorCode.INVALID_DEPLOY_VALUE);
        assertThat(badName.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(withValue.code()).contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(Files.exists(root)).as("--check writes nothing").isFalse();
    }

    private static final String ONE_X_UNIT = "[Unit]\nDescription=pg-ledger-mcp 1.x (DBHub)\n[Service]\nEnvironmentFile=/etc/pg-ledger-mcp/pg-ledger-mcp.env\n";

    @Test
    @DisplayName("INS-042 a file of that name that this command did not render (the unit of 1.x) is kept as <file>.1x-backup before it is replaced, once, and the output says so")
    void ins042_aForeignFileIsKept(Cli cli) throws IOException {
        Path root = tmp.resolve("root");
        Path unit = root.resolve("etc/systemd/system/pg-ledger-mcp.service");
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, ONE_X_UNIT, StandardCharsets.UTF_8);
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, root, LINUX);

        Path backup = unit.resolveSibling("pg-ledger-mcp.service.1x-backup");
        assertThat(result.code()).isEmpty();
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).as("the old unit is kept as it was").isEqualTo(ONE_X_UNIT);
        assertThat(Files.readString(unit, StandardCharsets.UTF_8)).as("the unit of 2.0 is in its place")
                .contains("Rendered by `ledger-cli render-deploy`").doesNotContain("DBHub");
        assertThat(output.info()).contains(backup.toString());
        assertThat(files(root).stream().filter(p -> p.getFileName().toString().endsWith(".1x-backup"))).as("only the one that existed")
                .hasSize(1);
    }

    @Test
    @DisplayName("INS-042 a file that this command rendered is replaced without a backup, also on every later run, and the backup of the first run stays as it was")
    void ins042_ourOwnFileIsNotKept(Cli cli) throws IOException {
        Path root = tmp.resolve("root");
        Path unit = root.resolve("etc/systemd/system/pg-ledger-mcp.service");
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, ONE_X_UNIT, StandardCharsets.UTF_8);
        run(cli, new RecordingOutput(), root, LINUX);

        Outcome again = run(cli, new RecordingOutput(), root, LINUX);

        assertThat(again.code()).isEmpty();
        assertThat(Files.readString(unit.resolveSibling("pg-ledger-mcp.service.1x-backup"), StandardCharsets.UTF_8))
                .isEqualTo(ONE_X_UNIT);
        assertThat(files(root).stream().filter(p -> p.getFileName().toString().endsWith(".1x-backup"))).hasSize(1);

        // every kind of file the command renders carries the marker: the WinSW configurations and the settings of Windows as well
        Path windows = tmp.resolve("windows");
        run(cli, new RecordingOutput(), windows, WINDOWS);
        run(cli, new RecordingOutput(), windows, WINDOWS);
        assertThat(files(windows).stream().filter(p -> p.getFileName().toString().endsWith(".1x-backup"))).isEmpty();
    }

    @Test
    @DisplayName("INS-042 a foreign file and a backup of that name both there: DEPLOY_WRITE_FAILED (exit 1), nothing of either is overwritten")
    void ins042_aBackupIsNeverOverwritten(Cli cli) throws IOException {
        Path root = tmp.resolve("root");
        Path unit = root.resolve("etc/systemd/system/pg-ledger-mcp.service");
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, ONE_X_UNIT + "# second\n", StandardCharsets.UTF_8);
        Path backup = unit.resolveSibling("pg-ledger-mcp.service.1x-backup");
        Files.writeString(backup, ONE_X_UNIT, StandardCharsets.UTF_8);

        Outcome result = run(cli, new RecordingOutput(), root, LINUX);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.DEPLOY_WRITE_FAILED);
        assertThat(Files.readString(unit, StandardCharsets.UTF_8)).isEqualTo(ONE_X_UNIT + "# second\n");
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(ONE_X_UNIT);
    }

    private static final String JAVA = "--java=/usr/bin/java";
    private static final String URL = "--jdbc-url=jdbc:postgresql://h/db";
    private static final String ACCOUNT = "--app-account=ledger_app";

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(new String[] { JAVA, "--jdbc-url=jdbc:postgresql://h/db?password=x", ACCOUNT }, ErrorCode.INVALID_DEPLOY_VALUE, ExitStatus.USAGE),
                Arguments.of(new String[] { JAVA, URL, "--app-account=a;b" }, ErrorCode.INVALID_DEPLOY_VALUE, ExitStatus.USAGE),
                Arguments.of(new String[] { JAVA, URL, ACCOUNT, "--platform=macos" }, ErrorCode.INVALID_DEPLOY_VALUE, ExitStatus.USAGE),
                Arguments.of(new String[] { JAVA, URL, ACCOUNT, "--mcp-port=80" }, ErrorCode.INVALID_DEPLOY_VALUE, ExitStatus.USAGE),
                Arguments.of(new String[] { JAVA, URL, ACCOUNT, "--force" }, ErrorCode.UNKNOWN_OPTION, ExitStatus.USAGE),
                Arguments.of(new String[] { JAVA, URL, ACCOUNT, "--schema" }, ErrorCode.MISSING_OPTION_VALUE, ExitStatus.USAGE),
                Arguments.of(new String[] { JAVA, URL, ACCOUNT, "--tls-key=/etc/ssl/k.pem" }, ErrorCode.MISSING_ARGUMENT, ExitStatus.USAGE));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    @DisplayName("INS-020 a refused command line writes nothing and prints no value")
    void ins020_aRefusalWritesNothing(String[] options, ErrorCode code, ExitStatus status, Cli cli, CapturedOutput captured)
            throws IOException {
        Path root = tmp.resolve("root");

        Outcome result = run(cli, new RecordingOutput(), root, options);

        assertThat(result.exitCode()).isEqualTo(status.code());
        assertThat(result.code()).contains(code);
        assertThat(Files.exists(root)).isFalse();
        assertThat(captured.getAll()).doesNotContain("password=x");
    }

    @Test
    @DisplayName("INS-020 a missing required option or a surplus operand is a usage error and writes nothing")
    void ins020_usageErrors(Cli cli) {
        Path root = tmp.resolve("root");

        Outcome missing = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "render-deploy", "--java=/usr/bin/java",
                "--root=" + root);
        Outcome surplus = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "render-deploy", "extra");

        assertThat(missing.code()).contains(ErrorCode.MISSING_ARGUMENT);
        assertThat(surplus.code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        assertThat(Files.exists(root)).isFalse();
    }
}
