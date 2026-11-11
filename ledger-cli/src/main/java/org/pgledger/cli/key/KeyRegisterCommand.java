package org.pgledger.cli.key;

import java.security.PublicKey;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code key-register <author> <public-key> <fingerprint> [--replace=<current-fingerprint>]}: the owner binds the public
 * key that a session printed (the hook command {@code session-key}) to its author. The third operand is the fingerprint
 * the session showed: the command refuses when the key does not have it, so the key is registered only after the owner
 * compared the two. An author that already has another live key is refused: a key is never replaced silently, because
 * whoever gets the owner to register a key under a known author name would inherit that author's grants. The refusal
 * shows the fingerprint and the registration time of the live key; the owner repeats the command with
 * {@code --replace=<that fingerprint>} to replace it. This is a command of the owner's tool; no MCP tool registers keys.
 *
 * <p>The option is written with an equals sign, the form Spring Boot's own command line parser understands
 * ({@code --name=value}, see https://docs.spring.io/spring-boot/reference/features/external-config.html). An option
 * written without {@code =value} has no value, and a word after it is a non-option argument, that is one more operand
 * (https://docs.spring.io/spring-boot/api/java/org/springframework/boot/ApplicationArguments.html, {@code getOptionValues}
 * and {@code getNonOptionArgs}).
 */
@Component
public class KeyRegisterCommand implements Command {

    static final String REPLACE = "replace";

    private final ObjectProvider<SessionKeyRegistry> registry;
    private final CliOutput output;
    private final CliMessages messages;

    KeyRegisterCommand(ObjectProvider<SessionKeyRegistry> registry, CliOutput output, CliMessages messages) {
        this.registry = registry;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.KEY_REGISTER;
    }

    @Override
    public Set<String> options() {
        return Set.of(REPLACE);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.size() < 3) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 3) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        Optional<String> replaced = replaceOption(arguments);
        String author = operands.get(0);
        PublicKey key;
        try {
            key = PublicKeys.decode(operands.get(1));
        } catch (AttestationException invalid) {
            throw new FailureException(ErrorCode.INVALID_PUBLIC_KEY, invalid);
        }
        String fingerprint = PublicKeys.fingerprint(key);
        if (!fingerprint.equals(operands.get(2).strip().toLowerCase(Locale.ROOT))) {
            throw new FailureException(ErrorCode.FINGERPRINT_MISMATCH, fingerprint);
        }
        SessionKeyRegistry target = registry.getIfAvailable();
        if (target == null) {
            throw new FailureException(ErrorCode.DRIVER_NOT_CONFIGURED);
        }
        if (replaced.isPresent()) {
            target.registerReplacing(author, PublicKeys.encode(key), replaced.get());
            output.info(messages.get("key-register.replaced", author, fingerprint, replaced.get()));
        } else {
            target.register(author, PublicKeys.encode(key));
            output.info(messages.get("key-register.registered", author, fingerprint));
        }
    }

    /** The value of {@code --replace=<fingerprint>}, empty when the option is absent. */
    private static Optional<String> replaceOption(ApplicationArguments arguments) {
        List<String> values = arguments.getOptionValues(REPLACE);
        if (values == null) {
            return Optional.empty();
        }
        if (values.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        if (values.isEmpty() || values.getFirst().isBlank()) {
            throw new UsageException(ErrorCode.MISSING_OPTION_VALUE);
        }
        return Optional.of(values.getFirst().strip().toLowerCase(Locale.ROOT));
    }
}
