package org.pgledger.mcp.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The built client hook jar ({@code java -jar ledger-client-hook.jar ...}) started with the JDK that runs the tests, as a
 * host would start it. Its key folder is passed in through the environment variable of the hook, so a test never
 * touches the real home folder. The path of the jar comes from the build ({@code ledger.hook.jar}).
 */
public final class HookProcess {

    private static final Path JAR = Path.of(System.getProperty("ledger.hook.jar"));
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /** What one start wrote and how it ended. */
    public record Result(int exit, String out, String err) {
    }

    private HookProcess() {
    }

    /**
     * Starts the hook with the arguments, the text on its standard input and {@code keys} as its key folder.
     *
     * @param work an empty directory the process may use for its standard streams and as its working directory
     */
    public static Result run(Path work, Path keys, String stdin, String... arguments) throws IOException {
        Path in = Files.createTempFile(work, "stdin", ".json");
        Path out = Files.createTempFile(work, "stdout", ".out");
        Path err = Files.createTempFile(work, "stderr", ".out");
        Files.writeString(in, stdin, StandardCharsets.UTF_8);
        Path cwd = Files.createTempDirectory(work, "cwd");
        List<String> command = new java.util.ArrayList<>();
        command.add(JAVA.toString());
        command.add("-jar");
        command.add(JAR.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectInput(in.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")
                .forEach(builder.environment()::remove);
        builder.environment().put("LEDGER_HOOK_KEY_DIR", keys.toString());
        Process process = builder.start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("the hook jar did not finish in 60 seconds");
            }
        } catch (InterruptedException failure) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the hook", failure);
        }
        return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
    }
}
