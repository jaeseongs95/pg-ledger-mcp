package org.pgledger.cli;

import java.util.List;
import java.util.function.Consumer;

import org.pgledger.cli.secret.SecretSetCommand;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/** Dispatches the first non-option argument to its command and turns a refusal into the process exit code. */
@Component
public class CliRunner implements ApplicationRunner, ExitCodeGenerator {

    private final SecretSetCommand secretSet;
    private final CliErrorTranslator translator;
    private final CliOutput output;
    private ExitStatus status = ExitStatus.SUCCESS;

    CliRunner(SecretSetCommand secretSet, CliErrorTranslator translator, CliOutput output) {
        this.secretSet = secretSet;
        this.translator = translator;
        this.output = output;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            dispatch(arguments.getNonOptionArgs());
        } catch (CliException exception) {
            CliErrorTranslator.Outcome outcome = translator.translate(exception);
            status = outcome.status();
            output.error(outcome.message());
        }
    }

    private void dispatch(List<String> operands) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_COMMAND);
        }
        CliCommand command = CliCommand.parse(operands.getFirst())
                .orElseThrow(() -> new UsageException(ErrorCode.UNKNOWN_COMMAND));
        Consumer<List<String>> handler = switch (command) {
            case SECRET_SET -> secretSet::run;
        };
        handler.accept(operands.subList(1, operands.size()));
    }

    @Override
    public int getExitCode() {
        return status.code();
    }
}
