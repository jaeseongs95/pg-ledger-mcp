package org.pgledger.application.read;

import java.util.Arrays;
import java.util.List;
import org.pgledger.domain.error.ValidationException;

/** Reads the constant names of the closed sets that have no wire token of their own; the match is exact. */
public final class EnumParser {

    private EnumParser() {
    }

    public static <T extends Enum<T>> T parse(String path, String raw, T[] values) {
        for (T value : values) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        List<String> allowed = Arrays.stream(values).map(Enum::name).toList();
        throw ValidationException.invalid(path, "unknown value " + raw, allowed);
    }
}
