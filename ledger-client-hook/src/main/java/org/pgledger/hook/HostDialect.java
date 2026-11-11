package org.pgledger.hook;

import tools.jackson.core.JacksonException;

/** How one host phrases its pre-tool-use event and the answer to it. */
sealed interface HostDialect permits ClaudeDialect, CodexDialect, AntigravityDialect {

    /** The vendor prefix of the attestation this host produces. */
    Vendor vendor();

    /**
     * Binds the host event and finds out whether it is a ledger write call and where its arguments and host id are.
     *
     * @throws JacksonException if {@code stdin} is not exactly one valid JSON document
     */
    Intake read(byte[] stdin) throws JacksonException;

    /** The answer to input that is not valid JSON. */
    Response malformedInput();

    /** The answer for a call that is not a ledger write call. */
    Response notLedgerCall();

    /** The answer that refuses the call. */
    Response deny(Refusal reason);

    /** The answer that lets the call go on with the stamped arguments. */
    Response allow(Intake.Attest call, Verdict.Stamped stamped);
}
