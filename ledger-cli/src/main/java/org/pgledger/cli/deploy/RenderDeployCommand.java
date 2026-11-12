package org.pgledger.cli.deploy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code render-deploy --java=<path> --jdbc-url=<url> --app-account=<account> [...]}: writes the definition of the two
 * services (systemd units, or WinSW configurations), the settings files and, with {@code --nginx-conf}, the nginx site of the
 * two public host names, from the parameters ({@link DeployParameters}, {@link DeployRenderer}). It needs no database and writes no secret.
 * All parameters are validated before the first file is written, so a refused value leaves nothing written. {@code --root}
 * writes below a directory instead of at the paths of the files (a test, or a look at the files before they are put in
 * place). The command is the one {@code deploy/install.sh} (Linux) and {@code deploy/install.cmd} (Windows,
 * {@code --platform=windows}) call; they run it as root or as an administrator, which is the only reason the files can be
 * put into {@code /etc} or the service directories. The targets (a personal PC, a Windows server, a Linux server) differ by
 * the options only: {@code --platform}, {@code --home}, the host names, and {@code --nginx-conf} (the site of the reverse
 * proxy, Linux only).
 */
@Component
public class RenderDeployCommand implements Command {

    /** The options of the command; the documentation test reads this set, so an option cannot be added undocumented. */
    public static final Set<String> OPTIONS = DeployOptions.NAMES;

    private final CliOutput output;
    private final CliMessages messages;

    RenderDeployCommand(CliOutput output, CliMessages messages) {
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.RENDER_DEPLOY;
    }

    @Override
    public Set<String> options() {
        return OPTIONS;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        boolean check = CliOptions.flag(arguments, DeployOptions.CHECK);
        DeployInput input = DeployInput.read(arguments);
        DeployParameters parameters = input.parameters();
        Path root = input.root();
        if (check) {
            // the installers run this before they change anything: a bad option stops them while nothing exists yet
            output.info(messages.get("render-deploy.checked", parameters.layout().name()));
            return;
        }
        for (Path file : DeployFiles.write(root, DeployRenderer.render(parameters),
                backup -> output.info(messages.get("render-deploy.kept", backup.toString())))) {
            output.info(messages.get("render-deploy.written", file.toString()));
        }
    }
}
