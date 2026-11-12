package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.testing.ContractVectors;
import tools.jackson.databind.JsonNode;

/**
 * HK-042, HK-043: the contract vectors of the author format and of the ledger tool names. The hook (Spring free, on the
 * attestation module only) and the server are two programs that each define these; the vector files of the test fixture
 * of {@code ledger-attestation} are what both are tested against (the server side runs the same files).
 */
@ExtendWith(HookExtension.class)
class ContractVectorsTest {

    private static JsonNode vectors(String resource) {
        return Json.MAPPER.readTree(ContractVectors.read(resource));
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.stringValue()));
        return texts;
    }

    // ---------------------------------------------------------------- HK-042 author

    static Stream<Arguments> validAuthors() {
        return texts(vectors(ContractVectors.AUTHORS).path("valid")).stream()
                .map(text -> Arguments.argumentSet(abbreviated(text), text));
    }

    /** The invalid vectors; a JSON null is the Java null (no author at all). */
    static Stream<Arguments> invalidAuthors() {
        List<Arguments> cases = new ArrayList<>();
        vectors(ContractVectors.AUTHORS).path("invalid").forEach(node -> {
            String value = node.path("value").isNull() ? null : node.path("value").stringValue();
            cases.add(Arguments.argumentSet(node.path("why").stringValue(), value));
        });
        return cases.stream();
    }

    private static String abbreviated(String text) {
        return text.length() > 40 ? text.substring(0, 37) + "... (" + text.length() + ")" : text;
    }

    @ParameterizedTest
    @MethodSource("validAuthors")
    @DisplayName("HK-042 every valid author vector is an Author, and its parts put together give the vector back")
    void everyValidVectorIsAnAuthor(String text) {
        Author author = Author.parse(text).orElseThrow(() -> new AssertionError("not accepted: " + text));
        assertThat(author.value()).isEqualTo(text);
        assertThat(author.vendor() + ":" + author.session().value()).isEqualTo(text);
    }

    @ParameterizedTest
    @MethodSource("invalidAuthors")
    @DisplayName("HK-042 no invalid author vector is an Author (the whole text counts, nothing is trimmed)")
    void noInvalidVectorIsAnAuthor(String text) {
        assertThat(Author.parse(text)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidAuthors")
    @DisplayName("HK-042 session-key refuses every invalid author vector with exit 2, nothing on stdout and no key created; a null author cannot be given on a command line")
    void sessionKeyRefusesInvalidVectors(String text, HookFixture fixture) {
        if (text == null) {
            return;
        }
        Run run = fixture.run(new String[] {HookCli.SESSION_KEY_COMMAND, "ledger", text}, new byte[0]);
        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stdout()).isEmpty();
        assertThat(run.err()).contains("author");
        assertThat(fixture.keyDirectory()).as("no key folder, so no key was created").doesNotExist();
    }

    @ParameterizedTest
    @MethodSource("validAuthors")
    @DisplayName("HK-042 session-key accepts every valid author vector and prints it back as the author line")
    void sessionKeyAcceptsValidVectors(String text, HookFixture fixture) {
        Run run = fixture.run(new String[] {HookCli.SESSION_KEY_COMMAND, "ledger", text}, new byte[0]);
        assertThat(run.err()).isEmpty();
        assertThat(run.exit()).isZero();
        assertThat(run.out()).contains("author: " + text + "\n");
    }

    @Test
    @DisplayName("HK-042 the value refuses what the format refuses: its compact constructor checks the vendor and the owner rule")
    void theValueChecksItself() {
        HostId session = new HostId("abc");
        assertThatThrownBy(() -> new Author("Claude", session)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Author("", session)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Author("user", session)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Author(null, session)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Author("claude", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HostId("a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HostId("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Author("user", new HostId("owner")).value()).isEqualTo("user:owner");
    }

    @Test
    @DisplayName("HK-042 the author of a vendor and a host id is <vendor prefix>:<host id>, and the three prefixes are claude, codex, gemini")
    void authorOfAVendorAndAHostId() {
        HostId id = new HostId("parent-1234_ab.cd");
        assertThat(Author.of(Vendor.CLAUDE, id).value()).isEqualTo("claude:parent-1234_ab.cd");
        assertThat(Author.of(Vendor.CODEX, id).value()).isEqualTo("codex:parent-1234_ab.cd");
        assertThat(Author.of(Vendor.GEMINI, id).value()).isEqualTo("gemini:parent-1234_ab.cd");
        for (Vendor vendor : Vendor.values()) {
            assertThat(Author.parse(Author.of(vendor, id).value())).as(vendor.name()).isPresent();
        }
    }

    // ---------------------------------------------------------------- HK-043 tool names

    @Test
    @DisplayName("HK-043 the write tools of the hook are exactly the write tool vectors, in the same order")
    void writeToolsAreTheVectors() {
        List<String> expected = texts(vectors(ContractVectors.WRITE_TOOLS).path("write"));
        assertThat(Arrays.stream(WriteTool.values()).map(WriteTool::canonicalName).toList()).isEqualTo(expected);
        assertThat(expected).hasSize(10);
    }

    @Test
    @DisplayName("HK-043 no read tool vector is a write tool of the hook: the hook never signs for a read tool")
    void readToolsAreNotWriteTools() {
        for (String read : texts(vectors(ContractVectors.WRITE_TOOLS).path("read"))) {
            assertThat(read).startsWith("ledger_");
            assertThat(WriteTool.ofOperation(read.substring("ledger_".length()))).as(read).isEmpty();
        }
    }

    @Test
    @DisplayName("HK-043 the vector files are UTF-8 JSON that the hook tests can read")
    void vectorFilesAreReadable() {
        assertThat(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(ContractVectors.read(ContractVectors.AUTHORS))).toString()).contains("claude:abc");
        assertThat(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(ContractVectors.read(ContractVectors.WRITE_TOOLS))).toString())
                .contains("ledger_save");
    }
}
