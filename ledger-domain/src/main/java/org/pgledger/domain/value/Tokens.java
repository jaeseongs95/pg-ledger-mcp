package org.pgledger.domain.value;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.pgledger.domain.error.ValidationException;

/** Parses wire tokens of closed sets; an unknown token is a validation failure that lists the allowed ones. */
public final class Tokens {

    private Tokens() {
    }

    public static <T extends Enum<T> & Token> T parse(String path, String raw, T[] values) {
        return find(raw, values).orElseThrow(() -> unknown(path, raw, values));
    }

    /** The constant of a token, or none when the token is not one of them; nothing is thrown. */
    public static <T extends Enum<T> & Token> Optional<T> find(String raw, T[] values) {
        return raw == null ? Optional.empty() : Arrays.stream(values).filter(value -> value.token().equals(raw))
                .findFirst();
    }

    /** The failure of a token that is not one of the constants (parse throws it; a gate may carry it as a value). */
    public static <T extends Enum<T> & Token> ValidationException unknown(String path, String raw, T[] values) {
        return ValidationException.invalid(path, "unknown value " + raw, allowed(values));
    }

    public static <T extends Enum<T> & Token> List<String> allowed(T[] values) {
        return Arrays.stream(values).map(Token::token).toList();
    }
}
