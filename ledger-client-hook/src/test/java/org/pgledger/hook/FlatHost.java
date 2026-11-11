package org.pgledger.hook;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Claude Code and Codex: the same event ({@code tool_name}, {@code tool_input}, {@code session_id}) and answer frame. */
abstract class FlatHost implements HostScenario {

    /** The host event; a null {@code session_id} is left out, the sub-agent id is there to be ignored. */
    record Event(JsonNode session_id, String agent_id, String tool_name, Object tool_input) {}

    /** The {@code hookSpecificOutput} of an answer; null components are not written. */
    record Specific(String hookEventName, String permissionDecision, String permissionDecisionReason, JsonNode updatedInput) {}

    record Answer(Specific hookSpecificOutput) {}

    static final String EVENT_NAME = "PreToolUse";

    @Override
    public byte[] event(String toolName, Object arguments, JsonNode hostId) {
        return Json.bytes(new Event(hostId, "child-ignored", toolName, arguments));
    }

    @Override
    public byte[] echoEvent(String operation, Object arguments) {
        return event(toolName(operation), arguments);
    }

    @Override
    public JsonNode echoed(Run run) {
        return run.json().at("/hookSpecificOutput/updatedInput");
    }

    protected static Optional<JsonNode> answer(Specific specific) {
        return Optional.of(Json.tree(new Answer(specific)));
    }

    @Override
    public String toString() {
        return client().argument();
    }
}
