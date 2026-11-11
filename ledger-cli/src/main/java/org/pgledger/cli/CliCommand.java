package org.pgledger.cli;

import java.util.Objects;
import java.util.Optional;

/** The commands this tool offers. Only implemented commands are listed here. */
public enum CliCommand {

    SECRET_SET("secret-set");

    private final String cliName;

    CliCommand(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    /** Message key of this command line in the usage text. */
    public String usageKey() {
        return "usage." + cliName;
    }

    /** The one place where a command line word becomes a command. */
    public static Optional<CliCommand> parse(String word) {
        for (CliCommand command : values()) {
            if (Objects.equals(command.cliName, word)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }
}
