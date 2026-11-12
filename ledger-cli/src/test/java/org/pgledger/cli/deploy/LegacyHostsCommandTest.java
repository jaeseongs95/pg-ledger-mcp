package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * REH4-HOST-01, 02, 04 to 07: the public host names of a 1.x environment file are carried by the options or the command is
 * refused before anything is changed ({@code --from-1x-env} of {@code render-deploy}, {@code prepare-host} and {@code seal-host}).
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class LegacyHostsCommandTest {

    private static final String AGENT_TOKEN = "a7c3e19b5d2f48a6b0c9d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2";
    private static final String ADMIN_TOKEN = "0f1e2d3c4b5a69788796a5b4c3d2e1f00112233445566778899aabbccddeeff0";
    private static final String DB_PASSWORD = "s3cr3t-database-password";
    private static final String PUBLIC_NAME = "ledger-test-mcp.jaeseong.dev";

    private static final String[] BASE = { "--java=/usr/lib/jvm/java-25/bin/java",
            "--jdbc-url=jdbc:postgresql://db.example.org:5432/ledger", "--app-account=ledger_app" };

    @TempDir
    Path tmp;

    /** The environment file of a 1.1.0 installation: the tokens and a password are in it, which no message may hold. */
    private Path environment(String... settings) throws IOException {
        String text = "# written by the 1.x installer\nDBHUB_AUTH_TOKEN=" + AGENT_TOKEN + "\nPGLEDGER_ADMIN_TOKEN=" + ADMIN_TOKEN
                + "\nPGLEDGER_AUTHOR_PASSWORD=" + DB_PASSWORD + "\n" + String.join("\n", settings) + "\n";
        Path file = tmp.resolve("env");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private Outcome run(Cli cli, RecordingOutput output, String command, Path file, String... more) {
        List<String> args = new ArrayList<>(List.of(command));
        args.addAll(List.of(BASE));
        args.addAll(List.of(more));
        if (List.of(more).stream().noneMatch(option -> option.startsWith("--root="))) {
            // the paths of a Linux host are reached below a root (the tests run on any platform); nothing is written with --check
            args.add("--root=" + tmp.resolve("unused-root"));
        }
        args.add("--from-1x-env=" + file);
        return cli.run(tmp.resolve("secrets"), null, output, args.toArray(String[]::new));
    }

    private static void assertNoSecret(Outcome result, RecordingOutput output) {
        String everything = output.info() + "\n" + result.failure() + "\n" + result.refusal().map(r -> String.join(" ", r.arguments())).orElse("");
        assertThat(everything).doesNotContain(AGENT_TOKEN).doesNotContain(ADMIN_TOKEN).doesNotContain(DB_PASSWORD);
    }

    @Test
    @DisplayName("REH4-HOST-01 a public name of DBHUB_ALLOWED_HOSTS that no --mcp-host carries refuses the check and the run, naming the host and the option, with nothing written and no other value of the file in a message")
    void host01_aDroppedNameRefusesBeforeAnyChange(Cli cli) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=" + PUBLIC_NAME + ",localhost,127.0.0.1");
        Path root = tmp.resolve("root");

        RecordingOutput checked = new RecordingOutput();
        Outcome check = run(cli, checked, "render-deploy", file, "--check");
        RecordingOutput rendered = new RecordingOutput();
        Outcome render = run(cli, rendered, "render-deploy", file, "--root=" + root);

        for (Outcome result : List.of(check, render)) {
            assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
            assertThat(result.code()).contains(ErrorCode.LEGACY_HOSTS_DROPPED);
            assertThat(result.refusal().orElseThrow().arguments()).containsExactly(PUBLIC_NAME, "mcp-host");
        }
        assertNoSecret(check, checked);
        assertNoSecret(render, rendered);
        assertThat(root).as("nothing is written").doesNotExist();
    }

    @Test
    @DisplayName("REH4-HOST-02 the name given with --mcp-host is accepted and is in the allowed hosts of the MCP server, with the loopback names and nothing else; the admin web keeps its loopback names")
    void host02_theOptionCarriesTheName(Cli cli) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=" + PUBLIC_NAME + ",localhost,127.0.0.1");
        Path root = tmp.resolve("root");

        Outcome check = run(cli, new RecordingOutput(), "render-deploy", file, "--check", "--mcp-host=" + PUBLIC_NAME);
        Outcome render = run(cli, new RecordingOutput(), "render-deploy", file, "--mcp-host=" + PUBLIC_NAME, "--root=" + root);

        assertThat(check.failure()).isNull();
        assertThat(render.failure()).isNull();
        assertThat(Files.readString(root.resolve("etc/pg-ledger-mcp/mcp.yaml"), StandardCharsets.UTF_8))
                .contains("    allowed-hosts:\n      - '127.0.0.1'\n      - 'localhost'\n      - '" + PUBLIC_NAME + "'\n");
        assertThat(Files.readString(root.resolve("etc/pg-ledger-mcp/admin.yaml"), StandardCharsets.UTF_8))
                .contains("    allowed-hosts:\n      - '127.0.0.1'\n      - 'localhost'\n    driver-file:").doesNotContain(PUBLIC_NAME);
    }

    @Test
    @DisplayName("REH4-HOST-02 prepare-host and seal-host read the option too and refuse in the same way before they change anything")
    void host02_theHostCommandsCheckToo(Cli cli) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=" + PUBLIC_NAME);
        Path jars = Files.createDirectories(tmp.resolve("jars"));
        Path root = tmp.resolve("root");

        Outcome prepare = run(cli, new RecordingOutput(), "prepare-host", file, "--jars=" + jars,
                "--driver-jar=" + jars.resolve("driver.jar"), "--root=" + root);
        Outcome seal = run(cli, new RecordingOutput(), "seal-host", file, "--root=" + root);

        for (Outcome result : List.of(prepare, seal)) {
            assertThat(result.code()).contains(ErrorCode.LEGACY_HOSTS_DROPPED);
            assertThat(result.refusal().orElseThrow().arguments()).containsExactly(PUBLIC_NAME, "mcp-host");
        }
        assertThat(root).doesNotExist();
    }

    @Test
    @DisplayName("REH4-HOST-04 the admin web: PGLEDGER_ADMIN_ALLOWED_HOSTS of a 1.1.0 file is compared with --admin-host (one name or a list); the MCP names are named first")
    void host04_theAdminNames(Cli cli) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=127.0.0.1", "PGLEDGER_ADMIN_ALLOWED_HOSTS=admin.example.org,127.0.0.1");

        for (String option : List.of("--admin-host=other.example.org", "--mcp-host=mcp.example.org")) {
            Outcome refused = run(cli, new RecordingOutput(), "render-deploy", file, "--check", option);
            assertThat(refused.exitCode()).as(option).isEqualTo(ExitStatus.USAGE.code());
            assertThat(refused.refusal().orElseThrow().code()).isEqualTo(ErrorCode.LEGACY_HOSTS_DROPPED);
            assertThat(refused.refusal().orElseThrow().arguments()).as(option).containsExactly("admin.example.org", "admin-host");
        }
        for (String option : List.of("--admin-host=admin.example.org", "--admin-host=other.example.org,admin.example.org")) {
            Outcome accepted = run(cli, new RecordingOutput(), "render-deploy", file, "--check", option);
            assertThat(accepted.failure()).as(option).isNull();
        }

        Path both = environment("DBHUB_ALLOWED_HOSTS=mcp.example.org", "PGLEDGER_ADMIN_ALLOWED_HOSTS=admin.example.org");
        Outcome first = run(cli, new RecordingOutput(), "render-deploy", both, "--check");
        assertThat(first.refusal().orElseThrow().arguments()).containsExactly("mcp.example.org", "mcp-host");
    }

    @Test
    @DisplayName("REH4-HOST-05 a file that is missing, a directory, not UTF-8 or larger than 64 KiB is ENV_FILE_FAILED (exit 1) with the kind of the failure only, and nothing is written")
    void host05_anUnusableFile(Cli cli) throws IOException {
        Path notUtf8 = tmp.resolve("not-utf8");
        Files.write(notUtf8, new byte[] { 'A', '=', (byte) 0xC3, (byte) 0x28, '\n' });
        Path large = tmp.resolve("large");
        Files.writeString(large, "DBHUB_AUTH_TOKEN=" + "x".repeat(65 * 1024) + "\n", StandardCharsets.UTF_8);
        Path root = tmp.resolve("root");

        for (Path file : List.of(tmp.resolve("missing"), tmp, notUtf8, large)) {
            RecordingOutput output = new RecordingOutput();
            Outcome result = run(cli, output, "render-deploy", file, "--root=" + root);

            assertThat(result.exitCode()).as(file.toString()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).as(file.toString()).contains(ErrorCode.ENV_FILE_FAILED);
            assertThat(output.info()).doesNotContain("xxxxxxxx");
        }
        assertThat(root).doesNotExist();
    }

    @Test
    @DisplayName("REH4-HOST-06 loopback names only, a file without the key, and no file: no option is needed and the files are written")
    void host06_noPublicNameNeedsNoOption(Cli cli) throws IOException {
        Path loopback = environment("DBHUB_ALLOWED_HOSTS=localhost,127.0.0.1");
        Path without = environment();
        Path root = tmp.resolve("root");

        assertThat(run(cli, new RecordingOutput(), "render-deploy", loopback, "--check").failure()).isNull();
        assertThat(run(cli, new RecordingOutput(), "render-deploy", without, "--root=" + root).failure()).isNull();
        assertThat(root.resolve("etc/pg-ledger-mcp/mcp.yaml")).exists();
        assertThat(cli.run(tmp.resolve("secrets"), null, new RecordingOutput(),
                new String[] { "render-deploy", BASE[0], BASE[1], BASE[2], "--check", "--root=" + root }).failure()).isNull();
    }

    @Test
    @DisplayName("REH4-HOST-07 two public names: both options names are accepted; one of them is named when the other is missing; a name that is not plain is refused first as the form of --mcp-host")
    void host07_severalNames(Cli cli) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=a.example.org,b.example.org,localhost");

        Outcome both = run(cli, new RecordingOutput(), "render-deploy", file, "--check", "--mcp-host=a.example.org,b.example.org");
        Outcome one = run(cli, new RecordingOutput(), "render-deploy", file, "--check", "--mcp-host=b.example.org");
        Outcome form = run(cli, new RecordingOutput(), "render-deploy", file, "--check", "--mcp-host=A.example.org,b.example.org");

        assertThat(both.failure()).isNull();
        assertThat(one.code()).contains(ErrorCode.LEGACY_HOSTS_DROPPED);
        assertThat(one.refusal().orElseThrow().arguments()).containsExactly("a.example.org", "mcp-host");
        assertThat(form.code()).contains(ErrorCode.INVALID_DEPLOY_VALUE);
        assertThat(form.refusal().orElseThrow().arguments()).containsExactly("mcp-host");
    }
}
