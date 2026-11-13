package org.pgledger.cli;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Dispatches the first non-option argument to its {@link Command}. An option that is neither a Spring property (a dot in
 * its name), a Boot flag nor declared by the command is refused (UNKNOWN_OPTION), so a mistyped option never passes
 * silently. A refusal is thrown as a {@link CliException}; Boot maps it to the exit code ({@link CliExitCodeMapper}) and
 * reports it ({@link CliFailureAnalyzer}).
 */
@Component
public class CliRunner implements ApplicationRunner {

    /**
     * The two options of Spring Boot itself that have no dot ({@code --debug}, {@code --trace}); see
     * https://docs.spring.io/spring-boot/reference/features/logging.html (console output).
     */
    private static final Set<String> BOOT_FLAGS = Set.of("debug", "trace");

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
        Command target = commands.get(command);
        try {
            for (String option : arguments.getOptionNames()) {
                if (!option.contains(".") && !BOOT_FLAGS.contains(option) && !target.options().contains(option)) {
                    throw new UsageException(ErrorCode.UNKNOWN_OPTION);
                }
            }
            target.run(operands.subList(1, operands.size()), arguments);
        } catch (UsageException refusal) {
            // from here on the command is known: the report shows its usage line only (no word and an unknown word, above,
            // are the only refusals without a command: there the user has to choose one, so the whole list is shown)
            throw refusal.forCommand(command);
        }
    }
}
