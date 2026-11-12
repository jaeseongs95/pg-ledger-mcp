package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code prepare-host --jars=<dir> --driver-jar=<file> [--winsw=<file>] <the options of render-deploy>}: everything an
 * installation does to the files of the host before the secrets exist, in Java, so that the installers keep only what Java
 * cannot do (creating the service user, switching to it, the service managers, nginx). The same options as
 * {@code render-deploy} validate first, and a refused value changes nothing. What is done to the folders and the jars is
 * the business of the platform ({@link ServicePlatform#prepare}); then the rendered files are written, and a platform that is
 * not the platform of this host is refused before anything is read (without {@code --root}):
 *
 * <ul>
 * <li>Linux (run as root): the directories, the three jars and the JDBC driver jar copied with mode 0644 ({@link LinuxPlatform}).
 * <li>Windows (run in an elevated prompt): the home and its folders closed to every account but SYSTEM and Administrators, the
 *     secrets of an earlier run given back to the installer, the jars, the two WinSW executables and the driver copied
 *     ({@link WindowsPlatform}).
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

    /** The options of the command; the documentation test reads this set, so an option cannot be added undocumented. */
    public static final Set<String> OPTIONS = optionNames();

    private final DeployInputReader inputs;
    private final DeployRenderer renderer;
    private final DeployFiles files;
    private final ServicePlatforms platforms;
    private final CliOutput output;
    private final CliMessages messages;

    PrepareHostCommand(DeployInputReader inputs, DeployRenderer renderer, DeployFiles files, ServicePlatforms platforms,
            CliOutput output, CliMessages messages) {
        this.inputs = inputs;
        this.renderer = renderer;
        this.files = files;
        this.platforms = platforms;
        this.output = output;
        this.messages = messages;
    }

    private static Set<String> optionNames() {
        Set<String> names = new HashSet<>(DeployOptions.ALL);
        names.addAll(List.of(JARS, DRIVER_JAR, DeployOptions.WINSW, DeployOptions.TLS_ACME));
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
        DeployInput input = inputs.read(arguments);
        DeployParameters parameters = input.parameters();
        DeployLayout layout = parameters.layout();
        files.requireReachable(input.root(), layout);
        ServicePlatform platform = platforms.of(layout.platform());
        Path jars = Path.of(CliOptions.required(arguments, JARS));
        Path driver = Path.of(CliOptions.required(arguments, DRIVER_JAR));
        List<Path> wrappers = platform.wrappers(arguments);
        List<Path> sources = new ArrayList<>(List.of(jars.resolve(DeployLayout.CLI_JAR),
                jars.resolve(DeployLayout.MCP_JAR), jars.resolve(DeployLayout.ADMIN_JAR), driver));
        sources.addAll(wrappers);
        try {
            for (Path source : sources) {
                if (!Files.isRegularFile(source)) {
                    throw new FailureException(ErrorCode.HOST_FAILED, source.toString(), "NoSuchFile");
                }
            }
            platform.prepare(layout, input.root(), jars, driver, wrappers);
            if (parameters.nginx() != null && parameters.nginx().acme() != null) {
                platform.prepareTls(layout, input.root());
            }
            for (Path file : files.writeIntoHost(input.root(), renderer.render(parameters),
                    BackupNotice.told(output, messages))) {
                output.info(messages.get("render-deploy.written", file.toString()));
            }
        } catch (IOException e) {
            throw HostFiles.failure(e);
        }
        output.info(messages.get("prepare-host.prepared", layout.name()));
    }
}
