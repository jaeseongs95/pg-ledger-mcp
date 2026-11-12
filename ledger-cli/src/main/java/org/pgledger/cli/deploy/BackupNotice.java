package org.pgledger.cli.deploy;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;

/**
 * What a command tells about the backup of a file of 1.x ({@link DeployFiles.Backup}): that it moved the previous file there
 * (the first run), or that the backup was there already with the same bytes and was left as it was (a later run after the
 * rollback of the migration).
 */
final class BackupNotice {

    private BackupNotice() {
    }

    static BiConsumer<Path, DeployFiles.Backup> told(CliOutput output, CliMessages messages) {
        return (backup, how) -> output.info(messages.get(switch (how) {
            case MOVED -> "render-deploy.kept";
            case KEPT -> "render-deploy.kept-existing";
        }, backup.toString()));
    }
}
