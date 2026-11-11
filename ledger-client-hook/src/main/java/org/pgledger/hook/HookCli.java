package org.pgledger.hook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * One hook invocation: {@code <client>} on the command line, host JSON on {@code in}, host JSON on {@code out}.
 * Streams are passed in, so the only place that touches the process streams is {@link Main}.
 */
public final class HookCli {

    private static final int EXIT_OK = 0;
    private static final int EXIT_BLOCK_OR_USAGE = 2;

    private final JsonCodec codec;

    public HookCli(JsonCodec codec) {
        this.codec = codec;
    }

    /**
     * Runs the hook and returns the process exit code: 0 after a JSON answer or silence, 2 for a usage error or
     * when the host's contract asks for a blocking failure (Claude Code with malformed input).
     */
    public int run(String[] args, InputStream in, OutputStream out, OutputStream err) throws IOException {
        Optional<Client> client = args.length == 1 ? Client.fromArgument(args[0]) : Optional.empty();
        if (client.isEmpty()) {
            writeLine(err, "usage: java -jar ledger-client-hook.jar <" + Client.choices() + ">");
            return EXIT_BLOCK_OR_USAGE;
        }
        LedgerHook hook = new LedgerHook(client.get().dialect(codec), codec);
        return switch (hook.handle(in.readAllBytes())) {
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
        };
    }

    private static void writeLine(OutputStream stream, String text) throws IOException {
        stream.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }
}
