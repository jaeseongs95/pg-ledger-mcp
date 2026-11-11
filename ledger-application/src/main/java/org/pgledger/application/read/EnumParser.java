package org.pgledger.application.read;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/** Reads the constant names of the closed sets that have no wire token of their own; the match is exact. */
public final class EnumParser {

    private EnumParser() {
    }

    public static <T extends Enum<T>> T parse(String path, String raw, T[] values) {
        for (T value : values) {
            if (Objects.equals(value.name(), raw)) {
                return value;
            }
        }
        List<String> allowed = Arrays.stream(values).map(Enum::name).toList();
        throw ValidationException.invalid(path, ValidationReason.UNKNOWN_VALUE, allowed, String.valueOf(raw));
    }
}
