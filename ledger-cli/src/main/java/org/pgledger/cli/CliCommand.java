package org.pgledger.cli;

import java.util.Objects;
import java.util.Optional;

/** The commands this tool offers; the usage text lists them in this order. */
public enum CliCommand {

    SECRET_SET("secret-set"),
    TOKEN_GENERATE("token-generate"),
    DRIVER_REGISTER("driver-register"),
    MIGRATE("migrate"),
    IMPORT_LEGACY("import-legacy"),
    KEY_REGISTER("key-register"),
    KEY_REVOKE("key-revoke"),
    PROJECT_ADD("project-add"),
    CODE_SET("code-set"),
    CODES("codes"),
    GRANT("grant"),
    REVOKE("revoke"),
    GRANTS("grants"),
    MCP_CHECK("mcp-check"),
    RENDER_DEPLOY("render-deploy");

    private final String cliName;

    CliCommand(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    /** Message key of this command line in the usage text. */
    public String usageKey() {
        return "usage." + cliName;
    }

    /** The one place where a command line word becomes a command. */
    public static Optional<CliCommand> parse(String word) {
        for (CliCommand command : values()) {
            if (Objects.equals(command.cliName, word)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }
}
