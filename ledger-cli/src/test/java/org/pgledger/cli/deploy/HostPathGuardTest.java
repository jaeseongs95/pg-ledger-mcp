package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * CLI-PRN-03 (plan 2, F8): without {@code --root} a file is written at its own path, so a platform whose paths are not paths
 * of this host (a Windows drive path on a host without drives, a path of the root of a Linux file system on a host without
 * one) used to be written below the working directory. The three installer commands refuse it before they read or change
 * anything. Which platform is the foreign one depends on the host the test runs on, and the test asks the file system of the
 * host (is the path absolute?), never the name of the operating system (ARC-033); each host has exactly one foreign platform
 * of the two. Only {@code --check} and commands that cannot change anything are run without a root: a platform that is the
 * platform of the host would otherwise change the real folders of the host.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class HostPathGuardTest {

    private static final String[] LINUX = { "--java=/usr/lib/jvm/java-25/bin/java",
            "--jdbc-url=jdbc:postgresql://db.example.org:5432/ledger", "--app-account=ledger_app" };
    private static final String[] WINDOWS = { "--platform=windows", "--home=C:/ProgramData/pg-ledger-mcp",
            "--java=C:/Program Files/Java/jdk-25/bin/java.exe", "--jdbc-url=jdbc:postgresql://localhost:5432/ledger",
            "--app-account=ledger_app" };

    @TempDir
    Path tmp;

    private Outcome run(Cli cli, String command, String[] options, String... more) {
        List<String> args = new ArrayList<>(List.of(command));
        args.addAll(List.of(options));
        args.addAll(List.of(more));
        return cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), args.toArray(String[]::new));
    }

    /** Whether the paths of that platform are absolute paths of the host that runs this test. */
    private static boolean ownsThisHost(DeployLayout layout) {
        return Path.of(layout.etc()).isAbsolute();
    }

    private static void assertRefusedAsAPlatform(Outcome outcome) {
        assertThat(outcome.code()).contains(ErrorCode.INVALID_DEPLOY_VALUE);
        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(outcome.refusal().orElseThrow().arguments()).containsExactly(DeployOptions.PLATFORM);
    }

    @Test
    @DisplayName("CLI-PRN-03 render-deploy --check without a root refuses the platform whose paths are not paths of this host (naming platform, no value) and accepts the platform of the host; with a root both are accepted; nothing is written")
    void renderDeployCheck(Cli cli) throws Exception {
        String[][] platforms = { LINUX, WINDOWS };
        DeployLayout[] layouts = { DeployLayout.linux(DeployLayout.DEFAULT_NAME),
                DeployLayout.windows(DeployLayout.DEFAULT_NAME, "C:/ProgramData/pg-ledger-mcp") };
        int refused = 0;
        for (int index = 0; index < platforms.length; index++) {
            Outcome withoutRoot = run(cli, "render-deploy", platforms[index], "--check");
            Outcome withRoot = run(cli, "render-deploy", platforms[index], "--check", "--root=" + tmp.resolve("root"));

            if (ownsThisHost(layouts[index])) {
                assertThat(withoutRoot.failure()).as(layouts[index].platform().name()).isNull();
                assertThat(withoutRoot.exitCode()).isZero();
            } else {
                refused++;
                assertRefusedAsAPlatform(withoutRoot);
            }
            assertThat(withRoot.failure()).as("with a root: " + layouts[index].platform().name()).isNull();
        }
        assertThat(refused).as("one of the two platforms is not the platform of this host").isEqualTo(1);
        assertThat(Files.exists(tmp.resolve("root"))).as("--check writes nothing").isFalse();
    }

    @Test
    @DisplayName("CLI-PRN-03 prepare-host and seal-host without a root refuse the platform that is not the platform of the host before they read or change anything (the jars do not exist, so nothing could be written either)")
    void prepareAndSealHost(Cli cli) {
        String[][] platforms = { LINUX, WINDOWS };
        DeployLayout[] layouts = { DeployLayout.linux(DeployLayout.DEFAULT_NAME),
                DeployLayout.windows(DeployLayout.DEFAULT_NAME, "C:/ProgramData/pg-ledger-mcp") };
        for (int index = 0; index < platforms.length; index++) {
            if (ownsThisHost(layouts[index])) {
                continue;
            }
            Outcome prepare = run(cli, "prepare-host", platforms[index], "--jars=" + tmp.resolve("no-jars"),
                    "--driver-jar=" + tmp.resolve("no-driver.jar"), "--winsw=" + tmp.resolve("no-winsw.exe"));
            Outcome seal = run(cli, "seal-host", platforms[index]);

            assertRefusedAsAPlatform(prepare);
            assertRefusedAsAPlatform(seal);
        }
    }
}
