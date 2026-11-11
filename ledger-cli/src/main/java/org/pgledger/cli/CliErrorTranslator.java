package org.pgledger.cli;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** The one place where a refused command becomes an exit status and its texts. */
@Component
public class CliErrorTranslator {

    /** @param action what to do next (the usage text for a usage error), or null */
    public record Report(ExitStatus status, String description, String action) {
    }

    private final CliMessages messages;

    CliErrorTranslator(CliMessages messages) {
        this.messages = messages;
    }

    public Report translate(CliException exception) {
        String description = messages.get(exception.code().messageKey(), exception.arguments().toArray(String[]::new));
        return switch (exception) {
            case UsageException usage -> new Report(ExitStatus.USAGE, description, usageText());
            case FailureException failure -> new Report(ExitStatus.FAILURE, description, null);
        };
    }

    private String usageText() {
        List<String> lines = new ArrayList<>();
        lines.add(messages.get("usage.header"));
        for (CliCommand command : CliCommand.values()) {
            lines.add(messages.get(command.usageKey()));
        }
        return String.join("\n", lines);
    }
}
