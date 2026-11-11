package org.pgledger.hook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One hook invocation: {@code <client>} on the command line, host JSON on {@code in}, host JSON on {@code out}.
 * Streams are passed in, so the only place that touches the process streams is {@link Main}. Every outcome,
 * a failed read of stdin included, is a {@link Response}; the one complete {@code switch} in {@link #render}
 * turns it into the output and the exit code.
 */
public final class HookCli {

    private static final Logger LOG = Logger.getLogger(HookCli.class.getName());
    private static final int EXIT_OK = 0;
    private static final int EXIT_BLOCK_OR_USAGE = 2;

    private final JsonCodec codec;

    public HookCli(JsonCodec codec) {
        this.codec = codec;
    }

    /**
     * Runs the hook and returns the process exit code: 0 after a JSON answer or silence, 2 for a usage error, an
     * I/O failure, or when the host's contract asks for a blocking failure (Claude Code with malformed input).
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
        Optional<Client> client = args.length == 1 ? Client.fromArgument(args[0]) : Optional.empty();
        if (client.isEmpty()) {
            return new Response.Failure("usage: java -jar ledger-client-hook.jar <" + Client.choices() + ">");
        }
        LedgerHook hook = new LedgerHook(client.get().dialect(codec), codec);
        try {
            return hook.handle(in.readAllBytes());
        } catch (IOException e) {
            return new Response.InputFailure(e);
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
