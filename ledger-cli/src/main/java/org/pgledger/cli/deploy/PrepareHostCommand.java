package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.pgledger.cli.secret.ProcessUser;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code prepare-host --jars=<dir> --driver-jar=<file> [--winsw=<file>] <the options of render-deploy>}: everything an
 * installation does to the files of the host before the secrets exist, in Java, so that the installers keep only what Java
 * cannot do (creating the service user, switching to it, the service managers, nginx). The same options as
 * {@code render-deploy} validate first, and a refused value changes nothing. Then:
 *
 * <ul>
 * <li>Linux (run as root): the directories {@code <lib>} (root, 0755), {@code <etc>} (the service user, 0750),
 *     {@code <etc>/drivers} (root, 0755) and {@code <etc>/secrets} (the service user, 0700); the three jars and the JDBC
 *     driver jar copied with mode 0644; the rendered files. {@code install -d} and {@code install -m} did this.
 * <li>Windows (run in an elevated prompt): the home and its folders closed to every account but SYSTEM and Administrators
 *     ({@link WindowsAccess}); the secrets of an earlier run given back to the installer, so that the commands of this run can
 *     read them (the folder first, then its files); the jars, the two WinSW executables and the driver copied; the rendered
 *     files. {@code mkdir}, {@code icacls} and {@code copy} did this.
 * </ul>
 *
 * <p>Ownership and the modes of a directory are set with the attribute views of the file system, chosen by what the file
 * system supports (never by the name of the operating system), and a file system that has neither a view nor the other is
 * refused. An existing directory gets the same owner and mode as a new one (a re-run is an upgrade), a file of 1.x that a
 * rendered file replaces is kept ({@link DeployFiles#BACKUP_SUFFIX}). {@code --root} puts everything below a directory (a test).
 * The Java 25 check is the JVM's own: the jars are compiled for Java 25 and another runtime refuses to load them.
 */
@Component
public class PrepareHostCommand implements Command {

    static final String JARS = "jars";
    static final String DRIVER_JAR = "driver-jar";
    /** Windows: the WinSW executable, copied once for each of the two services. */
    static final String WINSW = "winsw";

    /** The options of the command; the documentation test reads this set, so an option cannot be added undocumented. */
    public static final Set<String> OPTIONS = optionNames();

    private final HostAccounts accounts;
    private final ProcessUser processUser;
    private final CliOutput output;
    private final CliMessages messages;

    PrepareHostCommand(HostAccounts accounts, ProcessUser processUser, CliOutput output, CliMessages messages) {
        this.accounts = accounts;
        this.processUser = processUser;
        this.output = output;
        this.messages = messages;
    }

    private static Set<String> optionNames() {
        Set<String> names = new HashSet<>(DeployOptions.ALL);
        names.addAll(List.of(JARS, DRIVER_JAR, WINSW));
        return Set.copyOf(names);
    }

    @Override
    public CliCommand name() {
        return CliCommand.PREPARE_HOST;
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
        DeployInput input = DeployInput.read(arguments);
        DeployParameters parameters = input.parameters();
        boolean windows = parameters.layout().platform() == Platform.WINDOWS;
        Path jars = Path.of(CliOptions.required(arguments, JARS));
        Path driver = Path.of(CliOptions.required(arguments, DRIVER_JAR));
        Path winsw = null;
        if (windows) {
            winsw = Path.of(CliOptions.required(arguments, WINSW));
        } else if (CliOptions.value(arguments, WINSW).isPresent()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        List<Path> sources = new ArrayList<>(List.of(jars.resolve(DeployLayout.CLI_JAR),
                jars.resolve(DeployLayout.MCP_JAR), jars.resolve(DeployLayout.ADMIN_JAR), driver));
        if (winsw != null) {
            sources.add(winsw);
        }
        try {
            for (Path source : sources) {
                if (!Files.isRegularFile(source)) {
                    throw new FailureException(ErrorCode.HOST_FAILED, source.toString(), "NoSuchFile");
                }
            }
            if (windows) {
                prepareWindows(parameters.layout(), input.root(), jars, driver, winsw);
            } else {
                prepareLinux(parameters.layout(), input.root(), jars, driver);
            }
            for (Path file : DeployFiles.write(input.root(), DeployRenderer.render(parameters),
                    backup -> output.info(messages.get("render-deploy.kept", backup.toString())))) {
                output.info(messages.get("render-deploy.written", file.toString()));
            }
        } catch (IOException e) {
            throw hostFailure(e);
        }
        output.info(messages.get("prepare-host.prepared", parameters.layout().name()));
    }

    private void prepareLinux(DeployLayout layout, Path root, Path jars, Path driver) throws IOException {
        Path lib = DeployFiles.resolve(root, layout.lib());
        Path etc = DeployFiles.resolve(root, layout.etc());
        FileSystem fileSystem = lib.getFileSystem();
        requireView(fileSystem, "posix");
        // the accounts are looked up before the first change: a host without the service user is refused with nothing done
        UserPrincipal user = accounts.user(fileSystem, layout.serviceUser());
        GroupPrincipal group = accounts.group(fileSystem, layout.serviceUser());
        directory(lib, "rwxr-xr-x", null, null);
        directory(etc, "rwxr-x---", user, group);
        directory(etc.resolve("drivers"), "rwxr-xr-x", null, null);
        directory(etc.resolve("secrets"), "rwx------", user, group);
        copyProduct(jars, driver, lib, etc.resolve("drivers"));
    }

    /** Creates the directory when it is not there and gives it the owner and the mode, as {@code install -d -m -o -g} did. */
    private static void directory(Path path, String mode, UserPrincipal owner, GroupPrincipal group) throws IOException {
        Files.createDirectories(path);
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (owner != null) {
            view.setOwner(owner);
            view.setGroup(group);
        }
        view.setPermissions(PosixFilePermissions.fromString(mode));
    }

    private void prepareWindows(DeployLayout layout, Path root, Path jars, Path driver, Path winsw) throws IOException {
        Path lib = DeployFiles.resolve(root, layout.lib());
        Path etc = DeployFiles.resolve(root, layout.etc());
        Path logs = DeployFiles.resolve(root, layout.logs());
        FileSystem fileSystem = lib.getFileSystem();
        requireView(fileSystem, "acl");
        UserPrincipal system = accounts.user(fileSystem, WindowsAccess.SYSTEM);
        UserPrincipal administrators = accounts.user(fileSystem, WindowsAccess.ADMINISTRATORS);
        UserPrincipal installer = processUser.current(fileSystem);
        List<AclEntry> closed = WindowsAccess.closed(system, administrators);
        // the home first: a folder made below it takes what the home gives, and the file system propagates nothing by itself
        Path home = lib.getParent();
        Files.createDirectories(home);
        WindowsAccess.set(home, null, closed);
        Path secrets = etc.resolve("secrets");
        for (Path folder : List.of(lib, etc, etc.resolve("drivers"), secrets, logs)) {
            Files.createDirectories(folder);
            WindowsAccess.set(folder, null, closed);
        }
        // After an earlier run the secrets belong to the service account: given back to the installer for this run (the
        // folder first, then its files); seal-host hands them over again at the end.
        WindowsAccess.set(secrets, installer, WindowsAccess.takenBack(installer, true));
        try (var files = Files.list(secrets)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                WindowsAccess.set(file, installer, WindowsAccess.takenBack(installer, false));
            }
        }
        copyProduct(jars, driver, lib, etc.resolve("drivers"));
        DeployFiles.copy(winsw, lib.resolve(layout.mcpName() + ".exe"));
        DeployFiles.copy(winsw, lib.resolve(layout.adminName() + ".exe"));
    }

    private static void copyProduct(Path jars, Path driver, Path lib, Path drivers) throws IOException {
        for (String jar : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR)) {
            DeployFiles.copy(jars.resolve(jar), lib.resolve(jar));
        }
        DeployFiles.copy(driver, drivers.resolve(driver.getFileName().toString()));
    }

    static void requireView(FileSystem fileSystem, String view) {
        if (!fileSystem.supportedFileAttributeViews().contains(view)) {
            throw new FailureException(ErrorCode.HOST_UNSUPPORTED, view);
        }
    }

    /** The path (or the account) and the kind of the failure; never the text of the exception. */
    static FailureException hostFailure(IOException e) {
        String subject = e instanceof FileSystemException failed && failed.getFile() != null ? failed.getFile()
                : e instanceof UserPrincipalNotFoundException missing ? missing.getName() : "-";
        return new FailureException(ErrorCode.HOST_FAILED, e, subject, e.getClass().getSimpleName());
    }
}
