package org.pgledger.hook;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Antigravity PreToolUse. The host conversation id is {@code conversationId}. MCP calls arrive either directly
 * (tool name {@code mcp:<server><sep>...}, arguments in {@code toolCall.args}) or wrapped in {@code call_mcp_tool},
 * whose {@code args} carry {@code ServerName}, {@code ToolName} and {@code Arguments} (an object or a JSON object
 * text). The server is the audience of the signature: only the shapes below are signed, for the server in the name or
 * in {@code ServerName}.
 * {@code overwrite} is merged into the host's tool args, so a direct call overwrites only {@code _hostAttestation}
 * (and {@code request_id} when the hook had to generate one) and a wrapped call overwrites only {@code Arguments},
 * in its original form. Only {@code allow} is used: no
 * auto-approval, the host's permission policy stays in force. The host ignores a failing hook process, so
 * malformed input is denied explicitly with exit 0.
 *
 * <p>The tool names that are signed for:
 * <ul>
 * <li>Direct: {@code mcp:<server><sep>ledger_<op>} or {@code mcp:<server><sep><server>_ledger_<op>} with a separator of
 *     {@code :}, {@code /} or {@code _}; a name without a server ({@code mcp:ledger_save}) names no audience and is not
 *     signed. The prefix before {@code ledger_} must be exactly the server.
 * <li>{@code call_mcp_tool}: the {@code ServerName} of the envelope, and a {@code ToolName} of {@code ledger_<op>} or
 *     {@code <ServerName>_ledger_<op>}.
 * </ul>
 * The operation is one of the ten known ones, exactly.
 */
record AntigravityDialect(JsonCodec codec) implements HostDialect {

    // java.util.logging, like the rest of the hook: see HookCli for why.
    private static final Logger LOG = Logger.getLogger(AntigravityDialect.class.getName());
    private static final String WRAPPER_TOOL = "call_mcp_tool";
    private static final String DIRECT_PREFIX = "mcp:";
    /** Group 1 is the server, group 2 the operation; compiled once. */
    private static final Pattern DIRECT_TOOL_NAME = Pattern.compile("^mcp:([" + McpToolName.SERVER_CHARACTERS + "]{1,"
            + McpToolName.MAX_SERVER_LENGTH + "}?)[:/_](?:\\1_)?ledger_" + WriteTool.alternation() + "$");
    /**
     * Group 1 is the prefix, if the tool name has one, group 2 the operation; the prefix must be the {@code ServerName} of
     * the envelope, which {@link #envelopeName} compares. Compiled once, not per call.
     */
    private static final Pattern ENVELOPE_TOOL_NAME = Pattern.compile("^(?:([" + McpToolName.SERVER_CHARACTERS + "]{1,"
            + McpToolName.MAX_SERVER_LENGTH + "})_)?ledger_" + WriteTool.alternation() + "$");

    @Override
    public Intake read(byte[] stdin) throws JacksonException {
        AntigravityEvent event = codec.readEvent(stdin, AntigravityEvent.class);
        if (event == null || event.toolCall() == null || event.toolCall().name() == null) {
            return new Intake.Skip();
        }
        String toolName = event.toolCall().name();
        JsonNode args = event.toolCall().args();
        HostIdentity host = HostIdentity.of(event.conversationId());
        if (WRAPPER_TOOL.equals(toolName)) {
            return readWrapped(args, host);
        }
        if (toolName.startsWith(DIRECT_PREFIX)) {
            return directName(toolName)
                    .<Intake>map(called -> new Intake.Attest(args, host, Intake.ArgumentForm.DIRECT, called.tool(),
                            called.server()))
                    .orElseGet(Intake.Skip::new);
        }
        return new Intake.Skip();
    }

    /** A direct MCP call: {@code mcp:<server><sep>[<server>_]ledger_<op>}. */
    private static Optional<McpToolName> directName(String hostToolName) {
        Matcher matcher = DIRECT_TOOL_NAME.matcher(hostToolName);
        return matcher.matches() ? McpToolName.ofOperation(matcher.group(1), matcher.group(2)) : Optional.empty();
    }

    /** {@code call_mcp_tool}: the {@code ServerName} and the {@code ToolName} of the envelope. */
    private static Optional<McpToolName> envelopeName(String serverName, String toolName) {
        if (!McpToolName.isServerName(serverName) || toolName == null) {
            return Optional.empty();
        }
        Matcher matcher = ENVELOPE_TOOL_NAME.matcher(toolName);
        // no prefix, or exactly the ServerName: any other prefix is the name of another server's tool
        if (!matcher.matches() || (matcher.group(1) != null && !Objects.equals(serverName, matcher.group(1)))) {
            return Optional.empty();
        }
        return McpToolName.ofOperation(serverName, matcher.group(2));
    }

    private Intake readWrapped(JsonNode args, HostIdentity host) {
        return codec.bindObject(args, WrapperCall.class)
                .flatMap(wrapper -> envelopeName(wrapper.serverName(), wrapper.toolName())
                        .map(called -> attestEnvelope(wrapper, called, host)))
                .orElseGet(Intake.Skip::new);
    }

    private Intake attestEnvelope(WrapperCall wrapper, McpToolName called, HostIdentity host) {
        if (wrapper.hostAttestation() != null) {
            return new Intake.Refuse(Refusal.RESERVED_FIELD);
        }
        WriteTool tool = called.tool();
        String server = called.server();
        JsonNode arguments = wrapper.arguments();
        if (arguments == null || !arguments.isString()) {
            return new Intake.Attest(arguments, host, Intake.ArgumentForm.EMBEDDED_OBJECT, tool, server);
        }
        try {
            return new Intake.Attest(codec.read(arguments.stringValue().getBytes(StandardCharsets.UTF_8)), host,
                    Intake.ArgumentForm.EMBEDDED_TEXT, tool, server);
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
            case DIRECT -> new HostDocument.Overwrite.Attestation(stamped.attestation(), stamped.generatedRequestId());
            case EMBEDDED_OBJECT -> new HostDocument.Overwrite.ObjectArguments(stamped.arguments());
            case EMBEDDED_TEXT -> new HostDocument.Overwrite.TextArguments(codec.writeText(stamped.arguments()));
        };
        return new Response.Document(new HostDocument.Decision("allow", null, overwrite));
    }
}
