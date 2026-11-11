package org.pgledger.attestation;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The value of {@code _hostAttestation}: the claims and the Ed25519 signature over them (RFC 8032, through the JDK
 * {@code Signature} of algorithm "Ed25519").
 *
 * <p>Text form (version 2): {@code pgla2.<author>.<tool>.<request id>.<arguments digest>.<issued at>.<signature>}.
 * Author, tool and request id are the UTF-8 bytes of the value in unpadded base64url (so none of them can hold the
 * separator and an empty value is an empty part), the arguments digest is 64 lower-case hex characters, the issued-at
 * time is decimal epoch seconds without leading zeros, the signature is the 64 signature bytes in unpadded base64url.
 * A text is accepted only in exactly that canonical form, so one token has one text. The prefix of version 1
 * ({@code pgla1}, without the arguments digest) is refused with {@link AttestationException.Reason#VERSION_UNSUPPORTED}.
 */
public final class AttestationToken {

    /** The prefix that names the format and its version. */
    public static final String PREFIX = "pgla2";

    /** The prefix of the retired version 1: it signed no arguments, so a captured token could carry other content. */
    public static final String RETIRED_PREFIX = "pgla1";

    /** The longest text that is parsed at all (a real token is far shorter). */
    static final int MAX_LENGTH = 2048;

    private static final int PARTS = 7;
    private static final int SIGNATURE_LENGTH = 64;
    private static final String ALGORITHM = "Ed25519";
    private static final Pattern EPOCH = Pattern.compile("0|[1-9][0-9]{0,14}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final AttestationClaims claims;
    private final byte[] signature;

    private AttestationToken(AttestationClaims claims, byte[] signature) {
        this.claims = claims;
        this.signature = signature;
    }

    /** Signs the claims with the private key of the author. */
    public static AttestationToken sign(AttestationClaims claims, PrivateKey key) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key);
            signer.update(claims.signedBytes());
            return new AttestationToken(claims, signer.sign());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 signature", failure);
        } catch (InvalidKeyException | SignatureException failure) {
            throw new IllegalArgumentException("The key cannot sign an attestation", failure);
        }
    }

    /**
     * Reads the text form.
     *
     * @throws AttestationException with reason {@code MALFORMED} when the text is not a token of this format
     */
    public static AttestationToken parse(String text) {
        if (text == null || text.length() > MAX_LENGTH) {
            throw new AttestationException(AttestationException.Reason.MALFORMED);
        }
        if (text.startsWith(RETIRED_PREFIX + ".")) {
            throw new AttestationException(AttestationException.Reason.VERSION_UNSUPPORTED);
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length != PARTS || !PREFIX.equals(parts[0])) {
            throw new AttestationException(AttestationException.Reason.MALFORMED);
        }
        String author = decodeText(parts[1]);
        String tool = decodeText(parts[2]);
        String requestId = decodeText(parts[3]);
        if (!ArgumentsDigest.isWellFormed(parts[4])) {
            throw new AttestationException(AttestationException.Reason.MALFORMED);
        }
        long issuedAt = parseEpoch(parts[5]);
        byte[] signature = decodeBytes(parts[6]);
        if (signature.length != SIGNATURE_LENGTH) {
            throw new AttestationException(AttestationException.Reason.MALFORMED);
        }
        return new AttestationToken(new AttestationClaims(author, tool, requestId, parts[4], issuedAt), signature);
    }

    public AttestationClaims claims() {
        return claims;
    }

    /** The text form that {@link #parse} reads back. */
    public String text() {
        return String.join(".", PREFIX, encodeText(claims.author()), encodeText(claims.tool()),
                encodeText(claims.requestId()), claims.argumentsDigest(), Long.toString(claims.issuedAt()),
                ENCODER.encodeToString(signature));
    }

    /**
     * Checks the signature against the public key of the author.
     *
     * @throws AttestationException with reason {@code SIGNATURE_INVALID} when it does not match
     */
    public void verify(PublicKey key) {
        boolean valid;
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(claims.signedBytes());
            valid = verifier.verify(signature);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 signature", failure);
        } catch (InvalidKeyException | SignatureException failure) {
            throw new AttestationException(AttestationException.Reason.SIGNATURE_INVALID, failure);
        }
        if (!valid) {
            throw new AttestationException(AttestationException.Reason.SIGNATURE_INVALID);
        }
    }

    /** The claims without the signature: a token is proof for a short time and does not belong in a log. */
    @Override
    public String toString() {
        return "AttestationToken[" + claims + "]";
    }

    private static String encodeText(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String part) {
        byte[] bytes = decodeBytes(part);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new AttestationException(AttestationException.Reason.MALFORMED, failure);
        }
    }

    private static byte[] decodeBytes(String part) {
        byte[] bytes;
        try {
            bytes = DECODER.decode(part);
        } catch (IllegalArgumentException failure) {
            throw new AttestationException(AttestationException.Reason.MALFORMED, failure);
        }
        // only the canonical text of the bytes is a token: no padding, no stray trailing bits
        if (!part.equals(ENCODER.encodeToString(bytes))) {
            throw new AttestationException(AttestationException.Reason.MALFORMED);
        }
        return bytes;
    }

    private static long parseEpoch(String part) {
        if (!EPOCH.matcher(part).matches()) {
            throw new AttestationException(AttestationException.Reason.MALFORMED);
        }
        return Long.parseLong(part);
    }
}
