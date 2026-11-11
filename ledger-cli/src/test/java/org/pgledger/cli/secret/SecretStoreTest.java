package org.pgledger.cli.secret;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

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
    @EnabledOnOs(LINUX)
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
    @EnabledOnOs(LINUX)
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
    @EnabledOnOs(LINUX)
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
    @EnabledOnOs(LINUX)
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
    @EnabledOnOs(LINUX)
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
    @EnabledOnOs(LINUX)
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
    @EnabledOnOs(LINUX)
    @DisplayName("SEC-013 the real process user owns what the store creates, so the owner check passes")
    void sec013_processUserIsTheOwnerOfCreatedFiles() throws IOException {
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "v".toCharArray());

        assertThat(new SystemProcessUser().current(directory.getFileSystem())).isEqualTo(Files.getOwner(directory)).isEqualTo(Files.getOwner(file));
        assertThatCode(() -> store(directory).check(NAME)).doesNotThrowAnyException();
    }

    @Test
    @EnabledOnOs(LINUX)
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
    @EnabledOnOs(LINUX)
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
        assumeThat(directory.getFileSystem().supportedFileAttributeViews()).contains("posix");

        assertRefused(() -> store(directory).store(NAME, "v".toCharArray()), ErrorCode.PARENT_MISSING, parent.toString());
        assertRefused(() -> store(directory).check(NAME), ErrorCode.PARENT_MISSING, parent.toString());

        assertThat(Files.exists(tmp.resolve("etc"))).isFalse();
    }

    @Test
    @EnabledOnOs(LINUX)
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

    @Test
    @EnabledOnOs(WINDOWS)
    @DisplayName("SEC-012 on Windows the default file system is refused and nothing is created")
    void sec012_windowsIsRefused() {
        Path directory = tmp.resolve("secrets");

        assertRefused(() -> store(directory).store(NAME, "v".toCharArray()), ErrorCode.NON_POSIX_FILESYSTEM, directory.toString());

        assertThat(Files.exists(directory)).isFalse();
    }
}
