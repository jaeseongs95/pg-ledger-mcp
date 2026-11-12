package org.pgledger.cli;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.ApplicationArguments;

/**
 * Reads the options of a command in the form Spring Boot's own parser understands, {@code --name=value}
 * (https://docs.spring.io/spring-boot/reference/features/external-config.html; the values come from
 * {@code getOptionValues} of
 * https://docs.spring.io/spring-boot/api/java/org/springframework/boot/ApplicationArguments.html). A value is never
 * written as a second word, because Boot reads that as a non-option argument. The runner has already refused every option
 * a command does not declare.
 */
public final class CliOptions {

    private CliOptions() {
    }

    /**
     * The value of {@code --name=<value>}, empty when the option is absent.
     *
     * @throws UsageException MISSING_OPTION_VALUE for {@code --name} or {@code --name=} (no value), TOO_MANY_ARGUMENTS
     *         for the option given twice
     */
    public static Optional<String> value(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null) {
            return Optional.empty();
        }
        if (values.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        if (values.isEmpty() || values.getFirst().isBlank()) {
            throw new UsageException(ErrorCode.MISSING_OPTION_VALUE);
        }
        return Optional.of(values.getFirst());
    }

    /**
     * As {@link #value}, but an absent option is null. This is the one place where an option that was not given becomes null,
     * for the commands of the application services whose records say "not given" with a null component
     * ({@code CodeCommand}, {@code GrantCommand}); everything of this tool that is not such a record keeps the
     * {@link Optional}.
     */
    public static String valueOrNull(ApplicationArguments arguments, String name) {
        return value(arguments, name).orElse(null);
    }

    /**
     * As {@link #valueOrNull}, for a whole number (white space around it is allowed).
     *
     * @throws UsageException INVALID_OPTION_VALUE when the value is not a number (the {@link NumberFormatException} is the
     *         cause; the message names no value)
     */
    public static Integer integerOrNull(ApplicationArguments arguments, String name) {
        return value(arguments, name).map(text -> {
            try {
                return Integer.valueOf(text.strip());
            } catch (NumberFormatException notANumber) {
                throw new UsageException(ErrorCode.INVALID_OPTION_VALUE, notANumber);
            }
        }).orElse(null);
    }

    /** As {@link #value}, but the option is required: its absence is MISSING_ARGUMENT. */
    public static String required(ApplicationArguments arguments, String name) {
        return value(arguments, name).orElseThrow(() -> new UsageException(ErrorCode.MISSING_ARGUMENT));
    }

    /**
     * Whether the flag {@code --name} is given; a flag has no value.
     *
     * @throws UsageException INVALID_OPTION_VALUE for {@code --name=<value>}
     */
    public static boolean flag(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null) {
            return false;
        }
        if (!values.isEmpty()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        return true;
    }
}
