package org.pgledger.hook;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

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

    private static final Logger LOG = Logger.getLogger(AntigravityDialect.class.getName());
    private static final String WRAPPER_TOOL = "call_mcp_tool";
    private static final String DIRECT_PREFIX = "mcp:";
    /** The ledger tool name ends the host tool name after a start, ':', '/' or '_'. */
    private static final Pattern LEDGER_WRITE_TOOL = Pattern.compile("(?:^|[:/_])ledger_" + WriteTool.alternation() + "$");

    @Override
    public Vendor vendor() {
        return Vendor.GEMINI;
    }

    @Override
    public Intake read(byte[] stdin) throws JacksonException {
        AntigravityEvent event = codec.readEvent(stdin, AntigravityEvent.class);
        if (event == null || event.toolCall() == null || event.toolCall().name() == null) {
            return new Intake.Skip();
        }
        String toolName = event.toolCall().name();
        JsonNode args = event.toolCall().args();
        if (WRAPPER_TOOL.equals(toolName)) {
            return readWrapped(args, event.conversationId());
        }
        if (toolName.startsWith(DIRECT_PREFIX) && LEDGER_WRITE_TOOL.matcher(toolName).find()) {
            return new Intake.Attest(args, event.conversationId(), Intake.ArgumentForm.DIRECT);
        }
        return new Intake.Skip();
    }

    private Intake readWrapped(JsonNode args, String hostId) {
        Optional<WrapperCall> bound = codec.bindObject(args, WrapperCall.class);
        if (bound.isEmpty()) {
            return new Intake.Skip();
        }
        WrapperCall wrapper = bound.get();
        if (wrapper.serverName() == null || wrapper.serverName().isEmpty() || wrapper.toolName() == null
                || !LEDGER_WRITE_TOOL.matcher(wrapper.toolName()).find()) {
            return new Intake.Skip();
        }
        if (wrapper.hostAttestation() != null) {
            return new Intake.Refuse(Refusal.RESERVED_FIELD);
        }
        JsonNode arguments = wrapper.arguments();
        if (arguments == null || !arguments.isString()) {
            return new Intake.Attest(arguments, hostId, Intake.ArgumentForm.EMBEDDED_OBJECT);
        }
        try {
            return new Intake.Attest(codec.read(arguments.stringValue().getBytes(StandardCharsets.UTF_8)), hostId,
                    Intake.ArgumentForm.EMBEDDED_TEXT);
        } catch (JacksonException notJson) {
            LOG.log(Level.FINE, "the Arguments text is not JSON", notJson);
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
        return new Response.Document(new HostDocument.Decision("deny", reason.message(), null));
    }

    @Override
    public Response allow(Intake.Attest call, Verdict.Stamped stamped) {
        HostDocument.Overwrite overwrite = switch (call.form()) {
            case DIRECT -> new HostDocument.Overwrite.Attestation(stamped.attestation());
            case EMBEDDED_OBJECT -> new HostDocument.Overwrite.ObjectArguments(stamped.arguments());
            case EMBEDDED_TEXT -> new HostDocument.Overwrite.TextArguments(codec.writeText(stamped.arguments()));
        };
        return new Response.Document(new HostDocument.Decision("allow", null, overwrite));
    }
}
