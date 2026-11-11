package org.pgledger.hook;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.hook.keys.SessionKey;
import org.pgledger.hook.keys.SessionKeyException;
import org.pgledger.hook.keys.SessionKeyStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The attestation rule, the same for every host: a ledger write call gets {@code _hostAttestation} added to the
 * model's arguments, unless the arguments are not an object, already contain the reserved key, the host id is missing
 * or malformed, or the {@code request_id} is not a text. The value is the text of an
 * {@link org.pgledger.attestation.AttestationToken}, signed with the private key of the author
 * {@code <vendor>:<host id>}, for the canonical tool name {@code ledger_<operation>}, the request id of the call and
 * the time of the injected clock. The request id is the argument {@code request_id}; if the model gave none (absent,
 * JSON null or blank) the hook generates a random UUID and puts it into the arguments as {@code request_id}. whoami has
 * no request id: its claim is empty and no {@code request_id} is added. Nothing else is changed.
 *
 * <p>The key pair of an author is created in the key folder by the first call of that author.
 */
record LedgerHook(HostDialect dialect, JsonCodec codec, Clock clock, SessionKeyStore keys) {

    private static final Logger LOG = Logger.getLogger(LedgerHook.class.getName());
    private static final String RESERVED_KEY = Intake.RESERVED_KEY;
    private static final String REQUEST_ID_KEY = "request_id";

    /** Turns the raw stdin bytes of one invocation into the answer for the host. */
    Response handle(byte[] stdin) {
        if (isBlank(stdin)) {
            return new Response.Silent();
        }
        Intake intake;
        try {
            intake = dialect.read(stdin);
        } catch (JacksonException malformed) {
            // the one place where malformed input becomes the host's answer (the stderr contract allows no log line)
            LOG.log(Level.FINE, "the hook input is not valid JSON", malformed);
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
        Optional<HostId> id = HostId.of(call.hostId());
        if (id.isEmpty()) {
            return new Verdict.Denied(Refusal.HOST_ID_INVALID);
        }
        String requestId;
        String generatedRequestId = null;
        if (call.tool() == WriteTool.WHOAMI) {
            requestId = "";
        } else {
            JsonNode given = arguments.get(REQUEST_ID_KEY);
            if (given == null || given.isNull() || (given.isString() && given.stringValue().isBlank())) {
                generatedRequestId = UUID.randomUUID().toString();
                requestId = generatedRequestId;
            } else if (given.isString()) {
                requestId = given.stringValue();
            } else {
                return new Verdict.Denied(Refusal.REQUEST_ID_INVALID);
            }
        }
        String author = dialect.vendor().attest(id.get());
        SessionKey key;
        try {
            key = keys.load(author);
        } catch (SessionKeyException unusable) {
            // the denial names no path and no key; the reason is in the cause (fine level only)
            LOG.log(Level.FINE, "the session key of the author cannot be used", unusable);
            return new Verdict.Denied(Refusal.SESSION_KEY_UNAVAILABLE);
        }
        AttestationClaims claims = new AttestationClaims(
                author, call.tool().canonicalName(), requestId, clock.instant().getEpochSecond());
        String attestation = key.sign(claims).text();
        ObjectNode stamped = arguments.deepCopy();
        if (generatedRequestId != null) {
            stamped.put(REQUEST_ID_KEY, generatedRequestId);
        }
        stamped.put(RESERVED_KEY, attestation);
        return new Verdict.Stamped(stamped, attestation, generatedRequestId);
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
