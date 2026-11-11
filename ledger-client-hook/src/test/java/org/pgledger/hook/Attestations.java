package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.pgledger.attestation.ArgumentsDigest;
import org.pgledger.attestation.AttestationClaims;
import org.pgledger.attestation.AttestationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one place of the tests that reads a stamped {@code _hostAttestation}: it parses the token, compares every claim
 * and verifies the signature with the public key of the author, taken from the key folder of the test.
 */
final class Attestations {

    /** The checked token, and the request id the hook generated (null if the arguments kept their own or have none). */
    record Stamp(String token, AttestationClaims claims, String addedRequestId) {}

    /** The accepted values of {@code issuedAt}, in epoch seconds, both ends included. */
    record Window(long from, long to) {

        /** Exactly this instant, for a run with the fixed clock of {@link HookFixture}. */
        static Window at(Instant instant) {
            return new Window(instant.getEpochSecond(), instant.getEpochSecond());
        }
    }

    private Attestations() {}

    /**
     * Verifies the attestation that the hook put into {@code carrier} (the object of the answer that holds the stamped
     * arguments, or the stamped arguments themselves) for a call whose own arguments were {@code sent}.
     *
     * @param server the MCP server of the call: the hook signs with the key of this server and this author
     * @param op the operation after {@code ledger_} (save, whoami ...)
     */
    static Stamp verify(Path keyDirectory, Window window, String server, String author, String op, JsonNode sent,
            JsonNode carrier) {
        JsonNode tokenNode = carrier.path(Json.RESERVED_KEY);
        assertThat(tokenNode.isString()).as("_hostAttestation is a text").isTrue();
        String token = tokenNode.stringValue();
        AttestationToken parsed = AttestationToken.parse(token);
        assertThat(parsed.text()).as("canonical token text").isEqualTo(token);
        AttestationClaims claims = parsed.claims();
        assertThat(claims.author()).as("author").isEqualTo(author);
        assertThat(claims.tool()).as("tool").isEqualTo("ledger_" + op);
        assertThat(claims.issuedAt()).as("issuedAt").isBetween(window.from(), window.to());
        // throws AttestationException (SIGNATURE_INVALID) if the key of the author did not sign these claims
        parsed.verify(publicKey(keyDirectory, server, author));

        JsonNode given = sent.get("request_id");
        boolean keeps = given != null && given.isString() && !given.stringValue().isBlank();
        String added = null;
        ObjectNode handedToTheHost = ((ObjectNode) sent).deepCopy();
        if ("whoami".equals(op)) {
            assertThat(claims.requestId()).as("whoami has no request id").isEmpty();
        } else {
            handedToTheHost.put("request_id", claims.requestId());
        }
        // the digest covers the arguments the host hands the server (the request id in, the attestation out), recomputed
        // here with the shared class from the tests' own reading of the JSON
        Map<?, ?> plain = (Map<?, ?>) Json.MAPPER.treeToValue(handedToTheHost, Object.class);
        assertThat(claims.argumentsDigest()).as("arguments digest").isEqualTo(ArgumentsDigest.of(plain));
        if (!"whoami".equals(op) && keeps) {
            assertThat(claims.requestId()).as("the request id of the call is signed as it is").isEqualTo(given.stringValue());
        } else if (!"whoami".equals(op)) {
            assertThat(claims.requestId()).as("a generated request id").isNotEmpty();
            assertThat(UUID.fromString(claims.requestId()).toString()).isEqualTo(claims.requestId());
            assertThat(carrier.path("request_id").stringValue()).as("request_id in the arguments").isEqualTo(claims.requestId());
            added = claims.requestId();
        }
        return new Stamp(token, claims, added);
    }

    /** The public key of the author for the server from the key folder (the key is read, or created if there is none yet). */
    static PublicKey publicKey(Path keyDirectory, String server, String author) {
        return TestStores.load(TestStores.open(keyDirectory), server, author).publicKey();
    }

    /** The name of the key file of a server and an author: the lower-case hex SHA-256 of {@code server LF author} plus {@code .key}. */
    static String keyFileName(String server, String author) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((server + "\n" + author).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + ".key";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
