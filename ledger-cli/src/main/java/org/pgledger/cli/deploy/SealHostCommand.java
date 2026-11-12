package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Set;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code seal-host <the options of render-deploy>}: the last file step of an installation on Windows, run after the secrets
 * are stored and the services are registered (what {@code icacls} did in {@code install.cmd}). The one service account
 * ({@link DeployLayout#WINDOWS_SERVICE_ACCOUNT}) gets what it needs: read and run the jars ({@code <lib>}), change the settings
 * folder ({@code <etc>}, where the driver page of the admin web writes its registration) and write the logs; the secrets are
 * handed over, the files first and then the folder: the service account is their owner and has full access, Administrators
 * can only see that a file exists, and nobody else, the installer included, has any ({@link WindowsAccess}). On Linux nothing
 * is left to do (the secrets are the service user's from the start), and the command refuses.
 */
@Component
public class SealHostCommand implements Command {

    private final HostAccounts accounts;
    private final CliOutput output;
    private final CliMessages messages;

    SealHostCommand(HostAccounts accounts, CliOutput output, CliMessages messages) {
        this.accounts = accounts;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.SEAL_HOST;
    }

    @Override
    public Set<String> options() {
        return Set.copyOf(DeployOptions.ALL);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        DeployInput input = DeployInput.read(arguments);
        DeployLayout layout = input.parameters().layout();
        if (layout.platform() != Platform.WINDOWS) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, DeployOptions.PLATFORM);
        }
        Path lib = DeployFiles.resolve(input.root(), layout.lib());
        Path etc = DeployFiles.resolve(input.root(), layout.etc());
        Path logs = DeployFiles.resolve(input.root(), layout.logs());
        Path secrets = etc.resolve("secrets");
        FileSystem fileSystem = lib.getFileSystem();
        PrepareHostCommand.requireView(fileSystem, "acl");
        try {
            UserPrincipal service = accounts.user(fileSystem, DeployLayout.WINDOWS_SERVICE_ACCOUNT);
            UserPrincipal administrators = accounts.user(fileSystem, WindowsAccess.ADMINISTRATORS);
            WindowsAccess.grantTree(lib, service, WindowsAccess.READ_EXECUTE, secrets);
            WindowsAccess.grantTree(etc, service, WindowsAccess.MODIFY, secrets);
            WindowsAccess.grantTree(logs, service, WindowsAccess.MODIFY, secrets);
            try (var files = Files.list(secrets)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    WindowsAccess.set(file, service, WindowsAccess.handedOver(service, administrators, false));
                }
            }
            WindowsAccess.set(secrets, service, WindowsAccess.handedOver(service, administrators, true));
        } catch (IOException e) {
            throw PrepareHostCommand.hostFailure(e);
        }
        output.info(messages.get("seal-host.sealed", layout.name()));
    }
}
