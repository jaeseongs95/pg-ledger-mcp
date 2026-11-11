package org.pgledger.cli.key;

import java.security.PublicKey;
import java.util.List;
import java.util.Locale;
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
import org.springframework.stereotype.Component;

/**
 * {@code key-register <author> <public-key> <fingerprint>}: the owner binds the public key that a session printed
 * (the hook command {@code session-key}) to its author. The third operand is the fingerprint the session showed: the
 * command refuses when the key does not have it, so the key is registered only after the owner compared the two. This is
 * a command of the owner's tool; no MCP tool registers keys.
 */
@Component
public class KeyRegisterCommand implements Command {

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
    public void run(List<String> operands) {
        if (operands.size() < 3) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 3) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
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
            throw new FailureException(ErrorCode.REGISTRY_NOT_CONFIGURED);
        }
        target.register(author, PublicKeys.encode(key));
        output.info(messages.get("key-register.registered", author, fingerprint));
    }
}
