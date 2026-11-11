package org.pgledger.attestation;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The block that the hook command {@code session-key <server> <author>} prints for the owner: four lines
 * {@code server: <server>}, {@code author: <author>}, {@code public-key: <public key>} and
 * {@code fingerprint: <fingerprint>} (the public key is X.509 DER in standard base64, the fingerprint its SHA-256 in 64
 * lower-case hex). The owner may paste the whole block where the bare public key is asked for, so that the screen can
 * show the server the key was made for next to the fingerprint, and the author of the block can be checked against the
 * author field. Lines may come in any order, blank lines and white space around a line are ignored, every one of the
 * four lines must be there exactly once and nothing else may be there.
 *
 * <p>The fingerprint line is checked against the key of the block: a block whose line does not belong to its key was
 * changed, or made up. It is not a substitute for the owner's comparison with what the session printed; the block comes
 * from the same place as the key.
 *
 * <p>Plain JDK: {@link String#lines()} splits the text (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html#lines()).
 *
 * @param server      the MCP server name as the host names it ({@code A-Za-z0-9._-}, at most 100 characters)
 * @param author      the author of the session
 * @param publicKey   the public key in its canonical form (X.509 DER, standard base64)
 * @param fingerprint the fingerprint of the key, 64 lower-case hex
 */
public record KeyBlock(String server, String author, String publicKey, String fingerprint) {

    private static final String SERVER_LINE = "server";
    private static final String AUTHOR_LINE = "author";
    private static final String PUBLIC_KEY_LINE = "public-key";
    private static final String FINGERPRINT_LINE = "fingerprint";
    private static final Set<String> LINES = Set.of(SERVER_LINE, AUTHOR_LINE, PUBLIC_KEY_LINE, FINGERPRINT_LINE);
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    /** Whether the text is meant as a block: one of its lines starts with {@code public-key:}. */
    public static boolean isBlock(String text) {
        return text != null && text.lines().anyMatch(line -> line.strip().startsWith(PUBLIC_KEY_LINE + ":"));
    }

    /**
     * Reads a block.
     *
     * @throws AttestationException KEY_BLOCK_INVALID when the text is not a block, KEY_INVALID when its public key is not
     *         an Ed25519 public key, KEY_BLOCK_FINGERPRINT when its fingerprint line is not the fingerprint of its key
     */
    public static KeyBlock parse(String text) {
        if (text == null) {
            throw new AttestationException(AttestationException.Reason.KEY_BLOCK_INVALID);
        }
        Map<String, String> values = new HashMap<>();
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            String name = colon < 0 ? "" : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1).strip();
            if (!LINES.contains(name) || value.isEmpty() || values.putIfAbsent(name, value) != null) {
                throw new AttestationException(AttestationException.Reason.KEY_BLOCK_INVALID);
            }
        }
        if (values.size() != LINES.size() || !SERVER_NAME.matcher(values.get(SERVER_LINE)).matches()) {
            throw new AttestationException(AttestationException.Reason.KEY_BLOCK_INVALID);
        }
        PublicKey key = PublicKeys.decode(values.get(PUBLIC_KEY_LINE));
        String fingerprint = PublicKeys.fingerprint(key);
        if (!Objects.equals(fingerprint, values.get(FINGERPRINT_LINE).toLowerCase(Locale.ROOT))) {
            throw new AttestationException(AttestationException.Reason.KEY_BLOCK_FINGERPRINT);
        }
        return new KeyBlock(values.get(SERVER_LINE), values.get(AUTHOR_LINE), PublicKeys.encode(key), fingerprint);
    }
}
