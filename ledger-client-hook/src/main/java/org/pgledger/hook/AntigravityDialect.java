package org.pgledger.hook;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Antigravity PreToolUse. The host conversation id is {@code conversationId}. MCP calls arrive either directly
 * (tool name {@code mcp:...}, arguments in {@code toolCall.args}) or wrapped in {@code call_mcp_tool}, whose
 * {@code args} carry {@code ServerName}, {@code ToolName} and {@code Arguments} (an object or a JSON object text).
 * {@code overwrite} is merged into the host's tool args, so a direct call overwrites only {@code _hostAttestation}
 * and a wrapped call overwrites only {@code Arguments}, in its original form. Only {@code allow} is used: no
 * auto-approval, the host's permission policy stays in force. The host ignores a failing hook process, so
 * malformed input is denied explicitly with exit 0.
 */
record AntigravityDialect(JsonCodec codec) implements HostDialect {

    private static final String WRAPPER_TOOL = "call_mcp_tool";
    private static final String DIRECT_PREFIX = "mcp:";
    /** The ledger tool name ends the host tool name after a start, ':', '/' or '_'. */
    private static final Pattern LEDGER_WRITE_TOOL = Pattern.compile("(?:^|[:/_])ledger_" + WriteTool.alternation() + "$");

    @Override
    public Vendor vendor() {
        return Vendor.GEMINI;
    }

    @Override
    public Intake read(JsonNode event) {
        JsonNode call = event.path("toolCall");
        JsonNode name = call.path("name");
        JsonNode args = call.path("args");
        JsonNode hostId = event.path("conversationId");
        if (!name.isString()) {
            return new Intake.Skip();
        }
        String toolName = name.stringValue();
        if (WRAPPER_TOOL.equals(toolName)) {
            return readWrapped(args, hostId);
        }
        if (toolName.startsWith(DIRECT_PREFIX) && LEDGER_WRITE_TOOL.matcher(toolName).find()) {
            return new Intake.Attest(args, hostId, Intake.ArgumentForm.DIRECT);
        }
        return new Intake.Skip();
    }

    private Intake readWrapped(JsonNode envelope, JsonNode hostId) {
        JsonNode server = envelope.path("ServerName");
        JsonNode tool = envelope.path("ToolName");
        if (!server.isString() || server.stringValue().isEmpty() || !tool.isString()
                || !LEDGER_WRITE_TOOL.matcher(tool.stringValue()).find()) {
            return new Intake.Skip();
        }
        if (Intake.carriesReservedKey(envelope)) {
            return new Intake.Refuse(Refusal.RESERVED_FIELD);
        }
        JsonNode arguments = envelope.path("Arguments");
        if (!arguments.isString()) {
            return new Intake.Attest(arguments, hostId, Intake.ArgumentForm.EMBEDDED_OBJECT);
        }
        try {
            return new Intake.Attest(codec.read(arguments.stringValue().getBytes(StandardCharsets.UTF_8)), hostId,
                    Intake.ArgumentForm.EMBEDDED_TEXT);
        } catch (JacksonException notJson) {
            return new Intake.Refuse(Refusal.ARGUMENTS_NOT_JSON_OBJECT);
        }
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
        ObjectNode root = codec.newObject();
        root.put("decision", "deny");
        root.put("reason", reason.message());
        return new Response.Document(root);
    }

    @Override
    public Response allow(Intake.Attest call, Verdict.Stamped stamped) {
        ObjectNode overwrite = codec.newObject();
        switch (call.form()) {
            case DIRECT -> overwrite.put(Intake.RESERVED_KEY, stamped.attestation());
            case EMBEDDED_OBJECT -> overwrite.set("Arguments", stamped.arguments());
            case EMBEDDED_TEXT -> overwrite.put("Arguments", codec.writeText(stamped.arguments()));
        }
        ObjectNode root = codec.newObject();
        root.put("decision", "allow");
        root.set("overwrite", overwrite);
        return new Response.Document(root);
    }
}
