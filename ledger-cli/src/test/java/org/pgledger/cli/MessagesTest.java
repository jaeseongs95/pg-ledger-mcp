package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MessagesTest {

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource(name).getInputStream()) {
            properties.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    @DisplayName("CLI-001 every error code, command and fixed text has a message in the default and the Korean bundle")
    void everyKeyIsDefinedInBothBundles() throws IOException {
        List<String> required = new ArrayList<>(List.of("usage.header", "secret-set.prompt", "secret-set.stored",
                "key-register.registered", "key-register.replaced", "key-register.server", "key-revoke.revoked",
                "migrate.applied", "migrate.current", "import-legacy.table", "import-legacy.imported", "import-legacy.verified",
                "import-legacy.violation", "import-legacy.mismatch", "driver-register.registered", "project-add.added", "code-set.set",
                "codes.line", "grant.issued", "grant.never", "revoke.revoked", "grants.none", "grants.line", "mcp-check.ok"));
        for (ErrorCode code : ErrorCode.values()) {
            required.add(code.messageKey());
        }
        for (CliCommand command : CliCommand.values()) {
            required.add(command.usageKey());
        }

        Properties base = load("messages.properties");
        Properties korean = load("messages_ko.properties");

        assertThat(base.stringPropertyNames()).containsAll(required);
        assertThat(korean.stringPropertyNames()).isEqualTo(base.stringPropertyNames());
    }

    @Test
    @DisplayName("SEC-013 the foreign-owner message tells the operator to run secret-set as the service account")
    void foreignOwnerMessageGivesTheSudoGuidance() throws IOException {
        String key = ErrorCode.FOREIGN_OWNER.messageKey();

        assertThat(load("messages.properties").getProperty(key)).contains("sudo -u").contains("ledger-cli secret-set");
        assertThat(load("messages_ko.properties").getProperty(key)).contains("sudo -u").contains("ledger-cli secret-set");
    }
}
