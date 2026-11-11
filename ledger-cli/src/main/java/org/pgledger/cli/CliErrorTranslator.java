package org.pgledger.cli;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The one place where a refused command becomes an exit status and a message. */
@Component
public class CliErrorTranslator {

    private static final Logger log = LoggerFactory.getLogger(CliErrorTranslator.class);

    public record Outcome(ExitStatus status, String message) {
    }

    private final CliMessages messages;

    CliErrorTranslator(CliMessages messages) {
        this.messages = messages;
    }

    public Outcome translate(CliException exception) {
        log.debug("Command refused: {}", exception.code(), exception);
        String message = messages.get(exception.code().messageKey(), exception.arguments().toArray(String[]::new));
        return switch (exception) {
            case UsageException usage -> new Outcome(ExitStatus.USAGE, message + System.lineSeparator() + usageText());
            case FailureException failure -> new Outcome(ExitStatus.FAILURE, message);
        };
    }

    private String usageText() {
        List<String> lines = new ArrayList<>();
        lines.add(messages.get("usage.header"));
        for (CliCommand command : CliCommand.values()) {
            lines.add(messages.get(command.usageKey()));
        }
        return String.join(System.lineSeparator(), lines);
    }
}
