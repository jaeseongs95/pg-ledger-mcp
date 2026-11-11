package org.pgledger.domain.value;

import java.util.Arrays;
import java.util.List;
import org.pgledger.domain.error.ValidationException;

/** Parses wire tokens of closed sets; an unknown token is a validation failure that lists the allowed ones. */
public final class Tokens {

    private Tokens() {
    }

    public static <T extends Enum<T> & Token> T parse(String path, String raw, T[] values) {
        if (raw != null) {
            for (T value : values) {
                if (value.token().equals(raw)) {
                    return value;
                }
            }
        }
        throw ValidationException.invalid(path, "unknown value " + raw, allowed(values));
    }

    public static <T extends Enum<T> & Token> List<String> allowed(T[] values) {
        return Arrays.stream(values).map(Token::token).toList();
    }
}
