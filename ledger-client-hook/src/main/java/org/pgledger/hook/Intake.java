package org.pgledger.hook;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** What a dialect found in a host event. */
sealed interface Intake {

    /** Where the host carries the tool arguments inside its event. */
    enum ArgumentForm {
        /** The event's arguments field is the tool input itself. */
        DIRECT,
        /** A wrapper object carries the tool input as a JSON object. */
        EMBEDDED_OBJECT,
        /** A wrapper object carries the tool input as JSON text inside a string. */
        EMBEDDED_TEXT
    }

    /** Not a ledger write call; the hook stays out of it. */
    record Skip() implements Intake {}

    /** A ledger write call that is refused before the attestation rules apply. */
    record Refuse(Refusal reason) implements Intake {}

    /** A ledger write call: the model's arguments and the host's id, both as found and not yet validated. */
    record Attest(JsonNode arguments, JsonNode hostId, ArgumentForm form) implements Intake {}

    /**
     * Intake of the events shared by Claude and Codex: {@code tool_name}, {@code tool_input}, {@code session_id}.
     */
    static Intake ofFlatEvent(JsonNode event, Pattern ledgerWriteTool) {
        JsonNode name = event.path("tool_name");
        if (!name.isString() || !ledgerWriteTool.matcher(name.stringValue()).find()) {
            return new Skip();
        }
        return new Attest(event.path("tool_input"), event.path("session_id"), ArgumentForm.DIRECT);
    }

    /** The reserved key name is the same for every host. */
    String RESERVED_KEY = "_hostAttestation";

    /** True if the model (or a wrapper) supplied the reserved key, whatever its value. */
    static boolean carriesReservedKey(JsonNode node) {
        return node instanceof ObjectNode object && object.has(RESERVED_KEY);
    }
}
