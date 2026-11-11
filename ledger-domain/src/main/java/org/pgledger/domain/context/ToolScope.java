package org.pgledger.domain.context;

/**
 * The wire name of the tool that the current request called (for example {@code ledger_save}), carried by a
 * {@link ScopedValue} like {@link ActorScope}: the tool binding of the MCP server binds it around the call of the tool
 * method, and the write gate compares it with the tool the author attestation was signed for. Outside a tool call it is
 * unbound, which the gate treats as a mismatch.
 */
public final class ToolScope {

    public static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

    private ToolScope() {
    }

    /** The name of the tool being called, or {@code null} outside a tool call. */
    public static String current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }
}
