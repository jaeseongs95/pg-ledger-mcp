package org.pgledger.attestation;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The text forms of an Ed25519 public key: X.509 SubjectPublicKeyInfo (DER) in standard base64, which is what the
 * hook prints and the owner registers, and its fingerprint, the SHA-256 of the DER bytes in 64 lower-case hex
 * characters, which the owner compares with the one the requesting session showed.
 */
public final class PublicKeys {

    private static final String ALGORITHM = "Ed25519";

    private PublicKeys() {
    }

    /**
     * Reads a key. Only a canonical Ed25519 key is accepted: the JDK must read the DER as such a key and write the
     * same bytes back.
     *
     * @throws AttestationException with reason {@code KEY_INVALID} for anything else (another algorithm, a private key,
     *         trailing bytes, text that is not base64)
     */
    public static PublicKey decode(String base64) {
        if (base64 == null) {
            throw new AttestationException(AttestationException.Reason.KEY_INVALID);
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException failure) {
            throw new AttestationException(AttestationException.Reason.KEY_INVALID, failure);
        }
        PublicKey key;
        try {
            key = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key factory", failure);
        } catch (InvalidKeySpecException | IllegalArgumentException failure) {
            throw new AttestationException(AttestationException.Reason.KEY_INVALID, failure);
        }
        if (!Arrays.equals(key.getEncoded(), der)) {
            throw new AttestationException(AttestationException.Reason.KEY_INVALID);
        }
        return key;
    }

    /** The X.509 DER bytes of the key in standard base64 (with padding). */
    public static String encode(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** SHA-256 of the X.509 DER bytes, 64 lower-case hex characters. */
    public static String fingerprint(PublicKey key) {
        return Sha256Hex.of(key.getEncoded());
    }

    /** The text that stands for the same key in one canonical form (what the server stores). */
    public static String canonical(String base64) {
        return encode(decode(base64));
    }
}
