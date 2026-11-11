package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.hook.keys.SessionKeyException;
import org.pgledger.hook.keys.SessionKeyStore;
import tools.jackson.databind.JsonNode;

/**
 * HK-032 .. HK-036: what the hook signs for (the server of a call), the protection of the key folder on a file system
 * with ACLs (Windows) and the key folder setting. Capability tests, never an operating system test: the ACL case runs
 * where the default file system has the ACL attribute view.
 */
@ExtendWith(HookExtension.class)
class KeyAuditTest {

    private static final String AUTHOR = "claude:audit-session";
    private static final JsonNode EMPTY = Json.of("{}");
    /** The well-known SID of the group Everyone; a SID text is understood by the lookup whatever the language of the system. */
    private static final String EVERYONE = "S-1-1-0";

    private static boolean acl() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("acl");
    }

    // ---- HK-032 ----

    static Stream<Arguments> foreignPrefixNames() {
        HostScenario claude = new ClaudeHost();
        HostScenario codex = new CodexHost();
        HostScenario agy = AntigravityHost.INSTANCE;
        return Stream.of(
                Arguments.argumentSet("claude prefix is not the server", claude, "mcp__a__b_ledger_save"),
                Arguments.argumentSet("claude codex_apps namespace", claude, "mcp__codex_apps__app_ledger_ledger_save"),
                Arguments.argumentSet("claude no server", claude, "mcp__ledger_save"),
                Arguments.argumentSet("codex prefix is not the server", codex, "mcp__a__b_ledger_save"),
                Arguments.argumentSet("codex codex_apps namespace", codex, "mcp__codex_apps__app_ledger_ledger_save"),
                Arguments.argumentSet("codex no server", codex, "mcp__ledger_save"),
                Arguments.argumentSet("antigravity prefix is not the server", agy, "mcp:test:other_ledger_save"),
                Arguments.argumentSet("antigravity no server", agy, "mcp:ledger_save"));
    }

    @ParameterizedTest
    @MethodSource("foreignPrefixNames")
    @DisplayName("HK-032 a tool name whose prefix before ledger_<op> is not the server segment, or that names no server, is not signed (a look-alike server gets no signature of the owner)")
    void foreignPrefixIsNotSigned(HostScenario host, String name, HookFixture fixture) {
        Run run = fixture.runClean(host, host.event(name, EMPTY));
        assertThat(run.document()).isEqualTo(host.untouched());
    }

    // ---- HK-033 ----

    @Test
    @DisplayName("HK-033 session-key takes a server and an author, and prints the server too")
    void sessionKeyNamesTheServer(HookFixture fixture) {
        Run run = fixture.run(new String[] {"session-key", "ledger", AUTHOR}, new byte[0]);
        assertThat(run.err()).isEmpty();
        assertThat(run.exit()).isZero();
        assertThat(run.out()).startsWith("server: ledger\nauthor: " + AUTHOR + "\npublic-key: ");
    }

    // ---- HK-035 ----

    @Test
    @DisplayName("HK-035 ACL view: the key folder and the key file the hook creates grant their owner alone")
    void createdWithOwnerOnlyAcl(@TempDir Path temp) throws IOException {
        Assumptions.assumeTrue(acl(), "NOT_RUN: the default file system has no ACL attribute view");
        Path directory = temp.resolve("keys");
        TestStores.load(TestStores.open(directory), AUTHOR);
        List<Path> created;
        try (Stream<Path> files = Files.list(directory)) {
            created = files.toList();
        }
        assertThat(created).hasSize(1);
        for (Path path : List.of(directory, created.getFirst())) {
            UserPrincipal owner = Files.getOwner(path);
            List<AclEntry> entries = Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl();
            assertThat(entries).as("ACL of %s", path.getFileName()).isNotEmpty().allSatisfy(entry -> {
                assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                assertThat(entry.principal()).isEqualTo(owner);
            });
        }
    }

    @ParameterizedTest
    @MethodSource("widenedTargets")
    @DisplayName("HK-035 ACL view: a key file or folder that grants read or write to another principal is refused and left as it is")
    void widenedAclIsRefused(boolean folder, AclEntryPermission permission, @TempDir Path temp) throws IOException {
        Assumptions.assumeTrue(acl(), "NOT_RUN: the default file system has no ACL attribute view");
        Path directory = temp.resolve("keys");
        SessionKeyStore store = TestStores.open(directory);
        TestStores.load(store, AUTHOR);
        Path file;
        try (Stream<Path> files = Files.list(directory)) {
            file = files.findFirst().orElseThrow();
        }
        Path target = folder ? directory : file;
        UserPrincipal everyone = FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(EVERYONE);
        AclFileAttributeView view = Files.getFileAttributeView(target, AclFileAttributeView.class);
        List<AclEntry> widened = new ArrayList<>(view.getAcl());
        widened.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                .setPermissions(Set.of(permission)).build());
        view.setAcl(widened);
        byte[] before = Files.readAllBytes(file);

        assertThatThrownBy(() -> TestStores.load(TestStores.open(directory), AUTHOR))
                .isInstanceOfSatisfying(SessionKeyException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(SessionKeyException.Reason.TOO_OPEN));
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    static Stream<Arguments> widenedTargets() {
        return Stream.of(
                Arguments.argumentSet("file read", false, AclEntryPermission.READ_DATA),
                Arguments.argumentSet("file write", false, AclEntryPermission.WRITE_DATA),
                Arguments.argumentSet("folder read", true, AclEntryPermission.LIST_DIRECTORY),
                Arguments.argumentSet("folder write", true, AclEntryPermission.ADD_FILE));
    }

    // ---- HK-036 ----

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"audit-keys", "./audit-keys", "../audit-keys", "~/audit-keys"})
    @DisplayName("HK-036 a relative key folder is refused with FOLDER_UNAVAILABLE and nothing is created")
    void relativeKeyFolderIsRefused(String relative) throws IOException {
        String name = relative.replace("audit-keys", "audit-keys-" + UUID.randomUUID());
        Path created = Path.of(name).toAbsolutePath().normalize();
        try {
            SessionKeyStore store = SessionKeyStore.at(name, List.of());
            assertThatThrownBy(() -> store.load(AUTHOR)).isInstanceOfSatisfying(SessionKeyException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(SessionKeyException.Reason.FOLDER_UNAVAILABLE));
            assertThat(created).doesNotExist();
        } finally {
            if (Files.exists(created)) {
                try (Stream<Path> all = Files.walk(created)) {
                    for (Path path : all.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
    }
}
