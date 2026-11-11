package org.pgledger.cli.secret;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.support.RequiresPosixPermissions;

class SecretStoreTest {

    private static final SecretName NAME = new SecretName("cloudflare-api-token");

    @TempDir
    Path tmp;

    private static SecretStore store(Path directory) {
        return store(directory, new SystemProcessUser());
    }

    private static SecretStore store(Path directory, ProcessUser user) {
        return new SecretStore(new SecretsProperties("pg-ledger-mcp", directory), user);
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertRefused(Runnable action, ErrorCode code, String... arguments) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(FailureException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.arguments()).containsExactly(arguments);
        });
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-001 creates the directory 0700 and the file 0600 with the exact UTF-8 bytes and no trailing newline")
    void sec001_createsDirectoryAndFileWithOwnerOnlyModes() throws IOException {
        Path parent = Files.createDirectories(tmp.resolve("etc").resolve("pg-ledger-mcp"));
        Path directory = parent.resolve("secrets");
        String value = "päss-한글-😀 with space";

        Path file = store(directory).store(NAME, value.toCharArray());

        assertThat(file).isEqualTo(directory.resolve("cloudflare-api-token"));
        assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readAllBytes(file)).isEqualTo(value.getBytes(UTF_8));
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-002 the same name again is replaced through a new 0600 file and a move, leaving no temporary file")
    void sec002_replacesAtomically() throws IOException {
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        Path file = store.store(NAME, "first-value".toCharArray());
        Object firstFileKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();

        store.store(NAME, "second".toCharArray());

        assertThat(Files.readString(file)).isEqualTo("second");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readAttributes(file, BasicFileAttributes.class).fileKey())
                .as("a new inode proves the replacement was not written in place")
                .isNotEqualTo(firstFileKey);
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-002 a value that cannot be encoded leaves the old content and no temporary file")
    void sec002_failedWriteKeepsOldValue() throws IOException {
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        Path file = store.store(NAME, "old-value".toCharArray());

        assertRefused(() -> store.store(NAME, new char[] {'a', '\ud800', 'b'}), ErrorCode.INVALID_ENCODING);

        assertThat(Files.readString(file)).isEqualTo("old-value");
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-006 an existing directory with mode 0755 is refused with its path and mode and is not narrowed")
    void sec006_refusesLooseDirectory() throws IOException {
        Path directory = tmp.resolve("secrets");
        Files.createDirectory(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertRefused(() -> store(directory).store(NAME, "v".toCharArray()), ErrorCode.LOOSE_PERMISSIONS, directory.toString(), "rwxr-xr-x");
        assertRefused(() -> store(directory).check(NAME), ErrorCode.LOOSE_PERMISSIONS, directory.toString(), "rwxr-xr-x");

        assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(names(directory)).isEmpty();
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-006 an existing file with mode 0644 is refused with its path and mode and keeps its content and mode")
    void sec006_refusesLooseFile() throws IOException {
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        Path file = store.store(NAME, "old-value".toCharArray());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertRefused(() -> store.store(NAME, "new".toCharArray()), ErrorCode.LOOSE_PERMISSIONS, file.toString(), "rw-r--r--");

        assertThat(Files.readString(file)).isEqualTo("old-value");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-006 a symbolic link in place of the secret file is refused")
    void sec006_refusesSymbolicLinkTarget() throws IOException {
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        store.store(new SecretName("other"), "x".toCharArray());
        Files.createSymbolicLink(directory.resolve("cloudflare-api-token"), directory.resolve("other"));

        assertRefused(() -> store.store(NAME, "v".toCharArray()), ErrorCode.UNEXPECTED_FILE_TYPE,
                directory.resolve("cloudflare-api-token").toString());
        assertThat(Files.readString(directory.resolve("other"))).isEqualTo("x");
    }

    /** Answers with the given principals one call after the other; the last one repeats. */
    private static ProcessUser users(UserPrincipal... principals) {
        Iterator<UserPrincipal> remaining = List.of(principals).iterator();
        UserPrincipal[] last = new UserPrincipal[1];
        return fileSystem -> {
            if (remaining.hasNext()) {
                last[0] = remaining.next();
            }
            return last[0];
        };
    }

    private static UserPrincipal named(String name) throws IOException {
        return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(name);
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-013 the real process user owns what the store creates, so the owner check passes")
    void sec013_processUserIsTheOwnerOfCreatedFiles() throws IOException {
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "v".toCharArray());

        assertThat(new SystemProcessUser().current(directory.getFileSystem())).isEqualTo(Files.getOwner(directory)).isEqualTo(Files.getOwner(file));
        assertThatCode(() -> store(directory).check(NAME)).doesNotThrowAnyException();
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-013 a directory owned by another user is refused naming path, owner and current user, before any prompt")
    void sec013_refusesDirectoryOfAnotherOwner() throws IOException {
        Path directory = tmp.resolve("secrets");
        store(directory).store(NAME, "old".toCharArray());
        String owner = Files.getOwner(directory).getName();
        UserPrincipal root = named("root");
        assumeThat(root).isNotEqualTo(Files.getOwner(directory));

        assertRefused(() -> store(directory, users(root)).store(NAME, "new".toCharArray()), ErrorCode.FOREIGN_OWNER, directory.toString(), owner, "root");
        assertRefused(() -> store(directory, users(root)).check(NAME), ErrorCode.FOREIGN_OWNER, directory.toString(), owner, "root");

        assertThat(Files.readString(directory.resolve("cloudflare-api-token"))).isEqualTo("old");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-013 an existing secret file owned by another user is refused even when the directory is ours")
    void sec013_refusesFileOfAnotherOwner() throws IOException {
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "old".toCharArray());
        String owner = Files.getOwner(file).getName();
        UserPrincipal me = Files.getOwner(directory);
        UserPrincipal root = named("root");
        assumeThat(root).isNotEqualTo(me);

        assertRefused(() -> store(directory, users(me, root)).store(NAME, "new".toCharArray()), ErrorCode.FOREIGN_OWNER, file.toString(), owner, "root");

        assertThat(Files.readString(file)).isEqualTo("old");
    }

    @Test
    @DisplayName("SEC-014 a missing parent directory is refused naming the parent and is not created")
    void sec014_refusesMissingParent() throws IOException {
        Path parent = tmp.resolve("etc").resolve("pg-ledger-mcp");
        Path directory = parent.resolve("secrets");
        assumeThat(directory.getFileSystem().supportedFileAttributeViews()).containsAnyOf("posix", "acl");

        assertRefused(() -> store(directory).store(NAME, "v".toCharArray()), ErrorCode.PARENT_MISSING, parent.toString());
        assertRefused(() -> store(directory).check(NAME), ErrorCode.PARENT_MISSING, parent.toString());

        assertThat(Files.exists(tmp.resolve("etc"))).isFalse();
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-014 only the secrets directory itself is created, with mode 0700, inside an existing parent")
    void sec014_createsOnlyTheSecretsDirectory() throws IOException {
        Path parent = Files.createDirectory(tmp.resolve("pg-ledger-mcp"));
        Path directory = parent.resolve("secrets");

        store(directory).store(NAME, "v".toCharArray());

        assertThat(names(parent)).containsExactly("secrets");
        assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    @DisplayName("SEC-012 a file system without POSIX permissions fails with an explicit code and stores nothing")
    void sec012_refusesNonPosixFileSystem() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(tmp.resolve("not-posix.zip"), Map.of("create", "true"))) {
            Path directory = zip.getPath("/secrets");

            assertRefused(() -> store(directory).store(NAME, "v".toCharArray()), ErrorCode.NON_POSIX_FILESYSTEM, directory.toString());
            assertRefused(() -> store(directory).check(NAME), ErrorCode.NON_POSIX_FILESYSTEM, directory.toString());

            assertThat(Files.exists(directory)).isFalse();
        }
    }

    // ---- SEC-016 .. SEC-020: a file system with ACLs and without POSIX permissions (the Windows file systems) ----

    /** The test needs the default file system to have ACLs and no POSIX permissions: the capability, never the name of the system. */
    private static void assumeAclFileSystem() {
        assumeThat(FileSystems.getDefault().supportedFileAttributeViews()).contains("acl").doesNotContain("posix");
    }

    private static List<AclEntry> acl(Path path) throws IOException {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl();
    }

    private static AclEntry allowAll(UserPrincipal principal) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
    }

    /** Every entry of the ACL of the path is an ALLOW entry of its owner, which has the rights to read, write and delete. */
    private static void assertOwnerOnlyAcl(Path path) throws IOException {
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        assertThat(acl(path)).as("the ACL of %s", path).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(entry.principal()).isEqualTo(owner);
        });
        assertThat(acl(path).stream().flatMap(entry -> entry.permissions().stream()).toList())
                .contains(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.DELETE);
    }

    @Test
    @DisplayName("SEC-016 on a file system with ACLs the directory and the file are created with an owner-only ACL and the exact UTF-8 bytes")
    void sec016_createsOwnerOnlyAcl() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        String value = "päss-한글-😀 with space";

        Path file = store(directory).store(NAME, value.toCharArray());

        assertOwnerOnlyAcl(directory);
        assertOwnerOnlyAcl(file);
        UserPrincipal self = new SystemProcessUser().current(FileSystems.getDefault());
        assertThat(Files.getOwner(directory)).as("the account of the process owns it, not the group Administrators").isEqualTo(self);
        assertThat(Files.getOwner(file)).isEqualTo(self);
        assertThat(Files.readAllBytes(file)).isEqualTo(value.getBytes(UTF_8));
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
        assertThatCode(() -> store(directory).check(NAME)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-017 on a file system with ACLs the same name again is replaced through a new owner-only file and a move, leaving no temporary file")
    void sec017_replacesWithOwnerOnlyAcl() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        Path file = store.store(NAME, "first-value".toCharArray());

        store.store(NAME, "second".toCharArray());

        assertThat(Files.readString(file)).isEqualTo("second");
        assertOwnerOnlyAcl(file);
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    /** SYSTEM exists on every Windows file system and is not the current user; the lookup is by the file system, as FileTrust does. */
    private static UserPrincipal system() {
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("SYSTEM");
        } catch (IOException absent) {
            Assumptions.abort("this machine has no account SYSTEM");
            throw new IllegalStateException(absent);
        }
    }

    @Test
    @DisplayName("SEC-018 on a file system with ACLs an existing directory whose ACL allows another account is refused naming it, and is not narrowed")
    void sec018_refusesDirectoryThatAllowsAnotherAccount() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        store(directory).store(NAME, "old".toCharArray());
        UserPrincipal owner = Files.getOwner(directory);
        Files.getFileAttributeView(directory, AclFileAttributeView.class).setAcl(List.of(allowAll(owner), allowAll(system())));
        List<AclEntry> before = acl(directory);

        for (Runnable action : List.<Runnable>of(() -> store(directory).store(NAME, "new".toCharArray()),
                () -> store(directory).check(NAME))) {
            assertThatThrownBy(action::run).isInstanceOfSatisfying(FailureException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.LOOSE_PERMISSIONS);
                assertThat(e.arguments().getFirst()).isEqualTo(directory.toString());
            });
        }

        assertThat(acl(directory)).isEqualTo(before);
        assertThat(Files.readString(directory.resolve("cloudflare-api-token"))).isEqualTo("old");
    }

    @Test
    @DisplayName("SEC-019 on a file system with ACLs an existing secret file whose ACL allows another account is refused and keeps its content")
    void sec019_refusesFileThatAllowsAnotherAccount() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "old".toCharArray());
        UserPrincipal owner = Files.getOwner(file);
        Files.getFileAttributeView(file, AclFileAttributeView.class).setAcl(List.of(allowAll(owner), allowAll(system())));

        assertThatThrownBy(() -> store(directory).store(NAME, "new".toCharArray())).isInstanceOfSatisfying(FailureException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.LOOSE_PERMISSIONS);
                    assertThat(e.arguments().getFirst()).isEqualTo(file.toString());
                });

        assertThat(Files.readString(file)).isEqualTo("old");
    }

    @Test
    @DisplayName("SEC-020 on a file system with ACLs a directory or file owned by an account other than the current user is refused (FOREIGN_OWNER)")
    void sec020_refusesAnotherOwner() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "old".toCharArray());
        UserPrincipal other = system();
        assumeThat(other).isNotEqualTo(Files.getOwner(directory));

        assertRefused(() -> store(directory, users(other)).store(NAME, "new".toCharArray()), ErrorCode.FOREIGN_OWNER,
                directory.toString(), Files.getOwner(directory).getName(), other.getName());

        assertThat(Files.readString(file)).isEqualTo("old");
    }

    @Test
    @DisplayName("SEC-022 exists reports whether a secret is stored, and refuses like check when the store or the file cannot be used")
    void sec022_existsReportsPresenceAndRefusesLikeCheck() throws IOException {
        assumeThat(FileSystems.getDefault().supportedFileAttributeViews()).containsAnyOf("posix", "acl");
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        SecretStore orphan = store(tmp.resolve("etc").resolve("pg-ledger-mcp").resolve("secrets"));
        assertRefused(() -> orphan.exists(NAME), ErrorCode.PARENT_MISSING, tmp.resolve("etc").resolve("pg-ledger-mcp").toString());
        store.store(new SecretName("other"), "x".toCharArray());

        assertThat(store.exists(NAME)).isFalse();
        store.store(NAME, "v".toCharArray());
        assertThat(store.exists(NAME)).isTrue();
        assertThat(Files.readString(directory.resolve(NAME.value()))).isEqualTo("v");
    }

    @Test
    @DisplayName("SEC-021 a protection is chosen by the attribute views of the file system: POSIX first, then ACL, a file system with neither has none")
    void sec021_protectionFollowsTheAttributeViews() throws IOException {
        FileSystem system = FileSystems.getDefault();
        Set<String> views = system.supportedFileAttributeViews();
        Optional<SecretProtection> chosen = SecretProtection.of(system, new SystemProcessUser());

        assertThat(chosen).isPresent();
        assertThat(chosen.orElseThrow() instanceof SecretProtection.Posix).isEqualTo(views.contains("posix"));
        assertThat(chosen.orElseThrow() instanceof SecretProtection.Acl).isEqualTo(!views.contains("posix") && views.contains("acl"));
        try (FileSystem zip = FileSystems.newFileSystem(tmp.resolve("neither.zip"), Map.of("create", "true"))) {
            assertThat(SecretProtection.of(zip, new SystemProcessUser())).isEmpty();
        }
    }
}
