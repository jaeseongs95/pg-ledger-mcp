package org.pgledger.mcp.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the MCP server ({@code ledger.mcp.*}). The token is not part of any configuration file: in a
 * deployment it arrives through the secret store (a configtree property source), so its value is never printed.
 *
 * @param token            the bearer token the agents present (at least 16 characters). It is not checked by Bean
 *                         Validation: Boot prints the rejected value of a binding violation, and this one is a secret;
 *                         {@code AgentTokenProvider} checks it and names only the key
 * @param allowedHosts     host names a request may carry in its Host header (case does not matter)
 * @param allowedOrigins   origins a browser request may carry; a request without an Origin header is not a browser request
 * @param maxRequestSize   the largest request body
 * @param codePollInterval how often the common-code revision is read to notice changes made by another process
 */
@ConfigurationProperties(prefix = "ledger.mcp")
public record LedgerMcpProperties(String token,
        @DefaultValue({"127.0.0.1", "localhost"}) List<String> allowedHosts,
        @DefaultValue List<String> allowedOrigins, @DefaultValue("1MB") DataSize maxRequestSize,
        @DefaultValue("5s") Duration codePollInterval) {

    public LedgerMcpProperties {
        allowedHosts = List.copyOf(allowedHosts);
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    /** The token is a secret: it never appears in a log line or a failure text made from this record. */
    @Override
    public String toString() {
        return "LedgerMcpProperties[token=<hidden>, allowedHosts=" + allowedHosts + ", allowedOrigins="
                + allowedOrigins + ", maxRequestSize=" + maxRequestSize + ", codePollInterval=" + codePollInterval + "]";
    }
}
