package org.pgledger.attestation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 (FIPS 180-4) of bytes as 64 lower-case hex characters: the one place of this module that digests. The domain
 * module has its own {@code Sha256} value, because the client hook jar depends on this module only and the domain needs
 * a value type with its format check.
 */
public final class Sha256Hex {

    private static final String ALGORITHM = "SHA-256";

    private Sha256Hex() {
    }

    /** The digest of {@code bytes}, 64 lower-case hex characters. */
    public static String of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", failure);
        }
    }
}
