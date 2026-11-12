package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingHostAccounts;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresNonPosixFileSystem;
import org.pgledger.cli.support.RequiresPosixPermissions;

/**
 * INS-045: the access of a Windows installation, in Java and not with {@code icacls} ({@link WindowsAccess}): the entries it
 * builds (any file system, with principals of the test), and what {@code seal-host} does to real files on a file system with
 * ACLs. Handing a file to the real LocalService account needs an administrator, so the test plays that account with the
 * account that runs it.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class WindowsAccessTest {

    private record Principal(String name) implements UserPrincipal {

        @Override
        public String getName() {
            return name;
        }
    }

    private static final UserPrincipal SERVICE = new Principal("service");
    private static final UserPrincipal ADMINISTRATORS = new Principal("administrators");
    private static final UserPrincipal SYSTEM = new Principal("system");
    private static final UserPrincipal INSTALLER = new Principal("installer");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("INS-045 the home is closed to every account but SYSTEM and Administrators: two ALLOW entries with everything, inherited by files and folders")
    void ins045_closedHome() {
        List<AclEntry> acl = WindowsAccess.closed(SYSTEM, ADMINISTRATORS);

        assertThat(acl).hasSize(2);
        assertThat(acl).allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(entry.permissions()).isEqualTo(WindowsAccess.FULL);
            assertThat(entry.flags()).containsExactlyInAnyOrder(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        });
        assertThat(acl.stream().map(AclEntry::principal)).containsExactly(SYSTEM, ADMINISTRATORS);
    }

    @Test
    @DisplayName("INS-045 the secrets of an earlier run are given back to the installer alone; handed over, they are the service account's with full access, and Administrators may only see that a file exists")
    void ins045_secrets() {
        assertThat(WindowsAccess.takenBack(INSTALLER, true)).hasSize(1).allSatisfy(entry -> {
            assertThat(entry.principal()).isEqualTo(INSTALLER);
            assertThat(entry.permissions()).isEqualTo(WindowsAccess.FULL);
            assertThat(entry.flags()).isNotEmpty();
        });
        assertThat(WindowsAccess.takenBack(INSTALLER, false).getFirst().flags()).as("a file inherits nothing").isEmpty();

        List<AclEntry> handedOver = WindowsAccess.handedOver(SERVICE, ADMINISTRATORS, false);
        assertThat(handedOver).hasSize(2);
        assertThat(handedOver.get(0).principal()).isEqualTo(SERVICE);
        assertThat(handedOver.get(0).permissions()).isEqualTo(WindowsAccess.FULL);
        assertThat(handedOver.get(1).principal()).isEqualTo(ADMINISTRATORS);
        assertThat(handedOver.get(1).permissions()).containsExactlyInAnyOrder(AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE);
        assertThat(handedOver.stream().map(AclEntry::principal)).as("the installer has no entry").doesNotContain(INSTALLER);
    }

    @Test
    @DisplayName("INS-045 the rights of the service account on the jars and on the settings: read and run, and modify without changing the ACL or the owner")
    void ins045_rights() {
        assertThat(WindowsAccess.READ_EXECUTE).contains(AclEntryPermission.READ_DATA, AclEntryPermission.EXECUTE)
                .doesNotContain(AclEntryPermission.WRITE_DATA, AclEntryPermission.DELETE);
        assertThat(WindowsAccess.MODIFY).contains(AclEntryPermission.WRITE_DATA, AclEntryPermission.DELETE,
                AclEntryPermission.READ_DATA).doesNotContain(AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
    }

    @Test
    @DisplayName("INS-045 adding an entry replaces the ALLOW entry of the same principal and keeps the others, so a re-run adds no second entry")
    void ins045_withReplacesTheEntryOfThePrincipal() {
        AclEntry first = WindowsAccess.allow(SERVICE, WindowsAccess.READ_EXECUTE, true);
        AclEntry other = WindowsAccess.allow(SYSTEM, WindowsAccess.FULL, true);
        AclEntry second = WindowsAccess.allow(SERVICE, WindowsAccess.MODIFY, true);

        List<AclEntry> acl = WindowsAccess.with(WindowsAccess.with(List.of(other), first), second);

        assertThat(acl).containsExactly(other, second);
    }

    /** Gives the files of the test back to the account that runs it, so that the temporary directory can be deleted. */
    static void reopen(Path root) throws IOException {
        FileSystem fileSystem = FileSystems.getDefault();
        String user = ProcessHandle.current().info().user().orElseThrow();
        UserPrincipal self = fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(user);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                open(directory, true);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                open(file, false);
                return FileVisitResult.CONTINUE;
            }

            private void open(Path path, boolean directory) throws IOException {
                // the owner may always change the ACL; an owner other than this account is only a file the test did not make
                Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(List.of(WindowsAccess.allow(self,
                        WindowsAccess.FULL, directory)));
            }
        });
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("INS-045 seal-host on real files: the service account (played by the account of the test) gets the jars to read, the settings to modify and the logs; the secrets are its own with full access, Administrators only see that they exist; the secrets folder is not touched by the grants of the settings folder")
    void ins045_sealHost(Cli cli) throws IOException {
        FileSystem fileSystem = FileSystems.getDefault();
        String user = ProcessHandle.current().info().user().orElseThrow();
        UserPrincipal self = fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(user);
        RecordingHostAccounts accounts = new RecordingHostAccounts(Map.of(DeployLayout.WINDOWS_SERVICE_ACCOUNT, self), Map.of());
        Path home = tmp.resolve("root/C/ProgramData/pg-ledger-mcp");
        Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("etc/secrets"));
        Files.createDirectories(home.resolve("logs"));
        Files.writeString(home.resolve("lib/ledger-cli.jar"), "jar");
        Files.writeString(home.resolve("etc/application.yaml"), "yaml");
        Files.writeString(home.resolve("etc/secrets/ledger.mcp.token"), "never-printed-0123456789");
        RecordingOutput output = new RecordingOutput();
        try {
            Outcome result = cli.withHostAccounts(accounts).run(tmp.resolve("secrets"), null, output, "seal-host",
                    "--root=" + tmp.resolve("root"), "--platform=windows", "--home=C:/ProgramData/pg-ledger-mcp",
                    "--java=C:/Program Files/Java/jdk-25/bin/java.exe", "--jdbc-url=jdbc:postgresql://localhost:5432/ledger",
                    "--app-account=ledger_app");

            assertThat(result.code()).isEmpty();
            assertThat(accounts.askedUsers()).contains(DeployLayout.WINDOWS_SERVICE_ACCOUNT, WindowsAccess.ADMINISTRATORS);
            UserPrincipal administrators = fileSystem.getUserPrincipalLookupService()
                    .lookupPrincipalByName(WindowsAccess.ADMINISTRATORS);
            for (Path secret : List.of(home.resolve("etc/secrets/ledger.mcp.token"), home.resolve("etc/secrets"))) {
                assertThat(Files.getOwner(secret)).as(secret.toString()).isEqualTo(self);
                List<AclEntry> acl = Files.getFileAttributeView(secret, AclFileAttributeView.class).getAcl();
                assertThat(acl.stream().map(AclEntry::principal)).as(secret.toString()).containsExactly(self, administrators);
                assertThat(acl.get(1).permissions()).as(secret.toString()).doesNotContain(AclEntryPermission.READ_DATA,
                        AclEntryPermission.WRITE_DATA);
            }
            assertThat(Files.getFileAttributeView(home.resolve("lib/ledger-cli.jar"), AclFileAttributeView.class).getAcl()
                    .stream().filter(entry -> entry.principal().equals(self)).toList()).hasSize(1)
                    .allSatisfy(entry -> assertThat(entry.permissions()).doesNotContain(AclEntryPermission.WRITE_DATA));
            assertThat(Files.getFileAttributeView(home.resolve("etc/application.yaml"), AclFileAttributeView.class).getAcl()
                    .stream().filter(entry -> entry.principal().equals(self)).toList()).hasSize(1)
                    .allSatisfy(entry -> assertThat(entry.permissions()).contains(AclEntryPermission.WRITE_DATA));
            assertThat(output.info()).contains("Handed the secrets of the instance pg-ledger-mcp").doesNotContain("never-printed");
        } finally {
            reopen(tmp);
        }
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("INS-045 seal-host on a file system without ACLs is HOST_UNSUPPORTED, and for a Linux instance it is refused: nothing is changed")
    void ins045_sealHostNeedsAcls(Cli cli) {
        Outcome noAcls = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "seal-host", "--root=" + tmp.resolve("root"),
                "--platform=windows", "--home=C:/ProgramData/pg-ledger-mcp", "--java=C:/Program Files/Java/jdk-25/bin/java.exe",
                "--jdbc-url=jdbc:postgresql://localhost:5432/ledger", "--app-account=ledger_app");
        Outcome linux = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "seal-host", "--root=" + tmp.resolve("root"),
                "--java=/usr/bin/java", "--jdbc-url=jdbc:postgresql://localhost:5432/ledger", "--app-account=ledger_app");

        assertThat(noAcls.code()).contains(ErrorCode.HOST_UNSUPPORTED);
        assertThat(linux.code()).contains(ErrorCode.INVALID_DEPLOY_VALUE);
        assertThat(Files.exists(tmp.resolve("root"))).isFalse();
    }
}
