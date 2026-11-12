package org.pgledger.cli.token;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
    /** A second name for the same token: the admin web has to know the token of the agents to refuse it. */
    static final String COPY_TO = "copy-to";

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
        return Set.of(PRINT, COPY_TO);
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
        Optional<SecretName> copy = CliOptions.value(arguments, COPY_TO).map(SecretName::new);
        if (copy.filter(name::equals).isPresent()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        // both names are looked at before anything is written: a store that cannot be used refuses with nothing stored
        boolean present = store.exists(name);
        boolean copyPresent = copy.filter(store::exists).isPresent();
        if (present) {
            output.info(messages.get("token-generate.kept", name.value()));
        } else {
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
        if (!copyPresent) {
            copy.ifPresent(target -> copyTo(name, target));
        }
    }

    /** The same value under a second name, once: a copy that exists is never replaced (a re-run, or a token of 1.x carried over). */
    private void copyTo(SecretName source, SecretName target) {
        char[] value = store.load(source);
        try {
            Path file = store.store(target, value);
            output.info(messages.get("token-generate.copied", source.value(), target.value(), file.toString()));
        } finally {
            Arrays.fill(value, '\0');
        }
    }
}
