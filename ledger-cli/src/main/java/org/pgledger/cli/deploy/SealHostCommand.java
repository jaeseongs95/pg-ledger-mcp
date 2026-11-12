package org.pgledger.cli.deploy;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code seal-host <the options of render-deploy>}: the last file step of an installation on Windows, run after the secrets
 * are stored and the services are registered (what {@code icacls} did in {@code install.cmd}). The platform does it
 * ({@link ServicePlatform#seal}; {@link WindowsPlatform} describes what the service account gets and how the secrets are
 * handed over). On Linux nothing is left to do (the secrets are the service user's from the start), and the command refuses.
 */
@Component
public class SealHostCommand implements Command {

    private final DeployInputReader inputs;
    private final DeployFiles files;
    private final ServicePlatforms platforms;
    private final CliOutput output;
    private final CliMessages messages;

    SealHostCommand(DeployInputReader inputs, DeployFiles files, ServicePlatforms platforms, CliOutput output,
            CliMessages messages) {
        this.inputs = inputs;
        this.files = files;
        this.platforms = platforms;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.SEAL_HOST;
    }

    @Override
    public Set<String> options() {
        Set<String> names = new java.util.HashSet<>(DeployOptions.ALL);
        names.add(DeployOptions.TLS_ACME);
        return Set.copyOf(names);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        DeployInput input = inputs.read(arguments);
        DeployLayout layout = input.parameters().layout();
        files.requireReachable(input.root(), layout);
        try {
            platforms.of(layout.platform()).seal(layout, input.root());
        } catch (IOException e) {
            throw HostFiles.failure(e);
        }
        output.info(messages.get("seal-host.sealed", layout.name()));
    }
}
