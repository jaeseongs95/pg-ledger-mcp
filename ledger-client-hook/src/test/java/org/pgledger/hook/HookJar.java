package org.pgledger.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The built jar started with {@code java -jar} of the JDK that runs the tests, from an empty working directory with
 * the class path variables removed: only the jar is available. Files of one fixture live in its own directory.
 */
final class HookJar {

    static final Path JAR = Path.of(System.getProperty("ledger.hook.jar"));
    static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /** One start: what the process wrote and how long it took from start to exit. */
    record Timed(Run run, long millis) {}

    private final Path work;

    HookJar(Path work) {
        this.work = work;
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

    /** The plain start with this JDK and the default environment. */
    Timed start(byte[] stdin, String... args) throws IOException {
        return start(JAVA, List.of(), Map.of(), stdin, args);
    }

    Run run(byte[] stdin, String... args) throws IOException {
        return start(stdin, args).run();
    }
}
