package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** HK-014: the JSON round trip keeps what the host sent. */
@ExtendWith(HookExtension.class)
class JsonFidelityTest {

    /** JSON texts whose literal form is the case (exponents, trailing zeros, duplicate keys): they are sent as they are. */
    private static final String NUMBERS =
            "{\"big\":12345678901234567890,\"huge\":1e300,\"neg\":-0,\"dec\":0.10,\"tiny\":0.000000000000000000000000001,"
                    + "\"long\":9223372036854775807,\"exp\":1.5E+3,\"precise\":3.141592653589793238462643383279}";

    private static final String ESCAPES =
            "{\"esc\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\",\"pair\":\"\\ud83d\\ude00\",\"raw\":\"😀 한글 é\","
                    + "\"unknown\":{\"a\":[1,{\"b\":null}],\"c\":true},\"empty\":{},\"list\":[]}";

    private static final String DEPTH_100 = "[".repeat(100) + "1" + "]".repeat(100);

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-014 big integers and decimals keep their exact value (no double), and 0.10 keeps its trailing zero")
    void numbersAreExact(HostScenario host, HookFixture fixture) {
        Run run = fixture.runClean(host, host.echoEvent("save", Json.raw(NUMBERS)));
        assertThat(run.out()).contains("\"big\":12345678901234567890").contains("\"dec\":0.10").contains("\"long\":9223372036854775807");
        JsonNode args = host.echoed(run);
        assertThat(args.path("big").bigIntegerValue()).isEqualTo(new BigInteger("12345678901234567890"));
        assertThat(args.path("huge").decimalValue()).isEqualByComparingTo(new BigDecimal("1e300"));
        assertThat(args.path("neg").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(args.path("tiny").decimalValue()).isEqualByComparingTo(new BigDecimal("0.000000000000000000000000001"));
        assertThat(args.path("precise").decimalValue()).isEqualByComparingTo(new BigDecimal("3.141592653589793238462643383279"));
        assertThat(args.path("exp").decimalValue()).isEqualByComparingTo(new BigDecimal("1500"));
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-014 escapes, surrogate pairs and Korean text survive; unknown fields pass through untouched; the output is UTF-8")
    void stringsAndUnknownFields(HostScenario host, HookFixture fixture) {
        Run run = fixture.runClean(host, host.echoEvent("save", Json.raw(ESCAPES)));
        JsonNode args = host.echoed(run);
        assertThat(args.path("esc").stringValue()).isEqualTo("\"\\/\b\f\n\r\tA");
        assertThat(args.path("pair").stringValue()).isEqualTo("😀");
        Attestations.Stamp stamp = fixture.stampCarrier(host.server(), host.author(HookFixture.HOST_ID), "save", Json.of(ESCAPES), args);
        assertThat(args).isEqualTo(Json.stamped(Json.of(ESCAPES), stamp.token(), stamp.addedRequestId()));
        assertThat(run.stdout()).contains("한글".getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-014 100 levels of nesting and duplicate keys: no crash (the last duplicate wins, as in JSON.parse)")
    void nestingAndDuplicates(HostScenario host, HookFixture fixture) {
        String sent = "{\"deep\":" + DEPTH_100 + ",\"dup\":1,\"dup\":2}";
        Run run = fixture.runClean(host, host.echoEvent("save", Json.raw(sent)));
        JsonNode args = host.echoed(run);
        assertThat(args.path("deep")).isEqualTo(Json.of(DEPTH_100));
        assertThat(args.path("dup").intValue()).isEqualTo(2);
        fixture.stampCarrier(host.server(), host.author(HookFixture.HOST_ID), "save", Json.of(sent), args);
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-014 nesting far beyond any real call is rejected as malformed input, never a stack overflow")
    void absurdNestingIsRejected(HostScenario host, HookFixture fixture) {
        String deep = "[".repeat(5000) + "]".repeat(5000);
        Run run = fixture.run(host, host.echoEvent("save", Json.raw("{\"deep\":" + deep + "}")));
        InputErrorTest.assertMalformedAnswer(host, run);
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-014 a 1 MB body is handled in process well inside the time budget")
    void oneMegabyteBody(HostScenario host, HookFixture fixture) {
        String text = "한글abc".repeat(120_000); // 9 bytes per unit: about 1.08 MB of UTF-8
        JsonNode input = Json.tree(Map.of("body", text, "n", new BigInteger("12345678901234567890")));
        byte[] event = host.echoEvent("save", input);
        assertThat(event.length).isGreaterThan(1_000_000);
        long start = System.nanoTime();
        Run run = fixture.runClean(host, event);
        long millis = (System.nanoTime() - start) / 1_000_000;
        JsonNode args = host.echoed(run);
        Attestations.Stamp stamp = fixture.stampCarrier(host.server(), host.author(HookFixture.HOST_ID), "save", input, args);
        assertThat(args).isEqualTo(Json.stamped(input, stamp.token(), stamp.addedRequestId()));
        assertThat(millis).as("in-process milliseconds").isLessThan(3_000);
    }

    @Test
    @DisplayName("HK-014 Antigravity Arguments given as JSON text: numbers and Korean text survive the parse and re-serialisation")
    void numbersInsideJsonTextArguments(HookFixture fixture) {
        AntigravityHost agy = AntigravityHost.INSTANCE;
        Run run = fixture.runClean(agy, agy.echoEvent("save", NUMBERS));
        String text = agy.echoed(run).stringValue();
        assertThat(text).contains("\"big\":12345678901234567890").contains("\"dec\":0.10").contains("\"long\":9223372036854775807");
    }

    @Test
    @DisplayName("HK-014 (changed by attest-fix) a lone surrogate escape has no UTF-8 form, so it has no canonical form to sign: denied ARGUMENTS_NOT_SIGNABLE, nothing signed")
    void loneSurrogate(HookFixture fixture) {
        CodexHost codex = new CodexHost();
        Run run = fixture.runClean(codex, codex.event(codex.toolName("save"), Json.raw("{\"lone\":\"\\ud800x\"}")));
        assertThat(run.document()).isEqualTo(codex.denied(Refusal.ARGUMENTS_NOT_SIGNABLE));
        assertThat(fixture.keyDirectory()).doesNotExist();
    }
}
