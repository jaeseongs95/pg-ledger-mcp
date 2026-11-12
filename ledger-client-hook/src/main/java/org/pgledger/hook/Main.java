package org.pgledger.hook;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.pgledger.hook.keys.KeyFolder;
import org.pgledger.hook.keys.SessionKeyStore;

/**
 * Entry point of {@code java -jar ledger-client-hook.jar <claude|codex|antigravity>} (the host hook) and
 * {@code java -jar ledger-client-hook.jar session-key <server> <author>} (the public key of an author's session key for
 * an MCP server): the only class that is connected to stdin, stdout, stderr, the exit code, the environment and the
 * system properties.
 *
 * <p>The folder of the session keys is the value of the environment variable {@code LEDGER_HOOK_KEY_DIR} if it is
 * set and not blank (it must be an absolute path), otherwise {@code <user.home>/.pg-ledger-mcp/session-keys}
 * ({@link KeyFolder#resolve}). Main reads the two values and the property {@code user.name} (the account that must own
 * the key folder), and the key store in the {@code keys} package is the only code that uses the folder.
 *
 * <p>The objects are assembled by hand here, on purpose: the hook jar uses no Spring (no container, no component scan; it
 * depends on the JDK-only {@code ledger-attestation} module and Jackson, HK-018) and a hook is one short process per
 * call. The pieces that differ between hosts are the {@link HostDialect} implementations, chosen by {@link Client}.
 *
 * <p>The classes are Java 25 class files. An older JVM refuses the jar by itself with
 * {@code UnsupportedClassVersionError} (class file version 69.0 = Java 25), before any of this code runs.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        KeyFolder folder = KeyFolder.resolve(System.getenv(KeyFolder.ENVIRONMENT_VARIABLE), System.getProperty("user.home"));
        // the account that owns the key folder and the key files: the file system's owner is compared with it
        HookCli cli = new HookCli(JsonCodec.create(), Clock.systemUTC(),
                new SessionKeyStore(folder, System.getProperty("user.name")));
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
