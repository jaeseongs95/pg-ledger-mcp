package org.pgledger.cli;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Dispatches the first non-option argument to its {@link Command}. A refusal is thrown as a {@link CliException}; Boot maps
 * it to the exit code ({@link CliExitCodeMapper}) and reports it ({@link CliFailureAnalyzer}).
 */
@Component
public class CliRunner implements ApplicationRunner {

    private final Map<CliCommand, Command> commands = new EnumMap<>(CliCommand.class);

    CliRunner(List<Command> available) {
        for (Command command : available) {
            commands.put(command.name(), command);
        }
        for (CliCommand known : CliCommand.values()) {
            if (!commands.containsKey(known)) {
                throw new IllegalStateException("No Command bean for " + known);
            }
        }
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> operands = arguments.getNonOptionArgs();
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_COMMAND);
        }
        CliCommand command = CliCommand.parse(operands.getFirst())
                .orElseThrow(() -> new UsageException(ErrorCode.UNKNOWN_COMMAND));
        commands.get(command).run(operands.subList(1, operands.size()));
    }
}
