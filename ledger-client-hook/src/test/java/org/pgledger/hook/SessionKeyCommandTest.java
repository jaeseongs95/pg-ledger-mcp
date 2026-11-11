package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.attestation.AttestationToken;
import org.pgledger.attestation.PublicKeys;
import tools.jackson.databind.JsonNode;

/** HK-028 (where the key folder is) and HK-029 (the session-key command). */
@ExtendWith(HookExtension.class)
class SessionKeyCommandTest {

    private static final String AUTHOR = "claude:session-29";
    private static final List<String> DEFAULT_IN_HOME = List.of(".pg-ledger-mcp", "session-keys");

    // ---- HK-028: the key folder ----

    /** (value of LEDGER_HOOK_KEY_DIR, value of user.home, expected first element, expected further elements). */
    static Stream<Arguments> folderCases() {
        return Stream.of(
                Arguments.argumentSet("variable set", "key-dir", "home-dir", "key-dir", List.of()),
                Arguments.argumentSet("variable set, no home", "key-dir", null, "key-dir", List.of()),
                Arguments.argumentSet("variable not set", null, "home-dir", "home-dir", DEFAULT_IN_HOME),
                Arguments.argumentSet("variable empty", "", "home-dir", "home-dir", DEFAULT_IN_HOME),
                Arguments.argumentSet("variable blank", "  \t", "home-dir", "home-dir", DEFAULT_IN_HOME),
                Arguments.argumentSet("neither", null, null, "", List.of()),
                Arguments.argumentSet("blank both", " ", " ", "", List.of()));
    }

    @ParameterizedTest
    @MethodSource("folderCases")
    @DisplayName("HK-028 the key folder is LEDGER_HOOK_KEY_DIR if set and not blank, otherwise <user.home>/.pg-ledger-mcp/session-keys")
    void resolvesTheKeyFolder(String variable, String home, String first, List<String> more) {
        KeyFolder folder = KeyFolder.resolve(variable, home);
        assertThat(folder.first()).isEqualTo(first);
        assertThat(folder.more()).isEqualTo(more);
        if (home != null && variable == null) {
            assertThat(Path.of(folder.first(), folder.more().toArray(String[]::new)))
                    .isEqualTo(Path.of(home, ".pg-ledger-mcp", "session-keys"));
        }
    }

    @Test
    @DisplayName("HK-028 the environment variable of the key folder is named LEDGER_HOOK_KEY_DIR")
    void variableName() {
        assertThat(KeyFolder.ENVIRONMENT_VARIABLE).isEqualTo("LEDGER_HOOK_KEY_DIR").isEqualTo(HookJar.KEY_DIR_VARIABLE);
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-028 real jar: the first run creates the key file in the folder named by LEDGER_HOOK_KEY_DIR, and nowhere else")
    void processUsesTheFolderOfTheVariable(HostScenario host, HookJar jar, TestWorkspace workspace) throws IOException {
        byte[] stdin = host.event(host.toolName("save"), Json.of("{}"));
        Run run = jar.run(stdin, host.client().argument());
        assertThat(run.exit()).isZero();
        Path file = jar.keyDirectory().resolve(Attestations.keyFileName(host.author(HookFixture.HOST_ID)));
        assertThat(file).isRegularFile();

        Path elsewhere = workspace.root().resolve("elsewhere");
        Run other = jar.start(HookJar.JAVA, List.of(), Map.of(HookJar.KEY_DIR_VARIABLE, elsewhere.toString()), stdin,
                host.client().argument()).run();
        assertThat(other.exit()).isZero();
        assertThat(elsewhere.resolve(Attestations.keyFileName(host.author(HookFixture.HOST_ID)))).isRegularFile();
        try (Stream<Path> files = Files.list(jar.keyDirectory())) {
            assertThat(files.toList()).as("the first folder got no second key").containsExactly(file);
        }
    }

    // ---- HK-029: the session-key command ----

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    /** The three lines of a successful answer; fails if stdout has any other shape. */
    private static List<String> threeLines(Run run) {
        assertThat(run.out()).endsWith("\n");
        String[] parts = run.out().split("\n", -1);
        assertThat(parts).as("three lines and the end of the last one").hasSize(4);
        assertThat(parts[3]).isEmpty();
        return List.of(parts[0], parts[1], parts[2]);
    }

    private static String privateKeyText(Path file) throws IOException {
        return Files.readAllLines(file).stream().filter(line -> line.startsWith("private-key=")).findFirst()
                .orElseThrow(() -> new AssertionError("no private-key line in the key file")).substring("private-key=".length());
    }

    @Test
    @DisplayName("HK-029 session-key prints author, public-key and fingerprint (SHA-256 of the key's DER) as three lines, exit 0, empty stderr; creates the key once, a second run prints the same")
    void printsThePublicKey(HookJar jar) throws IOException {
        Run first = jar.run(new byte[0], "session-key", AUTHOR);
        assertThat(first.err()).as("stderr").isEmpty();
        assertThat(first.exit()).isZero();
        List<String> lines = threeLines(first);
        assertThat(lines.get(0)).isEqualTo("author: " + AUTHOR);
        assertThat(lines.get(1)).startsWith("public-key: ");
        assertThat(lines.get(2)).startsWith("fingerprint: ");
        String publicKey = lines.get(1).substring("public-key: ".length());
        PublicKey decoded = PublicKeys.decode(publicKey);
        assertThat(lines.get(2).substring("fingerprint: ".length()))
                .isEqualTo(sha256Hex(Base64.getDecoder().decode(publicKey))).isEqualTo(PublicKeys.fingerprint(decoded));

        Path file = jar.keyDirectory().resolve(Attestations.keyFileName(AUTHOR));
        assertThat(Files.readAllLines(file)).contains("public-key=" + publicKey);
        byte[] bytes = Files.readAllBytes(file);
        String privateKey = privateKeyText(file);
        assertThat(first.out()).doesNotContain(privateKey);

        Run second = jar.run(new byte[0], "session-key", AUTHOR);
        assertThat(second.exit()).isZero();
        assertThat(second.stdout()).isEqualTo(first.stdout());
        assertThat(second.err()).isEmpty();
        assertThat(Files.readAllBytes(file)).as("the key file is reused as it is").isEqualTo(bytes);
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-029 the key that session-key prints is the key that signs: a token of a stamping run for that author verifies with the printed public key")
    void printedKeyVerifiesTheStamps(HostScenario host, HookFixture fixture) {
        String author = host.author(HookFixture.HOST_ID);
        Run command = fixture.run(new String[] {HookCli.SESSION_KEY_COMMAND, author}, new byte[0]);
        assertThat(command.exit()).isZero();
        PublicKey printed = PublicKeys.decode(threeLines(command).get(1).substring("public-key: ".length()));
        JsonNode input = Json.of("{}");
        Run run = fixture.runClean(host, host.event(host.toolName("save"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "save", input, run);
        AttestationToken.parse(stamp.token()).verify(printed);
    }

    private static final List<String> INVALID_AUTHORS = List.of(
            "claude", "user:someone", "", "claude:" + "x".repeat(129), "claude:with space", "Claude:abc", ":abc",
            "claude:a/b", "claude:한글", "1claude:abc", "claude:abc\n", "user:", "user:Owner", "a".repeat(33) + ":abc");

    static Stream<Arguments> invalidAuthors() {
        return INVALID_AUTHORS.stream().map(author -> Arguments.argumentSet("[" + author.length() + "] " + author, author));
    }

    @ParameterizedTest
    @MethodSource("invalidAuthors")
    @DisplayName("HK-029 an invalid author (claude, user:someone, empty, 129-character session, space ...) exits 2: nothing on stdout, one line on stderr, no key created")
    void refusesAnInvalidAuthor(String author, HookJar jar, HookFixture fixture) throws IOException {
        String[] args = {"session-key", author};
        Run process = jar.run(new byte[0], args);
        Run inProcess = fixture.run(args, new byte[0]);
        assertThat(List.of(process, inProcess)).allSatisfy(run -> {
            assertThat(run.exit()).isEqualTo(2);
            assertThat(run.stdout()).isEmpty();
            assertThat(run.err()).matches("[^\\n]+\\n").contains("author");
        });
        assertThat(jar.keyDirectory()).as("no key folder, so no key was created").doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user:owner", "gemini:x", "a:b", "vendor-1:A._-9", "codex:parent-1234_ab.cd"})
    @DisplayName("HK-029 a valid author is accepted: the vendor a-z 0-9 - (starting with a letter) and the literal user:owner")
    void acceptsAValidAuthor(String author, HookFixture fixture) {
        Run run = fixture.run(new String[] {"session-key", author}, new byte[0]);
        assertThat(run.err()).isEmpty();
        assertThat(run.exit()).isZero();
        assertThat(threeLines(run).get(0)).isEqualTo("author: " + author);
    }

    @Test
    @DisplayName("HK-029 a key folder that is a plain file: nothing on stdout, one line on stderr without key material, exit 2")
    void failsWhenTheKeyFolderIsAFile(HookFixture fixture) throws IOException {
        Files.writeString(fixture.keyDirectory(), "not a folder");
        Run run = fixture.run(new String[] {"session-key", AUTHOR}, new byte[0]);
        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stdout()).isEmpty();
        assertThat(run.err()).matches("[^\\n]+\\n").contains("session key").contains("[FOLDER_UNAVAILABLE]");
    }
}
