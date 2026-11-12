package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.pgledger.cli.secret.ProcessUser;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * A personal PC or a Windows server: the services are WinSW configurations ({@link WinswServices}) below one home
 * directory ({@code --home}, for example {@code C:/ProgramData/<name>}), run by the one account LocalService.
 * {@code prepare-host} (run in an elevated prompt) closes the home and its folders to every account but SYSTEM and
 * Administrators ({@link WindowsAccess}), gives the secrets of an earlier run back to the installer (the folder first, then
 * its files) so the commands of the run can read them, and copies the jars, the two WinSW executables and the driver;
 * {@code seal-host} then gives the service account what it needs (read and run the jars, change the settings folder, where the
 * driver page of the admin web writes its registration, write the logs) and hands the secrets over, the files first and then
 * the folder: the service account is their owner and has full access, Administrators can only see that a file exists, and
 * nobody else, the installer included, has any. {@code mkdir}, {@code icacls} and {@code copy} did this.
 */
@Component
final class WindowsPlatform implements ServicePlatform {

    private final HostAccounts accounts;
    private final ProcessUser processUser;
    private final WindowsAccess access;
    private final HostFiles host;
    private final DeployFiles files;

    WindowsPlatform(HostAccounts accounts, ProcessUser processUser, WindowsAccess access, HostFiles host,
            DeployFiles files) {
        this.accounts = accounts;
        this.processUser = processUser;
        this.access = access;
        this.host = host;
        this.files = files;
    }

    @Override
    public Platform platform() {
        return Platform.WINDOWS;
    }

    @Override
    public DeployLayout layout(String name, String home) {
        if (home == null) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        return DeployLayout.windows(name, DeployForms.requirePath(home, DeployForms.WINDOWS_PATH, DeployOptions.HOME));
    }

    @Override
    public List<RenderedFile> serviceDefinitions(DeployParameters p) {
        DeployLayout layout = p.layout();
        Service mcp = Service.mcp(layout);
        Service admin = Service.admin(layout);
        return List.of(
                new RenderedFile(layout.lib() + "/" + mcp.name() + ".xml", WinswServices.configuration(p, mcp)),
                new RenderedFile(layout.lib() + "/" + admin.name() + ".xml", WinswServices.configuration(p, admin)));
    }

    @Override
    public List<Path> wrappers(ApplicationArguments arguments) {
        return List.of(Path.of(CliOptions.required(arguments, DeployOptions.WINSW)));
    }

    @Override
    public void prepare(DeployLayout layout, Path root, Path jars, Path driver, List<Path> wrappers) throws IOException {
        Path winsw = wrappers.getFirst();
        Path lib = files.resolve(root, layout.lib());
        Path etc = files.resolve(root, layout.etc());
        Path logs = files.resolve(root, layout.logs());
        FileSystem fileSystem = lib.getFileSystem();
        host.requireView(fileSystem, "acl");
        UserPrincipal system = accounts.user(fileSystem, WindowsAccess.SYSTEM);
        UserPrincipal administrators = accounts.user(fileSystem, WindowsAccess.ADMINISTRATORS);
        UserPrincipal installer = processUser.current(fileSystem);
        List<AclEntry> closed = WindowsAccess.closed(system, administrators);
        // the home first: a folder made below it takes what the home gives, and the file system propagates nothing by itself
        Path home = lib.getParent();
        Files.createDirectories(home);
        access.set(home, null, closed);
        Path secrets = etc.resolve("secrets");
        for (Path folder : List.of(lib, etc, etc.resolve("drivers"), secrets, logs)) {
            Files.createDirectories(folder);
            access.set(folder, null, closed);
        }
        // After an earlier run the secrets belong to the service account: given back to the installer for this run (the
        // folder first, then its files); seal-host hands them over again at the end.
        access.set(secrets, installer, WindowsAccess.takenBack(installer, true));
        try (var secretFiles = Files.list(secrets)) {
            for (Path file : (Iterable<Path>) secretFiles.filter(Files::isRegularFile)::iterator) {
                access.set(file, installer, WindowsAccess.takenBack(installer, false));
            }
        }
        host.copyProduct(jars, driver, lib, etc.resolve("drivers"));
        files.copy(winsw, lib.resolve(layout.mcpName() + ".exe"));
        files.copy(winsw, lib.resolve(layout.adminName() + ".exe"));
    }

    @Override
    public void seal(DeployLayout layout, Path root) throws IOException {
        Path lib = files.resolve(root, layout.lib());
        Path etc = files.resolve(root, layout.etc());
        Path logs = files.resolve(root, layout.logs());
        Path secrets = etc.resolve("secrets");
        FileSystem fileSystem = lib.getFileSystem();
        host.requireView(fileSystem, "acl");
        UserPrincipal service = accounts.user(fileSystem, DeployLayout.WINDOWS_SERVICE_ACCOUNT);
        UserPrincipal administrators = accounts.user(fileSystem, WindowsAccess.ADMINISTRATORS);
        access.grantTree(lib, service, WindowsAccess.READ_EXECUTE, secrets);
        access.grantTree(etc, service, WindowsAccess.MODIFY, secrets);
        access.grantTree(logs, service, WindowsAccess.MODIFY, secrets);
        try (var secretFiles = Files.list(secrets)) {
            for (Path file : (Iterable<Path>) secretFiles.filter(Files::isRegularFile)::iterator) {
                access.set(file, service, WindowsAccess.handedOver(service, administrators, false));
            }
        }
        access.set(secrets, service, WindowsAccess.handedOver(service, administrators, true));
    }
}
