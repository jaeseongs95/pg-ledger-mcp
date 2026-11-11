package org.pgledger.mcp.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.pgledger.attestation.ArgumentsDigest;
import org.pgledger.domain.value.AuthorId;
import org.springframework.context.ApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the client hook does for the tests: a write call whose {@code _hostAttestation} is a plain author of the ledger
 * format ({@code claude:e2e-author}) becomes the call the hook would send: the author is signed for the tool and the
 * request id of the call (a request id that is missing is made here, as the hook makes it) and the signed text takes the
 * place of the plain author. This is the one place of the MCP tests that makes a signature for the tests that are not
 * about the attestation itself; the authors are registered with their keys the first time they are used. A value that is
 * not a plain author (an empty or malformed one, a token a test made itself) is sent as it is, and so is a call whose
 * test asks for {@link McpSession#callUnsigned}.
 */
public final class Attester {

    private static final String ATTESTATION = "_hostAttestation";
    private static final String REQUEST_ID = "request_id";
    private static final String WHOAMI = "ledger_whoami";
    /** The tools that carry the attestation: the nine writes and whoami (a read tool never gets one). */
    private static final Set<String> ATTESTED_TOOLS = Set.of("ledger_save", "ledger_transition", "ledger_record_fix",
            "ledger_record_case", "ledger_link", "ledger_correct", "ledger_batch", "ledger_review",
            "ledger_review_correct", WHOAMI);

    private final KeyRing keys;
    private final JsonMapper mapper;

    private Attester(KeyRing keys, JsonMapper mapper) {
        this.keys = keys;
        this.mapper = mapper;
    }

    /** The attester of the server in the context. */
    public static Attester of(ApplicationContext context) {
        return new Attester(context.getBean(KeyRing.class), context.getBean(JsonMapper.class));
    }

    /** An attester that changes nothing (a client without a hook). */
    public static Attester none() {
        return new Attester(null, null);
    }

    /** The arguments as the hook would send them; the object itself when there is nothing to sign. */
    public Object stamp(String tool, Object arguments) {
        if (keys == null || !ATTESTED_TOOLS.contains(tool)) {
            return arguments;
        }
        Map<String, Object> call = mapper.convertValue(arguments, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        if (!(call.get(ATTESTATION) instanceof String author) || !AuthorId.matches(author)) {
            return arguments;
        }
        String requestId = "";
        if (!WHOAMI.equals(tool)) {
            requestId = call.get(REQUEST_ID) instanceof String given && !given.isBlank() ? given
                    : UUID.randomUUID().toString();
            call.put(REQUEST_ID, requestId);
        }
        keys.enroll(author);
        // the hook signs the arguments it hands the host: the request id is in, the attestation itself is not
        call.put(ATTESTATION, keys.token(author, tool, requestId, ArgumentsDigest.of(call)));
        return call;
    }
}
