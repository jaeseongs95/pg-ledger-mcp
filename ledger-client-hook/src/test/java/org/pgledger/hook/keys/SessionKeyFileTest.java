package org.pgledger.hook.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.hook.keys.SessionKeyException.Reason;

/**
 * HK-044 (file format part): what {@link SessionKeyFile} writes and reads, without a file system. The key store tests of
 * HK-024 .. HK-040 cover the same behaviour through real files; this class pins each refusal reason of the format.
 */
class SessionKeyFileTest {

    private static final String SERVER = "ledger";
    private static final String AUTHOR = "claude:session-1";
    private static final SessionKeyFile FILE = new SessionKeyFile(SERVER, AUTHOR);

    private static String text(byte[] content) {
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(content)).toString();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String content(String server, String author, String privateKey, String publicKey) {
        return "server=" + server + "\nauthor=" + author + "\nprivate-key=" + privateKey + "\npublic-key=" + publicKey + "\n";
    }

    /** The refusal of an action that must be refused by the key file format. */
    private static SessionKeyException refusal(Runnable action) {
        try {
            action.run();
        } catch (SessionKeyException refused) {
            return refused;
        }
        throw new AssertionError("the key file was accepted");
    }

    private static Reason parseReason(byte[] content) {
        return refusal(() -> FILE.parse(content)).reason();
    }

    @Test
    @DisplayName("HK-044 the file name is the lower-case hex SHA-256 of <server> LF <author> plus .key")
    void fileName() {
        assertThat(FILE.fileName()).isEqualTo(sha256Hex(SERVER + "\n" + AUTHOR) + ".key").matches("[0-9a-f]{64}\\.key");
        assertThat(new SessionKeyFile("other", AUTHOR).fileName()).isNotEqualTo(FILE.fileName());
        assertThat(new SessionKeyFile(SERVER, "claude:other").fileName()).isNotEqualTo(FILE.fileName());
    }

    @Test
    @DisplayName("HK-044 the content is four UTF-8 lines in this order: server=, author=, private-key= (PKCS#8 base64), public-key= (X.509 base64)")
    void encodedContent() {
        KeyPair pair = FILE.newKeyPair();
        String[] lines = text(FILE.encode(pair)).split("\n", -1);
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).isEqualTo("server=" + SERVER);
        assertThat(lines[1]).isEqualTo("author=" + AUTHOR);
        assertThat(lines[2]).isEqualTo("private-key=" + base64(pair.getPrivate().getEncoded()));
        assertThat(lines[3]).isEqualTo("public-key=" + base64(pair.getPublic().getEncoded()));
        assertThat(lines[4]).as("ends with a line feed").isEmpty();
    }

    @Test
    @DisplayName("HK-044 encode then parse gives the same key: same public key, server and author")
    void roundTrip() {
        KeyPair pair = FILE.newKeyPair();
        SessionKey key = FILE.parse(FILE.encode(pair));
        assertThat(key.server()).isEqualTo(SERVER);
        assertThat(key.author()).isEqualTo(AUTHOR);
        assertThat(PublicKeys.encode(key.publicKey())).isEqualTo(PublicKeys.encode(pair.getPublic()));
        assertThat(PublicKeys.encode(FILE.key(pair).publicKey())).isEqualTo(PublicKeys.encode(pair.getPublic()));
    }

    @Test
    @DisplayName("HK-044 a server or author that is empty or holds a line break is AUTHOR_INVALID, before any file is touched")
    void authorInvalid() {
        for (String bad : new String[] {"", "a\nb", "a\rb"}) {
            assertThat(refusal(() -> new SessionKeyFile(bad, AUTHOR)).reason()).as("server " + bad)
                    .isEqualTo(Reason.AUTHOR_INVALID);
            assertThat(refusal(() -> new SessionKeyFile(SERVER, bad)).reason()).as("author " + bad)
                    .isEqualTo(Reason.AUTHOR_INVALID);
        }
        assertThatThrownBy(() -> new SessionKeyFile(null, AUTHOR)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SessionKeyFile(SERVER, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("HK-044 a file of the wrong shape is CORRUPT: another number of lines, a wrong prefix, text that is not UTF-8, an empty file")
    void corruptShapes() {
        String good = text(FILE.encode(FILE.newKeyPair()));
        assertThat(parseReason(bytes(good + "extra=1\n"))).isEqualTo(Reason.CORRUPT);
        assertThat(parseReason(bytes(good.substring(0, good.lastIndexOf("public-key="))))).isEqualTo(Reason.CORRUPT);
        assertThat(parseReason(bytes(good.replace("server=", "srv=")))).isEqualTo(Reason.CORRUPT);
        assertThat(parseReason(bytes(good.replace("public-key=", "key=")))).isEqualTo(Reason.CORRUPT);
        assertThat(parseReason(new byte[0])).isEqualTo(Reason.CORRUPT);
        assertThat(parseReason(new byte[] {(byte) 0xC3, (byte) 0x28, '\n', '\n', '\n', '\n'})).isEqualTo(Reason.CORRUPT);
    }

    @Test
    @DisplayName("HK-044 a file of another server is SERVER_MISMATCH and a file of another author is AUTHOR_MISMATCH")
    void mismatches() {
        byte[] content = FILE.encode(FILE.newKeyPair());
        assertThat(refusal(() -> new SessionKeyFile("other", AUTHOR).parse(content)).reason())
                .isEqualTo(Reason.SERVER_MISMATCH);
        assertThat(refusal(() -> new SessionKeyFile(SERVER, "claude:other").parse(content)).reason())
                .isEqualTo(Reason.AUTHOR_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64 !!", "AAAA"})
    @DisplayName("HK-044 a private key text that is not base64 or not a key is CORRUPT; for the bad base64 the cause is dropped (its message names a character of the key text)")
    void badPrivateKey(String privateText) {
        String file = content(SERVER, AUTHOR, privateText, base64(FILE.newKeyPair().getPublic().getEncoded()));
        SessionKeyException refused = refusal(() -> FILE.parse(bytes(file)));
        assertThat(refused.reason()).isEqualTo(Reason.CORRUPT);
        if (privateText.contains("!")) {
            assertThat(refused.getCause()).isNull();
        }
    }

    @Test
    @DisplayName("HK-044 a public key that is not a key is CORRUPT; a private and a public key of two pairs are KEY_MISMATCH")
    void badPublicKeyAndPairs() {
        KeyPair pair = FILE.newKeyPair();
        KeyPair another = FILE.newKeyPair();
        String privateText = base64(pair.getPrivate().getEncoded());
        assertThat(parseReason(bytes(content(SERVER, AUTHOR, privateText, "AAAA")))).isEqualTo(Reason.CORRUPT);
        assertThat(parseReason(bytes(content(SERVER, AUTHOR, privateText, base64(another.getPublic().getEncoded())))))
                .isEqualTo(Reason.KEY_MISMATCH);
    }

    @Test
    @DisplayName("HK-044 the private key never appears in the text of the key, nor in the exception of a refused file")
    void privateKeyStaysInside() {
        KeyPair pair = FILE.newKeyPair();
        String privateText = base64(pair.getPrivate().getEncoded());
        assertThat(FILE.key(pair).toString()).doesNotContain(privateText);
        SessionKeyException refused = refusal(() -> FILE.parse(bytes(content(SERVER, AUTHOR, privateText, "AAAA"))));
        assertThat(refused.getMessage()).doesNotContain(privateText);
    }
}
