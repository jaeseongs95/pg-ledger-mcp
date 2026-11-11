package org.pgledger.hook;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** The host a hook invocation serves; the first command line argument of the jar. */
public enum Client {
    CLAUDE("claude"),
    CODEX("codex"),
    ANTIGRAVITY("antigravity");

    private final String argument;

    Client(String argument) {
        this.argument = argument;
    }

    /** The exact command line spelling, lower case. */
    public String argument() {
        return argument;
    }

    /** Parses the command line argument; only the exact spellings are accepted. */
    public static Optional<Client> fromArgument(String argument) {
        return Arrays.stream(values()).filter(client -> client.argument.equals(argument)).findFirst();
    }

    /** {@code claude|codex|antigravity}, for the usage message. */
    public static String choices() {
        return Arrays.stream(values()).map(Client::argument).collect(Collectors.joining("|"));
    }

    /** The input and output dialect of this host. */
    HostDialect dialect(JsonCodec codec) {
        return switch (this) {
            case CLAUDE -> new ClaudeDialect(codec);
            case CODEX -> new CodexDialect(codec);
            case ANTIGRAVITY -> new AntigravityDialect(codec);
        };
    }
}
