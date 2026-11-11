package org.pgledger.cli.secret;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.springframework.stereotype.Component;

/** {@code secret-set <name>}: the value is typed on the terminal and never appears in arguments, output or logs. */
@Component
public class SecretSetCommand {

    private final SecretStore store;
    private final SecretInput input;
    private final CliOutput output;
    private final CliMessages messages;

    SecretSetCommand(SecretStore store, SecretInput input, CliOutput output, CliMessages messages) {
        this.store = store;
        this.input = input;
        this.output = output;
        this.messages = messages;
    }

    public void run(List<String> operands) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        SecretName name = new SecretName(operands.getFirst());
        store.check(name);
        char[] value = input.read(messages.get("secret-set.prompt", name.value()));
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
