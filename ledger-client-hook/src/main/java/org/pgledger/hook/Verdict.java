package org.pgledger.hook;

import tools.jackson.databind.node.ObjectNode;

/** The attestation decision for one ledger call. */
sealed interface Verdict {

    /**
     * The call may go on with {@code arguments}: the model's arguments plus {@code _hostAttestation} (and the
     * generated {@code request_id}, if the model gave none). The arguments are arbitrary JSON of the model and stay a
     * Jackson tree so that they are given back unchanged.
     *
     * @param attestation        the text of the signed attestation token; it is written into the host's answer and
     *                           nowhere else (not logged)
     * @param generatedRequestId the request id the hook generated and put into the arguments, or {@code null} if the
     *                           arguments keep their own (or the tool, whoami, has none)
     */
    record Stamped(ObjectNode arguments, String attestation, String generatedRequestId) implements Verdict {}

    record Denied(Refusal reason) implements Verdict {}
}
