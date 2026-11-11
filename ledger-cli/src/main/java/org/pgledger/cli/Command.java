package org.pgledger.cli;

import java.util.List;

/** One command of the tool; the runner finds every implementation as a bean and dispatches by {@link #name()}. */
public interface Command {

    CliCommand name();

    /** @param operands the words after the command name; a refusal is thrown as a {@link CliException} */
    void run(List<String> operands);
}
