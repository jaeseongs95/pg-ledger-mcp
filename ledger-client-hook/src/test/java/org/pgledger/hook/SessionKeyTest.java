package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.attestation.PublicKeys;
import tools.jackson.databind.JsonNode;

/** HK-024..HK-027 and HK-031: where the session keys live, how they are protected, and what a bad key file does. */
@ExtendWith(HookExtension.class)
class SessionKeyTest {

    private static final JsonNode EMPTY = Json.of("{}");
    private static final String OTHER_ID = "other-session";
    private static final String PRIVATE_PREFIX = "private-key=";

    /** The key file of the author of the host id (or another author) for the server of the host's tool names. */
    private static Path keyFile(Path keyDirectory, HostScenario host, String author) {
        return keyDirectory.resolve(Attestations.keyFileName(host.server(), author));
    }

    /** Every file and folder below the root, relative to it. */
    private static List<Path> snapshot(Path root) throws IOException {
        try (Stream<Path> all = Files.walk(root)) {
            return all.filter(path -> !path.equals(root)).map(root::relativize).toList();
        }
    }

    /** The base64 text of the private key in a key file: what must never leave the key folder. */
    private static String privateKeyText(Path file) throws IOException {
        return Files.readAllLines(file).stream().filter(line -> line.startsWith(PRIVATE_PREFIX)).findFirst()
                .orElseThrow(() -> new AssertionError("no private-key line in the key file")).substring(PRIVATE_PREFIX.length());
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    // ---- HK-024: creation and reuse ----

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-024 the first call of a new author creates exactly one key file (author, private and public key lines); nothing else changes outside the key folder")
    void firstCallCreatesOneKeyFile(HostScenario host, HookFixture fixture) throws IOException {
        Path root = fixture.keyDirectory().getParent();
        assertThat(snapshot(root)).containsExactly(Path.of("work"));
        fixture.runClean(host, host.event(host.toolName("save"), EMPTY));
        String author = host.author(HookFixture.HOST_ID);
        assertThat(snapshot(root)).containsExactlyInAnyOrder(
                Path.of("work"), Path.of("keys"), Path.of("keys", Attestations.keyFileName(host.server(), author)));
        List<String> lines = Files.readAllLines(keyFile(fixture.keyDirectory(), host, author));
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo("server=" + host.server());
        assertThat(lines.get(1)).isEqualTo("author=" + author);
        assertThat(lines.get(2)).startsWith(PRIVATE_PREFIX);
        assertThat(lines.get(3)).startsWith("public-key=");
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-024 the second call (a new command each time) reuses the key: same file bytes, same public key, both tokens verify")
    void secondCallReusesTheKey(HostScenario host, HookFixture fixture) throws IOException {
        Path file = keyFile(fixture.keyDirectory(), host, host.author(HookFixture.HOST_ID));
        Run first = fixture.runClean(host, host.event(host.toolName("save"), EMPTY));
        byte[] bytes = Files.readAllBytes(file);
        String publicKey = PublicKeys.encode(Attestations.publicKey(fixture.keyDirectory(), host.server(),
                host.author(HookFixture.HOST_ID)));
        Run second = fixture.runClean(host, host.event(host.toolName("save"), EMPTY));
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        assertThat(PublicKeys.encode(Attestations.publicKey(fixture.keyDirectory(), host.server(),
                host.author(HookFixture.HOST_ID)))).isEqualTo(publicKey);
        assertThat(snapshot(fixture.keyDirectory())).hasSize(1);
        fixture.stamp(host, "save", EMPTY, first);
        fixture.stamp(host, "save", EMPTY, second);
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-024 two authors have two key files and different public keys; the same host id of another vendor is another author")
    void twoAuthorsTwoKeys(HostScenario host, HookFixture fixture) throws IOException {
        fixture.runClean(host, host.event(host.toolName("save"), EMPTY));
        fixture.runClean(host, host.event(host.toolName("save"), EMPTY, Json.text(OTHER_ID)));
        assertThat(snapshot(fixture.keyDirectory())).containsExactlyInAnyOrder(
                Path.of(Attestations.keyFileName(host.server(), host.author(HookFixture.HOST_ID))),
                Path.of(Attestations.keyFileName(host.server(), host.author(OTHER_ID))));
        String first = PublicKeys.encode(Attestations.publicKey(fixture.keyDirectory(), host.server(),
                host.author(HookFixture.HOST_ID)));
        String second = PublicKeys.encode(Attestations.publicKey(fixture.keyDirectory(), host.server(),
                host.author(OTHER_ID)));
        assertThat(first).isNotEqualTo(second);
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-024 real jar: the first process creates the key, the second process (a new JVM) reuses it; only the key folder changes outside the processes' own working files")
    void processesShareTheKey(HostScenario host, HookJar jar, TestWorkspace workspace) throws IOException {
        byte[] stdin = host.event(host.toolName("save"), EMPTY);
        Run first = jar.run(stdin, host.client().argument());
        Path file = keyFile(jar.keyDirectory(), host, host.author(HookFixture.HOST_ID));
        byte[] bytes = Files.readAllBytes(file);
        jar.stamp(host, "save", EMPTY, first);
        Run second = jar.run(stdin, host.client().argument());
        jar.stamp(host, "save", EMPTY, second);
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        List<Path> outside = snapshot(workspace.root()).stream().filter(path -> !path.startsWith("work")).toList();
        assertThat(outside).containsExactlyInAnyOrder(Path.of("keys"), Path.of("keys", Attestations.keyFileName(host.server(), host.author(HookFixture.HOST_ID))));
        for (Path cwd : jar.workingDirectories()) {
            assertThat(snapshot(cwd)).as("working directory of a process").isEmpty();
        }
    }

    // ---- HK-025 / HK-026: protection on a POSIX file system ----

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-025 POSIX only: a folder and a key file the hook creates are rwx------ and rw-------")
    void createdWithOwnerOnlyModes(HostScenario host, HookFixture fixture) throws IOException {
        Assumptions.assumeTrue(posix(), "NOT_RUN: the default file system has no POSIX attribute view");
        fixture.runClean(host, host.event(host.toolName("save"), EMPTY));
        Path file = keyFile(fixture.keyDirectory(), host, host.author(HookFixture.HOST_ID));
        assertThat(Files.getPosixFilePermissions(fixture.keyDirectory())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /** (host, what is widened, the wider mode): the hook refuses it and changes nothing. */
    static Stream<Arguments> widenedModes() {
        return Hosts.ALL.stream().flatMap(host -> Stream.of(
                Arguments.argumentSet(host + " file rw-r-----", host, false, "rw-r-----"),
                Arguments.argumentSet(host + " file rw-rw-rw-", host, false, "rw-rw-rw-"),
                Arguments.argumentSet(host + " folder rwxr-xr-x", host, true, "rwxr-xr-x"),
                Arguments.argumentSet(host + " folder rwx--x---", host, true, "rwx--x---")));
    }

    @ParameterizedTest
    @MethodSource("widenedModes")
    @DisplayName("HK-026 POSIX only: a key file or folder that group or others can reach is refused (SESSION_KEY_UNAVAILABLE): nothing signed, mode and file unchanged")
    void refusesAKeyThatOthersCanReach(HostScenario host, boolean folder, String mode, HookFixture fixture) throws IOException {
        Assumptions.assumeTrue(posix(), "NOT_RUN: the default file system has no POSIX attribute view");
        fixture.runClean(host, host.event(host.toolName("save"), EMPTY));
        String author = host.author(HookFixture.HOST_ID);
        Path file = keyFile(fixture.keyDirectory(), host, author);
        Path widened = folder ? fixture.keyDirectory() : file;
        Set<PosixFilePermission> wide = PosixFilePermissions.fromString(mode);
        Files.setPosixFilePermissions(widened, wide);
        byte[] before = Files.readAllBytes(file);
        String privateKey = privateKeyText(file);

        Run run = fixture.runClean(host, host.event(host.toolName("save"), EMPTY));

        assertThat(run.document()).isEqualTo(host.denied(Refusal.SESSION_KEY_UNAVAILABLE));
        assertThat(run.out()).doesNotContain("pgla2").doesNotContain(privateKey);
        assertThat(Files.getPosixFilePermissions(widened)).as("the mode is not repaired").isEqualTo(wide);
        assertThat(Files.readAllBytes(file)).as("the key file is not touched").isEqualTo(before);
        Run command = fixture.run(new String[] {HookCli.SESSION_KEY_COMMAND, host.server(), author}, new byte[0]);
        assertThat(command.exit()).isEqualTo(2);
        assertThat(command.stdout()).isEmpty();
        assertThat(command.err()).contains("[TOO_OPEN]").doesNotContain(privateKey);
    }

    // ---- HK-027: unusable key files ----

    /** How the key file of an author is damaged; the rewrite gets its own lines, the lines of another author's file and that author. */
    private interface Rewrite {
        String apply(List<String> own, List<String> foreign, String otherAuthor);
    }

    enum Corruption {
        GARBAGE((own, foreign, otherAuthor) -> "this is not a key file\n"),
        EMPTY_FILE((own, foreign, otherAuthor) -> ""),
        NOT_BASE64((own, foreign, otherAuthor) ->
                String.join("\n", own.get(0), own.get(1), "private-key=@@@@", own.get(3)) + "\n"),
        TRUNCATED((own, foreign, otherAuthor) -> String.join("\n", own.get(0), own.get(1), own.get(2)) + "\n"),
        AUTHOR_LINE_OF_ANOTHER_AUTHOR((own, foreign, otherAuthor) ->
                String.join("\n", own.get(0), "author=" + otherAuthor, own.get(2), own.get(3)) + "\n"),
        SERVER_LINE_OF_ANOTHER_SERVER((own, foreign, otherAuthor) ->
                String.join("\n", "server=another-server", own.get(1), own.get(2), own.get(3)) + "\n"),
        PRIVATE_KEY_OF_ANOTHER_PAIR((own, foreign, otherAuthor) ->
                String.join("\n", own.get(0), own.get(1), foreign.get(2), own.get(3)) + "\n");

        private final Rewrite rewrite;

        Corruption(Rewrite rewrite) {
            this.rewrite = rewrite;
        }

        String apply(List<String> own, List<String> foreign, String otherAuthor) {
            return rewrite.apply(own, foreign, otherAuthor);
        }
    }

    static Stream<Arguments> corruptions() {
        return Hosts.cross(List.of(Corruption.values()));
    }

    @ParameterizedTest
    @MethodSource("corruptions")
    @DisplayName("HK-027 a corrupt key file (garbage, empty, not base64, truncated, author line of another author, private key of another pair) is refused (SESSION_KEY_UNAVAILABLE): the file is unchanged and no key material is shown")
    void refusesACorruptKeyFile(HostScenario host, Corruption corruption, HookFixture fixture) throws IOException {
        fixture.runClean(host, host.event(host.toolName("save"), EMPTY));
        fixture.runClean(host, host.event(host.toolName("save"), EMPTY, Json.text(OTHER_ID)));
        String author = host.author(HookFixture.HOST_ID);
        Path file = keyFile(fixture.keyDirectory(), host, author);
        Path foreignFile = keyFile(fixture.keyDirectory(), host, host.author(OTHER_ID));
        List<String> own = Files.readAllLines(file);
        List<String> foreign = Files.readAllLines(foreignFile);
        String ownPrivateKey = privateKeyText(file);
        String foreignPrivateKey = privateKeyText(foreignFile);
        Files.writeString(file, corruption.apply(own, foreign, host.author(OTHER_ID)));
        byte[] before = Files.readAllBytes(file);

        Run run = fixture.runClean(host, host.event(host.toolName("save"), EMPTY));

        assertThat(run.document()).isEqualTo(host.denied(Refusal.SESSION_KEY_UNAVAILABLE));
        assertThat(run.out()).doesNotContain("pgla2").doesNotContain(ownPrivateKey).doesNotContain(foreignPrivateKey);
        assertThat(Files.readAllBytes(file)).as("the key file is neither repaired nor replaced").isEqualTo(before);
        Run command = fixture.run(new String[] {HookCli.SESSION_KEY_COMMAND, host.server(), author}, new byte[0]);
        assertThat(command.exit()).isEqualTo(2);
        assertThat(command.stdout()).isEmpty();
        assertThat(command.err()).doesNotContain(ownPrivateKey).doesNotContain(foreignPrivateKey);
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-027 a key folder that is a plain file is refused (SESSION_KEY_UNAVAILABLE) and left as it is")
    void refusesAKeyFolderThatIsAFile(HostScenario host, HookFixture fixture) throws IOException {
        Files.writeString(fixture.keyDirectory(), "not a folder");
        Run run = fixture.runClean(host, host.event(host.toolName("save"), EMPTY));
        assertThat(run.document()).isEqualTo(host.denied(Refusal.SESSION_KEY_UNAVAILABLE));
        assertThat(Files.readString(fixture.keyDirectory())).isEqualTo("not a folder");
    }

    // ---- HK-031: no private key in any output ----

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-031 stdout and stderr of a stamping run (in process and as a real process) never contain the private key text")
    void privateKeyNeverLeavesTheKeyFolder(HostScenario host, HookFixture fixture, HookJar jar) throws IOException {
        byte[] stdin = host.event(host.toolName("save"), EMPTY);
        Run inProcess = fixture.runClean(host, stdin);
        String privateKey = privateKeyText(keyFile(fixture.keyDirectory(), host, host.author(HookFixture.HOST_ID)));
        Run process = jar.run(stdin, host.client().argument());
        for (Run run : List.of(inProcess, process)) {
            assertThat(run.out()).doesNotContain(privateKey);
            assertThat(run.err()).doesNotContain(privateKey);
        }
        assertThat(process.exit()).isZero();
    }
}
