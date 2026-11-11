package org.pgledger.domain.value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/** A SHA-256 digest as 64 lower-case hex characters (evidence, payload and file digests). */
public record Sha256(String hex) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public Sha256 {
        if (hex == null || !FORMAT.matcher(hex).matches()) {
            throw ValidationException.invalid("sha256", ValidationReason.MUST_BE_LOWER_CASE_HEX, "64");
        }
    }

    public static Sha256 of(String hex) {
        return new Sha256(hex);
    }

    public static Sha256 ofBytes(byte[] bytes) {
        try {
            return new Sha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    public static Sha256 ofText(String text) {
        return ofBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
