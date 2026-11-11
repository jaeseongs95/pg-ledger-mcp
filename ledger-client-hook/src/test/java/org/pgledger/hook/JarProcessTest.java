package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Tests that start the real jar with {@code java -jar}: HK-015, HK-016, HK-017, HK-020, HK-021 and the 1 MB body of HK-014. */
@ExtendWith(HookExtension.class)
class JarProcessTest {

    private static final int HOST_LIMIT_MILLIS = 5_000;
    private static final JsonNode KOREAN_INPUT =
            Json.tree(Map.of("note", "한글 😀 é", "n", new java.math.BigInteger("12345678901234567890")));

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Four answers of every host: stamped, denied, passed and silent for empty input. */
    static Stream<Arguments> cleanChannelCases() {
        return Hosts.ALL.stream().flatMap(host -> Stream.of(
                Arguments.argumentSet(host + " stamp", host, host.event(host.toolName("save"), KOREAN_INPUT),
                        host.allowed(KOREAN_INPUT, host.attestation(HookFixture.HOST_ID))),
                Arguments.argumentSet(host + " deny", host,
                        host.event(host.toolName("save"), Json.tree(Map.of(Json.RESERVED_KEY, Json.of("null")))),
                        host.denied(Refusal.RESERVED_FIELD)),
                Arguments.argumentSet(host + " pass-through", host, host.event(host.toolName("get"), Json.of("{}")),
                        host.untouched()),
                Arguments.argumentSet(host + " empty input", host, new byte[0], Optional.empty())));
    }

    @ParameterizedTest
    @MethodSource("cleanChannelCases")
    @DisplayName("HK-016 real jar: stdout is at most one line of JSON, stderr is empty, exit 0 (stamp, deny, pass-through, empty input)")
    void cleanChannels(HostScenario host, byte[] stdin, Optional<JsonNode> expected, HookJar jar) throws IOException {
        Run run = jar.run(stdin, host.client().argument());
        assertThat(run.err()).as("stderr").isEmpty();
        assertThat(run.exit()).as("exit code").isZero();
        assertThat(run.out()).matches("(?s)|[^\\n]*\\n");
        assertThat(run.document()).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-016 real jar: Claude malformed JSON exits 2 with a stderr message and empty stdout; Codex and Antigravity deny on stdout, exit 0")
    void malformedThroughTheJar(HostScenario host, HookJar jar) throws IOException {
        InputErrorTest.assertMalformedAnswer(host, jar.run(bytes("{"), host.client().argument()));
    }

    private static final List<String> NON_DEFAULT_JVM = List.of(
            "-Dfile.encoding=windows-949", "-Dstdout.encoding=windows-949", "-Dstderr.encoding=windows-949",
            "-Dsun.jnu.encoding=windows-949", "-Dstdin.encoding=windows-949", "-Duser.language=tr", "-Duser.country=TR");

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-015 with another JVM default charset, locale and LC_ALL=C the output bytes are the same UTF-8 bytes")
    void outputIsUtf8WhateverTheJvmDefaults(HostScenario host, HookJar jar, HookFixture fixture) throws IOException {
        byte[] stdin = host.echoEvent("save", KOREAN_INPUT);
        Run baseline = jar.run(stdin, host.client().argument());
        assertThat(baseline.exit()).isZero();
        assertThat(baseline.stdout()).contains(bytes("한글 😀 é"));
        Run other = jar.start(HookJar.JAVA, NON_DEFAULT_JVM, Map.of("LC_ALL", "C", "LANG", "C"), stdin, host.client().argument()).run();
        assertThat(other.err()).as("stderr").isEmpty();
        assertThat(other.exit()).isZero();
        assertThat(other.stdout()).isEqualTo(baseline.stdout());
        assertThat(fixture.runClean(host, stdin).stdout()).isEqualTo(baseline.stdout());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("HK-017 cold start of java -jar finishes inside the 5 s host hook limit; the measured time of each start is recorded")
    void coldStartBudget(int start, HookJar jar) throws IOException {
        CodexHost codex = new CodexHost();
        HookJar.Timed timed = jar.start(codex.event(codex.toolName("save"), KOREAN_INPUT), "codex");
        assertThat(timed.run().err()).isEmpty();
        assertThat(timed.run().document()).isEqualTo(codex.allowed(KOREAN_INPUT, "codex:" + HookFixture.HOST_ID));
        String report = "HK-017 start " + start + ": java -jar wall time " + timed.millis()
                + " ms ; limit " + HOST_LIMIT_MILLIS + " ms ; jar " + HookJar.JAR.toFile().length() + " bytes ; "
                + System.getProperty("java.vendor.version") + " ; os " + System.getProperty("os.name");
        Path dir = Path.of(System.getProperty("ledger.hook.metrics"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("hk-017-cold-start-" + start + ".txt"), report + "\n", StandardCharsets.UTF_8);
        assertThat(timed.millis()).isLessThan(HOST_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("HK-014 a 1 MB body through the real jar stays inside the 5 s host limit")
    void oneMegabyteThroughTheJar(HookJar jar) throws IOException {
        ClaudeHost claude = new ClaudeHost();
        String text = "한글abc".repeat(120_000);
        JsonNode input = Json.tree(Map.of("body", text));
        byte[] stdin = claude.event(claude.toolName("save"), input);
        assertThat(stdin.length).isGreaterThan(1_000_000);
        HookJar.Timed timed = jar.start(stdin, "claude");
        assertThat(timed.run().err()).isEmpty();
        assertThat(timed.run().document()).isEqualTo(claude.allowed(input, "claude:" + HookFixture.HOST_ID));
        assertThat(timed.millis()).isLessThan(HOST_LIMIT_MILLIS);
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.of(
                Arguments.argumentSet("Claude", List.of("Claude")),
                Arguments.argumentSet("gemini", List.of("gemini")),
                Arguments.argumentSet("empty", List.of("")),
                Arguments.argumentSet("none", List.of()),
                Arguments.argumentSet("two", List.of("claude", "codex")),
                Arguments.argumentSet("CODEX", List.of("CODEX")),
                Arguments.argumentSet("leading space", List.of(" codex")));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    @DisplayName("HK-020 the argument must be exactly claude, codex or antigravity: anything else exits 2 with usage on stderr and nothing on stdout")
    void argumentIsAnEnum(List<String> arguments, HookJar jar, HookFixture fixture) throws IOException {
        CodexHost codex = new CodexHost();
        byte[] stdin = codex.event(codex.toolName("save"), Json.of("{}"));
        String[] args = arguments.toArray(String[]::new);
        Run process = jar.run(stdin, args);
        Run inProcess = fixture.run(args, stdin);
        assertThat(List.of(process, inProcess)).allSatisfy(run -> {
            assertThat(run.exit()).isEqualTo(2);
            assertThat(run.stdout()).isEmpty();
            assertThat(run.err()).contains("usage").contains("claude|codex|antigravity");
        });
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-021 java -jar with nothing but the jar (empty working directory, no CLASSPATH) runs on this JDK 25")
    void runsWithOnlyTheJar(HostScenario host, HookJar jar) throws IOException {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(25);
        JsonNode input = Json.of("{}");
        Run run = jar.run(host.event(host.toolName("whoami"), input), host.client().argument());
        assertThat(run.err()).isEmpty();
        assertThat(run.document()).isEqualTo(host.allowed(input, host.attestation(HookFixture.HOST_ID)));
    }

    @Test
    @DisplayName("HK-021 on a JDK older than 25 the JVM refuses the jar with UnsupportedClassVersionError (needs LEDGER_HOOK_LEGACY_JAVA)")
    void olderJdkFailsClearly(HookJar jar) throws IOException {
        String legacy = System.getenv("LEDGER_HOOK_LEGACY_JAVA");
        Assumptions.assumeTrue(legacy != null && !legacy.isBlank(),
                "NOT_RUN: no JDK older than 25 on this host (set LEDGER_HOOK_LEGACY_JAVA to its java executable)");
        CodexHost codex = new CodexHost();
        Run run = jar.start(Path.of(legacy), List.of(), Map.of(), codex.event(codex.toolName("save"), Json.of("{}")), "codex").run();
        assertThat(run.exit()).isNotZero();
        assertThat(run.stdout()).isEmpty();
        assertThat(run.err()).contains("UnsupportedClassVersionError").contains("69.0");
    }
}
