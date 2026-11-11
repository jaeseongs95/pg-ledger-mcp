package org.pgledger.cli.token;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.secret.SecretName;
import org.pgledger.cli.secret.SecretStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code token-generate <name> [--print]}: stores a new random token as the secret {@code <name>} of the secret store (the
 * same owner-only file {@code secret-set} writes, owned by the user that runs the command). An existing secret is never
 * replaced: a re-run of the installation keeps every token, and the command says so. The value is shown only with
 * {@code --print}, only when it was created by this run, and then once; it is never written to a log, and a token that was
 * kept is never shown (nothing in the product prints a stored secret). The agents' token has to be known to the owner to be
 * put into the client configuration; the admin token has to be known to sign in; both are printed once at the installation.
 */
@Component
public class TokenGenerateCommand implements Command {

    static final String PRINT = "print";

    private final SecretStore store;
    private final TokenGenerator generator;
    private final CliOutput output;
    private final CliMessages messages;

    TokenGenerateCommand(SecretStore store, TokenGenerator generator, CliOutput output, CliMessages messages) {
        this.store = store;
        this.generator = generator;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.TOKEN_GENERATE;
    }

    @Override
    public Set<String> options() {
        return Set.of(PRINT);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        boolean print = CliOptions.flag(arguments, PRINT);
        SecretName name = new SecretName(operands.getFirst());
        if (store.exists(name)) {
            output.info(messages.get("token-generate.kept", name.value()));
            return;
        }
        char[] token = generator.generate();
        try {
            Path file = store.store(name, token);
            output.info(messages.get("token-generate.created", name.value(), file.toString()));
            if (print) {
                output.info(messages.get("token-generate.printed", name.value(), String.valueOf(token)));
            }
        } finally {
            Arrays.fill(token, '\0');
        }
    }
}
