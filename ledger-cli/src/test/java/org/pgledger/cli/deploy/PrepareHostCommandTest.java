package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingHostAccounts;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresNonPosixFileSystem;
import org.pgledger.cli.support.RequiresPosixPermissions;

/**
 * INS-044: {@code prepare-host} does in Java what {@code install -d}, {@code install -m}, {@code mkdir}, {@code icacls} and
 * {@code copy} did in the installers: the directories with their owner and mode (Linux) or their ACLs (Windows), the copies of
 * the jars and the driver, the rendered files. Everything is put below a {@code --root}. Only an administrator can give a file to
 * another account, so the Linux tests play the service user with the account that runs the test (the names that are asked for
 * are checked) and the Windows test of the real ACLs runs only in an elevated prompt.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class PrepareHostCommandTest {

    private static final String[] COMMON = { "--jdbc-url=jdbc:postgresql://db.example.org:5432/ledger", "--app-account=ledger_app" };
    private static final String LINUX_JAVA = "--java=/usr/lib/jvm/java-25/bin/java";
    private static final String WINDOWS_JAVA = "--java=C:/Program Files/Java/jdk-25/bin/java.exe";

    @TempDir
    Path tmp;

    private Path jars() throws IOException {
        Path jars = tmp.resolve("jars");
        if (Files.exists(jars)) {
            return jars;
        }
        Files.createDirectories(jars);
        for (String jar : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR, "driver.jar", "WinSW.exe")) {
            Files.write(jars.resolve(jar), ("content of " + jar).getBytes(StandardCharsets.UTF_8));
        }
        return jars;
    }

    private Outcome run(Cli cli, RecordingOutput output, String... more) throws IOException {
        List<String> args = new ArrayList<>(List.of("prepare-host", "--jars=" + jars(), "--driver-jar=" + jars().resolve("driver.jar"),
                "--root=" + tmp.resolve("root")));
        args.addAll(List.of(COMMON));
        args.addAll(List.of(more));
        // the Java of the platform of the instance, unless the test gave one
        if (args.stream().noneMatch(arg -> arg.startsWith("--java="))) {
            args.add(args.contains("--platform=windows") ? WINDOWS_JAVA : LINUX_JAVA);
        }
        return cli.run(tmp.resolve("secrets"), null, output, args.toArray(String[]::new));
    }

    private RecordingHostAccounts testUser() throws IOException {
        UserPrincipal user = Files.getOwner(tmp);
        GroupPrincipal group = Files.readAttributes(tmp, PosixFileAttributes.class).group();
        String name = DeployTestBeans.defaults().instanceName();
        return new RecordingHostAccounts(Map.of(name, user), Map.of(name, group));
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("INS-044 Linux: the directories get their mode (lib 0755, etc 0750, drivers 0755, secrets 0700) and the service user as owner where the service uses them; the jars and the driver are copied with 0644; the files are rendered; the service user and its group are looked up by the name of the instance")
    void ins044_prepareLinux(Cli cli) throws IOException {
        RecordingHostAccounts accounts = testUser();
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli.withHostAccounts(accounts), output);

        assertThat(result.code()).isEmpty();
        Path root = tmp.resolve("root");
        assertThat(mode(root.resolve("opt/pg-ledger-mcp"))).isEqualTo("rwxr-xr-x");
        assertThat(mode(root.resolve("etc/pg-ledger-mcp"))).isEqualTo("rwxr-x---");
        assertThat(mode(root.resolve("etc/pg-ledger-mcp/drivers"))).isEqualTo("rwxr-xr-x");
        assertThat(mode(root.resolve("etc/pg-ledger-mcp/secrets"))).isEqualTo("rwx------");
        for (String jar : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR)) {
            Path copy = root.resolve("opt/pg-ledger-mcp").resolve(jar);
            assertThat(Files.readString(copy)).as(jar).isEqualTo("content of " + jar);
            assertThat(mode(copy)).as(jar).isEqualTo("rw-r--r--");
        }
        assertThat(Files.readString(root.resolve("etc/pg-ledger-mcp/drivers/driver.jar"))).isEqualTo("content of driver.jar");
        assertThat(Files.exists(root.resolve("etc/systemd/system/pg-ledger-mcp.service"))).as("the files are rendered").isTrue();
        assertThat(accounts.askedUsers()).containsExactly("pg-ledger-mcp");
        assertThat(accounts.askedGroups()).containsExactly("pg-ledger-mcp");
        assertThat(output.info()).contains("Prepared the host for the instance pg-ledger-mcp");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("INS-044 Linux: a re-run is an upgrade: a directory with the wrong mode gets the right one, a jar is replaced, a file of 1.x is kept as <file>.1x-backup")
    void ins044_aRerunRepairsAndReplaces(Cli cli) throws IOException {
        Path etc = tmp.resolve("root/etc/pg-ledger-mcp");
        Files.createDirectories(etc);
        Files.setPosixFilePermissions(etc, PosixFilePermissions.fromString("rwxrwxrwx"));
        Path lib = tmp.resolve("root/opt/pg-ledger-mcp");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve(DeployLayout.CLI_JAR), "an old jar");
        Path unit = tmp.resolve("root/etc/systemd/system/pg-ledger-mcp.service");
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, "[Unit]\nDescription=1.x\n");

        Outcome result = run(cli.withHostAccounts(testUser()), new RecordingOutput());

        assertThat(result.code()).isEmpty();
        assertThat(mode(etc)).isEqualTo("rwxr-x---");
        assertThat(Files.readString(lib.resolve(DeployLayout.CLI_JAR))).isEqualTo("content of ledger-cli.jar");
        assertThat(Files.readString(unit.resolveSibling("pg-ledger-mcp.service.1x-backup"))).isEqualTo("[Unit]\nDescription=1.x\n");
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("a bad name", new String[] { "--name=Bad_Name" }, ErrorCode.INVALID_DEPLOY_VALUE),
                Arguments.of("--winsw on Linux", new String[] { "--winsw=/x/WinSW.exe" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of("a service user that does not exist", new String[] { "--name=no-such-user-x1" }, ErrorCode.HOST_FAILED));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("refusals")
    @RequiresPosixPermissions
    @DisplayName("INS-044 Linux: a refused option, or a service user the host does not have, changes nothing: no directory is created")
    void ins044_refusalsChangeNothing(String label, String[] more, ErrorCode code, Cli cli) throws IOException {
        Outcome result = run(cli.withHostAccounts(new RecordingHostAccounts(Map.of(), Map.of())), new RecordingOutput(), more);

        assertThat(result.code()).as(label).contains(code);
        assertThat(Files.exists(tmp.resolve("root"))).as(label).isFalse();
    }

    @Test
    @DisplayName("INS-044 a missing jar is HOST_FAILED naming the file, a missing option is a usage error: nothing is created")
    void ins044_missingInputs(Cli cli) throws IOException {
        Files.delete(jars().resolve(DeployLayout.ADMIN_JAR));
        Outcome missingJar = run(cli, new RecordingOutput());
        Outcome noJars = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "prepare-host", "--root=" + tmp.resolve("root"),
                "--driver-jar=" + tmp.resolve("jars/driver.jar"), COMMON[0], COMMON[1], LINUX_JAVA);
        Outcome noWinsw = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "prepare-host", "--root=" + tmp.resolve("root"),
                "--jars=" + tmp.resolve("jars"), "--driver-jar=" + tmp.resolve("jars/driver.jar"), "--platform=windows",
                "--home=C:/ProgramData/pg-ledger-mcp", COMMON[0], COMMON[1], WINDOWS_JAVA);

        assertThat(missingJar.code()).contains(ErrorCode.HOST_FAILED);
        assertThat(missingJar.refusal().orElseThrow().arguments().getFirst()).endsWith(DeployLayout.ADMIN_JAR);
        assertThat(noJars.code()).contains(ErrorCode.MISSING_ARGUMENT);
        assertThat(noWinsw.code()).contains(ErrorCode.MISSING_ARGUMENT);
        assertThat(Files.exists(tmp.resolve("root"))).isFalse();
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("INS-044 a Linux instance on a file system without POSIX permissions is HOST_UNSUPPORTED (decided by the attribute views, not by the name of the system): nothing is created")
    void ins044_linuxNeedsPosix(Cli cli) throws IOException {
        Outcome result = run(cli, new RecordingOutput());

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.HOST_UNSUPPORTED);
        assertThat(Files.exists(tmp.resolve("root"))).isFalse();
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("INS-044 a Windows instance on a file system without ACLs is HOST_UNSUPPORTED: nothing is created")
    void ins044_windowsNeedsAcls(Cli cli) throws IOException {
        Outcome result = run(cli, new RecordingOutput(), "--platform=windows", "--home=C:/ProgramData/pg-ledger-mcp",
                "--winsw=" + jars().resolve("WinSW.exe"), "--java=C:/Program Files/Java/jdk-25/bin/java.exe");

        assertThat(result.code()).contains(ErrorCode.HOST_UNSUPPORTED);
        assertThat(Files.exists(tmp.resolve("root"))).isFalse();
    }

    /** Whether this process may change what only an administrator may: it can list the configuration of the system. */
    private static boolean elevated() {
        try (DirectoryStream<Path> configuration = Files.newDirectoryStream(Path.of("C:/Windows/System32/config"))) {
            return configuration != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("INS-044 Windows (in an elevated prompt): the home and its folders are closed to every account but SYSTEM and Administrators, the secrets of an earlier run are the installer's alone, the jars, the two WinSW executables and the driver are copied, the files are rendered")
    void ins044_prepareWindows(Cli cli) throws IOException {
        assumeTrue(elevated(), "this needs an elevated prompt: only an administrator may close a folder to everybody else");
        FileSystem fileSystem = FileSystems.getDefault();
        Path home = tmp.resolve("root/C/ProgramData/pg-ledger-mcp");
        try {
            Outcome result = run(cli, new RecordingOutput(), "--platform=windows", "--home=C:/ProgramData/pg-ledger-mcp",
                    "--winsw=" + jars().resolve("WinSW.exe"), "--java=C:/Program Files/Java/jdk-25/bin/java.exe");

            assertThat(result.code()).isEmpty();
            UserPrincipal system = fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(WindowsAccess.SYSTEM);
            UserPrincipal administrators = fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(WindowsAccess.ADMINISTRATORS);
            for (Path folder : List.of(home, home.resolve("lib"), home.resolve("etc"), home.resolve("etc/drivers"), home.resolve("logs"))) {
                List<AclEntry> acl = Files.getFileAttributeView(folder, AclFileAttributeView.class).getAcl();
                assertThat(acl.stream().map(AclEntry::principal)).as(folder.toString()).containsExactlyInAnyOrder(system, administrators);
            }
            List<AclEntry> secrets = Files.getFileAttributeView(home.resolve("etc/secrets"), AclFileAttributeView.class).getAcl();
            assertThat(secrets).as("the secrets are the installer's alone").hasSize(1);
            assertThat(Files.readString(home.resolve("lib/pg-ledger-mcp.exe"))).isEqualTo("content of WinSW.exe");
            assertThat(Files.readString(home.resolve("lib/pg-ledger-mcp-admin.exe"))).isEqualTo("content of WinSW.exe");
            assertThat(Files.readString(home.resolve("lib").resolve(DeployLayout.CLI_JAR))).isEqualTo("content of ledger-cli.jar");
            assertThat(Files.readString(home.resolve("etc/drivers/driver.jar"))).isEqualTo("content of driver.jar");
            assertThat(Files.exists(home.resolve("lib/pg-ledger-mcp.xml"))).isTrue();
        } finally {
            WindowsAccessTest.reopen(tmp);
        }
    }
}
