package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pgledger.hook.HookHarness.HOST_ID;
import static org.pgledger.hook.HookHarness.event;
import static org.pgledger.hook.HookHarness.outcome;
import static org.pgledger.hook.HookHarness.toolName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.hook.HookHarness.Outcome;
import org.pgledger.hook.HookHarness.Run;

/** Tests that start the real jar with {@code java -jar}: HK-015, HK-016, HK-017, HK-020, HK-021 and the 1 MB body of HK-014. */
class JarProcessTest {

    private static final Path JAR = Path.of(System.getProperty("ledger.hook.jar"));
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    private static final int HOST_LIMIT_MILLIS = 5_000;

    @TempDir
    Path work;

    private record Timed(Run run, long millis) {}

    /** Runs the jar from an empty working directory with the class path variables removed: only the jar is available. */
    private Timed java(Path javaExecutable, List<String> jvmArgs, Map<String, String> env, byte[] stdin, String... args) throws IOException {
        Path in = Files.createTempFile(work, "stdin", ".json");
        Path out = Files.createTempFile(work, "stdout", ".out");
        Path err = Files.createTempFile(work, "stderr", ".out");
        Files.write(in, stdin);
        Path cwd = Files.createTempDirectory(work, "cwd");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(JAR.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        for (String name : List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            builder.environment().remove(name);
        }
        builder.environment().putAll(env);
        long start = System.nanoTime();
        Process process = builder.start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("hook jar did not finish in 60 s");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Timed(new Run(process.exitValue(), Files.readAllBytes(out), Files.readAllBytes(err)), millis);
    }

    private Timed jar(byte[] stdin, String... args) throws IOException {
        return java(JAVA, List.of(), Map.of(), stdin, args);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final String KOREAN_INPUT = "{\"note\":\"한글 😀 é\",\"n\":12345678901234567890}";

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-016 real jar: stdout is one line of JSON, stderr is empty, exit 0 (stamp, deny, pass-through)")
    void cleanChannels(Client client) throws IOException {
        List<String> stdins = List.of(
                HookHarness.echoingEvent(client, "save", KOREAN_INPUT),
                event(client, toolName(client, "save"), "{\"_hostAttestation\":null}"),
                event(client, toolName(client, "get"), "{}"),
                "");
        List<Outcome> expected = List.of(Outcome.STAMPED, Outcome.DENIED, Outcome.UNTOUCHED, Outcome.UNTOUCHED);
        for (int i = 0; i < stdins.size(); i++) {
            Run run = jar(bytes(stdins.get(i)), client.argument()).run().ok();
            assertThat(outcome(client, run)).as("case " + i).isEqualTo(expected.get(i));
            if (run.stdout().length > 0) {
                assertThat(run.out()).endsWith("\n");
                assertThat(run.out().trim()).doesNotContain("\n");
                run.json();
            }
        }
    }

    @Test
    @DisplayName("HK-016 real jar: Claude malformed JSON exits 2 with a stderr message and empty stdout; Codex and Antigravity deny on stdout, exit 0")
    void malformedThroughTheJar() throws IOException {
        Run claude = jar(bytes("{"), "claude").run();
        assertThat(claude.exit()).isEqualTo(2);
        assertThat(claude.stdout()).isEmpty();
        assertThat(claude.err()).contains("ledger");
        for (Client client : List.of(Client.CODEX, Client.ANTIGRAVITY)) {
            Run run = jar(bytes("{"), client.argument()).run().ok();
            assertThat(outcome(client, run)).isEqualTo(Outcome.DENIED);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-015 with another JVM default charset, locale and LC_ALL=C the output bytes are the same UTF-8 bytes")
    void outputIsUtf8WhateverTheJvmDefaults(Client client) throws IOException {
        byte[] stdin = bytes(HookHarness.echoingEvent(client, "save", KOREAN_INPUT));
        Run baseline = jar(stdin, client.argument()).run().ok();
        assertThat(baseline.stdout()).contains(bytes("한글 😀 é"));
        List<String> odd = List.of("-Dfile.encoding=windows-949", "-Dstdout.encoding=windows-949", "-Dstderr.encoding=windows-949",
                "-Dsun.jnu.encoding=windows-949", "-Dstdin.encoding=windows-949", "-Duser.language=tr", "-Duser.country=TR");
        Timed other = java(JAVA, odd, Map.of("LC_ALL", "C", "LANG", "C"), stdin, client.argument());
        assertThat(other.run().err()).as("stderr").isEmpty();
        assertThat(other.run().exit()).isZero();
        assertThat(other.run().stdout()).isEqualTo(baseline.stdout());
        // and the in-process run produces the same bytes again
        assertThat(HookHarness.run(client, HookHarness.echoingEvent(client, "save", KOREAN_INPUT)).stdout()).isEqualTo(baseline.stdout());
    }

    @Test
    @DisplayName("HK-017 cold start of java -jar finishes inside the 5 s host hook limit; the measured times are recorded")
    void coldStartBudget() throws IOException {
        byte[] stdin = bytes(event(Client.CODEX, toolName(Client.CODEX, "save"), KOREAN_INPUT));
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Timed timed = jar(stdin, "codex");
            assertThat(outcome(Client.CODEX, timed.run().ok())).isEqualTo(Outcome.STAMPED);
            millis.add(timed.millis());
        }
        String report = "HK-017 java -jar wall time in ms (run 1 = first start after the build, runs 2-5 = repeated starts): " + millis
                + " ; limit " + HOST_LIMIT_MILLIS + " ; jar " + JAR.toFile().length() + " bytes ; "
                + System.getProperty("java.vendor.version") + " ; os " + System.getProperty("os.name");
        Path dir = Path.of(System.getProperty("ledger.hook.metrics"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("hk-017-cold-start.txt"), report + "\n", StandardCharsets.UTF_8);
        System.out.println(report);
        assertThat(millis).allSatisfy(ms -> assertThat(ms).isLessThan(HOST_LIMIT_MILLIS));
    }

    @Test
    @DisplayName("HK-014 a 1 MB body through the real jar stays inside the 5 s host limit")
    void oneMegabyteThroughTheJar() throws IOException {
        String text = "한글abc".repeat(120_000);
        String input = "{\"body\":\"" + text + "\"}";
        byte[] stdin = bytes(event(Client.CLAUDE, toolName(Client.CLAUDE, "save"), input));
        assertThat(stdin.length).isGreaterThan(1_000_000);
        Timed timed = jar(stdin, "claude");
        assertThat(outcome(Client.CLAUDE, timed.run().ok())).isEqualTo(Outcome.STAMPED);
        assertThat(timed.run().json().path("hookSpecificOutput").path("updatedInput").path("body").stringValue()).isEqualTo(text);
        assertThat(timed.millis()).isLessThan(HOST_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("HK-020 the argument must be exactly claude, codex or antigravity: anything else exits 2 with usage on stderr and nothing on stdout")
    void argumentIsAnEnum() throws IOException {
        byte[] stdin = bytes(event(Client.CODEX, toolName(Client.CODEX, "save"), "{}"));
        List<String[]> invalid = List.of(
                new String[] {"Claude"}, new String[] {"gemini"}, new String[] {""}, new String[] {},
                new String[] {"claude", "codex"}, new String[] {"CODEX"}, new String[] {" codex"});
        for (String[] args : invalid) {
            Run run = jar(stdin, args).run();
            assertThat(run.exit()).as(String.join("|", args)).isEqualTo(2);
            assertThat(run.stdout()).as(String.join("|", args)).isEmpty();
            assertThat(run.err()).contains("usage").contains("claude|codex|antigravity");
            Run inProcess = HookHarness.run(args, stdin);
            assertThat(inProcess.exit()).isEqualTo(2);
            assertThat(inProcess.stdout()).isEmpty();
            assertThat(inProcess.err()).contains("usage");
        }
    }

    @Test
    @DisplayName("HK-021 java -jar with nothing but the jar (empty working directory, no CLASSPATH) runs on this JDK 25")
    void runsWithOnlyTheJar() throws IOException {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(25);
        for (Client client : Client.values()) {
            Run run = jar(bytes(event(client, toolName(client, "whoami"), "{}")), client.argument()).run().ok();
            assertThat(outcome(client, run)).isEqualTo(Outcome.STAMPED);
            assertThat(run.out()).contains(HookHarness.vendor(client) + ":" + HOST_ID);
        }
    }

    @Test
    @DisplayName("HK-021 on a JDK older than 25 the JVM refuses the jar with UnsupportedClassVersionError (needs LEDGER_HOOK_LEGACY_JAVA)")
    void olderJdkFailsClearly() throws IOException {
        String legacy = System.getenv("LEDGER_HOOK_LEGACY_JAVA");
        Assumptions.assumeTrue(legacy != null && !legacy.isBlank(), "NOT_RUN: no JDK older than 25 on this host (set LEDGER_HOOK_LEGACY_JAVA to its java executable)");
        Timed timed = java(Path.of(legacy), List.of(), Map.of(), bytes(event(Client.CODEX, toolName(Client.CODEX, "save"), "{}")), "codex");
        assertThat(timed.run().exit()).isNotZero();
        assertThat(timed.run().stdout()).isEmpty();
        assertThat(timed.run().err()).contains("UnsupportedClassVersionError").contains("69.0");
    }
}
