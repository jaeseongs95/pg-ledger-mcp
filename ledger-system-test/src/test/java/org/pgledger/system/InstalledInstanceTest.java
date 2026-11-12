package org.pgledger.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W7-SYS-03: the Linux layout of the system test is rendered by the packaged {@code ledger-cli.jar} below a root and read back
 * on any host (the test needs no database and no account), so the branch that a Linux host takes is checked here too: the
 * settings and the unit are below the root, and the paths of the arguments of the unit, which are those of a real
 * installation ({@code /etc/<name>}, {@code /opt/<name>}), are mapped to the root.
 */
class InstalledInstanceTest {

    @TempDir
    Path work;

    @Test
    @DisplayName("W7-SYS-03 the Linux layout: render-deploy --platform=linux --root writes the settings and the unit below the root, and the arguments of the unit name the root")
    void theLinuxLayoutIsRenderedBelowTheRootAndMapped() throws IOException {
        InstalledInstance instance = InstalledInstance.linuxBelow(work.resolve("root"));
        List<String> command = new ArrayList<>(List.of("render-deploy"));
        command.addAll(instance.renderArguments("/usr/lib/jvm/java-25/bin/java", "jdbc:postgresql://127.0.0.1:5432/ledger",
                "ledger_app", "systest_schema", 8765));
        Path runs = Files.createDirectories(work.resolve("runs"));

        JavaProcess.Result rendered = JavaProcess.run(runs, Map.of(), null, 120, Path.of(System.getProperty("ledger.cli.jar")),
                command.toArray(String[]::new));

        assertThat(rendered.exit()).as(rendered.streams()).isZero();
        assertThat(instance.etc().resolve("application.yaml")).isRegularFile();
        assertThat(Files.readString(instance.etc().resolve("mcp.yaml"))).contains("port: 8765");
        List<String> arguments = instance.serverArguments();
        assertThat(arguments).contains("-jar");
        assertThat(arguments.get(arguments.indexOf("-jar") + 1)).isEqualTo(InstalledInstance.slash(instance.lib())
                + "/ledger-mcp-server.jar");
        assertThat(arguments.getLast()).startsWith("--spring.config.import=").contains("optional:configtree:")
                .contains(InstalledInstance.slash(instance.etc())).doesNotContain("file:/etc/systest")
                .doesNotContain("configtree:/etc/systest");
    }
}
