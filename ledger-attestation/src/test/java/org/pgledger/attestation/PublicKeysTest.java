package org.pgledger.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The text forms of an Ed25519 public key (ATT-010..ATT-012). */
class PublicKeysTest {

    private static KeyPair pair(String algorithm) {
        try {
            return KeyPairGenerator.getInstance(algorithm).generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    @DisplayName("ATT-010 a public key is its X.509 DER in standard base64 and reads back to the same key")
    void roundTrip() {
        PublicKey key = pair("Ed25519").getPublic();
        String text = PublicKeys.encode(key);
        assertThat(Base64.getDecoder().decode(text)).isEqualTo(key.getEncoded()).hasSize(44);
        assertThat(PublicKeys.decode(text)).isEqualTo(key);
        assertThat(PublicKeys.decode("  " + text + "\n")).isEqualTo(key);
        assertThat(PublicKeys.canonical(" " + text + " ")).isEqualTo(text);
    }

    @Test
    @DisplayName("ATT-011 the fingerprint is the SHA-256 of the DER bytes in 64 lower-case hex characters")
    void fingerprint() throws NoSuchAlgorithmException {
        PublicKey key = pair("Ed25519").getPublic();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        assertThat(PublicKeys.fingerprint(key)).isEqualTo(expected).matches("[0-9a-f]{64}");
        assertThat(PublicKeys.fingerprint(pair("Ed25519").getPublic())).isNotEqualTo(expected);
    }

    static Stream<String> notEd25519Keys() {
        byte[] valid = pair("Ed25519").getPublic().getEncoded();
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 1);
        return Stream.of(
                "",
                "not base64!",
                Base64.getEncoder().encodeToString(trailing),
                Base64.getEncoder().encodeToString(truncated),
                Base64.getEncoder().encodeToString(pair("Ed25519").getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair("X25519").getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(pair("RSA").getPublic().getEncoded()));
    }

    @ParameterizedTest
    @MethodSource("notEd25519Keys")
    @DisplayName("ATT-012 anything that is not a canonical Ed25519 X.509 public key (private key, other algorithm, damaged or padded DER, bad base64) is KEY_INVALID")
    void invalidKeys(String text) {
        try {
            PublicKeys.decode(text);
        } catch (AttestationException failure) {
            assertThat(failure.reason()).isEqualTo(AttestationException.Reason.KEY_INVALID);
            return;
        }
        throw new AssertionError("the key must be refused");
    }

    @Test
    @DisplayName("ATT-012 no key text (null) is KEY_INVALID")
    void nullKey() {
        try {
            PublicKeys.decode(null);
        } catch (AttestationException failure) {
            assertThat(failure.reason()).isEqualTo(AttestationException.Reason.KEY_INVALID);
            return;
        }
        throw new AssertionError("the key must be refused");
    }
}
