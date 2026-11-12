package org.pgledger.hook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.pgledger.hook.keys.KeyInfo;
import org.pgledger.hook.keys.SessionKeyException;
import org.pgledger.hook.keys.SessionKeyStore;

/**
 * One hook invocation. Two forms:
 * <ul>
 *   <li>{@code <client>} on the command line, host JSON on {@code in}, host JSON on {@code out}: the attestation of a
 *       ledger write call (the author's session key signs it);</li>
 *   <li>{@code session-key <server> <author>}: loads or creates the session key of the author for the MCP server (as the
 *       host names it in the tool name, for example {@code ledger} for {@code mcp__ledger__ledger_save}) and prints the
 *       server, the author, the public key and the fingerprint (four lines on {@code out}) for the owner, who registers
 *       the key on that server. A key is kept per server: the owner registers each server's key separately.</li>
 * </ul>
 * Streams, the clock and the key store are passed in, so the only place that touches the process is {@link Main}.
 * Every outcome, a failed read of stdin included, is a {@link Response}; the one complete {@code switch} in
 * {@link #render} turns it into the output and the exit code.
 */
public final class HookCli {

    /** The first argument of the key command. */
    static final String SESSION_KEY_COMMAND = "session-key";

    /**
     * The hook logs with {@code java.util.logging}, the logger of the JDK, because the hook jar carries no Spring Boot, no
     * SLF4J and no Logback (HK-018: the jar holds the JDK-only attestation classes and Jackson, nothing else). Every
     * record is at FINE or SEVERE; the host contract allows no extra line on stderr, so nothing is printed for FINE.
     * The other classes of the hook use the same logger.
     */
    private static final Logger LOG = Logger.getLogger(HookCli.class.getName());
    private static final int EXIT_OK = 0;
    private static final int EXIT_BLOCK_OR_USAGE = 2;
    private static final String USAGE = "usage: java -jar ledger-client-hook.jar <" + Client.choices() + "> | "
            + SESSION_KEY_COMMAND + " <server> <author>";
    private static final String SERVER_INVALID = "ledger: the server must be the MCP server name of the host "
            + "(1 to 100 characters of A-Z a-z 0-9 . _ -)";
    private static final String AUTHOR_INVALID = "ledger: the author must be <vendor>:<session id> "
            + "(vendor a-z 0-9 -, starting with a letter; session id 1 to 128 characters of A-Z a-z 0-9 . _ -)";

    private final JsonCodec codec;
    private final Clock clock;
    private final SessionKeyStore keys;

    public HookCli(JsonCodec codec, Clock clock, SessionKeyStore keys) {
        this.codec = codec;
        this.clock = clock;
        this.keys = keys;
    }

    /**
     * Runs the hook and returns the process exit code: 0 after a JSON answer or silence (or the three lines of
     * {@code session-key}), 2 for a usage error, an I/O failure, an unusable key, or when the host's contract asks for
     * a blocking failure (Claude Code with malformed input).
     */
    public int run(String[] args, InputStream in, OutputStream out, OutputStream err) {
        Response response = answer(args, in);
        try {
            return render(response, out, err);
        } catch (IOException e) {
            return ioFailure(e);
        }
    }

    private Response answer(String[] args, InputStream in) {
        if (args.length == 3 && SESSION_KEY_COMMAND.equals(args[0])) {
            return sessionKey(args[1], args[2]);
        }
        return Client.fromArgument(args.length == 1 ? args[0] : null)
                .map(client -> handle(client, in))
                .orElseGet(() -> new Response.Failure(USAGE));
    }

    /** The attestation of the host event on {@code in} for the client that the command line named. */
    private Response handle(Client client, InputStream in) {
        LedgerHook hook = new LedgerHook(client.dialect(codec), client.vendor(), codec, clock, keys);
        try {
            return hook.handle(in.readAllBytes());
        } catch (IOException e) {
            return new Response.InputFailure(e);
        }
    }

    /** The public half of the key of the author for the server: four lines, nothing of the private key. */
    private Response sessionKey(String server, String authorText) {
        if (!McpToolName.isServerName(server)) {
            return new Response.Failure(SERVER_INVALID);
        }
        return Author.parse(authorText).map(author -> publicKeyOf(server, author))
                .orElseGet(() -> new Response.Failure(AUTHOR_INVALID));
    }

    private Response publicKeyOf(String server, Author author) {
        try {
            KeyInfo info = keys.load(server, author.value()).info();
            return new Response.Lines(List.of("server: " + info.server(), "author: " + info.author(),
                    "public-key: " + info.publicKey(), "fingerprint: " + info.fingerprint()));
        } catch (SessionKeyException unusable) {
            LOG.log(Level.FINE, "the session key of the author cannot be used", unusable);
            return new Response.Failure(Refusal.SESSION_KEY_UNAVAILABLE.message() + " [" + unusable.reason() + "]");
        }
    }

    private int render(Response response, OutputStream out, OutputStream err) throws IOException {
        return switch (response) {
            case Response.Silent silent -> EXIT_OK;
            case Response.Document document -> {
                out.write(codec.write(document.body()));
                out.write('\n');
                out.flush();
                yield EXIT_OK;
            }
            case Response.Lines lines -> {
                for (String line : lines.lines()) {
                    writeLine(out, line);
                }
                yield EXIT_OK;
            }
            case Response.Failure failure -> {
                writeLine(err, failure.message());
                yield EXIT_BLOCK_OR_USAGE;
            }
            case Response.InputFailure failure -> ioFailure(failure.cause());
        };
    }

    /** The one log line and exit code of an I/O failure (reading stdin or writing the answer). */
    private static int ioFailure(IOException cause) {
        LOG.log(Level.SEVERE, "ledger: hook I/O failure", cause);
        return EXIT_BLOCK_OR_USAGE;
    }

    private static void writeLine(OutputStream stream, String text) throws IOException {
        stream.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }
}
