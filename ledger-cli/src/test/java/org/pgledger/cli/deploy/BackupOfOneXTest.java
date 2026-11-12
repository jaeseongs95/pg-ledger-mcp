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
import org.junit.jupiter.api.io.TempDir;

/**
 * REH2-INS-01 at the level of the writer, on any file system (the command test of {@code prepare-host} for the Linux layout
 * needs POSIX permissions): a file of 1.x whose backup {@code <file>.1x-backup} is already there with the same bytes.
 */
class BackupOfOneXTest {

    private static final String ONE_X = "[Unit]\nDescription=1.x\n";
    private static final String UNIT = "/etc/systemd/system/pg-ledger-mcp.service";
    private static final String RENDERED = Text.GENERATED + "\n[Unit]\nDescription=2.0\n";

    @TempDir
    Path tmp;

    private final DeployFiles files = new DeployFiles();

    @Test
    @DisplayName("REH2-INS-01 a file of 1.x and a backup with the same bytes (what the README rollback leaves): the writer goes on, the backup is kept as it is and told as kept, the file is the rendered one; the next run keeps the backup too")
    void sameBytesAreKept() throws IOException {
        Path unit = tmp.resolve("etc/systemd/system/pg-ledger-mcp.service");
        Path backup = unit.resolveSibling("pg-ledger-mcp.service" + DeployFiles.BACKUP_SUFFIX);
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, ONE_X, StandardCharsets.UTF_8);
        Files.writeString(backup, ONE_X, StandardCharsets.UTF_8);
        List<Path> kept = new ArrayList<>();

        List<Path> written = files.write(tmp, List.of(new RenderedFile(UNIT, RENDERED)), kept::add);

        assertThat(written).containsExactly(unit);
        assertThat(kept).containsExactly(backup);
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(ONE_X);
        assertThat(Files.readString(unit, StandardCharsets.UTF_8)).isEqualTo(RENDERED);
        files.write(tmp, List.of(new RenderedFile(UNIT, RENDERED)), kept::add);
        assertThat(kept).hasSize(1);
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(ONE_X);
    }
}
