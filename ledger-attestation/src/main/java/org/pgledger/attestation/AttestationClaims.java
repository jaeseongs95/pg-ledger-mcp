package org.pgledger.attestation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * What an attestation says and what the signature covers: who writes ({@code author}, {@code <vendor>:<host id>}),
 * which tool the call is for, the request id of the call (empty for a call that has none, such as whoami), the digest
 * of the arguments of the call ({@link ArgumentsDigest}) and when the hook issued it (UTC epoch seconds).
 *
 * <p>Signed bytes (version 2): the domain line {@code pg-ledger-mcp/attestation/v2}, a line feed, then for author,
 * tool, request id and arguments digest one field each, written as {@code <UTF-8 byte length in decimal>:<value bytes>}
 * and a line feed, then the epoch seconds in decimal and a line feed. The length in front of each value makes the
 * encoding unambiguous whatever characters a value holds, and the domain line keeps a signature of this protocol (and of
 * version 1, which had no arguments digest) from being valid in another one.
 */
public record AttestationClaims(String author, String tool, String requestId, String argumentsDigest, long issuedAt) {

    /** The first line of every signed message; a new format gets a new line and a new token prefix. */
    static final String DOMAIN = "pg-ledger-mcp/attestation/v2";

    public AttestationClaims {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(requestId, "requestId");
        if (!ArgumentsDigest.isWellFormed(argumentsDigest)) {
            throw new IllegalArgumentException("argumentsDigest must be 64 lower-case hex characters");
        }
        if (issuedAt < 0) {
            throw new IllegalArgumentException("issuedAt must not be negative");
        }
    }

    /** The fields of a call that an attestation is bound to, with the token the external contract names them by. */
    public enum Field {
        TOOL("tool"), REQUEST_ID("request_id"), ARGUMENTS("arguments");

        private final String token;

        Field(String token) {
            this.token = token;
        }

        /** The name of the field in the error of a binding mismatch ({@code details.field}). */
        public String token() {
            return token;
        }
    }

    /**
     * The first field of the call that these claims were not signed for, checked in the order tool, request id,
     * arguments; none when the call is what was signed. A call that has no request id (such as whoami) is one with the
     * empty request id.
     *
     * @param tool            the tool being called, or {@code null} outside a tool call
     * @param requestId       the request id of the call, or {@code null} for a call that has none
     * @param argumentsDigest the digest of the arguments of the call, or {@code null} outside a tool call
     */
    public Optional<Field> firstMismatch(String tool, String requestId, String argumentsDigest) {
        if (!Objects.equals(tool, this.tool)) {
            return Optional.of(Field.TOOL);
        }
        if (!Objects.equals(requestId == null ? "" : requestId, this.requestId)) {
            return Optional.of(Field.REQUEST_ID);
        }
        if (!Objects.equals(argumentsDigest, this.argumentsDigest)) {
            return Optional.of(Field.ARGUMENTS);
        }
        return Optional.empty();
    }

    /** The bytes the signature covers. */
    public byte[] signedBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(DOMAIN.getBytes(StandardCharsets.US_ASCII));
        out.write('\n');
        field(out, author);
        field(out, tool);
        field(out, requestId);
        field(out, argumentsDigest);
        out.writeBytes(Long.toString(issuedAt).getBytes(StandardCharsets.US_ASCII));
        out.write('\n');
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        out.write(':');
        out.writeBytes(bytes);
        out.write('\n');
    }
}
