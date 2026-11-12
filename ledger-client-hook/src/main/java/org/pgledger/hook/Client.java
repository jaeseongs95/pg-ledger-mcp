package org.pgledger.hook;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The host a hook invocation serves; the first command line argument of the jar. Each client carries the one vendor
 * that its authors are signed with ({@code claude:...}, {@code codex:...}, {@code gemini:...}: the Antigravity host is
 * Google's, hence {@link Vendor#GEMINI}), so the pairing of client and vendor is stated here and nowhere else.
 */
public enum Client {
    CLAUDE("claude", Vendor.CLAUDE),
    CODEX("codex", Vendor.CODEX),
    ANTIGRAVITY("antigravity", Vendor.GEMINI);

    private final String argument;
    private final Vendor vendor;

    Client(String argument, Vendor vendor) {
        this.argument = argument;
        this.vendor = vendor;
    }

    /** The exact command line spelling, lower case. */
    public String argument() {
        return argument;
    }

    /** The vendor of the authors that this client's attestations are signed for. */
    Vendor vendor() {
        return vendor;
    }

    /** Parses the command line argument; only the exact spellings are accepted. */
    public static Optional<Client> fromArgument(String argument) {
        return Arrays.stream(values()).filter(client -> Objects.equals(client.argument, argument)).findFirst();
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
