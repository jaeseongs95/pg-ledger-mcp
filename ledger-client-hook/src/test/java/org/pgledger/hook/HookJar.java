package org.pgledger.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/**
 * The built jar started with {@code java -jar} of the JDK that runs the tests, from an empty working directory with
 * the class path variables removed: only the jar is available. Files of one fixture live in its own directory. The key
 * folder of the test is handed to the process through {@code LEDGER_HOOK_KEY_DIR}, so the real home folder is never
 * touched; the process uses the real system clock, so the issue time of a token is checked against a window around the
 * run ({@link #stamp}).
 */
final class HookJar {

    static final Path JAR = Path.of(System.getProperty("ledger.hook.jar"));
    static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /** The environment variable that names the key folder (the hook's contract). */
    static final String KEY_DIR_VARIABLE = "LEDGER_HOOK_KEY_DIR";

    /** One start: what the process wrote and how long it took from start to exit. */
    record Timed(Run run, long millis) {}

    private final Path work;
    private final Path keyDirectory;
    private final Clock clock = Clock.systemUTC();
    private long startedAt;
    private long finishedAt;

    HookJar(Path work, Path keyDirectory) {
        this.work = work;
        this.keyDirectory = keyDirectory;
    }

    Path keyDirectory() {
        return keyDirectory;
    }

    Timed start(Path javaExecutable, List<String> jvmArgs, Map<String, String> env, byte[] stdin, String... args) throws IOException {
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
        List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach(builder.environment()::remove);
        builder.environment().put(KEY_DIR_VARIABLE, keyDirectory.toString());
        builder.environment().putAll(env);
        long start = System.nanoTime();
        startedAt = clock.instant().getEpochSecond();
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
        finishedAt = clock.instant().getEpochSecond();
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Timed(new Run(process.exitValue(), Files.readAllBytes(out), Files.readAllBytes(err)), millis);
    }

    /** The plain start with this JDK and the default environment (plus the key folder). */
    Timed start(byte[] stdin, String... args) throws IOException {
        return start(JAVA, List.of(), Map.of(), stdin, args);
    }

    Run run(byte[] stdin, String... args) throws IOException {
        return start(stdin, args).run();
    }

    /** The working directories of the processes started so far (each one was empty when its process started). */
    List<Path> workingDirectories() throws IOException {
        try (var entries = Files.list(work)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("cwd")).toList();
        }
    }

    /**
     * Checks the attestation of the last start of a direct call with the standard host id: the token is issued between
     * the start and the end of that process, and verified with the public key in the key folder.
     */
    Attestations.Stamp stamp(HostScenario host, String op, JsonNode sent, Run run) {
        return Attestations.verify(keyDirectory, new Attestations.Window(startedAt, finishedAt + 1), host.server(),
                host.author(HookFixture.HOST_ID), op, sent, host.stampedPart(run));
    }
}
