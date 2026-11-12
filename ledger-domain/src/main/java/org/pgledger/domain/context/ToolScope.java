package org.pgledger.domain.context;

import java.util.Optional;

/**
 * What the current request called, carried by {@link ScopedValue}s like {@link ActorScope}: the tool binding of the MCP
 * server binds them around the call of the tool method, and the write gate compares them with what the author
 * attestation was signed for. Outside a tool call they are unbound, which the gate treats as a mismatch.
 *
 * <ul>
 * <li>{@link #CURRENT}: the wire name of the tool (for example {@code ledger_save}).
 * <li>{@link #ARGUMENTS_DIGEST}: the digest of the arguments of the call as the server received them (64 lower-case hex
 *     characters, computed by the tool binding with the class that the client hook signs with), so a token that was
 *     signed for other arguments, or for a batch with other operations, is refused.
 * </ul>
 */
public final class ToolScope {

    public static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

    public static final ScopedValue<String> ARGUMENTS_DIGEST = ScopedValue.newInstance();

    private ToolScope() {
    }

    /** The name of the tool being called; empty outside a tool call. */
    public static Optional<String> current() {
        return CURRENT.isBound() ? Optional.ofNullable(CURRENT.get()) : Optional.empty();
    }

    /**
     * The digest of the arguments of the call being made; empty outside a tool call and in a call that has none (a call without
     * attestation has nothing signed, so the binding of the tool binds no digest).
     */
    public static Optional<String> argumentsDigest() {
        return ARGUMENTS_DIGEST.isBound() ? Optional.ofNullable(ARGUMENTS_DIGEST.get()) : Optional.empty();
    }
}
