package org.pgledger.hook.keys;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.attestation.Sha256Hex;
import org.pgledger.hook.keys.SessionKeyException.Reason;

/**
 * The key file of one (MCP server, author): its name, its content and the key pair in it. This is the file format and
 * nothing else; where the file is, how it is opened and created and how it is protected are the business of
 * {@link SessionKeyStore}, {@link KeyFileReader}, {@link KeyFileWriter} and {@link Protection}.
 *
 * <p>File name: the lower-case hex SHA-256 of {@code <server> LF <author>} plus {@code .key} (portable, and nothing in the
 * names can climb out of the folder; neither value can hold a line feed). Content, UTF-8 text with line feeds:
 * <pre>
 * server=&lt;server&gt;
 * author=&lt;author&gt;
 * private-key=&lt;PKCS#8 DER, standard base64&gt;
 * public-key=&lt;X.509 DER, standard base64&gt;
 * </pre>
 *
 * <p>A file that is read is checked: the server and author lines, both keys parse, both keys are a pair (a fixed message
 * signed with the private key verifies with the public key). The secret bytes that this class handles (the DER of the
 * private key) are zeroed as soon as they are no longer needed.
 *
 * @param server the MCP server, as the host names it
 * @param author the author {@code <vendor>:<session id>}
 */
record SessionKeyFile(String server, String author) {

    private static final String ALGORITHM = "Ed25519";
    private static final String SERVER_PREFIX = "server=";
    private static final String AUTHOR_PREFIX = "author=";
    private static final String PRIVATE_PREFIX = "private-key=";
    private static final String PUBLIC_PREFIX = "public-key=";
    private static final String FILE_SUFFIX = ".key";
    private static final String PROBE = "pg-ledger-mcp/session-key/probe";

    /**
     * @throws SessionKeyException with {@link Reason#AUTHOR_INVALID} if the server or the author is empty or holds a
     *                             line break (the file keeps each on its own line)
     */
    SessionKeyFile {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(author, "author");
        if (server.isEmpty() || hasLineBreak(server) || author.isEmpty() || hasLineBreak(author)) {
            throw new SessionKeyException(Reason.AUTHOR_INVALID);
        }
    }

    private static boolean hasLineBreak(String text) {
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }

    /** The name of the key file inside the folder. */
    String fileName() {
        return Sha256Hex.of((server + "\n" + author).getBytes(StandardCharsets.UTF_8)) + FILE_SUFFIX;
    }

    /** A new key pair for this file. */
    KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key pair generator", failure);
        }
    }

    /** The key of this file for a pair. */
    SessionKey key(KeyPair pair) {
        return new SessionKey(server, author, pair.getPrivate(), pair.getPublic());
    }

    /** The content of the file for a pair; the caller zeroes the returned bytes after writing them. */
    byte[] encode(KeyPair pair) {
        String text = SERVER_PREFIX + server + "\n" + AUTHOR_PREFIX + author + "\n"
                + PRIVATE_PREFIX + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n"
                + PUBLIC_PREFIX + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The key that the content of a file holds, after the checks of the class comment.
     *
     * @throws SessionKeyException {@link Reason#CORRUPT}, {@link Reason#SERVER_MISMATCH}, {@link Reason#AUTHOR_MISMATCH}
     *                             or {@link Reason#KEY_MISMATCH}
     */
    SessionKey parse(byte[] content) {
        List<String> lines = decode(content).lines().toList();
        if (lines.size() != 4) {
            throw new SessionKeyException(Reason.CORRUPT);
        }
        String storedServer = value(lines.get(0), SERVER_PREFIX);
        String storedAuthor = value(lines.get(1), AUTHOR_PREFIX);
        String privateText = value(lines.get(2), PRIVATE_PREFIX);
        String publicText = value(lines.get(3), PUBLIC_PREFIX);
        if (!Objects.equals(server, storedServer)) {
            throw new SessionKeyException(Reason.SERVER_MISMATCH);
        }
        if (!Objects.equals(author, storedAuthor)) {
            throw new SessionKeyException(Reason.AUTHOR_MISMATCH);
        }
        PrivateKey privateKey = privateKey(privateText);
        PublicKey publicKey = publicKey(publicText);
        requirePair(privateKey, publicKey);
        return new SessionKey(server, author, privateKey, publicKey);
    }

    private static String decode(byte[] content) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException failure) {
            throw new SessionKeyException(Reason.CORRUPT, failure);
        }
    }

    private static String value(String line, String prefix) {
        if (!line.startsWith(prefix)) {
            throw new SessionKeyException(Reason.CORRUPT);
        }
        return line.substring(prefix.length());
    }

    private static PrivateKey privateKey(String base64) {
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException notBase64) {
            // the cause is not kept: its message names a character of the key text
            throw new SessionKeyException(Reason.CORRUPT);
        }
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key factory", failure);
        } catch (InvalidKeySpecException failure) {
            throw new SessionKeyException(Reason.CORRUPT, failure);
        } finally {
            Arrays.fill(der, (byte) 0);
        }
    }

    private static PublicKey publicKey(String base64) {
        try {
            return PublicKeys.decode(base64);
        } catch (AttestationException failure) {
            throw new SessionKeyException(Reason.CORRUPT, failure);
        }
    }

    /** A fixed message signed with the private key must verify with the public key: the two belong together. */
    private static void requirePair(PrivateKey privateKey, PublicKey publicKey) {
        byte[] probe = PROBE.getBytes(StandardCharsets.US_ASCII);
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(probe);
            if (!verifier.verify(signature)) {
                throw new SessionKeyException(Reason.KEY_MISMATCH);
            }
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 signature", failure);
        } catch (InvalidKeyException | SignatureException failure) {
            throw new SessionKeyException(Reason.KEY_MISMATCH, failure);
        }
    }
}
