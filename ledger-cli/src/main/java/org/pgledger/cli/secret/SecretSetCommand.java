package org.pgledger.cli.secret;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.pgledger.cli.CliOptions;

import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.Command;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code secret-set <name> [--stdin] [--if-absent]}: the value is typed on the terminal (or, with {@code --stdin}, taken from
 * the first line of standard input) and never appears in arguments, output or logs; with {@code --if-absent} an existing
 * secret is kept and nothing is asked.
 */
@Component
public class SecretSetCommand implements Command {

    static final String STDIN = "stdin";
    static final String IF_ABSENT = "if-absent";

    private final SecretStore store;
    private final SecretInput input;
    private final StandardInput standardInput;
    private final CliOutput output;
    private final CliMessages messages;

    SecretSetCommand(SecretStore store, SecretInput input, StandardInput standardInput, CliOutput output,
            CliMessages messages) {
        this.store = store;
        this.input = input;
        this.standardInput = standardInput;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.SECRET_SET;
    }

    @Override
    public Set<String> options() {
        return Set.of(STDIN, IF_ABSENT);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        boolean fromStandardInput = CliOptions.flag(arguments, STDIN);
        SecretName name = new SecretName(operands.getFirst());
        store.check(name);
        // --if-absent: an installation that is run again asks for nothing it has (the secret is never replaced by it)
        if (CliOptions.flag(arguments, IF_ABSENT) && store.exists(name)) {
            output.info(messages.get("secret-set.kept", name.value()));
            return;
        }
        // --stdin is the one opt-in way to give the secret without a terminal: its first line, nothing from an argument.
        // Made here, not injected: the source is chosen per command line (the flag), both are SecretInput implementations
        // (so a bean of that type would be ambiguous), and the stdin one only wraps the stream of this run.
        SecretInput source = fromStandardInput ? new StdinSecretInput(standardInput.stream()) : input;
        char[] value = source.read(messages.get("secret-set.prompt", name.value()));
        try {
            if (value == null || value.length == 0) {
                throw new FailureException(ErrorCode.EMPTY_SECRET);
            }
            Path file = store.store(name, value);
            output.info(messages.get("secret-set.stored", name.value(), file.toString()));
        } finally {
            if (value != null) {
                Arrays.fill(value, '\0');
            }
        }
    }
}
