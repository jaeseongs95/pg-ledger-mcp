package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * A Linux server: the services are systemd units ({@link SystemdUnits}), the jars are below {@code /opt/<name>} and the
 * settings below {@code /etc/<name>}, owned by the system user of the instance. Run as root, {@code prepare-host} makes the
 * directories {@code <lib>} (root, 0755), {@code <etc>} (the service user, 0750), {@code <etc>/drivers} (root, 0755) and
 * {@code <etc>/secrets} (the service user, 0700) and copies the three jars and the JDBC driver jar with mode 0644 ({@code
 * install -d} and {@code install -m} did this). The secrets are the service user's from the start, so there is nothing to seal.
 */
@Component
final class LinuxPlatform implements ServicePlatform {

    private final HostAccounts accounts;
    private final HostFiles host;
    private final DeployFiles files;

    LinuxPlatform(HostAccounts accounts, HostFiles host, DeployFiles files) {
        this.accounts = accounts;
        this.host = host;
        this.files = files;
    }

    @Override
    public Platform platform() {
        return Platform.LINUX;
    }

    @Override
    public DeployLayout layout(String name, String home) {
        if (home != null) {
            // the home is the one directory of a Windows installation; a Linux one follows the name
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, DeployOptions.HOME);
        }
        return DeployLayout.linux(name);
    }

    @Override
    public List<RenderedFile> serviceDefinitions(DeployParameters p) {
        DeployLayout layout = p.layout();
        Service mcp = Service.mcp(layout);
        Service admin = Service.admin(layout);
        return List.of(
                new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + mcp.name() + ".service",
                        SystemdUnits.unit(p, mcp, "network-online.target", null)),
                new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + admin.name() + ".service",
                        SystemdUnits.unit(p, admin, "network-online.target " + mcp.name() + ".service", layout.etc())));
    }

    @Override
    public List<Path> wrappers(ApplicationArguments arguments) {
        if (CliOptions.value(arguments, DeployOptions.WINSW).isPresent()) {
            // WinSW is the service wrapper of Windows; systemd needs none
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        return List.of();
    }

    @Override
    public void prepare(DeployLayout layout, Path root, Path jars, Path driver, List<Path> wrappers) throws IOException {
        Path lib = files.resolve(root, layout.lib());
        Path etc = files.resolve(root, layout.etc());
        FileSystem fileSystem = lib.getFileSystem();
        host.requireView(fileSystem, "posix");
        // the accounts are looked up before the first change: a host without the service user is refused with nothing done
        UserPrincipal user = accounts.user(fileSystem, layout.serviceUser());
        GroupPrincipal group = accounts.group(fileSystem, layout.serviceUser());
        host.directory(lib, "rwxr-xr-x", null, null);
        host.directory(etc, "rwxr-x---", user, group);
        host.directory(etc.resolve("drivers"), "rwxr-xr-x", null, null);
        host.directory(etc.resolve("secrets"), "rwx------", user, group);
        host.copyProduct(jars, driver, lib, etc.resolve("drivers"));
    }

    @Override
    public void seal(DeployLayout layout, Path root) {
        // the secrets are the service user's from the start: seal-host is a Windows step
        throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, DeployOptions.PLATFORM);
    }
}
