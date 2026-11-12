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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * becomes {@code ledger.admin.token}. The other settings of the 1.x environment are not read. {@code PGLEDGER_ADMIN_TOKEN} is
 * optional: a 1.0.0 installation had no admin web and its environment has no such setting; then only the two secrets of the
 * agents are stored, one line says that {@code ledger.admin.token} was not imported, and the installer's next step
 * ({@code token-generate ledger.admin.token --print}) creates it and shows it once.
 *
 * <p>All values are checked before anything is written ({@code DBHUB_AUTH_TOKEN} present; what is present at least
 * {@value #MINIMUM_LENGTH} characters, the admin token different from the agent token). A secret that is stored already is kept and never replaced, so a run that stopped
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

    /** The two settings of the 1.x environment that are read: the name in the file, in the order they are checked and reported. */
    enum OneXKey {
        /** The bearer token of the agents. */
        AGENT("DBHUB_AUTH_TOKEN"),
        /** The sign-in token of the admin web. */
        ADMIN("PGLEDGER_ADMIN_TOKEN");

        private final String setting;

        OneXKey(String setting) {
            this.setting = setting;
        }

        String setting() {
            return setting;
        }
    }

    /** The secrets of the new installation, each with the 1.x setting it comes from; the order is the order of the output. */
    enum ImportedSecret {
        MCP_TOKEN("ledger.mcp.token", OneXKey.AGENT),
        ADMIN_AGENT_TOKEN("ledger.admin.agent-token", OneXKey.AGENT),
        ADMIN_TOKEN("ledger.admin.token", OneXKey.ADMIN);

        private final SecretName secret;
        private final OneXKey source;

        ImportedSecret(String name, OneXKey source) {
            this.secret = new SecretName(name);
            this.source = source;
        }

        SecretName secret() {
            return secret;
        }

        OneXKey source() {
            return source;
        }
    }

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
        Optional<String> file = CliOptions.value(arguments, ENV);
        boolean fromStandardInput = CliOptions.flag(arguments, STDIN);
        if (file.isPresent() && fromStandardInput) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        if (file.isEmpty() && !fromStandardInput) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        // no file means --stdin (checked above); standard input is read only then
        String text = file.map(name -> readFile(Path.of(name)))
                .orElseGet(() -> readStandardInput(standardInput.stream()));
        OneXEnvironment environment = OneXEnvironment.parse(text);
        Map<OneXKey, String> values = new EnumMap<>(OneXKey.class);
        for (OneXKey key : OneXKey.values()) {
            environment.value(key.setting()).ifPresent(value -> values.put(key, value));
        }
        requireAgentToken(values);
        requireUsable(values);
        // every secret is looked at before the first is written: a store that cannot be used refuses with nothing written
        Map<ImportedSecret, Boolean> present = new EnumMap<>(ImportedSecret.class);
        for (ImportedSecret imported : ImportedSecret.values()) {
            present.put(imported, store.exists(imported.secret()));
        }
        for (ImportedSecret imported : ImportedSecret.values()) {
            String name = imported.secret().value();
            if (present.get(imported)) {
                output.info(messages.get("secret-import-1x.kept", name));
                continue;
            }
            if (!values.containsKey(imported.source())) {
                // a 1.0.0 installation had no admin web, so its environment has no admin token: the installer generates one
                output.info(messages.get("secret-import-1x.no-admin", imported.source().setting(), name));
                continue;
            }
            char[] value = values.get(imported.source()).toCharArray();
            try {
                store.store(imported.secret(), value);
            } finally {
                Arrays.fill(value, '\0');
            }
            output.info(messages.get("secret-import-1x.stored", name, imported.source().setting()));
        }
    }

    /** The token of the agents is required (its name is the one named, never a value); the admin token is optional. */
    private static void requireAgentToken(Map<OneXKey, String> values) {
        if (!values.containsKey(OneXKey.AGENT)) {
            throw new FailureException(ErrorCode.ENV_KEYS_MISSING, OneXKey.AGENT.setting());
        }
    }

    /** What is present is checked; the admin token only when the 1.x environment has one. */
    private static void requireUsable(Map<OneXKey, String> values) {
        String agent = values.get(OneXKey.AGENT);
        if (agent.length() < MINIMUM_LENGTH) {
            throw new FailureException(ErrorCode.INVALID_1X_VALUE, OneXKey.AGENT.setting());
        }
        // absent for a 1.0.0 installation (no admin web); the two tokens guard different things (the agents, the owner's
        // sign-in): one value for both is refused, not carried over
        String admin = values.get(OneXKey.ADMIN);
        if (admin != null && (admin.length() < MINIMUM_LENGTH || Objects.equals(admin, agent))) {
            throw new FailureException(ErrorCode.INVALID_1X_VALUE, OneXKey.ADMIN.setting());
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
