package org.pgledger.cli.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.condition.OS.LINUX;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** Test-only stand-in for the server-side configuration records that will consume the secret directory. */
class ConfigTreeBindingTest {

    @ConfigurationProperties("ledger.mcp")
    record McpSecrets(String bearerToken, String dbPassword) {

        McpSecrets {
            Objects.requireNonNull(bearerToken, "ledger.mcp.bearer-token is required");
            Objects.requireNonNull(dbPassword, "ledger.mcp.db-password is required");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpSecrets.class)
    static class BindingConfiguration {
    }

    @TempDir
    Path tmp;

    private static ConfigurableApplicationContext start(Path secrets) {
        return new SpringApplicationBuilder(BindingConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.import=configtree:" + secrets.toString().replace('\\', '/') + "/")
                .run();
    }

    @Test
    @EnabledOnOs(LINUX)
    @DisplayName("SEC-007 files written by the secret store are bound by configtree into a ConfigurationProperties record")
    void sec007_configtreeRoundTrip() {
        Path secrets = tmp.resolve("secrets");
        SecretStore store = new SecretStore(new SecretsProperties("pg-ledger-mcp", secrets));
        store.store(new SecretName("ledger.mcp.bearer-token"), "bearer-é-VALUE-1".toCharArray());
        store.store(new SecretName("ledger.mcp.db-password"), "db-VALUE-2".toCharArray());

        try (ConfigurableApplicationContext context = start(secrets)) {
            McpSecrets bound = context.getBean(McpSecrets.class);

            assertThat(bound.bearerToken()).isEqualTo("bearer-é-VALUE-1");
            assertThat(bound.dbPassword()).isEqualTo("db-VALUE-2");
        }
    }

    @Test
    @EnabledOnOs(LINUX)
    @DisplayName("SEC-007 a missing required secret fails startup naming the property and never a value")
    void sec007_missingSecretFailsStartupWithoutValues() throws IOException {
        Path secrets = tmp.resolve("secrets");
        new SecretStore(new SecretsProperties("pg-ledger-mcp", secrets))
                .store(new SecretName("ledger.mcp.bearer-token"), "bearer-VALUE-1".toCharArray());

        assertThatThrownBy(() -> start(secrets).close())
                .hasStackTraceContaining("ledger.mcp.db-password")
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain("bearer-VALUE-1"));
        assertThat(Files.readString(secrets.resolve("ledger.mcp.bearer-token"))).isEqualTo("bearer-VALUE-1");
    }

    private static String stackTrace(Throwable e) {
        java.io.StringWriter text = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }
}
