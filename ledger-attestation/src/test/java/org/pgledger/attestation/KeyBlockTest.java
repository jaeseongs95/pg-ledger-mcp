package org.pgledger.attestation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** ATT-040 and ATT-041 (audit2 S1): the block that the hook command session-key prints. */
class KeyBlockTest {

    private static final KeyPair PAIR = pair();
    private static final String KEY = PublicKeys.encode(PAIR.getPublic());
    private static final String FINGERPRINT = PublicKeys.fingerprint(PAIR.getPublic());

    private static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String block(String server, String author, String key, String fingerprint) {
        return "server: " + server + "\nauthor: " + author + "\npublic-key: " + key + "\nfingerprint: " + fingerprint + "\n";
    }

    @Test
    @DisplayName("ATT-040 the four lines as the hook prints them are a block: server, author, canonical public key, fingerprint; the lines may come in any order, blank lines, CRLF and white space are ignored, the fingerprint in upper case is the same")
    void aBlockIsRead() {
        KeyBlock expected = new KeyBlock("codex_apps.ledger", "codex:s-1", KEY, FINGERPRINT);

        assertThat(KeyBlock.parse(block("codex_apps.ledger", "codex:s-1", KEY, FINGERPRINT))).isEqualTo(expected);
        assertThat(KeyBlock.parse("\r\n  fingerprint:   " + FINGERPRINT.toUpperCase(java.util.Locale.ROOT)
                + "  \r\n\r\npublic-key:" + KEY + "\r\nauthor: codex:s-1\r\nserver:\tcodex_apps.ledger\r\n\r\n"))
                .isEqualTo(expected);
        assertThat(KeyBlock.isBlock(block("ledger", "claude:a", KEY, FINGERPRINT))).isTrue();
        assertThat(KeyBlock.isBlock("  public-key: " + KEY)).isTrue();
        assertThat(KeyBlock.isBlock(KEY)).isFalse();
        assertThat(KeyBlock.isBlock(" " + KEY + "\n")).isFalse();
        assertThat(KeyBlock.isBlock(null)).isFalse();
    }

    static Stream<Arguments> notABlock() {
        String ok = block("ledger", "claude:a", KEY, FINGERPRINT);
        return Stream.of(
                Arguments.argumentSet("null", null),
                Arguments.argumentSet("empty", ""),
                Arguments.argumentSet("the bare key", KEY),
                Arguments.argumentSet("no server", "author: claude:a\npublic-key: " + KEY + "\nfingerprint: " + FINGERPRINT),
                Arguments.argumentSet("no fingerprint line", "server: ledger\nauthor: claude:a\npublic-key: " + KEY),
                Arguments.argumentSet("a line twice", ok + "author: claude:b\n"),
                Arguments.argumentSet("the same line twice", ok + ok),
                Arguments.argumentSet("an unknown line", ok + "note: x\n"),
                Arguments.argumentSet("a line without a colon", ok + "stray\n"),
                Arguments.argumentSet("an empty value", block("ledger", "", KEY, FINGERPRINT)),
                Arguments.argumentSet("an empty server", block("", "claude:a", KEY, FINGERPRINT)),
                Arguments.argumentSet("a server with a space", block("my server", "claude:a", KEY, FINGERPRINT)),
                Arguments.argumentSet("a server with an escape character", block("led\u001b[2Jger", "claude:a", KEY, FINGERPRINT)),
                Arguments.argumentSet("a server of 101 characters", block("s".repeat(101), "claude:a", KEY, FINGERPRINT)),
                Arguments.argumentSet("upper-case line name", ok.replace("server:", "Server:")));
    }

    @ParameterizedTest
    @MethodSource("notABlock")
    @DisplayName("ATT-041 a text that is not exactly the four lines (a line missing, repeated or unknown, an empty value, a server that is no server name) is KEY_BLOCK_INVALID")
    void aBadBlockIsRefused(String text) {
        assertThatThrownBy(() -> KeyBlock.parse(text)).isInstanceOfSatisfying(AttestationException.class,
                failure -> assertThat(failure.reason()).isEqualTo(AttestationException.Reason.KEY_BLOCK_INVALID));
    }

    @Test
    @DisplayName("ATT-041 a fingerprint line that is not the fingerprint of the block's key is KEY_BLOCK_FINGERPRINT; a key that is no Ed25519 key is KEY_INVALID")
    void theLinesMustBelongTogether() {
        String other = PublicKeys.fingerprint(pair().getPublic());
        assertThatThrownBy(() -> KeyBlock.parse(block("ledger", "claude:a", KEY, other)))
                .isInstanceOfSatisfying(AttestationException.class, failure -> assertThat(failure.reason())
                        .isEqualTo(AttestationException.Reason.KEY_BLOCK_FINGERPRINT));
        assertThatThrownBy(() -> KeyBlock.parse(block("ledger", "claude:a", "AAAA", FINGERPRINT)))
                .isInstanceOfSatisfying(AttestationException.class, failure -> assertThat(failure.reason())
                        .isEqualTo(AttestationException.Reason.KEY_INVALID));
    }
}
