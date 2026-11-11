package org.pgledger.hook;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * One host as the tests see it: how it names the ledger tools, how its event is written, and what a correct answer
 * looks like. The expected answers are the host contract written out as records; a test only states the case and
 * asks the scenario.
 */
interface HostScenario {

    /** What a host that gets malformed input must see: exit code, stdout document, and the exact stderr text. */
    record Malformed(int exit, Optional<JsonNode> stdout, String stderr) {}

    Client client();

    /** The author of the host id: {@code <vendor>:<host id>}, spelled out as the contract has it. */
    String author(String hostId);

    /** The host's name for {@code ledger_<operation>}. */
    String toolName(String operation);

    /** The MCP server that {@link #toolName} names: the audience of the signature and the part of the key's identity. */
    String server();

    /**
     * A host event for the tool. {@code arguments} is a JSON tree, a record, a map or {@link Json#raw raw JSON};
     * a Java null {@code hostId} leaves the id key out.
     */
    byte[] event(String toolName, Object arguments, JsonNode hostId);

    /** Like {@link #event(String, Object, JsonNode)} with the standard host id {@link HookFixture#HOST_ID}. */
    default byte[] event(String toolName, Object arguments) {
        return event(toolName, arguments, Json.text(HookFixture.HOST_ID));
    }

    /** An event for the operation in a form that makes the host echo the whole arguments back (HK-014). */
    byte[] echoEvent(String operation, Object arguments);

    /** The complete arguments of the answer to an {@link #echoEvent}. */
    JsonNode echoed(Run run);

    /**
     * The answer to a call that gets stamped; {@code sent} are the arguments of the direct call, {@code token} the
     * attestation the hook wrote, {@code addedRequestId} the request id the hook generated (null if it added none).
     */
    Optional<JsonNode> allowed(JsonNode sent, String token, String addedRequestId);

    /**
     * The object of the answer to a direct call that carries {@code _hostAttestation}: the whole stamped arguments
     * (Claude, Codex) or the overwrite that is merged into the host's arguments (Antigravity).
     */
    JsonNode stampedPart(Run run);

    Optional<JsonNode> denied(Refusal reason);

    /** The answer to a call the hook does not handle. */
    Optional<JsonNode> untouched();

    Malformed malformedInput();
}
