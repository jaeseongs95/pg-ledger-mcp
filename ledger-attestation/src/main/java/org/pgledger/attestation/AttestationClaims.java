package org.pgledger.attestation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * What an attestation says and what the signature covers: who writes ({@code author}, {@code <vendor>:<host id>}),
 * which tool the call is for, the request id of the call (empty for a call that has none, such as whoami) and when
 * the hook issued it (UTC epoch seconds).
 *
 * <p>Signed bytes (version 1): the domain line {@code pg-ledger-mcp/attestation/v1}, a line feed, then for author,
 * tool and request id one field each, written as {@code <UTF-8 byte length in decimal>:<value bytes>} and a line
 * feed, then the epoch seconds in decimal and a line feed. The length in front of each value makes the encoding
 * unambiguous whatever characters a value holds, and the domain line keeps a signature of this protocol from being
 * valid in another one.
 */
public record AttestationClaims(String author, String tool, String requestId, long issuedAt) {

    /** The first line of every signed message; a new format gets a new line and a new token prefix. */
    static final String DOMAIN = "pg-ledger-mcp/attestation/v1";

    public AttestationClaims {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(requestId, "requestId");
        if (issuedAt < 0) {
            throw new IllegalArgumentException("issuedAt must not be negative");
        }
    }

    /** The bytes the signature covers. */
    public byte[] signedBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(DOMAIN.getBytes(StandardCharsets.US_ASCII));
        out.write('\n');
        field(out, author);
        field(out, tool);
        field(out, requestId);
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
