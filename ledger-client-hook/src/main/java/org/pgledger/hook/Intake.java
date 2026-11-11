package org.pgledger.hook;

import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

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

    /**
     * A ledger write call: the model's arguments and the host id, both as found and not yet validated.
     *
     * @param arguments arbitrary JSON of the model, kept as a Jackson tree so that it is given back unchanged except
     *                  for the reserved key; {@code null} if the host event has none
     * @param hostId    the host session or conversation id; {@code null} if absent or not a JSON string
     * @param tool      the write tool the host tool name stands for, whatever naming the host used; the attestation
     *                  is issued for its canonical name {@code ledger_<operation>}
     */
    record Attest(JsonNode arguments, String hostId, ArgumentForm form, WriteTool tool) implements Intake {}

    /**
     * Intake of the events shared by Claude and Codex: {@code tool_name}, {@code tool_input}, {@code session_id}.
     *
     * @param ledgerWriteTool the dialect's tool name pattern, built with {@link WriteTool#alternation()}
     * @param event the bound event, or {@code null} if the JSON was not an object
     */
    static Intake ofFlatEvent(FlatEvent event, Pattern ledgerWriteTool) {
        if (event == null || event.toolName() == null) {
            return new Skip();
        }
        Optional<WriteTool> tool = WriteTool.find(ledgerWriteTool, event.toolName());
        if (tool.isEmpty()) {
            return new Skip();
        }
        return new Attest(event.toolInput(), event.sessionId(), ArgumentForm.DIRECT, tool.get());
    }

    /** The reserved key name is the same for every host. */
    String RESERVED_KEY = "_hostAttestation";
}
