package org.pgledger.hook;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Claude Code: updatedInput without any permission decision, an empty object for an unhandled call, exit 2 on malformed input. */
final class ClaudeHost extends FlatHost {

    @Override
    public Client client() {
        return Client.CLAUDE;
    }

    @Override
    public String author(String hostId) {
        return "claude:" + hostId;
    }

    @Override
    public String toolName(String operation) {
        return "mcp__ledger__ledger_" + operation;
    }

    @Override
    public String server() {
        return "ledger";
    }

    @Override
    public Optional<JsonNode> allowed(JsonNode sent, String token, String addedRequestId) {
        return answer(new Specific(EVENT_NAME, null, null, Json.stamped(sent, token, addedRequestId)));
    }

    @Override
    public Optional<JsonNode> denied(Refusal reason) {
        return answer(new Specific(EVENT_NAME, "deny", reason.message(), null));
    }

    @Override
    public Optional<JsonNode> untouched() {
        return Optional.of(Json.of("{}"));
    }

    @Override
    public Malformed malformedInput() {
        return new Malformed(2, Optional.empty(), Refusal.MALFORMED_INPUT.message() + "\n");
    }
}
