package org.pgledger.application.gate;

import org.pgledger.domain.value.AuthorId;

/** Turns what a caller presented as {@code _hostAttestation} into the author it proves, or refuses it. */
public interface AttestationVerifier {

    /**
     * Checks, in this order: the text is a signed attestation whose author has the ledger format; the author has a live
     * registered key; the signature is valid under that key; the tool and the request id are those of the call; the
     * issue time is inside the window.
     *
     * @param presented the raw value of {@code _hostAttestation}; {@code null} is HOST_ATTESTATION_REQUIRED
     * @param tool      the tool of the call (the wire name), or {@code null} outside a tool call
     * @param requestId the request id of the call as the caller sent it; {@code null} and empty are the same
     * @return the author the attestation proves
     * @throws org.pgledger.domain.error.TrustException with the code of the first check that fails
     */
    AuthorId verify(String presented, String tool, String requestId);
}
