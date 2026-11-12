package org.pgledger.cli.secret;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code secret-import-1x (--env=<file> | --stdin)}: moves the two secrets of a 1.x installation into the secret store, so that
 * the clients of the old server and the admin sign-in keep working after the migration. {@code DBHUB_AUTH_TOKEN} (the bearer
 * token of the agents) becomes {@code ledger.mcp.token} and {@code ledger.admin.agent-token}, {@code PGLEDGER_ADMIN_TOKEN}
 * becomes {@code ledger.admin.token}. The other settings of the 1.x environment are not read.
 *
 * <p>All values are checked before anything is written (present, at least {@value #MINIMUM_LENGTH} characters, the admin
 * token different from the agent token). A secret that is stored already is kept and never replaced, so a run that stopped
 * half way is completed by the next one. Only the names of the secrets and of the 1.x settings are printed, never a value, and
 * no refusal holds a value. The command does not use the database.
 *
 * <p>The 1.x file is owned by root with mode 0600 while the secret store belongs to the service account, so the installer
 * opens the file as root and gives it to this command on standard input ({@code --stdin}, at most {@value #MAXIMUM_BYTES}
 * bytes, the file read to its end); {@code --env=<file>} is for an operator who can read the file as the service account.
 */
@Component
public class SecretImport1xCommand implements Command {

    static final String ENV = "env";
    static final String STDIN = "stdin";

    static final String AGENT_KEY = "DBHUB_AUTH_TOKEN";
    static final String ADMIN_KEY = "PGLEDGER_ADMIN_TOKEN";
    /** A 1.x token is 64 hexadecimal characters; anything under this is not a token that was generated. */
    static final int MINIMUM_LENGTH = 16;
    /** The 1.x file has a few lines; this bounds what a wrong input can make the program hold. */
    static final int MAXIMUM_BYTES = 64 * 1024;

    private final SecretStore store;
    private final StandardInput standardInput;
    private final CliOutput output;
    private final CliMessages messages;

    SecretImport1xCommand(SecretStore store, StandardInput standardInput, CliOutput output, CliMessages messages) {
        this.store = store;
        this.standardInput = standardInput;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.SECRET_IMPORT_1X;
    }

    @Override
    public Set<String> options() {
        return Set.of(ENV, STDIN);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        var file = CliOptions.value(arguments, ENV);
        boolean fromStandardInput = CliOptions.flag(arguments, STDIN);
        if (file.isPresent() && fromStandardInput) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        if (file.isEmpty() && !fromStandardInput) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        OneXEnvironment environment = OneXEnvironment.parse(
                fromStandardInput ? readStandardInput(standardInput.stream()) : readFile(Path.of(file.get())));
        String agent = environment.value(AGENT_KEY).orElse(null);
        String admin = environment.value(ADMIN_KEY).orElse(null);
        requirePresent(agent, admin);
        requireUsable(agent, admin);
        // the secrets of the new installation, each with the 1.x setting it comes from; the order is the order of the output
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("ledger.mcp.token", AGENT_KEY);
        targets.put("ledger.admin.agent-token", AGENT_KEY);
        targets.put("ledger.admin.token", ADMIN_KEY);
        // every secret is looked at before the first is written: a store that cannot be used refuses with nothing written
        Map<SecretName, Boolean> present = new LinkedHashMap<>();
        targets.keySet().forEach(name -> present.put(new SecretName(name), store.exists(new SecretName(name))));
        for (Map.Entry<SecretName, Boolean> entry : present.entrySet()) {
            String name = entry.getKey().value();
            if (entry.getValue()) {
                output.info(messages.get("secret-import-1x.kept", name));
                continue;
            }
            char[] value = (AGENT_KEY.equals(targets.get(name)) ? agent : admin).toCharArray();
            try {
                store.store(entry.getKey(), value);
            } finally {
                Arrays.fill(value, '\0');
            }
            output.info(messages.get("secret-import-1x.stored", name, targets.get(name)));
        }
    }

    private static void requirePresent(String agent, String admin) {
        List<String> missing = new ArrayList<>();
        if (agent == null) {
            missing.add(AGENT_KEY);
        }
        if (admin == null) {
            missing.add(ADMIN_KEY);
        }
        if (!missing.isEmpty()) {
            throw new FailureException(ErrorCode.ENV_KEYS_MISSING, String.join(", ", missing));
        }
    }

    private static void requireUsable(String agent, String admin) {
        if (agent.length() < MINIMUM_LENGTH) {
            throw new FailureException(ErrorCode.INVALID_1X_VALUE, AGENT_KEY);
        }
        // the two tokens guard different things (the agents, the owner's sign-in): one value for both is refused, not carried over
        if (admin.length() < MINIMUM_LENGTH || admin.equals(agent)) {
            throw new FailureException(ErrorCode.INVALID_1X_VALUE, ADMIN_KEY);
        }
    }

    /** The file; only the kind of a failure is reported, never the text of an exception (it could hold what was read). */
    private static String readFile(Path file) {
        try (InputStream stream = Files.newInputStream(file)) {
            return decode(stream.readNBytes(MAXIMUM_BYTES + 1), ErrorCode.ENV_FILE_FAILED);
        } catch (IOException e) {
            throw new FailureException(ErrorCode.ENV_FILE_FAILED, e.getClass().getSimpleName());
        }
    }

    private static String readStandardInput(InputStream stream) {
        try {
            return decode(stream.readNBytes(MAXIMUM_BYTES + 1), ErrorCode.STDIN_FAILED);
        } catch (IOException e) {
            throw new FailureException(ErrorCode.STDIN_FAILED, e.getClass().getSimpleName());
        }
    }

    /** UTF-8, nothing replaced; the bytes are wiped once decoded. A text over the bound is not an environment file of 1.x. */
    private static String decode(byte[] bytes, ErrorCode failure) {
        try {
            if (bytes.length > MAXIMUM_BYTES) {
                throw new FailureException(failure, "TooLarge");
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new FailureException(failure, e.getClass().getSimpleName());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
