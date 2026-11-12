package org.pgledger.cli.token;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Makes the bearer tokens of the product (the agents' token of the MCP server, the admin token): 32 random bytes from a
 * {@link SecureRandom} (256 bits, the strength of the key of an HMAC-SHA-256), written in the URL-safe base64 alphabet without
 * padding, which is 43 characters of {@code A-Z a-z 0-9 - _}: no character that needs quoting in a header, a file name or a
 * command line, and well over the 16 characters the servers require. The random source is given by the caller; the
 * product uses the platform's default {@code SecureRandom} (on Linux a CSPRNG seeded by the kernel). Reference:
 * https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/SecureRandom.html
 */
@Component
public final class TokenGenerator {

    /** The random bytes of a token. */
    static final int BYTES = 32;

    private final SecureRandom random;

    public TokenGenerator(SecureRandom random) {
        this.random = random;
    }

    /** A new token; the caller wipes the returned array when it has stored it. */
    public char[] generate() {
        byte[] bytes = new byte[BYTES];
        try {
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
