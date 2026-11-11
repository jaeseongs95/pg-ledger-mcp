package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pgledger.hook.HookHarness.ATTESTATION;
import static org.pgledger.hook.HookHarness.HOST_ID;
import static org.pgledger.hook.HookHarness.echoedArguments;
import static org.pgledger.hook.HookHarness.echoingEvent;
import static org.pgledger.hook.HookHarness.event;
import static org.pgledger.hook.HookHarness.outcome;
import static org.pgledger.hook.HookHarness.run;
import static org.pgledger.hook.HookHarness.toolName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.hook.HookHarness.Outcome;
import org.pgledger.hook.HookHarness.Run;
import tools.jackson.databind.JsonNode;

/** HK-014: the JSON round trip keeps what the host sent. */
class JsonFidelityTest {

    private static final String NUMBERS =
            "{\"big\":12345678901234567890,\"huge\":1e999,\"neg\":-0,\"dec\":0.10,\"tiny\":0.000000000000000000000000001,"
                    + "\"long\":9223372036854775807,\"exp\":1.5E+3,\"precise\":3.141592653589793238462643383279}";

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-014 big integers and decimals keep their exact value (no double), and 0.10 keeps its trailing zero")
    void numbersAreExact(Client client) {
        Run run = run(client, echoingEvent(client, "save", NUMBERS)).ok();
        String out = run.out();
        assertThat(out).contains("\"big\":12345678901234567890").contains("\"dec\":0.10").contains("\"long\":9223372036854775807");
        JsonNode args = echoedArguments(client, run);
        assertThat(args.path("big").bigIntegerValue()).isEqualTo(new BigInteger("12345678901234567890"));
        assertThat(args.path("huge").decimalValue()).isEqualByComparingTo(new BigDecimal("1e999"));
        assertThat(args.path("neg").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(args.path("tiny").decimalValue()).isEqualByComparingTo(new BigDecimal("0.000000000000000000000000001"));
        assertThat(args.path("precise").decimalValue()).isEqualByComparingTo(new BigDecimal("3.141592653589793238462643383279"));
        assertThat(args.path("exp").decimalValue()).isEqualByComparingTo(new BigDecimal("1500"));
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-014 escapes, surrogate pairs and Korean text survive; unknown fields pass through untouched")
    void stringsAndUnknownFields(Client client) {
        String input = "{\"esc\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\",\"pair\":\"\\ud83d\\ude00\",\"raw\":\"😀 한글 é\","
                + "\"unknown\":{\"a\":[1,{\"b\":null}],\"c\":true},\"empty\":{},\"list\":[]}";
        Run run = run(client, echoingEvent(client, "save", input)).ok();
        JsonNode args = echoedArguments(client, run);
        assertThat(args.path("esc").stringValue()).isEqualTo("\"\\/\b\f\n\r\tA");
        assertThat(args.path("pair").stringValue()).isEqualTo("😀");
        assertThat(args.path("raw").stringValue()).isEqualTo("😀 한글 é");
        assertThat(args).isEqualTo(HookHarness.withAttestation(input, vendorPrefix(client) + HOST_ID));
        // the output is UTF-8 text with the characters themselves, not byte soup
        assertThat(run.stdout()).contains("한글".getBytes(StandardCharsets.UTF_8));
    }

    private static String vendorPrefix(Client client) {
        return HookHarness.vendor(client) + ":";
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-014 100 levels of nesting and duplicate keys: no crash (the last duplicate wins, as in JSON.parse)")
    void nestingAndDuplicates(Client client) {
        String deep = "[".repeat(100) + "1" + "]".repeat(100);
        String input = "{\"deep\":" + deep + ",\"dup\":1,\"dup\":2}";
        Run run = run(client, echoingEvent(client, "save", input)).ok();
        JsonNode args = echoedArguments(client, run);
        assertThat(args.path("deep")).isEqualTo(HookHarness.MAPPER.readTree(deep));
        assertThat(args.path("dup").intValue()).isEqualTo(2);
        assertThat(args.path(ATTESTATION).stringValue()).isEqualTo(vendorPrefix(client) + HOST_ID);
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-014 nesting far beyond any real call is rejected as malformed input, never a stack overflow")
    void absurdNestingIsRejected(Client client) {
        String deep = "[".repeat(5000) + "]".repeat(5000);
        Run run = run(client, echoingEvent(client, "save", "{\"deep\":" + deep + "}"));
        if (client == Client.CLAUDE) {
            assertThat(run.exit()).isEqualTo(2);
        } else {
            assertThat(outcome(client, run.ok())).isEqualTo(Outcome.DENIED);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-014 a 1 MB body is handled in process well inside the time budget")
    void oneMegabyteBody(Client client) {
        String text = "한글abc".repeat(120_000); // 9 bytes per unit: about 1.08 MB of UTF-8
        String input = "{\"body\":\"" + text + "\",\"n\":12345678901234567890}";
        String event = echoingEvent(client, "save", input);
        assertThat(event.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(1_000_000);
        long start = System.nanoTime();
        Run run = run(client, event).ok();
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(echoedArguments(client, run).path("body").stringValue()).isEqualTo(text);
        assertThat(millis).as("in-process milliseconds").isLessThan(3_000);
    }

    @Test
    @DisplayName("HK-014 Antigravity Arguments given as JSON text: numbers and Korean text survive the parse and re-serialisation")
    void numbersInsideJsonTextArguments() {
        String envelope = "{\"ServerName\":\"s\",\"ToolName\":\"ledger_save\",\"Arguments\":" + HookHarness.quote(NUMBERS) + "}";
        Run run = run(Client.ANTIGRAVITY, event(Client.ANTIGRAVITY, "call_mcp_tool", envelope)).ok();
        String text = run.json().path("overwrite").path("Arguments").stringValue();
        assertThat(text).contains("\"big\":12345678901234567890").contains("\"dec\":0.10").contains("\"long\":9223372036854775807");
    }

    @Test
    @DisplayName("HK-014 a lone surrogate escape is passed through as the same escape, not replaced or rejected")
    void loneSurrogate() {
        String input = "{\"lone\":\"\\ud800x\"}";
        Run run = run(Client.CODEX, event(Client.CODEX, toolName(Client.CODEX, "save"), input)).ok();
        assertThat(run.out()).contains("\"lone\":\"\\uD800x\"");
    }
}
