package org.pgledger.attestation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The token format of the author attestation (ATT-001..ATT-008, ATT-035, ATT-036; version 2 carries the arguments digest). */
class AttestationTokenTest {

    /** The digest of the arguments object {@code {}}: SHA-256 of the two characters. */
    private static final String EMPTY_ARGUMENTS = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
    private static final String OTHER_ARGUMENTS = ArgumentsDigest.of(Map.of("a", 1));

    private static final AttestationClaims CLAIMS =
            new AttestationClaims("claude:s1", "ledger_save", "r1", EMPTY_ARGUMENTS, 1_700_000_000L);

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static AttestationException.Reason reasonOf(Runnable action) {
        try {
            action.run();
        } catch (AttestationException failure) {
            return failure.reason();
        }
        throw new AssertionError("the action did not fail");
    }

    @Test
    @DisplayName("ATT-001 a signed token has the text pgla2.<author>.<tool>.<request id>.<arguments digest>.<issued at>.<signature> and parses back to the same claims")
    void roundTrip() {
        KeyPair pair = newKeyPair();
        AttestationToken token = AttestationToken.sign(CLAIMS, pair.getPrivate());
        String text = token.text();
        String[] parts = text.split("\\.", -1);
        assertThat(parts).hasSize(7);
        assertThat(parts[0]).isEqualTo("pgla2");
        assertThat(decode(parts[1])).isEqualTo("claude:s1");
        assertThat(decode(parts[2])).isEqualTo("ledger_save");
        assertThat(decode(parts[3])).isEqualTo("r1");
        assertThat(parts[4]).isEqualTo(EMPTY_ARGUMENTS).matches("[0-9a-f]{64}");
        assertThat(parts[5]).isEqualTo("1700000000");
        assertThat(Base64.getUrlDecoder().decode(parts[6])).hasSize(64);
        AttestationToken parsed = AttestationToken.parse(text);
        assertThat(parsed.claims()).isEqualTo(CLAIMS);
        assertThat(parsed.text()).isEqualTo(text);
        parsed.verify(pair.getPublic());
    }

    private static String decode(String part) {
        return text(Base64.getUrlDecoder().decode(part));
    }

    private static String text(byte[] bytes) {
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes)).toString();
    }

    @Test
    @DisplayName("ATT-002 the signature verifies with the key of the signer and with no other key")
    void verifiesOnlyWithTheSignersKey() {
        KeyPair signer = newKeyPair();
        KeyPair other = newKeyPair();
        AttestationToken token = AttestationToken.parse(AttestationToken.sign(CLAIMS, signer.getPrivate()).text());
        token.verify(signer.getPublic());
        assertThat(reasonOf(() -> token.verify(other.getPublic()))).isEqualTo(AttestationException.Reason.SIGNATURE_INVALID);
    }

    static Stream<AttestationClaims> tampered() {
        return Stream.of(
                new AttestationClaims("claude:s2", CLAIMS.tool(), CLAIMS.requestId(), EMPTY_ARGUMENTS, CLAIMS.issuedAt()),
                new AttestationClaims(CLAIMS.author(), "ledger_link", CLAIMS.requestId(), EMPTY_ARGUMENTS, CLAIMS.issuedAt()),
                new AttestationClaims(CLAIMS.author(), CLAIMS.tool(), "r2", EMPTY_ARGUMENTS, CLAIMS.issuedAt()),
                new AttestationClaims(CLAIMS.author(), CLAIMS.tool(), "", EMPTY_ARGUMENTS, CLAIMS.issuedAt()),
                new AttestationClaims(CLAIMS.author(), CLAIMS.tool(), CLAIMS.requestId(), OTHER_ARGUMENTS, CLAIMS.issuedAt()),
                new AttestationClaims(CLAIMS.author(), CLAIMS.tool(), CLAIMS.requestId(), EMPTY_ARGUMENTS, CLAIMS.issuedAt() + 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tampered")
    @DisplayName("ATT-003 and ATT-035 a token whose author, tool, request id, arguments digest or time was changed after signing is SIGNATURE_INVALID")
    void changedClaimsInvalidateTheSignature(AttestationClaims changed) {
        KeyPair pair = newKeyPair();
        String signature = AttestationToken.sign(CLAIMS, pair.getPrivate()).text().split("\\.", -1)[6];
        String forged = String.join(".", "pgla2", encode(changed.author()), encode(changed.tool()),
                encode(changed.requestId()), changed.argumentsDigest(), Long.toString(changed.issuedAt()), signature);
        AttestationToken token = AttestationToken.parse(forged);
        assertThat(token.claims()).isEqualTo(changed);
        assertThat(reasonOf(() -> token.verify(pair.getPublic()))).isEqualTo(AttestationException.Reason.SIGNATURE_INVALID);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("ATT-004 the signed bytes are fixed: domain line, length-prefixed author, tool, request id and arguments digest, epoch seconds")
    void signedBytesAreFixed() {
        assertThat(text(CLAIMS.signedBytes())).isEqualTo("pg-ledger-mcp/attestation/v2\n9:claude:s1\n11:ledger_save\n2:r1\n64:"
                + EMPTY_ARGUMENTS + "\n1700000000\n");
    }

    @Test
    @DisplayName("ATT-005 moving characters between fields, or hiding a field boundary in a value, never gives the same signed bytes")
    void encodingIsUnambiguous() {
        List<AttestationClaims> all = List.of(
                new AttestationClaims("ab", "c", "", EMPTY_ARGUMENTS, 1),
                new AttestationClaims("a", "bc", "", EMPTY_ARGUMENTS, 1),
                new AttestationClaims("a", "b", "c", EMPTY_ARGUMENTS, 1),
                new AttestationClaims("", "abc", "", EMPTY_ARGUMENTS, 1),
                new AttestationClaims("a\n1:b", "", "", EMPTY_ARGUMENTS, 1),
                new AttestationClaims("a", "1:b\n", "", EMPTY_ARGUMENTS, 1),
                new AttestationClaims("a", "", "1:b\n", EMPTY_ARGUMENTS, 1),
                new AttestationClaims("a", "b", "c", EMPTY_ARGUMENTS, 10),
                new AttestationClaims("a", "b", "c", OTHER_ARGUMENTS, 10));
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertThat(all.get(i).signedBytes()).as("%s vs %s", all.get(i), all.get(j))
                        .isNotEqualTo(all.get(j).signedBytes());
            }
        }
    }

    @Test
    @DisplayName("ATT-006 an empty request id and an empty author are empty parts and round trip; non-ASCII values round trip")
    void emptyAndNonAsciiValues() {
        KeyPair pair = newKeyPair();
        for (AttestationClaims claims : List.of(
                new AttestationClaims("claude:s1", "ledger_whoami", "", EMPTY_ARGUMENTS, 0),
                new AttestationClaims("", "", "", OTHER_ARGUMENTS, 5),
                new AttestationClaims("claude:세션-1", "ledger_저장", "요청", EMPTY_ARGUMENTS, 1_900_000_000L))) {
            AttestationToken parsed = AttestationToken.parse(AttestationToken.sign(claims, pair.getPrivate()).text());
            assertThat(parsed.claims()).isEqualTo(claims);
            parsed.verify(pair.getPublic());
        }
    }

    @ParameterizedTest
    @MethodSource("badDigests")
    @DisplayName("ATT-007 claims with an arguments digest that is not 64 lower-case hex characters cannot be made")
    void digestFormatIsChecked(String digest) {
        assertThatThrownBy(() -> new AttestationClaims("a", "b", "c", digest, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> badDigests() {
        return Stream.of("", "abc", EMPTY_ARGUMENTS.toUpperCase(), EMPTY_ARGUMENTS.substring(1), EMPTY_ARGUMENTS + "0",
                "g" + EMPTY_ARGUMENTS.substring(1), " " + EMPTY_ARGUMENTS.substring(1));
    }

    static Stream<String> malformed() {
        String valid = AttestationToken.sign(CLAIMS, newKeyPair().getPrivate()).text();
        String[] parts = valid.split("\\.", -1);
        String signature = parts[6];
        String rest = valid.substring("pgla2.".length());
        return Stream.of(
                "",
                "claude:abc",
                "pgla2",
                "pgla2.a.b.c.d.e",
                valid + ".extra",
                "pgla3." + rest,
                "PGLA2." + rest,
                String.join(".", "pgla2", "a*", parts[2], parts[3], parts[4], parts[5], signature),
                String.join(".", "pgla2", parts[1] + "=", parts[2], parts[3], parts[4], parts[5], signature),
                String.join(".", "pgla2", "/w", parts[2], parts[3], parts[4], parts[5], signature),
                String.join(".", "pgla2", "gA", parts[2], parts[3], parts[4], parts[5], signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4].toUpperCase(), parts[5], signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4].substring(1), parts[5], signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4] + "0", parts[5], signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], "", parts[5], signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4], "-1", signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4], "01", signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4], "1e3", signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4], "", signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4], "1234567890123456", signature),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4], parts[5],
                        signature.substring(0, signature.length() - 2)),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4], parts[5], signature + "AA"),
                String.join(".", "pgla2", parts[1], parts[2], parts[3], parts[4], parts[5], ""),
                valid + "x".repeat(2100));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    @DisplayName("ATT-007 text that is not a canonical token (wrong shape, bad encoding, bad digest, bad time, bad sizes) is MALFORMED")
    void malformedTextIsRefused(String text) {
        assertThat(reasonOf(() -> AttestationToken.parse(text))).isEqualTo(AttestationException.Reason.MALFORMED);
    }

    @Test
    @DisplayName("ATT-007 no text at all (null) is MALFORMED")
    void nullIsRefused() {
        assertThat(reasonOf(() -> AttestationToken.parse(null))).isEqualTo(AttestationException.Reason.MALFORMED);
    }

    @Test
    @DisplayName("ATT-007 bytes that are not UTF-8 in the author part are MALFORMED")
    void authorMustBeUtf8() {
        String signature = AttestationToken.sign(CLAIMS, newKeyPair().getPrivate()).text().split("\\.", -1)[6];
        String notUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xC3, (byte) 0x28});
        String text = String.join(".", "pgla2", notUtf8, encode("ledger_save"), encode("r1"), EMPTY_ARGUMENTS, "1",
                signature);
        assertThat(reasonOf(() -> AttestationToken.parse(text))).isEqualTo(AttestationException.Reason.MALFORMED);
    }

    @Test
    @DisplayName("ATT-036 a token of the retired version 1 (prefix pgla1, six parts, no arguments digest) is VERSION_UNSUPPORTED and never accepted")
    void versionOneIsRefused() {
        String signature = AttestationToken.sign(CLAIMS, newKeyPair().getPrivate()).text().split("\\.", -1)[6];
        String versionOne = String.join(".", "pgla1", encode("claude:s1"), encode("ledger_save"), encode("r1"),
                "1700000000", signature);
        assertThat(reasonOf(() -> AttestationToken.parse(versionOne))).isEqualTo(AttestationException.Reason.VERSION_UNSUPPORTED);
        assertThat(reasonOf(() -> AttestationToken.parse("pgla1."))).isEqualTo(AttestationException.Reason.VERSION_UNSUPPORTED);
    }

    @Test
    @DisplayName("ATT-008 toString shows the claims and neither the signature nor the text of the token")
    void toStringHidesTheSignature() {
        AttestationToken token = AttestationToken.sign(CLAIMS, newKeyPair().getPrivate());
        String signature = token.text().split("\\.", -1)[6];
        assertThat(token.toString()).contains("claude:s1").doesNotContain(signature).doesNotContain(token.text());
        assertThatThrownBy(() -> AttestationToken.parse("pgla2.x")).hasMessageNotContaining("pgla2.x");
    }
}
