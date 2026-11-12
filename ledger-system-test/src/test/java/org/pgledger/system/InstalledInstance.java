package org.pgledger.system;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The instance that {@code render-deploy} makes in a temporary directory, on the platform of the host the system test runs on:
 * the one place that knows what differs (W7-SYS-03). The platform is chosen by what the file system can do (the POSIX attribute
 * view: Linux; otherwise Windows), never by the name of the operating system (ARC-033).
 *
 * <ul>
 * <li>Windows: {@code --platform=windows --home=<directory>}, the settings in {@code <home>/etc}, the service definition the
 * WinSW file {@code <home>/lib/pg-ledger-mcp.xml}. The paths of the product are drive paths without spaces, so a temporary
 * directory that has none cannot hold the instance (the test is skipped, as before).</li>
 * <li>Linux: {@code --platform=linux --root=<directory> --name=<name>}, the files of the unit below the root
 * ({@code <root>/etc/<name>}, {@code <root>/opt/<name>}, the unit {@code <root>/etc/systemd/system/<name>.service}); the
 * arguments of the unit name the paths of a real installation ({@code /etc/<name>}, {@code /opt/<name>}), which are mapped to
 * the root. Nothing that needs root runs: no account, no service, no {@code prepare-host}.</li>
 * </ul>
 */
final class InstalledInstance {

    private static final Pattern DRIVE_PATH = Pattern.compile("[A-Za-z]:(?:/[A-Za-z0-9._+-]+)+");
    private static final Pattern WINDOWS_ARGUMENTS = Pattern.compile("<arguments>(.*?)</arguments>");
    private static final String WINDOWS_NAME = "pg-ledger-mcp";
    private static final String LINUX_NAME = "systest";

    private enum Platform {
        LINUX, WINDOWS
    }

    private final Platform platform;
    private final Path directory;

    private InstalledInstance(Platform platform, Path directory) {
        this.platform = platform;
        this.directory = directory;
    }

    /**
     * The instance below {@code work}. On Windows the test is skipped (an assumption) when the directory is no drive path
     * without spaces.
     */
    static InstalledInstance on(Path work) {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Path directory = work.resolve(posix ? "root" : "home");
        if (!posix) {
            assumeTrue(DRIVE_PATH.matcher(slash(directory)).matches(),
                    "render-deploy --platform=windows needs a drive path without spaces for the instance home: " + directory);
        }
        return new InstalledInstance(posix ? Platform.LINUX : Platform.WINDOWS, directory);
    }

    /** The Linux layout below {@code directory}, whatever the host is: for the test of the layout itself (it renders, nothing more). */
    static InstalledInstance linuxBelow(Path directory) {
        return new InstalledInstance(Platform.LINUX, directory);
    }

    /** True on the platform whose paths are those of the packaged Linux server. */
    boolean isLinux() {
        return platform == Platform.LINUX;
    }

    /** The settings directory of the instance on this host (settings, registration, driver jars, the secret directory). */
    Path etc() {
        return platform == Platform.LINUX ? directory.resolve("etc").resolve(LINUX_NAME) : directory.resolve("etc");
    }

    /** The directory of the jars of the instance on this host. */
    Path lib() {
        return platform == Platform.LINUX ? directory.resolve("opt").resolve(LINUX_NAME) : directory.resolve("lib");
    }

    /** The arguments of {@code render-deploy} for this instance. */
    List<String> renderArguments(String java, String jdbcUrl, String appAccount, String schema, int mcpPort) {
        List<String> arguments = new ArrayList<>();
        if (platform == Platform.LINUX) {
            arguments.addAll(List.of("--platform=linux", "--root=" + slash(directory), "--name=" + LINUX_NAME));
        } else {
            arguments.addAll(List.of("--platform=windows", "--home=" + slash(directory)));
        }
        arguments.addAll(List.of("--java=" + java, "--jdbc-url=" + jdbcUrl, "--app-account=" + appAccount,
                "--schema=" + schema, "--mcp-port=" + mcpPort));
        return arguments;
    }

    /** The service definition of the MCP server as rendered: the WinSW file, or the systemd unit. */
    String serviceDefinition() throws IOException {
        return Files.readString(platform == Platform.LINUX
                ? directory.resolve("etc").resolve("systemd").resolve("system").resolve(LINUX_NAME + ".service")
                : lib().resolve(WINDOWS_NAME + ".xml"));
    }

    /**
     * The Java arguments of the MCP server as the service definition has them ({@code -Duser.timezone=UTC -jar <jar>
     * --spring.config.import=...}), with the paths of a real Linux installation mapped to this instance.
     *
     * @throws AssertionError when the definition has no arguments of the server
     */
    List<String> serverArguments() throws IOException {
        String definition = serviceDefinition();
        String line;
        if (platform == Platform.LINUX) {
            line = definition.lines().filter(text -> text.startsWith("ExecStart=")).findFirst()
                    .orElseThrow(() -> new AssertionError("no ExecStart in the unit: " + definition));
            // the Java executable is the first word; the rest are the arguments
            line = line.substring("ExecStart=".length());
            line = line.substring(line.indexOf(' ') + 1);
        } else {
            Matcher arguments = WINDOWS_ARGUMENTS.matcher(definition);
            if (!arguments.find()) {
                throw new AssertionError("the arguments of the MCP service in " + definition);
            }
            line = arguments.group(1);
        }
        List<String> mapped = new ArrayList<>();
        for (String argument : line.split(" ")) {
            mapped.add(platform == Platform.LINUX ? argument.replace("/etc/" + LINUX_NAME, slash(etc()))
                    .replace("/opt/" + LINUX_NAME, slash(lib())) : argument);
        }
        return List.copyOf(mapped);
    }

    static String slash(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
