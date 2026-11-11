package org.pgledger.hook;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Codex: an explicit allow with updatedInput, an explicit deny, silence for an unhandled call. */
final class CodexHost extends FlatHost {

    @Override
    public Client client() {
        return Client.CODEX;
    }

    @Override
    public String attestation(String hostId) {
        return "codex:" + hostId;
    }

    @Override
    public String toolName(String operation) {
        return "mcp__test__ledger_" + operation;
    }

    @Override
    public Optional<JsonNode> allowed(JsonNode sent, String attestation) {
        return answer(new Specific(EVENT_NAME, "allow", null, Json.stamped(sent, attestation)));
    }

    @Override
    public Optional<JsonNode> denied(Refusal reason) {
        return answer(new Specific(EVENT_NAME, "deny", reason.message(), null));
    }

    @Override
    public Optional<JsonNode> untouched() {
        return Optional.empty();
    }

    @Override
    public Malformed malformedInput() {
        return new Malformed(0, denied(Refusal.MALFORMED_INPUT), "");
    }
}
