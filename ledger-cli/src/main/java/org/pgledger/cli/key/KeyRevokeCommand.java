package org.pgledger.cli.key;

import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * {@code key-revoke <author> --fingerprint=<fingerprint>}: the owner closes the live public key of an author. The
 * fingerprint is the confirmation: the command closes the key only when the key that is live now has that fingerprint (the
 * one the owner read from the admin web's table of keys, or from the registration), so a key that was replaced meanwhile
 * is not closed by a command that was meant for the old one. The author keeps its grants but cannot sign until another key
 * is registered. The operation is recorded in the owner's audit trail as {@code cli:<process user>}. This is a command of
 * the owner's tool; no MCP tool revokes keys.
 *
 * <p>The option is written with an equals sign, the form Spring Boot's own command line parser understands
 * ({@code --name=value}, see https://docs.spring.io/spring-boot/reference/features/external-config.html; the values are
 * read with {@code getOptionValues} of
 * https://docs.spring.io/spring-boot/api/java/org/springframework/boot/ApplicationArguments.html).
 */
@Component
public class KeyRevokeCommand implements Command {

    static final String FINGERPRINT = "fingerprint";

    private final ObjectProvider<SessionKeyRegistry> registry;
    private final CliOutput output;
    private final CliMessages messages;

    KeyRevokeCommand(ObjectProvider<SessionKeyRegistry> registry, CliOutput output, CliMessages messages) {
        this.registry = registry;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.KEY_REVOKE;
    }

    @Override
    public Set<String> options() {
        return Set.of(FINGERPRINT);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        String fingerprint = fingerprintOption(arguments);
        SessionKeyRegistry target = registry.getIfAvailable();
        if (target == null) {
            throw new FailureException(ErrorCode.DRIVER_NOT_CONFIGURED);
        }
        String author = operands.getFirst();
        target.revoke(author, fingerprint);
        output.info(messages.get("key-revoke.revoked", author, fingerprint));
    }

    /** The value of {@code --fingerprint=<fingerprint>}; the option is required, because it is the confirmation. */
    private static String fingerprintOption(ApplicationArguments arguments) {
        List<String> values = arguments.getOptionValues(FINGERPRINT);
        if (values == null) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (values.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        if (values.isEmpty() || values.getFirst().isBlank()) {
            throw new UsageException(ErrorCode.MISSING_OPTION_VALUE);
        }
        return values.getFirst().strip().toLowerCase(Locale.ROOT);
    }
}
