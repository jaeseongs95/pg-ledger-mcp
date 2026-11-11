package org.pgledger.hook;

import tools.jackson.databind.node.ObjectNode;

/** The attestation decision for one ledger call. */
sealed interface Verdict {

    /** The call may go on with {@code arguments}: the model's arguments plus {@code _hostAttestation}. */
    record Stamped(ObjectNode arguments, String attestation) implements Verdict {}

    record Denied(Refusal reason) implements Verdict {}
}
