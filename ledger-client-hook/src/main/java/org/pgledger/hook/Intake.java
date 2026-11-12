package org.pgledger.hook;

import java.util.Optional;
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

    /** How a host spells the name of a tool: the one thing that the hosts of {@link #ofFlatEvent} do not share. */
    @FunctionalInterface
    interface ToolNameReader {

        /** The server and the write tool that the host's tool name stands for, or empty if it is not a ledger write tool. */
        Optional<McpToolName> read(String hostToolName);
    }

    /** Not a ledger write call; the hook stays out of it. */
    record Skip() implements Intake {}

    /** A ledger write call that is refused before the attestation rules apply. */
    record Refuse(Refusal reason) implements Intake {}

    /**
     * A ledger write call: the model's arguments and the host id, both as found and not yet judged.
     *
     * @param arguments arbitrary JSON of the model, kept as a Jackson tree so that it is given back unchanged except
     *                  for the reserved key; {@code null} if the host event has none
     * @param host      the host session or conversation id: a {@link HostId}, or {@link HostIdentity.Invalid} if the
     *                  event had none of the allowed form
     * @param tool      the write tool the host tool name stands for; the attestation is issued for its canonical name
     *                  {@code ledger_<operation>}
     * @param server    the MCP server the host tool name stands for: the audience of the attestation, whose key is
     *                  kept per server (see {@link McpToolName})
     */
    record Attest(JsonNode arguments, HostIdentity host, ArgumentForm form, WriteTool tool, String server)
            implements Intake {}

    /**
     * Intake of the events shared by Claude and Codex: {@code tool_name}, {@code tool_input}, {@code session_id}.
     *
     * @param event     the bound event, or {@code null} if the JSON was not an object
     * @param toolNames how the dialect reads a host tool name
     */
    static Intake ofFlatEvent(FlatEvent event, ToolNameReader toolNames) {
        if (event == null || event.toolName() == null) {
            return new Skip();
        }
        return toolNames.read(event.toolName())
                .<Intake>map(called -> new Attest(event.toolInput(), HostIdentity.of(event.sessionId()),
                        ArgumentForm.DIRECT, called.tool(), called.server()))
                .orElseGet(Skip::new);
    }

    /** The reserved key name is the same for every host. */
    String RESERVED_KEY = "_hostAttestation";
}
