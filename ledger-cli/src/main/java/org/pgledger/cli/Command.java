package org.pgledger.cli;

import java.util.List;
import java.util.Set;

import org.springframework.boot.ApplicationArguments;

/** One command of the tool; the runner finds every implementation as a bean and dispatches by {@link #name()}. */
public interface Command {

    CliCommand name();

    /**
     * The names of the options of this command ({@code --name=value}, a single word without a dot). Every other option
     * of that form is refused by the runner; an option with a dot in its name is a Spring property and always passes.
     */
    default Set<String> options() {
        return Set.of();
    }

    /**
     * @param operands the words after the command name; a refusal is thrown as a {@link CliException}
     * @param arguments the whole command line as Spring Boot parsed it (the options are read from here)
     */
    void run(List<String> operands, ApplicationArguments arguments);
}
