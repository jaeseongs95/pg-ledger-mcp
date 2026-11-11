package org.pgledger.hook;

import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Antigravity: {@code toolCall.name}/{@code toolCall.args}, {@code conversationId}; an {@code overwrite} that is
 * merged into the tool args of the host. A direct call overwrites only {@code _hostAttestation}; a
 * {@code call_mcp_tool} call overwrites only {@code Arguments}.
 */
final class AntigravityHost implements HostScenario {

    static final AntigravityHost INSTANCE = new AntigravityHost();

    record Call(String name, Object args) {}

    /** The host event; a null {@code conversationId} is left out, the sub-agent id is there to be ignored. */
    record Event(Call toolCall, JsonNode conversationId, String agent_id) {}

    /** The {@code args} of a {@code call_mcp_tool} call; null components are left out (a missing field). */
    record Envelope(Object ServerName, Object ToolName, Object Arguments, Object extra) {}

    record Answer(String decision, String reason, Object overwrite) {}

    private AntigravityHost() {}

    @Override
    public Client client() {
        return Client.ANTIGRAVITY;
    }

    @Override
    public String attestation(String hostId) {
        return "gemini:" + hostId;
    }

    @Override
    public String toolName(String operation) {
        return "mcp:test:ledger_" + operation;
    }

    @Override
    public byte[] event(String toolName, Object arguments, JsonNode hostId) {
        return Json.bytes(new Event(new Call(toolName, arguments), hostId, "child-9999"));
    }

    /** A {@code call_mcp_tool} event whose {@code args} are the given envelope. */
    byte[] wrapperEvent(Object envelope) {
        return event("call_mcp_tool", envelope);
    }

    @Override
    public byte[] echoEvent(String operation, Object arguments) {
        return wrapperEvent(new Envelope("test", "ledger_" + operation, arguments, null));
    }

    @Override
    public JsonNode echoed(Run run) {
        return run.json().at("/overwrite/Arguments");
    }

    @Override
    public Optional<JsonNode> allowed(JsonNode sent, String attestation) {
        return Optional.of(Json.tree(new Answer("allow", null, Map.of(Json.RESERVED_KEY, attestation))));
    }

    /** The answer to a wrapped call whose {@code Arguments} are the given value (an object, or its JSON text). */
    Optional<JsonNode> allowedWrapped(Object arguments) {
        return Optional.of(Json.tree(new Answer("allow", null, Map.of("Arguments", arguments))));
    }

    @Override
    public Optional<JsonNode> denied(Refusal reason) {
        return Optional.of(Json.tree(new Answer("deny", reason.message(), null)));
    }

    @Override
    public Optional<JsonNode> untouched() {
        return Optional.empty();
    }

    @Override
    public Malformed malformedInput() {
        return new Malformed(0, denied(Refusal.MALFORMED_INPUT), "");
    }

    @Override
    public String toString() {
        return client().argument();
    }
}
