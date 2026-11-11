package org.pgledger.hook;

import tools.jackson.core.JacksonException;

/**
 * Claude Code PreToolUse (https://code.claude.com/docs/en/hooks#pretooluse). The tool name is
 * {@code mcp__<server>__<tool>}; only the shapes of {@link McpToolName} are signed, for the server in the name. The host conversation id is
 * {@code session_id}; {@code agent_id} is intentionally not used. A stamped call answers with {@code updatedInput}
 * and no permission decision, so the host's own permission policy still decides; exit 2 blocks the call.
 */
record ClaudeDialect(JsonCodec codec) implements HostDialect {

    private static final String EVENT_NAME = "PreToolUse";

    @Override
    public Vendor vendor() {
        return Vendor.CLAUDE;
    }

    @Override
    public Intake read(byte[] stdin) throws JacksonException {
        return Intake.ofFlatEvent(codec.readEvent(stdin, FlatEvent.class), McpToolName::ofDoubleUnderscore);
    }

    @Override
    public Response malformedInput() {
        return new Response.Failure(Refusal.MALFORMED_INPUT.message());
    }

    @Override
    public Response notLedgerCall() {
        return new Response.Document(new HostDocument.Empty());
    }

    @Override
    public Response deny(Refusal reason) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, "deny", reason.message(), null));
    }

    @Override
    public Response allow(Intake.Attest call, Verdict.Stamped stamped) {
        return document(new HostDocument.PreToolUse.Specific(EVENT_NAME, null, null, stamped.arguments()));
    }

    private static Response document(HostDocument.PreToolUse.Specific specific) {
        return new Response.Document(new HostDocument.PreToolUse(specific));
    }
}
