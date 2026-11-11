package org.pgledger.application.gate;

import org.pgledger.domain.value.AuthorId;

/** Turns what a caller presented as {@code _hostAttestation} into the author it proves, or refuses it. */
public interface AttestationVerifier {

    /**
     * Checks, in this order: the text is a signed attestation whose author has the ledger format; the signature is
     * valid under the live registered key of the author (no key and a wrong signature are the same refusal,
     * ATTESTATION_SIGNATURE_INVALID, with no author in it); the tool, the request id and the arguments digest are those
     * of the call; the issue time is inside the window. Reads only: for a call that writes use
     * {@link #verifyForWrite}.
     *
     * @param presented       the raw value of {@code _hostAttestation}; {@code null} is HOST_ATTESTATION_REQUIRED
     * @param tool            the tool of the call (the wire name), or {@code null} outside a tool call
     * @param requestId       the request id of the call as the caller sent it; {@code null} and empty are the same
     * @param argumentsDigest the digest of the arguments of the call as the server received them
     *                        ({@link org.pgledger.attestation.ArgumentsDigest}), or {@code null} outside a tool call
     *                        (never equal to a signed digest)
     * @return the author the attestation proves
     * @throws org.pgledger.domain.error.TrustException with the code of the first check that fails
     */
    AuthorId verify(String presented, String tool, String requestId, String argumentsDigest);

    /**
     * As {@link #verify}, for a call that writes, inside the transaction of the write: the live key of the author is
     * also locked until the transaction ends (a conditional UPDATE, like the grant check), so closing the key waits for
     * this write, and a write that verified the key before a close committed cannot commit after it.
     */
    AuthorId verifyForWrite(String presented, String tool, String requestId, String argumentsDigest);
}
