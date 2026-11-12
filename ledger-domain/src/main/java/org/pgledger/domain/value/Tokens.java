package org.pgledger.domain.value;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * Parses the texts of closed sets, the one place that does it: by wire token (the constants that implement
 * {@link Token}) or by constant name (the sets that have no wire token of their own). An unknown text is a validation
 * failure that lists the allowed ones.
 */
public final class Tokens {

    private Tokens() {
    }

    public static <T extends Enum<T> & Token> T parse(String path, String raw, T[] values) {
        return find(raw, values).orElseThrow(() -> unknown(path, raw, values));
    }

    /** The constant of a token, or none when the token is not one of them; nothing is thrown. */
    public static <T extends Enum<T> & Token> Optional<T> find(String raw, T[] values) {
        return raw == null ? Optional.empty() : Arrays.stream(values).filter(value -> Objects.equals(value.token(), raw))
                .findFirst();
    }

    /** The failure of a token that is not one of the constants (parse throws it; a gate may carry it as a value). */
    public static <T extends Enum<T> & Token> ValidationException unknown(String path, String raw, T[] values) {
        return unknownBy(path, raw, values, Token::token);
    }

    public static <T extends Enum<T> & Token> List<String> allowed(T[] values) {
        return Arrays.stream(values).map(Token::token).toList();
    }

    /** The constant of a constant name; the match is exact. */
    public static <T extends Enum<T>> T parseName(String path, String raw, T[] values) {
        return Arrays.stream(values).filter(value -> Objects.equals(value.name(), raw)).findFirst()
                .orElseThrow(() -> unknownBy(path, raw, values, Enum::name));
    }

    private static <T> ValidationException unknownBy(String path, String raw, T[] values,
            Function<T, String> text) {
        return ValidationException.invalid(path, ValidationReason.UNKNOWN_VALUE,
                Arrays.stream(values).map(text).toList(), String.valueOf(raw));
    }
}
