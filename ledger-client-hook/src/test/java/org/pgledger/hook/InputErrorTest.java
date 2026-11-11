package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pgledger.hook.HookHarness.event;
import static org.pgledger.hook.HookHarness.outcome;
import static org.pgledger.hook.HookHarness.run;
import static org.pgledger.hook.HookHarness.toolName;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.hook.HookHarness.Outcome;
import org.pgledger.hook.HookHarness.Run;

/** HK-008: broken input. */
class InputErrorTest {

    private static final List<String> MALFORMED = List.of("{", "{\"a\":", "not json", "{} {}", "{\"a\":1,}", "[1,", "'{}'", "{\"a\":NaN}", "{\"a\":01}");

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-008 malformed JSON: Claude exits 2 with a message on stderr and nothing on stdout; Codex and Antigravity deny explicitly and exit 0")
    void malformedJson(Client client) {
        for (String stdin : MALFORMED) {
            Run run = run(client, stdin);
            if (client == Client.CLAUDE) {
                assertThat(run.exit()).as(stdin).isEqualTo(2);
                assertThat(run.stdout()).as(stdin).isEmpty();
                assertThat(run.err()).as(stdin).contains("ledger").endsWith("\n");
            } else {
                run.ok();
                assertThat(outcome(client, run)).as(stdin).isEqualTo(Outcome.DENIED);
                assertThat(HookHarness.denialText(client, run)).contains("valid JSON");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-008 invalid UTF-8 bytes are malformed input, not a crash")
    void invalidUtf8(Client client) {
        byte[] bytes = new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        Run run = run(new String[] {client.argument()}, bytes);
        if (client == Client.CLAUDE) {
            assertThat(run.exit()).isEqualTo(2);
        } else {
            assertThat(outcome(client, run.ok())).isEqualTo(Outcome.DENIED);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-008 empty or blank stdin: no output, exit 0")
    void emptyStdin(Client client) {
        for (String stdin : List.of("", " ", "\r\n\t ")) {
            Run run = run(client, stdin).ok();
            assertThat(run.stdout()).as("blank").isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-008 non-object arguments (null, [], \"text\", 7, true) of a ledger tool are denied")
    void nonObjectArguments(Client client) {
        for (String arguments : List.of("null", "[]", "\"text\"", "7", "true", "1.5")) {
            Run run = run(client, event(client, toolName(client, "save"), arguments)).ok();
            assertThat(outcome(client, run)).as(arguments).isEqualTo(Outcome.DENIED);
            assertThat(HookHarness.denialText(client, run)).contains("object");
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-008 valid JSON that is not a host event (null, [], 7, \"x\", {}) is not a ledger call: untouched")
    void notAnEvent(Client client) {
        for (String stdin : List.of("null", "[]", "7", "\"x\"", "{}")) {
            Run run = run(client, stdin).ok();
            assertThat(outcome(client, run)).as(stdin).isEqualTo(Outcome.UNTOUCHED);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-008 a leading UTF-8 byte order mark does not break parsing")
    void byteOrderMark(Client client) {
        byte[] json = event(client, toolName(client, "whoami"), "{}").getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[json.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(json, 0, withBom, 3, json.length);
        assertThat(outcome(client, run(new String[] {client.argument()}, withBom).ok())).isEqualTo(Outcome.STAMPED);
    }
}
