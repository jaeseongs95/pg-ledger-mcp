package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/**
 * REH2-INS-02 at the level of the writer, on any file system: a file of 1.x and a backup with other bytes. The writer that
 * {@code prepare-host} uses ({@link DeployFiles#writeIntoHost}) lets the refusal reach the command, which reports every failure
 * of the host with {@link HostFiles#failure}: HOST_FAILED naming both files, never the raw
 * {@code FileAlreadyExistsException}.
 */
class BackupDiffersTest {

    private static final String ONE_X = "[Unit]\nDescription=1.x\n";
    private static final String UNIT = "/etc/systemd/system/pg-ledger-mcp.service";
    private static final String RENDERED = Text.GENERATED + "\n[Unit]\nDescription=2.0\n";

    @TempDir
    Path tmp;

    private final DeployFiles files = new DeployFiles();

    @Test
    @DisplayName("REH2-INS-02 a file of 1.x and a backup with other bytes: BackupDiffersException naming both files, nothing of either changed; prepare-host reports it as HOST_FAILED naming both files, not as FileAlreadyExistsException")
    void otherBytesAreRefused() throws IOException {
        Path unit = tmp.resolve("etc/systemd/system/pg-ledger-mcp.service");
        Path backup = unit.resolveSibling("pg-ledger-mcp.service" + DeployFiles.BACKUP_SUFFIX);
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, ONE_X + "# changed since\n", StandardCharsets.UTF_8);
        Files.writeString(backup, ONE_X, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> files.writeIntoHost(tmp, List.of(new RenderedFile(UNIT, RENDERED)), path -> { }))
                .isInstanceOfSatisfying(DeployFiles.BackupDiffersException.class, refused -> {
                    assertThat(refused.getFile()).isEqualTo(unit.toString());
                    assertThat(refused.getOtherFile()).isEqualTo(backup.toString());
                    FailureException failure = HostFiles.failure(refused);
                    assertThat(failure.code()).isEqualTo(ErrorCode.HOST_FAILED);
                    assertThat(failure.arguments().getFirst()).contains(unit.toString()).contains(backup.toString());
                    assertThat(String.join(" ", failure.arguments())).doesNotContain("FileAlreadyExistsException");
                });
        assertThat(Files.readString(unit, StandardCharsets.UTF_8)).isEqualTo(ONE_X + "# changed since\n");
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(ONE_X);
    }
}
