package org.pgledger.seed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The SHA-256 digest of a set of codes: the one way the seed and the database are compared (counts and digest). The codes are
 * put in the order of group and code first, so the digest does not depend on the order they come in, and every field is part of
 * it, so a change of one character, the state or the sort order changes the digest.
 */
public final class SeedDigest {

    /** Between the fields of a code; no text of a code holds it. */
    private static final char FIELD = '\u001f';
    private static final Comparator<SeedCode> ORDER = Comparator.comparing(SeedCode::group).thenComparing(SeedCode::code);

    private SeedDigest() {
    }

    /** The digest as 64 lower-case hex characters. */
    public static String of(Collection<SeedCode> codes) {
        MessageDigest digest = digest();
        List<SeedCode> ordered = codes.stream().sorted(ORDER).toList();
        for (SeedCode code : ordered) {
            String line = String.join(String.valueOf(FIELD), code.group(), code.code(), text(code.nameKo()),
                    text(code.nameEn()), text(code.descriptionKo()), text(code.descriptionEn()),
                    Integer.toString(code.sortOrder()), code.state()) + "\n";
            digest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** An absent text is the empty text. */
    private static String text(String value) {
        return Objects.toString(value, "");
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform (MessageDigest, "Standard Algorithm Names")
            throw new IllegalStateException("the Java platform has no SHA-256", e);
        }
    }
}
