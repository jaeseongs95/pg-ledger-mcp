package org.pgledger.hook;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Claude Code PreToolUse (https://code.claude.com/docs/en/hooks#pretooluse). The host conversation id is
 * {@code session_id}; {@code agent_id} is intentionally not used. A stamped call answers with {@code updatedInput}
 * and no permission decision, so the host's own permission policy still decides; exit 2 blocks the call.
 */
record ClaudeDialect(JsonCodec codec) implements HostDialect {

    private static final Pattern LEDGER_WRITE_TOOL = Pattern.compile("^mcp__.*ledger_" + WriteTool.alternation() + "$");
    private static final String EVENT_NAME = "PreToolUse";

    @Override
    public Vendor vendor() {
        return Vendor.CLAUDE;
    }

    @Override
    public Intake read(JsonNode event) {
        return Intake.ofFlatEvent(event, LEDGER_WRITE_TOOL);
    }

    @Override
    public Response malformedInput() {
        return new Response.Failure(Refusal.MALFORMED_INPUT.message());
    }

    @Override
    public Response notLedgerCall() {
        return new Response.Document(codec.newObject());
    }

    @Override
    public Response deny(Refusal reason) {
        ObjectNode specific = specific();
        specific.put("permissionDecision", "deny");
        specific.put("permissionDecisionReason", reason.message());
        return new Response.Document(wrap(specific));
    }

    @Override
    public Response allow(Intake.Attest call, Verdict.Stamped stamped) {
        ObjectNode specific = specific();
        specific.set("updatedInput", stamped.arguments());
        return new Response.Document(wrap(specific));
    }

    private ObjectNode specific() {
        ObjectNode specific = codec.newObject();
        specific.put("hookEventName", EVENT_NAME);
        return specific;
    }

    private ObjectNode wrap(ObjectNode specific) {
        ObjectNode root = codec.newObject();
        root.set("hookSpecificOutput", specific);
        return root;
    }
}
