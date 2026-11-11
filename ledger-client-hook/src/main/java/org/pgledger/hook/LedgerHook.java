package org.pgledger.hook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;

/**
 * The stateless attestation rule, the same for every host: a ledger write call gets
 * {@code _hostAttestation = <vendor>:<host id>} added to the model's arguments, unless the arguments are not an
 * object, already contain the reserved key, or the host id is missing or malformed. Nothing else is changed.
 */
record LedgerHook(HostDialect dialect, JsonCodec codec) {

    private static final String RESERVED_KEY = Intake.RESERVED_KEY;

    /** Turns the raw stdin bytes of one invocation into the answer for the host. */
    Response handle(byte[] stdin) {
        if (isBlank(stdin)) {
            return new Response.Silent();
        }
        Intake intake;
        try {
            intake = dialect.read(stdin);
        } catch (JacksonException malformed) {
            return dialect.malformedInput();
        }
        return switch (intake) {
            case Intake.Skip skip -> dialect.notLedgerCall();
            case Intake.Refuse refuse -> dialect.deny(refuse.reason());
            case Intake.Attest call -> switch (stamp(call)) {
                case Verdict.Stamped stamped -> dialect.allow(call, stamped);
                case Verdict.Denied denied -> dialect.deny(denied.reason());
            };
        };
    }

    private Verdict stamp(Intake.Attest call) {
        // The arguments are arbitrary JSON of the model: the copy of the tree keeps every value as the host sent it.
        if (!(call.arguments() instanceof ObjectNode arguments)) {
            return new Verdict.Denied(Refusal.ARGUMENTS_NOT_OBJECT);
        }
        if (arguments.has(RESERVED_KEY)) {
            return new Verdict.Denied(Refusal.RESERVED_FIELD);
        }
        return HostId.of(call.hostId())
                .<Verdict>map(id -> {
                    String attestation = dialect.vendor().attest(id);
                    ObjectNode stamped = arguments.deepCopy();
                    stamped.put(RESERVED_KEY, attestation);
                    return new Verdict.Stamped(stamped, attestation);
                })
                .orElseGet(() -> new Verdict.Denied(Refusal.HOST_ID_INVALID));
    }

    private static boolean isBlank(byte[] bytes) {
        for (byte b : bytes) {
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return false;
            }
        }
        return true;
    }
}
