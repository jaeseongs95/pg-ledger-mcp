package org.pgledger.hook;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Codex PreToolUse. The host conversation id is {@code session_id} (Codex also gives sub-agents the parent's id).
 * A stamped call answers {@code allow} with {@code updatedInput}; a refusal answers {@code deny}. Malformed
 * input is denied explicitly with exit 0 (a failing hook process would not be a reliable block).
 */
record CodexDialect(JsonCodec codec) implements HostDialect {

    private static final Pattern LEDGER_WRITE_TOOL = Pattern.compile("^mcp__.+_ledger_" + WriteTool.alternation() + "$");
    private static final String EVENT_NAME = "PreToolUse";

    @Override
    public Vendor vendor() {
        return Vendor.CODEX;
    }

    @Override
    public Intake read(JsonNode event) {
        return Intake.ofFlatEvent(event, LEDGER_WRITE_TOOL);
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
        ObjectNode specific = specific("deny");
        specific.put("permissionDecisionReason", reason.message());
        return new Response.Document(wrap(specific));
    }

    @Override
    public Response allow(Intake.Attest call, Verdict.Stamped stamped) {
        ObjectNode specific = specific("allow");
        specific.set("updatedInput", stamped.arguments());
        return new Response.Document(wrap(specific));
    }

    private ObjectNode specific(String decision) {
        ObjectNode specific = codec.newObject();
        specific.put("hookEventName", EVENT_NAME);
        specific.put("permissionDecision", decision);
        return specific;
    }

    private ObjectNode wrap(ObjectNode specific) {
        ObjectNode root = codec.newObject();
        root.set("hookSpecificOutput", specific);
        return root;
    }
}
