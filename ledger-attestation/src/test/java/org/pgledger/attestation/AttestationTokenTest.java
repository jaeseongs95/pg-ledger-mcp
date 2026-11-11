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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The token format of the author attestation (ATT-001..ATT-008). */
class AttestationTokenTest {

    private static final AttestationClaims CLAIMS = new AttestationClaims("claude:s1", "ledger_save", "r1", 1_700_000_000L);

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
    @DisplayName("ATT-001 a signed token has the text pgla1.<author>.<tool>.<request id>.<issued at>.<signature> and parses back to the same claims")
    void roundTrip() {
        KeyPair pair = newKeyPair();
        AttestationToken token = AttestationToken.sign(CLAIMS, pair.getPrivate());
        String text = token.text();
        String[] parts = text.split("\\.", -1);
        assertThat(parts).hasSize(6);
        assertThat(parts[0]).isEqualTo("pgla1");
        assertThat(decode(parts[1])).isEqualTo("claude:s1");
        assertThat(decode(parts[2])).isEqualTo("ledger_save");
        assertThat(decode(parts[3])).isEqualTo("r1");
        assertThat(parts[4]).isEqualTo("1700000000");
        assertThat(Base64.getUrlDecoder().decode(parts[5])).hasSize(64);
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
                new AttestationClaims("claude:s2", CLAIMS.tool(), CLAIMS.requestId(), CLAIMS.issuedAt()),
                new AttestationClaims(CLAIMS.author(), "ledger_link", CLAIMS.requestId(), CLAIMS.issuedAt()),
                new AttestationClaims(CLAIMS.author(), CLAIMS.tool(), "r2", CLAIMS.issuedAt()),
                new AttestationClaims(CLAIMS.author(), CLAIMS.tool(), "", CLAIMS.issuedAt()),
                new AttestationClaims(CLAIMS.author(), CLAIMS.tool(), CLAIMS.requestId(), CLAIMS.issuedAt() + 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tampered")
    @DisplayName("ATT-003 a token whose author, tool, request id or time was changed after signing is SIGNATURE_INVALID")
    void changedClaimsInvalidateTheSignature(AttestationClaims changed) {
        KeyPair pair = newKeyPair();
        String signature = AttestationToken.sign(CLAIMS, pair.getPrivate()).text().split("\\.", -1)[5];
        String forged = String.join(".", "pgla1", encode(changed.author()), encode(changed.tool()),
                encode(changed.requestId()), Long.toString(changed.issuedAt()), signature);
        AttestationToken token = AttestationToken.parse(forged);
        assertThat(token.claims()).isEqualTo(changed);
        assertThat(reasonOf(() -> token.verify(pair.getPublic()))).isEqualTo(AttestationException.Reason.SIGNATURE_INVALID);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("ATT-004 the signed bytes are fixed: domain line, length-prefixed author, tool and request id, epoch seconds")
    void signedBytesAreFixed() {
        assertThat(text(CLAIMS.signedBytes()))
                .isEqualTo("pg-ledger-mcp/attestation/v1\n9:claude:s1\n11:ledger_save\n2:r1\n1700000000\n");
    }

    @Test
    @DisplayName("ATT-005 moving characters between fields, or hiding a field boundary in a value, never gives the same signed bytes")
    void encodingIsUnambiguous() {
        List<AttestationClaims> all = List.of(
                new AttestationClaims("ab", "c", "", 1),
                new AttestationClaims("a", "bc", "", 1),
                new AttestationClaims("a", "b", "c", 1),
                new AttestationClaims("", "abc", "", 1),
                new AttestationClaims("a\n1:b", "", "", 1),
                new AttestationClaims("a", "1:b\n", "", 1),
                new AttestationClaims("a", "", "1:b\n", 1),
                new AttestationClaims("a", "b", "c", 10),
                new AttestationClaims("a", "b", "c", 1));
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
        for (AttestationClaims claims : List.of(new AttestationClaims("claude:s1", "ledger_whoami", "", 0),
                new AttestationClaims("", "", "", 5),
                new AttestationClaims("claude:세션-1", "ledger_저장", "요청", 1_900_000_000L))) {
            AttestationToken parsed = AttestationToken.parse(AttestationToken.sign(claims, pair.getPrivate()).text());
            assertThat(parsed.claims()).isEqualTo(claims);
            parsed.verify(pair.getPublic());
        }
    }

    static Stream<String> malformed() {
        String valid = AttestationToken.sign(CLAIMS, newKeyPair().getPrivate()).text();
        String[] parts = valid.split("\\.", -1);
        String signature = parts[5];
        return Stream.of(
                "",
                "claude:abc",
                "pgla1",
                "pgla1.a.b.c.d",
                valid + ".extra",
                "pgla2." + valid.substring("pgla1.".length()),
                "PGLA1." + valid.substring("pgla1.".length()),
                String.join(".", "pgla1", "a*", parts[2], parts[3], parts[4], signature),
                String.join(".", "pgla1", parts[1] + "=", parts[2], parts[3], parts[4], signature),
                String.join(".", "pgla1", "/w", parts[2], parts[3], parts[4], signature),
                String.join(".", "pgla1", "gA", parts[2], parts[3], parts[4], signature),
                String.join(".", "pgla1", parts[1], parts[2], parts[3], "-1", signature),
                String.join(".", "pgla1", parts[1], parts[2], parts[3], "01", signature),
                String.join(".", "pgla1", parts[1], parts[2], parts[3], "1e3", signature),
                String.join(".", "pgla1", parts[1], parts[2], parts[3], "", signature),
                String.join(".", "pgla1", parts[1], parts[2], parts[3], "1234567890123456", signature),
                String.join(".", "pgla1", parts[1], parts[2], parts[3], parts[4], signature.substring(0, signature.length() - 2)),
                String.join(".", "pgla1", parts[1], parts[2], parts[3], parts[4], signature + "AA"),
                String.join(".", "pgla1", parts[1], parts[2], parts[3], parts[4], ""),
                valid + "x".repeat(2100));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    @DisplayName("ATT-007 text that is not a canonical token (old format, wrong shape, bad encoding, bad time, bad sizes) is MALFORMED")
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
        String signature = AttestationToken.sign(CLAIMS, newKeyPair().getPrivate()).text().split("\\.", -1)[5];
        String notUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xC3, (byte) 0x28});
        String text = String.join(".", "pgla1", notUtf8, encode("ledger_save"), encode("r1"), "1", signature);
        assertThat(reasonOf(() -> AttestationToken.parse(text))).isEqualTo(AttestationException.Reason.MALFORMED);
    }

    @Test
    @DisplayName("ATT-008 toString shows the claims and neither the signature nor the text of the token")
    void toStringHidesTheSignature() {
        AttestationToken token = AttestationToken.sign(CLAIMS, newKeyPair().getPrivate());
        String signature = token.text().split("\\.", -1)[5];
        assertThat(token.toString()).contains("claude:s1").doesNotContain(signature).doesNotContain(token.text());
        assertThatThrownBy(() -> AttestationToken.parse("pgla1.x")).hasMessageNotContaining("pgla1.x");
    }
}
