package org.pgledger.hook;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * One host as the tests see it: how it names the ledger tools, how its event is written, and what a correct answer
 * looks like. The expected answers are the host contract; a test only states the case and asks the scenario.
 */
interface HostScenario {

    /** What a host that gets malformed input must see: exit code, answer, and whether stderr carries a message. */
    record Malformed(int exit, Optional<HostAnswer> answer, boolean stderrMessage) {}

    Client client();

    /** The vendor prefix of the stamped id, spelled out as the contract has it. */
    String vendor();

    /** The host's name for {@code ledger_<operation>}. */
    String toolName(String operation);

    /** A host event for the tool, with the arguments as JSON; a null {@code hostId} leaves the id key out. */
    byte[] event(String toolName, JsonNode body, JsonNode hostId);

    /** A host event whose arguments are the given raw JSON text, with the standard host id (for HK-014). */
    byte[] rawEvent(String toolName, String rawBody);

    /** Like {@link #rawEvent} for an operation, in a form that makes the host echo the whole arguments back. */
    byte[] echoEvent(String operation, String rawBody);

    /** The complete arguments of the answer to an {@link #echoEvent}. */
    JsonNode echoed(Run run);

    /** The stdout of {@code run} read as this host's answer; empty if nothing was written. */
    Optional<HostAnswer> answer(Run run);

    /** The answer to a direct call that gets stamped. */
    Optional<HostAnswer> allowed(String attestation, JsonNode stampedBody);

    Optional<HostAnswer> denied(Refusal reason);

    /** The answer to a call the hook does not handle. */
    Optional<HostAnswer> untouched();

    Malformed malformedInput();
}
