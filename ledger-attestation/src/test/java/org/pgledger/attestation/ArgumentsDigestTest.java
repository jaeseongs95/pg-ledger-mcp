package org.pgledger.attestation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** ATT-030 .. ATT-034: the canonical form (RFC 8785) and the digest of the arguments of a call. */
class ArgumentsDigestTest {

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    @Test
    @DisplayName("ATT-030 the sample of RFC 8785 section 3.2.2 gives exactly the output of the RFC")
    void rfcSample() {
        Map<String, Object> input = map(
                "numbers", List.of(333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001),
                "string", "€$\u000f\nA'B\"\\\\\"/",
                "literals", Arrays.asList(null, true, false));
        assertThat(JsonCanonicalizer.canonicalize(input)).isEqualTo(
                "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                        + "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}");
    }

    static Stream<Arguments> numbers() {
        return Stream.of(
                Arguments.of(0, "0"), Arguments.of(-0.0, "0"), Arguments.of(1, "1"), Arguments.of(1.0, "1"),
                Arguments.of(4.50, "4.5"), Arguments.of(2e-3, "0.002"), Arguments.of(-1, "-1"),
                Arguments.of(123456789012345680000d, "123456789012345680000"), Arguments.of(1e21, "1e+21"),
                Arguments.of(1e-6, "0.000001"), Arguments.of(1e-7, "1e-7"), Arguments.of(5e-324, "5e-324"),
                Arguments.of(1.7976931348623157e308, "1.7976931348623157e+308"),
                Arguments.of(9007199254740992L, "9007199254740992"), Arguments.of(9007199254740993L, "9007199254740992"),
                Arguments.of(0.1, "0.1"), Arguments.of(100, "100"), Arguments.of(1.5e300, "1.5e+300"),
                Arguments.of(-1.25e-10, "-1.25e-10"), Arguments.of(12345.678, "12345.678"));
    }

    @ParameterizedTest
    @MethodSource("numbers")
    @DisplayName("ATT-031 a number is the ECMAScript text of its double, the same for Integer, Long, Double, BigInteger and BigDecimal")
    void numberText(Number value, String expected) {
        assertThat(JsonCanonicalizer.canonicalize(value)).isEqualTo(expected);
        assertThat(JsonCanonicalizer.canonicalize(new BigDecimal(value.toString()))).isEqualTo(expected);
        assertThat(JsonCanonicalizer.canonicalize(Double.valueOf(value.doubleValue()))).isEqualTo(expected);
        if (value.doubleValue() == Math.rint(value.doubleValue()) && Math.abs(value.doubleValue()) < 9e15) {
            assertThat(JsonCanonicalizer.canonicalize(BigInteger.valueOf(value.longValue()))).isEqualTo(expected);
            assertThat(JsonCanonicalizer.canonicalize(Long.valueOf(value.longValue()))).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("ATT-032 strings have the minimal escapes of RFC 8785; member names are sorted by UTF-16 code units")
    void stringsAndMemberOrder() {
        StringBuilder controls = new StringBuilder();
        for (char c = 0; c < 0x20; c++) {
            controls.append(c);
        }
        String canonical = JsonCanonicalizer.canonicalize(controls.toString());
        assertThat(canonical).isEqualTo("\"\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007\\b\\t\\n\\u000b\\f\\r"
                + "\\u000e\\u000f\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017\\u0018\\u0019\\u001a\\u001b"
                + "\\u001c\\u001d\\u001e\\u001f\"");
        assertThat(JsonCanonicalizer.canonicalize("a/b\u007f😀한글\"\\"))
                .isEqualTo("\"a/b\u007f😀한글\\\"\\\\\"");

        Map<String, Object> members = map("דּ", 1, "😀", 2, "B", 3, "a", 4, "", 5);
        // UTF-16 order: "" < "B" < "a" < U+D83D U+DE00 < U+FB33 (the emoji's first code unit is D83D), unlike UTF-8 order
        assertThat(JsonCanonicalizer.canonicalize(members))
                .isEqualTo("{\"\":5,\"B\":3,\"a\":4,\"😀\":2,\"דּ\":1}");
    }

    private static Map<String, Object> call() {
        return map("request_id", "r1", "entity", "defect", "fields", map("title", "t", "project_id", "p"),
                "ops", List.of(map("op", "save"), map("op", "link")));
    }

    @Test
    @DisplayName("ATT-033 the digest is 64 lower-case hex, ignores key order and the spelling of numbers and the top-level _hostAttestation, and differs for any other change")
    void digestProperties() {
        String digest = ArgumentsDigest.of(call());
        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(ArgumentsDigest.of(Map.of())).isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");

        Map<String, Object> reordered = map("ops", List.of(map("op", "save"), map("op", "link")),
                "fields", map("project_id", "p", "title", "t"), "entity", "defect", "request_id", "r1");
        assertThat(ArgumentsDigest.of(reordered)).isEqualTo(digest);
        assertThat(ArgumentsDigest.of(map("n", 1))).isEqualTo(ArgumentsDigest.of(map("n", 1.0)))
                .isEqualTo(ArgumentsDigest.of(map("n", new BigDecimal("1.00"))));

        Map<String, Object> withAttestation = call();
        withAttestation.put("_hostAttestation", "pgla2.whatever");
        assertThat(ArgumentsDigest.of(withAttestation)).isEqualTo(digest);
        withAttestation.put("_hostAttestation", null);
        assertThat(ArgumentsDigest.of(withAttestation)).isEqualTo(digest);
        // the same name below the top level is content
        assertThat(ArgumentsDigest.of(map("a", map("_hostAttestation", "x")))).isNotEqualTo(ArgumentsDigest.of(map("a", map())));

        List<Map<String, Object>> others = new ArrayList<>();
        Map<String, Object> changedValue = call();
        changedValue.put("entity", "task");
        others.add(changedValue);
        Map<String, Object> changedNested = call();
        changedNested.put("fields", map("title", "T", "project_id", "p"));
        others.add(changedNested);
        Map<String, Object> swapped = call();
        swapped.put("ops", List.of(map("op", "link"), map("op", "save")));
        others.add(swapped);
        Map<String, Object> extra = call();
        extra.put("ops", List.of(map("op", "save"), map("op", "link"), map("op", "save")));
        others.add(extra);
        Map<String, Object> shorter = call();
        shorter.put("ops", List.of(map("op", "save")));
        others.add(shorter);
        Map<String, Object> added = call();
        added.put("note", "x");
        others.add(added);
        Map<String, Object> removed = call();
        removed.remove("request_id");
        others.add(removed);
        Map<String, Object> asText = call();
        asText.put("fields", "{\"title\":\"t\",\"project_id\":\"p\"}");
        others.add(asText);
        for (Map<String, Object> other : others) {
            assertThat(ArgumentsDigest.of(other)).as("%s", other).isNotEqualTo(digest);
        }
        // empty text, absent and JSON null are three different calls: the signature covers what was sent
        assertThat(List.of(ArgumentsDigest.of(map("a", "")), ArgumentsDigest.of(map()), ArgumentsDigest.of(map("a", null))))
                .doesNotHaveDuplicates();
        assertThat(ArgumentsDigest.of(map("a", "1"))).isNotEqualTo(ArgumentsDigest.of(map("a", 1)));
    }

    static Stream<Arguments> notJson() {
        return Stream.of(
                Arguments.argumentSet("NaN", map("a", Double.NaN)),
                Arguments.argumentSet("infinity", map("a", Double.POSITIVE_INFINITY)),
                Arguments.argumentSet("overflow", map("a", new BigDecimal("1e999"))),
                Arguments.argumentSet("lone high surrogate", map("a", "x\ud83d")),
                Arguments.argumentSet("lone low surrogate", map("a", "\ude00x")),
                Arguments.argumentSet("lone surrogate in a name", map("\ud83d", 1)),
                Arguments.argumentSet("non-String key", new LinkedHashMap<Object, Object>(Map.of(1, "x"))),
                Arguments.argumentSet("Object", map("a", new Object())),
                Arguments.argumentSet("Date", map("a", new Date(0))),
                Arguments.argumentSet("deep", deep(300)));
    }

    private static Map<String, Object> deep(int levels) {
        Map<String, Object> value = map();
        for (int i = 0; i < levels; i++) {
            value = map("a", value);
        }
        return value;
    }

    @ParameterizedTest
    @MethodSource("notJson")
    @DisplayName("ATT-034 something that is not JSON is an IllegalArgumentException, never the digest of something else")
    void notJsonIsRefused(Map<?, ?> arguments) {
        assertThatThrownBy(() -> ArgumentsDigest.of(arguments)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ATT-034 no arguments object (null) is an IllegalArgumentException")
    void nullIsRefused() {
        assertThatThrownBy(() -> ArgumentsDigest.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
