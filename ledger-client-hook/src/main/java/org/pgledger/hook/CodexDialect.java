package org.pgledger.hook;

import tools.jackson.core.JacksonException;

/**
 * Codex PreToolUse. The tool name is {@code mcp__<server>__<tool>}; only the shapes of {@link McpToolName} are signed,
 * for the server in the name (or, for the Codex Apps namespace, the app). The host conversation id is {@code session_id} (Codex also gives sub-agents the parent id).
 * A stamped call answers {@code allow} with {@code updatedInput}; a refusal answers {@code deny}. Malformed
 * input is denied explicitly with exit 0 (a failing hook process would not be a reliable block).
 */
record CodexDialect(JsonCodec codec) implements HostDialect {

    private static final String EVENT_NAME = "PreToolUse";

    @Override
    public Vendor vendor() {
        return Vendor.CODEX;
    }

    @Override
    public Intake read(byte[] stdin) throws JacksonException {
        return Intake.ofFlatEvent(codec.readEvent(stdin, FlatEvent.class), McpToolName::ofCodex);
    }

    @Override
    public Response malformedInput() {
        return deny(Refusal.MALFORMED_INPUT);
    }

    @Override
    public Response notLedgerCall() {
        return new Response.Silent();
    }

    @Override
    public Response deny(Refusal reason) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, "deny", reason.message(), null));
    }

    @Override
    public Response allow(Intake.Attest call, Verdict.Stamped stamped) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, "allow", null, stamped.arguments()));
    }

    private static Response document(HostDocument.PreToolUse.Specific specific) {
        return new Response.Document(new HostDocument.PreToolUse(specific));
    }
}
