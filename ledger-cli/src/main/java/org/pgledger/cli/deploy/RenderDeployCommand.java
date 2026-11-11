package org.pgledger.cli.deploy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * {@code render-deploy --java=<path> --jdbc-url=<url> --mcp-account=<account> --admin-account=<account> [...]}: writes the two
 * systemd units, the settings file of each service and, with {@code --nginx-conf}, the nginx site of the two public host
 * names, from the parameters ({@link DeployParameters}, {@link DeployRenderer}). It needs no database and writes no secret.
 * All parameters are validated before the first file is written, so a refused value leaves nothing written. {@code --root}
 * writes below a directory instead of {@code /} (a test, or a look at the files before they are put in place). The command
 * is the one {@code deploy/install.sh} calls; it runs as root there, which is the only reason the files can be put into
 * {@code /etc}.
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
        Map<String, String> values = new HashMap<>();
        for (String option : DeployOptions.ALL) {
            CliOptions.value(arguments, option).ifPresent(value -> values.put(option, value));
        }
        DeployParameters parameters = DeployParameters.of(values);
        Path root;
        try {
            root = Path.of(values.getOrDefault(DeployOptions.ROOT, "/"));
        } catch (InvalidPathException e) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, e, DeployOptions.ROOT);
        }
        for (Path file : DeployFiles.write(root, DeployRenderer.render(parameters))) {
            output.info(messages.get("render-deploy.written", file.toString()));
        }
    }
}
