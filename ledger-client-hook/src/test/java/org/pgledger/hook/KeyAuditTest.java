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
import java.security.PublicKey;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;
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

    private static final String SERVER = "ledger";
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

    @Test
    @DisplayName("HK-033 the key of an author is kept per server: two servers, two key files and keys, and a signature made for one server never verifies under the key of the other")
    void keysAreKeptPerServer(HookFixture fixture) throws IOException {
        HostScenario claude = new ClaudeHost();
        JsonNode input = Json.of("{\"marker\":\"two servers\"}");
        String author = claude.author(HookFixture.HOST_ID);
        Run first = fixture.runClean(claude, claude.event("mcp__ledger__ledger_save", input));
        Run second = fixture.runClean(claude, claude.event("mcp__ledger-test__ledger-test_ledger_save", input));
        Attestations.Stamp onLedger = fixture.stampCarrier("ledger", author, "save", input, claude.stampedPart(first));
        Attestations.Stamp onTest = fixture.stampCarrier("ledger-test", author, "save", input, claude.stampedPart(second));

        PublicKey ledgerKey = Attestations.publicKey(fixture.keyDirectory(), "ledger", author);
        PublicKey testKey = Attestations.publicKey(fixture.keyDirectory(), "ledger-test", author);
        assertThat(PublicKeys.encode(ledgerKey)).isNotEqualTo(PublicKeys.encode(testKey));
        assertThatThrownBy(() -> AttestationToken.parse(onLedger.token()).verify(testKey))
                .isInstanceOf(AttestationException.class);
        assertThatThrownBy(() -> AttestationToken.parse(onTest.token()).verify(ledgerKey))
                .isInstanceOf(AttestationException.class);
        try (Stream<Path> files = Files.list(fixture.keyDirectory())) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactlyInAnyOrder(
                    Attestations.keyFileName("ledger", author), Attestations.keyFileName("ledger-test", author));
        }
    }

    @Test
    @DisplayName("HK-034 a key file that holds another server's key (a copied file) is refused as SERVER_MISMATCH: nothing is signed and the file is not changed")
    void aCopiedKeyFileIsRefused(HookFixture fixture) throws IOException {
        HostScenario claude = new ClaudeHost();
        String author = claude.author(HookFixture.HOST_ID);
        fixture.runClean(claude, claude.event("mcp__ledger__ledger_save", EMPTY));
        Path copy = fixture.keyDirectory().resolve(Attestations.keyFileName("other", author));
        Files.copy(fixture.keyDirectory().resolve(Attestations.keyFileName("ledger", author)), copy,
                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        byte[] before = Files.readAllBytes(copy);

        Run run = fixture.runClean(claude, claude.event("mcp__other__ledger_save", EMPTY));
        assertThat(run.document()).isEqualTo(claude.denied(Refusal.SESSION_KEY_UNAVAILABLE));
        assertThat(Files.readAllBytes(copy)).isEqualTo(before);
        assertThatThrownBy(() -> TestStores.load(TestStores.open(fixture.keyDirectory()), "other", author))
                .isInstanceOfSatisfying(SessionKeyException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(SessionKeyException.Reason.SERVER_MISMATCH));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{\"n\":12345678901234567890}",
            "{\"d\":0.10,\"e\":1e2,\"z\":-0,\"big\":1E+30}", "{\"a\":[1,2.50,{\"b\":null}],\"s\":\"한글\",\"t\":\"a\\u0000\\n\"}"})
    @DisplayName("HK-038 the token signs the digest of the arguments the hook returns (numbers, null, nesting, escapes), recomputed with the shared class")
    void theDigestCoversTheArgumentsAsReturned(String json, HookFixture fixture) {
        HostScenario claude = new ClaudeHost();
        JsonNode input = Json.of(json);
        Run run = fixture.runClean(claude, claude.event(claude.toolName("save"), input));
        fixture.stamp(claude, "save", input, run);
    }

    @Test
    @DisplayName("HK-038 a number that has no double (1e999) cannot be signed: denied ARGUMENTS_NOT_SIGNABLE, no key is created")
    void aNumberBeyondTheRangeOfADoubleIsDenied(HookFixture fixture) {
        HostScenario claude = new ClaudeHost();
        Run run = fixture.runClean(claude, claude.event(claude.toolName("save"), Json.of("{\"n\":1e999}")));
        assertThat(run.document()).isEqualTo(claude.denied(Refusal.ARGUMENTS_NOT_SIGNABLE));
        assertThat(fixture.keyDirectory()).doesNotExist();
    }

    // ---- HK-035 ----

    @Test
    @DisplayName("HK-035 ACL view: the key folder and the key file the hook creates grant their owner alone")
    void createdWithOwnerOnlyAcl(@TempDir Path temp) throws IOException {
        Assumptions.assumeTrue(acl(), "NOT_RUN: the default file system has no ACL attribute view");
        Path directory = temp.resolve("keys");
        TestStores.load(TestStores.open(directory), SERVER, AUTHOR);
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
        TestStores.load(store, SERVER, AUTHOR);
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

        assertThatThrownBy(() -> TestStores.load(TestStores.open(directory), SERVER, AUTHOR))
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
            SessionKeyStore store = TestStores.at(name, List.of());
            assertThatThrownBy(() -> store.load(SERVER, AUTHOR)).isInstanceOfSatisfying(SessionKeyException.class,
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
