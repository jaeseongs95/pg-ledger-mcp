package org.pgledger.hook;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point of {@code java -jar ledger-client-hook.jar <claude|codex|antigravity>}: the only class that is
 * connected to stdin, stdout, stderr and the exit code.
 *
 * <p>The classes are Java 25 class files. An older JVM refuses the jar by itself with
 * {@code UnsupportedClassVersionError} (class file version 69.0 = Java 25), before any of this code runs.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        int status;
        try {
            status = new HookCli(JsonCodec.create()).run(args, System.in, System.out, System.err);
        } catch (IOException e) {
            byte[] message = ("ledger: hook I/O failure: " + e.getClass().getSimpleName() + "\n").getBytes(StandardCharsets.UTF_8);
            System.err.write(message, 0, message.length);
            status = 2;
        }
        PrintStream stdout = System.out;
        stdout.flush();
        if (stdout.checkError() && status == 0) {
            byte[] message = "ledger: could not write the hook output\n".getBytes(StandardCharsets.UTF_8);
            System.err.write(message, 0, message.length);
            status = 2;
        }
        System.exit(status);
    }
}
