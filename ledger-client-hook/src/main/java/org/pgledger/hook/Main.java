package org.pgledger.hook;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.pgledger.hook.keys.SessionKeyStore;

/**
 * Entry point of {@code java -jar ledger-client-hook.jar <claude|codex|antigravity>} (the host hook) and
 * {@code java -jar ledger-client-hook.jar session-key <author>} (the public key of an author's session key): the only
 * class that is connected to stdin, stdout, stderr, the exit code, the environment and the system properties.
 *
 * <p>The folder of the session keys is the value of the environment variable {@code LEDGER_HOOK_KEY_DIR} if it is
 * set and not blank, otherwise {@code <user.home>/.pg-ledger-mcp/session-keys} ({@link KeyFolder#resolve}). Main reads
 * the two values, and the key store in the {@code keys} package is the only code that uses the folder.
 *
 * <p>The classes are Java 25 class files. An older JVM refuses the jar by itself with
 * {@code UnsupportedClassVersionError} (class file version 69.0 = Java 25), before any of this code runs.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        KeyFolder folder = KeyFolder.resolve(System.getenv(KeyFolder.ENVIRONMENT_VARIABLE), System.getProperty("user.home"));
        HookCli cli = new HookCli(JsonCodec.create(), Clock.systemUTC(), SessionKeyStore.at(folder.first(), folder.more()));
        int status = cli.run(args, System.in, System.out, System.err);
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
