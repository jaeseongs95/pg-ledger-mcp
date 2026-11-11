package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** HK-008: broken input. */
@ExtendWith(HookExtension.class)
class InputErrorTest {

    /** Text that is not one valid JSON document; it cannot be produced by a JSON writer, so it is written out. */
    private static final List<String> MALFORMED = List.of(
            "{", "{\"a\":", "not json", "{} {}", "{\"a\":1,}", "[1,", "'{}'", "{\"a\":NaN}", "{\"a\":01}");

    static Stream<Arguments> malformedTexts() {
        return Hosts.cross(MALFORMED);
    }

    static void assertMalformedAnswer(HostScenario host, Run run) {
        HostScenario.Malformed expected = host.malformedInput();
        assertThat(run.exit()).as("exit code").isEqualTo(expected.exit());
        assertThat(run.document()).as("stdout").isEqualTo(expected.stdout());
        assertThat(run.err()).as("stderr").isEqualTo(expected.stderr());
    }

    @ParameterizedTest
    @MethodSource("malformedTexts")
    @DisplayName("HK-008 malformed JSON: Claude exits 2 with a message on stderr and nothing on stdout; Codex and Antigravity deny explicitly and exit 0")
    void malformedJson(HostScenario host, String stdin, HookFixture fixture) {
        assertMalformedAnswer(host, fixture.run(host, stdin.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-008 invalid UTF-8 bytes are malformed input, not a crash")
    void invalidUtf8(HostScenario host, HookFixture fixture) {
        byte[] bytes = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        assertMalformedAnswer(host, fixture.run(host, bytes));
    }

    static Stream<Arguments> blankTexts() {
        return Hosts.cross(List.of("", " ", "\r\n\t "));
    }

    @ParameterizedTest
    @MethodSource("blankTexts")
    @DisplayName("HK-008 empty or blank stdin: no output, exit 0")
    void emptyStdin(HostScenario host, String stdin, HookFixture fixture) {
        Run run = fixture.runClean(host, stdin.getBytes(StandardCharsets.UTF_8));
        assertThat(run.stdout()).isEmpty();
    }

    static Stream<Arguments> nonObjectArguments() {
        return Hosts.cross(Stream.of("null", "[]", "\"text\"", "7", "true", "1.5").map(Json::of).toList());
    }

    @ParameterizedTest
    @MethodSource("nonObjectArguments")
    @DisplayName("HK-008 non-object arguments (null, [], \"text\", 7, true, 1.5) of a ledger tool are denied")
    void nonObjectArguments(HostScenario host, JsonNode arguments, HookFixture fixture) {
        Run run = fixture.runClean(host, host.event(host.toolName("save"), arguments));
        assertThat(run.document()).isEqualTo(host.denied(Refusal.ARGUMENTS_NOT_OBJECT));
        assertThat(run.out()).contains("object");
    }

    static Stream<Arguments> notEvents() {
        return Hosts.cross(List.of("null", "[]", "7", "\"x\"", "{}"));
    }

    @ParameterizedTest
    @MethodSource("notEvents")
    @DisplayName("HK-008 valid JSON that is not a host event (null, [], 7, \"x\", {}) is not a ledger call: untouched")
    void notAnEvent(HostScenario host, String stdin, HookFixture fixture) {
        Run run = fixture.runClean(host, stdin.getBytes(StandardCharsets.UTF_8));
        assertThat(run.document()).isEqualTo(host.untouched());
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-008 a leading UTF-8 byte order mark does not break parsing")
    void byteOrderMark(HostScenario host, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("note", "한글"));
        byte[] json = host.event(host.toolName("whoami"), input);
        byte[] withBom = new byte[json.length + 3];
        System.arraycopy(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, 0, withBom, 0, 3);
        System.arraycopy(json, 0, withBom, 3, json.length);
        Run run = fixture.runClean(host, withBom);
        Attestations.Stamp stamp = fixture.stamp(host, "whoami", input, run);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), stamp.addedRequestId()));
    }
}
