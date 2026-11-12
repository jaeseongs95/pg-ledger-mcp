package org.pgledger.system;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A packaged jar of the product started as a separate Java process ({@code java -jar <jar> <arguments>}), with the Java of
 * the running test JVM.
 *
 * <p>Why a process and not the classes in this JVM: the subject of the system test is the packaged jar and its startup
 * wiring (the Spring Boot loader, the configuration import of the rendered service, the registered JDBC driver that is
 * loaded from a jar at run time, the schema guard, the application account). A context inside the test JVM has the test class
 * path, no loader, and the {@code embedded} profile; it cannot show any of that. The launcher is the Java binary only: no
 * shell, no script, no other program.
 *
 * <p>The streams of a process are files of the work directory (a pipe that nobody reads would stop a process that writes a
 * lot). The standard input is a pipe: a secret that a process has to read from its standard input ({@code --stdin}) is never
 * written to a file. A password goes to a process only through {@code environment} or {@code stdin}, never as an argument.
 */
final class JavaProcess {

    /** The limits of every child JVM of the system test (the resources of the test PC are shared). */
    private static final List<String> LIMITS = List.of("-Xmx512m", "-XX:ActiveProcessorCount=1", "-Duser.timezone=UTC",
            // the messages the tests read are the English ones, whatever the language of the machine is
            "-Duser.language=en", "-Duser.country=US", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8");

    /** What a finished process wrote and how it ended. */
    record Result(int exit, String out, String err) {

        /** The streams as one text for the message of a failed check (a product message never holds a secret). */
        String streams() {
            return "exit " + exit + "\nstdout:\n" + tail(out) + "\nstderr:\n" + tail(err);
        }
    }

    private JavaProcess() {
    }

    /** The Java binary that runs this test JVM. */
    static Path java() {
        return Path.of(ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("the Java binary of the test JVM is not known")));
    }

    /**
     * Runs {@code java -jar <jar> <arguments>} to its end (at most {@code seconds}) and returns what it wrote.
     *
     * @param work        a directory for the two stream files of this run
     * @param environment variables added to the environment of this process (never inherited secrets: see {@link #builder})
     * @param stdin       the text written to the standard input and then closed, or {@code null} for none
     */
    static Result run(Path work, Map<String, String> environment, String stdin, int seconds, Path jar,
            String... arguments) throws IOException {
        Path out = Files.createTempFile(work, "stdout", ".out");
        Path err = Files.createTempFile(work, "stderr", ".out");
        List<String> command = new ArrayList<>(List.of(java().toString()));
        command.addAll(LIMITS);
        command.addAll(List.of("-jar", jar.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = builder(command, environment, work).redirectOutput(out.toFile())
                .redirectError(err.toFile());
        Process process = builder.start();
        try (OutputStream input = process.getOutputStream()) {
            if (stdin != null) {
                input.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException closed) {
            // a process that ended without reading its input (a refused command line) is judged by its exit code
        }
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("the process of " + jar.getFileName() + " did not end in " + seconds + " seconds");
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + jar.getFileName(), interrupted);
        }
        return new Result(process.exitValue(), text(out), text(err));
    }

    /**
     * Starts {@code java <java arguments>} and leaves it running (the server). Its standard output and error are one file
     * of the work directory; the standard input is closed at once.
     */
    static Process start(Path work, Map<String, String> environment, Path log, List<String> javaArguments)
            throws IOException {
        List<String> command = new ArrayList<>(List.of(java().toString()));
        command.addAll(LIMITS);
        command.addAll(javaArguments);
        Process process = builder(command, environment, work).redirectErrorStream(true).redirectOutput(log.toFile())
                .start();
        process.getOutputStream().close();
        return process;
    }

    /** Ends a process: destroy, wait, and forcibly when it does not end. */
    static void stop(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            process.destroy();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(20, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The builder: the working directory, and an environment of the process that has no option variable of the JVM and no
     * setting of the product from the environment of the test (the settings of a run are the ones it passes).
     */
    private static ProcessBuilder builder(List<String> command, Map<String, String> environment, Path work) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile());
        builder.environment().keySet().removeIf(name -> {
            String upper = name.toUpperCase(java.util.Locale.ROOT);
            return upper.equals("CLASSPATH") || upper.equals("JAVA_TOOL_OPTIONS") || upper.equals("_JAVA_OPTIONS")
                    || upper.equals("JDK_JAVA_OPTIONS") || upper.startsWith("LEDGER_") || upper.startsWith("SPRING_");
        });
        builder.environment().putAll(environment);
        return builder;
    }

    /** The first and the last lines of a stream, for a message (the error is at the head, the cause chain at the end). */
    static String tail(String text) {
        List<String> lines = text.lines().toList();
        if (lines.size() <= 45) {
            return String.join("\n", lines);
        }
        return String.join("\n", lines.subList(0, 25)) + "\n... " + (lines.size() - 45) + " lines left out ...\n"
                + String.join("\n", lines.subList(lines.size() - 20, lines.size()));
    }

    /** A file the process wrote, as text; a byte that is not UTF-8 is replaced, never an exception (a message is read, not parsed). */
    static String text(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
